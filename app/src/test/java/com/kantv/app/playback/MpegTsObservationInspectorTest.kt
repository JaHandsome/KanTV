package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpegTsObservationInspectorTest {
    @Test
    fun singlePidContinuousTimelineHasWrapSafeMetrics() {
        val wrap = 1L shl 33
        val input = pcrPacket(wrap - 45_000) + pcrPacket(45_000)

        val result = MpegTsObservationInspector.inspect(
            input,
            hlsDiscontinuity = true,
            hlsDiscontinuitySequence = 8,
        )

        assertEquals(TransportStreamInputSource.ORIGINAL, result.inputSource)
        assertTrue(result.pcrAvailable)
        assertEquals(256, result.pcrPid)
        assertEquals(1_000_000L, result.pcrSpanUs)
        assertEquals(1_000_000L, result.pcrMaxJumpUs)
        assertEquals(0, result.pcrBackwardJumpCount)
        assertEquals(0, result.adaptationDiscontinuityCount)
        assertTrue(result.hlsDiscontinuity)
        assertEquals(8L, result.hlsDiscontinuitySequence)
    }

    @Test
    fun interleavedPcrPidsKeepTimelineMetricsUnknown() {
        val input = pcrPacket(0, pid = 256) +
            pcrPacket(100, pid = 257) +
            pcrPacket(90_000, pid = 256) +
            pcrPacket(90_100, pid = 257)

        val result = MpegTsObservationInspector.inspect(input)

        assertTrue(result.pcrAvailable)
        assertEquals(-1, result.pcrPid)
        assertEquals(-1L, result.pcrSpanUs)
        assertEquals(-1L, result.pcrMaxJumpUs)
        assertEquals(-1, result.pcrBackwardJumpCount)
    }

    @Test
    fun discontinuityOnSelectedPidSeparatesPcrEpochs() {
        val input = pcrPacket(0, pid = 256) +
            pcrPacket(90_000, pid = 256, discontinuity = true) +
            pcrPacket(180_000, pid = 256)

        val result = MpegTsObservationInspector.inspect(input)

        assertTrue(result.pcrAvailable)
        assertEquals(256, result.pcrPid)
        assertEquals(-1L, result.pcrSpanUs)
        assertEquals(-1L, result.pcrMaxJumpUs)
        assertEquals(-1, result.pcrBackwardJumpCount)
        assertEquals(1, result.adaptationDiscontinuityCount)
    }

    @Test
    fun missingPcrAndPtsRemainExplicitlyUnavailable() {
        val packet = ByteArray(188) { 0xff.toByte() }.apply {
            this[0] = 0x47
            this[3] = 0x10
        }

        val result = MpegTsObservationInspector.inspect(packet)

        assertFalse(result.pcrAvailable)
        assertEquals(-1, result.pcrPid)
        assertEquals(-1L, result.pcrSpanUs)
        assertEquals(-1L, result.pcrMaxJumpUs)
        assertEquals(-1, result.pcrBackwardJumpCount)
        assertEquals(null, result.firstAudioMinusVideoUs)
    }

    @Test
    fun repairDecisionIsReportedWithoutChangingOriginalInput() {
        val input = timestampAnomalyStream(120 * 90_000L)
        val before = input.copyOf()

        val result = MpegTsObservationInspector.inspect(input)

        assertTrue(result.wouldRepair)
        assertEquals(120L, result.repairCorrectionSeconds)
        assertEquals(10, result.repairHeaderCount)
        assertTrue(before.contentEquals(input))
    }

    private fun pcrPacket(value: Long, pid: Int = 256, discontinuity: Boolean = false): ByteArray {
        require(pid in 0..0x1fff)
        val t = value and ((1L shl 33) - 1)
        return ByteArray(188) { 0xff.toByte() }.apply {
            this[0] = 0x47
            this[1] = ((pid ushr 8) and 0x1f).toByte()
            this[2] = pid.toByte()
            this[3] = 0x20
            this[4] = 7
            this[5] = (0x10 or if (discontinuity) 0x80 else 0).toByte()
            this[6] = (t ushr 25).toByte()
            this[7] = (t ushr 17).toByte()
            this[8] = (t ushr 9).toByte()
            this[9] = (t ushr 1).toByte()
            this[10] = (((t and 1).toInt() shl 7) or 0x7e).toByte()
            this[11] = 0
        }
    }
}
