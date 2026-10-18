package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picture-motion judge is the only place in the app that can tell "decoding but the picture
 * never changes" from healthy playback, so its rules are pinned here rather than only observed on a
 * device.
 */
class PictureMotionTrackerTest {
    private val tracker = PictureMotionTracker()

    private fun luma(value: Double, size: Int = 16) = DoubleArray(size) { value }

    @Test
    fun firstJudgeableSampleEstablishesBaselineAndCountsAsVerified() {
        val observation = tracker.onSample(nowMs = 0L, sourceGeneration = 1L, signature = luma(120.0), captureMs = 12L)
        assertEquals(PictureMotion.MOVING, observation.motion)
        assertEquals(0L, observation.millisSincePictureVerified)
        assertEquals(12L, observation.captureMs)
    }

    @Test
    fun unchangedPictureReportsStillAndGrowsTheElapsedCount() {
        tracker.onSample(0L, 1L, luma(120.0), 10L)
        val first = tracker.onSample(6_000L, 1L, luma(120.0), 10L)
        val second = tracker.onSample(12_000L, 1L, luma(120.0), 10L)
        assertEquals(PictureMotion.STILL, first.motion)
        assertEquals(6_000L, first.millisSincePictureVerified)
        assertEquals(PictureMotion.STILL, second.motion)
        assertEquals(12_000L, second.millisSincePictureVerified)
    }

    @Test
    fun changedPictureReturnsToMovingAndResetsTheElapsedCount() {
        tracker.onSample(0L, 1L, luma(120.0), 10L)
        tracker.onSample(6_000L, 1L, luma(120.0), 10L)
        val moved = tracker.onSample(12_000L, 1L, luma(200.0), 10L)
        assertEquals(PictureMotion.MOVING, moved.motion)
        assertEquals(0L, moved.millisSincePictureVerified)
    }

    @Test
    fun differenceAtTheBoundaryCountsAsMotionBecauseTheComparisonIsInclusive() {
        // 0.5 and 100.5 are exactly representable, so this pins ">=" rather than ">" without
        // depending on how a decimal literal happens to round.
        val boundary = PictureMotionTracker(movingDifference = 0.5)
        boundary.onSample(0L, 1L, luma(100.0), 10L)
        assertEquals(PictureMotion.MOVING, boundary.onSample(6_000L, 1L, luma(100.5), 10L).motion)
    }

    @Test
    fun differenceBelowTheBoundaryCountsAsStill() {
        val below = PictureMotionTracker(movingDifference = 0.5)
        below.onSample(0L, 1L, luma(100.0), 10L)
        assertEquals(PictureMotion.STILL, below.onSample(6_000L, 1L, luma(100.25), 10L).motion)
    }

    @Test
    fun defaultBoundaryStaysIdenticalToTheDebugProbeMeasure() {
        // The probe calls a picture static when its frame difference is below this value; keeping the
        // constants equal is what makes probe measurements and this judge agree about one source.
        assertEquals(0.8, PictureMotionTracker.MOVING_DIFFERENCE, 0.0)
        assertEquals(3.0, PictureMotionTracker.BLACK_LUMA, 0.0)
    }

    @Test
    fun darkFrameIsNeverReportedAsStillness() {
        tracker.onSample(0L, 1L, luma(120.0), 10L)
        val dark = tracker.onSample(6_000L, 1L, luma(1.0), 10L)
        assertEquals(PictureMotion.UNVERIFIABLE, dark.motion)
        assertTrue(dark.motion != PictureMotion.STILL)
    }

    @Test
    fun failedCaptureIsUnavailableAndDoesNotAdvanceTheVerifiedInstant() {
        tracker.onSample(0L, 1L, luma(120.0), 10L)
        val missing = tracker.onSample(6_000L, 1L, null, -2L)
        assertEquals(PictureMotion.UNAVAILABLE, missing.motion)
        assertEquals(6_000L, missing.millisSincePictureVerified)
        assertEquals(-2L, missing.captureMs)
    }

    @Test
    fun notObservedShiftsTheVerifiedInstantSoACoveringMenuIsNotStillness() {
        tracker.onSample(0L, 1L, luma(120.0), 10L)
        // An overlay covered the picture for 60 s; that interval was never judged.
        tracker.onNotObserved(60_000L)
        val afterOverlay = tracker.onSample(66_000L, 1L, luma(120.0), 10L)
        assertEquals(PictureMotion.STILL, afterOverlay.motion)
        assertEquals(6_000L, afterOverlay.millisSincePictureVerified)
    }

    @Test
    fun generationChangeStartsOverSoNoVerdictStraddlesTwoCandidates() {
        tracker.onSample(0L, 1L, luma(120.0), 10L)
        tracker.onSample(6_000L, 1L, luma(120.0), 10L)
        val nextSource = tracker.onSample(12_000L, 2L, luma(120.0), 10L)
        assertEquals(PictureMotion.MOVING, nextSource.motion)
        assertEquals(0L, nextSource.millisSincePictureVerified)
    }

    @Test
    fun signatureSizeChangeIsTreatedAsANewBaseline() {
        tracker.onSample(0L, 1L, luma(120.0, size = 16), 10L)
        val resized = tracker.onSample(6_000L, 1L, luma(120.0, size = 8), 10L)
        assertEquals(PictureMotion.MOVING, resized.motion)
    }

    @Test
    fun resetForgetsEverythingIncludingTheVerifiedInstant() {
        tracker.onSample(0L, 1L, luma(120.0), 10L)
        tracker.reset()
        val afterReset = tracker.onSample(9_000L, 1L, luma(120.0), 10L)
        assertEquals(PictureMotion.MOVING, afterReset.motion)
        assertEquals(0L, afterReset.millisSincePictureVerified)
    }

    @Test
    fun meanAbsoluteDifferenceIsZeroForIdenticalSignaturesAndSizedForMismatch() {
        assertEquals(0.0, PictureMotionTracker.meanAbsoluteDifference(luma(50.0), luma(50.0)), 0.0001)
        assertEquals(0.0, PictureMotionTracker.meanAbsoluteDifference(luma(50.0, 4), luma(50.0, 8)), 0.0001)
        assertEquals(10.0, PictureMotionTracker.meanAbsoluteDifference(luma(50.0), luma(60.0)), 0.0001)
    }

    @Test
    fun neverVerifiedPictureReportsTheSentinelRatherThanZero() {
        val empty = PictureMotionTracker()
        val unavailable = empty.onSample(1_000L, 1L, null, -2L)
        assertEquals(PictureMotion.UNAVAILABLE, unavailable.motion)
        assertEquals(-1L, unavailable.millisSincePictureVerified)
    }
}
