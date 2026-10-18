package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GreyScreenEvidenceTest {
    private var now = 0L
    private fun tracker() = GreyScreenEvidenceTracker(elapsedRealtimeMs = { now })

    @Test
    fun aPlayerThatIsReadyButNeverPresentsIsReportedAsNoFirstFrame() {
        val tracker = tracker()
        tracker.reset()
        tracker.onEvent(PlaybackEvent.Ready(1L), 0L)

        now = 11_000L
        assertNull(tracker.observe(now, renderedFirstFrame = false, presentationTimeUs = -1, audioPresentedFrames = -1, ready = true))

        now = 12_000L
        assertEquals(
            GreyScreenEvidence.NO_FIRST_FRAME,
            tracker.observe(now, renderedFirstFrame = false, presentationTimeUs = -1, audioPresentedFrames = -1, ready = true),
        )
        // Reported once per generation.
        assertNull(tracker.observe(13_000L, false, -1L, -1L, true))
    }

    @Test
    fun soundWithoutAnyPictureIsReportedEarlierAndSeparately() {
        val tracker = tracker()
        tracker.reset()
        tracker.onEvent(PlaybackEvent.Ready(2L), 0L)

        now = 6_500L
        assertEquals(
            GreyScreenEvidence.SOUND_ONLY,
            tracker.observe(
                nowMs = now,
                renderedFirstFrame = false,
                presentationTimeUs = -1L,
                audioPresentedFrames = 1_000L,
                ready = true,
            ),
        )
    }

    @Test
    fun aPictureThatStopsAdvancingIsReportedAsFrozen() {
        val tracker = tracker()
        tracker.reset()
        tracker.onEvent(PlaybackEvent.FirstFrame(3L), 1_000L)

        now = 2_000L
        assertNull(tracker.observe(now, true, 1_000_000L, -1L, true))

        now = 9_000L
        assertNull(tracker.observe(now, true, 1_000_000L, -1L, true))

        now = 10_000L
        assertEquals(
            GreyScreenEvidence.FRAME_FROZEN,
            tracker.observe(now, true, 1_000_000L, -1L, true),
        )
    }

    @Test
    fun advancingPresentationTimeIsNeverEvidenceOfAGreyScreen() {
        val tracker = tracker()
        tracker.reset()
        tracker.onEvent(PlaybackEvent.FirstFrame(4L), 0L)
        var pts = 1_000_000L
        repeat(40) {
            now += 1_000L
            pts += 40_000L
            assertNull(tracker.observe(now, true, pts, 100L + it, true))
        }
    }

    @Test
    fun resetForANewSourceForgetsEveryPreviousEvidence() {
        val tracker = tracker()
        tracker.reset()
        tracker.onEvent(PlaybackEvent.Ready(5L), 0L)
        now = 13_000L
        assertEquals(GreyScreenEvidence.NO_FIRST_FRAME, tracker.observe(now, false, -1L, -1L, true))

        tracker.reset()
        now += 1_000L
        assertNull(tracker.observe(now, false, -1L, -1L, true))
        // The new generation can report the same evidence again.
        now += 13_000L
        assertEquals(GreyScreenEvidence.NO_FIRST_FRAME, tracker.observe(now, false, -1L, -1L, true))
    }
}
