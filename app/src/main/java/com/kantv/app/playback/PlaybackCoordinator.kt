package com.kantv.app.playback

class PlaybackCoordinator(
    private val engineFactory: PlaybackEngineFactory,
    private val scheduler: RetryScheduler,
    private val diagnostics: PlaybackDiagnostics,
    private val policy: RecoveryPolicy = RecoveryPolicy(),
    private val elapsedRealtimeMs: () -> Long,
    private val onStateChanged: (PlaybackUiState) -> Unit,
    private val onFirstFrame: (channelId: String) -> Unit,
    private val onTracksChanged: () -> Unit = {},
    private val onDiagnosticFactsChanged: () -> Unit = {},
    private val onOfficialWeb: (RouteSession, PlaybackRoute.OfficialWeb) -> Unit = { _, _ -> },
    private val onBlocked: (RouteSession, PlaybackRoute.Blocked) -> Unit = { _, _ -> },
    /**
     * 契约 §3：`routes` 全部失败后，依次尝试 ① 服务端 fallback ② 本地官网内嵌目录。
     * 返回 null 表示没有可用的兜底路线（继续既有的恢复/冷却逻辑）。
     */
    private val hubFallbackRoute: (channelId: String) -> PlaybackRoute.OfficialWeb? = { null },
    /**
     * Candidate health history, implemented by the existing source-profile store. `null` keeps the
     * catalogue order, which is also what happens when no history exists yet. Only SHA-256 digests
     * are ever read or written through it.
     */
    private val profileStore: SourcePlaybackProfileStore? = null,
    /** Native Media3 candidate facts for the existing diagnostics journal. */
    private val onDiagnostic: (PlaybackDiagnosticFact) -> Unit = {},
    /** Injectable for tests; production uses the wall clock so decay is comparable across restarts. */
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
    /**
     * 每次观测到画面推进（渲染出新的一帧）时回调。回流用它统计"画面推进事件数"与冻结段
     * （段长按"画面恢复推进"计，见 `PlaybackFeedbackCollector`）。
     */
    private val onPictureProgress: () -> Unit = {},
    /**
     * 一次被计入的缓冲区间结束（`RebufferHealthPolicy` 的 counted rebuffer，单次 ≥600ms）时回调，
     * 参数为该区间时长。回流用它把"反复短缓冲"计入 outcome 判定——这个节奏不产生恢复动作，
     * 既不会进 `stalls`，也够不到 6 秒冻结段，只靠事实出口才能被看见。
     */
    private val onRebufferCounted: (durationMs: Long) -> Unit = {},
    /**
     * 当前候选线路发生变化（首次起播、自动换源、手动选线）时回调。
     * 回流据此把"一次连续附着"切分独立记录，避免两条线的冻结秒数被合并。
     */
    private val onActiveSourceChanged: (PlaybackRequest) -> Unit = {},
) {
    constructor(
        engine: PlaybackEngine,
        scheduler: RetryScheduler,
        diagnostics: PlaybackDiagnostics,
        policy: RecoveryPolicy = RecoveryPolicy(),
        elapsedRealtimeMs: () -> Long,
        onStateChanged: (PlaybackUiState) -> Unit,
        onFirstFrame: (channelId: String) -> Unit,
        onTracksChanged: () -> Unit = {},
        onDiagnosticFactsChanged: () -> Unit = {},
    ) : this(
        PlaybackEngineFactory { engine }, scheduler, diagnostics, policy, elapsedRealtimeMs,
        onStateChanged, onFirstFrame, onTracksChanged, onDiagnosticFactsChanged,
    )

    private var nextSessionId = 1L
    private var nextRouteGeneration = 1L
    private var selectionGeneration = 0L
    private var engine: PlaybackEngine? = null
    private var activeRouteSession: RouteSession? = null
    private var desiredPictureMode = PictureMode.FILL
    private var released = false
    private val healthPolicy = RebufferHealthPolicy()
    private val greyScreen = GreyScreenEvidenceTracker(elapsedRealtimeMs = elapsedRealtimeMs)
    /** Increments on every candidate start, reopen, resume, and session teardown. */
    private var sourceGeneration = 0L
    /** Non-zero while [selectManualSource] pinned a single candidate; health must not reorder it. */
    private var manualSelectionRequested = 0
    private var sourceStartedAtMs = 0L
    /** One early switch per candidate: the threshold is a level, the switch happens once. */
    private var unstableCandidateHandled = false

    /**
     * 播放期间收到新的阵容时，**只更新后备候选**，不打断当前播放。
     *
     * 正在播放的那条线路保持不动；下一次自愈切线 / 重载会从新阵容里挑（顺序即新阵容的顺序）。
     * 这样"枢纽中途修好或换线"能在下一次重试被用上，而不是整场沿用开播那一刻的阵容。
     *
     * 返回 true 表示候选集合确有变化。
     */
    fun updateCandidates(requests: List<PlaybackRequest>): Boolean {
        if (released || requests.isEmpty() || activeRouteSession?.route !is PlaybackRoute.Native ||
            requests.any { it.channelId != activeChannelId }
        ) return false
        val playing = activeRequests.getOrNull(activeSourceIndex)
        if (playing == null) {
            activeRequests = requests
            activeSourceIndex = 0
            return true
        }
        val merged = buildList {
            add(playing)
            requests.forEach { candidate -> if (candidate.uri != playing.uri) add(candidate) }
        }
        if (merged.map { it.uri } == activeRequests.map { it.uri }) return false
        activeRequests = merged
        activeSourceIndex = 0
        return true
    }

    /** Captures the selection epoch before asynchronous catalogue work, including A → B → A. */
    fun currentSelectionToken(): RouteSelectionToken? = activeRouteSession?.let {
        RouteSelectionToken(selectionGeneration, it.route.channelId)
    }

    fun updateCandidates(token: RouteSelectionToken, requests: List<PlaybackRequest>): Boolean {
        if (token.selectionGeneration != selectionGeneration || token.channelId != activeChannelId) return false
        return updateCandidates(requests)
    }

    fun selectTrack(sessionId: Long, kind: PlaybackTrackKind, id: String?): Boolean =
        sessionId != 0L && sessionId == activeSessionId && engine?.selectTrack(sessionId, kind, id) == true

    fun setPictureMode(mode: PictureMode) {
        desiredPictureMode = mode
        engine?.setPictureMode(mode)
    }

    private var activeSessionId = 0L
    private var activeChannelId: String? = null
    private var activeRequests: List<PlaybackRequest> = emptyList()
    private var activeSourceIndex = 0
    private var recoveryStartedAtMs: Long? = null
    private var automaticReopens = 0
    private var renderedFirstFrame = false
    /** 最近一次"画面确实推进"的时刻（elapsedRealtime）；冻结判定与"稳定"判定都以它为准。 */
    private var lastPictureProgressAtMs = 0L
    /** 画面推进事件计数：判定"窗口内是否推进过"用计数，避免依赖时钟分辨率。 */
    private var pictureProgressCount = 0L
    private var openingStage = OpeningStage.CONNECTING
    private var bufferingGeneration = 0L
    private var frameProgressGeneration = 0L
    private var stablePlaybackGeneration = 0L
    private var paused = false
    private var pendingRecoveryOnResume = false
    private var cooldownDueMs: Long? = null
    private var cooldownAttempt = 0
    private var recoveryGeneration = 0L
    private var terminalFailure = false

    /** One-based index for display, or zero when no channel is active. */
    val sourceIndex: Int
        get() = if (activeRequests.isEmpty()) 0 else activeSourceIndex + 1
    val sourceCount: Int
        get() = activeRequests.size

    fun select(request: PlaybackRequest) {
        select(listOf(request))
    }

    /** Selects one route; candidate health may reorder the configured list. */
    fun select(requests: List<PlaybackRequest>) {
        manualSelectionRequested = 0
        selectRoute(PlaybackRouteClassifier.classify(requests))
    }

    /** Manual source pick: the user's choice is respected and health history never reorders it. */
    fun selectManualSource(request: PlaybackRequest) {
        manualSelectionRequested = 1
        selectRoute(PlaybackRouteClassifier.classify(listOf(request)))
    }

    /** Invalidates slow catalogue selection work and tears down the old route immediately. */
    fun beginSelection(channelId: String): RouteSelectionToken {
        check(!released) { "Playback coordinator has been released" }
        val token = RouteSelectionToken(++selectionGeneration, channelId)
        deactivateCurrentRoute(notifyIdle = false)
        return token
    }

    fun selectRoute(token: RouteSelectionToken, route: PlaybackRoute): RouteSession? {
        if (released || activeRouteSession != null ||
            token.selectionGeneration != selectionGeneration || token.channelId != route.channelId
        ) return null
        return activateRoute(route)
    }

    fun selectRequests(token: RouteSelectionToken, requests: List<PlaybackRequest>): RouteSession? =
        selectRoute(token, PlaybackRouteClassifier.classify(requests, token.channelId))

    /**
     * Manual source pick inside an existing selection: the user's candidate is the only one tried
     * first, and candidate health is not allowed to reorder it.
     */
    fun selectManualSource(token: RouteSelectionToken, request: PlaybackRequest): RouteSession? {
        manualSelectionRequested = 1
        return selectRoute(token, PlaybackRouteClassifier.classify(listOf(request), token.channelId))
    }

    fun selectRoute(route: PlaybackRoute): RouteSession {
        check(!released) { "Playback coordinator has been released" }
        selectionGeneration++
        deactivateCurrentRoute(notifyIdle = false)
        return activateRoute(route)
    }

    /** Read-only gate for native diagnostic producers; retained snapshots alone are not route identity. */
    fun acceptsNativeDiagnostics(sessionId: Long): Boolean {
        val active = activeRouteSession ?: return false
        return active.route is PlaybackRoute.Native && active.sessionId == sessionId &&
            activeSessionId == sessionId
    }

    fun acceptsOfficialWebResult(sessionId: Long, routeGeneration: Long): Boolean {
        val active = activeRouteSession ?: return false
        return active.route is PlaybackRoute.OfficialWeb &&
            active.sessionId == sessionId && active.routeGeneration == routeGeneration
    }

    private fun activateRoute(route: PlaybackRoute): RouteSession {
        val routeSession = RouteSession(nextSessionId++, nextRouteGeneration++, route)
        activeRouteSession = routeSession
        activeSessionId = routeSession.sessionId
        activeChannelId = route.channelId
        when (route) {
            is PlaybackRoute.Native -> activateNative(route)
            is PlaybackRoute.OfficialWeb -> {
                // 官网路由不经过播放器:硬停止引擎,避免旧流在网页后面继续出声。
                engine?.stop()
                onOfficialWeb(routeSession, route)
            }
            is PlaybackRoute.Blocked -> {
                engine?.stop()
                onStateChanged(PlaybackUiState.Idle)
                onBlocked(routeSession, route)
            }
        }
        return routeSession
    }

    private fun activateNative(route: PlaybackRoute.Native) {
        scheduler.cancelAll()
        cooldownDueMs = null
        cooldownAttempt = 0
        terminalFailure = false
        recoveryGeneration++
        // The list is played exactly as the catalogue ordered it — which is also exactly the order the
        // source picker shows. Playback history used to reorder candidates here, and it earned its
        // removal: it scored a source that only ever showed a frozen test card as the best candidate
        // and put it first, so the user met a dead source on every open. Quality judgement belongs to
        // the user and to whoever publishes the list, not to a score this app guesses from its own
        // imperfect observations.
        activeRequests = route.requests.toList()
        manualSelectionRequested = 0
        activeSourceIndex = 0
        paused = false
        pendingRecoveryOnResume = false
        clearRecoveryBudget()
        ensureEngine()
        startActiveSource()
    }

    /**
     * Switches to the following configured source. Returns false when there is none.
     *
     * A manual switch records no candidate-health evidence: the user's choice is not a measurement of
     * the candidate they left. Only [onCandidateUnstable] and terminal errors record outcomes.
     */
    fun switchToNextSource(): Boolean {
        if (paused) return false
        if (activeSourceIndex >= activeRequests.lastIndex) return false
        switchToNextSourceInternal(PlaybackDiagnosticReason.AUTO_SWITCH)
        return true
    }

    /**
     * The candidate currently playing. Diagnostics-only: it exists so a report can name the line that
     * was actually on screen, which no other surface can answer after an automatic switch.
     */
    fun activeRequest(): PlaybackRequest? = activeRequests.getOrNull(activeSourceIndex)

    private fun switchToNextSourceInternal(reason: PlaybackDiagnosticReason) {
        activeSourceIndex++
        sourceGeneration++
        scheduler.cancelAll()
        startActiveSource(reason)
    }

    fun pause() {
        if (activeRouteSession?.route !is PlaybackRoute.Native) return
        paused = true
        recoveryGeneration++
        bufferingGeneration++
        frameProgressGeneration++
        stablePlaybackGeneration++
        sourceGeneration++
        healthPolicy.pause(elapsedRealtimeMs())
        greyScreen.reset()
        scheduler.cancelAll()
        engine?.pause()
        emitFact(PlaybackDiagnosticReason.USER_PAUSE)
    }

    fun resume() {
        if (activeRouteSession?.route !is PlaybackRoute.Native) return
        paused = false
        // A resume starts a new source epoch: intervals opened before it can never be extended.
        sourceGeneration++
        healthPolicy.resume(elapsedRealtimeMs(), sourceGeneration)
        greyScreen.reset()
        if (terminalFailure) return
        if (cooldownDueMs != null) {
            scheduleCooldown()
            return
        }
        if (pendingRecoveryOnResume && activeSessionId != 0L) {
            pendingRecoveryOnResume = false
            openingStage = OpeningStage.CONNECTING
            onStateChanged(PlaybackUiState.Loading(activeChannelId.orEmpty(), openingStage))
            engine?.reopen(activeSessionId)
            engine?.resume()
            scheduleFirstFrameFallback()
            emitFact(PlaybackDiagnosticReason.AUTO_REOPEN)
        } else {
            engine?.resume()
            if (activeSessionId != 0L) {
                if (renderedFirstFrame) {
                    scheduleFrameProgressFallback()
                    scheduleStablePlayback()
                } else {
                    scheduleFirstFrameFallback()
                }
            }
            emitFact(PlaybackDiagnosticReason.USER_RESUME)
        }
    }

    fun stop() {
        if (released) return
        selectionGeneration++
        deactivateCurrentRoute(notifyIdle = true)
    }

    fun release() {
        if (released) return
        selectionGeneration++
        deactivateCurrentRoute(notifyIdle = true)
        // The engine is only torn down here, when the coordinator itself is going away. Selection
        // changes keep it: rebuilding the whole renderer chain on every channel change made each
        // zap pay a full engine construction.
        engine?.release()
        engine = null
        released = true
    }

    private fun deactivateCurrentRoute(notifyIdle: Boolean) {
        scheduler.cancelAll()
        cooldownDueMs = null
        cooldownAttempt = 0
        terminalFailure = false
        recoveryGeneration++
        sourceGeneration++
        if (activeSessionId != 0L) {
            // The candidate identity is captured before teardown, so the closing record still says
            // which candidate the session ended on.
            val endedSession = activeSessionId
            val endedOrdinal = if (activeRequests.isEmpty()) 0 else activeSourceIndex + 1
            val endedCount = activeRequests.size
            val endedHash = activeRequests.getOrNull(activeSourceIndex)
                ?.let(SourcePlaybackProfileStore::sourceHash)
            healthPolicy.endSource(elapsedRealtimeMs(), RebufferEndReason.SESSION_ENDED)
            greyScreen.reset()
            emitFact(
                PlaybackDiagnosticReason.SESSION_ENDED,
                sessionIdOverride = endedSession,
                sourceOrdinalOverride = endedOrdinal,
                sourceCountOverride = endedCount,
                sourceHashOverride = endedHash,
            )
        }
        activeRouteSession = null
        activeSessionId = 0L
        activeChannelId = null
        activeRequests = emptyList()
        activeSourceIndex = 0
        paused = false
        pendingRecoveryOnResume = false
        clearRecoveryBudget()
        renderedFirstFrame = false
        bufferingGeneration++
        frameProgressGeneration++
        stablePlaybackGeneration++
        val oldEngine = engine
        // 换台(notifyIdle=false)走软停止:会话身份复位、预检中止,但解码器/渲染链保持热备,
        // 新源起播时直接复用;若下一路由是官网页或 Blocked,由 activateRoute 对应分支补硬停止。
        // 真实停止(后台/退出/释放)才走 stop()。
        if (notifyIdle) oldEngine?.stop() else oldEngine?.stopForSelectionChange()
        if (notifyIdle) onStateChanged(PlaybackUiState.Idle)
    }

    private fun ensureEngine(): PlaybackEngine {
        engine?.let { return it }
        val created = engineFactory.create()
        created.setPictureMode(desiredPictureMode)
        created.setEventListener(::onEngineEvent)
        engine = created
        return created
    }

    private fun onEngineEvent(event: PlaybackEvent) {
        if (event.sessionId != activeSessionId) {
            diagnostics.record(event.sessionId, null, DiagnosticType.STALE_EVENT_DROPPED)
            return
        }
        if (paused || cooldownDueMs != null || terminalFailure) return
        // The health policy runs first and may leave for the next candidate. Everything below would
        // then be applied to the candidate that just replaced this one, so stop here instead.
        val observedSessionId = activeSessionId
        val observedSourceGeneration = sourceGeneration
        observeHealth(event)
        if (activeSessionId != observedSessionId || sourceGeneration != observedSourceGeneration) return
        when (event) {
            is PlaybackEvent.Loading -> {
                onStateChanged(
                    if (renderedFirstFrame) {
                        PlaybackUiState.Buffering(activeChannelId.orEmpty())
                    } else {
                        PlaybackUiState.Loading(activeChannelId.orEmpty(), openingStage)
                    },
                )
                if (renderedFirstFrame) {
                    stablePlaybackGeneration++
                    scheduleStallFallbackIfNeeded()
                } else {
                    scheduleFirstFrameFallback()
                }
            }
            is PlaybackEvent.Ready -> {
                if (renderedFirstFrame) {
                    bufferingGeneration++
                    scheduler.cancelAll()
                    scheduleFrameProgressFallback()
                    scheduleStablePlayback()
                }
                if (!renderedFirstFrame) openingStage = OpeningStage.PREPARING
                onStateChanged(if (renderedFirstFrame) PlaybackUiState.Ready(activeChannelId.orEmpty()) else PlaybackUiState.Loading(activeChannelId.orEmpty(), openingStage))
            }
            is PlaybackEvent.FirstFrame -> {
                val channelId = activeChannelId ?: return
                renderedFirstFrame = true
                pendingRecoveryOnResume = false
                bufferingGeneration++
                scheduler.cancelAll()
                diagnostics.record(event.sessionId, channelId, DiagnosticType.FIRST_FRAME)
                onStateChanged(PlaybackUiState.Ready(channelId))
                onFirstFrame(channelId)
                scheduleFrameProgressFallback()
                scheduleStablePlayback()
                emitFact(
                    PlaybackDiagnosticReason.FIRST_FRAME,
                    // Wait since this candidate started; the policy already tracks that boundary.
                    firstFrameLatencyMs = healthPolicy.health().let { health ->
                        health.firstFrameAtMs?.minus(sourceStartedAtMs)?.coerceAtLeast(0L) ?: -1L
                    },
                )
            }
            is PlaybackEvent.VideoProgress -> {
                // 先记录"画面推进"，再重新布防：布防抓到的计数必须**已经包含这一次推进**，
                // 否则计时器到点会误判为"刚推进过"，画面冻结永远检测不到（2026-09-18 实证）。
                lastPictureProgressAtMs = elapsedRealtimeMs()
                pictureProgressCount++
                onPictureProgress()
                if (renderedFirstFrame) {
                    diagnostics.recordVideoProgress(event.sessionId, event.presentationTimeUs)
                    scheduleFrameProgressFallback()
                }
                observeGreyScreen(event.presentationTimeUs)
            }
            is PlaybackEvent.Technical -> {
                diagnostics.recordTechnical(event.sessionId, event.update)
                onDiagnosticFactsChanged()
                if (event.update is PlaybackTechnicalUpdate.TracksDetected) onTracksChanged()
                if (!renderedFirstFrame) {
                    val next = when (val update = event.update) {
                        PlaybackTechnicalUpdate.PreparingPlayback -> OpeningStage.PREPARING
                        is PlaybackTechnicalUpdate.VideoDecoderSelected -> OpeningStage.PREPARING
                        is PlaybackTechnicalUpdate.TracksDetected -> if (update.tracks.isNotEmpty()) OpeningStage.PREPARING else openingStage
                        is PlaybackTechnicalUpdate.KernelSelected -> if (update.reason in setOf(KernelSelectionReason.DEFAULT, KernelSelectionReason.LEARNED_PREFERENCE)) openingStage else OpeningStage.TRYING_ALTERNATIVE
                        is PlaybackTechnicalUpdate.StreamDeliverySelected -> if (update.strategy != StreamDeliveryStrategy.DIRECT) OpeningStage.PREPARING else openingStage
                        else -> openingStage
                    }
                    if (next != openingStage) {
                        openingStage = next
                        onStateChanged(PlaybackUiState.Loading(activeChannelId.orEmpty(), openingStage))
                    }
                }
            }
            is PlaybackEvent.Ended -> handleFailure(PlaybackErrorKind.TEMPORARY_NETWORK, END_OF_LIVE_STREAM)
            is PlaybackEvent.Error -> handleFailure(event.kind, event.errorCode)
        }
    }

    private fun handleFailure(kind: PlaybackErrorKind, errorCode: Int) {
        if (paused) return
        diagnostics.record(activeSessionId, activeChannelId, DiagnosticType.ERROR, errorCode)
        onDiagnosticFactsChanged()
        renderedFirstFrame = false
        bufferingGeneration++
        frameProgressGeneration++
        stablePlaybackGeneration++
        scheduler.cancelAll()
        openingStage = OpeningStage.TRYING_ALTERNATIVE
        emitFact(diagnosticReasonForError(errorCode, kind), errorCode = errorCode, errorKind = kind)
        if (engine?.tryAlternative(activeSessionId, kind, errorCode) == true) {
            pendingRecoveryOnResume = false
            onStateChanged(PlaybackUiState.Loading(activeChannelId.orEmpty(), openingStage))
            scheduleFirstFrameFallback()
            return
        }
        if (switchToNextSource()) return
        // 契约 §3：本频道的 routes 全部失败后，先尝试服务端 fallback / 本地官网内嵌目录。
        if (activateHubFallback()) return
        if (kind == PlaybackErrorKind.BEHIND_LIVE_WINDOW && reserveRecoveryAttempt()) {
            onStateChanged(PlaybackUiState.Loading(activeChannelId.orEmpty(), openingStage))
            engine?.recoverToLive(activeSessionId)
            scheduleFirstFrameFallback()
            return
        }
        if (kind == PlaybackErrorKind.AUTHORIZATION || kind == PlaybackErrorKind.UNSUPPORTED_FORMAT) {
            terminalFailure = true
            pendingRecoveryOnResume = false
            onStateChanged(PlaybackUiState.Failed(activeChannelId.orEmpty(), kind))
            engine?.stop()
            return
        }
        scheduleReopenOrFail(kind)
    }

    /**
     * 契约 §3 的兜底接管。成功时把路线换成官网内嵌（WebView 容器），并抑制通用失败界面。
     * 只在 Native 路线、且所有线路都已尝试过之后调用。
     */
    private fun activateHubFallback(): Boolean {
        if (activeRouteSession?.route !is PlaybackRoute.Native) return false
        val channelId = activeChannelId ?: return false
        val route = hubFallbackRoute(channelId) ?: return false
        scheduler.cancelAll()
        recoveryGeneration++
        terminalFailure = false
        engine?.stop()
        activateRoute(route)
        return true
    }

    private fun startActiveSource(reason: PlaybackDiagnosticReason = PlaybackDiagnosticReason.SOURCE_START) {
        val request = activeRequests.getOrNull(activeSourceIndex) ?: return
        renderedFirstFrame = false
        pendingRecoveryOnResume = false
        bufferingGeneration++
        frameProgressGeneration++
        stablePlaybackGeneration++
        sourceStartedAtMs = elapsedRealtimeMs()
        healthPolicy.beginSource(activeSessionId, sourceGeneration, sourceStartedAtMs)
        greyScreen.reset()
        unstableCandidateHandled = false
        diagnostics.beginSource(
            sessionId = activeSessionId,
            channelId = request.channelId,
            sourceIndex = activeSourceIndex + 1,
            sourceCount = activeRequests.size,
        )
        diagnostics.record(activeSessionId, request.channelId, DiagnosticType.SELECTED)
        openingStage = if (activeSourceIndex > 0) OpeningStage.TRYING_ALTERNATIVE else OpeningStage.CONNECTING
        onStateChanged(PlaybackUiState.Loading(request.channelId, openingStage))
        onActiveSourceChanged(request)
        emitFact(reason)
        ensureEngine().play(activeSessionId, request)
    }

    /**
     * Feeds one engine event into the health state machine. Only the rebuffer policy decides whether
     * a candidate is unstable; this method merely records the evidence and, on the exact event that
     * crosses the threshold, leaves for the next candidate *before* Media3 raises 1002.
     */
    private fun observeHealth(event: PlaybackEvent) {
        val verdict = healthPolicy.onEvent(event, elapsedRealtimeMs())
        greyScreen.onEvent(event, elapsedRealtimeMs())
        if (verdict.event != null) {
            diagnostics.recordRebuffer(event.sessionId, verdict.event.durationMs)
            // The hub feedback path needs this same evidence: a self-recovering rebuffer raises no
            // recovery action and no freeze segment, so nothing else in the report can see it.
            onRebufferCounted(verdict.event.durationMs)
            emitFact(
                PlaybackDiagnosticReason.REBUFFER_INTERVAL,
                rebufferEvent = verdict.event,
                health = healthPolicy.health(),
            )
        }
        if (verdict.unstable && !unstableCandidateHandled) {
            unstableCandidateHandled = true
            onCandidateUnstable(verdict.unstableReason)
        }
    }

    /**
     * The rebuffering threshold was crossed.
     *
     * A stall is far more often a network hiccup than a dead candidate, and switching candidates is
     * not free: live candidates sit at different live edges, so the picture jumps. Waiting in place
     * costs nothing — a live stream resumes at its own live edge, which is continuous with what the
     * user was already watching. So the same candidate is given the bounded in-place recovery budget
     * first, and the next candidate is tried only once that budget is spent.
     */
    private fun onCandidateUnstable(reason: RebufferHealthPolicy.UnstableReason?) {
        val health = healthPolicy.health()
        emitFact(
            PlaybackDiagnosticReason.REBUFFER_UNSTABLE,
            health = health,
            unstableReason = reason,
        )
        healthPolicy.endSource(elapsedRealtimeMs(), RebufferEndReason.SOURCE_SWITCHED)
        renderedFirstFrame = false
        bufferingGeneration++
        frameProgressGeneration++
        stablePlaybackGeneration++
        scheduler.cancelAll()
        openingStage = OpeningStage.TRYING_ALTERNATIVE
        if (recoveryBudgetAvailable()) {
            emitFact(PlaybackDiagnosticReason.AUTO_REOPEN)
            scheduleReopenOrFail(PlaybackErrorKind.TEMPORARY_NETWORK)
            return
        }
        if (activeSourceIndex < activeRequests.lastIndex) {
            switchToNextSourceInternal(PlaybackDiagnosticReason.AUTO_SWITCH)
            return
        }
        // Last configured candidate: fall through to the existing recovery ladder, which may still
        // reopen the same candidate or hand over to the hub/official-web fallback.
        if (activateHubFallback()) return
        scheduleReopenOrFail(PlaybackErrorKind.TEMPORARY_NETWORK)
    }

    private fun emitFact(
        reason: PlaybackDiagnosticReason,
        errorCode: Int? = null,
        errorKind: PlaybackErrorKind? = null,
        rebufferEvent: RebufferHealthPolicy.RebufferEvent? = null,
        health: RebufferHealthPolicy.SessionHealth? = null,
        unstableReason: RebufferHealthPolicy.UnstableReason? = null,
        firstFrameLatencyMs: Long? = null,
        greyScreenReason: GreyScreenEvidence? = null,
        sessionIdOverride: Long = activeSessionId,
        sourceOrdinalOverride: Int = if (activeRequests.isEmpty()) 0 else activeSourceIndex + 1,
        sourceCountOverride: Int = activeRequests.size,
        sourceHashOverride: String? = activeRequests.getOrNull(activeSourceIndex)
            ?.let(SourcePlaybackProfileStore::sourceHash),
    ) {
        val request = activeRequests.getOrNull(activeSourceIndex)
        onDiagnostic(
            PlaybackDiagnosticFact(
                sessionId = sessionIdOverride,
                sourceGeneration = sourceGeneration,
                reason = reason,
                sourceOrdinal = sourceOrdinalOverride,
                sourceCount = sourceCountOverride,
                sourceHash = sourceHashOverride.orEmpty(),
                errorCode = errorCode,
                errorKind = errorKind,
                rebufferDurationMs = rebufferEvent?.durationMs,
                rebufferReason = rebufferEvent?.reason,
                rebufferCountWindow = health?.countedRebufferInWindow,
                rebufferTotalMsWindow = health?.countedRebufferMsInWindow,
                unstableReason = unstableReason,
                // 候选健康分刻意不再计算：字段保留（接收侧要求精确字段集），恒为 null 表示「从未测量」。
                healthScore = null,
                firstFrameLatencyMs = firstFrameLatencyMs,
                greyScreenReason = greyScreenReason,
            ),
        )
    }

    /**
     * Read-only recovery budget check, so a caller can decide *before* spending the attempt.
     * [reserveRecoveryAttempt] mutates on success, which would burn two attempts if used as a probe.
     */
    private fun recoveryBudgetAvailable(): Boolean {
        val now = elapsedRealtimeMs()
        val startedAt = recoveryStartedAtMs ?: now
        return automaticReopens < policy.maxAutomaticReopens && now - startedAt <= policy.maxActiveRecoveryMs
    }

    private fun diagnosticReasonForError(
        errorCode: Int,
        kind: PlaybackErrorKind,
    ): PlaybackDiagnosticReason = when {
        kind == PlaybackErrorKind.BEHIND_LIVE_WINDOW -> PlaybackDiagnosticReason.BEHIND_LIVE_WINDOW
        errorCode == FIRST_FRAME_TIMEOUT_ERROR_CODE -> PlaybackDiagnosticReason.FIRST_FRAME_TIMEOUT
        errorCode == FRAME_PROGRESS_TIMEOUT_ERROR_CODE -> PlaybackDiagnosticReason.FRAME_PROGRESS_TIMEOUT
        errorCode == STALL_ERROR_CODE -> PlaybackDiagnosticReason.BUFFERING_STALL
        else -> PlaybackDiagnosticReason.PLAYER_ERROR
    }

    /**
     * Evidence for "grey screen": the player is ready but the picture never arrives, or the picture
     * arrived and then stopped, or audio advances with no picture at all. Only counters are kept.
     */
    private fun observeGreyScreen(presentationTimeUs: Long) {
        val snapshot = diagnostics.currentSnapshot() ?: return
        // The audio clock delta is the same signal the sampler uses: it advances while sound is
        // audible even when no picture ever renders, which is what separates "sound only" from
        // "silent and frozen".
        val audioAdvanced = diagnostics.audioClockDelta(activeSessionId) > 0L
        diagnostics.markAudioClock(activeSessionId)
        val evidence = greyScreen.observe(
            nowMs = elapsedRealtimeMs(),
            renderedFirstFrame = renderedFirstFrame,
            presentationTimeUs = presentationTimeUs,
            audioPresentedFrames = if (audioAdvanced) 1L else -1L,
            ready = snapshot.tracks.isNotEmpty(),
        ) ?: return
        emitFact(PlaybackDiagnosticReason.GREY_SCREEN, greyScreenReason = evidence)
    }

    /** Called from the periodic sampler so a frozen picture is reported without new frames. */
    fun sampleGreyScreenEvidence() {
        if (activeSessionId == 0L || paused) return
        val snapshot = diagnostics.currentSnapshot() ?: return
        observeGreyScreen(snapshot.lastVideoPtsUs)
    }

    private fun scheduleReopenOrFail(kind: PlaybackErrorKind) {
        if (!reserveRecoveryAttempt()) {
            pendingRecoveryOnResume = false
            val delay = (30_000L * (1L shl cooldownAttempt.coerceAtMost(4))).coerceAtMost(300_000L)
            cooldownAttempt++
            cooldownDueMs = elapsedRealtimeMs() + delay
            recoveryGeneration++
            onStateChanged(PlaybackUiState.Failed(activeChannelId.orEmpty(), kind, delay))
            engine?.stop()
            scheduleCooldown()
            return
        }
        val observedSession = activeSessionId
        val delay = policy.retryDelaysMs.getOrElse(automaticReopens - 1) { policy.retryDelaysMs.lastOrNull() ?: 0L }
        onStateChanged(PlaybackUiState.Recovering(activeChannelId.orEmpty(), automaticReopens))
        pendingRecoveryOnResume = true
        emitFact(PlaybackDiagnosticReason.AUTO_REOPEN)
        scheduler.schedule(delay) {
            if (!paused && activeSessionId == observedSession) {
                pendingRecoveryOnResume = false
                openingStage = OpeningStage.CONNECTING
                onStateChanged(PlaybackUiState.Loading(activeChannelId.orEmpty(), openingStage))
                engine?.reopen(observedSession)
                scheduleFirstFrameFallback()
            }
        }
    }

    private fun scheduleCooldown() {
        val due = cooldownDueMs ?: return
        val session = activeSessionId
        val generation = recoveryGeneration
        emitFact(PlaybackDiagnosticReason.COOLDOWN_RETRY)
        scheduler.schedule((due - elapsedRealtimeMs()).coerceAtLeast(0L)) {
            if (!paused && activeSessionId == session && generation == recoveryGeneration && cooldownDueMs != null) {
                cooldownDueMs = null
                recoveryGeneration++
                activeSourceIndex = 0
                sourceGeneration++
                clearRecoveryBudget()
                startActiveSource(PlaybackDiagnosticReason.COOLDOWN_RETRY)
                scheduleFirstFrameFallback()
            }
        }
    }

    private fun scheduleStallFallbackIfNeeded() {
        if (!renderedFirstFrame || paused) return
        val observedSession = activeSessionId
        val observedGeneration = ++bufferingGeneration
        emitFact(PlaybackDiagnosticReason.BUFFERING_STALL)
        scheduler.schedule(STALL_BEFORE_SOURCE_SWITCH_MS) {
            if (!paused && activeSessionId == observedSession && bufferingGeneration == observedGeneration) {
                handleFailure(PlaybackErrorKind.TEMPORARY_NETWORK, STALL_ERROR_CODE)
            }
        }
    }

    private fun scheduleFirstFrameFallback() {
        if (renderedFirstFrame || paused) return
        val observedSession = activeSessionId
        val observedGeneration = ++bufferingGeneration
        scheduler.schedule(FIRST_FRAME_BEFORE_SOURCE_SWITCH_MS) {
            if (
                !paused &&
                !renderedFirstFrame &&
                activeSessionId == observedSession &&
                bufferingGeneration == observedGeneration
            ) {
                emitFact(PlaybackDiagnosticReason.FIRST_FRAME_TIMEOUT)
                handleFailure(PlaybackErrorKind.TEMPORARY_NETWORK, FIRST_FRAME_TIMEOUT_ERROR_CODE)
            }
        }
    }

    private fun scheduleFrameProgressFallback() {
        if (!renderedFirstFrame || paused) return
        val observedSession = activeSessionId
        val observedGeneration = ++frameProgressGeneration
        val observedProgressCount = pictureProgressCount
        scheduler.schedule(FRAME_PROGRESS_TIMEOUT_MS) {
            if (paused || !renderedFirstFrame || activeSessionId != observedSession) return@schedule
            // 画面在此期间确实推进过 → 不是冻结。
            if (pictureProgressCount > observedProgressCount) return@schedule
            // 无论代次都留痕：这一条是"画面不动但播放器自称就绪"的唯一直接证据。
            emitFact(PlaybackDiagnosticReason.FRAME_PROGRESS_TIMEOUT)
            // 只有在本次布防仍然有效时才触发动作，避免与恢复阶梯重复触发。
            if (frameProgressGeneration != observedGeneration) return@schedule
            // 安全阀：真实广播本就有静止画面（字幕版、静止图、黑场），
            // 6 秒不动只留痕、不动手；持续到 PICTURE_FREEZE_ACTION_MS 才进恢复阶梯。
            val frozenForMs = elapsedRealtimeMs() - lastPictureProgressAtMs
            if (frozenForMs < PICTURE_FREEZE_ACTION_MS) {
                scheduler.schedule(PICTURE_FREEZE_ACTION_MS - frozenForMs) {
                    if (!paused && renderedFirstFrame && activeSessionId == observedSession &&
                        frameProgressGeneration == observedGeneration && pictureProgressCount == observedProgressCount
                    ) handleFailure(PlaybackErrorKind.DECODER, FRAME_PROGRESS_TIMEOUT_ERROR_CODE)
                }
                return@schedule
            }
            handleFailure(PlaybackErrorKind.DECODER, FRAME_PROGRESS_TIMEOUT_ERROR_CODE)
        }
    }

    private fun scheduleStablePlayback() {
        if (!renderedFirstFrame || paused) return
        val observedSession = activeSessionId
        val observedGeneration = ++stablePlaybackGeneration
        val observedProgressCount = pictureProgressCount
        scheduler.schedule(policy.stablePlaybackBeforeResetMs) {
            if (
                activeSessionId == observedSession &&
                stablePlaybackGeneration == observedGeneration &&
                renderedFirstFrame &&
                !paused
            ) {
                // 「稳定」必须以画面为准：窗口内画面必须推进过，且此刻没有停住。
                // 否则一条"播放器报就绪、画面冻着"的线会被判稳定，进而重置恢复预算，
                // 于是陷入"每几分钟试两次 → 判成功 → 再等一分钟"的死循环（2026-09-17 实证）。
                val pictureAdvanced = pictureProgressCount > observedProgressCount
                val pictureMovingNow = elapsedRealtimeMs() - lastPictureProgressAtMs < FRAME_PROGRESS_TIMEOUT_MS
                if (!pictureAdvanced || !pictureMovingNow) {
                    emitFact(PlaybackDiagnosticReason.FRAME_PROGRESS_TIMEOUT)
                    return@schedule
                }
                engine?.onPlaybackStable(observedSession)
                emitFact(PlaybackDiagnosticReason.PLAYBACK_STABLE, health = healthPolicy.health())
                cooldownAttempt = 0
                clearRecoveryBudget()
            }
        }
    }

    private fun reserveRecoveryAttempt(): Boolean {
        val now = elapsedRealtimeMs()
        val startedAt = recoveryStartedAtMs ?: now.also { recoveryStartedAtMs = it }
        if (automaticReopens >= policy.maxAutomaticReopens || now - startedAt > policy.maxActiveRecoveryMs) return false
        automaticReopens++
        diagnostics.record(activeSessionId, activeChannelId, DiagnosticType.RECOVERY_ATTEMPT, automaticReopens)
        return true
    }

    private fun clearRecoveryBudget() {
        recoveryStartedAtMs = null
        automaticReopens = 0
    }

    private companion object {
        const val END_OF_LIVE_STREAM = -10_001
        const val STALL_ERROR_CODE = -10_002
        const val FIRST_FRAME_TIMEOUT_ERROR_CODE = -10_003
        const val FRAME_PROGRESS_TIMEOUT_ERROR_CODE = -10_004
        // 与 RebufferHealthPolicy.SINGLE_REBUFFER_LIMIT_MS 对齐：一次卡顿先原地等恢复
        // （直播在同一 live edge 续上），超过这个耐心上限才换候选源。旧值 5s 会让普通网络
        // 抖动把用户甩到另一条线的时间点上。
        const val STALL_BEFORE_SOURCE_SWITCH_MS = 25_000L
        // 坏源(握手成功但不吐数据)必须快速下台；15s 足够覆盖预检回退+直连起播。
        const val FIRST_FRAME_BEFORE_SOURCE_SWITCH_MS = 15_000L
        const val FRAME_PROGRESS_TIMEOUT_MS = 6_000L
        /** 画面持续不推进到这个时长才动手自愈；更短的静止可能只是字幕版/静止图。 */
        const val PICTURE_FREEZE_ACTION_MS = 15_000L
    }
}
enum class OpeningStage { CONNECTING, PREPARING, TRYING_ALTERNATIVE }

/**
 * Why the coordinator acted. A closed enum: the diagnostics journal stores the name, never text
 * derived from a source, a page, or an error message.
 */
enum class PlaybackDiagnosticReason {
    SOURCE_START,
    FIRST_FRAME,
    REBUFFER_INTERVAL,
    REBUFFER_UNSTABLE,
    PLAYER_ERROR,
    BEHIND_LIVE_WINDOW,
    FIRST_FRAME_TIMEOUT,
    FRAME_PROGRESS_TIMEOUT,
    BUFFERING_STARTED,
    BUFFERING_STALL,
    AUTO_SWITCH,
    AUTO_REOPEN,
    COOLDOWN_RETRY,
    USER_PAUSE,
    USER_RESUME,
    PLAYBACK_STABLE,
    GREY_SCREEN,
    SESSION_ENDED,
}

/**
 * One candidate-level fact for the existing diagnostics journal. Every field is a count, an ordinal,
 * a duration, a fixed enum, or a SHA-256 digest prefix; there is no field able to carry a URI, a
 * request header, a cookie, a token, or media content.
 */
data class PlaybackDiagnosticFact(
    val sessionId: Long,
    /** Changes on every candidate start, reopen, resume, and teardown. */
    val sourceGeneration: Long,
    val reason: PlaybackDiagnosticReason,
    val sourceOrdinal: Int,
    val sourceCount: Int,
    val sourceHash: String,
    val errorCode: Int? = null,
    /** Media3-level classification, so the journal can separate renderer from network faults. */
    val errorKind: PlaybackErrorKind? = null,
    val rebufferDurationMs: Long? = null,
    val rebufferReason: RebufferHealthPolicy.RebufferReason? = null,
    val rebufferCountWindow: Int? = null,
    val rebufferTotalMsWindow: Long? = null,
    val unstableReason: RebufferHealthPolicy.UnstableReason? = null,
    val healthScore: Int? = null,
    val firstFrameLatencyMs: Long? = null,
    val greyScreenReason: GreyScreenEvidence? = null,
)

sealed interface PlaybackUiState {
    data object Idle : PlaybackUiState
    data class Loading(val channelId: String, val stage: OpeningStage = OpeningStage.CONNECTING) : PlaybackUiState
    data class Buffering(val channelId: String) : PlaybackUiState
    data class Ready(val channelId: String) : PlaybackUiState
    data class Recovering(val channelId: String, val attempt: Int) : PlaybackUiState
    data class Failed(val channelId: String, val kind: PlaybackErrorKind, val autoRetryInMs: Long? = null) : PlaybackUiState
}
