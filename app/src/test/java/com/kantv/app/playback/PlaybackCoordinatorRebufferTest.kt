package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests for the candidate-health path: the coordinator must leave an unstable candidate
 * on its own evidence instead of waiting for Media3 to raise BEHIND_LIVE_WINDOW (1002).
 */
class PlaybackCoordinatorRebufferTest {
    private val timeline = mutableListOf<Long>()
    private val facts = mutableListOf<PlaybackDiagnosticFact>()

    private fun coordinator(
        engine: FakeEngine,
        scheduler: FakeScheduler,
        states: MutableList<PlaybackUiState> = mutableListOf(),
        countedRebuffers: MutableList<Long> = mutableListOf(),
    ) = PlaybackCoordinator(
        // The primary constructor is used deliberately: it is the only one that exposes the
        // candidate-health store and the diagnostic fact sink under test.
        engineFactory = PlaybackEngineFactory { engine },
        scheduler = scheduler,
        diagnostics = PlaybackDiagnostics(elapsedRealtimeMs = { now() }),
        elapsedRealtimeMs = { now() },
        onStateChanged = states::add,
        onFirstFrame = {},
        onDiagnostic = facts::add,
        onRebufferCounted = countedRebuffers::add,
    )

    private fun now(): Long = timeline.sum()

    private fun advance(ms: Long) {
        timeline += ms
    }

    private fun request(id: String, source: String) = PlaybackRequest(id, "https://example.invalid/$source")

    @Test
    fun candidatesPlayInTheOrderTheCatalogueGaveThem() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler)

        coordinator.select(
            listOf(request("one", "first"), request("one", "second"), request("one", "third")),
        )

        // No candidate is reordered from this device's own playback history any more. History used to
        // reorder this list and it put a source that only showed a frozen test card first, which is why
        // the viewer met a dead source on every open. The first candidate played is the first one given.
        assertEquals("https://example.invalid/first", engine.playedRequests.single().uri)
    }

    @Test
    fun repeatedShortHiccupsNoLongerSwitchCandidate() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val stateAtSwitch = mutableListOf<PlaybackUiState>()
        val coordinator = coordinator(engine, scheduler, stateAtSwitch)
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        // Past the first-frame grace window: an interruption inside it is diagnostic only.
        advance(20_000)

        engine.emit(PlaybackEvent.Loading(session))
        advance(2_500)
        engine.emit(PlaybackEvent.Ready(session))

        engine.emit(PlaybackEvent.Loading(session))
        advance(2_000)
        engine.emit(PlaybackEvent.Ready(session))

        // 4.5 s of hiccups inside the window is tolerated: the viewer keeps watching the candidate
        // they were on instead of being moved to a different live edge. No Error(1002) was emitted.
        assertEquals(1, engine.playedRequests.size)
        assertEquals(session, engine.lastSession)
        assertTrue(facts.none { it.reason == PlaybackDiagnosticReason.REBUFFER_UNSTABLE })

        val rebufferFact = facts.first { it.reason == PlaybackDiagnosticReason.REBUFFER_INTERVAL }
        assertEquals(2_500L, rebufferFact.rebufferDurationMs)
        // Only a digest is ever reported for the candidate.
        assertEquals(64, rebufferFact.sourceHash.length)
        assertFalse(rebufferFact.sourceHash.contains("example.invalid"))
    }

    @Test
    fun aLongRebufferRecoversInPlaceBeforeSwitchingCandidate() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val stateAtSwitch = mutableListOf<PlaybackUiState>()
        val coordinator = coordinator(engine, scheduler, stateAtSwitch)
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        advance(20_000)

        engine.emit(PlaybackEvent.Loading(session))
        advance(26_000)
        engine.emit(PlaybackEvent.Ready(session))

        // A backup candidate exists, but a stall is still treated as something to recover from first:
        // reopening the same source resumes at its own live edge, which is continuous with what the
        // viewer was watching. Switching is the fallback once the recovery budget is spent.
        assertEquals(1, engine.playedRequests.size)
        assertEquals(session, engine.lastSession)
        val unstable = facts.first { it.reason == PlaybackDiagnosticReason.REBUFFER_UNSTABLE }
        assertEquals(RebufferHealthPolicy.UnstableReason.SINGLE_LONG_REBUFFER, unstable.unstableReason)
        // The instability fact belongs to the candidate that stalled, before anything else happens.
        assertEquals(1, unstable.sourceOrdinal)
        assertEquals(2, unstable.sourceCount)
        assertTrue(engine.reopenCount + scheduler.tasks.size > 0)
    }

    @Test
    fun oneShortJitterAfterTheGraceWindowDoesNotSwitchCandidate() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler)
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        advance(20_000)

        engine.emit(PlaybackEvent.Loading(session))
        advance(1_100)
        engine.emit(PlaybackEvent.Ready(session))

        assertEquals(1, engine.playedRequests.size)
        assertFalse(facts.any { it.reason == PlaybackDiagnosticReason.REBUFFER_UNSTABLE })
    }

    @Test
    fun rebufferingInsideTheFirstFrameGraceWindowIsDiagnosticOnly() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler)
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        advance(200)

        engine.emit(PlaybackEvent.Loading(session))
        advance(7_000)
        engine.emit(PlaybackEvent.Ready(session))

        assertEquals(1, engine.playedRequests.size)
        assertFalse(facts.any { it.reason == PlaybackDiagnosticReason.REBUFFER_UNSTABLE })
    }

    @Test
    fun theLastCandidateFallsBackToTheRecoveryLadderInsteadOfSwitching() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler)
        coordinator.select(request("one", "only"))
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        advance(20_000)

        engine.emit(PlaybackEvent.Loading(session))
        advance(26_000)
        engine.emit(PlaybackEvent.Ready(session))

        assertEquals(1, engine.playedRequests.size)
        // Nothing left to switch to, so the candidate is recorded as unhealthy and the existing
        // recovery ladder takes over (a reopen is scheduled asynchronously).
        assertTrue(facts.any { it.reason == PlaybackDiagnosticReason.REBUFFER_UNSTABLE })
        assertTrue(engine.reopenCount + scheduler.tasks.size > 0)
    }

    @Test
    fun everyCountedRebufferReachesTheFeedbackPathWithItsOwnDuration() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val counted = mutableListOf<Long>()
        val coordinator = coordinator(engine, scheduler, countedRebuffers = counted)
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        // Past the first-frame grace window, so these are the very episodes the health policy
        // counts. Self-recovering ones are the point: they raise no recovery action and no stall.
        advance(20_000)

        engine.emit(PlaybackEvent.Loading(session))
        advance(2_000)
        engine.emit(PlaybackEvent.VideoProgress(session, 1_000_000))
        engine.emit(PlaybackEvent.Loading(session))
        advance(1_500)
        engine.emit(PlaybackEvent.VideoProgress(session, 3_000_000))
        engine.emit(PlaybackEvent.Loading(session))
        advance(300)
        engine.emit(PlaybackEvent.VideoProgress(session, 5_000_000))

        // Only the episodes the policy counted (>= its 600 ms floor) are handed over, with the
        // interval duration, so the feedback path can judge repetition without a second definition.
        assertEquals(listOf(2_000L, 1_500L), counted)
    }

    @Test
    fun aLateErrorFromAnotherSessionCannotPolluteTheCurrentCandidate() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler)
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        advance(20_000)

        engine.emit(PlaybackEvent.Error(session + 999L, PlaybackErrorKind.BEHIND_LIVE_WINDOW, 1002))

        assertEquals(1, engine.playedRequests.size)
        assertFalse(facts.any { it.reason == PlaybackDiagnosticReason.BEHIND_LIVE_WINDOW })
    }

    @Test
    fun rapidChannelChangesCannotAccumulateRebufferingAcrossSessions() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler)

        // Six channel changes, each with a 2.5 s buffering episode under a frozen clock. If the
        // policy leaked across sessions, the sliding window would declare instability and start
        // switching candidates by itself.
        repeat(6) {
            coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
            val session = engine.lastSession
            engine.emit(PlaybackEvent.FirstFrame(session))
            engine.emit(PlaybackEvent.Loading(session))
            engine.emit(PlaybackEvent.Ready(session))
        }

        assertEquals(6, engine.playedRequests.size)
        assertTrue(engine.playedRequests.all { it.uri.endsWith("/primary") })
        assertFalse(facts.any { it.reason == PlaybackDiagnosticReason.REBUFFER_UNSTABLE })
        assertEquals(
            "every channel change must open a fresh source generation",
            6,
            facts.count { it.reason == PlaybackDiagnosticReason.SOURCE_START },
        )
    }

    @Test
    fun aStoppedSessionIsClosedAndItsLateEventsAreIgnored() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler)
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        advance(20_000)
        val factsBeforeStop = facts.size

        coordinator.stop()
        engine.emit(PlaybackEvent.FirstFrame(session))
        engine.emit(PlaybackEvent.Loading(session))
        advance(2_500)
        engine.emit(PlaybackEvent.Ready(session))
        engine.emit(PlaybackEvent.Error(session, PlaybackErrorKind.BEHIND_LIVE_WINDOW, 1002))

        assertTrue(facts.takeLast(1).all { it.reason == PlaybackDiagnosticReason.SESSION_ENDED })
        assertEquals(factsBeforeStop + 1, facts.size)
        assertEquals(1, engine.playedRequests.size)
    }

    @Test
    fun aHundredAndTwoStillRecoversToLiveWhenTheCandidateWasOtherwiseStable() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler)
        coordinator.select(request("one", "only"))
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        advance(30_000)

        engine.emit(PlaybackEvent.Error(session, PlaybackErrorKind.BEHIND_LIVE_WINDOW, 1002))

        // The final protection is untouched by the early-switch path: a stable candidate that goes
        // behind live is still recovered rather than abandoned.
        assertEquals(1, engine.recoverToLiveCount)
        assertEquals(0, engine.reopenCount)
        val fact = facts.last()
        assertEquals(PlaybackDiagnosticReason.BEHIND_LIVE_WINDOW, fact.reason)
        assertEquals(1002, fact.errorCode)
        assertEquals(session, engine.lastSession)
    }

    @Test
    fun aWatchdogLeavingTheCandidateIsReportedAsItsOwnReason() {
        val engine = FakeEngine()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(engine, scheduler)
        coordinator.select(listOf(request("one", "primary"), request("one", "backup")))
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        advance(1_000)
        engine.emit(PlaybackEvent.Loading(session))

        val stall = scheduler.tasks.firstOrNull { it.delayMs == 25_000L }
        stall?.action()

        // The watchdog fact is followed by the AUTO_SWITCH fact that starts the next candidate, so
        // the assertion is on presence rather than on "last".
        assertTrue(facts.any { it.reason == PlaybackDiagnosticReason.BUFFERING_STALL })
        assertEquals(PlaybackDiagnosticReason.AUTO_SWITCH, facts.last().reason)
        assertEquals(2, engine.playedRequests.size)
    }
}
