package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorTest {
    @Test
    fun readyWithoutDisplayedFrameKeepsLoadingVisible() {
        val engine = FakeEngine()
        val states = mutableListOf<PlaybackUiState>()
        val coordinator = coordinator(engine, FakeScheduler(), states)
        coordinator.select(request("one"))
        engine.emit(PlaybackEvent.Ready(engine.lastSession))
        assertEquals(PlaybackUiState.Loading("one", OpeningStage.PREPARING), states.last())
        engine.emit(PlaybackEvent.FirstFrame(engine.lastSession))
        assertEquals(PlaybackUiState.Ready("one"), states.last())
    }

    @Test
    fun fiftyRepeatedSelectionsDropAllOldSessionEvents() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val states = mutableListOf<PlaybackUiState>()
        val coordinator = coordinator(engine, scheduler, states)
        val sessions = mutableListOf<Long>()
        repeat(50) {
            coordinator.select(request("same"))
            sessions += engine.lastSession
        }
        sessions.dropLast(1).forEach { old ->
            engine.emit(PlaybackEvent.FirstFrame(old))
            engine.emit(PlaybackEvent.Loading(old))
        }
        assertEquals(50, engine.playedRequests.size)
        assertEquals(PlaybackUiState.Loading("same"), states.last())
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test
    fun endedLiveStreamUsesTheSameBoundedRecoveryPolicy() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val states = mutableListOf<PlaybackUiState>()
        val coordinator = coordinator(engine, scheduler, states)
        coordinator.select(PlaybackRequest("channel-a", "https://example.invalid/a.m3u8"))

        engine.emit(PlaybackEvent.Ended(engine.lastSession))

        assertTrue(states.last() is PlaybackUiState.Recovering)
        assertEquals(1, scheduler.tasks.size)
        scheduler.runNext()
        assertEquals(1, engine.reopenCount)
    }

    @Test
    fun staleErrorCannotReplaceNewChannelState() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val states = mutableListOf<PlaybackUiState>()
        val coordinator = coordinator(engine, scheduler, states)
        coordinator.select(request("one"))
        val oldSession = engine.lastSession
        coordinator.select(request("two"))

        engine.emit(PlaybackEvent.Error(oldSession, PlaybackErrorKind.TEMPORARY_NETWORK, 2001))

        assertEquals(PlaybackUiState.Loading("two"), states.last())
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test
    fun exhaustedFastRecoveryKeepsExternalRouteSession() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val states = mutableListOf<PlaybackUiState>()
        val coordinator = coordinator(engine, scheduler, states)
        coordinator.select(request("one"))
        val session = engine.lastSession

        repeat(2) {
            engine.emit(PlaybackEvent.Error(session, PlaybackErrorKind.TEMPORARY_NETWORK, 2001))
            scheduler.runNext()
        }
        engine.emit(PlaybackEvent.Error(session, PlaybackErrorKind.TEMPORARY_NETWORK, 2001))

        assertEquals(2, engine.reopenCount)
        assertEquals(PlaybackUiState.Failed("one", PlaybackErrorKind.TEMPORARY_NETWORK, 30_000L), states.last())
        assertEquals(30_000L, scheduler.tasks.single().delayMs)
        scheduler.runNext()
        assertEquals(2, engine.playedRequests.size)
        assertEquals(session, engine.lastSession)
    }

    @Test
    fun firstFrameSavesOnlyActiveChannel() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val saved = mutableListOf<String>()
        val coordinator = PlaybackCoordinator(
            engine, scheduler, PlaybackDiagnostics(), elapsedRealtimeMs = { 0L },
            onStateChanged = {}, onFirstFrame = saved::add,
        )
        coordinator.select(request("one"))
        val oldSession = engine.lastSession
        coordinator.select(request("two"))
        engine.emit(PlaybackEvent.FirstFrame(oldSession))
        engine.emit(PlaybackEvent.FirstFrame(engine.lastSession))

        assertEquals(listOf("two"), saved)
    }

    @Test
    fun authorizationFailureSwitchesSourceWithinStableRouteSession() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val states = mutableListOf<PlaybackUiState>()
        val coordinator = coordinator(engine, scheduler, states)
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
        val primarySession = engine.lastSession

        engine.emit(PlaybackEvent.Error(primarySession, PlaybackErrorKind.AUTHORIZATION, 403))

        assertEquals(2, engine.playedRequests.size)
        assertTrue(engine.playedRequests.last().uri.endsWith("/backup"))
        assertEquals(primarySession, engine.lastSession)
        assertEquals(PlaybackUiState.Loading("one", OpeningStage.TRYING_ALTERNATIVE), states.last())
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test
    fun endedStreamSwitchesToNextSourceBeforeUsingRetryBudget() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler, mutableListOf())
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))

        engine.emit(PlaybackEvent.Ended(engine.lastSession))

        assertTrue(engine.playedRequests.last().uri.endsWith("/backup"))
        assertEquals(0, engine.reopenCount)
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test
    fun oldSourceDeadlineIsDroppedAfterFallback() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val states = mutableListOf<PlaybackUiState>()
        val coordinator = coordinator(engine, scheduler, states)
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
        val primarySession = engine.lastSession
        engine.emit(PlaybackEvent.Loading(primarySession))
        val oldDeadline = scheduler.tasks.first { it.delayMs == 15_000L }.action
        engine.emit(PlaybackEvent.Error(primarySession, PlaybackErrorKind.DECODER, 1))
        oldDeadline()

        assertEquals(2, engine.playedRequests.size)
        assertEquals(PlaybackUiState.Loading("one", OpeningStage.TRYING_ALTERNATIVE), states.last())
    }

    @Test
    fun prolongedBufferingAfterFirstFrameSwitchesToNextSource() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler, mutableListOf())
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
        val primarySession = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(primarySession))
        engine.emit(PlaybackEvent.Loading(primarySession))

        // 25s 对齐 RebufferHealthPolicy.SINGLE_REBUFFER_LIMIT_MS：先原地等，超过才换源。
        scheduler.runNextWithDelay(25_000)

        assertTrue(engine.playedRequests.last().uri.endsWith("/backup"))
        assertEquals(primarySession, engine.lastSession)
    }

    @Test
    fun bufferingAfterFirstFrameIsDifferentFromInitialLoading() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val states = mutableListOf<PlaybackUiState>()
        val coordinator = coordinator(engine, scheduler, states)
        coordinator.select(request("one"))
        val session = engine.lastSession

        engine.emit(PlaybackEvent.FirstFrame(session))
        engine.emit(PlaybackEvent.Loading(session))

        assertEquals(PlaybackUiState.Buffering("one"), states.last())
    }

    @Test
    fun readyBeforeBufferingDeadlineKeepsCurrentSource() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler, mutableListOf())
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        engine.emit(PlaybackEvent.Loading(session))
        engine.emit(PlaybackEvent.Ready(session))

        assertTrue(scheduler.tasks.none { it.delayMs == 25_000L })
        assertEquals(1, engine.playedRequests.size)
    }

    @Test
    fun manualSourceSwitchExposesOneBasedLineNumberAndDoesNothingPastLastSource() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler, mutableListOf())
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))

        assertEquals(1, coordinator.sourceIndex)
        assertEquals(2, coordinator.sourceCount)
        assertTrue(coordinator.switchToNextSource())
        assertEquals(2, coordinator.sourceIndex)
        assertTrue(!coordinator.switchToNextSource())
        assertEquals(2, engine.playedRequests.size)
    }

    @Test
    fun compatibilityPrebufferDeadlineWithoutFirstFrameSwitchesToNextSource() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler, mutableListOf())
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
        val primarySession = engine.lastSession
        engine.emit(PlaybackEvent.Loading(primarySession))

        scheduler.runNextWithDelay(15_000)

        assertTrue(engine.playedRequests.last().uri.endsWith("/backup"))
        assertEquals(primarySession, engine.lastSession)
    }

    @Test
    fun bufferingDeadlineCannotSwitchSourceWhilePaused() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler, mutableListOf())
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        engine.emit(PlaybackEvent.Loading(session))
        val delayedAction = scheduler.tasks.first { it.delayMs == 25_000L }.action

        coordinator.pause()
        delayedAction()

        assertEquals(1, engine.playedRequests.size)
    }

    @Test
    fun recoveryWaitingDuringPauseReopensWhenPlaybackResumes() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler, mutableListOf())
        coordinator.select(request("one"))
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        engine.emit(PlaybackEvent.Error(session, PlaybackErrorKind.TEMPORARY_NETWORK, 2001))

        coordinator.pause()
        coordinator.resume()

        assertEquals(1, engine.reopenCount)
        assertEquals(1, engine.resumeCount)
    }

    @Test
    fun frozenVideoTriesAnotherKernelBeforeAnotherSource() {
        val engine = FakeEngine().apply { alternativeAvailable = true }
        val scheduler = FakeScheduler()
        var clock = 0L
        val coordinator = PlaybackCoordinator(
            engine, scheduler, PlaybackDiagnostics(), elapsedRealtimeMs = { clock },
            onStateChanged = { _ -> }, onFirstFrame = {},
        )
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        // 画面持续不推进越过安全阀（15 秒）后才动手换内核。
        clock = 20_000L

        scheduler.runNextWithDelay(6_000)

        assertEquals(1, engine.alternativeAttempts)
        assertEquals(1, engine.playedRequests.size)
        assertTrue(engine.playedRequests.single().uri.endsWith("/primary"))
    }

    @Test
    fun freezeCheckAtSixSecondsSchedulesTheFifteenSecondAction() {
        val engine = FakeEngine().apply { alternativeAvailable = true }
        val scheduler = FakeScheduler()
        var clock = 0L
        val coordinator = PlaybackCoordinator(
            engine, scheduler, PlaybackDiagnostics(), elapsedRealtimeMs = { clock },
            onStateChanged = {}, onFirstFrame = {},
        )
        coordinator.select(request("one"))
        engine.emit(PlaybackEvent.FirstFrame(engine.lastSession))
        clock = 6_000L
        scheduler.runNextWithDelay(6_000)
        assertEquals(0, engine.alternativeAttempts)
        clock = 15_000L
        scheduler.runNextWithDelay(9_000)
        assertEquals(1, engine.alternativeAttempts)
    }

    @Test
    fun pausedOrSupersededFreezeActionCannotRecover() {
        val engine = FakeEngine().apply { alternativeAvailable = true }
        val scheduler = FakeScheduler()
        var clock = 0L
        val coordinator = PlaybackCoordinator(
            engine, scheduler, PlaybackDiagnostics(), elapsedRealtimeMs = { clock },
            onStateChanged = {}, onFirstFrame = {},
        )
        coordinator.select(request("one"))
        engine.emit(PlaybackEvent.FirstFrame(engine.lastSession))
        clock = 6_000L
        scheduler.runNextWithDelay(6_000)
        val oldAction = scheduler.tasks.first { it.delayMs == 9_000L }.action
        coordinator.pause()
        clock = 15_000L
        oldAction()
        coordinator.resume()
        engine.emit(PlaybackEvent.VideoProgress(engine.lastSession, 1_000L))
        oldAction()
        coordinator.select(request("two"))
        oldAction()
        assertEquals(0, engine.alternativeAttempts)
    }

    @Test
    fun continuingVideoProgressKeepsCurrentKernelAndSource() {
        val engine = FakeEngine().apply { alternativeAvailable = true }
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler, mutableListOf())
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        val oldDeadline = scheduler.tasks.first { it.delayMs == 6_000L }.action
        engine.emit(PlaybackEvent.VideoProgress(session, 1_000_000L))

        oldDeadline()

        assertEquals(0, engine.alternativeAttempts)
        assertEquals(1, engine.playedRequests.size)
    }

    @Test
    fun stablePlaybackTeachesEnginePreference() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler, mutableListOf())
        coordinator.select(request("one"))
        engine.emit(PlaybackEvent.FirstFrame(engine.lastSession))
        // 「稳定」以画面为准：窗口内必须有真实画面推进，否则"播放器报就绪、画面冻着"的线
        // 会被误判为稳定并重置恢复预算（2026-09-17 实证的死循环）。
        engine.emit(PlaybackEvent.VideoProgress(engine.lastSession, 1_000L))

        scheduler.runNextWithDelay(60_000)

        assertEquals(1, engine.stableNotifications)
    }

    /**
     * 画面冻着（播放器仍报就绪）不得被判为"稳定"：否则会重置恢复预算，
     * 陷入"每几分钟试两次 → 判成功 → 再等一分钟"的死循环（2026-09-17 实证）。
     */
    @Test
    fun frozenPictureIsNeverTreatedAsStable() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        var clock = 0L
        val coordinator = PlaybackCoordinator(
            engine, scheduler, PlaybackDiagnostics(), elapsedRealtimeMs = { clock },
            onStateChanged = { _ -> }, onFirstFrame = {},
        )
        coordinator.select(request("one"))
        clock = 1_000L
        engine.emit(PlaybackEvent.FirstFrame(engine.lastSession))
        engine.emit(PlaybackEvent.VideoProgress(engine.lastSession, 1_000L))
        // 画面从此不再推进；把时钟推过 60 秒稳定窗口（也已远超 6 秒冻结阈值）。
        clock = 61_000L

        scheduler.tasks.last { it.delayMs == 60_000L }.action()

        assertEquals(0, engine.stableNotifications)
    }

    /** 画面冻结时会真的进入恢复阶梯（不再是"检测到了但没有动作"）。 */
    @Test
    fun frozenPictureEnterSRecoveryInsteadOfSilence() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        var clock = 0L
        val states = mutableListOf<PlaybackUiState>()
        val coordinator = PlaybackCoordinator(
            engine, scheduler, PlaybackDiagnostics(), elapsedRealtimeMs = { clock },
            onStateChanged = states::add, onFirstFrame = {},
        )
        coordinator.select(request("one"))
        clock = 1_000L
        engine.emit(PlaybackEvent.FirstFrame(engine.lastSession))
        engine.emit(PlaybackEvent.VideoProgress(engine.lastSession, 1_000L))
        clock = 20_000L  // 距上次推进 19 秒：已过 6 秒留痕阈值、也越过 15 秒动作安全阀

        scheduler.tasks.last { it.delayMs == 6_000L }.action()

        assertTrue(
            "画面冻结应触发恢复，而不是静默",
            states.any { it is PlaybackUiState.Recovering } || engine.reopenCount > 0,
        )
    }

    /**
     * 自愈期间刷新候选：正在播放的线路不动，但下一次切线用**新阵容**的顺序。
     * （枢纽中途修好/换线后，整场沿用开播阵容会一直停在坏线上。）
     */
    @Test
    fun candidateRefreshKeepsPlayingLineAndUsesFreshLineupForNextSwitch() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler, mutableListOf())
        coordinator.select(request("one", "a"))
        engine.emit(PlaybackEvent.FirstFrame(engine.lastSession))

        val changed = coordinator.updateCandidates(
            listOf(request("one", "a"), request("one", "b")),
        )

        assertTrue(changed)
        assertTrue(coordinator.switchToNextSource())
        assertEquals("https://example.invalid/b", engine.playedRequests.last().uri)
    }

    @Test
    fun failureInvalidatesPendingStablePlaybackLearning() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler, mutableListOf())
        coordinator.select(request("one"))
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        val staleStableAction = scheduler.tasks.first { it.delayMs == 60_000L }.action

        engine.emit(PlaybackEvent.Error(session, PlaybackErrorKind.OFFLINE, 2001))
        staleStableAction()

        assertEquals(0, engine.stableNotifications)
    }

    @Test fun receivingMediaAdvancesOpeningButNeverClaimsAFrameWasDisplayed() {
        val engine = FakeEngine()
        val states = mutableListOf<PlaybackUiState>()
        val c = coordinator(engine, FakeScheduler(), states)
        c.select(request("one"))
        val old = engine.lastSession
        engine.emit(PlaybackEvent.Technical(old, PlaybackTechnicalUpdate.PreparingPlayback))
        assertEquals(PlaybackUiState.Loading("one", OpeningStage.PREPARING), states.last())
        engine.emit(PlaybackEvent.Loading(old))
        assertEquals(PlaybackUiState.Loading("one", OpeningStage.PREPARING), states.last())
        c.select(request("two"))
        engine.emit(PlaybackEvent.Technical(old, PlaybackTechnicalUpdate.PreparingPlayback))
        assertEquals(PlaybackUiState.Loading("two"), states.last())
        engine.emit(PlaybackEvent.FirstFrame(engine.lastSession))
        engine.emit(PlaybackEvent.Technical(engine.lastSession, PlaybackTechnicalUpdate.PreparingPlayback))
        assertEquals(PlaybackUiState.Ready("two"), states.last())
    }

    private fun exhaust(engine: FakeEngine, scheduler: FakeScheduler) {
        repeat(2) {
            engine.emit(PlaybackEvent.Error(engine.lastSession, PlaybackErrorKind.TEMPORARY_NETWORK, 2001))
            scheduler.runNext()
        }
        engine.emit(PlaybackEvent.Error(engine.lastSession, PlaybackErrorKind.TEMPORARY_NETWORK, 2001))
    }

    @Test fun cooldownBacksOffAndCannotReviveAnOldChannel() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val c = coordinator(engine, scheduler, mutableListOf())
        c.select(request("one"))
        exhaust(engine, scheduler)
        scheduler.runNext()
        exhaust(engine, scheduler)
        assertEquals(60_000L, scheduler.tasks.single().delayMs)
        val old = scheduler.tasks.single().action
        c.select(request("two"))
        old()
        assertEquals(3, engine.playedRequests.size)
        assertEquals("two", engine.playedRequests.last().channelId)
    }

    @Test fun cooldownPausesAndResumesWithoutImmediateReopen() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val c = coordinator(engine, scheduler, mutableListOf())
        c.select(request("one"))
        exhaust(engine, scheduler)
        val old = scheduler.tasks.single().action
        c.pause()
        assertTrue(scheduler.tasks.isEmpty())
        c.resume()
        old()
        assertEquals(1, engine.playedRequests.size)
        assertEquals(30_000L, scheduler.tasks.single().delayMs)
        scheduler.runNext()
        assertEquals(2, engine.playedRequests.size)
    }

    @Test fun lateEventsCannotCancelCooldownAndStopCancelsIt() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val states = mutableListOf<PlaybackUiState>()
        val c = coordinator(engine, scheduler, states)
        c.select(request("one"))
        exhaust(engine, scheduler)
        val old = scheduler.tasks.single().action
        engine.emit(PlaybackEvent.FirstFrame(engine.lastSession))
        engine.emit(PlaybackEvent.Error(engine.lastSession, PlaybackErrorKind.TEMPORARY_NETWORK, 2001))
        assertTrue(states.last() is PlaybackUiState.Failed)
        assertEquals(30_000L, scheduler.tasks.single().delayMs)
        c.stop()
        old()
        assertEquals(1, engine.playedRequests.size)
        assertEquals(PlaybackUiState.Idle, states.last())
    }

    @Test fun unavailableAuthorizationDoesNotReconnectForever() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val states = mutableListOf<PlaybackUiState>()
        val c = coordinator(engine, scheduler, states)
        c.select(request("one"))
        engine.emit(PlaybackEvent.Error(engine.lastSession, PlaybackErrorKind.AUTHORIZATION, 403))
        c.pause()
        c.resume()
        assertTrue(scheduler.tasks.isEmpty())
        assertEquals(0, engine.reopenCount)
        assertEquals(PlaybackUiState.Failed("one", PlaybackErrorKind.AUTHORIZATION), states.last())
    }

    private fun coordinator(
        engine: FakeEngine,
        scheduler: FakeScheduler,
        states: MutableList<PlaybackUiState>,
    ) = PlaybackCoordinator(
        engine, scheduler, PlaybackDiagnostics(), elapsedRealtimeMs = { 0L },
        onStateChanged = states::add, onFirstFrame = {},
    )

    private fun request(id: String, source: String = "live") =
        PlaybackRequest(id, "https://example.invalid/$source")
}

internal class FakeEngine : PlaybackEngine {
    private var listener: (PlaybackEvent) -> Unit = {}
    var lastSession = 0L
    var reopenCount = 0
    var resumeCount = 0
    var alternativeAvailable = false
    var alternativeAttempts = 0
    var stableNotifications = 0
    var recoverToLiveCount = 0
    val playedRequests = mutableListOf<PlaybackRequest>()

    override fun setEventListener(listener: (PlaybackEvent) -> Unit) { this.listener = listener }
    override fun play(sessionId: Long, request: PlaybackRequest) {
        lastSession = sessionId
        playedRequests += request
    }
    override fun reopen(sessionId: Long) { if (sessionId == lastSession) reopenCount++ }
    override fun recoverToLive(sessionId: Long) { if (sessionId == lastSession) recoverToLiveCount++ }
    override fun pause() = Unit
    override fun resume() { resumeCount++ }
    override fun stop() = Unit
    override fun release() = Unit
    override fun tryAlternative(sessionId: Long, kind: PlaybackErrorKind, errorCode: Int): Boolean {
        alternativeAttempts++
        return sessionId == lastSession && alternativeAvailable
    }
    override fun onPlaybackStable(sessionId: Long) {
        if (sessionId == lastSession) stableNotifications++
    }
    fun emit(event: PlaybackEvent) = listener(event)
}

internal class FakeScheduler : RetryScheduler {
    data class Task(val delayMs: Long, val action: () -> Unit)

    val tasks = ArrayDeque<Task>()
    override fun schedule(delayMs: Long, action: () -> Unit) { tasks.addLast(Task(delayMs, action)) }
    override fun cancelAll() { tasks.clear() }
    fun runNext() = tasks.removeFirst().action.invoke()
    fun runNextWithDelay(delayMs: Long) {
        val task = tasks.first { it.delayMs == delayMs }
        tasks.remove(task)
        task.action.invoke()
    }
}
