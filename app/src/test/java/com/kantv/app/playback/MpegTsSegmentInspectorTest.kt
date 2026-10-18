package com.kantv.app.playback

import org.junit.Assert.assertTrue
import org.junit.Test

class MpegTsSegmentInspectorTest {
    @Test
    fun detectsACompleteTsDecoderBootstrapWithoutRetainingPayload() {
        val pat = packet(pid = 0, payload = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xe1.toByte(), 0,
        ))
        val pmt = packet(pid = 0x100, payload = byteArrayOf(
            0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0x1b, 0xe1.toByte(), 1, 0, 0,
        ))
        val pcr = packet(pid = 0x101, payload = byteArrayOf(0), pcr = true)
        val video = packet(
            pid = 0x101,
            payload = byteArrayOf(0, 0, 1, 7, 0, 0, 1, 8, 0, 0, 1, 5),
        )

        val result = MpegTsSegmentInspector.inspect(pat + pmt + pcr + video)

        assertTrue(result.pat)
        assertTrue(result.pmt)
        assertTrue(result.pcr)
        assertTrue(result.h264)
        assertTrue(result.sps)
        assertTrue(result.pps)
        assertTrue(result.idr)
        assertTrue(result.independentlyDecodable)
    }

    private fun packet(pid: Int, payload: ByteArray, pcr: Boolean = false): ByteArray {
        val packet = ByteArray(188) { 0xff.toByte() }
        packet[0] = 0x47
        packet[1] = (0x40 or (pid ushr 8)).toByte()
        packet[2] = pid.toByte()
        packet[3] = if (pcr) 0x30 else 0x10
        var offset = 4
        if (pcr) {
            packet[offset++] = 7
            packet[offset++] = 0x10
            repeat(6) { packet[offset++] = 0 }
        }
        payload.copyInto(packet, offset, 0, minOf(payload.size, packet.size - offset))
        return packet
    }
}
