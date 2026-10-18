package com.kantv.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kantv.app.catalog.AppPreferences
import com.kantv.app.catalog.CatalogDatabase
import com.kantv.app.catalog.CatalogRepository
import com.kantv.app.catalog.ChannelDirectory
import com.kantv.app.catalog.ChannelEntity
import com.kantv.app.catalog.SecretBox
import com.kantv.app.epg.DefaultEpgRepository
import com.kantv.app.epg.EpgChannelRef
import com.kantv.app.epg.EpgGuide
import com.kantv.app.playback.GeckoPlaybackStrategy
import com.kantv.app.playback.GeckoBackAction
import com.kantv.app.playback.GeckoBackPolicy
import com.kantv.app.playback.GeckoPresentationMode
import com.kantv.app.playback.GeckoPresentationScript
import com.kantv.app.playback.GeckoVisualOutputClassifier
import com.kantv.app.playback.GeckoVisualOutputSignal
import com.kantv.app.playback.MseMirrorScript
import com.kantv.app.playback.MseMirrorServer
import com.kantv.app.playback.NativePagePrepareScript
import com.kantv.app.playback.PlaybackRouteIntent
import com.kantv.app.playback.SupportedWebLivePage
import com.kantv.app.ui.ChannelBrowseAnchor
import com.kantv.app.ui.ChannelBrowseNav
import com.kantv.app.ui.ChannelBrowsePanel
import com.kantv.app.ui.ChannelSourceLabels
import com.kantv.app.ui.PlaybackMenuAction
import com.kantv.app.ui.PlaybackMenuOverlay
import com.kantv.app.ui.PlaybackMenuPlacement
import com.kantv.app.ui.ScreenUi
import com.kantv.app.ui.TvDirectionalKeyGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

/**
 * 央视系镜像模式的 GeckoView 解密宿主（2026-09-13，替代 TCL 系统 WebView 83）。
 *
 * 背景：TCL 真机系统 WebView（Chromium 83）承载官方直播页时长期卡加载且多轮排查
 * 无法定位根因（内核层黑盒）；Android 无法单独升级系统 WebView，唯一出路是把
 * 独立浏览器内核随 APK 分发。项目内 GeckoView 146 已在 TVB 财经验收过官方直播页
 * 全链路（广告→直播），故镜像模式换用 Gecko 载体：隐藏于原生播放器之下，页面自带
 * WASM 解密，MSE 钩子镜像干净 fMP4 → 本地合成 HLS → ExoPlayer 原生渲染。
 *
 * 与 WebView 版镜像的桥差异（GeckoSession 没有 evaluateJavascript）：
 * - 信号通道：WebExtension（assets/kantv-gecko-mirror/）content script 自动注入
 *   （document_start），经 sendNativeMessage 推 bridge-ready / 每秒页面状态；
 * - 数据通道：主域 hook 由 [MseMirrorScript.geckoInstallScript] 经
 *   `loadUri("javascript:")` 注入（**必须 void 返回**，非 void 会用返回值替换文档），
 *   攒批 postMessage 给 content script → sendNativeMessage 推回本 Activity →
 *   [MseMirrorServer.offer] 行协议解析（content script 的回环 fetch 被页面 CSP
 *   拦，NetworkError，故数据也走原生消息）；
 * - video.play()/静音/状态探测在 content script（DOM 层与页面共享）。
 *
 * 与已删除的 WebView 宿主（OfficialWebLiveActivity，2026-09-15 移除）契约一致（RESULT_OK + channelOffset /
 * selectedChannelId / destination），MainActivity 侧无需新分支逻辑。
 *
 * 分层：Gecko 页面（解密宿主，可见但被原生视频覆盖——保持可见可避免 Gecko 对
 * 不可见面面的媒体节流类优化）→ MseMirrorScript 钩子 → MseMirrorServer →
 * ExoPlayer(TextureView)。看门狗与 WebView 版镜像同参（60s 首片 / 30s 断流 /
 * 最多重建 2 次）。
 */
open class GeckoMirrorLiveActivity : AppCompatActivity() {
    private lateinit var page: SupportedWebLivePage
    private lateinit var container: FrameLayout
    private lateinit var loadingHint: android.widget.TextView
    /** 用户可见交流层（转圈 + 分级文案）；与 loadingHint 并存，由其承担"人话"部分。 */
    private lateinit var statusView: com.kantv.app.PlaybackStatusView
    private val handler = Handler(Looper.getMainLooper())
    private var stopped = false
    private var destroyed = false

    // ===== Gecko 运行时 =====
    private var runtime: GeckoRuntime? = null
    private var initialOfficialNavigationObserved = false
    private var geckoView: GeckoView? = null
    @Volatile private var geckoSession: GeckoSession? = null
    @Volatile private var sessionGeneration = 0L
    private var pageStartedAtMs = 0L
    private var mirrorExtensionReady = false
    /** 当前已提交页面 URL（onLocationChange 上报）；javascript: 注入的域门依据。 */
    private var currentUrl: String? = null
    /** 最近一次 onPageStart 的 URL（区分主文档加载与 javascript:/about:blank 加载）。 */
    private var lastPageStartUrl: String? = null
    private var pageLoadRetries = 0
    /** runtime 级安装结果；每个新 GeckoSession 仍需单独绑定消息 delegate。 */
    private var mirrorExtension: WebExtension? = null

    // ===== 镜像通道 =====
    private var mirrorServer: MseMirrorServer? = null
    private var mirrorPlayer: androidx.media3.exoplayer.ExoPlayer? = null
    private var mirrorPlayerView: View? = null
    /** 播放器已预建但尚未挂载媒体时为 false;挂载幂等,只挂一次。 */
    private var mirrorMediaAttached = false
    private var mirrorPlaylistUri: String? = null
    private var mirrorStartedAtMs = 0L
    /** MSE server 实例的固定起点；Gecko watchdog/reload 不得改写。 */
    private var mirrorServerStartedAtMs = 0L

    /** 镜像看门狗重建/放弃判定（阈值、时钟口径与放弃闩锁见策略类）。 */
    private val mirrorRecovery = com.kantv.app.playback.MirrorRecoveryPolicy()

    /** 主域脚本注入节流（镜像路径端点判空先于窗口检查的纪律在策略类里固化）。 */
    private val injectionThrottle = com.kantv.app.playback.MirrorInjectionThrottle()

    /** 镜像诊断计数器/里程碑/阶段状态（按内聚块搬至同文件 GeckoMirrorDiagnostics）。 */
    private val mirrorDiag = GeckoMirrorDiagnostics()

    // ===== 桥循环 =====
    private var watchdogJob: Job? = null

    /** 诊断采样循环：前后台各拉起一次，同一时刻最多一个（onStop 取消，onStart 重启）。 */
    private var diagnosticSamplingJob: Job? = null

    // ===== 原生页进度看门狗（NATIVE_PAGE 生产路径）=====
    /**
     * 生产路径恒为 NATIVE_PAGE，镜像看门狗（[maybeRecoverStalledMirror]）只在 MSE 镜像策略下运行，
     * 导致"页面能打开但视频零帧/中途定格"时 app 从不自愈（真机诊断报告已证实：
     * 央视官网会话 RECOVERY_STARTED 计数为 0，用户只能手动换台）。
     * 这里用页面每秒上报的 time/frames 事实做独立看门狗，动作复用已有的 [reload]（硬重建会话）。
     */
    private var nativeProgressWatchdogJob: Job? = null
    private val nativeProgressPolicy = com.kantv.app.playback.NativePageProgressWatchdogPolicy(
        firstFrameTimeoutMs = NATIVE_PROGRESS_FIRST_FRAME_TIMEOUT_MS,
        stallTimeoutMs = NATIVE_PROGRESS_STALL_TIMEOUT_MS,
        maxRecoveries = NATIVE_PROGRESS_MAX_RECOVERIES,
        runtimeFirstFrameDeadlineMs = NATIVE_PROGRESS_RUNTIME_FIRST_FRAME_DEADLINE_MS,
    )
    /** 像素巡检节拍计数（每 [NATIVE_VISUAL_TICK_EVERY] 个看门狗周期采一次）。 */
    private var nativeVisualTick = 0
    // ===== 完整诊断：证据状态（供采样落盘，回答"哪一层坏了 / 为什么没自愈"）=====
    private var lastVisualSampleOutcome = "unknown"
    private var lastVisualSampleCallbackMs = -1L
    private var visualCaptureSequence = 0L
    private var lastWatchdogDecision = "unknown"
    private var lastWatchdogReason = "unknown"
    private var lastSampleSkipReason = "NONE"
    private var probeTimedOut = false
    private var probeIssuedAtMs = 0L
    private var lastHeartbeatAtMs = 0L
    /** 采样中断起点（熄屏/后台）：回前台时把中断时长落进诊断。 */
    private var samplingSuspendedAtMs = 0L
    /** 本次回前台时算出的中断时长（-1 = 未中断），在下一次采样时写入 context 组。 */
    private var resumeSuspendedMs = -1L
    /** 本轮是否发生过恢复动作（用于决定"已自动恢复"要不要提示一次）。 */
    private var hadRecoveryThisSession = false
    /** 镜像数据泵（行协议解析 + base64 解码下线程；单线程保行序）。 */
    private val offerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kantv-gecko-mirror-offer").apply { isDaemon = true }
    }

    // ===== 观测/诊断 =====
    private var diagnosticSessionId = 0L
    private var routeGeneration = 0L
    private var autoReturnScheduled = false
    private var savedChannel = false
    // ===== 启动计时埋点（只进调试状态/日志，不新增上报字段）=====
    /** 页面启动时刻（来自 [pageStartedAtMs] 的快照，用于算"启动到出画"耗时）。 */
    private var startupPageStartedAtMs = 0L
    /**
     * **本次启动**确认出画（像素证据）的时刻。
     *
     * 与 [mirrorFirstFrameAtMs]（镜像链路证据）和 [firstFrameMilestoneRecorded]（一次性上报闩锁）
     * 分开：启动耗时必须用**本轮**的首帧时间减**本轮**的页面起点，不能拿旧 session 的证据相减。
     */
    private var startupFirstFrameAtMs = 0L
    /** 本次会话最近一次自动重建的时刻（用于区分"启动被掐"与"真的卡死"）。 */
    private var lastRebuildAtMs = 0L
    private var nativeVisualProbeInFlight = false
    private val playbackStrategy by lazy {
        GeckoPlaybackStrategy.resolve(
            diagnosticsEnabled = BuildConfig.KANTV_DIAGNOSTICS,
            requested = intent.getStringExtra(GeckoPlaybackStrategy.EXTRA),
        )
    }
    private val presentationMode by lazy {
        GeckoPresentationMode.resolve(
            diagnosticsEnabled = BuildConfig.KANTV_DIAGNOSTICS,
            requested = intent.getStringExtra(GeckoPresentationMode.EXTRA),
            loginPending = intent.getBooleanExtra(GeckoPresentationMode.EXTRA_LOGIN_PENDING, false),
        )
    }
    private val diagnosticRecorder by lazy {
        com.kantv.app.diagnostics.OfficialWebDiagnosticsRecorder(applicationContext) {
            com.kantv.app.diagnostics.DiagnosticUploadConfig.schedule(applicationContext)
        }
    }

    /**
     * 页面状态观测簇（页面每秒 probe → 状态镜像/事件/提示；自本宿主纯搬移，
     * 2026-09-28，客户端重构 3-3）。宿主事实经构造回调注入，mirrorDiag/看门狗策略
     * 以实例注入，字段写回顺序不变；下方转发属性让簇外既有读写保持原样。
     */
    private val pageState = com.kantv.app.playback.OfficialWebPageStateMirror(
        mirrorDiag = mirrorDiag,
        nativeProgressPolicy = nativeProgressPolicy,
        recoveryBudget = NATIVE_PROGRESS_MAX_RECOVERIES,
        sessionAlive = { geckoSession != null && !stopped && !destroyed },
        pageProvider = { page.provider },
        pageName = { page.name },
        mirrorStartedAtMs = { mirrorStartedAtMs },
        sessionGeneration = { sessionGeneration },
        startupPageStartedAtMs = { startupPageStartedAtMs },
        startupFirstFrameAtMs = { startupFirstFrameAtMs },
        lastRebuildAtMs = { lastRebuildAtMs },
        savedChannel = { savedChannel },
        yicaiVerifiedAtMs = {
            getSharedPreferences(LOGIN_PREFS, MODE_PRIVATE).getLong(KEY_YICAI_VERIFIED_AT, 0L)
        },
        markYicaiVerifiedNow = {
            getSharedPreferences(LOGIN_PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_YICAI_VERIFIED_AT, System.currentTimeMillis()).apply()
        },
        requestPageFocus = { runCatching { geckoView?.requestFocus() } },
        loadingHintOrNull = { if (::loadingHint.isInitialized) loadingHint else null },
        statusViewOrNull = { if (::statusView.isInitialized) statusView else null },
        requestPageLogin = { requestPageLogin() },
        tapLoginEntry = { x, y -> tapLoginEntry(x, y) },
        restartWithNormalPresentation = { restartWithNormalPresentation() },
        usesMseMirror = { playbackStrategy.usesMseMirror },
        playbackStrategyName = { playbackStrategy.name },
        presentationModeName = { presentationMode.name },
        signalNativeProgress = { signalNativeProgress() },
        verifyNativeVisualOutput = { verifyNativeVisualOutput() },
        refreshMirrorDiagnosticCounters = { refreshMirrorDiagnosticCounters() },
        emitDiagnosticEvent = { kind -> diagnosticEvent(kind) },
        writeDebugState = { state -> writeDebugState(state) },
    )

    private var probeValues: com.kantv.app.diagnostics.DiagnosticPlaybackValues
        get() = pageState.probeValues
        set(value) { pageState.probeValues = value }
    private var webStateMirror: String
        get() = pageState.webStateMirror
        set(value) { pageState.webStateMirror = value }
    private var loginMessageShown: Boolean
        get() = pageState.loginMessageShown
        set(value) { pageState.loginMessageShown = value }
    private var loginFlowActive: Boolean
        get() = pageState.loginFlowActive
        set(value) { pageState.loginFlowActive = value }
    private var restartedAfterLogin: Boolean
        get() = pageState.restartedAfterLogin
        set(value) { pageState.restartedAfterLogin = value }

    // ===== 遥控 UI =====
    private var menu: PlaybackMenuOverlay? = null
    private val directionalKeyGate = TvDirectionalKeyGate()
    private val ui by lazy { ScreenUi(this) }
    /** 浏览面板挂载层：独立于 container 其它子 View，整层清场不影响网页与播放器。 */
    private lateinit var browseRoot: FrameLayout
    private lateinit var browsePanel: ChannelBrowsePanel
    /** 浏览浮层当前页（null = 未打开）；键位判定与 MainActivity.Screen.browsePage 同口径。 */
    private var browseScreen: ChannelBrowseNav.Page? = null
    private var browseChannels: List<ChannelEntity> = emptyList()
    /** 打开浏览时解析出的当前频道锚点（"正在观看"高亮与打开落点用）。 */
    private var browseCurrent: ChannelEntity? = null
    /** 作废跨轮次的异步频道加载：再次打开/关闭时旧结果不得落屏。 */
    private var browseLoadGeneration = 0L
    private val browseRepository by lazy { CatalogRepository(CatalogDatabase.get(this), SecretBox()) }
    private val browseShowing: Boolean get() = browseScreen != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnosticSessionId = intent.getLongExtra(PlaybackRouteIntent.EXTRA_SESSION_ID, 0L)
        routeGeneration = intent.getLongExtra(PlaybackRouteIntent.EXTRA_ROUTE_GENERATION, 0L)
        if (diagnosticSessionId <= 0L || routeGeneration <= 0L) {
            setResult(RESULT_CANCELED, routeResultIntent())
            finish()
            return
        }
        page = SupportedWebLivePage.from(intent.getStringExtra("pageUrl").orEmpty())
            ?.takeIf { it.officialPlayer } ?: run {
            setResult(RESULT_CANCELED, routeResultIntent())
            finish()
            return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        container = FrameLayout(this)
        loadingHint = android.widget.TextView(this).apply {
            text = "正在加载官网直播…\n首次打开可能需要较长时间，请稍候。"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setPadding(24, 20, 24, 20)
            setBackgroundColor(0xB3101216.toInt())
        }
        container.addView(loadingHint, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
        setContentView(container)
        statusView = com.kantv.app.PlaybackStatusView(this).also { it.attachTo(container) }
        menu = PlaybackMenuOverlay(this, container)
        browseRoot = FrameLayout(this)
        container.addView(browseRoot, FrameLayout.LayoutParams(-1, -1))
        browsePanel = ChannelBrowsePanel(this, browseRoot, ui, browseHost)
        beginDiagnosticSession()
        if (playbackStrategy.usesMseMirror) {
            startMirrorPlayback()
            startMirrorWatchdog()
        } else {
            mirrorStartedAtMs = android.os.SystemClock.elapsedRealtime()
        }
        startDiagnosticSampling()
        startNativePageSession()
        // 无感接管（2026-09-20）：若预热器里已有本频道的热会话（开机预热或上次退出暂存），
        // 直接接管，跳过冷创建——页面已经加载好/WASM 已编译，出画从几十秒降到几秒。
        val adopted = runCatching { com.kantv.app.playback.WebPlaybackPreloader.take(page.url) }.getOrNull()
        if (adopted == null) {
            // 没有命中：槽位里若是**别的**频道的预热页，立即释放，绝不让两个网页
            // 同时在后台烧内存/流量（内存纪律）。
            com.kantv.app.playback.WebPlaybackPreloader.release()
        }
        createWebRuntime(adopted)
        startNativeProgressWatchdog()
    }

    // ===== Gecko 会话生命周期 =====

    private fun createWebRuntime(adopted: com.kantv.app.playback.WebPlaybackPreloader.AdoptedSession? = null) {
        // 提前返回（正在销毁/已有会话）时，接管出来的会话必须关闭，不能留在后台播放。
        if (stopped || destroyed || isFinishing || geckoSession != null) {
            adopted?.session?.let { runCatching { it.close() } }
            return
        }
        val geckoRuntime = runtime ?: runCatching {
            GeckoRuntime.getDefault(this).also { runtime = it }
        }.getOrNull() ?: run {
            // 2026-09-15：WebView 宿主已删除，GeckoView 成为唯一网页内核，没有可退的兜底内核。
            // 因此这里不再"弹一句就退出"，而是用与其它等待态一致的交流层说明情况，
            // 并给用户足够时间按返回键换台（不打断、不强制退出）。
            val line = com.kantv.app.playback.PlaybackStatusCopy.runtimeUnavailable(page.name)
            if (::statusView.isInitialized) statusView.showLoading(line.headline, line.detail)
            diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.RENDERER_ERROR)
            android.util.Log.e("KanTvNativeWatchdog", "GeckoRuntime unavailable; no fallback engine")
            // 拿不到 runtime 就没法使用接管出来的会话：必须关闭，不能让它失去所有者。
            adopted?.session?.let { runCatching { it.close() } }
            return
        }
        sessionGeneration++
        nativeVisualProbeInFlight = false
        pageState.lastProbeTime = -1.0
        pageState.lastProbeFrames = -1.0
        pageState.lastMilestoneReadyState = -1
        pageState.nodeReady0AtMs = 0L
        pageState.metadataAtMs = 0L
        mirrorDiag.mirrorStage = "SESSION_START"
        mirrorDiag.mirrorNavigationState = "NOT_STARTED"
        mirrorDiag.mirrorExtensionState = if (mirrorExtensionReady) "READY" else "PENDING"
        mirrorDiag.mirrorBridgeReady = false
        mirrorDiag.mirrorHookRequested = false
        mirrorDiag.lastPlayerNodeState = "unknown"
        mirrorDiag.presentationFullscreenActive = false
        val session = adopted?.session ?: GeckoSession()
        geckoSession = session
        pageStartedAtMs = android.os.SystemClock.elapsedRealtime()
        // 启动计时埋点跟随"当前文档的起点"，保证重建后算出来的是这一次启动的真实耗时。
        startupPageStartedAtMs = pageStartedAtMs
        // 接管时页面已提交，onLocationChange 不会再派发：用预热器记录的落地 URL 播种 currentUrl。
        currentUrl = adopted?.landedUrl
        startupFirstFrameAtMs = 0L
        lastPageStartUrl = null
        injectionThrottle.reset()
        pageLoadRetries = 0
        refreshMirrorDiagnosticCounters()
        diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.WEB_SESSION_CREATED)
        // UA 必须与真实引擎一致（桌面 Firefox）：伪装 Chrome UA 时页面引擎检测失败
        // （window.chrome 不存在等），走了不解密的降级分支——实测镜像分片带真加扰
        // 签名（top block unavailable/Reference N），而同刻模拟器 Chrome 里同一页面
        // 解密正常。Firefox 分支为页面官方支持的播放器（含 WASM 解密）。
        runCatching { session.settings.userAgentOverride = DESKTOP_FIREFOX_UA }
        runCatching { session.settings.useTrackingProtection = false }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                target: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny> {
                if (target !== session) return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                val uri = request.uri
                // 只保护顶层导航不跳出同 provider 官网域；子资源/SPA 内部跳转全部放行。
                // javascript: URL（主域 hook 注入）必须放行。
                if (request.target != GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT) {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }
                if (uri == "about:blank" || uri.startsWith("javascript:")) {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }
                val sameProvider = SupportedWebLivePage.from(uri)?.provider == page.provider
                // 第一财经登录回跳（yicai.smgbb.cn/api/setcookie）是唯一允许的跨域顶层导航；
                // 白名单校验在纯函数里，其他跨域一律拒绝（安全边界）。
                val parsed = runCatching { java.net.URI(uri) }.getOrNull()
                val allowed = com.kantv.app.playback.OfficialWebLoginPolicy.allowsTopLevelNavigation(
                    provider = page.provider,
                    sameProviderPage = sameProvider,
                    loginFlowActive = loginFlowActive,
                    scheme = parsed?.scheme,
                    host = parsed?.host,
                    port = parsed?.port ?: -1,
                    path = parsed?.path,
                )
                if (BuildConfig.DEBUG && !uri.startsWith("javascript:")) {
                    android.util.Log.d("KanTvMirror", "onLoadRequest target=${request.target} allow=$allowed")
                }
                return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }

            /**
             * 站点把登录页开在**新窗口**时（window.open / target=_blank），GeckoView 走的是这个回调。
             * 以前完全没有实现它：`onLoadRequest` 对"新窗口"请求一律放行，却没人处理新会话，
             * 于是请求被静默丢弃、并折叠成一次同页刷新（实测日志：点击后紧跟一次 /tv 重载、
             * 页面事件计数归零）——**第一财经的登录二维码因此永远出不来**。
             *
             * 电视上没有多窗口可用，所以不新建会话，而是把目标地址**装回当前会话**，
             * 让登录页直接出现在画面里；用户扫到码才算数。返回 null = 不创建新窗口。
             */
            override fun onNewSession(target: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (target === session) {
                    handler.post { if (geckoSession === session) session.loadUri(uri) }
                }
                return null
            }

            override fun onLocationChange(
                target: GeckoSession,
                url: String?,
                permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (target !== session) return
                currentUrl = url
                if (BuildConfig.DEBUG) android.util.Log.d("KanTvMirror", "onLocationChange providerPage=${isProviderPage(url)}")
                // 提交即注入：官网文档已存在、页面脚本尚未初始化播放器——此刻装
                // MediaSource/Worker 钩子才赶得上（实测：慢加载 31s 的页面在
                // onPageStop 前 18s 播放器已建好 Worker+SourceBuffer，onPageStop
                // 才注入=0 分片）。onPageStop 路径保留兜底（节流去重）。
                if (isProviderPage(url)) handler.post {
                    if (mirrorDiag.navigationCommittedGeneration != sessionGeneration) {
                        mirrorDiag.navigationCommittedGeneration = sessionGeneration
                        mirrorDiag.mirrorNavigationState = "COMMITTED"
                        mirrorDiag.mirrorStage = "PAGE_COMMITTED"
                        refreshMirrorDiagnosticCounters()
                        diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.NAVIGATION_COMMITTED)
                    }
                    injectMirrorHook()
                }
            }
        }
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                target: GeckoSession,
                permission: GeckoSession.PermissionDelegate.ContentPermission,
            ): GeckoResult<Int> {
                if (target !== session) return GeckoResult.fromValue(
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                val autoplay = permission.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                    permission.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                return GeckoResult.fromValue(
                    if (autoplay) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(target: GeckoSession, fullScreen: Boolean) {
                if (target !== session) return
                handler.post {
                    if (geckoSession !== session || mirrorDiag.presentationFullscreenActive == fullScreen) {
                        return@post
                    }
                    mirrorDiag.presentationFullscreenActive = fullScreen
                    if (fullScreen) mirrorDiag.presentationFullscreenEnterCount++ else mirrorDiag.presentationFullscreenExitCount++
                    refreshPresentationDiagnostics()
                    diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.DISPLAY_MODE_CHANGED)
                    writeProbeDebugState()
                }
            }

        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(target: GeckoSession, url: String) {
                if (target !== session) return
                if (BuildConfig.DEBUG) android.util.Log.d("KanTvMirror", "onPageStart providerPage=${isProviderPage(url)}")
                // javascript: 加载不算主导航（避免污染 onPageStop 兜底注入的判断）。
                if (!url.startsWith("javascript:")) lastPageStartUrl = url
                if (url.startsWith("http")) {
                    pageStartedAtMs = android.os.SystemClock.elapsedRealtime()
                    // 同一次运行期内的主文档导航：埋点跟着新文档重新计时。
                    startupPageStartedAtMs = pageStartedAtMs
                    // 新文档＝新一轮启动：本轮首帧证据与节点/元数据时间戳一并复位，
                    // 避免与新的页面起点相减出负值。
                    startupFirstFrameAtMs = 0L
                    pageState.nodeReady0AtMs = 0L
                    pageState.metadataAtMs = 0L
                    pageState.lastMilestoneReadyState = -1
                    if (mirrorDiag.navigationStartedGeneration != sessionGeneration) {
                        mirrorDiag.navigationStartedGeneration = sessionGeneration
                        mirrorDiag.mirrorNavigationState = "STARTED"
                        mirrorDiag.mirrorStage = "NAVIGATING"
                        refreshMirrorDiagnosticCounters()
                        diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.NAVIGATION_STARTED)
                    }
                }
            }

            override fun onPageStop(target: GeckoSession, succeeded: Boolean) {
                if (target !== session) return
                if (BuildConfig.DEBUG) android.util.Log.d("KanTvMirror", "onPageStop succeeded=$succeeded providerPage=${isProviderPage(currentUrl)}")
                // 注入时机纪律（实测踩坑链）：
                // - content script 的 bridge-ready 信号在 document_start 就到，此时页面
                //   还在加载，javascript: load 会 cancel 进行中的主导航（onPageStop
                //   succeeded=false，播放器脚本加载不完整 → 0 分片）；
                // - about:blank 与 javascript: URL 也触发 onPageStop，无门控会造成
                //   注入与主导航竞争（120ms 一轮风暴，页面永远卡在 about:blank）。
                // 因此只在「主文档导航成功完成」时注入：本次 onPageStart 是 http 页面
                // + succeeded + 已提交官网域。
                val wasMainDocumentLoad = lastPageStartUrl?.startsWith("http") == true
                if (succeeded && wasMainDocumentLoad && isProviderPage(currentUrl)) {
                    if (mirrorDiag.navigationFinishedGeneration != sessionGeneration) {
                        mirrorDiag.navigationFinishedGeneration = sessionGeneration
                        mirrorDiag.mirrorNavigationState = "FINISHED"
                        mirrorDiag.mirrorStage = "PAGE_FINISHED"
                        refreshMirrorDiagnosticCounters()
                        diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.NAVIGATION_FINISHED)
                    }
                    handler.post { injectMirrorHook() }
                }
                // 主导航失败（未提交到官网域）：3s 后重试导航，最多 2 次。
                if (!succeeded && !isProviderPage(currentUrl) && pageLoadRetries < MAX_PAGE_LOAD_RETRIES) {
                    mirrorDiag.mirrorNavigationState = "FAILED"
                    mirrorDiag.mirrorStage = "FAILED"
                    refreshMirrorDiagnosticCounters()
                    diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.NAVIGATION_FAILED)
                    pageLoadRetries++
                    if (BuildConfig.DEBUG) android.util.Log.d("KanTvMirror", "page load failed, retry $pageLoadRetries/$MAX_PAGE_LOAD_RETRIES")
                    handler.postDelayed({ geckoSession?.loadUri(page.url) }, 3_000)
                }
            }
        }
        // 接管预热会话：预热器在后台已 open 过，不能重复 open；冷创建时才在这里 open。
        if (!session.isOpen) session.open(geckoRuntime)
        val view = geckoView ?: GeckoView(this).also {
            geckoView = it
            // Gecko 的 SurfaceView 后端可以出现“页面 video 已解码进度、但独立
            // Surface 始终只有首个中性灰缓冲区”的首开竞态。使用同窗口合成的
            // TextureView 后端，使网页帧和 Activity 生命周期共用一条提交链。
            it.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
            it.coverUntilFirstPaint(Color.BLACK)
            // 置于最底层：镜像模式的原生 TextureView 出帧后覆盖页面；
            // 保持 VISIBLE 让 Gecko 合成器持续运行（规避不可见页面的媒体节流）。
            container.addView(it, 0, FrameLayout.LayoutParams(-1, -1))
        }
        if (view.session !== session) {
            view.session?.let { _ -> view.releaseSession() }
            view.setSession(session)
        }
        // 接管热会话时不重新导航（页面已在预热期加载完成）；冷创建才从这里出发。
        setupMirrorExtension(geckoRuntime, session, navigate = adopted == null)
        if (adopted != null) {
            // 预热期没有我们的呈现脚本（只有每秒静音兜底），接管后补呈现注入并解除静音。
            // 解除静音做 0/1/2 秒三次：预热器最后一注静音可能已在 Gecko 线程队列里、
            // 晚于第一次解除到达——三次覆盖后，迟到的静音注入必然被压过。
            fun unmuteNow() {
                if (geckoSession === session && !stopped && !destroyed) {
                    runCatching {
                        // 先关掉预热器的常驻静音器(否则它会在 1s 内重新压掉解除),再解除静音。
                        session.loadUri(
                            "javascript:void(window.__kantvPreloadMute=0," +
                                "document.querySelectorAll('video').forEach(function(v){v.muted=false}))",
                        )
                    }
                }
            }
            handler.post {
                unmuteNow()
                handler.postDelayed({ unmuteNow() }, 1_000L)
                handler.postDelayed({ unmuteNow() }, 2_000L)
                if (geckoSession === session && !stopped && !destroyed) {
                    runCatching { session.loadUri(javascriptUri(pagePreparationAndPresentationScript())) }
                    // 接管时页面 onPageStop 早已过去(在预热器里),主域镜像钩子必须在这里
                    // 补注——否则镜像频道的预热白做(只有呈现脚本,没有分片泵)。
                    // injectMirrorHook 自身幂等且带 3s 节流,与迟到的 bridge-ready 触发去重。
                    if (playbackStrategy.usesMseMirror) injectMirrorHook()
                }
            }
        }
        writeDebugState(JSONObject().put("sessionGeneration", sessionGeneration))
    }

    /**
     * 注册镜像 WebExtension（runtime 级一次），注册完成才首次导航（保证
     * content script 在 document_start 注入）。后续会话重建时扩展已在，直接导航。
     */
    private fun setupMirrorExtension(geckoRuntime: GeckoRuntime, session: GeckoSession, navigate: Boolean = true) {
        fun reportFailure() {
            handler.post {
                if (geckoSession !== session || stopped || destroyed) return@post
                mirrorDiag.mirrorExtensionState = "FAILED"
                mirrorDiag.mirrorStage = "FAILED"
                refreshMirrorDiagnosticCounters()
                diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.EXTENSION_FAILED)
                // Cold sessions still navigate on extension failure; adopted pages must not reload.
                if (navigate) loadInitialOfficialPage(session)
            }
        }
        val installedExtension = mirrorExtension
        if (mirrorExtensionReady && installedExtension != null) {
            bindMirrorExtensionAndNavigate(session, installedExtension, navigate)
            return
        }
        runCatching {
            geckoRuntime.webExtensionController
                .ensureBuiltIn("resource://android/assets/kantv-gecko-mirror/", "kantv-mirror@kantv.local")
                .accept({ extension ->
                    if (extension == null) {
                        reportFailure()
                        return@accept
                    }
                    mirrorExtension = extension
                    mirrorExtensionReady = true
                    handler.post {
                        if (geckoSession !== session || stopped || destroyed) return@post
                        bindMirrorExtensionAndNavigate(session, extension, navigate)
                    }
                }, { error ->
                    reportFailure()
                    writeDebugState(JSONObject().put(
                        "extensionError", error?.javaClass?.simpleName ?: "unknown"))
                })
        }.onFailure {
            reportFailure()
        }
    }

    private fun bindMirrorExtensionAndNavigate(session: GeckoSession, extension: WebExtension, navigate: Boolean = true) {
        val bound = runCatching {
            session.webExtensionController.setMessageDelegate(extension, mirrorMessageDelegate, "kantv")
        }.isSuccess
        if (bound) {
            mirrorDiag.mirrorExtensionState = "READY"
            mirrorDiag.mirrorStage = "EXTENSION_READY"
            refreshMirrorDiagnosticCounters()
            diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.EXTENSION_READY)
        } else {
            mirrorDiag.mirrorExtensionState = "FAILED"
            mirrorDiag.mirrorStage = "FAILED"
            refreshMirrorDiagnosticCounters()
            diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.EXTENSION_FAILED)
        }
        // 接管预热会话时页面已在（预热期）加载好，绝不重新导航——否则刚编译好的
        // WASM 又从头再来，预热就白做了。冷创建时照旧：桥绑定失败也继续导航。
        if (navigate) loadInitialOfficialPage(session)
    }

    /** No-op in production; a debug subclass may observe the first already-prepared navigation. */
    protected open fun onBeforeInitialOfficialNavigation() = Unit

    private fun loadInitialOfficialPage(session: GeckoSession) {
        if (!initialOfficialNavigationObserved) {
            initialOfficialNavigationObserved = true
            runCatching { onBeforeInitialOfficialNavigation() }
        }
        session.loadUri(page.url)
    }

    /** content script → Kotlin（信号 + 镜像数据）。 */
    private val mirrorMessageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            app: String,
            message: Any,
            sender: WebExtension.MessageSender,
        ): GeckoResult<Any>? {
            val obj = message as? JSONObject ?: return null
            if (sender.session !== geckoSession) return null
            val messageGeneration = sessionGeneration
            val messageSession = sender.session
            fun currentMessage(): Boolean = com.kantv.app.diagnostics.isCurrentGeckoMessage(
                messageGeneration, sessionGeneration, messageSession, geckoSession,
            )
            when (obj.optString("kind")) {
                "diagnosticCapability" -> return GeckoResult.fromValue<Any>(
                    JSONObject().put("diagnosticsEnabled", BuildConfig.KANTV_DIAGNOSTICS)
                        .put("audioObservationEnabled", BuildConfig.KANTV_DIAGNOSTICS && !playbackStrategy.usesMseMirror),
                )
                // 数据通道：行协议批量体。回环 fetch 曾被页面 CSP 拦（NetworkError），
                // 原生消息不受页面 CSP 约束。单线程 executor 保证行序（init 先于分片）。
                "offer" -> {
                    val body = obj.optString("body")
                    val offerTarget = mirrorServer
                    if (body.isNotEmpty() && offerTarget != null) offerExecutor.execute {
                        if (!currentMessage()) return@execute
                        val accepted = offerTarget.offer(body)
                        if (accepted > 0) {
                            // 事件化起播:首个分片到达立刻尝试启动镜像播放器,
                            // 不再等 2s watchdog 轮询(最差白等一个周期)。
                            handler.post {
                                if (currentMessage()) maybeStartMirrorPlayer()
                            }
                        }
                        if (BuildConfig.DEBUG && accepted == 0) {
                            android.util.Log.d("KanTvMirror", "offer batch dropped (0 lines accepted)")
                        }
                    }
                }
                "mirrorMetrics" -> handler.post {
                    if (!currentMessage()) return@post
                    recordBridgeMirrorMetrics(
                        workerSwitches = obj.optLong("workerSwitches", 0L).coerceAtLeast(0L),
                        instanceSwitches = obj.optLong("instanceSwitches", 0L).coerceAtLeast(0L),
                    )
                }
                "presentation" -> handler.post {
                    if (currentMessage()) processPresentationState(obj)
                }
                else -> {
                    if (BuildConfig.DEBUG && obj.optString("kind") != "state") {
                        android.util.Log.d("KanTvMirror", "native msg kind=${obj.optString("kind")} where=${obj.optString("where")}")
                    }
                    val statePostedAtMs = android.os.SystemClock.elapsedRealtime()
                    handler.post {
                        if (!currentMessage()) return@post
                        when (obj.optString("kind")) {
                            // bridge-ready 只做观测：content script 在 document_start 注入，此刻
                            // 页面还在加载，此时 javascript: 注入会 cancel 主导航（实测踩坑）。
                            // 主域 hook 的注入时机唯一：主文档导航成功的 onPageStop。
                            "bridge" -> {
                                if (!mirrorDiag.mirrorBridgeReady) {
                                    mirrorDiag.mirrorBridgeReady = true
                                    mirrorDiag.mirrorStage = "BRIDGE_READY"
                                    refreshMirrorDiagnosticCounters()
                                    diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.BRIDGE_READY)
                                }
                            }
                            "state" -> processProbeState(obj, (android.os.SystemClock.elapsedRealtime() - statePostedAtMs).coerceAtLeast(0L))
                        }
                    }
                }
            }
            return null
        }
    }

    /** 经 `loadUri("javascript:")` 注入主域镜像钩子（幂等、void 返回）。 */
    private fun injectMirrorHook() {
        val session = geckoSession ?: return
        // 域门：只在官网页已提交（https + 同 host）后注入。about:blank / javascript:
        // 上下文里注入无意义（导航一走即销毁），且 javascript: load 会打断进行中的
        // 主导航（实测：无门控时 onPageStop→注入→javascript: 导航→onPageStop→…
        // 120ms 一轮风暴，tv.cctv.com 永远加载不出来）。
        if (!isProviderPage(currentUrl)) return
        // 节流：onPageStop 与 bridge-ready 双触发都会到这里；脚本幂等，3s 内只注一次。
        // 时间戳只在真正注入前更新：镜像端点判空（shouldInjectMirror 的 endpointAvailable=false）
        // 等提前返回不得白吃节流窗口——顺序由 MirrorInjectionThrottle 固化。
        val now = android.os.SystemClock.elapsedRealtime()
        if (!playbackStrategy.usesMseMirror) {
            if (!injectionThrottle.shouldInjectNativePage(now)) return
            injectionThrottle.markInjected(now)
            mirrorDiag.mirrorStage = "NATIVE_PAGE_PREPARED"
            refreshMirrorDiagnosticCounters()
            diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.NATIVE_PAGE_PREPARED)
            runCatching { session.loadUri(javascriptUri(pagePreparationAndPresentationScript())) }
            return
        }
        val endpoint = mirrorOfferEndpoint() ?: return
        if (!injectionThrottle.shouldInjectMirror(now, endpointAvailable = true)) return
        injectionThrottle.markInjected(now)
        mirrorDiag.mirrorHookRequested = true
        mirrorDiag.mirrorStage = "HOOK_REQUESTED"
        refreshMirrorDiagnosticCounters()
        diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.HOOK_INJECTION_REQUESTED)
        if (BuildConfig.DEBUG) android.util.Log.d("KanTvMirror", "injectMirrorHook providerPage=true")
        // title 探针：验证 javascript: URL 真的执行（bridge state 回传 document.title）。
        // 必须 void 前缀——javascript: URL 的完成值若非 undefined（赋值表达式返回
        // 字符串），Gecko 会用该字符串替换整个文档，页面连同播放器一起被销毁
        // （实测：无 void 时注入后 0.45s 出现第二条 bridge 消息=新文档，0 分片）。
        runCatching { session.loadUri("javascript:void(document.title='KANTV_HOOK_OK')") }
        val script = MseMirrorScript.geckoInstallScript(endpoint)
        runCatching { session.loadUri(javascriptUri(script)) }
        runCatching { session.loadUri(javascriptUri(pagePreparationAndPresentationScript())) }
    }

    /** 页面是否已提交到目标官网域（域门：只认 https + 与入口页同 host）。 */
    private fun isProviderPage(url: String?): Boolean {
        if (url == null || !url.startsWith("https://")) return false
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        val pageHost = pageHostOrNull() ?: return false
        return host == pageHost
    }

    private fun pageHostOrNull(): String? =
        runCatching { java.net.URI(page.url).host?.lowercase() }.getOrNull()

    /** javascript: URL 的 body 需 percent-encode（换行/空格/中文在 URL 里非法）。
     *  脚本内容在会话内高度重复（钩子 + 页面准备两份轮换），按内容缓存编码结果，
     *  避免每次注入都全量 percent-encode 几十 KB。 */
    private val javascriptUriCache = HashMap<String, String>(4)

    private fun javascriptUri(script: String): String {
        javascriptUriCache[script]?.let { return it }
        val encoded = StringBuilder()
        for (byte in script.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            if (value in 0x21..0x7E && value != 0x25) {
                encoded.append(value.toChar())
            } else {
                encoded.append('%').append("%02X".format(value))
            }
        }
        return "javascript:$encoded".also {
            if (javascriptUriCache.size < 8) javascriptUriCache[script] = it
        }
    }

    private fun pagePreparationAndPresentationScript(): String =
        preparePageScript() + "\n" + GeckoPresentationScript.build(
            mode = presentationMode,
            playerSelector = page.playerSelector,
            videoSelector = page.videoSelector,
            // 荔枝网（GDTV）原页自带 UA 原生 controls 与常驻焦点框；仅该站点隐藏。
            suppressNativePlayerChrome = page.suppressesNativePlayerChrome,
        )

    /** 频道点击（央视频 SPA 页需要点选目标频道）+ video.play() 重试（主域 DOM 操作）。 */
    private fun preparePageScript(): String =
        NativePagePrepareScript.build(
            // pid 深链页面不装点击循环：深链已是权威选择，模糊点击反而可能切错频道。
            label = com.kantv.app.playback.NativePageEvidenceGate.clickLoopLabel(
                page.channelLabel, page.channelPid),
            videoSelector = page.videoSelector,
            // 原页播放必须保留网页音频；只有镜像时才静音网页，避免与 ExoPlayer 重复出声。
            muteWebAudio = playbackStrategy.usesMseMirror,
            diagnosticsEnabled = BuildConfig.KANTV_DIAGNOSTICS,
        )

    private fun closeWebRuntime() {
        pageState.invalidateDiagnosticObservation()
        val session = geckoSession ?: return
        geckoSession = null
        val view = geckoView
        if (view != null && view.session === session) view.releaseSession()
        runCatching { session.close() }
    }

    // ===== 镜像数据泵（POST /offer 回环）=====

    private fun startMirrorPlayback() {
        // 若未来把 server 纳入重建，先吸收旧实例的末次计数，保证同一 Activity
        // 会话的诊断累计不因端口/server 切换归零。
        mirrorServer?.let { previous ->
            refreshMirrorDiagnosticCounters()
            previous.close()
            mirrorServer = null
        }
        val server = MseMirrorServer(
            debugLog = { message ->
                if (BuildConfig.DEBUG) android.util.Log.d("KanTvMirror", message)
            },
        )
        when (val result = server.start()) {
            is MseMirrorServer.StartResult.Started -> {
                mirrorServer = server
                mirrorPlaylistUri = result.playlistUri
                val startedAtMs = android.os.SystemClock.elapsedRealtime()
                mirrorStartedAtMs = startedAtMs
                mirrorServerStartedAtMs = startedAtMs
                mirrorRecovery.noteRecoveryBudgetReset()
                mirrorDiag.mirrorServerStarts++
                mirrorDiag.lastServerVideoInitCount = 0L
                mirrorDiag.lastServerAudioInitCount = 0L
                mirrorDiag.lastServerAcceptedVideoFragments = 0L
                mirrorDiag.lastServerAcceptedAudioFragments = 0L
                mirrorDiag.lastServerRejectedVideoFragments = 0L
                mirrorDiag.lastServerRejectedAudioFragments = 0L
                mirrorDiag.mirrorStage = "SERVER_READY"
                refreshMirrorDiagnosticCounters()
                diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.MIRROR_SERVER_STARTED)
                // 预建播放器:建造/视图/监听器开销移出首分片后的关键路径。
                prebuildMirrorPlayer()
                if (BuildConfig.DEBUG) android.util.Log.d("KanTvMirror", "server started uri=<local-port>")
            }
            else -> {
                mirrorDiag.mirrorStage = "FAILED"
                refreshMirrorDiagnosticCounters()
                diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.MIRROR_SERVER_FAILED)
                server.close()
            }
        }
    }

    private fun mirrorOfferEndpoint(): String? =
        mirrorPlaylistUri?.let { it.substringBeforeLast('/') + "/offer" }

    private fun startMirrorWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch {
            while (isActive && !stopped && !destroyed) {
                maybeStartMirrorPlayer()
                maybeRecoverStalledMirror()
                delay(WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private fun maybeStartMirrorPlayer() {
        val server = mirrorServer ?: return
        if (server.ready && !mirrorMediaAttached) {
            // 流恢复产出即清零重试预算（长期会话中允许后续再自愈两轮）。
            mirrorRecovery.noteRecoveryBudgetReset()
            webStateMirror = "INITIALIZING"
            ensureMirrorPlayer()
        }
    }

    // ===== 原生页进度看门狗 =====

    /**
     * 启动原生页看门狗。只服务生产路径（NATIVE_PAGE）；
     * 镜像策略有自己的 [maybeRecoverStalledMirror]，两者不叠加。
     */
    private fun startNativeProgressWatchdog() {
        if (playbackStrategy.usesMseMirror) return
        nativeProgressWatchdogJob?.cancel()
        nativeProgressWatchdogJob = lifecycleScope.launch {
            while (isActive && !stopped && !destroyed) {
                checkNativePageProgress()
                delay(NATIVE_PROGRESS_CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * 会话开始（重建 / 回前台 / 主文档导航）：复位**画面与会话**状态，保留"首帧事件已宣告"。
     * 不这么做的话，熄屏归来会被立刻判成"没有画面"而白重建一次。
     */
    private fun startNativePageSession() {
        nativeProgressPolicy.startSession(android.os.SystemClock.elapsedRealtime())
        // 注意：启动计时埋点的"页面起点"**不在这里**取。`reload()` 会先调本方法、再调
        // `createWebRuntime()`，那时 `pageStartedAtMs` 还是上一份文档的时间——会让
        // `sincePageStart`（正是真机复核本次修复的指标）虚高。埋点统一在
        // `createWebRuntime()` / `onPageStart()` 里跟着 `pageStartedAtMs` 一起更新。
        pageState.lastMilestoneReadyState = -1
        pageState.pagePlayingPhaseReported = false
        pageState.nodeReady0AtMs = 0L
        pageState.metadataAtMs = 0L
    }

    /** DOM 在推进（time/frames 增长）。只代表时钟，**不再**顺带归还预算或清画面计数。 */
    private fun signalNativeProgress() {
        nativeProgressPolicy.observeProgress(android.os.SystemClock.elapsedRealtime())
    }

    /**
     * 起播超时、中途定格、或"DOM 说在播但画面没出来" → 自动重建会话。
     * 单会话内预算用尽后只发一次不阻塞提示并回退上一频道，**绝不退出播放页、不丢当前频道**。
     */
    private fun checkNativePageProgress() {
        if (stopped || destroyed || isFinishing) return
        if (playbackStrategy.usesMseMirror) return
        if (geckoSession == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        // 采样门槛：只在"页面自称数据充分在播"时才做整帧读回。起播/暂停/缓冲期不读回，
        // 既省开销，也避免把正在缓冲的页面误判成卡死。
        val pageClaimsPlaying = probeValues.readyState == 4 && !probeValues.paused
        nativeVisualTick++
        if (pageClaimsPlaying && nativeVisualTick % NATIVE_VISUAL_TICK_EVERY == 0) {
            observeNativeVisualOutput()
        } else {
            lastSampleSkipReason = if (!pageClaimsPlaying) "NOT_PLAYING" else "NONE"
        }
        // 像素读回超时兜底：回调既不成功也不失败时，避免巡检永久静默且无痕迹。
        if (nativeVisualProbeInFlight && probeIssuedAtMs > 0L && now - probeIssuedAtMs > PROBE_TIMEOUT_MS) {
            nativeVisualProbeInFlight = false
            probeTimedOut = true
            lastVisualSampleOutcome = "NO_SAMPLE"
            lastVisualSampleCallbackMs = -2L
            nativeProgressPolicy.observePictureMissing(now)
            diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.GREY_SCREEN_EVIDENCE)
            android.util.Log.w("KanTvNativeWatchdog", "visual probe timed out after ${now - probeIssuedAtMs}ms")
        }
        lastWatchdogDecision = "NONE"
        lastWatchdogReason = if (nativeProgressPolicy.isWedgeActive(now)) "PICTURE_UNVERIFIED" else "NONE"
        when (nativeProgressPolicy.decide(now)) {
            com.kantv.app.playback.NativePageProgressWatchdogPolicy.Decision.NONE -> Unit
            com.kantv.app.playback.NativePageProgressWatchdogPolicy.Decision.REBUILD -> {
                lastWatchdogDecision = "REBUILD"
                lastWatchdogReason = if (nativeProgressPolicy.hasEverVerifiedPicture()) {
                    "FRAME_PROGRESS_TIMEOUT"
                } else {
                    "FIRST_FRAME_TIMEOUT"
                }
                hadRecoveryThisSession = true
                diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.WATCHDOG_DECISION)
                diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.RECOVERY_STARTED)
                android.util.Log.i("KanTvNativeWatchdog", "rebuild: " + diagnosticSummaryLine())
                reload()
            }
            com.kantv.app.playback.NativePageProgressWatchdogPolicy.Decision.NOTIFY_GIVE_UP -> {
                diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.RECOVERY_STARTED)
                probeValues = probeValues.copy(webRecoveryStage = "FAILED")
                // 首选：自动回到上一个能看的频道（无感，不会把用户留在黑屏上）。
                if (!requestPreviousChannelFallback() && ::statusView.isInitialized) {
                    // 没有上一个频道（首次打开就失败）才给一次不阻塞提示；不提供按钮，出口是返回键。
                    val line = com.kantv.app.playback.PlaybackStatusCopy.failed(
                        channelName = page.name,
                        attempts = nativeProgressPolicy.recoveriesUsed(),
                    )
                    statusView.showLoading(line.headline, line.detail)
                }
            }
        }
    }

    /**
     * 镜像看门狗（与 WebView 镜像同参）：
     * A. 未启动：页面 JS 依赖链断裂导致播放器永不初始化，60s 内无任何分片 → 重建会话。
     * B. 运行中断流：页面播放器自我重载/流断 → 30s 无新分片即重建会话 + 镜像播放器。
     * 各最多重试 2 次，成功出片后计数清零；预算耗尽后回退上一频道（与原生看门狗同一出口）。
     */
    private fun maybeRecoverStalledMirror() {
        val server = mirrorServer ?: return
        if (stopped || destroyed || geckoSession == null) return
        val now = System.currentTimeMillis()
        val lastArrival = server.lastFragmentArrivedAtMs
        // v38 起播放器随 server.start 预建（prebuildMirrorPlayer），mirrorPlayer 从 t≈0 就非 null：
        // 零分片超时必须改按"server 启动以来从未收到分片"判定，否则钩子没装上时会话永远挂起。
        // 判定与放弃闩锁收进 MirrorRecoveryPolicy（阈值、时钟口径逐字等价）。
        val decision = mirrorRecovery.decide(
            lastArrivalAtMs = lastArrival,
            startedAtMs = mirrorStartedAtMs,
            elapsedNowMs = android.os.SystemClock.elapsedRealtime(),
            wallNowMs = now,
        )
        if (decision == com.kantv.app.playback.MirrorRecoveryPolicy.Decision.NONE) return
        val watchdogReason = mirrorRecovery.reasonFor(lastArrival)
        if (decision == com.kantv.app.playback.MirrorRecoveryPolicy.Decision.GIVE_UP) {
            // 与原生进度看门狗同一出口：预算耗尽后不再重建，回退上一频道；
            // 没有上一个频道才给一次不阻塞提示，绝不退出播放页、不丢当前频道。
            mirrorDiag.mirrorWatchdogReason = watchdogReason
            mirrorDiag.mirrorStage = "FAILED"
            refreshMirrorDiagnosticCounters()
            diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.RECOVERY_STARTED)
            probeValues = probeValues.copy(webRecoveryStage = "FAILED")
            android.util.Log.w("KanTvMirror",
                "mirror watchdog give up: reason=$watchdogReason attempts=${mirrorRecovery.attemptsUsed()}")
            if (!requestPreviousChannelFallback() && ::statusView.isInitialized) {
                val line = com.kantv.app.playback.PlaybackStatusCopy.failed(
                    channelName = page.name,
                    attempts = mirrorRecovery.attemptsUsed(),
                )
                statusView.showLoading(line.headline, line.detail)
            }
            return
        }
        mirrorDiag.mirrorWatchdogRebuilds++
        mirrorDiag.mirrorWatchdogReason = watchdogReason
        mirrorDiag.mirrorStage = "WATCHDOG_REBUILD"
        mirrorStartedAtMs = android.os.SystemClock.elapsedRealtime()
        refreshMirrorDiagnosticCounters()
        if (BuildConfig.DEBUG) {
            android.util.Log.d("KanTvMirror",
                "stall detected reason=$watchdogReason rebuilding gecko session attempt=${mirrorRecovery.attemptsUsed()}")
        }
        writeDebugState(JSONObject()
            .put("mirrorWatchdogReason", watchdogReason)
            .put("mirrorWatchdogRebuilds", mirrorDiag.mirrorWatchdogRebuilds))
        diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.RECOVERY_STARTED)
        probeValues = probeValues.copy(
            webRecoveryStage = "HARD_RECREATE",
            webRuntimeGeneration = sessionGeneration,
        )
        releaseMirrorPlayer()
        closeWebRuntime()
        createWebRuntime()
    }

    private fun ensureMirrorPlayer() {
        if (stopped || destroyed || isFinishing) return
        // 播放器在 server.start 成功时已预建;这里只做媒体挂载(幂等)。
        if (mirrorPlayer == null) prebuildMirrorPlayer()
        if (mirrorMediaAttached) return
        val playlistUri = mirrorPlaylistUri ?: return
        val player = mirrorPlayer ?: return
        player.setMediaItem(
            androidx.media3.common.MediaItem.Builder()
                .setUri(android.net.Uri.parse(playlistUri))
                .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                .build())
        (mirrorPlayerView as? TextureView)?.let(player::setVideoTextureView)
        player.prepare()
        player.playWhenReady = true
        mirrorMediaAttached = true
    }

    /** 预建播放器与 TextureView（server.start 成功即调用）:建造/视图/监听器的热身开销
     *  不再占用首分片到达后的关键路径;媒体挂载等 server.ready 后由 [ensureMirrorPlayer] 完成。 */
    private fun prebuildMirrorPlayer() {
        if (mirrorPlayer != null || stopped || destroyed || isFinishing) return
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(this).build()
        mirrorDiag.mirrorStage = "PLAYER_CREATED"
        // 用 TextureView 而非 SurfaceView：与 Gecko 页面层混合合成在低端 TV 上更稳，
        // 且 GeckoView 本身就是 SurfaceView，避免双层 Surface z-order 问题。
        val textureView = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    player.setVideoTextureView(this@apply)
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                }

                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true

                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {
                    mirrorDiag.mirrorOutputUpdates++
                }
            }
        }
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() {
                if (startupFirstFrameAtMs == 0L) startupFirstFrameAtMs = android.os.SystemClock.elapsedRealtime()
                if (mirrorDiag.mirrorFirstFrameAtMs == 0L) {
                    mirrorDiag.mirrorFirstFrameAtMs = android.os.SystemClock.elapsedRealtime()
                    // 首帧本身就是输出进度基线；即使设备不再派发 TextureView 更新，
                    // 后续“输入继续、输出不动”也能在 15 秒后被诊断。
                    mirrorDiag.lastOutputProgressAtMs = mirrorDiag.mirrorFirstFrameAtMs
                    mirrorDiag.mirrorStage = "FIRST_FRAME"
                    loadingHint.visibility = View.GONE
                    webStateMirror = "PLAYING"
                    markSavedChannel()
                    // 进程级通知：MainActivity 回流 firstFrame（WEB_EMBED 会话不再恒 FAIL）。
                    com.kantv.app.diagnostics.WebPlaybackNotifier.notifyFirstFrame(
                        diagnosticSessionId, routeGeneration)
                    // 只有镜像链路真的是出画路径时，"镜像首帧"才等于"恢复成功"。
                    // 原生页（NATIVE_PAGE）会话里镜像分片恒为 0，此前这里无条件发射
                    // RECOVERY_SUCCEEDED，给复盘留下"已恢复"的假信号（2026-09-20 修正）。
                    if (playbackStrategy.usesMseMirror) {
                        probeValues = probeValues.copy(webRecoveryStage = "IDLE")
                        diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.RECOVERY_SUCCEEDED)
                    }
                    if (!mirrorDiag.firstFrameMilestoneRecorded) {
                        mirrorDiag.firstFrameMilestoneRecorded = true
                        refreshMirrorDiagnosticCounters()
                        diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.MIRROR_FIRST_FRAME)
                    }
                    writeDebugState(JSONObject()
                        .put("firstFrameElapsedMs", com.kantv.app.playback.startupFirstFrameElapsedMs(
                            startupPageStartedAtMs, startupFirstFrameAtMs)))
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (BuildConfig.DEBUG) android.util.Log.d("KanTvMirror", "player error: ${error.errorCodeName}")
                mirrorDiag.mirrorStage = "PLAYER_ERROR"
                probeValues = probeValues.copy(
                    errorCode = error.errorCode,
                    rendererState = player.playbackState,
                    mirrorStage = mirrorDiag.mirrorStage,
                )
                diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.PLAYBACK_FAULT)
            }
        })
        // 插到 GeckoView 之上、loadingHint/浮层之下。媒体挂载推迟到 server.ready
        // (ensureMirrorPlayer),避免播放器对着尚无分片的清单打 503 错误。
        val insertIndex = geckoView?.let { container.indexOfChild(it) + 1 }?.coerceAtLeast(1) ?: 1
        container.addView(textureView, insertIndex, FrameLayout.LayoutParams(-1, -1))
        // 时序兜底：镜像播放器晚于浮层建成时，新 View 不得带着焦点资格加入围栏外。
        if (browseShowing) textureView.isFocusable = false
        mirrorPlayer = player
        mirrorPlayerView = textureView
        if (!mirrorDiag.playerCreatedMilestoneRecorded) {
            mirrorDiag.playerCreatedMilestoneRecorded = true
            refreshMirrorDiagnosticCounters()
            diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.MIRROR_PLAYER_CREATED)
        }
    }

    private fun releaseMirrorPlayer() {
        mirrorPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        mirrorPlayer = null
        mirrorMediaAttached = false
        mirrorPlayerView?.let { view -> runCatching { (view.parent as? android.view.ViewGroup)?.removeView(view) } }
        mirrorPlayerView = null
    }

    private fun stopMirrorPlayback() {
        releaseMirrorPlayer()
        mirrorServer?.close()
        mirrorServer = null
        mirrorPlaylistUri = null
    }

    // ===== 页面状态观测（content script 每秒 push；主体纯搬移至 OfficialWebPageStateMirror）=====

    private fun processProbeState(state: JSONObject, hostPostLagMs: Long) = pageState.processProbeState(state, hostPostLagMs)

    /** 接受 bridge 已白名单化的固定枚举、布尔和有界数值，不接受页面自由文本。 */
    private fun processPresentationState(state: JSONObject) = pageState.processPresentationState(state)

    private fun refreshPresentationDiagnostics() = pageState.refreshPresentationDiagnostics()

    /** Rolling debug snapshot for emulator acceptance; contains fixed identifiers and counters only. */
    private fun writeProbeDebugState() = pageState.writeProbeDebugState()

    private fun updateLoadingHint() = pageState.updateLoadingHint()

    /**
     * 用正常呈现策略重启本页（登录完成时调用一次）。
     *
     * 呈现模式在 Activity 启动时确定，所以同一个实例没法"登录后再铺满"；重启是最可靠的做法，
     * 而且等价于用户重新进入频道——正好也是我们承诺的行为。
     */
    private fun restartWithNormalPresentation() {
        if (restartedAfterLogin) return
        // **只有从登录阶段启动的实例才需要重启。**
        // 重启出来的新实例已经不带登录阶段标记了，而登录判定在它里面照样成立
        // （站点已登录、页面在播），少了这个判断就会无限重启——实测每 1.4 秒一次、
        // 一分钟内堆出 200 多个会话。这是用户看到的"无限刷新页面"。
        if (!intent.getBooleanExtra(GeckoPresentationMode.EXTRA_LOGIN_PENDING, false)) return
        restartedAfterLogin = true
        runCatching {
            val next = android.content.Intent(intent)
            next.removeExtra(GeckoPresentationMode.EXTRA_LOGIN_PENDING)
            startActivity(next)
            finish()
        }
    }

    /**
     * 让页面自己的登录入口把二维码弹出来。
     *
     * 页面源码里那个"登录"是 `<a onclick="ShowUserLogin()">登录</a>`——**没有 href，只调用一个
     * 全局函数**（实测取自网站页面源码）。所以不必再模拟点击：合成点击要穿过事件可信度与
     * 用户激活两道关卡，而这里走的是页面自己的登录流程入口。
     *
     * 更关键的是：函数可调用就意味着**不必等页面把免费试用跑到 403**——第一次打开这个频道
     * 就能把二维码摆给用户扫，这正是用户要求的顺序。
     *
     * 经 `loadUri("javascript:")` 注入，运行在页面自己的上下文里；**必须 void 返回**，
     * 否则返回值会替换文档。
     */
    private fun requestPageLogin() {
        val session = geckoSession ?: return
        runCatching {
            // 极简形式：单条表达式，不用 IIFE、不用 try/catch。
            // 之前那版有语法错误（unexpected token ')'），**每一次注入都没执行**——
            // 这正是"坐标对、事件也送达，页面却毫无反应"的真正原因。
            session.loadUri("javascript:window.ShowUserLogin&&window.ShowUserLogin('')")
        }
    }

    /**
     * 在页面报来的坐标上合成一次真实触摸。
     *
     * GeckoView 收到的是平台输入，因此页面把它当作**用户自己的点击**（可信手势）——这正是
     * "登录二维码必须由真实手势触发"这类站点的唯一可行通道。坐标由注入脚本按
     * `getBoundingClientRect() × devicePixelRatio` 给出（已包含 app 的页面适配变换），
     * 这里只做视口范围内的钳制；越界或视图未就绪就放弃，绝不做第二次。
     */
    private fun tapLoginEntry(x: Int, y: Int) {
        val view = geckoView ?: return
        if (view.width <= 0 || view.height <= 0) return
        val px = x.coerceIn(0, view.width - 1).toFloat()
        val py = y.coerceIn(0, view.height - 1).toFloat()
        val now = android.os.SystemClock.uptimeMillis()
        runCatching {
            val down = android.view.MotionEvent.obtain(
                now, now, android.view.MotionEvent.ACTION_DOWN, px, py, 0)
            // 必须声明输入来源：source=0 的事件 GeckoView 不接受，点下去等于什么都没发生
            // （实测：坐标正确、点击发出，页面毫无反应，就是因为漏了这一行）。
            down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)
            view.dispatchTouchEvent(down)
            down.recycle()
            val up = android.view.MotionEvent.obtain(
                now, now + 60L, android.view.MotionEvent.ACTION_UP, px, py, 0)
            up.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)
            view.dispatchTouchEvent(up)
            up.recycle()
        }
    }

    /**
     * 周期性的画面证据采集入口（由进度看门狗按节拍调用）。
     * 真正读回在 [verifyNativeVisualOutput]；结果只作为证据交给策略，不在此处做任何重建决策。
     */
    private fun observeNativeVisualOutput() {
        if (stopped || destroyed || isFinishing) return
        if (nativeVisualProbeInFlight) return
        verifyNativeVisualOutput()
    }

    /**
     * DOM time/decoded-frame progress is input evidence, not proof that Android composited a frame.
     * Sample a 16x9 grid from Gecko's in-memory output, retain no pixels, and persist only fixed
     * enums/counters.
     */
    private fun verifyNativeVisualOutput() {
        val view = geckoView ?: return
        if (nativeVisualProbeInFlight || stopped || destroyed || isFinishing) return
        val observedGeneration = sessionGeneration
        nativeVisualProbeInFlight = true
        // 耗时测量的基准必须在这里取：`observeNativeVisualOutput()` 是可选入口，
        // 首帧路径（nativeFirstProgress）不经过它，若在那里赋值会留下 0 值，
        // 把"开机至今"误算成延迟（本次测量第一个样本 2049970ms 就是这么来的）。
        val issuedAtMs = android.os.SystemClock.elapsedRealtime()
        val captureSequence = ++visualCaptureSequence
        probeValues = probeValues.copy(geckoObserver = probeValues.geckoObserver.copy(
            geckoCaptureSequence = captureSequence, geckoCaptureApiCallMs = -1,
            geckoCaptureIssueToListenerMs = -1, geckoCaptureClassifyMs = -1, geckoCapturePostLagMs = -1,
        ))
        var apiCallMs = -1L
        fun recordCapture(listenerAtMs: Long, classifyMs: Long, postedAtMs: Long) {
            // This guard only protects the new observation. Existing visual policy is unchanged.
            if (observedGeneration != sessionGeneration || captureSequence != visualCaptureSequence) return
            val appliedAtMs = android.os.SystemClock.elapsedRealtime()
            probeValues = probeValues.copy(geckoObserver = probeValues.geckoObserver.copy(
                geckoCaptureSequence = captureSequence, geckoCaptureApiCallMs = apiCallMs,
                geckoCaptureIssueToListenerMs = (listenerAtMs - issuedAtMs).coerceIn(0L, 86_400_000L),
                geckoCaptureClassifyMs = classifyMs,
                geckoCapturePostLagMs = (appliedAtMs - postedAtMs).coerceIn(0L, 86_400_000L),
            ))
        }
        probeIssuedAtMs = issuedAtMs
        probeTimedOut = false
        val apiStartedAtMs = android.os.SystemClock.elapsedRealtime()
        runCatching {
            val capture = view.capturePixels()
            apiCallMs = (android.os.SystemClock.elapsedRealtime() - apiStartedAtMs).coerceIn(0L, 86_400_000L)
            probeValues = probeValues.copy(geckoObserver = probeValues.geckoObserver.copy(geckoCaptureApiCallMs = apiCallMs))
            capture.accept({ bitmap ->
                val listenerAtMs = android.os.SystemClock.elapsedRealtime()
                val signal = bitmap?.let { captured ->
                    runCatching {
                        GeckoVisualOutputClassifier.classify(sampleVisualGrid(captured))
                    }.getOrDefault(GeckoVisualOutputSignal.NO_SAMPLE)
                } ?: GeckoVisualOutputSignal.NO_SAMPLE
                runCatching { bitmap?.recycle() }
                val postedAtMs = android.os.SystemClock.elapsedRealtime()
                val classifyMs = (postedAtMs - listenerAtMs).coerceIn(0L, 86_400_000L)
                handler.post {
                    if (observedGeneration != sessionGeneration || stopped || destroyed || isFinishing) {
                        return@post
                    }
                    recordCapture(listenerAtMs, classifyMs, postedAtMs)
                    nativeVisualProbeInFlight = false
                    // 像素结果只作为**证据**交给看门狗统一决策（不再由像素路径自行重建），
                    // 这样"时钟在走"与"画面没出来"两条证据不会互相抵消。
                    val observedAtMs = android.os.SystemClock.elapsedRealtime()
                    lastVisualSampleOutcome = visualOutcomeName(signal)
                    lastVisualSampleCallbackMs = (observedAtMs - probeIssuedAtMs).coerceAtLeast(0L)
                    if (signal == GeckoVisualOutputSignal.VISIBLE_DETAIL) {
                        nativeProgressPolicy.observePictureVerified(observedAtMs)
                        // 启动期（页面还没自称在播）绝不把像素证据当作"首帧已出"：
                        // 那时屏幕上多半是页面的加载图/首页推荐，不是视频帧。只有页面自己
                        // 报告进入播放（readyState≥2 且未暂停）之后，像素确认才算数。
                        if (pageClaimsNativePlayback()) markNativeFirstFrameVerified()
                    } else {
                        // FLAT_NEUTRAL / FLAT_OTHER / NO_SAMPLE 一律视为"没有画面证据"：
                        // 后者覆盖 capturePixels() 拿不到位图那一支（本次排查的竞争假设之一）。
                        nativeProgressPolicy.observePictureMissing(observedAtMs)
                        if (signal != GeckoVisualOutputSignal.NO_SAMPLE) {
                            diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.GREY_SCREEN_EVIDENCE)
                        }
                    }
                }
            }, {
                val listenerAtMs = android.os.SystemClock.elapsedRealtime()
                handler.post {
                    if (observedGeneration == sessionGeneration) {
                        recordCapture(listenerAtMs, -1, listenerAtMs)
                        nativeVisualProbeInFlight = false
                        // 读回失败同样是**结果**，必须记下来：以前这条路径什么都不写，
                        // 报告里就只剩 "unknown"，既不是"没采到"也不是"画面静止"——
                        // 第一财经"页面在播却一直显示正在加载"就是被这个空洞藏住的。
                        lastVisualSampleOutcome = "NO_SAMPLE"
                        lastVisualSampleCallbackMs =
                            (android.os.SystemClock.elapsedRealtime() - probeIssuedAtMs).coerceAtLeast(0L)
                        nativeProgressPolicy.observePictureMissing(android.os.SystemClock.elapsedRealtime())
                    }
                }
            })
        }.onFailure {
            if (observedGeneration == sessionGeneration) {
                if (captureSequence == visualCaptureSequence) {
                    apiCallMs = (android.os.SystemClock.elapsedRealtime() - apiStartedAtMs).coerceIn(0L, 86_400_000L)
                    probeValues = probeValues.copy(geckoObserver = probeValues.geckoObserver.copy(geckoCaptureApiCallMs = apiCallMs))
                }
                nativeVisualProbeInFlight = false
                lastVisualSampleOutcome = "NO_SAMPLE"
                lastVisualSampleCallbackMs =
                    (android.os.SystemClock.elapsedRealtime() - probeIssuedAtMs).coerceAtLeast(0L)
                nativeProgressPolicy.observePictureMissing(android.os.SystemClock.elapsedRealtime())
            }
        }
    }

    private fun sampleVisualGrid(bitmap: android.graphics.Bitmap): IntArray {
        if (bitmap.width <= 0 || bitmap.height <= 0) return IntArray(0)
        return IntArray(VISUAL_SAMPLE_COLUMNS * VISUAL_SAMPLE_ROWS) { index ->
            val column = index % VISUAL_SAMPLE_COLUMNS
            val row = index / VISUAL_SAMPLE_COLUMNS
            val x = ((column + 1) * bitmap.width / (VISUAL_SAMPLE_COLUMNS + 1))
                .coerceIn(0, bitmap.width - 1)
            val y = ((row + 1) * bitmap.height / (VISUAL_SAMPLE_ROWS + 1))
                .coerceIn(0, bitmap.height - 1)
            bitmap.getPixel(x, y)
        }
    }

    /**
     * 页面是否**自称**进入播放（`readyState≥2` 且未暂停）。
     *
     * 这是"启动期"与"运行期"的分界。启动期屏幕上可能已经有内容（加载图、首页推荐、广告），
     * 但那些都不是视频帧——只有页面自报在播，像素证据才能被当作"画面已出"。
     */
    private fun pageClaimsNativePlayback(): Boolean =
        !playbackStrategy.usesMseMirror && probeValues.readyState >= 2 && !probeValues.paused

    private fun markNativeFirstFrameVerified() {
        // 本次启动的首帧证据独立于一次性上报闩锁：恢复后画面被重新确认时也要刷新它。
        if (startupFirstFrameAtMs == 0L) startupFirstFrameAtMs = android.os.SystemClock.elapsedRealtime()
        if (mirrorDiag.firstFrameMilestoneRecorded) return
        mirrorDiag.firstFrameMilestoneRecorded = true
        mirrorDiag.mirrorFirstFrameAtMs = android.os.SystemClock.elapsedRealtime()
        webStateMirror = "PLAYING"
        mirrorDiag.mirrorStage = "NATIVE_PAGE_PROGRESS"
        probeValues = probeValues.copy(webState = webStateMirror, mirrorStage = mirrorDiag.mirrorStage)
        markSavedChannel()
        com.kantv.app.diagnostics.WebPlaybackNotifier.notifyFirstFrame(
            diagnosticSessionId, routeGeneration)
        refreshMirrorDiagnosticCounters()
        diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.NATIVE_PAGE_PROGRESS)
        android.util.Log.i(
            "KanTvNativeWatchdog",
            "first frame verified: sincePageStart=${mirrorDiag.mirrorFirstFrameAtMs - startupPageStartedAtMs}ms" +
                " nodeReady0=${elapsedSinceOrNone(pageState.nodeReady0AtMs, mirrorDiag.mirrorFirstFrameAtMs)}" +
                " metadata=${elapsedSinceOrNone(pageState.metadataAtMs, mirrorDiag.mirrorFirstFrameAtMs)}" +
                " rebuildAt=${elapsedSinceOrNone(lastRebuildAtMs, mirrorDiag.mirrorFirstFrameAtMs)}",
        )
        // 恢复成功给一次确认（仅当本轮真的做过恢复动作），1.5 秒后自动消失。
        if (nativeProgressPolicy.recoveriesUsed() > 0 || hadRecoveryThisSession) {
            hadRecoveryThisSession = false
            // 这里才是"恢复成功"的**真实**证据：画面确实出帧了。
            // （镜像链路的 RECOVERY_SUCCEEDED 只在镜像真的是出画路径时才发，见 onRenderedFirstFrame。）
            probeValues = probeValues.copy(webRecoveryStage = "IDLE")
            diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.RECOVERY_SUCCEEDED)
            val line = com.kantv.app.playback.PlaybackStatusCopy.recovered()
            statusView.showRecovered(line.headline, line.detail)
        } else {
            updateLoadingHint()
        }
    }

    /** 诊断用的"从某时刻到现在"耗时；时刻未记录过时返回 -1。 */
    private fun elapsedSinceOrNone(sinceMs: Long, nowMs: Long): Long =
        if (sinceMs > 0L) nowMs - sinceMs else -1L

    private fun markSavedChannel() {
        if (savedChannel) return
        savedChannel = true
        intent.getStringExtra("channelId")?.let { id ->
            getSharedPreferences("opened_channels", MODE_PRIVATE).edit().putBoolean(id, true).apply()
            lifecycleScope.launch { AppPreferences(this@GeckoMirrorLiveActivity).saveLastChannelId(id) }
        }
    }

    // ===== 诊断 =====

    private fun beginDiagnosticSession() {
        if (!BuildConfig.KANTV_DIAGNOSTICS) return
        com.kantv.app.diagnostics.DiagnosticUploadConfig.initializeBundledDefaults(this)
        mirrorDiag.diagnosticStartedAtMs = android.os.SystemClock.elapsedRealtime()
        probeValues = com.kantv.app.diagnostics.DiagnosticPlaybackValues.EMPTY.copy(
            routeGeneration = routeGeneration,
            mirrorStage = mirrorDiag.mirrorStage,
            mirrorNavigationState = mirrorDiag.mirrorNavigationState,
            mirrorExtensionState = mirrorDiag.mirrorExtensionState,
            geckoPlaybackStrategy = playbackStrategy.name,
            geckoPresentationMode = presentationMode.name,
        )
        diagnosticRecorder.start(page.tvgId,
            com.kantv.app.diagnostics.DiagnosticProvider.valueOf(page.provider.name), diagnosticSessionId,
            mirrorDiag.diagnosticStartedAtMs)
    }

    /** Content script 上报的是页面内累计数；导航重建时从零开始，按增量汇总到会话。 */
    private fun recordBridgeMirrorMetrics(workerSwitches: Long, instanceSwitches: Long) {
        mirrorDiag.recordBridgeMetrics(workerSwitches, instanceSwitches)
        refreshMirrorDiagnosticCounters()
    }

    /** 将服务器内的接受/拒绝计数与页面桥计数写入诊断采样，不携带 URL 或媒体字节。 */
    private fun refreshMirrorDiagnosticCounters() {
        val result = mirrorDiag.refreshCounters(
            snapshot = mirrorServer?.diagnosticsSnapshot(),
            mirrorServerStartedAtMs = mirrorServerStartedAtMs,
            nowMs = android.os.SystemClock.elapsedRealtime(),
            base = probeValues,
            playbackStrategyName = playbackStrategy.name,
            presentationModeName = presentationMode.name,
            routeGeneration = routeGeneration,
        )
        probeValues = result.values
        result.events.forEach { (kind, stage) ->
            diagnosticEvent(kind, probeValues.copy(mirrorStage = stage))
        }
    }

    private fun diagnosticEvent(
        kind: com.kantv.app.diagnostics.DiagnosticEntryKind,
        values: com.kantv.app.diagnostics.DiagnosticPlaybackValues = probeValues,
    ) {
        if (BuildConfig.KANTV_DIAGNOSTICS && diagnosticSessionId != 0L) {
            val observed = if (playbackStrategy.usesMseMirror) values else
                pageState.diagnosticValues(android.os.SystemClock.elapsedRealtime(), values)
            diagnosticRecorder.event(diagnosticSessionId, kind, values = observed)
        }
    }

    private fun finalizeDiagnosticSession(kind: com.kantv.app.diagnostics.DiagnosticEntryKind) {
        if (!BuildConfig.KANTV_DIAGNOSTICS || diagnosticSessionId == 0L) return
        diagnosticEvent(kind)
    }

    /** 结束采样循环（退后台/销毁）；回前台由 [startDiagnosticSampling] 重新拉起。 */
    private fun stopDiagnosticSampling() {
        if (!com.kantv.app.playback.DiagnosticSamplingGuard.shouldStop(
                diagnosticSamplingJob?.isActive == true)) return
        diagnosticSamplingJob?.cancel()
        diagnosticSamplingJob = null
    }

    private fun startDiagnosticSampling() {
        if (!BuildConfig.KANTV_DIAGNOSTICS) return
        // 幂等：回前台会再次调用，已经在跑就不重复起循环。
        if (!com.kantv.app.playback.DiagnosticSamplingGuard.shouldStart(
                diagnosticSamplingJob?.isActive == true)) return
        diagnosticSamplingJob = lifecycleScope.launch {
            var pssAtMs = 0L
            var pssKb = -1L
            while (isActive && !stopped && !destroyed) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - pssAtMs >= 30_000) {
                    // getPss() 扫 /proc 是十 ms 级开销，移出主线程；读数只进诊断报告。
                    pssKb = withContext(kotlinx.coroutines.Dispatchers.Default) {
                        runCatching { android.os.Debug.getPss().toLong() }.getOrDefault(-1L)
                    }
                    pssAtMs = now
                }
                refreshMirrorDiagnosticCounters()
                if (!playbackStrategy.usesMseMirror) probeValues = pageState.diagnosticValues(now)
                // 镜像链路才有 ExoPlayer：NATIVE_PAGE 的状态/缓冲事实来自页面，
                // 不能用空 mirrorPlayer 的 0/-1 覆盖掉。
                val mirror = if (playbackStrategy.usesMseMirror) mirrorPlayer else null
                val playbackState = mirror?.playbackState ?: probeValues.playbackStateCode
                val positionMs = mirror?.currentPosition ?: -1L
                val bufferedPositionMs = mirror?.bufferedPosition ?: probeValues.bufferedPositionMs
                val bufferDurationMs = when {
                    mirror == null -> probeValues.bufferDurationMs
                    positionMs >= 0L && bufferedPositionMs >= 0L ->
                        (bufferedPositionMs - positionMs).coerceAtLeast(0L)
                    else -> -1L
                }
                diagnosticRecorder.sample(com.kantv.app.diagnostics.OfficialWebDiagnosticSample(
                    diagnosticSessionId, now,
                    probeValues.copy(
                        processPssKb = pssKb,
                        measurement = if (playbackStrategy.usesMseMirror)
                            com.kantv.app.diagnostics.DiagnosticPlaybackValues.MeasurementEvidence()
                            else probeValues.measurement,
                        webState = webStateMirror,
                        webRuntimeGeneration = sessionGeneration,
                        playbackStateCode = playbackState,
                        rendererState = if (mirror != null) playbackState else probeValues.rendererState,
                        buffering = if (mirror != null)
                            playbackState == androidx.media3.common.Player.STATE_BUFFERING else probeValues.buffering,
                        bufferedPositionMs = bufferedPositionMs,
                        bufferDurationMs = bufferDurationMs,
                        presentedFrames = if (playbackStrategy.usesMseMirror)
                            com.kantv.app.diagnostics.presentedFramesForMirrorSample(
                                usesMseMirror = true,
                                mirrorOutputUpdates = mirrorDiag.mirrorOutputUpdates,
                            ) else probeValues.presentedFrames,
                        // ===== 完整诊断：合成层 / 决策层 / 上下文层证据 =====
                        visual = com.kantv.app.diagnostics.DiagnosticPlaybackValues.VisualEvidence(
                            sampleOutcome = lastVisualSampleOutcome,
                            sampleCallbackMs = lastVisualSampleCallbackMs,
                            millisSinceVerified = nativeProgressPolicy.millisSincePictureVerified(now),
                        ),
                        watchdog = com.kantv.app.diagnostics.DiagnosticPlaybackValues.WatchdogEvidence(
                            wedgeActive = wedgeActive(),
                            decision = lastWatchdogDecision,
                            reason = lastWatchdogReason,
                            recoveries = nativeProgressPolicy.recoveriesUsed(),
                            recoveryLimit = NATIVE_PROGRESS_MAX_RECOVERIES,
                            sampleSkipReason = lastSampleSkipReason,
                            probeTimedOut = probeTimedOut,
                        ),
                        context = com.kantv.app.diagnostics.DiagnosticPlaybackValues.ContextEvidence(
                            networkTransport = networkTransportName(),
                            networkMetered = networkMeteredFlag(),
                            millisSinceHeartbeat = now - lastHeartbeatAtMs,
                            samplingSuspended = samplingSuspendedAtMs > 0L || resumeSuspendedMs >= 0L,
                            samplingSuspendedMs = if (samplingSuspendedAtMs > 0L) {
                                now - samplingSuspendedAtMs
                            } else {
                                resumeSuspendedMs
                            },
                        ),
                    ),
                    menuVisible = menu?.isShowing == true || browseShowing,
                ))
                if (now - lastHeartbeatAtMs >= HEARTBEAT_INTERVAL_MS) {
                    lastHeartbeatAtMs = now
                    diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.HEARTBEAT)
                    // 单行自包含摘要：用户只需贴这一行即可定性（AI 读，用户不必看）。
                    android.util.Log.i("KanTvDiag", diagnosticSummaryLine())
                }
                delay(3_000)
            }
        }
    }

    // ===== 退出/换台契约（沿用原 WebView 宿主的同一契约）=====

    private fun routeResultIntent(): Intent = Intent()
        .putExtra(PlaybackRouteIntent.EXTRA_SESSION_ID, diagnosticSessionId)
        .putExtra(PlaybackRouteIntent.EXTRA_ROUTE_GENERATION, routeGeneration)

    /**
     * 自愈全部失败后的回退：请宿主把我送回上一个能看的频道。
     * 无感标准——宁可回到上一个频道，也不要把用户留在黑屏或频道列表。
     */
    private fun requestPreviousChannelFallback(): Boolean {
        val previous = intent.getStringExtra(
            com.kantv.app.playback.PlaybackFallbackContract.EXTRA_PREVIOUS_CHANNEL_ID,
        ) ?: return false
        if (previous.isBlank()) return false
        finalizeDiagnosticSession(com.kantv.app.diagnostics.DiagnosticEntryKind.SWITCH)
        setResult(Activity.RESULT_OK, routeResultIntent()
            .putExtra("selectedChannelId", previous)
            .putExtra(com.kantv.app.playback.PlaybackFallbackContract.RESULT_CHANNEL_FALLBACK, true))
        finish()
        return true
    }

    private fun leave(offset: Int) {
        if (offset == 0) { showChannelBrowser(); return }
        finalizeDiagnosticSession(if (offset > 0) com.kantv.app.diagnostics.DiagnosticEntryKind.SWITCH
            else com.kantv.app.diagnostics.DiagnosticEntryKind.STOP)
        setResult(Activity.RESULT_OK, routeResultIntent().putExtra("channelOffset", offset))
        finish()
    }

    private fun selectBrowsedChannel(channelId: String, sourceIndex: Int?) {
        finalizeDiagnosticSession(com.kantv.app.diagnostics.DiagnosticEntryKind.SWITCH)
        setResult(RESULT_OK, routeResultIntent().putExtra("selectedChannelId", channelId).apply {
            if (sourceIndex != null) putExtra("sourceIndex", sourceIndex)
        })
        finish()
    }

    private fun showChannelBrowser() {
        menu?.dismiss()
        val generation = ++browseLoadGeneration
        lifecycleScope.launch {
            val (ordered, anchor) = withContext(Dispatchers.IO) {
                val ordered = ChannelDirectory.ordered(browseRepository.observeChannels().first())
                val anchor = ChannelBrowseAnchor.resolve(
                    channels = ordered,
                    currentChannelId = intent.getStringExtra("channelId"),
                    currentTvgId = intent.getStringExtra("channelTvgId") ?: page.tvgId,
                    idOf = { it.id },
                    tvgIdOf = { it.tvgId },
                    sourceUrisOf = { channel -> browseRepository.playbackRequests(channel.id).map { it.uri } },
                )
                ordered to anchor
            }
            // 加载期间又打开/关闭了浏览：旧结果作废，绝不让过期列表落屏。
            if (destroyed || generation != browseLoadGeneration) return@launch
            browseChannels = ordered
            browseCurrent = anchor
            when (val landing = ChannelBrowseNav.landing(
                anchor?.let { ChannelBrowseNav.CurrentChannel(ChannelDirectory.category(it)) },
            )) {
                ChannelBrowseNav.Landing.BrowseGroups -> showBrowseGroups()
                is ChannelBrowseNav.Landing.BrowseChannels -> showBrowseChannels(landing.group)
            }
        }
    }

    // ===== 频道浏览浮层（与 MainActivity 共用 ChannelBrowsePanel，客户端重构 2-4）=====

    /** 浏览渲染层宿主回调：只经此通信，换台/设置/离开等语义留在本 Activity。 */
    private val browseHost = object : ChannelBrowsePanel.Host {
        override fun channels(): List<ChannelEntity> = browseChannels
        override fun currentChannel(): ChannelEntity? = browseCurrent
        override fun play(channel: ChannelEntity, sourceIndex: Int?) = browsePlay(channel, sourceIndex)
        override fun showChannelSources(channel: ChannelEntity) =
            this@GeckoMirrorLiveActivity.showChannelSources(channel)
        override fun showGroupChannels(groupTitle: String?) = showBrowseChannels(groupTitle)
        override fun showSettings() {
            setResult(RESULT_OK, routeResultIntent().putExtra("destination", "settings"))
            finish()
        }
        override fun notifyProgrammeUnavailable(message: String) {
            Toast.makeText(this@GeckoMirrorLiveActivity, message, Toast.LENGTH_SHORT).show()
        }
        override suspend fun loadEpg(refs: List<EpgChannelRef>) =
            DefaultEpgRepository.shared(this@GeckoMirrorLiveActivity).load(refs)
        override fun dismissBrowse() = dismissChannelBrowse()
    }

    private fun showBrowseGroups() {
        val fromBrowseDepth = browseScreen in setOf(ChannelBrowseNav.Page.CHANNELS, ChannelBrowseNav.Page.GUIDE)
        clearBrowseOverlay()
        setUnderlyingSurfacesFocusable(false)
        browseRoot.bringToFront()
        browseScreen = ChannelBrowseNav.Page.GROUPS
        browsePanel.showGroups(resetGroupMemory = !fromBrowseDepth)
    }

    private fun showBrowseChannels(groupTitle: String?) {
        clearBrowseOverlay()
        setUnderlyingSurfacesFocusable(false)
        browseRoot.bringToFront()
        browseScreen = ChannelBrowseNav.Page.CHANNELS
        browsePanel.showChannels(groupTitle)
    }

    private fun showBrowseGuide(channel: ChannelEntity) {
        clearBrowseOverlay()
        setUnderlyingSurfacesFocusable(false)
        browseRoot.bringToFront()
        browseScreen = ChannelBrowseNav.Page.GUIDE
        browsePanel.showGuide(channel)
    }

    /**
     * 浮层焦点围栏（reviewer 静态复核回归修复）：GeckoView 与镜像 TextureView 都是 browseRoot
     * 的兄弟节点（同一 FrameLayout）且默认可聚焦；GUIDE 页方向键 PASS_THROUGH 走系统焦点遍历
     * 时，FocusFinder 会把焦点移到它们之一，浮层未关后续按键就直接进网页。浮层显示期间剥夺
     * 焦点资格、清场时恢复；登录流程等正常态依赖的 geckoView.requestFocus() 不受影响。
     */
    private fun setUnderlyingSurfacesFocusable(focusable: Boolean) {
        geckoView?.isFocusable = focusable
        mirrorPlayerView?.isFocusable = focusable
    }

    /** 整层清场：断开垂直焦点链、作废未完成的 EPG 填充，网页与播放器子 View 不受影响。 */
    private fun clearBrowseOverlay() {
        if (!::browsePanel.isInitialized) return
        browsePanel.onHidden()
        browseRoot.removeAllViews()
    }

    /** 关浏览浮层回网页画面（GROUPS 页再退的落点；原 overlay onDismiss 无额外动作）。 */
    private fun dismissChannelBrowse() {
        browseLoadGeneration++
        browseScreen = null
        clearBrowseOverlay()
        setUnderlyingSurfacesFocusable(true)
    }

    /** 浏览页键位判定委托给纯状态机；非浏览页或非方向/返回键返回 null（不拦截）。 */
    private fun browseDecision(keyCode: Int, repeatCount: Int = 0): ChannelBrowseNav.Decision? {
        val page = browseScreen ?: return null
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

    /** 浏览浮层键处理：判定全部交给 ChannelBrowseNav，语义与 MainActivity 浏览页一致。 */
    private fun handleBrowseKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val keyCode = event.keyCode
        if (browseDecision(keyCode) == ChannelBrowseNav.Decision.MOVE_FOCUS) {
            if (browsePanel.moveVerticalFocus(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)) return true
        }
        // 频道列表里右键 = 直接看（短按播放）；长按右键展开操作面板。须先于方向键闸门。
        if (browseScreen == ChannelBrowseNav.Page.CHANNELS && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val decision = browseDecision(keyCode, event.repeatCount)
            (currentFocus?.tag as? ChannelEntity)?.let { channel ->
                when (decision) {
                    ChannelBrowseNav.Decision.PLAY -> browsePlay(channel, null)
                    ChannelBrowseNav.Decision.SHOW_ACTIONS -> showChannelActions(channel)
                    else -> Unit
                }
            }
            return true
        }
        when (browseDecision(keyCode, event.repeatCount)) {
            ChannelBrowseNav.Decision.OPEN_FOCUSED_GROUP -> {
                currentFocus?.takeIf { it.tag is ChannelBrowsePanel.GroupTarget }?.performClick()
                return true
            }
            ChannelBrowseNav.Decision.CONSUME -> return true
            ChannelBrowseNav.Decision.RETREAT -> {
                when (ChannelBrowseNav.retreat(browseScreen ?: return true)) {
                    ChannelBrowseNav.Retreat.BROWSE_GROUPS -> showBrowseGroups()
                    ChannelBrowseNav.Retreat.BROWSE_CHANNELS -> showBrowseChannels(browsePanel.currentGroupTitle)
                    ChannelBrowseNav.Retreat.DISMISS -> dismissChannelBrowse()
                }
                return true
            }
            ChannelBrowseNav.Decision.DISMISS -> {
                dismissChannelBrowse()
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 浏览层播放语义（与原网页宿主浮层 onPlay 同口径）：
     * 选回正在播的同一频道且不指定来源 = 只关浮层回网页画面；其余走换台契约回传宿主。
     */
    private fun browsePlay(channel: ChannelEntity, sourceIndex: Int?) {
        if (channel.id == intent.getStringExtra("channelId") && sourceIndex == null) {
            dismissChannelBrowse()
        } else {
            selectBrowsedChannel(channel.id, sourceIndex)
        }
    }

    /** 操作面板：B 套独立 ACTIONS 页并入 A 套 PlaybackMenuOverlay，与 MainActivity 一致。 */
    private fun showChannelActions(channel: ChannelEntity) {
        menu?.show(
            channel.name,
            "确定看这个频道吗？也可以查看节目单或选择播放来源。",
            listOf(
                PlaybackMenuAction("看这个频道") { browsePlay(channel, null) },
                PlaybackMenuAction("查看节目单") { showBrowseGuide(channel) },
                PlaybackMenuAction("选择播放来源") { showChannelSources(channel) },
            ),
        )
    }

    /** 来源选择：B 套独立 SOURCES 页并入 A 套底部菜单浮层，与 MainActivity 同一份标签口径。 */
    private fun showChannelSources(channel: ChannelEntity) {
        lifecycleScope.launch {
            val requests = withContext(Dispatchers.IO) { browseRepository.playbackRequests(channel.id) }
            if (destroyed || isFinishing || !browseShowing || requests.isEmpty()) return@launch
            menu?.show(
                "${channel.name} · 选择播放来源",
                "确认使用所选来源 · 返回键取消",
                ChannelSourceLabels.labels(requests.map { it.uri }).mapIndexed { index, label ->
                    PlaybackMenuAction(label) { browsePlay(channel, index) }
                },
            )
        }
    }

    private fun showMainMenu() {
        menu?.show("看电视", actions = listOf(
            PlaybackMenuAction("继续看电视") {},
            PlaybackMenuAction("频道与节目表", ::showChannelBrowser),
            PlaybackMenuAction("设置") {
                setResult(RESULT_OK, routeResultIntent().putExtra("destination", "settings"))
                finish()
            },
            PlaybackMenuAction("退出看电视") {
                setResult(RESULT_OK, routeResultIntent().putExtra("destination", "exit"))
                finish()
            },
        ))
    }

    private fun showControls() {
        val quality = if (probeValues.height > 0) "${probeValues.height}p" else "正在读取"
        menu?.show(
            page.name,
            "官网直播 · 与媒体播放器使用相同的底部控制浮窗",
            listOf(
                PlaybackMenuAction("画质 · $quality") {},
                PlaybackMenuAction("重新连接", ::reload),
                PlaybackMenuAction("频道与节目表", ::showChannelBrowser),
                PlaybackMenuAction("设置") {
                    setResult(RESULT_OK, routeResultIntent().putExtra("destination", "settings"))
                    finish()
                },
                PlaybackMenuAction("继续看电视") {},
            ),
            PlaybackMenuPlacement.BOTTOM,
        )
    }

    private fun reload() {
        mirrorRecovery.noteRecoveryBudgetReset()
        mirrorStartedAtMs = android.os.SystemClock.elapsedRealtime()
        lastRebuildAtMs = mirrorStartedAtMs
        // 重建后进度基线作废：从"现在"重新计时，避免用旧基线立刻再次判定超时。
        startNativePageSession()
        diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.RECOVERY_STARTED)
        webStateMirror = "INITIALIZING"
        autoReturnScheduled = false
        releaseMirrorPlayer()
        closeWebRuntime()
        createWebRuntime()
        Toast.makeText(this, "正在重新连接", Toast.LENGTH_SHORT).show()
    }

    // ===== 遥控键 =====

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                menu?.dismiss()
                dismissChannelBrowse()
                showMainMenu()
            }
            return true
        }
        // 网页全屏是浏览器状态，不是 KanTV 菜单状态：BACK 的第一优先级只退出
        // Fullscreen API，等待 ContentDelegate 确认退出后，下一次 BACK 才能打开菜单。
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            GeckoBackPolicy.action(mirrorDiag.presentationFullscreenActive) == GeckoBackAction.EXIT_WEB_FULLSCREEN) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                geckoSession?.exitFullScreen()
            }
            return true
        }
        // 浮层优先于 Activity 默认键。操作菜单（含来源选择）可以叠在浏览面板之上，
        // 因此先判菜单；浏览键语义整体交给 ChannelBrowseNav + ChannelBrowsePanel（同 MainActivity）。
        if (menu?.isShowing == true) {
            return if (menu?.handleKey(event) == true) true else super.dispatchKeyEvent(event)
        }
        if (browseShowing) return handleBrowseKey(event)
        // 需要登录时，把方向键/确认键**交给页面**。
        // 理由一：登录框通常要求真实用户手势（isTrusted）才会弹出，脚本里的合成点击会被忽略。
        // 理由二：只有让遥控器进得去，用户才能处理扫码之后的确认步骤。
        // 返回键与菜单键仍归 app，保证用户随时能离开、能换台。
        if (loginMessageShown &&
            event.keyCode != KeyEvent.KEYCODE_BACK &&
            event.keyCode != KeyEvent.KEYCODE_MENU) {
            return super.dispatchKeyEvent(event)
        }
        val key = event.keyCode
        val handled = key in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU)
        if (!handled) return super.dispatchKeyEvent(event)
        val guardedDirectional = key in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        if (event.action == KeyEvent.ACTION_UP && guardedDirectional) {
            directionalKeyGate.release(key)
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (guardedDirectional && !directionalKeyGate.shouldHandleDown(key, event.repeatCount)) return true
        if (!guardedDirectional && event.repeatCount != 0) return true
        when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> leave(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> leave(1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> showControls()
            KeyEvent.KEYCODE_BACK -> showMainMenu()
            else -> leave(0)
        }
        return true
    }

    // ===== 前后台生命周期 =====

    override fun onStart() {
        super.onStart()
        com.kantv.app.update.LanUpdateManager.scheduleForegroundAutoUpdate(this)
        // 契约 §6：播放中不得应用新订阅。网页宿主同样要打这个标志，否则后台刷新
        // 会判定"未在播放"，在看直播时当场重建频道目录（用户会看到列表变化/换台丢失）。
        com.kantv.app.playback.PlaybackActivityFlag.markActive()
        if (stopped) {
            stopped = false
            diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.FOREGROUND)
            mirrorPlayer?.playWhenReady = true
            // 回前台优先接管刚才暂存的会话（若预热器还留着它）。
            createWebRuntime(runCatching {
                com.kantv.app.playback.WebPlaybackPreloader.take(page.url)
            }.getOrNull())
            if (playbackStrategy.usesMseMirror) startMirrorWatchdog()
            // 回前台：进度基线作废并重新计时（onStop 取消过看门狗）。
            startNativePageSession()
            // 采样中断自描述：把"停了多久"写进诊断，避免把陈旧值当现况。
            val suspendedForMs = if (samplingSuspendedAtMs > 0L) {
                android.os.SystemClock.elapsedRealtime() - samplingSuspendedAtMs
            } else -1L
            resumeSuspendedMs = suspendedForMs
            lastHeartbeatAtMs = android.os.SystemClock.elapsedRealtime()
            samplingSuspendedAtMs = 0L
            mirrorStartedAtMs = android.os.SystemClock.elapsedRealtime()
            startNativeProgressWatchdog()
            // 后台期间采样循环已结束：回前台必须重启，否则报告与 KanTvDiag 心跳永久静默。
            startDiagnosticSampling()
        }
    }

    override fun onStop() {
        stopped = true
        samplingSuspendedAtMs = android.os.SystemClock.elapsedRealtime()
        pageState.invalidateDiagnosticObservation()
        diagnosticEvent(com.kantv.app.diagnostics.DiagnosticEntryKind.BACKGROUND)
        mirrorPlayer?.playWhenReady = false
        watchdogJob?.cancel()
        watchdogJob = null
        nativeProgressWatchdogJob?.cancel()
        nativeProgressWatchdogJob = null
        stopDiagnosticSampling()
        // 熄屏期间不计入"起播等待"：否则回前台会被立刻判成超时并多重建一次。
        mirrorStartedAtMs = 0L
        // 无感重开（2026-09-20）：离开播放页（回列表/退后台）时把还活着的会话交给预热器
        // 暂存，同一频道再次打开直接复用热会话，不用重新付一遍 WASM 初始化。
        retainSessionForReopen()
        closeWebRuntime()
        com.kantv.app.playback.PlaybackActivityFlag.markIdle()
        super.onStop()
    }

    /** 把当前会话转交给预热器（静音暂存，空闲超时/内存吃紧自动释放）。 */
    private fun retainSessionForReopen() {
        val current = geckoSession ?: return
        if (!current.isOpen) return
        val view = geckoView
        if (view != null && view.session === current) view.releaseSession()
        geckoSession = null
        // 传真实落地 URL：页面若已跳到别处，下次接管会因不匹配而拒绝（错页不被复用）。
        com.kantv.app.playback.WebPlaybackPreloader.retain(current, page.url, currentUrl)
    }

    override fun onDestroy() {
        destroyed = true
        nativeProgressWatchdogJob?.cancel()
        nativeProgressWatchdogJob = null
        stopDiagnosticSampling()
        handler.removeCallbacksAndMessages(null)
        offerExecutor.shutdownNow()
        menu?.dismiss()
        if (::browsePanel.isInitialized) browsePanel.release()
        finalizeDiagnosticSession(com.kantv.app.diagnostics.DiagnosticEntryKind.STOP)
        if (BuildConfig.KANTV_DIAGNOSTICS) diagnosticRecorder.close()
        stopMirrorPlayback()
        closeWebRuntime()
        super.onDestroy()
    }

    // ===== 工具 =====

    /**
     * 自包含诊断摘要（单行、供 AI 读）。用户只需贴这一行即可定位问题层次。
     * 格式：`summary ch=<频道> t=<本地时间> elapsed=<播了多久>s reason=<报告原因>`
     *       `dom=<readyState>/<paused> frames=<帧数> pic=<画面状态> wedge=<是否>`
     *       `rec=<已用>/<预算> decision=<最近决定> skip=<跳过原因> label=<找到>/<点中>`
     * 只含白名单枚举与有界数值，不含地址、凭据或页面文本。
     */
    private fun diagnosticSummaryLine(): String {
        val now = System.currentTimeMillis()
        val elapsedSeconds = (android.os.SystemClock.elapsedRealtime() - mirrorStartedAtMs).coerceAtLeast(0L) / 1_000L
        return "summary ch=${intent.getStringExtra("channelId") ?: "unknown"}" +
            " t=${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(now))}" +
            " elapsed=${elapsedSeconds}s" +
            " dom=${probeValues.readyState}/${probeValues.paused} frames=${probeValues.frames}" +
            " pic=$lastVisualSampleOutcome/${lastVisualSampleCallbackMs}ms" +
            " wedge=${wedgeActive()}" +
            " rec=${nativeProgressPolicy.recoveriesUsed()}/$NATIVE_PROGRESS_MAX_RECOVERIES" +
            " decision=$lastWatchdogDecision reason=$lastWatchdogReason skip=$lastSampleSkipReason" +
            " firstFrame=${nativeProgressPolicy.hasEverVerifiedPicture()} timedOut=$probeTimedOut" +
            " label=${probeValues.labelFound}/${probeValues.labelClicked}" +
            " probe=${probeValues.measurement.probeObservationStatus}/${probeValues.jsSampleAgeMs}ms" +
            " frameSource=${probeValues.videoTimingSource} presented=${probeValues.presentedFrames}" +
            " callbackAge=${probeValues.measurement.videoCallbackAgeMs}ms" +
            " av=${probeValues.measurement.avSyncAssessment}"
    }

    /**
     * 枚举 → 诊断白名单值。
     * 注意**不能**直接用 `name`：枚举里是 `VISIBLE_DETAIL`，而诊断白名单用的是 `DETAIL`——
     * 名字漂移会让 require 在采样循环里抛异常，直接崩掉 app（2026-09-15 实测踩过）。
     */
    private fun visualOutcomeName(signal: GeckoVisualOutputSignal): String = when (signal) {
        GeckoVisualOutputSignal.VISIBLE_DETAIL -> "DETAIL"
        GeckoVisualOutputSignal.FLAT_NEUTRAL -> "FLAT_NEUTRAL"
        GeckoVisualOutputSignal.FLAT_OTHER -> "FLAT_OTHER"
        GeckoVisualOutputSignal.NO_SAMPLE -> "NO_SAMPLE"
    }

    /** wedge = 页面自称数据充分在播，但画面超时未被确认（本次事故的核心形态）。 */
    private fun wedgeActive(): Boolean = nativeProgressPolicy.isWedgeActive(
        android.os.SystemClock.elapsedRealtime())

    /** 网络传输类型：只看类型，不取任何地址（隐私约束）。 */
    private fun networkTransportName(): String = runCatching {
        val manager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return@runCatching "unknown"
        val network = manager.activeNetwork ?: return@runCatching "NONE"
        val caps = manager.getNetworkCapabilities(network) ?: return@runCatching "unknown"
        when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "OTHER"
        }
    }.getOrDefault("unknown")

    private fun networkMeteredFlag(): Boolean = runCatching {
        val manager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return@runCatching false
        manager.isActiveNetworkMetered
    }.getOrDefault(false)

    private fun writeDebugState(state: JSONObject) {
        if (!BuildConfig.DEBUG || destroyed) return
        runCatching {
            state.put("channelId", page.tvgId)
                .put("engine", "gecko")
                .put("sessionGeneration", sessionGeneration)
                .put("sampledAtMs", System.currentTimeMillis())
            java.io.File(filesDir, "gecko-mirror-state.json").writeText(state.toString())
        }
    }

    private companion object {
        const val VISUAL_SAMPLE_COLUMNS = 16
        const val VISUAL_SAMPLE_ROWS = 9
        /** 每 N 个看门狗周期做一次像素巡检（2s × 8 = 约 16s 一次，读回开销可控）。 */
        const val NATIVE_VISUAL_TICK_EVERY = 8
        const val WATCHDOG_INTERVAL_MS = 2_000L
        // ===== 原生页进度看门狗 =====
        /** 检查间隔。 */
        const val NATIVE_PROGRESS_CHECK_INTERVAL_MS = 2_000L
        /** 像素读回超过此时长未回调即视为"探针卡住"，强制复位并留痕。 */
        const val PROBE_TIMEOUT_MS = 20_000L
        const val LOGIN_PREFS = com.kantv.app.playback.OfficialWebLoginPolicy.PREFS
        const val KEY_YICAI_VERIFIED_AT = com.kantv.app.playback.OfficialWebLoginPolicy.KEY_VERIFIED_AT
        /** 诊断存活心跳间隔：采样被暂停时也要证明"我还活着"。 */
        const val HEARTBEAT_INTERVAL_MS = 60_000L
        /**
         * 启动窗口：从会话开始（或最近一次页面启动进展）起算，期内**绝不重建**。
         *
         * 2026-09-20 真机实测修正：央视频官网页面在这台电视上要先编译/实例化 WASM 播放器，
         * 从导航到出画需要 25~60 秒（桌面同网络只要 3~8 秒）。原来的 45 秒窗口配合
         * "像素探针一看到页面内容就关闭窗口"，会在约 20 秒时把正在启动的页面硬重建掉，
         * 用户看到的是"反复超时"。现在窗口提到 90 秒（实测值 1.5 倍余量），
         * 且由页面自报的 `readyState` 进展顺延（上限在策略里）。
         */
        const val NATIVE_PROGRESS_FIRST_FRAME_TIMEOUT_MS =
            com.kantv.app.playback.NativePageProgressWatchdogPolicy.STARTUP_WINDOW_DEFAULT_MS
        /** 曾成功播放过之后，停顿时不再宽容：15s 无推进即重建（与 StallDetector 口径一致）。 */
        const val NATIVE_PROGRESS_STALL_TIMEOUT_MS = 15_000L
        /**
         * **运行期**画面死线：页面已自称在播、但还没确认过画面时，多久必须见到画面证据。
         * 与启动窗口分开——启动窗口是"等页面启动"，这里是"已经开播却看不到"；
         * 混用会把 2026-09-15 那个形态的检测延迟从约 46 秒拖到 92 秒。
         */
        const val NATIVE_PROGRESS_RUNTIME_FIRST_FRAME_DEADLINE_MS = 20_000L
        /** 单会话最多自动重建次数；超出后只提示不退出，避免"重建风暴"。 */
        const val NATIVE_PROGRESS_MAX_RECOVERIES = 3
        const val MAX_PAGE_LOAD_RETRIES = 2
        /** 桌面 Firefox UA：与 Gecko 引擎真实身份一致。伪装 Chrome UA 时页面引擎检测
         *  失败走降级分支（镜像分片带真加扰）；Firefox 是页面官方支持的播放器路径。 */
        const val DESKTOP_FIREFOX_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:146.0) Gecko/20100101 Firefox/146.0"
    }
}
