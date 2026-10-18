package com.kantv.app.catalog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kantv.app.diagnostics.HubFeedbackUploadWorker
import com.kantv.app.playback.PlaybackActivityFlag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 刷新目录：**枢纽订阅为首选来源**，其后才是用户自建的 M3U 订阅。
 * 两条通道都保留旧目录：任何失败都不清空已有频道。
 */
class PlaylistRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val force = inputData.getBoolean(PlaylistRefreshScheduler.FORCE_KEY, false)
        val repository = HubWiring.repository(applicationContext)
        HubRefreshWorkerSupport.refresh(
            context = applicationContext,
            repository = repository,
            force = force,
            playbackActive = PlaybackActivityFlag.isActive(),
        )
        PlaylistRefreshRunner.refresh(
            repository = repository,
            force = force,
            // 无感标准：用户在看直播时不抢带宽（多订阅串行拉取可能拖慢高码率直播），
            // 也避免用户自己的列表在观看中被改写。延后到下一次周期即可。
            playbackActive = PlaybackActivityFlag.isActive(),
        )
        // 回流上报只做「把队列发出去」，不占用前台。
        HubFeedbackUploadWorker.schedule(applicationContext)
        Result.success()
    }
}

/** 枢纽相关对象的唯一装配点，保证 Worker / 前台 / 探测程序使用同一套实现。 */
internal object HubWiring {
    fun repository(context: Context): CatalogRepository = CatalogRepository(
        CatalogDatabase.get(context.applicationContext),
        SecretBox(),
        aliasTableProvider = { ChannelAliasStore.of(context.applicationContext).current() },
        hubRoutes = HubRouteRegistry(HubWiring.store(context)),
    )

    @Volatile
    private var sharedStore: HubSubscriptionStore? = null

    fun store(context: Context): HubSubscriptionStore =
        // 进程级单例：Worker 写入的新 payload 必须对前台/探测程序立即可见，
        // 否则各实例的私有缓存会把文件覆盖回旧内容。
        sharedStore ?: synchronized(this) {
            sharedStore ?: HubSubscriptionStore(SharedPreferencesHubSubscriptionPersistence(context.applicationContext))
                .also { sharedStore = it }
        }
}

/** 投递通道的执行封装：只为复用与可读性存在，本身不含业务策略。 */
internal object HubRefreshWorkerSupport {
    suspend fun refresh(
        context: Context,
        repository: CatalogRepository,
        force: Boolean,
        playbackActive: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): HubRefreshOutcome {
        val configuration = HubConfig.read(context)
        if (!HubEndpointPolicy.validEndpoint(configuration.endpoint)) {
            return HubRefreshOutcome.Skipped("ENDPOINT_INVALID")
        }
        val coordinator = HubSubscriptionCoordinator(
            store = HubWiring.store(context),
            client = HubSubscriptionClient(
                endpoint = configuration.endpoint,
                identity = com.kantv.app.diagnostics.HubFeedbackIdentity.of(context),
            ),
            sink = repository,
            aliasCache = ChannelAliasStore.of(context),
            onProtocolNote = { notice -> HubProtocolNotices.record(context, notice) },
            // P0-2 方案 C：名单指纹变化只留痕（客户端无跨启动持久的 url_key 归因缓存，
            // 指纹在 signed_params 内 -> ETag 变化 -> 下次刷新必拉新 payload 自愈）。
            onSignedParamsFingerprintChange = { previous, current ->
                android.util.Log.i(
                    "KanTvHub",
                    "signed_params fingerprint changed: $previous -> $current; " +
                        "server url_key semantics switched, next refresh re-fetches payload",
                )
            },
        )
        return coordinator.refresh(force = force, playbackActive = playbackActive, nowMs = nowMs)
    }
}

/**
 * 协议级事件（v1.1 §4 `notices[]`）的统一处理点：**本地留痕 + 排队上报**。
 *
 * v1 的结构性矛盾（§5.2 要求上报、§4 无字段承载）已由 v1.1 的 `notices[]` 解决，
 * 因此这里不再只做本地留痕。
 */
object HubProtocolNotices {
    fun record(context: Context, notice: com.kantv.app.catalog.HubNotice) {
        HubProtocolNotes.record(context, notice.code)
        HubFeedbackUploadWorker.reportNotice(context, notice)
    }
}

/** 协议级事件的本地副本（便于在设置页/诊断中展示；上报走 [HubProtocolNotices]）。 */
object HubProtocolNotes {
    private const val PREFS = "hub_protocol_notes"
    private const val KEY_LAST = "last_note"
    private const val KEY_COUNT_PREFIX = "count_"

    fun record(context: Context, code: String?) {
        if (code.isNullOrBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_COUNT_PREFIX + code, 0) + 1
        prefs.edit().putString(KEY_LAST, code).putInt(KEY_COUNT_PREFIX + code, count).apply()
    }

    fun last(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)
}

/**
 * Refresh core kept independent of WorkManager so debug probes can run the same batch behavior
 * against an isolated catalog with a synthetic loader.
 */
internal object PlaylistRefreshRunner {
    private val refreshMutex = Mutex()

    suspend fun refresh(
        repository: CatalogRepository,
        force: Boolean,
        playbackActive: Boolean = false,
        loader: (String) -> String = { PlaylistLoader().fromUrl(it) },
        nowMs: () -> Long = System::currentTimeMillis,
    ) {
        // 播放中不刷新用户订阅：避免与直播抢带宽，也避免列表在观看时被改写。
        if (playbackActive && !force) return
        refreshMutex.withLock {
            // Read after acquiring the process-wide lock: a periodic work item that was waiting
            // behind a manual refresh sees the new attempt timestamp and skips it.
            repository.subscriptions().forEach { subscription ->
                val startedAtMs = nowMs()
                if (!force && !PlaylistRefreshPolicy.shouldRefresh(subscription, startedAtMs)) return@forEach
                refreshOne(repository, subscription.id, startedAtMs, loader)
            }
        }
    }

    private suspend fun refreshOne(
        repository: CatalogRepository,
        subscriptionId: String,
        startedAtMs: Long,
        loader: (String) -> String,
    ) {
        try {
            // An attempt write failure is isolated to this subscription like a network failure.
            repository.recordSubscriptionAttempt(subscriptionId, startedAtMs)
            // The repository decrypts only for this request. Do not log, return, or persist this URL.
            val refreshUrl = repository.refreshUrl(subscriptionId)
            if (refreshUrl == null) {
                recordFailure(repository, subscriptionId, PlaylistRefreshStatus.SUBSCRIPTION_MISSING, startedAtMs)
                return
            }
            val playlist = loader(refreshUrl)
            currentCoroutineContext().ensureActive()
            when (repository.import(playlist, refreshUrl, startedAtMs, userInitiated = false)) {
                is CatalogImportResult.Imported -> Unit
                is CatalogImportResult.Rejected -> recordFailure(
                    repository,
                    subscriptionId,
                    PlaylistRefreshStatus.IMPORT_REJECTED,
                    startedAtMs,
                )
                is CatalogImportResult.Preserved -> recordFailure(
                    repository,
                    subscriptionId,
                    PlaylistRefreshStatus.IMPORT_PRESERVED,
                    startedAtMs,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            recordFailure(repository, subscriptionId, PlaylistRefreshStatus.from(failure), startedAtMs)
        }
    }

    private suspend fun recordFailure(
        repository: CatalogRepository,
        subscriptionId: String,
        status: String,
        startedAtMs: Long,
    ) {
        try {
            currentCoroutineContext().ensureActive()
            repository.recordSubscriptionFailure(subscriptionId, status, startedAtMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The next subscription must still run if this subscription's status write is unavailable.
        }
    }
}

/** Pure timing and safe status policy: no exception text, URL, headers, or credentials escape here. */
object PlaylistRefreshPolicy {
    const val REFRESH_INTERVAL_MS = 60L * 60L * 1_000L

    fun shouldCatchUp(subscriptions: List<PlaylistSubscriptionEntity>, nowMs: Long): Boolean =
        subscriptions.any { shouldRefresh(it, nowMs) }

    fun shouldRefresh(subscription: PlaylistSubscriptionEntity, nowMs: Long): Boolean =
        isStale(subscription.lastAttemptAtMs, nowMs) && isStale(subscription.lastSuccessAtMs, nowMs)

    private fun isStale(timestampMs: Long?, nowMs: Long): Boolean =
        timestampMs == null || timestampMs <= 0L || nowMs - timestampMs >= REFRESH_INTERVAL_MS
}

object PlaylistRefreshStatus {
    const val SUBSCRIPTION_MISSING = "SUBSCRIPTION_MISSING"
    const val IMPORT_REJECTED = "IMPORT_REJECTED"
    const val IMPORT_PRESERVED = "IMPORT_PRESERVED"
    const val PLAYLIST_TOO_LARGE = "PLAYLIST_TOO_LARGE"
    const val PLAYLIST_INVALID = "PLAYLIST_INVALID"
    const val URL_INVALID = "URL_INVALID"
    const val NETWORK_TIMEOUT = "NETWORK_TIMEOUT"
    const val NETWORK_UNAVAILABLE = "NETWORK_UNAVAILABLE"
    const val NETWORK_IO = "NETWORK_IO"
    const val NETWORK_BLOCKED = "NETWORK_BLOCKED"

    fun from(failure: Throwable): String = when (failure) {
        is PlaylistLoadException.HttpStatus -> "HTTP_${failure.status.coerceIn(100, 599)}"
        PlaylistLoadException.TooLarge -> PLAYLIST_TOO_LARGE
        is MalformedURLException -> URL_INVALID
        is SocketTimeoutException -> NETWORK_TIMEOUT
        is UnknownHostException, is ConnectException, is SSLException -> NETWORK_UNAVAILABLE
        is SecurityException -> NETWORK_BLOCKED
        is java.io.IOException -> NETWORK_IO
        else -> PLAYLIST_INVALID
    }
}
