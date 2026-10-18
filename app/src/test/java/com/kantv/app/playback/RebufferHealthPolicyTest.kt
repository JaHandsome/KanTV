package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RebufferHealthPolicyTest {
    @Test
    fun repeatedLoadingInsideOneEpisodeDoesNotRestartTheMeasurement() {
        val policy = RebufferHealthPolicy()
        policy.beginSource(sessionId = 7L, generation = 3L, nowMs = 0L)
        policy.onEvent(PlaybackEvent.FirstFrame(7L), 1_000L)

        // A player that re-reports buffering several times must not reset the measured duration.
        policy.onEvent(PlaybackEvent.Loading(7L), 10_000L)
        policy.onEvent(PlaybackEvent.Loading(7L), 12_000L)
        val verdict = policy.onEvent(PlaybackEvent.Loading(7L), 14_000L)
        // The interval is open, so nothing is counted yet; only `buffering` is true.
        assertTrue(verdict.buffering)
        assertEquals(0, verdict.countedRebuffer)

        val closed = policy.onEvent(PlaybackEvent.Ready(7L), 17_000L)
        assertNotNull(closed.event)
        assertEquals(7_000L, closed.event?.durationMs)
        assertEquals(RebufferHealthPolicy.RebufferReason.PLAYER_BUFFERING, closed.event?.reason)
        assertEquals(3L, closed.event?.generation)
    }

    @Test
    fun aSingleShortJitterNeverSwitchesTheCandidate() {
        val policy = RebufferHealthPolicy()
        policy.beginSource(sessionId = 1L, generation = 1L, nowMs = 0L)
        policy.onEvent(PlaybackEvent.FirstFrame(1L), 500L)
        policy.onEvent(PlaybackEvent.Loading(1L), 10_000L)
        policy.onEvent(PlaybackEvent.Ready(1L), 10_400L)
        // Both episodes are below the 600 ms floor, so neither is even recorded.
        policy.onEvent(PlaybackEvent.Loading(1L), 20_000L)
        policy.onEvent(PlaybackEvent.Ready(1L), 20_500L)

        assertFalse(policy.isUnstable())
        val health = policy.health(20_500L)
        assertEquals(0, health.countedRebufferInWindow)
        assertEquals(0L, health.countedRebufferMsInWindow)
        // Sub-threshold interruptions are discarded rather than recorded as one-off noise.
        assertEquals(0, health.rebufferCount)
        assertEquals(0L, health.totalRebufferMs)
    }

    @Test
    fun aHiccupOfAFewSecondsIsNeverInstability() {
        val policy = RebufferHealthPolicy()
        policy.beginSource(sessionId = 2L, generation = 1L, nowMs = 0L)
        policy.onEvent(PlaybackEvent.FirstFrame(2L), 1_000L)

        // Two ordinary hiccups inside one window. These used to add up to instability, which moved
        // the viewer to another candidate mid-programme; live candidates sit at different live edges,
        // so that jump is exactly what a viewer notices and dislikes. Hiccups are waited out now.
        policy.onEvent(PlaybackEvent.Loading(2L), 10_000L)
        policy.onEvent(PlaybackEvent.Ready(2L), 12_500L)
        assertFalse(policy.isUnstable())

        policy.onEvent(PlaybackEvent.Loading(2L), 20_000L)
        val verdict = policy.onEvent(PlaybackEvent.Ready(2L), 22_000L)

        assertFalse(verdict.unstable)
    }

    @Test
    fun accumulatedInterruptionsStillReachTheThresholdInsideTheWindow() {
        val policy = RebufferHealthPolicy()
        policy.beginSource(sessionId = 2L, generation = 1L, nowMs = 0L)
        policy.onEvent(PlaybackEvent.FirstFrame(2L), 1_000L)

        // Episode one: 10 s, still within tolerance.
        policy.onEvent(PlaybackEvent.Loading(2L), 10_000L)
        policy.onEvent(PlaybackEvent.Ready(2L), 20_000L)
        assertFalse(policy.isUnstable())

        // Episode two: 21 s, so 31 s cumulative inside the window -> unstable without waiting for 1002.
        policy.onEvent(PlaybackEvent.Loading(2L), 25_000L)
        val verdict = policy.onEvent(PlaybackEvent.Ready(2L), 46_000L)

        assertTrue(verdict.unstable)
        assertEquals(RebufferHealthPolicy.UnstableReason.ACCUMULATED_DURATION, verdict.unstableReason)
        assertEquals(2, verdict.countedRebuffer)
        assertEquals(31_000L, verdict.countedRebufferMs)
    }

    @Test
    fun sixSeparateCountedRebuffersCrossTheCountThreshold() {
        val policy = RebufferHealthPolicy()
        policy.beginSource(sessionId = 3L, generation = 1L, nowMs = 0L)
        policy.onEvent(PlaybackEvent.FirstFrame(3L), 1_000L)
        var now = 10_000L
        repeat(5) {
            policy.onEvent(PlaybackEvent.Loading(3L), now)
            policy.onEvent(PlaybackEvent.Ready(3L), now + 900L)
            now += 3_000L
        }
        assertFalse(policy.isUnstable())

        policy.onEvent(PlaybackEvent.Loading(3L), now)
        val verdict = policy.onEvent(PlaybackEvent.Ready(3L), now + 900L)

        assertTrue(verdict.unstable)
        assertEquals(RebufferHealthPolicy.UnstableReason.REPEATED_REBUFFERS, verdict.unstableReason)
    }

    @Test
    fun oneLongRebufferIsEnoughOnItsOwn() {
        val policy = RebufferHealthPolicy()
        policy.beginSource(sessionId = 4L, generation = 1L, nowMs = 0L)
        policy.onEvent(PlaybackEvent.FirstFrame(4L), 1_000L)
        policy.onEvent(PlaybackEvent.Loading(4L), 10_000L)
        // 26 s: past the point where a stall stops looking like a hiccup.
        val verdict = policy.onEvent(PlaybackEvent.Ready(4L), 36_000L)

        assertTrue(verdict.unstable)
        assertEquals(RebufferHealthPolicy.UnstableReason.SINGLE_LONG_REBUFFER, verdict.unstableReason)
    }

    @Test
    fun videoProgressClosesTheIntervalAndResetsTheFrameBaseline() {
        val policy = RebufferHealthPolicy()
        policy.beginSource(sessionId = 5L, generation = 1L, nowMs = 0L)
        policy.onEvent(PlaybackEvent.FirstFrame(5L), 0L)
        policy.onEvent(PlaybackEvent.Loading(5L), 5_000L)

        val verdict = policy.onEvent(PlaybackEvent.VideoProgress(5L, 900_000L), 6_400L)

        assertEquals(1_400L, verdict.event?.durationMs)
        assertEquals(900_000L, verdict.lastVideoPtsUs)
        // The interval is consumed: a second Ready cannot close it again.
        assertNull(policy.onEvent(PlaybackEvent.Ready(5L), 7_000L).event)
    }

    @Test
    fun aFrozenPictureIsMeasuredFromTheLastObservedFrame() {
        val policy = RebufferHealthPolicy()
        policy.beginSource(sessionId = 6L, generation = 1L, nowMs = 0L)
        policy.onEvent(PlaybackEvent.FirstFrame(6L), 1_000L)
        policy.onEvent(PlaybackEvent.VideoProgress(6L, 1_000_000L), 2_000L)

        // No buffering state change at all: the player simply stopped advancing the picture. The gap
        // has to outlive the stall threshold before any of it is attributed to rebuffering.
        val gapEndMs = 2_000L + RebufferHealthPolicy.FRAME_STALL_THRESHOLD_MS + 8_000L
        val verdict = policy.onEvent(PlaybackEvent.VideoProgress(6L, 1_200_000L), gapEndMs)

        val event = verdict.event
        assertNotNull(event)
        assertEquals(RebufferHealthPolicy.RebufferReason.FRAME_STALL, event?.reason)
        // Only the time beyond the stall threshold counts, and it is measured from the last frame.
        assertEquals(2_000L + RebufferHealthPolicy.FRAME_STALL_THRESHOLD_MS, event?.startedAtMs)
        assertEquals(8_000L, event?.durationMs)
    }

    @Test
    fun pausedWallClockIsNeverCountedAsRebuffering() {
        val policy = RebufferHealthPolicy()
        policy.beginSource(sessionId = 8L, generation = 1L, nowMs = 0L)
        policy.onEvent(PlaybackEvent.FirstFrame(8L), 1_000L)
        policy.onEvent(PlaybackEvent.Loading(8L), 10_000L)

        val paused = policy.pause(12_000L)
        assertEquals(2_000L, paused.event?.durationMs)
        // A Loading callback that arrives while paused must not open a new interval.
        assertNull(policy.onEvent(PlaybackEvent.Loading(8L), 60_000L).event)
        assertEquals(1, policy.health(60_000L).rebufferCount)
        // Paused wall-clock time is still never attributed to the source.
        assertEquals(2_000L, policy.health(60_000L).totalRebufferMs)
    }

    @Test
    fun resumeRetainsWindowHistoryButRequiresANewFirstFrame() {
        val policy = RebufferHealthPolicy()
        policy.beginSource(sessionId = 9L, generation = 1L, nowMs = 0L)
        policy.onEvent(PlaybackEvent.FirstFrame(9L), 1_000L)
        policy.onEvent(PlaybackEvent.Loading(9L), 10_000L)
        policy.onEvent(PlaybackEvent.Ready(9L), 20_000L)

        policy.pause(21_000L)
        val resumed = policy.resume(22_000L, generation = 2L)

        // The picture boundary survives a resume: Media3 does not re-emit a rendered first frame,
        // so the window history must stay counted or the early-switch path would die after any pause.
        assertTrue(resumed.firstFrameObserved)
        assertEquals(2L, resumed.generation)
        assertEquals(1, resumed.countedRebuffer)
        assertEquals(10_000L, resumed.countedRebufferMs)
        // A buffering callback after resume still accumulates on the same source, and the pre-pause
        // episode is still inside the window, so the two together cross the accumulated threshold.
        policy.onEvent(PlaybackEvent.Loading(9L), 23_000L)
        val afterResume = policy.onEvent(PlaybackEvent.Ready(9L), 44_000L)
        assertTrue(afterResume.unstable)
        assertEquals(2L, afterResume.event?.generation)
    }

    @Test
    fun anotherSessionsCallbacksCannotPolluteTheCurrentCandidate() {
        val policy = RebufferHealthPolicy()
        policy.beginSource(sessionId = 10L, generation = 1L, nowMs = 0L)
        policy.onEvent(PlaybackEvent.FirstFrame(10L), 1_000L)

        // Late callbacks from a stopped external session.
        val stale = policy.onEvent(PlaybackEvent.Loading(99L), 5_000L)
        assertNull(stale.event)
        assertFalse(stale.buffering)
        policy.onEvent(PlaybackEvent.Ready(99L), 9_000L)
        policy.onEvent(PlaybackEvent.Error(99L, PlaybackErrorKind.BEHIND_LIVE_WINDOW, 1002), 9_500L)

        val health = policy.health(10_000L)
        assertEquals(0, health.rebufferCount)
        assertFalse(health.unstable)
        assertEquals(-1L, health.lastVideoPtsUs)
    }

    @Test
    fun beginSourceEndsTheOldCandidateAndStartsACleanGeneration() {
        val policy = RebufferHealthPolicy()
        policy.beginSource(sessionId = 11L, generation = 1L, nowMs = 0L)
        policy.onEvent(PlaybackEvent.FirstFrame(11L), 1_000L)
        policy.onEvent(PlaybackEvent.Loading(11L), 10_000L)
        policy.onEvent(PlaybackEvent.Ready(11L), 36_000L)
        assertTrue(policy.isUnstable())

        val fresh = policy.beginSource(sessionId = 11L, generation = 2L, nowMs = 37_000L)

        assertFalse(fresh.unstable)
        assertEquals(2L, fresh.generation)
        assertEquals(0, policy.health(37_000L).rebufferCount)
        // The old open interval was discarded at the boundary: a trailing Ready cannot close it.
        assertNull(policy.onEvent(PlaybackEvent.Ready(11L), 38_000L).event)
        val health = policy.health(45_000L)
        assertEquals(0, health.rebufferCount)
        assertEquals(0L, health.totalRebufferMs)
        assertEquals(2L, health.generation)
    }

    @Test
    fun aSlowStutterLoopBecomesUnstableAfterMinutesOfDutyCycleLoss() {
        val policy = RebufferHealthPolicy()
        policy.beginSource(sessionId = 12L, generation = 1L, nowMs = 0L)
        policy.onEvent(PlaybackEvent.FirstFrame(12L), 1_000L)

        // 2 s of buffering every 18 s: small enough that no single episode is evidence, and sparse
        // enough that one minute never holds more than four of them. This is the loop that kept a
        // field box buffering for minutes while every 60 s threshold stayed quiet, so the watchdog
        // reported "recovered" after each episode and the viewer kept watching a stuttering picture.
        val afterOneMinute = policy.playStutterEpisodes(sessionId = 12L, fromMs = 10_000L, episodes = 4)
        assertFalse(policy.isUnstable())
        val quiet = policy.health(afterOneMinute)
        assertEquals(4, quiet.countedRebufferInWindow)
        assertEquals(8_000L, quiet.countedRebufferMsInWindow)

        // The same rhythm for six more minutes. Nothing about it changes except that it lasts.
        val afterSixMinutes = policy.playStutterEpisodes(
            sessionId = 12L,
            fromMs = afterOneMinute + 16_000L,
            episodes = 17,
        )

        val verdict = policy.health(afterSixMinutes)
        assertTrue(verdict.unstable)
        assertEquals(RebufferHealthPolicy.UnstableReason.REPEATED_REBUFFERS, verdict.unstableReason)
        // The tenth episode: the long window needs ~2 minutes of the loop before it holds enough.
        assertEquals(174_000L, verdict.unstableAtMs)
        // At the moment it fired the short window still showed a healthy source, which is exactly why
        // the earlier rules never switched away.
        assertEquals(4, verdict.countedRebufferInWindow)
        assertEquals(8_000L, verdict.countedRebufferMsInWindow)
    }

    @Test
    fun theSameStutterLoopConfinedToTwoMinutesIsStillTolerated() {
        val policy = RebufferHealthPolicy()
        policy.beginSource(sessionId = 13L, generation = 1L, nowMs = 0L)
        policy.onEvent(PlaybackEvent.FirstFrame(13L), 1_000L)

        val afterTwoMinutes = policy.playStutterEpisodes(sessionId = 13L, fromMs = 10_000L, episodes = 7)

        val health = policy.health(afterTwoMinutes)
        assertFalse(health.unstable)
        // Seven interruptions and 14 s of loss: both arms of the sustained rule are short, so the loop
        // has to persist before it is treated as a broken candidate.
        assertEquals(7, health.rebufferCount)
        assertEquals(14_000L, health.totalRebufferMs)
        assertTrue(health.rebufferCount < RebufferHealthPolicy.SUSTAINED_COUNT_IN_WINDOW)
        assertTrue(health.totalRebufferMs < RebufferHealthPolicy.SUSTAINED_TOTAL_MS)
    }

    @Test
    fun isolatedHiccupsOfAFewSecondsStayTolerated() {
        val policy = RebufferHealthPolicy()
        policy.beginSource(sessionId = 14L, generation = 1L, nowMs = 0L)
        policy.onEvent(PlaybackEvent.FirstFrame(14L), 1_000L)

        // One 3 s hiccup right after the start: below the single-interruption limit and alone in the
        // long window.
        val firstHiccupClose = policy.playStutterEpisodes(
            sessionId = 14L,
            fromMs = 30_000L,
            episodes = 1,
            bufferMs = 3_000L,
        )
        assertEquals(1, policy.health(firstHiccupClose).rebufferCount)
        assertFalse(policy.isUnstable())

        // Three more, widely spaced, across five minutes. A live stream hiccups; running for a long
        // time must not by itself turn sparse hiccups into a source switch.
        var last = firstHiccupClose
        listOf(90_000L, 180_000L, 270_000L).forEach { fromMs ->
            last = policy.playStutterEpisodes(sessionId = 14L, fromMs = fromMs, episodes = 1, bufferMs = 3_000L)
        }

        val health = policy.health(last)
        assertFalse(health.unstable)
        assertEquals(4, health.rebufferCount)
        assertEquals(12_000L, health.totalRebufferMs)
        assertTrue(health.rebufferCount < RebufferHealthPolicy.SUSTAINED_COUNT_IN_WINDOW)
    }

    @Test
    fun manyTinyBlipsBelowTheSustainedDurationBudgetStayTolerated() {
        val policy = RebufferHealthPolicy()
        policy.beginSource(sessionId = 15L, generation = 1L, nowMs = 0L)
        policy.onEvent(PlaybackEvent.FirstFrame(15L), 1_000L)

        // Nine counted interruptions in five minutes but only 6.3 s of lost picture: the count arm is
        // satisfied and the duration arm is not. Interruptions that each resolve instantly are worth
        // waiting out, however many of them a long session accumulates.
        val last = policy.playStutterEpisodes(
            sessionId = 15L,
            fromMs = 20_000L,
            episodes = 9,
            bufferMs = 700L,
            everyMs = 30_000L,
        )

        val health = policy.health(last)
        assertFalse(health.unstable)
        assertEquals(9, health.rebufferCount)
        assertEquals(6_300L, health.totalRebufferMs)
        assertTrue(health.rebufferCount >= RebufferHealthPolicy.SUSTAINED_COUNT_IN_WINDOW)
        assertTrue(health.totalRebufferMs < RebufferHealthPolicy.SUSTAINED_TOTAL_MS)
    }

    @Test
    fun theSixtySecondWindowKeepsItsOwnReasons() {
        // Six short interruptions inside one minute still fire on their own count, long before the
        // long window holds anything.
        val counted = RebufferHealthPolicy()
        counted.beginSource(sessionId = 16L, generation = 1L, nowMs = 0L)
        counted.onEvent(PlaybackEvent.FirstFrame(16L), 1_000L)
        counted.playStutterEpisodes(sessionId = 16L, fromMs = 10_000L, episodes = 6, bufferMs = 900L, everyMs = 3_000L)

        assertTrue(counted.isUnstable())
        assertEquals(
            RebufferHealthPolicy.UnstableReason.REPEATED_REBUFFERS,
            counted.health(25_000L).unstableReason,
        )

        // 30 s of loss inside one minute still fires on accumulated duration, and it keeps that
        // reason: the sustained arm is evaluated last and never relabels what the short window saw.
        val accumulated = RebufferHealthPolicy()
        accumulated.beginSource(sessionId = 17L, generation = 1L, nowMs = 0L)
        accumulated.onEvent(PlaybackEvent.FirstFrame(17L), 1_000L)
        accumulated.playStutterEpisodes(sessionId = 17L, fromMs = 10_000L, episodes = 3, bufferMs = 10_000L, everyMs = 22_000L)

        val verdict = accumulated.health(64_000L)
        assertTrue(verdict.unstable)
        assertEquals(
            RebufferHealthPolicy.UnstableReason.ACCUMULATED_DURATION,
            verdict.unstableReason,
        )
    }

    /**
     * Feeds [episodes] interruptions of [bufferMs] each, spaced [everyMs] apart starting at [fromMs],
     * and returns the timestamp at which the last one closed.
     */
    private fun RebufferHealthPolicy.playStutterEpisodes(
        sessionId: Long,
        fromMs: Long,
        episodes: Int,
        bufferMs: Long = 2_000L,
        everyMs: Long = 18_000L,
    ): Long {
        var start = fromMs
        repeat(episodes) {
            onEvent(PlaybackEvent.Loading(sessionId), start)
            onEvent(PlaybackEvent.Ready(sessionId), start + bufferMs)
            start += everyMs
        }
        return start - everyMs + bufferMs
    }
}
