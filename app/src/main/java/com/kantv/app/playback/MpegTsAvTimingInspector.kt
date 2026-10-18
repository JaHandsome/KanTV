package com.kantv.app.playback

/**
 * Reads only first PES timestamps from one MPEG-TS segment. The result contains bounded deltas and
 * PIDs, never source bytes, URLs, headers, or raw timestamps.
 */
internal object MpegTsAvTimingInspector {
    private const val PACKET_SIZE = 188
    private const val WRAP = 1L shl 33
    private const val CLOCK_HZ = 90_000L

    data class Timing(
        val firstAudioPid: Int,
        val firstAudioMinusVideoMs: Int,
        val firstAudioMinusVideoUs: Long = firstAudioMinusVideoMs * 1_000L,
        val secondAudioPid: Int = -1,
        val secondAudioMinusVideoMs: Int = 0,
        val secondAudioMinusVideoUs: Long = secondAudioMinusVideoMs * 1_000L,
    )

    fun inspect(bytes: ByteArray): Timing? {
        if (bytes.size < PACKET_SIZE || bytes.size % PACKET_SIZE != 0) return null
        var videoPts: Long? = null
        val audioPts = linkedMapOf<Int, Long>()
        for (offset in bytes.indices step PACKET_SIZE) {
            fun byte(index: Int): Int = bytes[index].toInt() and 0xff
            if (byte(offset) != 0x47 || byte(offset + 1) and 0x80 != 0) return null
            if (byte(offset + 1) and 0x40 == 0) continue
            val pid = ((byte(offset + 1) and 0x1f) shl 8) or byte(offset + 2)
            val adaptation = (byte(offset + 3) ushr 4) and 0x03
            var payload = offset + 4
            if (adaptation == 2 || adaptation == 3) {
                val length = byte(payload)
                if (payload + length >= offset + PACKET_SIZE) continue
                payload += length + 1
            }
            if (adaptation != 1 && adaptation != 3 || payload + 14 > offset + PACKET_SIZE) continue
            if (byte(payload) != 0 || byte(payload + 1) != 0 || byte(payload + 2) != 1) continue
            val streamId = byte(payload + 3)
            val isVideo = streamId in 0xe0..0xef
            val isAudio = streamId in 0xc0..0xdf || streamId == 0xbd
            if (!isVideo && !isAudio) continue
            val flags = byte(payload + 7)
            if (flags and 0x80 == 0 || byte(payload + 8) < 5) continue
            val stamp = payload + 9
            if (!validStamp(bytes, stamp)) continue
            val pts = readStamp(bytes, stamp)
            if (isVideo && videoPts == null) videoPts = pts
            if (isAudio && pid !in audioPts) audioPts[pid] = pts
        }
        val video = videoPts ?: return null
        val tracks = audioPts.entries.sortedBy { it.key }.take(2)
        val first = tracks.firstOrNull() ?: return null
        val second = tracks.getOrNull(1)
        return Timing(
            firstAudioPid = first.key,
            firstAudioMinusVideoMs = deltaMs(first.value, video),
            firstAudioMinusVideoUs = deltaUs(first.value, video),
            secondAudioPid = second?.key ?: -1,
            secondAudioMinusVideoMs = second?.let { deltaMs(it.value, video) } ?: 0,
            secondAudioMinusVideoUs = second?.let { deltaUs(it.value, video) } ?: 0,
        )
    }

    private fun deltaMs(value: Long, reference: Long): Int {
        return (deltaTicks(value, reference) * 1_000 / CLOCK_HZ)
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun deltaUs(value: Long, reference: Long): Long =
        deltaTicks(value, reference) * 1_000_000L / CLOCK_HZ

    private fun deltaTicks(value: Long, reference: Long): Long =
        ((value - reference + WRAP / 2) and (WRAP - 1)) - WRAP / 2

    private fun validStamp(bytes: ByteArray, position: Int): Boolean =
        position + 4 < bytes.size && bytes[position].toInt() and 1 == 1 &&
            bytes[position + 2].toInt() and 1 == 1 && bytes[position + 4].toInt() and 1 == 1

    private fun readStamp(bytes: ByteArray, position: Int): Long =
        ((bytes[position].toLong() and 14) shl 29) or
            ((bytes[position + 1].toLong() and 255) shl 22) or
            ((bytes[position + 2].toLong() and 254) shl 14) or
            ((bytes[position + 3].toLong() and 255) shl 7) or
            ((bytes[position + 4].toLong() and 255) ushr 1)
}
