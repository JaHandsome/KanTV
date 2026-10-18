package com.kantv.app

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.kantv.app.epg.*
import com.kantv.app.catalog.AppPreferences
import com.kantv.app.catalog.CatalogDatabase
import com.kantv.app.catalog.CatalogImportResult
import com.kantv.app.catalog.CatalogRepository
import com.kantv.app.catalog.ChannelEntity
import com.kantv.app.catalog.ChannelAliasStore
import com.kantv.app.catalog.HubRouteRegistry
import com.kantv.app.catalog.HubSubscriptionClient
import com.kantv.app.catalog.HubSubscriptionCoordinator
import com.kantv.app.catalog.HubSubscriptionStore
import com.kantv.app.catalog.HubWiring
import com.kantv.app.catalog.PlaylistLoader
import com.kantv.app.catalog.SecretBox
import com.kantv.app.ui.ChannelBrowseNav
import com.kantv.app.ui.ChannelBrowsePanel
import com.kantv.app.ui.ChannelSourceLabels
import com.kantv.app.util.Hashing
import com.kantv.app.ui.PlaybackMenuAction
import com.kantv.app.ui.PlaybackMenuOverlay
import com.kantv.app.ui.ScreenUi
import com.kantv.app.ui.TvDirectionalKeyGate
import com.kantv.app.epg.EpgChannelRef
import com.kantv.app.diagnostics.NativeDiagnosticsSampler
import com.kantv.app.diagnostics.PlaybackFeedbackQueue
import com.kantv.app.diagnostics.SharedPreferencesFeedbackQueuePersistence
import com.kantv.app.playback.PlaybackActivityFlag
import com.kantv.app.playback.MainThreadRetryScheduler
import com.kantv.app.playback.Media3PlaybackEngine
import com.kantv.app.playback.AdaptivePlaybackEngine
import com.kantv.app.playback.ContainerInfoSource
import com.kantv.app.playback.OpeningStage
import com.kantv.app.playback.PictureMode
import com.kantv.app.playback.trackChoiceLabel
import com.kantv.app.playback.PlaybackEngineFactory
import com.kantv.app.playback.PlaybackKernel
import com.kantv.app.playback.PlaybackCoordinator
import com.kantv.app.playback.PlaybackRoute
import com.kantv.app.playback.PlaybackRouteIntent
import com.kantv.app.playback.RouteSession
import com.kantv.app.playback.PlaybackDiagnostics
import com.kantv.app.playback.PlaybackDiagnosticSnapshot
import com.kantv.app.playback.PlaybackErrorKind
import com.kantv.app.playback.PlaybackTrackInfo
import com.kantv.app.playback.PlaybackTrackKind
import com.kantv.app.playback.PlaybackUiState
import com.kantv.app.playback.SharedPreferencesSourcePlaybackProfileStore
import com.kantv.app.playback.KernelSelectionReason
import com.kantv.app.playback.StreamDeliveryReason
import com.kantv.app.playback.StreamDeliveryStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var repository: CatalogRepository
    private lateinit var preferences: AppPreferences
    private lateinit var coordinator: PlaybackCoordinator
    private val ui by lazy { ScreenUi(this) }
    private lateinit var browsePanel: ChannelBrowsePanel
    private val diagnostics = PlaybackDiagnostics(elapsedRealtimeMs = SystemClock::elapsedRealtime)
    private val sourceProfileStore by lazy { SharedPreferencesSourcePlaybackProfileStore(this) }
    // 原生播放诊断采样簇整体在 diagnostics/NativeDiagnosticsSampler（只采集/上报，不参与播放决策）；
    // 宿主事实（播放状态、屏幕/菜单、抓帧目标、提示渲染）经构造参数注入，这里只持有并委托。
    // Fixed for this Activity instance; a new test launch is required to change the control.
    private var debugDisablePixelCopy = false
    private var debugDirectHls = false
    private val nativeDiagnostics by lazy {
        NativeDiagnosticsSampler(
            context = this,
            diagnostics = diagnostics,
            coordinator = { coordinator },
            hubRoutes = { hubRoutes },
            playbackUiState = { currentPlaybackState },
            isFullscreen = { screen == Screen.FULLSCREEN },
            isSourceMenuShowing = { sourceMenu?.isShowing == true },
            firstChannelOpening = { firstChannelOpening },
            isFinishing = { isFinishing },
            pictureCopyTargets = { listOfNotNull(playerView.videoSurfaceView, window) },
            showStatus = ::showStatus,
            renderPlaybackUiState = ::renderPlaybackState,
            showFullscreen = ::showFullscreen,
            debugDisablePixelCopy = debugDisablePixelCopy,
        )
    }

    private var channels: List<ChannelEntity> = emptyList()
    private var activeChannelId: String? = null
    private var activeChannel: ChannelEntity? = null
    private val openingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var openingChannelId: String? = null
    private var openingStartedMs = 0L
    private val openingTick = object : Runnable {
        override fun run() {
            val loading = currentPlaybackState as? PlaybackUiState.Loading ?: return
            if (screen == Screen.FULLSCREEN) showOpeningStatus(loading)
            openingHandler.postDelayed(this, 1_000)
        }
    }
    private var currentPlaybackState: PlaybackUiState = PlaybackUiState.Idle
    /**
     * 上一个正在看的频道（换台时记录）。播放宿主自动恢复全部失败时回退到它，
     * 避免把用户留在黑屏上——这是"不打断观看"的最低保障。
     */
    private var previousChannelIdForFallback: String? = null
    private var bootstrapped = false
    /** 目录准备已完成（后台协程跑完 prepareCatalogStartup）；空列表提示以此为门。 */
    @Volatile
    private var catalogPrepared = false
    /**
     * 启动期列表渲染冻结（无感启动，2026-09-20）：onCreate 起冻结，用户第一次按键时解冻。
     * 冻结期间频道数据照常更新（供播放候选），但列表屏幕不跟着重排。
     */
    private var listRenderFrozen = true
    /** 官网页 Gecko 预热只在首帧后调度一次。 */
    private var prewarmScheduled = false
    private val openedChannels by lazy { getSharedPreferences("opened_channels", MODE_PRIVATE) }
    private val epgRepository by lazy { DefaultEpgRepository.shared(this) }
    private var firstChannelOpening = false
    private var webPlayerClosed = false
    private var screen = Screen.FULLSCREEN
    private var importing = false
    private var defaultPlaylistMode: String? = null
    /** 枢纽订阅状态、线路视图与别名表缓存（进程内共享，只读本地已持久化的服务端数据）。 */
    private lateinit var hubStore: com.kantv.app.catalog.HubSubscriptionStore
    private lateinit var hubRoutes: com.kantv.app.catalog.HubRouteRegistry
    private lateinit var hubAliasCache: com.kantv.app.catalog.ChannelAliasCache
    private val feedbackCollector = com.kantv.app.diagnostics.PlaybackFeedbackCollector()
    /** 当前回流附着对应的线路摘要；换线时用于判断是否需要切分记录。 */
    private var activeFeedbackUrlKey: String? = null
    /** 播放中刷新后备候选的节流闸门，见 [refreshPlaybackCandidates]。 */
    private val candidateRefreshThrottle = com.kantv.app.playback.CandidateRefreshThrottle()
    // Tier 1 嗅探换轨（三阶梯架构，2026-09-12）：换轨失败后一次性质启官方页（禁嗅探）的标记。
    private var pendingSniffFallbackChannelId: String? = null
    private var disableSniffForNextPlay = false

    /** Tier 2 整页播放观测到首帧时回流（修复 WEB_EMBED 恒 FAIL 的既有缺口）。 */
    private val webPlaybackFirstFrameListener = { sessionId: Long, generation: Long ->
        if (coordinator.acceptsOfficialWebResult(sessionId, generation)) {
            feedbackCollector.onFirstFrame()
        }
    }
    private var currentKernel: PlaybackKernel = PlaybackKernel.MEDIA3
    private var subscriptionSummary: TextView? = null
    private var subscriptions = emptyList<com.kantv.app.catalog.PlaylistSubscriptionEntity>()
    private val directionalKeyGate = TvDirectionalKeyGate()
    private var sourceMenu: PlaybackMenuOverlay? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importFromFile)
    }

    private val officialWebPlayer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val routeSessionId = result.data?.getLongExtra(PlaybackRouteIntent.EXTRA_SESSION_ID, 0L) ?: 0L
        val routeGeneration = result.data?.getLongExtra(PlaybackRouteIntent.EXTRA_ROUTE_GENERATION, 0L) ?: 0L
        if (!coordinator.acceptsOfficialWebResult(routeSessionId, routeGeneration)) {
            return@registerForActivityResult
        }
        // Tier 1 嗅探换轨：官方页容器验证到可播流 -> 激活 Native 路线交 Media3。
        // 不走 coordinator.stop()：selectRoute 自动作废旧 OfficialWeb 路线并接管播放。
        val sniffedUri = result.data?.getStringExtra(PlaybackRouteIntent.EXTRA_SNIFFED_STREAM_URI)
        if (!sniffedUri.isNullOrEmpty()) {
            val channelId = activeChannelId ?: return@registerForActivityResult
            val mime = result.data?.getStringExtra(PlaybackRouteIntent.EXTRA_SNIFFED_STREAM_MIME)
                ?.takeIf { it.isNotBlank() }
            val headers = result.data?.getStringExtra(PlaybackRouteIntent.EXTRA_SNIFFED_STREAM_HEADERS)
                ?.let { raw -> runCatching { org.json.JSONObject(raw) }.getOrNull() }
                ?.let { obj ->
                    obj.keys().asSequence().associateWith { key -> obj.optString(key) }
                }.orEmpty()
            webPlayerClosed = false
            pendingSniffFallbackChannelId = channelId
            coordinator.selectRoute(
                PlaybackRoute.Native(channelId, listOf(
                    com.kantv.app.playback.PlaybackRequest(
                        channelId = channelId,
                        uri = sniffedUri,
                        mimeType = mime,
                        requestHeaders = headers,
                    ),
                )),
            )
            beginNativeDiagnostics()
            return@registerForActivityResult
        }
        coordinator.stop()
        // 网页播放期间拉到的订阅在这里补应用：原来只有"回主页且原生播放进入 Idle/Failed"
        // 才补，用户一直用网页播放时服务端线路变更会一直不生效。
        applyDeferredHubSubscription()
        val offset = result.data?.getIntExtra("channelOffset", 0) ?: 0
        webPlayerClosed = true
        val chosenId = result.data?.getStringExtra("selectedChannelId")
        if (result.data?.getBooleanExtra(com.kantv.app.playback.PlaybackFallbackContract.RESULT_CHANNEL_FALLBACK, false) == true && chosenId != null) {
            // 播放宿主自愈全部失败 → 自动回到上一个能看的频道，不把用户留在黑屏上。
            // 只回退到"确实存在"的那个频道；找不到就回列表，避免盲选一个又失败造成来回切。
            val restored = channels.firstOrNull { it.id == chosenId }
            if (restored != null) {
                showStatus("这个频道暂时不可用\n已自动回到 ${restored.name}")
                play(restored)
            } else {
                showChannelGroups()
            }
            return@registerForActivityResult
        }
        if (chosenId != null) {
            val source = result.data?.getIntExtra("sourceIndex", -1)?.takeIf { index -> index >= 0 }
            playWhenChannelAvailable(chosenId, source, attempt = 0)
            return@registerForActivityResult
        }
        when (result.data?.getStringExtra("destination")) {
            "exit" -> finish()
            "settings" -> showSettings()
            "guide" -> showGuide()
            else -> if (offset != 0) switchRelativeChannel(offset) else showChannelGroups()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            debugDisablePixelCopy = intent.getBooleanExtra("debug_disable_pixel_copy", false)
            debugDirectHls = intent.getBooleanExtra("debug_direct_hls", false)
            runCatching {
                android.util.Log.d(
                    "KanTvGlControl",
                    "flags pixelCopyOff=${if (debugDisablePixelCopy) 1 else 0}" +
                        " hwuiWindowFlag=${if (window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED != 0) 1 else 0}",
                )
            }
        }
        buildRoot()
        hideSystemUi()
        browsePanel = ChannelBrowsePanel(this, root, ui, browseHost)
        // 无感启动（2026-09-20）：本地频道目录在 Room 里，直接监听渲染即可；
        // 不再先显示"正在读取本地频道…"再等目录准备——两个流程并行，谁先好谁先上屏。

        // 枢纽：端点/token 走构建期注入；别名表与订阅状态只读本地缓存。
        repository = HubWiring.repository(this)
        hubStore = HubWiring.store(this)
        hubRoutes = HubRouteRegistry(hubStore)
        hubAliasCache = ChannelAliasStore.of(this)
        preferences = AppPreferences(this)
        // 无感启动（预热）：Gecko 预热要编译 WASM、抢 CPU/IO,若与首开通道同跑会拖慢首帧。
        // 延迟到首帧渲染后 500ms 再做(见 onFirstFrame);上次看的不是官网页则不预热。
        // 若上次看的是官网频道,稍后自动恢复/手动打开时直接接管热会话。
        // Tier 2 首帧回流：网页容器观测到画面推进时经进程级通知上报（PR4）。
        com.kantv.app.diagnostics.WebPlaybackNotifier.addListener(webPlaybackFirstFrameListener)
        if (BuildConfig.KANTV_DIAGNOSTICS) {
            com.kantv.app.diagnostics.DiagnosticUploadConfig.initializeBundledDefaults(this)
            nativeDiagnostics.prepare()
            com.kantv.app.diagnostics.DiagnosticUploadConfig.schedule(this)
        }
        scope.launch {
            repository.observeSubscriptions().collectLatest { updated ->
                subscriptions = updated
                if (screen == Screen.SUBSCRIPTIONS) subscriptionSummary?.text = subscriptionStatusText()
            }
        }
        scope.launch { ensureDefaultNasSubscription() }
        coordinator = PlaybackCoordinator(
            engineFactory = PlaybackEngineFactory {
                com.kantv.app.playback.WebLivePlaybackEngine(AdaptivePlaybackEngine(
                    media3Factory = PlaybackEngineFactory {
                        Media3PlaybackEngine(this, playerView, debugDirectHls = debugDirectHls)
                    },
                    profileStore = sourceProfileStore,
                    onKernelChanged = ::showPlaybackKernel,
                ))
            },
            scheduler = MainThreadRetryScheduler(),
            diagnostics = diagnostics,
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
            // Candidate health and kernel preference share the one SHA-256-keyed store.
            profileStore = sourceProfileStore,
            onDiagnostic = ::recordNativeDiagnosticFact,
            onStateChanged = ::handlePlaybackStateChanged,
            onTracksChanged = ::refreshTrackChoices,
            // Detailed counters are sampled by nativeDiagnosticTick every 3 seconds. Candidate
            // state transitions still reach recordNativeDiagnosticFact immediately; sampling on
            // every Media3 technical callback would turn high-rate audio/video telemetry into
            // continuous disk churn in the ordinary package.
            onFirstFrame = { channelId ->
                openedChannels.edit().putBoolean(channelId, true).apply()
                feedbackCollector.onFirstFrame()
                scope.launch { preferences.saveLastChannelId(channelId) }
                if (!prewarmScheduled) {
                    prewarmScheduled = true
                    // 预热让位首帧:出画 500ms 后再起后台 Gecko 预热。
                    playerView.postDelayed({ warmLastOfficialWebChannel() }, 500L)
                }
            },
            onOfficialWeb = ::launchOfficialWeb,
            hubFallbackRoute = { channelId ->
                // 契约 §3：本频道 routes 全部失败后，依次尝试服务端 fallback 与本地官网内嵌目录。
                hubRoutes.fallbackRouteForInternal(channelId).also { route ->
                    // 服务端下发的 fallback 若不在本地官网目录内，容器无法打开：本地留痕 + 上报 notice。
                    if (route == null && hubRoutes.unplayableServerFallbacks(channelId) > 0) {
                        com.kantv.app.catalog.HubProtocolNotices.record(
                            this,
                            com.kantv.app.catalog.HubNotice(
                                com.kantv.app.catalog.HubNotice.CODE_OTHER,
                                "server fallback url is not playable by the local web container",
                            ),
                        )
                    }
                }
            },
            onBlocked = { _, route ->
                stopNativeDiagnostics(com.kantv.app.diagnostics.DiagnosticEntryKind.STOP)
                playerView.visibility = View.GONE
                val name = activeChannel?.name ?: route.channelId
                showStatus("$name\n${route.message}\n\n按确认键返回频道列表")
            },
            // 画面每推进一帧都会回调：回流据此统计推进事件数与冻结段（段长按"画面恢复推进"计）。
            onPictureProgress = { feedbackCollector.onPictureProgress() },
            // 每次被计入的缓冲区间结束都会回调：回流据此识别"反复短缓冲"（不产生恢复动作，
            // 因此 stalls 与冻结段都看不到），让这种观看不再被报成 ok。
            // 同一入口兼作候选刷新：自愈成功的短缓冲永远不进 Recovering，刷新若只挂在恢复
            // 状态上，开播那一刻的阵容就会锁死整场，换源（switchToNextSource）学不到枢纽
            // 后来上新的线路。事件先按会话身份过滤再进这里、暂停时回调直接 return，
            // 所以它只可能来自"未暂停的 Native 会话"；节流统一在刷新入口内处理。
            onRebufferCounted = { durationMs ->
                feedbackCollector.onRebufferCounted(durationMs)
                refreshPlaybackCandidates()
            },
            // 候选线路变化（起播/自动换源/手动选线）→ 切分回流记录，避免两条线的冻结秒数被合并。
            onActiveSourceChanged = { request ->
                val key = request.hubUrlKey
                if (key != null && key != activeFeedbackUrlKey) {
                    finishHubFeedback()
                    activeChannel?.let { beginHubFeedback(it, request) }
                }
            },
        )

        coordinator.setPictureMode(PictureMode.FILL)
        // 目录准备（首次安装/版本升级时才真正做工作；平时是一次有界的本地读取）与
        // 频道列表渲染并行执行：列表能立刻出来，准备在后台静默完成。
        scope.launch {
            var catalogAttempt = 0
            while (true) {
                if (prepareCatalogStartup()) {
                    catalogPrepared = true
                    break
                }
                catalogAttempt++
                if (catalogAttempt == 1) {
                    showStatus("正在准备频道…\n首次启动需要一点时间，会自动重试。")
                }
                kotlinx.coroutines.delay(minOf(30_000L, 2_000L shl (catalogAttempt - 1).coerceAtMost(4)))
            }
        }
        scope.launch {
            repository.observeChannels().collectLatest { newChannels ->
                channels = com.kantv.app.catalog.ChannelDirectory.ordered(newChannels)
                if (!bootstrapped) {
                    bootstrapped = true
                    when {
                        channels.isNotEmpty() -> restoreLastChannel()
                        BuildConfig.DEV_PLAYLIST_URL.isNotBlank() -> importFromUrl(BuildConfig.DEV_PLAYLIST_URL)
                        // 首拍空列表不代表"没有频道"：目录可能还在后台准备。只有目录
                        // 准备完成后仍为空，才提示导入（避免首次安装闪现错误指引）。
                        catalogPrepared -> showSettings("还没有频道，请先导入 M3U 播放源")
                        else -> Unit
                    }
                } else if (activeChannelId == null && channels.isNotEmpty() && !importing) {
                    restoreLastChannel()
                } else if (screen == Screen.GROUPS) {
                    // 启动期列表冻结（2026-09-20 无感启动）：订阅/目录后台更新时不让屏幕上的
                    // 列表跟着重排；数据照常进内存（播放候选是新的），渲染等用户按键后再刷。
                    if (!listRenderFrozen) showChannelGroups()
                } else if (screen == Screen.CHANNELS) {
                    if (!listRenderFrozen) showGroupChannels(browsePanel.currentGroupTitle)
                }
            }
        }
    }

    override fun onDestroy() {
        openingHandler.removeCallbacks(openingTick)
        if (::browsePanel.isInitialized) browsePanel.release()
        com.kantv.app.diagnostics.WebPlaybackNotifier.removeListener(webPlaybackFirstFrameListener)
        stopNativeDiagnostics(com.kantv.app.diagnostics.DiagnosticEntryKind.STOP)
        if (BuildConfig.KANTV_DIAGNOSTICS) nativeDiagnostics.close()
        nativeDiagnostics.shutdown()
        coordinator.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 内存吃紧时先释放预热会话（它本来就是"可有可无的加速"，绝不让看电视受影响）。
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            com.kantv.app.playback.WebPlaybackPreloader.release()
        }
    }

    override fun onStop() {
        openingHandler.removeCallbacks(openingTick)
        nativeDiagnostics.onAppBackgrounded()
        if (::coordinator.isInitialized) coordinator.pause()
        // 熄屏/切后台：冻结累计到此为止（暂停时长绝不算成冻结）。
        feedbackCollector.onWatchingStopped()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        com.kantv.app.update.LanUpdateManager.scheduleForegroundAutoUpdate(this)
        nativeDiagnostics.onAppForegrounded()
        scope.launch { com.kantv.app.catalog.PlaylistRefreshScheduler.ensureScheduled(this@MainActivity) }
        if (::coordinator.isInitialized) coordinator.resume()
        // 回到前台继续累计"用户在看"的墙钟（无活跃附着时收集器内部直接忽略）。
        feedbackCollector.onWatchingStarted()
        openingHandler.removeCallbacks(openingTick)
        if (currentPlaybackState is PlaybackUiState.Loading) openingHandler.post(openingTick)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (sourceMenu?.isShowing == true) {
            return if (sourceMenu?.handleKey(event) == true) true else super.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && bootstrapped) showMenu()
            return true
        }
        val guardedDirectional = event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT) &&
            screen in setOf(Screen.FULLSCREEN, Screen.GROUPS, Screen.CHANNELS)
        if (event.action == KeyEvent.ACTION_UP && guardedDirectional) {
            directionalKeyGate.release(event.keyCode)
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        // 启动期列表冻结：用户第一次按键即解冻，并立刻把列表刷新到最新数据。
        if (listRenderFrozen) {
            listRenderFrozen = false
            // 整表构建不压在按键路径上:解冻只记标记,列表刷新推迟到下一帧。
            // 解冻键只解冻不移动：本次按键直接消费，避免同一按键先在旧（冻结）列表上
            // 移动、一帧后整页重建又把焦点拉回锚点的竞态；重建后焦点落在锚点/记忆行。
            val thawScreen = screen
            window.decorView.post {
                when (thawScreen) {
                    Screen.GROUPS -> showChannelGroups()
                    Screen.CHANNELS -> showGroupChannels(browsePanel.currentGroupTitle)
                    else -> Unit
                }
            }
            if (event.keyCode in setOf(
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                )
            ) {
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return handleBack()
        if (browseDecision(event.keyCode) == ChannelBrowseNav.Decision.MOVE_FOCUS) {
            if (browsePanel.moveVerticalFocus(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)) return true
        }

        // 频道列表里右键 = 直接看（无感：不再要求"再按一次确认"）；长按右键才展开信息面板。
        // 必须放在 guardedDirectional 之前：长按产生的 repeat 事件会被那道闸门吞掉。
        if (screen == Screen.CHANNELS && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val decision = browseDecision(event.keyCode, event.repeatCount)
            (currentFocus?.tag as? ChannelEntity)?.let { channel ->
                when (decision) {
                    ChannelBrowseNav.Decision.PLAY -> play(channel)
                    ChannelBrowseNav.Decision.SHOW_ACTIONS -> showChannelActions(channel)
                    else -> Unit
                }
            }
            return true
        }
        if (guardedDirectional && !directionalKeyGate.shouldHandleDown(event.keyCode, event.repeatCount)) return true
        when (browseDecision(event.keyCode, event.repeatCount)) {
            ChannelBrowseNav.Decision.OPEN_FOCUSED_GROUP -> {
                currentFocus?.takeIf { it.tag is ChannelBrowsePanel.GroupTarget }?.performClick()
                return true
            }
            ChannelBrowseNav.Decision.CONSUME -> return true
            ChannelBrowseNav.Decision.RETREAT -> {
                showChannelGroups()
                return true
            }
            ChannelBrowseNav.Decision.DISMISS -> {
                resumeTelevision()
                return true
            }
            else -> Unit
        }
        if (screen == Screen.FULLSCREEN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_LEFT,
                -> {
                    showCurrentChannelList()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    switchRelativeChannel(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    switchRelativeChannel(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    showControls()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack(): Boolean {
        // 浏览页回退链委托给纯状态机：CHANNELS→GROUPS，GUIDE→所选组频道，GROUPS→回全屏。
        when (val retreat = screen.browsePage()?.let { ChannelBrowseNav.retreat(it) }) {
            ChannelBrowseNav.Retreat.BROWSE_GROUPS -> {
                showChannelGroups()
                return true
            }
            ChannelBrowseNav.Retreat.BROWSE_CHANNELS -> {
                showGroupChannels(browsePanel.currentGroupTitle)
                return true
            }
            ChannelBrowseNav.Retreat.DISMISS -> {
                resumeTelevision()
                return true
            }
            null -> Unit
        }
        when (screen) {
            Screen.TRACKS -> showControls()
            Screen.STREAM_INFO -> showControls()
            Screen.CONTROLS -> resumeTelevision()
            Screen.SETTINGS -> if (activeChannelId == null) showMenu() else resumeTelevision()
            Screen.DIAGNOSTICS, Screen.SUBSCRIPTIONS -> showSettings()
            Screen.MENU -> resumeTelevision()
            Screen.FULLSCREEN -> showMenu()
            // GROUPS/CHANNELS/GUIDE 已在上方的回退链分支处理。
            else -> Unit
        }
        return true
    }

    private fun buildRoot() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); keepScreenOn = true }
        playerView = if (isEmulator()) {
            layoutInflater.inflate(R.layout.player_view_texture, root, false) as PlayerView
        } else {
            PlayerView(this)
        }.apply {
            useController = false
            setKeepContentOnPlayerReset(false)
            setShutterBackgroundColor(Color.BLACK)
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
        }
        status = TextView(this).apply {
            setTextColor(WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(ui.dp(28))
            background = ui.rounded(0xCC101216.toInt(), 18)
            visibility = View.GONE
        }
        root.addView(playerView, match())
        root.addView(
            status,
            FrameLayout.LayoutParams(ui.dp(560), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        setContentView(root)
    }

    private fun showPlaybackKernel(kernel: PlaybackKernel) {
        currentKernel = kernel
        playerView.visibility = View.VISIBLE
    }

    /**
     * 播放状态变化的统一入口：
     * 1. 维护跨线程的「正在播放」标志（契约 §6：播放中不得应用新订阅）；
     * 2. 驱动枢纽回流采集（首帧 / 停帧 / 失败）；
     * 3. 播放停止后补应用播放期间拉取到的订阅。
     */
    private fun handlePlaybackStateChanged(state: PlaybackUiState) {
        when (state) {
            is PlaybackUiState.Loading -> {
                PlaybackActivityFlag.markActive()
                feedbackCollector.onStallEnded()
                feedbackCollector.onWatchingStarted()
            }
            is PlaybackUiState.Recovering -> {
                PlaybackActivityFlag.markActive()
                // 既有帧进度门 / 停帧检测驱动的恢复动作，等价于一次停帧。
                feedbackCollector.onStallStarted()
                feedbackCollector.onWatchingStarted()
                // 自愈期间刷新后备候选：不打断当前播放，让下一次切线/重载用上最新阵容
                // （枢纽中途修好或换线时，整场沿用开播阵容会一直停在坏线上）。
                // 自愈成功的短缓冲到不了这里，那条节奏由 onRebufferCounted 走同一入口。
                refreshPlaybackCandidates()
            }
            is PlaybackUiState.Failed -> {
                PlaybackActivityFlag.markIdle()
                feedbackCollector.onStallEnded()
                feedbackCollector.onWatchingStopped()
                feedbackCollector.onFailure(failReasonFor(state))
                finishHubFeedback()
                applyDeferredHubSubscription()
                maybeFallBackFromSniffedStream()
            }
            is PlaybackUiState.Idle -> {
                PlaybackActivityFlag.markIdle()
                feedbackCollector.onWatchingStopped()
                finishHubFeedback()
                applyDeferredHubSubscription()
            }
            else -> {
                PlaybackActivityFlag.markActive()
                feedbackCollector.onStallEnded()
                feedbackCollector.onWatchingStarted()
            }
        }
        renderPlaybackState(state)
    }

    /**
     * Tier 1 换轨失败的兜底：嗅探流交给 Media3 后仍终态失败（token 过期/加密/解码失败），
     * 一次性质启官方页（禁嗅探防循环）回 Tier 2 整页播放。用户手动重播不受影响。
     */
    private fun maybeFallBackFromSniffedStream() {
        val channelId = pendingSniffFallbackChannelId ?: return
        pendingSniffFallbackChannelId = null
        if (channelId != activeChannelId) return
        val channel = channels.firstOrNull { it.id == channelId } ?: return
        disableSniffForNextPlay = true
        play(channel)
    }

    /** 播放期间拉取到的订阅在停止后补应用（不打断播放，也不丢弃结果）。 */
    private fun applyDeferredHubSubscription() {
        scope.launch {
            runCatching {
                HubSubscriptionCoordinator(
                    store = hubStore,
                    client = HubSubscriptionClient(
                        endpoint = com.kantv.app.catalog.HubConfig.read(this@MainActivity).endpoint,
                        identity = com.kantv.app.diagnostics.HubFeedbackIdentity.of(this@MainActivity),
                    ),
                    sink = repository,
                    aliasCache = hubAliasCache,
                    onProtocolNote = { notice ->
                        com.kantv.app.catalog.HubProtocolNotices.record(this@MainActivity, notice)
                    },
                    // P0-2 方案 C：服务端名单指纹变化 = url_key 语义切换（陈旧 key 已全表重算后投递）。
                    // 只留痕不动作：枢纽链路 url_key 全部来自服务端 payload，指纹在 signed_params 内，
                    // ETag 随之变化，下一次刷新必拉取新 payload 自愈。
                    onSignedParamsFingerprintChange = { previous, current ->
                        android.util.Log.i(
                            "KanTvHub",
                            "signed_params fingerprint changed: $previous -> $current; " +
                                "server url_key semantics switched, next refresh re-fetches payload",
                        )
                    },
                ).applyDeferredIfAny()
            }
        }
    }

    /**
     * 用最新阵容刷新后备候选。
     *
     * 只更新候选集合，不动正在播放的那条线路：契约要求播放中不得打断；
     * 而"枢纽修好/换线后能跟上"是本机体验问题，属于播放侧职责。
     *
     * 两个调用点共用这一个入口：恢复状态（既有）与每次计入的缓冲区间（自愈成功的短缓冲
     * 不进恢复状态，只有这个入口能看见）。读取要重新解密线路并重建候选列表，所以
     * 先过 [candidateRefreshThrottle] 再进协程，窗口内的缓冲事件不读库。
     */
    private fun refreshPlaybackCandidates() {
        val channelId = activeChannelId?.takeIf { it.isNotEmpty() } ?: return
        if (!::coordinator.isInitialized) return
        val selection = coordinator.currentSelectionToken()?.takeIf { it.channelId == channelId } ?: return
        if (!candidateRefreshThrottle.shouldRefresh(SystemClock.elapsedRealtime())) return
        scope.launch {
            val channel = channels.firstOrNull { it.id == channelId } ?: return@launch
            val fresh = runCatching { repository.playbackRequests(channel.id) }.getOrNull() ?: return@launch
            if (fresh.isNotEmpty()) coordinator.updateCandidates(selection, fresh)
        }
    }

    /** 开始一条枢纽线路的回流采集；非枢纽线路不采集（无法归因，也不上报）。 */
    private fun beginHubFeedback(channel: ChannelEntity, request: com.kantv.app.playback.PlaybackRequest) {
        val route = request.hubRoute ?: return
        val urlKey = request.hubUrlKey ?: return
        activeFeedbackUrlKey = urlKey
        feedbackCollector.begin(
            com.kantv.app.diagnostics.FeedbackSessionKey(
                channelId = channel.tvgId ?: channel.id,
                urlKey = urlKey,
                route = route,
                engine = when {
                    route == "web-embed" -> com.kantv.app.catalog.FeedbackEngine.WEB_EMBED
                    else -> com.kantv.app.catalog.FeedbackEngine.MEDIA3
                },
            ),
        )
    }

    /** 结束采集并入队；失败立即上报、成功延迟聚合（不占前台）。 */
    private fun finishHubFeedback() {
        val result = feedbackCollector.finish() ?: return
        activeFeedbackUrlKey = null
        PlaybackFeedbackQueue(
            com.kantv.app.diagnostics.SharedPreferencesFeedbackQueuePersistence(this),
        ).enqueue(result)
        com.kantv.app.diagnostics.HubFeedbackUploadWorker.schedule(this)
    }

    private fun failReasonFor(state: PlaybackUiState.Failed): com.kantv.app.catalog.FeedbackFailReason =
        when (state.kind) {
            PlaybackErrorKind.UNSUPPORTED_FORMAT, PlaybackErrorKind.DECODER ->
                com.kantv.app.catalog.FeedbackFailReason.DECODE_ERROR
            PlaybackErrorKind.AUTHORIZATION -> com.kantv.app.catalog.FeedbackFailReason.HTTP_403
            PlaybackErrorKind.OFFLINE, PlaybackErrorKind.TEMPORARY_NETWORK ->
                com.kantv.app.catalog.FeedbackFailReason.NETWORK
            PlaybackErrorKind.BEHIND_LIVE_WINDOW -> com.kantv.app.catalog.FeedbackFailReason.TIMEOUT
            else -> com.kantv.app.catalog.FeedbackFailReason.UNKNOWN
        }

    private suspend fun restoreLastChannel() {
        val last = preferences.lastChannelId()
        val channel = channels.firstOrNull { it.id == last }
        if (channel == null) {
            showChannelGroups()
            return
        }
        play(channel)
    }

    /**
     * 一次性频道目录启动准备。无默认清单也是成功；只有真正失败才由调用方重试。
     * 用户标准：启动阶段不得因为数据没准备好就把用户挡在门外或要求手动导入。
     */
    private suspend fun prepareCatalogStartup(): Boolean = withContext(Dispatchers.IO) {
        try {
            val catalogStartedAt = SystemClock.elapsedRealtime()
            val bootstrapResult = com.kantv.app.catalog.CatalogBootstrapper(
                com.kantv.app.catalog.SharedPreferencesBootstrapStore(this@MainActivity),
                upgradeGuard = com.kantv.app.catalog.AppUpgradeGuard.create(this@MainActivity),
            ).prepare(
                revision = com.kantv.app.catalog.OfficialCatalogRevision.current,
                hasChannels = { repository.hasChannels() },
                // ensureOfficialWebChannels() itself runs the identity-safe variant dedupe, so
                // old duplicates converge with every source and the earliest position preserved.
                ensureOfficial = { repository.ensureOfficialWebChannels(); Unit },
                ensureBundled = {
                    val bundled = try {
                        assets.open("bundled-default/channels.m3u").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } catch (_: java.io.FileNotFoundException) { null }
                    if (bundled != null) {
                        check(repository.ensureBundledPlaylist(bundled) !is CatalogImportResult.Rejected)
                    }
                },
                onPreparing = { firstInstall ->
                    withContext(Dispatchers.Main) {
                        // 无感启动：已有本地频道时，后台目录更新不再弹"正在更新本机频道目录"；
                        // 只有真正的首次安装（本地无频道）才提示。
                        if (screen == Screen.FULLSCREEN && (firstInstall || channels.isEmpty())) {
                            showStatus(if (firstInstall)
                                "首次准备频道…\n完成后会保存在本机，以后直接读取。"
                                else "正在更新本机频道目录…\n仅目录变化时需要处理，请稍候。")
                        }
                    }
                },
            )
            // The revision gate may reuse an already-populated database. Run the conservative,
            // identity-only merge every startup so upgrades also converge old duplicate rows;
            // the no-op path is just one bounded catalogue read.
            repository.dedupeNameVariants()
            if (BuildConfig.DEBUG) runCatching {
                java.io.File(filesDir, "catalog-startup-state.json").writeText(org.json.JSONObject()
                    .put("catalogPreparationMs", SystemClock.elapsedRealtime() - catalogStartedAt)
                    .put("reusedLocal", bootstrapResult.reusedLocal)
                    .put("updatedOfficial", bootstrapResult.updatedOfficial)
                    .put("checkedBundled", bootstrapResult.checkedBundled).toString())
            }
            defaultPlaylistMode = repository.defaultPlaylistMode()
            true
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            false
        }
    }

    /**
     * 播放宿主返回的频道若当前列表里还没有（导入/刷新竞态、或频道刚被重命名合并），
     * 原先会静默什么都不做，用户停在黑屏上。这里改为短时自动重试，仍找不到才给一次提示。
     */
    private fun playWhenChannelAvailable(channelId: String, sourceIndex: Int?, attempt: Int) {
        val channel = channels.firstOrNull { it.id == channelId }
        if (channel != null) {
            play(channel, sourceIndex)
            return
        }
        val retryDelaysMs = longArrayOf(400L, 1_200L, 2_500L)
        if (attempt >= retryDelaysMs.size) {
            showStatus("这个频道暂时不可用，正在回到频道列表…")
            scope.launch {
                kotlinx.coroutines.delay(2_500L)
                if (!isFinishing) showChannelGroups()
            }
            return
        }
        if (attempt == 0) showStatus("正在准备这个频道…")
        scope.launch {
            kotlinx.coroutines.delay(retryDelaysMs[attempt])
            if (!isFinishing) playWhenChannelAvailable(channelId, sourceIndex, attempt + 1)
        }
    }

    private fun play(channel: ChannelEntity, sourceIndex: Int? = null) {
        stopNativeDiagnostics(com.kantv.app.diagnostics.DiagnosticEntryKind.STOP)
        val selection = coordinator.beginSelection(channel.id)
        // 记住"上一个正在看的频道"，供播放宿主自愈失败时自动回退（无感：不把用户丢在黑屏上）。
        previousChannelIdForFallback = activeChannelId?.takeIf { it != channel.id }
        activeChannelId = channel.id
        activeChannel = channel
        firstChannelOpening = !openedChannels.getBoolean(channel.id, false)
        webPlayerClosed = false
        showFullscreen()
        showStatus(if (firstChannelOpening) "${channel.name}\n首次打开，正在读取频道并选择播放方式…" else "${channel.name}\n正在连接频道…")
        scope.launch {
            val allRequests = withContext(Dispatchers.IO) { repository.playbackRequests(channel.id) }
            val base = allRequests
            val selected = base.getOrNull(sourceIndex ?: 0)
            val requests = if (selected == null) emptyList() else if (sourceIndex != null) listOf(selected)
                else listOf(selected) + base.filter { it !== selected }
            selected?.let { beginHubFeedback(channel, it) }
            val routeSession = if (sourceIndex != null && selected != null) {
                // Manual pick: the coordinator is told explicitly so candidate health can never
                // reorder or override the user's chosen source.
                coordinator.selectManualSource(selection, selected)
            } else {
                coordinator.selectRequests(selection, requests)
            } ?: return@launch
            if (routeSession.route is PlaybackRoute.Native) {
                beginNativeDiagnostics()
            }
        }
    }

    private fun launchOfficialWeb(session: RouteSession, route: PlaybackRoute.OfficialWeb) {
        android.util.Log.i("KanTvRoute", "launchOfficialWeb provider=${route.page.provider}")
        stopNativeDiagnostics(com.kantv.app.diagnostics.DiagnosticEntryKind.STOP)
        playerView.visibility = View.GONE
        if (screen != Screen.FULLSCREEN) {
            webPlayerClosed = true
            coordinator.stop()
            return
        }
        // 2026-09-15：WebView 宿主已删除，官网页只剩 GeckoView 一条路；
        val target = GeckoMirrorLiveActivity::class.java
        officialWebPlayer.launch(android.content.Intent(this, target)
            .putExtra("pageUrl", route.page.url)
            .putExtra("channelId", route.channelId)
            .putExtra("channelTvgId", route.page.tvgId)
            .putExtra(PlaybackRouteIntent.EXTRA_SESSION_ID, session.sessionId)
            .putExtra(PlaybackRouteIntent.EXTRA_ROUTE_GENERATION, session.routeGeneration)
            .putExtra(PlaybackRouteIntent.EXTRA_DISABLE_SNIFF, disableSniffForNextPlay)
            .putExtra(com.kantv.app.playback.PlaybackFallbackContract.EXTRA_PREVIOUS_CHANNEL_ID, previousChannelIdForFallback)
            // 从未登录过这个官网时，进入"登录阶段"呈现：不做 CSS 铺满，让整页可见，
            // 用户才扫得到二维码。登录成功后这条就不再传，恢复既定呈现策略。
            .putExtra(
                com.kantv.app.playback.GeckoPresentationMode.EXTRA_LOGIN_PENDING,
                com.kantv.app.playback.OfficialWebLoginPolicy.needsFirstYicaiLogin(
                    route.page.provider,
                    getSharedPreferences(
                        com.kantv.app.playback.OfficialWebLoginPolicy.PREFS,
                        MODE_PRIVATE,
                    ).getLong(com.kantv.app.playback.OfficialWebLoginPolicy.KEY_VERIFIED_AT, 0L),
                ),
            ))
        // 一次性标记：仅在换轨失败自动回退时置 true，正常播放路径恒 false。
        disableSniffForNextPlay = false
    }

    private fun beginNativeDiagnostics() = nativeDiagnostics.begin()

    private fun stopNativeDiagnostics(kind: com.kantv.app.diagnostics.DiagnosticEntryKind) =
        nativeDiagnostics.stop(kind)

    private fun recordNativeDiagnosticFact(fact: com.kantv.app.playback.PlaybackDiagnosticFact) =
        nativeDiagnostics.recordFact(fact)

    private fun reportBadSource() = nativeDiagnostics.reportBadSource()

    private fun switchRelativeChannel(offset: Int) {
        if (channels.isEmpty()) return
        val currentIndex = channels.indexOfFirst { it.id == activeChannelId }.takeIf { it >= 0 } ?: 0
        val targetIndex = (currentIndex + offset).mod(channels.size)
        play(channels[targetIndex])
    }

    private fun renderPlaybackState(state: PlaybackUiState) {
        val previousState = currentPlaybackState
        currentPlaybackState = state
        nativeDiagnostics.onPlaybackUiStateChanged(state, previousState)
        openingHandler.removeCallbacks(openingTick)
        if (state !is PlaybackUiState.Loading && state !is PlaybackUiState.Recovering) openingChannelId = null
        if (screen != Screen.FULLSCREEN) {
            status.visibility = View.GONE
            return
        }
        when (state) {
            PlaybackUiState.Idle -> showStatus("请选择频道")
            is PlaybackUiState.Loading -> {
                if (openingChannelId != state.channelId) {
                    openingChannelId = state.channelId
                    openingStartedMs = SystemClock.elapsedRealtime()
                }
                if (screen == Screen.FULLSCREEN) showOpeningStatus(state)
                openingHandler.postDelayed(openingTick, 1_000)
            }
            is PlaybackUiState.Buffering -> showStatus("正在缓冲，请稍候…\n可按上下键换台，菜单键打开主菜单。")
            is PlaybackUiState.Ready -> status.visibility = View.GONE
            is PlaybackUiState.Recovering -> showStatus("信号波动，正在自动恢复 ${state.attempt}/2…")
            is PlaybackUiState.Failed -> showStatus(
                if (state.autoRetryInMs != null) "${if (state.kind == PlaybackErrorKind.OFFLINE) "网络已断开" else "频道信号暂不可用"}，约 ${state.autoRetryInMs / 1000} 秒后自动再试\n也可按确认键选择其他频道"
                else failureMessage(state.kind),
            )
        }
    }

    private fun showOpeningStatus(state: PlaybackUiState.Loading) {
        val seconds = ((SystemClock.elapsedRealtime() - openingStartedMs) / 1000).coerceAtLeast(0)
        val name = channels.firstOrNull { it.id == state.channelId }?.name ?: "当前频道"
        val stage = when (state.stage) {
            OpeningStage.CONNECTING -> "正在连接频道…"
            OpeningStage.PREPARING -> "正在准备画面…"
            OpeningStage.TRYING_ALTERNATIVE -> "正在尝试其他播放方式…"
        }
        val hint = when {
            seconds >= 25 -> "信号准备较慢，播放器会自动尝试恢复。\n也可按上下键换台。"
            seconds >= 8 -> "这个频道打开较慢，请再等一会儿。\n无需重复按键。"
            else -> "画面出来后会自动播放，请稍候。"
        }
        val firstHint = if (firstChannelOpening) "首次打开，正在自动选择播放配置。\n" else ""
        showStatus("$name\n$firstHint$stage\n已等候 $seconds 秒\n$hint")
    }

    private fun failureMessage(kind: PlaybackErrorKind): String = when (kind) {
        PlaybackErrorKind.OFFLINE -> "网络暂不可用，请检查网络连接"
        PlaybackErrorKind.AUTHORIZATION -> "频道暂时无法授权播放"
        PlaybackErrorKind.UNSUPPORTED_FORMAT -> "此频道格式暂不支持"
        PlaybackErrorKind.DECODER -> "画面无法持续播放，已尝试切换播放方式"
        else -> "频道暂时无法播放 · 确认键可选择其他频道"
    }

    private fun showCurrentChannelList() {
        val current = currentChannel()
        val landing = ChannelBrowseNav.landing(
            current?.let { ChannelBrowseNav.CurrentChannel(com.kantv.app.catalog.ChannelDirectory.category(it)) },
        )
        when (landing) {
            ChannelBrowseNav.Landing.BrowseGroups -> showChannelGroups()
            is ChannelBrowseNav.Landing.BrowseChannels -> showGroupChannels(landing.group)
        }
    }

    private fun currentChannel(): ChannelEntity? =
        ChannelBrowseNav.resolveCurrentChannel(
            channels = channels,
            activeChannelId = activeChannelId,
            playedId = activeChannel?.id,
            playedIdentity = activeChannel?.let {
                it.identityKey ?: com.kantv.app.catalog.ChannelIdentity.knownKey(it.tvgId, it.name)
            },
            idOf = { it.id },
            identityOf = { it.identityKey ?: com.kantv.app.catalog.ChannelIdentity.knownKey(it.tvgId, it.name) },
        )

    private fun Screen.browsePage(): ChannelBrowseNav.Page? = when (this) {
        Screen.GROUPS -> ChannelBrowseNav.Page.GROUPS
        Screen.CHANNELS -> ChannelBrowseNav.Page.CHANNELS
        Screen.GUIDE -> ChannelBrowseNav.Page.GUIDE
        else -> null
    }

    /** 浏览页键位判定委托给纯状态机；非浏览页或非方向/返回键返回 null（不拦截）。 */
    private fun browseDecision(keyCode: Int, repeatCount: Int = 0): ChannelBrowseNav.Decision? {
        val page = screen.browsePage() ?: return null
        val key = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> ChannelBrowseNav.Key.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> ChannelBrowseNav.Key.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> ChannelBrowseNav.Key.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> ChannelBrowseNav.Key.RIGHT
            KeyEvent.KEYCODE_BACK -> ChannelBrowseNav.Key.BACK
            else -> return null
        }
        return ChannelBrowseNav.decideKey(
            page = page,
            key = key,
            repeatCount = repeatCount,
            focusIsChannel = currentFocus?.tag is ChannelEntity,
            focusIsGroup = currentFocus?.tag is ChannelBrowsePanel.GroupTarget,
        )
    }

    /** 浏览渲染层的宿主回调：渲染只经此通信，播放/换源/导航动作仍在宿主侧。 */
    private val browseHost = object : ChannelBrowsePanel.Host {
        override fun channels(): List<ChannelEntity> = this@MainActivity.channels
        override fun currentChannel(): ChannelEntity? = this@MainActivity.currentChannel()
        override fun play(channel: ChannelEntity, sourceIndex: Int?) =
            this@MainActivity.play(channel, sourceIndex)
        override fun showChannelSources(channel: ChannelEntity) =
            this@MainActivity.showChannelSources(channel)
        override fun showGroupChannels(groupTitle: String?) =
            this@MainActivity.showGroupChannels(groupTitle)
        override fun showSettings() = this@MainActivity.showSettings()
        override fun notifyProgrammeUnavailable(message: String) {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
        override suspend fun loadEpg(refs: List<EpgChannelRef>) = epgRepository.load(refs)
        override fun dismissBrowse() = resumeTelevision()
    }

    private fun showChannelGroups() {
        val fromBrowseDepth = screen in setOf(Screen.CHANNELS, Screen.GUIDE)
        clearOverlay()
        screen = Screen.GROUPS
        browsePanel.showGroups(resetGroupMemory = !fromBrowseDepth)
    }

    private fun showGroupChannels(groupTitle: String?) {
        clearOverlay()
        screen = Screen.CHANNELS
        browsePanel.showChannels(groupTitle)
    }

    private fun showChannelSources(channel: ChannelEntity) {
        scope.launch {
            val requests = withContext(Dispatchers.IO) { repository.playbackRequests(channel.id) }
            // CONTROLS：观看中从播放控制面板进入换源。
            if (isFinishing ||
                screen !in setOf(Screen.CHANNELS, Screen.GUIDE, Screen.CONTROLS) ||
                requests.isEmpty()
            ) return@launch
            val labels = ChannelSourceLabels.labels(requests.map { it.uri })
            val overlay = sourceMenu ?: PlaybackMenuOverlay(this@MainActivity, root).also { sourceMenu = it }
            overlay.show(
                "${channel.name} · 选择播放来源",
                "确认使用所选来源 · 返回键取消",
                labels.mapIndexed { index, label -> PlaybackMenuAction(label) { play(channel, index) } },
            )
        }
    }

    private fun showChannelActions(channel: ChannelEntity) {
        val overlay = sourceMenu ?: PlaybackMenuOverlay(this, root).also { sourceMenu = it }
        overlay.show(
            channel.name,
            "确定看这个频道吗？也可以查看节目单或选择播放来源。",
            listOf(
                PlaybackMenuAction("看这个频道") { play(channel) },
                PlaybackMenuAction("查看节目单") { showChannelGuide(channel) },
                PlaybackMenuAction("选择播放来源") { showChannelSources(channel) },
            ),
        )
    }

    private var controlsSummary: TextView? = null
    private var trackChoiceButtons = listOf<Pair<Button, String?>>()
    private var trackChoiceKind = PlaybackTrackKind.AUDIO
    private var trackChoiceSession = 0L
    private var trackMessage: TextView? = null
    private fun showControls() {
        clearOverlay()
        screen = Screen.CONTROLS
        val snapshot = diagnostics.currentSnapshot()
        val channelName = channels.firstOrNull { it.id == activeChannelId }?.name ?: "当前频道"
        val tracks = snapshot?.tracks.orEmpty()
        val audio = tracks.firstOrNull { it.kind == PlaybackTrackKind.AUDIO && it.selected }
        val panel = bottomPlaybackControlsPanel(channelName)
        controlsSummary = ui.body("媒体播放 · ${audio?.let { trackChoiceLabel(it, tracks.filter { track -> track.kind == PlaybackTrackKind.AUDIO }.indexOf(it) + 1) } ?: "正在识别音轨"}")
        panel.addView(controlsSummary)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val first = ui.tvButton("音轨") { showTrackChoices(PlaybackTrackKind.AUDIO) }
        val entries = mutableListOf(
            first,
            ui.tvButton("字幕") { showTrackChoices(PlaybackTrackKind.SUBTITLE) },
            ui.tvButton("播放信息") { showStreamInfo() },
        )
        // 换源入口：观看中按右键就能到达。此前唯一入口是"频道列表里长按右键"，
        // 而按下的第一帧就已经开始播放并跳到全屏，长按分支永远走不到——等于没有入口。
        currentChannel()?.let { channel -> entries += ui.tvButton("来源") { showChannelSources(channel) } }
        // 质量判断交回给用户：一次点击把这一刻钉住，不需要描述、不需要知道自己在哪条线路上。
        entries += ui.tvButton("报错") { reportBadSource() }
        entries.forEach { button ->
            button.layoutParams = LinearLayout.LayoutParams(0, ui.dp(72), 1f).apply {
                setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5))
            }
            actions.addView(button)
        }
        panel.addView(actions)
        panel.post { first.requestFocus() }
    }

    private fun bottomPlaybackControlsPanel(heading: String): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(20))
            background = ui.rounded(0xE61A1E25.toInt(), ui.dp(18))
            addView(ui.title(heading))
        }
        root.addView(panel, FrameLayout.LayoutParams(
            resources.displayMetrics.widthPixels - ui.dp(32),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = ui.dp(16) })
        return panel
    }

    private fun showTrackChoices(kind: PlaybackTrackKind) {
        clearOverlay()
        screen = Screen.TRACKS
        trackChoiceKind = kind
        val snapshot = diagnostics.currentSnapshot()
        trackChoiceSession = snapshot?.sessionId ?: 0L
        val panel = ui.playbackPanel(if (kind == PlaybackTrackKind.AUDIO) "音轨" else "字幕", root)
        trackMessage = ui.body(if (kind == PlaybackTrackKind.AUDIO) "选择音轨即可试听，打勾的是正在播放的音轨。" else "只能关闭独立字幕轨道；画面内嵌的字幕无法关闭。")
        panel.addView(trackMessage)
        val tracks = snapshot?.tracks.orEmpty().filter { it.kind == kind }
        val choices = mutableListOf<Pair<Button, String?>>()
        fun addChoice(id: String?, text: String) {
            val button = ui.tvButton(text) {
                if (!coordinator.selectTrack(trackChoiceSession, kind, id)) {
                    trackMessage?.text = "切换未成功，轨道可能已变化。请返回后重新打开。"
                } else if (kind == PlaybackTrackKind.AUDIO) {
                    nativeDiagnostics.onAudioTrackSelected(trackChoiceSession)
                }
                refreshTrackChoices()
            }
            panel.addView(button)
            choices += button to id
        }
        if (kind == PlaybackTrackKind.SUBTITLE) addChoice(null, "关闭字幕")
        tracks.forEachIndexed { index, track ->
            if (track.selectionId != null) addChoice(track.selectionId, trackChoiceLabel(track, index + 1))
        }
        if (tracks.isEmpty()) {
            panel.addView(ui.body(if (kind == PlaybackTrackKind.AUDIO) "当前未检测到可选音轨。" else "当前未检测到可选字幕轨道。"))
        }
        // 音轨/字幕菜单只承担本类轨道的选择；声音同步与播放信息留在浮窗（showControls），
        // 避免两个界面按钮互相复制导致菜单越叠越乱（2026-09-12 用户反馈）。
        val back = ui.tvButton("继续看电视") { showFullscreen() }
        panel.addView(back)
        trackChoiceButtons = choices
        refreshTrackChoices()
        panel.post {
            (choices.firstOrNull { it.first.isSelected && it.first.isEnabled }?.first
                ?: (0 until panel.childCount).map { panel.getChildAt(it) }.firstOrNull { it is Button && it.isEnabled }
                ?: back).requestFocus()
        }
    }

    private fun refreshTrackChoices() {
        if (screen == Screen.CONTROLS) {
            val snapshot = diagnostics.currentSnapshot()
            val tracks = snapshot?.tracks.orEmpty().filter { it.kind == PlaybackTrackKind.AUDIO }
            val audio = tracks.firstOrNull { it.selected }
            val name = channels.firstOrNull { it.id == activeChannelId }?.name ?: "当前频道"
            controlsSummary?.text = "$name  ·  ${audio?.let { trackChoiceLabel(it, tracks.indexOf(it) + 1) } ?: "正在识别音轨"}"
        }
        if (screen != Screen.TRACKS) return
        val snapshot = diagnostics.currentSnapshot() ?: return
        if (snapshot.sessionId != trackChoiceSession) return
        val tracks = snapshot.tracks.filter { it.kind == trackChoiceKind }
        val expectedIds = (if (trackChoiceKind == PlaybackTrackKind.SUBTITLE) listOf<String?>(null) else emptyList()) + tracks.mapNotNull { it.selectionId }
        if (trackChoiceButtons.map { it.second } != expectedIds) {
            showTrackChoices(trackChoiceKind)
            return
        }
        trackChoiceButtons.forEach { (button, id) ->
            val track = tracks.firstOrNull { it.selectionId == id }
            val selected = if (id == null) tracks.none { it.selected } else track?.selected == true
            button.isSelected = selected
            val label = if (id == null) "关闭字幕" else track?.let { trackChoiceLabel(it, tracks.indexOf(it) + 1) } ?: "轨道已变化，请重新打开"
            button.text = (if (selected) "✓  " else "    ") + label
            button.isEnabled = id == null || (track != null && track.supported)
        }
    }

    private fun showStreamInfo() {
        clearOverlay()
        screen = Screen.STREAM_INFO
        val panel = ui.playbackPanel("播放信息", root)
        val snapshot = diagnostics.currentSnapshot()
        val channelName = channels.firstOrNull { it.id == activeChannelId }?.name ?: "当前频道"
        @Suppress("DEPRECATION")
        val displayMode = windowManager.defaultDisplay.mode
        panel.addView(ui.body("屏幕输出：${displayMode.physicalWidth}×${displayMode.physicalHeight} · ${"%.1f".format(displayMode.refreshRate)} Hz\n画面：铺满屏幕\n屏幕输出与频道分辨率可能不同。"))
        panel.addView(ui.body(snapshot?.let { formatDiagnosticSnapshot(channelName, it) } ?: "尚未读取到播放信息"))
        val refresh = ui.tvButton("刷新信息") { showStreamInfo() }
        panel.addView(refresh)
        panel.addView(ui.tvButton("继续看电视") { showFullscreen() })
        panel.post { refresh.requestFocus(); (panel.parent as? ScrollView)?.scrollTo(0, 0) }
    }

    private fun showGuide() = showChannelGroups()

    private fun showChannelGuide(channel: ChannelEntity) {
        clearOverlay()
        screen = Screen.GUIDE
        browsePanel.showGuide(channel)
    }

    private fun showMenu() {
        clearOverlay()
        screen = Screen.MENU
        val panel = ui.pagePanel(Gravity.CENTER_HORIZONTAL)
        panel.addView(ui.title(getString(R.string.app_name)))
        panel.addView(ui.tvButton("继续看电视") { resumeTelevision() })
        panel.addView(ui.tvButton("频道与节目表") { showCurrentChannelList() })
        panel.addView(ui.tvButton("设置") { showSettings() })
        panel.addView(ui.tvButton("退出看电视") { finish() })
        root.addView(panel, match())
        panel.post { panel.getChildAt(if (activeChannelId == null) 2 else 1)?.requestFocus() }
    }

    private fun showSettings(message: String? = null) {
        clearOverlay()
        screen = Screen.SETTINGS
        val panel = ui.pagePanel()
        panel.addView(ui.title("设置"))
        panel.addView(ui.body(message ?: "可使用内置官网频道，或添加 M3U 订阅、直播网页与本地清单。\nM3U 订阅每小时更新，本地文件保持导入时的内容。"))
        val input = EditText(this).apply {
            hint = "输入 M3U 或已支持的直播网页地址"
            setTextColor(WHITE)
            setHintTextColor(0xFF9EA6B2.toInt())
            textSize = 20f
            isSingleLine = true
            setText(BuildConfig.DEV_PLAYLIST_URL)
            background = ui.rounded(0x99252B34.toInt(), 12)
            setPadding(ui.dp(20))
        }
        panel.addView(input, LinearLayout.LayoutParams(matchSize, ui.dp(72)).apply { setMargins(0, ui.dp(12), 0, ui.dp(16)) })
        panel.addView(ui.tvButton("添加订阅 / 官网地址") {
            val value = input.text.toString().trim()
            if (value.isNotEmpty()) importFromUrl(value) else showStatus("请输入播放源或直播网页地址")
        })
        panel.addView(ui.tvButton("从文件导入") { filePicker.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "text/plain", "*/*")) })
        panel.addView(ui.tvButton("订阅更新（每小时）") { showSubscriptions() })
        val updateStatus = ui.body("应用更新：${com.kantv.app.update.LanUpdateStatusStore.text(this)}")
        panel.addView(updateStatus)
        panel.addView(ui.body("版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）"))
        val hubBase = com.kantv.app.catalog.HubConfig.read(this).endpoint.trimEnd('/')
        panel.addView(ui.body(if (com.kantv.app.catalog.HubEndpointPolicy.validEndpoint(hubBase)) {
            "已配置频道枢纽（自动更新）\n通用订阅：$hubBase/live.m3u（标准 M3U，可填入其他播放器）\n枢纽增强：$hubBase（多线路自动切换；配有回流凭据时回传播放结果）"
        } else {
            "未配置频道枢纽。内置官网频道可直接使用，也可在上方添加自己的 M3U 订阅或从文件导入。"
        }))
        panel.addView(ui.tvButton("检查并安装更新") {
            com.kantv.app.update.LanUpdateManager.downloadAndInstall(
                this,
                callback = { status -> showSettings(status) },
                onProgress = { text -> updateStatus.text = text },
            )
        })
        panel.addView(ui.tvButton("诊断状态") {
            startActivity(android.content.Intent(this, com.kantv.app.diagnostics.DiagnosticSettingsActivity::class.java))
        })
        panel.addView(ui.body("播放诊断只显示格式和运行状态，不显示播放地址、请求头或凭据。EPG 与台标预设将在后续阶段加入。"))
        root.addView(ScrollView(this).apply { addView(panel) }, match())
        panel.post { panel.getChildAt(3)?.requestFocus() }
    }

    private fun showSubscriptions() {
        clearOverlay()
        screen = Screen.SUBSCRIPTIONS
        val panel = ui.pagePanel()
        panel.addView(ui.title("订阅更新"))
        panel.addView(ui.body("仅用户添加的网址每小时检查，打开应用时补查。\n内置清单和本地文件不联网更新。更新不打断播放；休眠、断网时可能延后。"))
        val update = ui.tvButton("立即更新所有订阅") {
            if (subscriptions.isEmpty()) {
                Toast.makeText(this, "请先在设置中添加 M3U 固定网址", Toast.LENGTH_LONG).show()
            } else {
                com.kantv.app.catalog.PlaylistRefreshScheduler.requestRefresh(this, force = true)
                Toast.makeText(this, "更新已安排，联网后执行；旧频道继续保留", Toast.LENGTH_LONG).show()
            }
        }
        panel.addView(update)
        subscriptionSummary = ui.body(subscriptionStatusText())
        panel.addView(subscriptionSummary)
        panel.addView(ui.tvButton("返回设置") { showSettings() })
        root.addView(ScrollView(this).apply { addView(panel) }, match())
        panel.post { update.requestFocus() }
    }

    private fun subscriptionStatusText(): String {
        if (subscriptions.isEmpty()) return (if (defaultPlaylistMode == "BUNDLED") "正在使用内置默认清单，不自动更新。" else "当前没有在线订阅。") +
            "\n成功添加新 M3U 网址后，替换默认清单并开启每小时更新。\n导入本地文件只替换内容，不会联网刷新。"
        val format = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return subscriptions.mapIndexed { index, sub ->
            val successAt = sub.lastSuccessAtMs ?: 0L
            val last = if (successAt > 0) format.format(java.util.Date(successAt)) else "尚未成功"
            val status = when (sub.status) {
                "SUCCESS" -> "更新成功"
                "UPDATING" -> "正在更新"
                else -> if (successAt > 0) "最近更新未成功，仍使用上次内容" else "等待成功更新"
            }
            "订阅 ${index + 1} · ${sub.channelCount} 个频道\n$status\n最近成功：$last"
        }.joinToString("\n\n")
    }

    private fun showPlaybackDiagnostics() {
        clearOverlay()
        screen = Screen.DIAGNOSTICS
        val panel = ui.pagePanel()
        panel.addView(ui.title("播放诊断"))
        val snapshot = diagnostics.currentSnapshot()
        if (snapshot == null) {
            panel.addView(ui.body("还没有播放记录。开始播放一个频道后再查看。"))
        } else {
            val channelName = channels.firstOrNull { it.id == snapshot.channelId }?.name ?: "当前频道"
            panel.addView(ui.body(formatDiagnosticSnapshot(channelName, snapshot)))
        }
        panel.addView(ui.body(com.kantv.app.diagnostics.DiagnosticLedgerHealth.summary(
            com.kantv.app.diagnostics.DiagnosticLedgerHealth.read(this),
        )))
        panel.addView(ui.tvButton("刷新") { showPlaybackDiagnostics() })
        panel.addView(ui.tvButton("返回设置") { showSettings() })
        val scroll = ScrollView(this).apply { addView(panel) }
        root.addView(scroll, match())
        panel.post {
            panel.getChildAt(3)?.requestFocus()
            scroll.post { scroll.scrollTo(0, 0) }
        }
    }

    private fun formatDiagnosticSnapshot(
        channelName: String,
        snapshot: PlaybackDiagnosticSnapshot,
    ): String {
        val trackLines = if (snapshot.tracks.isEmpty()) {
            "  尚未读取到轨道"
        } else {
            snapshot.tracks.joinToString("\n") { "  ${formatTrack(it)}" }
        }
        val decoder = snapshot.videoDecoderName ?: when (snapshot.kernel) {
            PlaybackKernel.MEDIA3 -> "等待 Media3 解码器初始化"
            null -> "尚未选择"
        }
        return buildString {
            appendLine("频道：$channelName")
            appendLine("播放源：${snapshot.sourceIndex}/${snapshot.sourceCount}")
            appendLine("当前内核：${snapshot.kernel?.displayName() ?: "尚未选择"}")
            appendLine("选择原因：${snapshot.kernelReason?.displayName() ?: "等待识别"}")
            appendLine("取流策略：${snapshot.streamDeliveryStrategy?.displayName() ?: "等待识别"}")
            appendLine("取流说明：${snapshot.streamDeliveryReason?.displayName() ?: "等待识别"}")
            appendLine(
                when (snapshot.containerInfoSource) {
                    ContainerInfoSource.PLAYER_DETECTED -> "容器（播放器识别）：${snapshot.containerMimeType}"
                    ContainerInfoSource.REQUEST_HINT -> "容器提示（按地址推断）：${snapshot.containerMimeType}"
                    null -> "容器：尚未识别"
                },
            )
            appendLine("视频解码路径：$decoder")
            appendLine("首次出画：${snapshot.firstFrameMs?.let { "${it} 毫秒" } ?: "尚未出画"}")
            appendLine("累计掉帧：${snapshot.droppedVideoFrames}")
            val output = snapshot.audioOutputConfiguration
            appendLine(
                if (output == null) {
                    "音频输出：未知"
                } else {
                    "音频输出（AudioSink配置）：${output.outputMode.name}，编码 ${output.encoding}，" +
                        "${output.sampleRate}Hz，channelConfig ${output.channelConfig}，" +
                        "tunneling=${output.tunneling}，offload=${output.offload}"
                },
            )
            appendLine("实际音频路由：${output?.actualRoute ?: "未知"}")
            appendLine(
                "AudioSink位置：${if (snapshot.audioSinkPositionAvailability == com.kantv.app.playback.ObservationAvailability.AVAILABLE) "${snapshot.audioSinkPositionUs} 微秒" else "未知"}",
            )
            appendLine("AudioTrack timestamp / playback head：未知（Media3 AudioSink未公开）")
            snapshot.audioTiming?.let { timing ->
                appendLine("播放时钟：${timing.clockMs} 毫秒")
                appendLine("音频校准：${timing.configuredDelayUs / 1000} 毫秒（负数为提前声音）")
                appendLine("音频缓冲：已播放 ${timing.playedBuffers} / 丢失 ${timing.lostBuffers}")
            }
            snapshot.transportStreamObservation?.let { ts ->
                appendLine("TS输入：${ts.inputSource.name}；PCR=${if (ts.pcrAvailable) "可用" else "未知"}")
                appendLine("TS分析建议修复：${ts.wouldRepair}（仅分析，播放字节未修改）")
            }
            appendLine("最近错误码：${snapshot.lastErrorCode ?: "无"}")
            appendLine("轨道：")
            append(trackLines)
        }
    }

    private fun formatTrack(track: PlaybackTrackInfo): String {
        val type = when (track.kind) {
            PlaybackTrackKind.VIDEO -> "视频"
            PlaybackTrackKind.AUDIO -> "音频"
            PlaybackTrackKind.SUBTITLE -> "字幕"
            PlaybackTrackKind.OTHER -> "其他"
        }
        val details = mutableListOf<String>()
        track.mimeType?.let(details::add)
        track.codec?.let { details += "编码 $it" }
        if (track.width != null && track.height != null) details += "${track.width}×${track.height}"
        track.frameRate?.let { details += "${"%.2f".format(it)}fps" }
        track.channelCount?.let { details += "${it}声道" }
        track.sampleRate?.let { details += "${it}Hz" }
        track.language?.takeIf { it.isNotBlank() }?.let { details += it }
        if (track.selected) details += "当前使用"
        if (!track.supported) details += "设备声明不支持"
        return "$type：${details.joinToString(" · ").ifBlank { "已识别" }}"
    }

    private fun PlaybackKernel.displayName(): String = when (this) {
        PlaybackKernel.MEDIA3 -> "Media3"
    }

    private fun KernelSelectionReason.displayName(): String = when (this) {
        KernelSelectionReason.DEFAULT -> "新源默认尝试"
        KernelSelectionReason.SOURCE_COMPATIBILITY -> "直播网站兼容模式"
        KernelSelectionReason.LEARNED_PREFERENCE -> "本机上次稳定播放"
        KernelSelectionReason.MANUAL_REOPEN -> "重新打开当前源"
        KernelSelectionReason.FIRST_FRAME_TIMEOUT -> "等待首帧超时"
        KernelSelectionReason.FRAME_STALLED -> "画面停止推进"
        KernelSelectionReason.BUFFERING_TIMEOUT -> "缓冲持续过久"
        KernelSelectionReason.FORMAT_UNSUPPORTED -> "格式不兼容"
        KernelSelectionReason.DECODER_ERROR -> "解码失败"
        KernelSelectionReason.STREAM_ENDED -> "直播流意外结束"
        KernelSelectionReason.OTHER_ERROR -> "当前内核播放失败"
    }

    private fun StreamDeliveryStrategy.displayName(): String = when (this) {
        StreamDeliveryStrategy.DIRECT -> "直接读取"
        StreamDeliveryStrategy.HLS_PARALLEL_PREFETCH -> "HLS 并行预取"
        StreamDeliveryStrategy.HLS_CACHED_PLAYLIST -> "缓存 HLS 播放列表"
    }

    private fun StreamDeliveryReason.displayName(): String = when (this) {
        StreamDeliveryReason.DEFAULT -> "当前格式使用普通读取"
        StreamDeliveryReason.PREFETCH_ENABLED -> "并行准备直播分片，降低单连接过慢造成的停顿"
        StreamDeliveryReason.MASTER_PLAYLIST -> "多码率主节目单暂不经过本地预取"
        StreamDeliveryReason.ENCRYPTED_PLAYLIST -> "加密节目单保持原播放器处理"
        StreamDeliveryReason.INITIALIZATION_SEGMENT -> "分段 MP4 保持原播放器处理"
        StreamDeliveryReason.INVALID_MEDIA_PLAYLIST -> "节目单结构不适合本地预取"
        StreamDeliveryReason.PREFETCH_NETWORK_FAILURE -> "预取初始化失败，已回到直接读取"
        StreamDeliveryReason.UNSUPPORTED_CODEC -> "无法确认安全起播位置，已回到直接读取"
        StreamDeliveryReason.UNVERIFIED_BOOTSTRAP -> "未找到完整关键帧起点，已回到直接读取"
        StreamDeliveryReason.BYTE_RANGE_PLAYLIST -> "字节范围节目单暂不经过本地预取"
        StreamDeliveryReason.GAP_PLAYLIST -> "含缺口的节目单暂不经过本地预取"
        StreamDeliveryReason.LOW_LATENCY_PLAYLIST -> "低延迟节目单暂不经过本地预取"
        StreamDeliveryReason.PREFETCH_PENDING -> "已直接起播，本地预取正在后台验证"
    }

    /** 首帧后调度一次的官网页预热：仅当上次看的是官网频道时才启动。 */
    private fun warmLastOfficialWebChannel() {
        scope.launch {
            val lastId = preferences.lastChannelId()
            // 存的是频道表主键（StableChannelId），不是 tvgId：必须按主键口径解析。
            val page = com.kantv.app.playback.OfficialWebChannelCatalog.pageForStoredChannelId(lastId)
            if (page != null) {
                com.kantv.app.playback.WebPlaybackPreloader.warm(this@MainActivity, page.url)
            }
        }
    }

    /** 已配置枢纽时首次导入通用订阅；未配对的通用构建不发起请求。 */
    private suspend fun ensureDefaultNasSubscription() {
        val endpoint = com.kantv.app.catalog.HubConfig.read(this).endpoint
        if (!com.kantv.app.catalog.HubEndpointPolicy.validEndpoint(endpoint)) return
        val prefs = getSharedPreferences("kantv_catalog_defaults", MODE_PRIVATE)
        if (prefs.getBoolean("default_m3u_applied", false)) return
        val url = endpoint.trimEnd('/') + "/live.m3u"
        runCatching {
            val refreshStartedAtMs = System.currentTimeMillis()
            val text = withContext(Dispatchers.IO) { PlaylistLoader().fromUrl(url) }
            repository.import(text, url, refreshStartedAtMs)
        }
        val expectedId = Hashing.sha256Hex(url.toByteArray(Charsets.UTF_8))
        val saved = repository.subscriptions().firstOrNull { it.id == expectedId }
        if (saved?.lastSuccessAtMs != null) {
            prefs.edit().putBoolean("default_m3u_applied", true).apply()
        }
    }

    private fun importFromUrl(url: String) {
        if (importing) return
        if (com.kantv.app.playback.SupportedWebLivePage.from(url) != null) {
            importing = true
            showStatus("正在添加直播频道…")
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) { repository.addWebLivePage(url) }
                }
                importing = false
                result.onSuccess { channel ->
                    Toast.makeText(this@MainActivity, "已添加 ${channel.name}", Toast.LENGTH_SHORT).show()
                    play(channel)
                }.onFailure { showSettings("添加失败，请检查直播网页地址") }
            }
            return
        }
        importing = true
        showStatus("正在导入频道…")
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val refreshStartedAtMs = System.currentTimeMillis()
                    val text = PlaylistLoader().fromUrl(url)
                    repository.import(text, url, refreshStartedAtMs)
                }
            }
            importing = false
            result.onSuccess(::finishImport).onFailure {
                showSettings("导入失败，请检查地址和网络后重试")
            }
        }
    }

    private fun importFromFile(uri: Uri) {
        if (importing) return
        importing = true
        showStatus("正在导入频道…")
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val text = readText(uri)
                    repository.import(text, "content://local-playlist")
                }
            }
            importing = false
            result.onSuccess(::finishImport).onFailure { showSettings("文件无法读取或不是有效的 M3U") }
        }
    }

    private fun finishImport(result: CatalogImportResult) {
        when (result) {
            is CatalogImportResult.Imported -> {
                scope.launch {
                    defaultPlaylistMode = withContext(Dispatchers.IO) { repository.defaultPlaylistMode() }
                    com.kantv.app.catalog.PlaylistRefreshScheduler.ensureScheduled(this@MainActivity)
                }
                val sourceNote = if (result.sourceCount > result.channelCount) {
                    "，包含 ${result.sourceCount} 个播放源"
                } else {
                    ""
                }
                Toast.makeText(this, "已使用自定义清单，现有 ${result.channelCount} 个频道$sourceNote；官网频道已保留", Toast.LENGTH_LONG).show()
                activeChannelId = null
                scope.launch {
                    val imported = repository.observeChannels().first { it.isNotEmpty() }
                    channels = com.kantv.app.catalog.ChannelDirectory.ordered(imported)
                    if (activeChannelId == null) restoreLastChannel()
                }
            }
            is CatalogImportResult.Rejected -> showSettings("没有找到可用频道，原频道列表已保留")
            is CatalogImportResult.Preserved -> showSettings("新播放源内容疑似不完整，已保留原频道列表")
        }
    }

    private fun readText(uri: Uri): String {
        val output = ByteArrayOutputStream()
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "FILE_OPEN_FAILED" }
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 5 * 1024 * 1024) { "PLAYLIST_TOO_LARGE" }
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun resumeTelevision() {
        val channel = currentChannel()
        if (webPlayerClosed && channel != null) play(channel) else if (channel == null) showChannelGroups() else showFullscreen()
    }

    private fun showFullscreen() {
        clearOverlay()
        screen = Screen.FULLSCREEN
        // 观看期间保持常亮：否则等待换台/起播时电视可能自动熄屏，观感上像是"卡住了"。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.requestFocus()
        renderPlaybackState(currentPlaybackState)
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
        status.bringToFront()
    }

    private fun clearOverlay() {
        status.visibility = View.GONE
        if (::browsePanel.isInitialized) browsePanel.onHidden()
        for (index in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(index)
            if (child !== playerView && child !== status) root.removeViewAt(index)
        }
        status.bringToFront()
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.HARDWARE.contains("ranchu") ||
            Build.HARDWARE.contains("goldfish")

    private fun hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun match() = FrameLayout.LayoutParams(matchSize, matchSize)

    private enum class Screen { FULLSCREEN, GROUPS, CHANNELS, CONTROLS, TRACKS, STREAM_INFO, GUIDE, MENU, SETTINGS, DIAGNOSTICS, SUBSCRIPTIONS }

    private companion object {
        const val matchSize = ViewGroup.LayoutParams.MATCH_PARENT
        const val WHITE = 0xFFF5F7FA.toInt()
    }
}
