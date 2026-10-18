package com.kantv.app.playback

import org.junit.Assert.*
import org.junit.Test

class MpegTsAudioTimestampRepairTest {
    @Test fun repairsStableTwoMinuteAudioErrorAcrossTimestampWrap() {
        val source = timestampAnomalyStream(120 * 90_000L)
        val original = source.copyOf()
        val analysis = MpegTsAudioTimestampRepair.analyze(source)
        val result = MpegTsAudioTimestampRepair.apply(source)
        assertTrue(analysis.wouldRepair)
        assertEquals(120L, analysis.correctionSeconds)
        assertEquals(10, analysis.headerCount)
        assertEquals(120L, result.correctionSeconds)
        assertEquals(10, result.correctedHeaders)
        assertEquals(TransportStreamInputSource.REPAIRED_INPUT, result.inputSource)
        assertArrayEquals(original, source)
        assertArrayEquals(timestampAnomalyStream(0), result.bytes)
    }

    @Test fun ordinaryLipSyncOffsetsAreNeverRewritten() {
        listOf(0L, 18_900L, 90_000L, 29 * 90_000L).forEach { offset ->
            val source = timestampAnomalyStream(offset)
            assertFalse(MpegTsAudioTimestampRepair.analyze(source).wouldRepair)
            assertSame(source, MpegTsAudioTimestampRepair.apply(source).bytes)
        }
    }

    @Test fun inconsistentAudioClockAndTruncatedTransportAreNotGuessed() {
        val source = timestampAnomalyStream(120 * 90_000L)
        val mixed = source.copyOf()
        val normal = timestampAnomalyStream(0)
        normal.copyInto(mixed, 188, 188, 376)
        assertSame(mixed, MpegTsAudioTimestampRepair.apply(mixed).bytes)
        val truncated = source.copyOf(source.size - 1)
        assertSame(truncated, MpegTsAudioTimestampRepair.apply(truncated).bytes)
    }

    @Test fun scrambledAndPrivateStreamsRemainUntouched() {
        val scrambled = timestampAnomalyStream(120 * 90_000L).apply { this[3] = (this[3].toInt() or 0x80).toByte() }
        assertSame(scrambled, MpegTsAudioTimestampRepair.apply(scrambled).bytes)
        val privateStream = timestampAnomalyStream(120 * 90_000L).apply { this[188 + 7] = 0xbd.toByte() }
        assertSame(privateStream, MpegTsAudioTimestampRepair.apply(privateStream).bytes)
    }

    @Test fun rewritesAudioDtsAlongsidePtsWithoutChangingTheirDistance() {
        fun withDts(source: ByteArray): ByteArray = source.apply {
            for (o in 188 until size step 376) {
                this[o + 11] = 0xc0.toByte()
                this[o + 12] = 10
                copyInto(this, o + 18, o + 13, o + 18)
                this[o + 13] = (this[o + 13].toInt() or 0x10).toByte()
                this[o + 18] = (this[o + 18].toInt() and 0x1f).toByte()
            }
        }
        val result = MpegTsAudioTimestampRepair.apply(withDts(timestampAnomalyStream(120 * 90_000L)))
        assertEquals(120L, result.correctionSeconds)
        assertArrayEquals(withDts(timestampAnomalyStream(0)), result.bytes)
    }

    @Test fun multipleAudioPidsRemainUntouched() {
        val source = timestampAnomalyStream(120 * 90_000L).apply { this[188 + 2] = 2 }
        assertSame(source, MpegTsAudioTimestampRepair.apply(source).bytes)
    }

}

internal fun timestampAnomalyStream(audioLag: Long): ByteArray = (0 until 10).flatMap { index ->
    val time = 9_000_000L + index * 3_600
    timestampPacket(true, time).toList() + timestampPacket(false, time - audioLag).toList()
}.toByteArray()

private fun timestampPacket(video: Boolean, time: Long): ByteArray {
        val b = ByteArray(188) { 0xff.toByte() }
        b[0] = 0x47; b[1] = 0x41; b[2] = if (video) 0 else 1
        b[3] = if (video) 0x30 else 0x10
        var p = 4
        val t = time and ((1L shl 33) - 1)
        if (video) {
            b[4] = 7; b[5] = 0x10
            b[6] = (t ushr 25).toByte(); b[7] = (t ushr 17).toByte()
            b[8] = (t ushr 9).toByte(); b[9] = (t ushr 1).toByte()
            b[10] = (((t and 1).toInt() shl 7) or 0x7e).toByte(); b[11] = 0
            p = 12
        }
        b[p] = 0; b[p + 1] = 0; b[p + 2] = 1
        b[p + 3] = if (video) 0xe0.toByte() else 0xc0.toByte()
        b[p + 4] = 0; b[p + 5] = 0
        b[p + 6] = 0x80.toByte(); b[p + 7] = 0x80.toByte(); b[p + 8] = 5
        p += 9
        b[p] = (0x21 or (((t ushr 30).toInt() and 7) shl 1)).toByte()
        b[p + 1] = (t ushr 22).toByte()
        b[p + 2] = ((((t ushr 15).toInt() and 127) shl 1) or 1).toByte()
        b[p + 3] = (t ushr 7).toByte()
        b[p + 4] = (((t.toInt() and 127) shl 1) or 1).toByte()
    return b
}
