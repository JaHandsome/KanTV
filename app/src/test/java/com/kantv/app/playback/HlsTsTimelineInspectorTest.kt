package com.kantv.app.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsTsTimelineInspectorTest {
    @Test
    fun reportsLastMinusFirstForOneVideoAndAtMostTwoAudioPidsWithoutChangingBytes() {
        val first = 9_000_000L
        val bytes = packet(0x101, 0xe0, first) + packet(0x102, 0xe1, first) +
            packet(0x093, 0xc0, first - 32_400) + packet(0x094, 0xbd, first - 28_800) +
            packet(0x095, 0xc1, first) + packet(0x101, 0xe0, first + 446_400) +
            packet(0x093, 0xc0, first - 32_400 + 450_000) +
            packet(0x094, 0xbd, first - 28_800 + 441_000)
        val before = bytes.copyOf()

        val timeline = HlsTsTimelineInspector.inspect(bytes)

        assertEquals(HlsTsTimelineInspector.TrackSpan(0x101, 2, 4_960_000), timeline.video)
        assertEquals(
            listOf(
                HlsTsTimelineInspector.TrackSpan(0x093, 2, 5_000_000),
                HlsTsTimelineInspector.TrackSpan(0x094, 2, 4_900_000),
            ),
            timeline.audio,
        )
        assertEquals(2, timeline.ignoredTrackPackets)
        assertEquals(0, timeline.invalidPesHeaders)
        assertArrayEquals(before, bytes)
        assertFalse(timeline.logFields().contains(first.toString()))
        assertFalse(timeline.logFields().contains("pts="))
    }

    @Test
    fun handlesThirtyThreeBitWrapAndKeepsSignedRegressionVisible() {
        val wrap = 1L shl 33
        val wrapped = HlsTsTimelineInspector.inspect(
            packet(1, 0xe0, wrap - 90_000) + packet(1, 0xe0, 360_000),
        )
        val regressed = HlsTsTimelineInspector.inspect(
            packet(1, 0xe0, 450_000) + packet(1, 0xe0, 0),
        )

        assertEquals(5_000_000L, wrapped.video?.spanUs)
        assertEquals(-5_000_000L, regressed.video?.spanUs)
    }

    @Test
    fun validatesPtsDtsPrefixesAndMarkers() {
        val valid = packet(1, 0xe0, 90_000, withDts = true)
        val wrongPrefix = packet(1, 0xe0, 0).apply { this[13] = 0x11 }
        val wrongMarker = packet(1, 0xe0, 0).apply { this[15] = 0 }
        val wrongDts = packet(1, 0xe0, 0, withDts = true).apply { this[18] = 0x21 }
        val timeline = HlsTsTimelineInspector.inspect(valid + wrongPrefix + wrongMarker + wrongDts)

        assertEquals(1, timeline.video?.ptsCount)
        assertEquals(3, timeline.invalidPesHeaders)
    }

    @Test
    fun rejectsMalformedPesHeaderAndPacketLengthRatherThanReadingPaddingAsPts() {
        val reservedFlags = packet(1, 0xe0, 0).apply { this[11] = 0x40 }
        val badMpegHeader = packet(1, 0xe0, 0).apply { this[10] = 0 }
        val shortHeader = packet(1, 0xe0, 0).apply { this[12] = 4 }
        val overPacketHeader = packet(1, 0xe0, 0).apply {
            this[12] = 0xff.toByte()
            this[9] = 8
        }
        val shortPesLength = packet(1, 0xe0, 0).apply { this[9] = 7 }
        val timeline = HlsTsTimelineInspector.inspect(
            reservedFlags + badMpegHeader + shortHeader + overPacketHeader + shortPesLength,
        )

        assertNull(timeline.video)
        assertEquals(5, timeline.invalidPesHeaders)
    }

    @Test
    fun skipsInvalidTransportHeadersAndNonStartPayloads() {
        val badSync = packet(1, 0xe0, 0).apply { this[0] = 0 }
        val error = packet(1, 0xe0, 0).apply { this[1] = 0xc0.toByte() }
        val scrambled = packet(1, 0xe0, 0).apply { this[3] = 0x90.toByte() }
        val noControl = packet(1, 0xe0, 0).apply { this[3] = 0 }
        val badAdaptation = packet(1, 0xe0, 0).apply {
            this[3] = 0x30
            this[4] = 0xff.toByte()
        }
        val nonStart = packet(1, 0xe0, 0).apply { this[1] = 0 }
        val timeline = HlsTsTimelineInspector.inspect(
            badSync + error + scrambled + noControl + badAdaptation + nonStart,
        )

        assertNull(timeline.video)
        assertEquals(5, timeline.invalidPackets)
    }

    @Test
    fun acceptsPayloadAfterAdaptationAndHandlesEmptyAndTruncatedInput() {
        val adapted = packet(1, 0xe0, 90_000).let { original ->
            original.copyOf().apply {
                original.copyInto(this, 7, 4, 4 + 14)
                this[3] = 0x30
                this[4] = 2
                this[5] = 0
                this[6] = 0
            }
        }
        val timeline = HlsTsTimelineInspector.inspect(adapted + ByteArray(37))

        assertEquals(1, timeline.video?.ptsCount)
        assertEquals(37, timeline.trailingBytes)
        assertEquals(0, HlsTsTimelineInspector.inspect(ByteArray(0)).scannedPackets)
        assertEquals(187, HlsTsTimelineInspector.inspect(ByteArray(187)).trailingBytes)
    }

    @Test
    fun reportsSplitTimestampAsMissingButAcceptsIntactPtsWhenOptionalHeaderContinues() {
        val optionalContinuation = packet(1, 0xe0, 90_000).apply { this[12] = 0xff.toByte() }
        val split = ByteArray(188).apply {
            this[0] = 0x47
            this[1] = 0x40
            this[2] = 1
            this[3] = 0x30
            this[4] = 170.toByte()
            packet(1, 0xe0, 0).copyInto(this, 175, 4, 17)
        }
        val timeline = HlsTsTimelineInspector.inspect(optionalContinuation + split)

        assertEquals(1, timeline.video?.ptsCount)
        assertEquals(1, timeline.splitPesHeaders)
        assertEquals(0, timeline.invalidPesHeaders)
    }

    @Test
    fun scansExactlyTheFixedLimitAndDoesNotReadTheNextPacket() {
        val cap = 65_536
        val bytes = ByteArray((cap + 1) * 188)
        for (index in 0..cap) {
            bytes[index * 188] = 0x47
            bytes[index * 188 + 3] = 0x10
        }
        packet(1, 0xe0, 0).copyInto(bytes)
        packet(1, 0xe0, 450_000).copyInto(bytes, (cap - 1) * 188)
        packet(1, 0xe0, 900_000).copyInto(bytes, cap * 188)

        val bounded = HlsTsTimelineInspector.inspect(bytes)
        val exact = HlsTsTimelineInspector.inspect(bytes.copyOf(cap * 188))

        assertEquals(cap, bounded.scannedPackets)
        assertTrue(bounded.bounded)
        assertFalse(exact.bounded)
        assertEquals(2, bounded.video?.ptsCount)
        assertEquals(5_000_000L, bounded.video?.spanUs)
        assertEquals(bounded.video, exact.video)
    }

    @Test
    fun keepsFirstCompletedAnchorWhileSegmentsArriveOutOfSequenceAcrossWrap() {
        val anchors = HlsTsTimelineInspector.Anchors()
        val wrap = 1L shl 33
        fun observe(sequence: Long, pts: Long) = HlsTsTimelineInspector.inspect(packet(1, 0xe0, pts))
            .measureAnchor(anchors, 10, sequence, 3)

        val firstCompleted = observe(2, 360_000)
        val earlierSequence = observe(1, wrap - 90_000)
        val laterSequence = observe(3, 810_000)

        assertEquals(0L, firstCompleted.firstRelativeUs)
        assertEquals(-5_000_000L, earlierSequence.firstRelativeUs)
        assertEquals(5_000_000L, laterSequence.firstRelativeUs)
        assertEquals(2L, earlierSequence.anchorRelativeSequence)
        assertEquals(firstCompleted.epoch, earlierSequence.epoch)
        assertEquals(firstCompleted.epoch, laterSequence.epoch)
    }

    @Test
    fun isolatesPidDiscontinuityAndGenerationAndBoundsAnchorEpochs() {
        val anchors = HlsTsTimelineInspector.Anchors()
        fun observe(generation: Long, pid: Int, disc: Long, pts: Long) =
            HlsTsTimelineInspector.inspect(packet(pid, 0xe0, pts)).measureAnchor(anchors, generation, 1, disc)

        assertEquals(0, observe(10, 1, 0, 90_000).epoch)
        assertEquals(1, observe(10, 2, 0, 90_000).epoch)
        assertEquals(2, observe(10, 1, 1, 90_000).epoch)
        for (disc in 2L..6L) assertTrue(observe(10, 1, disc, 90_000).available)
        assertFalse(observe(10, 1, 7, 90_000).available)
        assertEquals(5_000_000L, observe(10, 1, 0, 540_000).firstRelativeUs)
        assertEquals(0L, observe(11, 1, 0, 540_000).firstRelativeUs)
        assertEquals(0, observe(11, 1, 0, 990_000).epoch)
        anchors.reset()
        assertEquals(0L, observe(11, 1, 0, 990_000).firstRelativeUs)
        assertFalse(HlsTsTimelineInspector.inspect(ByteArray(0)).measureAnchor(anchors, 11, 2, 0).available)
    }

    private fun packet(pid: Int, streamId: Int, pts: Long, withDts: Boolean = false): ByteArray {
        val bytes = ByteArray(188) { 0xff.toByte() }
        bytes[0] = 0x47
        bytes[1] = (0x40 or (pid ushr 8)).toByte()
        bytes[2] = pid.toByte()
        bytes[3] = 0x10
        bytes[4] = 0
        bytes[5] = 0
        bytes[6] = 1
        bytes[7] = streamId.toByte()
        bytes[8] = 0
        bytes[9] = 0
        bytes[10] = 0x80.toByte()
        bytes[11] = (if (withDts) 0xc0 else 0x80).toByte()
        bytes[12] = (if (withDts) 10 else 5).toByte()
        writeStamp(bytes, 13, pts, if (withDts) 3 else 2)
        if (withDts) writeStamp(bytes, 18, pts - 3_600, 1)
        return bytes
    }

    private fun writeStamp(bytes: ByteArray, position: Int, pts: Long, prefix: Int) {
        val wrapped = pts and ((1L shl 33) - 1)
        bytes[position] = ((prefix shl 4) or 1 or (((wrapped ushr 30).toInt() and 7) shl 1)).toByte()
        bytes[position + 1] = (wrapped ushr 22).toByte()
        bytes[position + 2] = ((((wrapped ushr 15).toInt() and 127) shl 1) or 1).toByte()
        bytes[position + 3] = (wrapped ushr 7).toByte()
        bytes[position + 4] = (((wrapped.toInt() and 127) shl 1) or 1).toByte()
    }
}
