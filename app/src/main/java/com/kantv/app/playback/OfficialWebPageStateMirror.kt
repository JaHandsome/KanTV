package com.kantv.app.playback

import android.view.View
import android.widget.TextView
import com.kantv.app.BuildConfig
import com.kantv.app.GeckoMirrorDiagnostics
import com.kantv.app.PlaybackStatusView
import com.kantv.app.diagnostics.DiagnosticEntryKind
import com.kantv.app.diagnostics.DiagnosticPlaybackValues
import com.kantv.app.diagnostics.GeckoTraceDiagnosticEvidence
import org.json.JSONObject

/**
 * 官方网页宿主的"页面状态观测"簇（从 GeckoMirrorLiveActivity 纯搬移，2026-09-28，
 * 客户端重构 3-3；判定逻辑/阈值/时序/日志措辞逐字保留，零行为变更）。
 *
 * content script 每秒把白名单化的页面状态（布尔/枚举/有界数值）推给宿主，这里负责
 * JSONObject → 状态镜像（probeValues/webStateMirror/mirrorDiag 字段）的胶水写回、
 * 里程碑事件发射与加载提示文案。决策逻辑已在 OfficialWebLoginPolicy /
 * NativePageEvidenceGate / NativePageProgressWatchdogPolicy 策略类里，本类不做决策。
 *
 * 宿主事实（会话活性、页面 provider/名称、启动计时埋点、recorder 门、视图、
 * BuildConfig.DEBUG 日志门、sharedPreferences、诊断事件发射）全部经构造注入的回调
 * 读取/执行；mirrorDiag 与 nativeProgressPolicy 以实例注入，字段写回顺序与原
 * Activity 内顺序一致。簇外调用的方法在 Activity 保留薄委托。
 */
internal class OfficialWebPageStateMirror(
    private val mirrorDiag: GeckoMirrorDiagnostics,
    private val nativeProgressPolicy: NativePageProgressWatchdogPolicy,
    private val recoveryBudget: Int,
    private val sessionAlive: () -> Boolean,
    private val pageProvider: () -> OfficialWebProvider,
    private val pageName: () -> String,
    private val mirrorStartedAtMs: () -> Long,
    private val sessionGeneration: () -> Long,
    private val startupPageStartedAtMs: () -> Long,
    private val startupFirstFrameAtMs: () -> Long,
    private val lastRebuildAtMs: () -> Long,
    private val savedChannel: () -> Boolean,
    private val yicaiVerifiedAtMs: () -> Long,
    private val markYicaiVerifiedNow: () -> Unit,
    private val requestPageFocus: () -> Unit,
    private val loadingHintOrNull: () -> TextView?,
    private val statusViewOrNull: () -> PlaybackStatusView?,
    private val requestPageLogin: () -> Unit,
    private val tapLoginEntry: (Int, Int) -> Unit,
    private val restartWithNormalPresentation: () -> Unit,
    private val usesMseMirror: () -> Boolean,
    private val playbackStrategyName: () -> String,
    private val presentationModeName: () -> String,
    private val signalNativeProgress: () -> Unit,
    private val verifyNativeVisualOutput: () -> Unit,
    private val refreshMirrorDiagnosticCounters: () -> Unit,
    private val emitDiagnosticEvent: (DiagnosticEntryKind) -> Unit,
    private val writeDebugState: (JSONObject) -> Unit,
) {
    // ===== 观测/诊断状态（自宿主纯搬移；宿主经转发属性/直读访问，写回顺序不变）=====
    private val probeObservation = com.kantv.app.diagnostics.GeckoProbeObservation()

    fun diagnosticValues(
        nowMs: Long,
        values: DiagnosticPlaybackValues = probeValues,
    ): DiagnosticPlaybackValues = probeObservation.sample(values, nowMs, sessionGeneration())

    fun invalidateDiagnosticObservation() { probeObservation.invalidate() }

    var probeValues = DiagnosticPlaybackValues.EMPTY
    var webStateMirror = "INITIALIZING"
    /** 页面已明确要求登录、且已把提示换成登录指引；避免每拍重复设置。 */
    var loginMessageShown = false
    /** 已按登录入口坐标合成过一次真实触摸；只做一次，不循环。 */
    var loginTapSent = false
    /** 已请页面弹出登录入口（ShowUserLogin）；只请求一次。 */
    var pageLoginRequested = false
    /** 登录完成后已用正常呈现重启过本页；只做一次。 */
    var restartedAfterLogin = false
    /** 二维码已经出现过（用于判定"扫码成功"）。 */
    var qrPanelSeen = false
    /** 本会话已记录登录完成；只做一次。 */
    var loginVerifiedRecorded = false
    /** 页面上的登录弹窗（二维码）是否已出现；用来决定给用户讲哪一步。 */
    var loginDialogVisibleForUser = false
    /** 页面已呈现登录后的认证 UI：登录完成。 */
    var loginAuthenticated = false
    /** 第一财经登录流程是否进行中（登录期间放行回跳白名单）。 */
    var loginFlowActive = false
    var lastProbeTime = -1.0
    var lastProbeFrames = -1.0
    /** 已上报给看门狗的最高 `readyState`；只在**前进**时上报（进展证据，不是噪声）。 */
    var lastMilestoneReadyState = -1
    /** "页面已进入播放"这个阶段是否已上报过（电平判定只能报一次，否则会烧光顺延额度）。 */
    var pagePlayingPhaseReported = false
    /** 播放器节点就绪（`readyState=0`，页面已建好 media 元素）的时刻。 */
    var nodeReady0AtMs = 0L
    /** 页面第一次自称"元数据就绪"（`readyState≥1`）的时刻。 */
    var metadataAtMs = 0L

    /**
     * 页面是否要求登录——**必须在 [processProbeState] 的任何提前返回之前调用**。
     *
     * 未登录时页面里还没有播放器节点，"需要登录"与"找不到播放器"本就是同一个状态；原来的顺序
     * 把登录判定放在"找不到播放器就 return"之后，于是登录信号永远读不到：既不提示需要登录，
     * 也不会去点开页面自己的登录入口，二维码因此永远不出现。
     * 这里只消费注入脚本白名单化的布尔事实，不含账号、二维码或任何页面文本。
     */
    private fun handleLoginSignals(state: JSONObject) {
        val loginRequiredSignal = state.optBoolean("loginRequired")
        val authenticatedUi = state.optBoolean("authenticatedUi")
        val loginEntryAvailable = state.optBoolean("loginEntryAvailable")
        loginDialogVisibleForUser = state.optBoolean("loginDialogVisible")
        // 二维码出现过没有：面板拿到尺寸就说明出现过。
        if (state.optInt("panelW", -1) > 0) qrPanelSeen = true
        // **扫码成功**的判定不能只认站点那个头像选择器（.m-login .u-login img.f-avatar）：
        // 实测扫码之后站点不一定渲染它，于是"登录完成"永远检测不到、页面一直停在扫码页。
        // 可靠的判据是行为事实：**先看见过二维码，之后页面不再要求登录、而且已经在播**。
        val scanPlaying = state.optBoolean("hasVideo") &&
            state.optInt("ready", 0) >= 2 &&
            !state.optBoolean("paused", true)
        // 兜底：站点那边其实已经登录、只是 app 的记录丢了（例如清了应用数据）——此时页面不需要
        // 登录又在播，也应该判为已登录。加一个稳定期，避免刚起播、登录提示还没出现的瞬间误判。
        val settled = android.os.SystemClock.elapsedRealtime() - mirrorStartedAtMs() > LOGIN_SETTLE_MS
        loginAuthenticated = authenticatedUi ||
            (qrPanelSeen && !loginRequiredSignal && scanPlaying) ||
            (settled && !qrPanelSeen && !loginRequiredSignal && scanPlaying)
        // **首开即登录**：第一财经必须登录才能看完整内容，所以不等页面把免费试用跑完，
        // 第一次打开这个频道就直接把登录引导摆出来。否则用户要先看一段试用、试用结束才被告知
        // 要登录——那不是他想要的顺序。
        val firstTimeLogin = pageProvider() == OfficialWebProvider.YICAI &&
            OfficialWebLoginPolicy.needsFirstYicaiLogin(
                pageProvider(),
                yicaiVerifiedAtMs(),
            )
        if ((loginRequiredSignal || firstTimeLogin) && !loginMessageShown) {
            // 页面已经明确要求登录，此时再对用户说"正在加载…请稍候"就是叫他干等。
            // 二维码由页面自己渲染（脚本已替用户点开那个他本来就要点的登录入口），扫一次即可；
            // 登录状态保存在应用内，下次不用再扫。
            loginMessageShown = true
            // 让按键落到页面上：登录框要靠真实用户手势才会弹出来。
            requestPageFocus()
            loadingHintOrNull()?.let {
                it.text = "需要登录：请按确定键打开登录，扫码后即可观看（只需一次）"
                it.visibility = View.VISIBLE
            }
        }
        if (pageProvider() != OfficialWebProvider.YICAI) return
        val hasVideo = state.optBoolean("hasVideo")
        // 站点通常把"登录"绑成普通元素的 click、且不可键盘聚焦：方向键选不中它，脚本里的合成
        // click 又被当成不可信事件忽略。于是按页面报来的坐标**合成一次真实触摸**——GeckoView 会
        // 把它当平台输入交给页面，等同于用户自己点了一下，站点的登录二维码才会弹出来。
        // 只在"要求登录且尚未点过"时做一次：有界、不循环。
        val loginX = state.optInt("loginEntryX", -1)
        val loginY = state.optInt("loginEntryY", -1)
        // 由 app 直接调用页面的登录入口：这是"首开即弹二维码"的可靠通道。
        if ((loginRequiredSignal || firstTimeLogin) && !pageLoginRequested) {
            pageLoginRequested = true
            requestPageLogin()
        }
        if ((loginRequiredSignal || firstTimeLogin) && !loginTapSent && loginX >= 0 && loginY >= 0) {
            loginTapSent = true
            tapLoginEntry(loginX, loginY)
        }
        loginFlowActive = OfficialWebLoginPolicy.requiresYicaiLogin(
            pageProvider(), hasVideo, loginRequiredSignal)
        // 页面出现登录入口而尚无认证 UI：视为登录流程进行中（放行回跳白名单）。
        if (!loginFlowActive && loginEntryAvailable && !authenticatedUi) loginFlowActive = true
        // 登录完成（无论由哪种事实判出）：记下时间戳，并**刷新这个频道**、切回正常呈现。
        // 用户要的顺序就是"扫完码 → 刷新 → 正常看到直播画面"；不刷新就会一直停在扫码页。
        if (loginAuthenticated && !loginVerifiedRecorded) {
            loginVerifiedRecorded = true
            markYicaiVerifiedNow()
            loginFlowActive = false
            restartWithNormalPresentation()
        }
    }

    fun processProbeState(state: JSONObject, hostPostLagMs: Long = -1) {
        // 会话可能正在销毁：此时不得再喂判定基线（否则污染"时钟在走"证据）。
        if (!sessionAlive()) return
        val nowReceivedMs = android.os.SystemClock.elapsedRealtime()
        if (!probeObservation.accept(
                sessionGeneration(), state.optLong("documentId", -1L),
                state.optLong("nodeGeneration", -1L), state.optLong("tick", -1L), nowReceivedMs,
            )) return
        // Epoch is only a host receipt anchor for this packet; it is never mixed with document time.
        val hostReceivedEpochMs = System.currentTimeMillis()
        val hasIdentity = state.optLong("documentId", -1L) >= 0 && state.optLong("nodeGeneration", -1L) >= 0
        // Record the accepted raw evidence before any progress/login event can sample it.
        val measurement = geckoMeasurementEvidence(state, hasIdentity, !usesMseMirror())
        probeObservation.recordMeasurementAtReceipt(measurement)
        val realNodeChanged = hasIdentity && probeValues.nodeGeneration != probeObservation.nodeGeneration
        val previousNodeState = mirrorDiag.lastPlayerNodeState
        val legacyReplacement = if (state.optInt("noVideo", 0) == 1) {
            previousNodeState == "PLAYER_ACTIVE" || previousNodeState == "PLAYER_READY0"
        } else {
            previousNodeState == "PLAYER_ACTIVE" && state.optInt("ready", 0) == 0
        }
        if (NativePageEvidenceGate.shouldResetPlayerStartup(
                hasIdentity, realNodeChanged && probeValues.nodeGeneration >= 0, legacyReplacement,
            )) {
            lastMilestoneReadyState = -1
            pagePlayingPhaseReported = false
            nativeProgressPolicy.observePlayerReplaced(nowReceivedMs)
        }
        if (realNodeChanged) {
            lastProbeTime = -1.0
            lastProbeFrames = -1.0
        }
        // 关键点：登录事实必须在**任何提前返回之前**处理。
        // 第一财经未登录时，页面里还没有播放器节点（noVideo），而"需要登录"恰恰就是这个状态；
        // 原来登录判定写在"找不到播放器就 return"的后面，于是登录信号永远读不到——
        // 既不提示需要登录，也不会去点开页面自己的登录入口，二维码因此永远不出现。
        handleLoginSignals(state)
        // 选台点击的落地事实（content script 只转发两个布尔）：供"频道从未被选中"复盘。
        probeValues = probeValues.copy(
            labelFound = state.optBoolean("labelFound"),
            labelClicked = state.optBoolean("labelClicked"),
            geckoObserver = com.kantv.app.diagnostics.GeckoObserverDiagnosticEvidence.fromBridge(
                state, hasIdentity && !usesMseMirror(), hostPostLagMs,
            ).copy(
                geckoCaptureSequence = probeValues.geckoObserver.geckoCaptureSequence,
                geckoCaptureApiCallMs = probeValues.geckoObserver.geckoCaptureApiCallMs,
                geckoCaptureIssueToListenerMs = probeValues.geckoObserver.geckoCaptureIssueToListenerMs,
                geckoCaptureClassifyMs = probeValues.geckoObserver.geckoCaptureClassifyMs,
                geckoCapturePostLagMs = probeValues.geckoObserver.geckoCapturePostLagMs,
            ),
            geckoTrace = GeckoTraceDiagnosticEvidence.fromBridge(
                state, hasIdentity && !usesMseMirror(), hostReceivedEpochMs,
            ),
        )
        if (state.optInt("noVideo", 0) == 1) {
            webStateMirror = "NO_PLAYER_NODE"
            mirrorDiag.mirrorStage = "NO_PLAYER_NODE"
            val nodeChanged = mirrorDiag.lastPlayerNodeState != "NO_PLAYER_NODE"
            if (nodeChanged) mirrorDiag.mirrorNodeGeneration++
            probeValues = probeValues.copy(
                nodeCount = 0,
                nodeGeneration = if (hasIdentity) probeObservation.nodeGeneration else mirrorDiag.mirrorNodeGeneration,
                readyState = 0,
                // 节点没了，旧节点的进度/缓冲/错误证据一并清掉，不能当成现况上报。
                timeMs = -1,
                frames = -1,
                presentedFrames = -1,
                videoPtsUs = -1,
                videoTimingSource = "GECKO_NO_VIDEO",
                actualPresentedAvailability = "UNAVAILABLE",
                measurement = measurement,
                width = 0,
                height = 0,
                paused = true,
                buffering = false,
                jsSampleAgeMs = 0,
                droppedFrames = -1,
                bufferedPositionMs = -1,
                bufferDurationMs = -1,
                errorCode = 0,
                webState = "NO_PLAYER_NODE",
                webRuntimeGeneration = sessionGeneration(),
            )
            if (nodeChanged) {
                mirrorDiag.lastPlayerNodeState = "NO_PLAYER_NODE"
                refreshMirrorDiagnosticCounters()
                emitDiagnosticEvent(DiagnosticEntryKind.PLAYER_NODE_MISSING)

            }
            // 连播放器节点都没有（央视频选台失败的形态之一）：同样是"从未选中"证据。
            // 门控在 NativePageEvidenceGate：其它官网的 readyState=0 可能是正常等待态。
            if (NativePageEvidenceGate.shouldFeedNeverSelectedEvidence(pageProvider())) {
                nativeProgressPolicy.observePageMediaSnapshot(
                    android.os.SystemClock.elapsedRealtime(), 0, -1L)
            }
            writeProbeDebugState()
            updateLoadingHint()
            return
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.d("KanTvMirror",
                "probe time=${state.optDouble("time", -1.0)} ready=${state.optInt("ready")}")
        }
        val time = state.optDouble("time", -1.0)
        val frames = state.optDouble("frames", -1.0)
        val readyState = state.optInt("ready", 0).coerceIn(0, 4)
        val paused = state.optBoolean("paused", true)
        // 登录事实已在 handleLoginSignals() 处理——它必须在**任何提前返回之前**跑，
        // 因为"需要登录"这个状态本身就意味着页面里还没有播放器节点（noVideo）。
        val nativeAdvancing = !usesMseMirror() && !paused && readyState >= 2 &&
            ((lastProbeTime >= 0.0 && time > lastProbeTime) ||
                (lastProbeFrames >= 0.0 && frames > lastProbeFrames))
        val nativeFirstProgress = nativeAdvancing && startupFirstFrameAtMs() == 0L
        val playerNodeState = if (readyState == 0) "PLAYER_READY0" else "PLAYER_ACTIVE"
        val nodeChanged = playerNodeState != mirrorDiag.lastPlayerNodeState
        if (nodeChanged) {
            mirrorDiag.mirrorNodeGeneration++
            mirrorDiag.lastPlayerNodeState = playerNodeState
            if (readyState == 0) {
                webStateMirror = "PLAYER_NO_FRAME_PROGRESS"
                mirrorDiag.mirrorStage = "PLAYER_READY0"
            } else if (mirrorDiag.mirrorFirstFrameAtMs <= 0L) {
                webStateMirror = "INITIALIZING"
            }
            // Legacy bridges lacked object identity and used readiness transitions as replacement.
            // With real identity, keep the same-node high-water mark during normal buffering.
            if (!hasIdentity) {
                lastMilestoneReadyState = -1
                pagePlayingPhaseReported = false
            }
        }
        if (nativeAdvancing && mirrorDiag.firstFrameMilestoneRecorded) {
            webStateMirror = "PLAYING"
            mirrorDiag.mirrorStage = "NATIVE_PAGE_PROGRESS"
        }
        // 看门狗的事实来源：只有"time/frames 真的在推进"才算健康。
        // 注意这里不要求 firstFrameMilestoneRecorded——起播阶段的推进同样是健康证据
        // （`nativeAdvancing` 已包含 paused=false 与 readyState>=2）。
        if (nativeAdvancing) signalNativeProgress()
        // 启动进展（2026-09-20）：`readyState` 前进证明"页面还在启动、没坏"。
        // 喂给看门狗做两件事：顺延启动窗口、标记"页面已自称在播"（此后像素证据才参与判定）。
        // 没有这条，央视频那种要在电视上编译 20~30 秒 WASM 播放器的页面会被自己的像素证据掐断。
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (readyState > lastMilestoneReadyState) {
            lastMilestoneReadyState = readyState
            if (readyState == 0 && nodeReady0AtMs == 0L) nodeReady0AtMs = nowMs
            if (readyState >= 1 && metadataAtMs == 0L) metadataAtMs = nowMs
            nativeProgressPolicy.observeStartupMilestone(nowMs)
        }
        // "频道从未被选中"证据（央视频 SPA 点选失败形态）：readyState 恒 0 且时间轴不动。
        // 只喂央视频——其它官网的 readyState=0 可能是正常等待态（如第一财经登录流程）。
        if (NativePageEvidenceGate.shouldFeedNeverSelectedEvidence(pageProvider())) {
            nativeProgressPolicy.observePageMediaSnapshot(
                nowMs, readyState, if (time.isFinite() && time >= 0) (time * 1000).toLong() else -1L)
        }
        // "是否在播"是**电平**判定，只在翻转时报一次：`readyState` 完全可能在 paused=true 时
        // 就到达 4，之后 play() 成功而 readyState 不再前进——只靠"前进"那一拍会永远进不了
        // 运行期，于是画面明明正常却按启动期规则超时重建、最后把频道踢掉。
        // 只在翻转时报一次是为了不把顺延额度在几秒内烧光（此调用不消耗额度）。
        val pagePlayingNow = readyState >= 2 && !paused
        if (pagePlayingNow && !pagePlayingPhaseReported) {
            pagePlayingPhaseReported = true
            nativeProgressPolicy.observePlaybackClaim(nowMs)
        }
        probeValues = probeValues.copy(
            timeMs = if (time.isFinite() && time >= 0) (time * 1000).toLong() else -1,
            frames = frames.toLong().coerceAtLeast(-1),
            width = state.optInt("w", 0).coerceAtLeast(0),
            height = state.optInt("h", 0).coerceAtLeast(0),
            // 桥只上报白名单化的有界值，未知一律 -1。
            droppedFrames = state.optLong("droppedFrames", -1L).coerceAtLeast(-1L),
            bufferedPositionMs = state.optLong("bufferedPositionMs", -1L).coerceAtLeast(-1L),
            bufferDurationMs = state.optLong("bufferDurationMs", -1L).coerceAtLeast(-1L),
            errorCode = state.optInt("errorCode", 0).coerceIn(0, 4),
            readyState = readyState,
            paused = paused,
            buffering = !paused && readyState < 3,
            jsSampleAgeMs = 0,
            presentedFrames = state.optLong("presentedFrames", -1L).coerceIn(-1L, MAX_JS_INTEGER),
            videoPtsUs = state.optLong("videoPtsUs", -1L).coerceIn(-1L, MAX_JS_INTEGER),
            videoTimingSource = state.optString("videoTimingSource").takeIf {
                it in setOf("GECKO_RVFC", "GECKO_RVFC_PENDING", "GECKO_PLAYBACK_QUALITY", "GECKO_CURRENT_TIME")
            } ?: "unknown",
            actualPresentedAvailability = state.optString("actualPresentedAvailability").takeIf {
                it in setOf("BROWSER_COMPOSITOR_ONLY", "NO_CALLBACK_YET", "UNAVAILABLE")
            } ?: "unknown",
            audioTrackTimestampAvailability = "UNAVAILABLE_PLATFORM_API",
            measurement = measurement,
            nodeCount = 1,
            nodeGeneration = if (hasIdentity) probeObservation.nodeGeneration else mirrorDiag.mirrorNodeGeneration,
            webState = webStateMirror,
            webRuntimeGeneration = sessionGeneration(),
        )
        if (readyState == 0 && nodeChanged) {
            // Only the transition emits a durable event; 3-second samples preserve persistence.
            refreshMirrorDiagnosticCounters()
            emitDiagnosticEvent(DiagnosticEntryKind.PLAYER_NODE_READY0)
        }
        if (nativeFirstProgress) verifyNativeVisualOutput()
        writeProbeDebugState()
        updateLoadingHint()
        lastProbeTime = time
        lastProbeFrames = frames
    }

    /** 接受 bridge 已白名单化的固定枚举、布尔和有界数值，不接受页面自由文本。 */
    fun processPresentationState(state: JSONObject) {
        val reportedMode = state.optString("mode")
        if (reportedMode != presentationModeName()) return
        probeValues = probeValues.copy(
            geckoPresentationMode = presentationModeName(),
            geckoFullscreenRequestCount = state.optLong("requestCount", 0L).coerceAtLeast(0L),
            geckoFullscreenRequestAccepted = state.optBoolean("requestAccepted", false),
            geckoCssFitApplied = state.optBoolean("cssFitApplied", false),
            geckoPresentationRebindCount = state.optLong("rebindCount", 0L).coerceAtLeast(0L),
            geckoPresentationTargetWidth = state.optInt("targetWidth", 0).coerceIn(0, 32768),
            geckoPresentationTargetHeight = state.optInt("targetHeight", 0).coerceIn(0, 32768),
            geckoPresentationViewportWidth = state.optInt("viewportWidth", 0).coerceIn(0, 32768),
            geckoPresentationViewportHeight = state.optInt("viewportHeight", 0).coerceIn(0, 32768),
            geckoPresentationCoveragePermille = state.optInt("coveragePermille", 0).coerceIn(0, 1000),
        )
        refreshPresentationDiagnostics()
        writeProbeDebugState()
    }

    fun refreshPresentationDiagnostics() {
        probeValues = mirrorDiag.refreshPresentation(probeValues, presentationModeName())
    }

    /** Rolling debug snapshot for emulator acceptance; contains fixed identifiers and counters only. */
    fun writeProbeDebugState() {
        writeDebugState(JSONObject()
            .put("readyState", probeValues.readyState)
            .put("timeMs", probeValues.timeMs)
            .put("frames", probeValues.frames)
            .put("paused", probeValues.paused)
            .put("width", probeValues.width)
            .put("height", probeValues.height)
            .put("nodeCount", probeValues.nodeCount)
            .put("webState", probeValues.webState)
            .put("mirrorStage", mirrorDiag.mirrorStage)
            .put("playbackStrategy", playbackStrategyName())
            .put("presentationMode", presentationModeName())
            .put("fullscreenRequestCount", probeValues.geckoFullscreenRequestCount)
            .put("fullscreenRequestAccepted", probeValues.geckoFullscreenRequestAccepted)
            .put("fullscreenActive", probeValues.geckoFullscreenActive)
            .put("fullscreenEnterCount", probeValues.geckoFullscreenEnterCount)
            .put("fullscreenExitCount", probeValues.geckoFullscreenExitCount)
            .put("cssFitApplied", probeValues.geckoCssFitApplied)
            .put("presentationRebindCount", probeValues.geckoPresentationRebindCount)
            .put("presentationTargetWidth", probeValues.geckoPresentationTargetWidth)
            .put("presentationTargetHeight", probeValues.geckoPresentationTargetHeight)
            .put("presentationViewportWidth", probeValues.geckoPresentationViewportWidth)
            .put("presentationViewportHeight", probeValues.geckoPresentationViewportHeight)
            .put("presentationCoveragePermille", probeValues.geckoPresentationCoveragePermille)
            // 本次启动的"页面起点 → 首帧（像素确认）"；-1 = 本次启动尚未确认出画。
            .put("firstFrameElapsedMs", startupFirstFrameElapsedMs(
                startupPageStartedAtMs(), startupFirstFrameAtMs()))
            // 页面媒体证据（桥上报的有界值）：验收时读这四个数字判断卡在哪一层。
            .put("errorCode", probeValues.errorCode)
            .put("droppedFrames", probeValues.droppedFrames)
            .put("bufferedPositionMs", probeValues.bufferedPositionMs)
            .put("bufferDurationMs", probeValues.bufferDurationMs)
            // 启动计时埋点：回答"这 20~30 秒到底花在哪"（只写调试文件，不上报）。
            .put("startupPageToReady0Ms", elapsedSinceOrNone(startupPageStartedAtMs(), nodeReady0AtMs.let {
                if (it > 0L) it else android.os.SystemClock.elapsedRealtime()
            }))
            .put("startupReady0ToMetadataMs", if (nodeReady0AtMs > 0L && metadataAtMs > 0L) {
                metadataAtMs - nodeReady0AtMs
            } else {
                -1L
            })
            .put("startupReadyState", lastMilestoneReadyState)
            .put("startupPageClaimedPlaying", nativeProgressPolicy.hasPageClaimedPlaying())
            .put("labelFound", probeValues.labelFound)
            .put("labelClicked", probeValues.labelClicked)
            .put("lastRebuildAtMs", lastRebuildAtMs())
            .put("watchdogRecoveries", nativeProgressPolicy.recoveriesUsed()))
    }

    fun updateLoadingHint() {
        val statusView = statusViewOrNull() ?: return
        // 画面已就绪 / 已保存频道 / 保护态：不打扰观看。
        if (pictureReadyForUser() || savedChannel()) {
            statusView.hide()
            loadingHintOrNull()?.visibility = View.GONE
            return
        }
        // 页面已明确要求登录：**它已经起来了，是在等登录**，此时再说"还没起来/正在重试"就是误导。
        // 二维码由页面自己渲染（脚本已替用户点开那个他本来就要点的登录入口），扫一次即可，
        // 登录状态保存在应用内，下次不用再扫。
        // 登录要**带着用户一步步走**：先告诉他按确定键打开登录，二维码出现后再告诉他扫码。
        // 每一步只说这一句该说的话，不让他猜。
        // 关键：指引**只能用底部那一行**，不能用居中面板——登录二维码就在屏幕中间，
        // 居中的提示会把它整个盖住，用户就扫不到了（实测踩到）。
        if (loginMessageShown && !loginAuthenticated) {
            val headline = if (loginDialogVisibleForUser) "请用手机扫描二维码" else "需要登录"
            val detail = if (loginDialogVisibleForUser) {
                "扫码后即可继续观看（只需一次）"
            } else {
                "请按确定键打开登录，然后扫码（只需一次）"
            }
            statusView.hide()
            loadingHintOrNull()?.let {
                it.text = "$headline：$detail"
                it.visibility = View.VISIBLE
            }
            return
        }
        val elapsedSeconds = (android.os.SystemClock.elapsedRealtime() - mirrorStartedAtMs()) / 1_000
        val attempts = nativeProgressPolicy.recoveriesUsed()
        val line = PlaybackStatusCopy.loading(
            channelName = pageName(),
            elapsedSeconds = elapsedSeconds,
            recoveryAttempt = attempts,
            recoveryBudget = recoveryBudget,
        )
        statusView.showLoading(line.headline, line.detail)
        loadingHintOrNull()?.visibility = View.GONE
    }

    /** 画面是否已确认（用户能看到内容）——用于决定要不要收起等待提示。 */
    private fun pictureReadyForUser(): Boolean =
        nativeProgressPolicy.hasEverVerifiedPicture() && mirrorDiag.firstFrameMilestoneRecorded

    /** 诊断用的"从某时刻到现在"耗时；时刻未记录过时返回 -1。 */
    private fun elapsedSinceOrNone(sinceMs: Long, nowMs: Long): Long =
        if (sinceMs > 0L) nowMs - sinceMs else -1L

    private companion object {
        const val MAX_JS_INTEGER = 9_007_199_254_740_991L
        /** 兜底判定前的稳定期：刚起播时登录提示可能还没出现，不能立刻当成已登录。 */
        const val LOGIN_SETTLE_MS = 20_000L
    }
}


/** Only fixed enums and integer facts cross the bridge; malformed availability fails closed. */
internal fun geckoMeasurementEvidence(
    state: JSONObject,
    hasIdentity: Boolean,
    enabled: Boolean,
): DiagnosticPlaybackValues.MeasurementEvidence {
    fun number(key: String, minimum: Long = -1): Long {
        val value = state.opt(key) as? Number ?: return -1
        val double = value.toDouble()
        return if (double.isFinite() && double >= minimum &&
            double <= DiagnosticPlaybackValues.MAX_WEB_INTEGER && double == value.toLong().toDouble()
        ) value.toLong() else -1
    }
    val base = DiagnosticPlaybackValues.MeasurementEvidence(
        probeObservationStatus = if (hasIdentity) "VALID" else "INSUFFICIENT_NO_IDENTITY",
        avSyncAssessment = "UNKNOWN_NO_MEDIA_ELEMENT_AUDIO_OUTPUT_TIMESTAMP",
        videoCallbackAgeMs = number("videoCallbackAgeMs"),
        videoPresentationAgeMs = number("videoPresentationAgeMs"),
        videoExpectedDisplayDelayUs = number("videoExpectedDisplayDelayUs", -1_000_000)
            .coerceIn(-1_000_000, 1_000_000),
        geckoAudioClockStatus = if (enabled) "INSUFFICIENT_NO_SAMPLE" else "NOT_APPLICABLE",
    )
    if (!enabled) return base
    val status = state.optString("geckoAudioClockStatus").takeIf {
        it in DiagnosticPlaybackValues.GECKO_AUDIO_CLOCK_STATUSES && it != "NOT_APPLICABLE"
    } ?: "INSUFFICIENT_NO_SAMPLE"
    val duration = number("geckoAudioClockCallDurationMs")
    if (status != "AVAILABLE") return base.copy(
        geckoAudioClockStatus = status, geckoAudioClockCallDurationMs = duration,
    )
    val delta = state.opt("geckoAudioMinusVideoUs") as? Number
    if (delta == null || !delta.toDouble().isFinite() ||
        delta.toDouble() != delta.toLong().toDouble() ||
        delta.toLong() !in -DiagnosticPlaybackValues.MAX_WEB_INTEGER..DiagnosticPlaybackValues.MAX_WEB_INTEGER
    ) return base.copy(geckoAudioClockStatus = "INSUFFICIENT_INVALID_CLOCK")
    return try {
        base.copy(
            avSyncAssessment = "UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED",
            geckoAudioClockStatus = status,
            geckoAudioClockUs = number("geckoAudioClockUs"),
            geckoAudioClockAgeMs = number("geckoAudioClockAgeMs"),
            geckoAudioClockCallDurationMs = duration,
            geckoPairedVideoPtsUs = number("geckoPairedVideoPtsUs"),
            geckoAudioMinusVideoUs = number("geckoAudioMinusVideoUs", -DiagnosticPlaybackValues.MAX_WEB_INTEGER),
            geckoAvPairSpanMs = number("geckoAvPairSpanMs"),
        )
    } catch (_: IllegalArgumentException) {
        base.copy(geckoAudioClockStatus = "INSUFFICIENT_INVALID_CLOCK")
    }
}
