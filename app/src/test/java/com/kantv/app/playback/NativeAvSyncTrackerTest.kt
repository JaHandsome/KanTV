package com.kantv.app.playback

import com.kantv.app.diagnostics.NativeDiagnosticMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeAvSyncTrackerTest {
    private val nowNs = 5_000_000_000L

    private fun tracker(offset: Long = 1_000_000_000_000L) = NativeAvSyncTracker().apply {
        playing(true)
        period("period-a", 1)
        audioOffset(offset)
    }

    @Test
    fun realOffsetNormalizesLargeRendererPtsAndKeepsNonzeroMediaStart() {
        val tracker = tracker()
        tracker.audio(1_000_079_999_999, true, nowNs)
        tracker.video(80_000_000, nowNs + 30_000_000, nowNs + 10_000_000)
        val value = NativeDiagnosticMapper.nativeMeasurement(tracker.snapshot(4), nowNs + 500_000_000)
        assertEquals("AVAILABLE", value.nativeAudioClockStatus)
        assertEquals(79_999_999L, value.nativeAudioClockUs)
        assertEquals(-1L, value.nativeAudioMinusVideoUs)
        assertEquals(80_000_000L, value.nativePairedVideoPtsUs)
        assertEquals(10_000L, value.nativeAvPairSpanUs)
        assertEquals(20_000L, value.nativeVideoReleaseDelayUs)
        assertEquals("UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED", value.avSyncAssessment)
    }

    @Test
    fun signedOffsetsAndZeroClockAreLegalWithoutAssumingOneTrillion() {
        val tracker = tracker(-40_000)
        tracker.audio(10_000, true, nowNs)
        tracker.video(50_000, nowNs, nowNs)
        assertEquals(50_000L, tracker.snapshot(1).audioClockUs)
        tracker.reset(clearOffsets = true)
        tracker.period("period-b", 1)
        tracker.audioOffset(0)
        tracker.audio(0, true, nowNs)
        tracker.video(0, nowNs, nowNs)
        assertEquals("AVAILABLE", tracker.snapshot(1).status)
    }

    @Test
    fun normalFrameCadencePairsRawSinkCursorWithoutExtrapolation() {
        val tracker = tracker(0)
        repeat(100) { index ->
            val audioAt = nowNs + index * 40_000_000L
            tracker.audio(index * 40_000L, true, audioAt)
            tracker.video(index * 40_000L + 20_000, audioAt + 30_000_000, audioAt + 10_000_000)
            assertEquals(-20_000L, tracker.snapshot(1).audioClockUs - tracker.snapshot(1).videoPtsUs)
        }
    }

    @Test
    fun pausedBufferingAndSeekRequireNewSamplesButPreserveEffectiveOffset() {
        val tracker = tracker(500_000)
        tracker.audio(600_000, true, nowNs)
        tracker.video(100_000, nowNs, nowNs)
        tracker.playing(false)
        assertEquals("INSUFFICIENT_NOT_PLAYING", tracker.snapshot(1).status)
        tracker.playing(true)
        assertEquals("INSUFFICIENT_INVALIDATED", tracker.snapshot(1).status)
        tracker.audio(900_000, true, nowNs)
        tracker.video(400_000, nowNs, nowNs)
        assertEquals("AVAILABLE", tracker.snapshot(1).status)
        tracker.reset() // same-period seek, sink flush or reported discontinuity
        tracker.video(2_000_000, nowNs, nowNs)
        assertEquals("INSUFFICIENT_NO_SAMPLE", tracker.snapshot(1).status)
        tracker.audio(2_500_000, true, nowNs)
        assertEquals("AVAILABLE", tracker.snapshot(1).status)
    }

    @Test
    fun unknownAndStaleCannotKeepMeasuredNumericsOrTurnIntoZero() {
        val tracker = tracker(0)
        tracker.audio(100, true, nowNs)
        tracker.video(101, nowNs, nowNs)
        val stale = NativeDiagnosticMapper.nativeMeasurement(tracker.snapshot(1), nowNs + 1_500_000_001)
        assertEquals("INSUFFICIENT_STALE_SAMPLE", stale.nativeAudioClockStatus)
        assertEquals(-1L, stale.nativeAudioClockUs)
        assertEquals(-1L, stale.nativePairedVideoPtsUs)
        tracker.audio(-1, false, nowNs)
        assertEquals("INSUFFICIENT_INVALID_CLOCK", tracker.snapshot(1).status)
        tracker.audio(100, true, nowNs)
        tracker.video(101, nowNs + 100_001_000, nowNs + 100_001_000)
        assertEquals("INSUFFICIENT_PAIR_STALE", tracker.snapshot(1).status)
    }

    @Test
    fun changedOrMultiplePeriodsStayUnknownUntilExplicitNewGeneration() {
        val tracker = tracker(0)
        tracker.period("period-b", 1)
        tracker.period("period-a", 1)
        tracker.audio(100, true, nowNs)
        tracker.video(100, nowNs, nowNs)
        assertEquals("INSUFFICIENT_STREAM_MISMATCH", tracker.snapshot(1).status)
        tracker.reset(clearOffsets = true)
        tracker.period(null, 0)
        tracker.period("period-c", 1)
        tracker.audioOffset(0)
        tracker.audio(100, true, nowNs)
        tracker.video(100, nowNs, nowNs)
        assertEquals("AVAILABLE", tracker.snapshot(2).status)
        tracker.period(null, 0)
        tracker.audio(100, true, nowNs)
        tracker.video(100, nowNs, nowNs)
        assertEquals("INSUFFICIENT_STREAM_MISMATCH", tracker.snapshot(2).status)
        tracker.period("period-c", 1)
        tracker.audio(100, true, nowNs)
        tracker.video(100, nowNs, nowNs)
        assertEquals("AVAILABLE", tracker.snapshot(2).status)
        tracker.period(null, 2)
        tracker.period("period-c", 1)
        tracker.audio(100, true, nowNs)
        tracker.video(100, nowNs, nowNs)
        assertEquals("INSUFFICIENT_STREAM_MISMATCH", tracker.snapshot(2).status)
    }

    @Test
    fun changedEffectiveOffsetCannotTemporarilyPairWithOldVideoOrigin() {
        val tracker = tracker(0)
        tracker.audio(100, true, nowNs)
        tracker.video(100, nowNs, nowNs)
        tracker.audioOffset(null)
        tracker.audioOffset(500_000)
        tracker.audio(500_100, true, nowNs)
        tracker.video(100, nowNs, nowNs)
        assertEquals("INSUFFICIENT_STREAM_MISMATCH", tracker.snapshot(1).status)
    }

    @Test
    fun sourceReplacementDropsOffsetAndFixedPair() {
        val tracker = tracker(0)
        tracker.audio(100, true, nowNs)
        tracker.video(101, nowNs, nowNs)
        val old = tracker.snapshot(1)
        tracker.reset(clearOffsets = true)
        tracker.period("new-period", 1)
        tracker.video(999, nowNs, nowNs)
        assertEquals("INSUFFICIENT_NO_OFFSET", tracker.snapshot(2).status)
        assertEquals(101L, old.videoPtsUs)
        assertEquals(1L, old.generation)
        tracker.audioOffset(0)
        tracker.audio(998, true, nowNs)
        assertEquals("INSUFFICIENT_NO_SAMPLE", tracker.snapshot(2).status)
        tracker.video(999, nowNs, nowNs)
        assertEquals("AVAILABLE", tracker.snapshot(2).status)
    }
}
