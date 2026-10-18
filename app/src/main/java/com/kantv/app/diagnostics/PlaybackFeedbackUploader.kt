package com.kantv.app.diagnostics

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kantv.app.catalog.FeedbackEngine
import com.kantv.app.catalog.FeedbackFailReason
import com.kantv.app.catalog.FeedbackOutcome
import com.kantv.app.catalog.HubClientIdentity
import com.kantv.app.catalog.HubConfig
import com.kantv.app.catalog.HubEndpointPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/** POST 的传输层端口；抽出便于单测节流与失败路径，不依赖真实网络。 */
interface FeedbackTransport {
    /** 返回状态码与响应体；网络异常直接抛出，由调用方归类为固定状态码。 */
    fun post(uri: String, token: String, body: String): FeedbackHttpResponse
}

/**
 * 一次 POST 的结果。
 *
 * [body] 只用于读取服务端在 202 里回的契约自检计数
 * （`freeze_ok` / `freeze_violation` / `freeze_partial`），有界读取、不参与判定成败。
 */
data class FeedbackHttpResponse(val code: Int, val body: String = "")

class HttpUrlConnectionFeedbackTransport(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) : FeedbackTransport {
    override fun post(uri: String, token: String, body: String): FeedbackHttpResponse {
        // 连接配置共享于 PostJsonBearer；Accept/useCaches/字节数口径为本侧历史语义。
        val connection = openPostJsonBearerConnection(
            uri = uri,
            token = token,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            sendAcceptJson = true,
            disableCaches = true,
            bodyLength = body.toByteArray(Charsets.UTF_8).size.toLong(),
        )
        try {
            connection.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return FeedbackHttpResponse(code, readBounded(stream))
        } finally {
            connection.disconnect()
        }
    }

    /** 有界读取：响应体只用于自检计数，绝不因为服务端多说几句就把内存吃掉。 */
    private fun readBounded(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return try {
            stream.use { input ->
                val buffer = ByteArray(MAX_RESPONSE_BYTES)
                var filled = 0
                while (filled < buffer.size) {
                    val read = input.read(buffer, filled, buffer.size - filled)
                    if (read <= 0) break
                    filled += read
                }
                String(buffer, 0, filled, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 8 * 1024
    }
}

/** 待上报队列的持久化端口。 */
interface FeedbackQueuePersistence {
    fun read(): String?
    fun write(value: String)
}

class InMemoryFeedbackQueuePersistence(private var value: String? = null) : FeedbackQueuePersistence {
    override fun read(): String? = value

    override fun write(value: String) {
        this.value = value
    }
}

class SharedPreferencesFeedbackQueuePersistence(context: Context) : FeedbackQueuePersistence {
    private val prefs = context.applicationContext.getSharedPreferences("hub_feedback", Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(value: String) {
        prefs.edit().putString(KEY, value).apply()
    }

    private companion object {
        const val KEY = "pending_results"
    }
}

/** 协议级 notices 的持久化端口（与 results 分开存放，互不影响）。 */
interface FeedbackNoticePersistence {
    fun read(): String?
    fun write(value: String)
}

class InMemoryFeedbackNoticePersistence(private var value: String? = null) : FeedbackNoticePersistence {
    override fun read(): String? = value

    override fun write(value: String) {
        this.value = value
    }
}

class SharedPreferencesFeedbackNoticePersistence(context: Context) : FeedbackNoticePersistence {
    private val prefs = context.applicationContext.getSharedPreferences("hub_feedback", Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(value: String) {
        prefs.edit().putString(KEY, value).apply()
    }

    private companion object {
        const val KEY = "pending_notices"
    }
}

/**
 * 待上报的协议级 notices（§4 / §5.2）。
 *
 * v1 的结构性矛盾是「§5.2 要求上报、§4 却没有字段承载且禁止 channel_id 为 null 的 results」；
 * v1.1 新增 `notices[]` 后，这类事件有了合法出口。
 */
class FeedbackNoticeQueue(
    private val persistence: FeedbackNoticePersistence = InMemoryFeedbackNoticePersistence(),
) {
    @Volatile private var cached: MutableList<com.kantv.app.catalog.HubNotice>? = null

    fun pending(): List<com.kantv.app.catalog.HubNotice> = load()

    fun enqueue(notice: com.kantv.app.catalog.HubNotice) {
        val current = load()
        // 同一 code + detail 只保留一条，避免刷屏式重复。
        if (current.none { it.code == notice.code && it.detail == notice.detail }) {
            current += notice
            save(current)
        }
    }

    fun removeFirst(count: Int) {
        if (count <= 0) return
        val current = load()
        if (count >= current.size) {
            save(emptyList())
            return
        }
        save(current.subList(count, current.size).toMutableList())
    }

    fun size(): Int = load().size

    private fun load(): MutableList<com.kantv.app.catalog.HubNotice> {
        cached?.let { return it }
        val decoded = FeedbackNoticeCodec.decode(persistence.read()).toMutableList()
        cached = decoded
        return decoded
    }

    private fun save(notices: List<com.kantv.app.catalog.HubNotice>) {
        cached = notices.toMutableList()
        persistence.write(FeedbackNoticeCodec.encode(notices))
    }
}

internal object FeedbackNoticeCodec {
    fun encode(notices: List<com.kantv.app.catalog.HubNotice>): String = JSONArray().also { array ->
        notices.forEach { notice ->
            array.put(JSONObject().put("code", notice.code).put("detail", notice.detail))
        }
    }.toString()

    fun decode(payload: String?): List<com.kantv.app.catalog.HubNotice> {
        if (payload.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(payload)
            (0 until array.length()).mapNotNull { index ->
                val row = array.optJSONObject(index) ?: return@mapNotNull null
                val code = row.optString("code")
                if (code.isEmpty()) null
                else com.kantv.app.catalog.HubNotice(code, row.optString("detail"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * 待上报结果队列：内存 + 持久化，进程重启后仍能上报。
 *
 * 只保存 `url_key`（哈希）与固定枚举字段，**不含原始 URL**（见 [PlaybackFeedbackResult]）。
 */
class PlaybackFeedbackQueue(
    private val persistence: FeedbackQueuePersistence = InMemoryFeedbackQueuePersistence(),
) {
    private var cached: MutableList<PlaybackFeedbackResult>? = null

    fun pending(): List<PlaybackFeedbackResult> = load()

    fun enqueue(result: PlaybackFeedbackResult) {
        val current = load()
        current += result
        save(current)
    }

    fun removeAll(results: List<PlaybackFeedbackResult>) {
        val current = load()
        current.removeAll(results)
        save(current)
    }

    /**
     * 移除队首的 [count] 条。
     *
     * 必须按**位置**而不是按值移除：同一频道的多轮结果字段可能完全相同，
     * 按值 `removeAll` 会把重复项一起删掉（单测 `batch never exceeds the configured maximum`
     * 正是覆盖这个缺陷）。
     */
    fun removeFirst(count: Int) {
        if (count <= 0) return
        val current = load()
        if (count >= current.size) {
            save(emptyList())
            return
        }
        save(current.subList(count, current.size).toMutableList())
    }

    /** 是否存在失败结果（决定是否立即上报）。 */
    fun hasPendingFailure(): Boolean = load().any { it.outcome == FeedbackOutcome.FAIL }

    fun size(): Int = load().size

    private fun load(): MutableList<PlaybackFeedbackResult> {
        cached?.let { return it }
        val decoded = FeedbackQueueCodec.decode(persistence.read()).toMutableList()
        cached = decoded
        return decoded
    }

    private fun save(results: List<PlaybackFeedbackResult>) {
        cached = results.toMutableList()
        persistence.write(FeedbackQueueCodec.encode(results))
    }
}

internal object FeedbackQueueCodec {
    fun encode(results: List<PlaybackFeedbackResult>): String = JSONArray().also { array ->
        results.forEach { result ->
            array.put(
                JSONObject()
                    .put("channel_id", result.channelId)
                    .put("url_key", result.urlKey)
                    .put("route", result.route)
                    .put("outcome", result.outcome.wireName)
                    .put("engine", result.engine.wireName)
                    .put("first_frame_ms", result.firstFrameMs ?: JSONObject.NULL)
                    .put("stalls", result.stalls)
                    .put("stall_seconds", result.stallSeconds)
                    .put("play_seconds", result.playSeconds)
                    .put("fail_reason", result.failReason?.wireName ?: JSONObject.NULL)
                    .put("observed_at", result.observedAt)
                    .apply {
                        // 冻结口径：整组给齐或整组不给（旧版本写入的队列记录没有这一组）。
                        if (result.freezeMeasured) {
                            // 成组五个字段 + 独立可选的长冻结段数（-1 = 本次取不到，只省它一个）。
                            put("active_seconds", result.activeSeconds)
                            put("freeze_seconds", result.freezeSeconds)
                            put("freeze_segments", result.freezeSegments)
                            put("freeze_longest_seconds", result.freezeLongestSeconds)
                            put("picture_progress_events", result.pictureProgressEvents)
                            if (result.freezeLongSegments >= 0) {
                                put("freeze_long_segments", result.freezeLongSegments)
                            }
                        }
                    },
            )
        }
    }.toString()

    fun decode(payload: String?): List<PlaybackFeedbackResult> {
        if (payload.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(payload)
            (0 until array.length()).mapNotNull { index ->
                val row = array.optJSONObject(index) ?: return@mapNotNull null
                val channelId = row.optString("channel_id")
                val urlKey = row.optString("url_key")
                if (channelId.isEmpty() || urlKey.isEmpty()) return@mapNotNull null
                PlaybackFeedbackResult(
                    channelId = channelId,
                    urlKey = urlKey,
                    route = row.optString("route"),
                    outcome = row.optString("outcome").let { wire ->
                        FeedbackOutcome.entries.firstOrNull { it.wireName == wire }
                    } ?: return@mapNotNull null,
                    engine = row.optString("engine").let { wire ->
                        FeedbackEngine.entries.firstOrNull { it.wireName == wire }
                    } ?: FeedbackEngine.MEDIA3,
                    firstFrameMs = if (row.isNull("first_frame_ms")) null else row.optLong("first_frame_ms"),
                    stalls = row.optInt("stalls", 0),
                    stallSeconds = row.optLong("stall_seconds", 0L),
                    playSeconds = row.optLong("play_seconds", 0L),
                    failReason = row.optString("fail_reason").takeIf { it.isNotEmpty() }
                        ?.let { wire -> FeedbackFailReason.entries.firstOrNull { it.wireName == wire } },
                    observedAt = row.optString("observed_at"),
                    activeSeconds = row.optLong("active_seconds", 0L),
                    freezeSeconds = row.optLong("freeze_seconds", 0L),
                    freezeSegments = row.optInt("freeze_segments", 0),
                    freezeLongestSeconds = row.optLong("freeze_longest_seconds", 0L),
                    freezeLongSegments = row.optInt("freeze_long_segments", -1),
                    pictureProgressEvents = row.optInt("picture_progress_events", 0),
                    // 只有真的采到这一组才算"已采集"：旧队列记录缺键 → 整组不上报。
                    freezeMeasured = row.has("active_seconds"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * 从 202 响应体里取一个自检计数。解析失败或字段缺失一律返回 0——
 * 这只是一条自检线索，绝不因为它改变"结果是否已上报成功"的判定。
 */
internal fun freezeCounter(body: String, key: String): Int {
    if (body.isBlank()) return 0
    return try {
        org.json.JSONObject(body).optInt(key, 0).coerceAtLeast(0)
    } catch (_: Exception) {
        0
    }
}

sealed interface FeedbackFlushOutcome {
    data class Uploaded(
        val accepted: Int,
        val notices: Int,
        /** 服务端契约自检计数（v9 起）：ok / 违反不变式 / 部分字段缺失。 */
        val freezeOk: Int = 0,
        val freezeViolation: Int = 0,
        val freezePartial: Int = 0,
    ) : FeedbackFlushOutcome

    /** 无需上报（无待发结果与 notices、未配对 token、地址非法或未到节流时点）。 */
    data class Skipped(val reason: String) : FeedbackFlushOutcome

    /** 服务端拒绝或网络失败；队列保留，等待下次。 */
    data class Rejected(val status: String) : FeedbackFlushOutcome
}

/**
 * 契约 §4 的回流上报。
 *
 * - 只上报 `url_key`，绝不上报原始 URL / 请求头 / 凭据。
 * - token 来自构建期注入（`BuildConfig.HUB_DEFAULT_TOKEN`），不硬编码、不落仓库。
 * - 失败立即上报；成功延迟聚合批量上报（[FeedbackThrottlePolicy]）。
 * - 本类不做线程调度：调用方（WorkManager Worker）负责「不占前台、不打断播放」。
 */
class PlaybackFeedbackUploader(
    private val endpoint: String,
    private val token: String,
    private val identity: HubClientIdentity,
    private val transport: FeedbackTransport,
    private val sessionId: String,
    private val androidApi: Int,
    private val abi: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zoneOffsetMinutes: () -> Int = { TimeZone.getDefault().rawOffset / 60_000 },
    private val lastFlushAtMs: () -> Long = { 0L },
) {
    fun flush(
        queue: PlaybackFeedbackQueue,
        notices: FeedbackNoticeQueue = FeedbackNoticeQueue(),
        force: Boolean = false,
    ): FeedbackFlushOutcome {
        val pending = queue.pending()
        val pendingNotices = notices.pending()
        if (pending.isEmpty() && pendingNotices.isEmpty()) return FeedbackFlushOutcome.Skipped("EMPTY")
        if (!HubEndpointPolicy.validEndpoint(endpoint)) return FeedbackFlushOutcome.Skipped("ENDPOINT_INVALID")
        if (!HubEndpointPolicy.validToken(token)) return FeedbackFlushOutcome.Skipped("TOKEN_MISSING")
        val now = nowMs()
        val batch = pending.take(FeedbackThrottlePolicy.BATCH_MAX_RESULTS)
        val noticeBatch = pendingNotices.take(FeedbackThrottlePolicy.BATCH_MAX_NOTICES)
        val immediate = queue.hasPendingFailure() || noticeBatch.isNotEmpty()
        if (!force && !FeedbackThrottlePolicy.shouldFlushNow(immediate, batch.size, lastFlushAtMs(), now)) {
            return FeedbackFlushOutcome.Skipped("THROTTLED")
        }
        val body = FeedbackPayloadEncoder.encode(
            session = sessionId,
            client = identity.client,
            app = identity.app,
            deviceModel = identity.device,
            androidApi = androidApi,
            abi = abi,
            reportedAt = HubTimestamps.iso8601(now, zoneOffsetMinutes()),
            results = batch,
            notices = noticeBatch,
        )
        val response = try {
            transport.post(HubEndpointPolicy.feedbackUri(endpoint), token, body)
        } catch (failure: Exception) {
            return FeedbackFlushOutcome.Rejected(com.kantv.app.catalog.HubSubscriptionStatus.from(failure))
        }
        if (response.code !in 200..299) {
            return FeedbackFlushOutcome.Rejected(com.kantv.app.catalog.HubSubscriptionStatus.http(response.code))
        }
        queue.removeFirst(batch.size)
        notices.removeFirst(noticeBatch.size)
        return FeedbackFlushOutcome.Uploaded(
            accepted = batch.size,
            notices = noticeBatch.size,
            freezeOk = freezeCounter(response.body, "freeze_ok"),
            freezeViolation = freezeCounter(response.body, "freeze_violation"),
            freezePartial = freezeCounter(response.body, "freeze_partial"),
        )
    }
}

/**
 * 回流上报 Worker：网络可用时把队列批量发出。
 * 契约 §4.1：回流只用于记录与只读聚合，不得作为删除/降级/拉黑任何源的依据。
 */
class HubFeedbackUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val configuration = HubConfig.read(applicationContext)
        val queue = PlaybackFeedbackQueue(SharedPreferencesFeedbackQueuePersistence(applicationContext))
        val notices = FeedbackNoticeQueue(SharedPreferencesFeedbackNoticePersistence(applicationContext))
        if (queue.size() == 0 && notices.size() == 0) return@withContext Result.success()
        val uploader = PlaybackFeedbackUploader(
            endpoint = configuration.endpoint,
            token = configuration.feedbackToken,
            identity = HubFeedbackIdentity.of(applicationContext),
            transport = HttpUrlConnectionFeedbackTransport(),
            sessionId = HubFeedbackIdentity.sessionId(applicationContext),
            androidApi = android.os.Build.VERSION.SDK_INT,
            abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        )
        when (val outcome = uploader.flush(queue, notices, force = queue.hasPendingFailure())) {
            is FeedbackFlushOutcome.Uploaded -> {
                // 服务端会回契约自检计数：违反不变式/部分字段缺失时在本机账本留痕，
                // 便于对照 "最近错误" 定位是我们报错了还是对方理解错了。
                if (outcome.freezeViolation > 0) {
                    DiagnosticLedgerHealth.recordError(applicationContext, "FREEZE_CONTRACT_VIOLATION")
                } else if (outcome.freezePartial > 0) {
                    DiagnosticLedgerHealth.recordError(applicationContext, "FREEZE_CONTRACT_PARTIAL")
                }
                Result.success()
            }
            is FeedbackFlushOutcome.Skipped -> Result.success()
            is FeedbackFlushOutcome.Rejected -> if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        internal const val WORK_NAME = "kantv-hub-feedback"

        /** 入队即触发一次；重复入队被 KEEP 合并，不会堆积。 */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<HubFeedbackUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * 记录并排队一条协议级 notice（v1.1 §4 `notices[]`）。
         *
         * `detail` 只允许写入固定说明，**不得**包含 URL、header 或凭据。
         */
        fun reportNotice(context: Context, notice: com.kantv.app.catalog.HubNotice) {
            FeedbackNoticeQueue(SharedPreferencesFeedbackNoticePersistence(context)).enqueue(notice)
            schedule(context)
        }
    }
}

/** 设备身份与会话标识；只使用非敏感信息。 */
object HubFeedbackIdentity {
    private const val PREFS = "hub_feedback_identity"
    private const val KEY_SESSION = "session_id"

    fun of(context: Context): HubClientIdentity = HubClientIdentity(
        client = "kantv",
        app = com.kantv.app.BuildConfig.VERSION_NAME,
        device = HubEndpointPolicy.deviceToken(android.os.Build.MODEL),
    )

    /** 进程级会话 uuid；缺失时生成并落库（只用于服务端聚合去重，不含任何个人标识）。 */
    fun sessionId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SESSION, null)?.let { return it }
        val generated = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_SESSION, generated).apply()
        return generated
    }
}
