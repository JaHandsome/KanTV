package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpegTsAvTimingInspectorTest {
    @Test
    fun measuresBothAudioTracksAgainstVideoWithoutRetainingTimestamps() {
        val videoPts = 9_000_000L
        val bytes = packet(0x101, 0xe0, videoPts) +
            packet(0x093, 0xc0, videoPts - 32_400) +
            packet(0x094, 0xbd, videoPts - 28_800)

        val timing = requireNotNull(MpegTsAvTimingInspector.inspect(bytes))

        assertEquals(0x093, timing.firstAudioPid)
        assertEquals(-360, timing.firstAudioMinusVideoMs)
        assertEquals(0x094, timing.secondAudioPid)
        assertEquals(-320, timing.secondAudioMinusVideoMs)
    }

    @Test
    fun rejectsNonTransportPayload() {
        assertNull(MpegTsAvTimingInspector.inspect(ByteArray(188)))
    }

    private fun packet(pid: Int, streamId: Int, pts: Long): ByteArray {
        val bytes = ByteArray(188) { 0xff.toByte() }
        bytes[0] = 0x47
        bytes[1] = (0x40 or (pid ushr 8)).toByte()
        bytes[2] = pid.toByte()
        bytes[3] = 0x10
        var offset = 4
        bytes[offset++] = 0
        bytes[offset++] = 0
        bytes[offset++] = 1
        bytes[offset++] = streamId.toByte()
        bytes[offset++] = 0
        bytes[offset++] = 0
        bytes[offset++] = 0x80.toByte()
        bytes[offset++] = 0x80.toByte()
        bytes[offset++] = 5
        val wrapped = pts and ((1L shl 33) - 1)
        bytes[offset] = (0x21 or (((wrapped ushr 30).toInt() and 7) shl 1)).toByte()
        bytes[offset + 1] = (wrapped ushr 22).toByte()
        bytes[offset + 2] = ((((wrapped ushr 15).toInt() and 127) shl 1) or 1).toByte()
        bytes[offset + 3] = (wrapped ushr 7).toByte()
        bytes[offset + 4] = (((wrapped.toInt() and 127) shl 1) or 1).toByte()
        return bytes
    }
}
