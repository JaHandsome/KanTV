package com.kantv.app.playback

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Analyzes a narrowly defined whole-second timestamp anomaly without changing the input.
 * Applying the experimental correction is deliberately a separate, explicit operation.
 */
internal object MpegTsAudioTimestampRepair {
    private const val WRAP = 1L shl 33
    private const val HZ = 90_000L
    data class Analysis(
        val wouldRepair: Boolean = false,
        val correctionSeconds: Long = 0,
        val headerCount: Int = 0,
    )
    data class Result(
        val bytes: ByteArray,
        val correctionSeconds: Long = 0,
        val correctedHeaders: Int = 0,
        val inputSource: TransportStreamInputSource = TransportStreamInputSource.ORIGINAL,
    )
    private data class AudioStamp(val position: Int, val dtsPosition: Int?, val pts: Long, val pcrDelta: Long)
    private data class Plan(val correctionSeconds: Long, val audio: List<AudioStamp>)

    fun analyze(bytes: ByteArray): Analysis {
        val plan = plan(bytes) ?: return Analysis()
        return Analysis(true, plan.correctionSeconds, plan.audio.size)
    }

    /** Experimental only. Production playback does not call this method. */
    fun apply(bytes: ByteArray): Result {
        val plan = plan(bytes) ?: return Result(bytes)
        val repaired = bytes.copyOf()
        plan.audio.forEach {
            writeStamp(repaired, it.position, readStamp(bytes, it.position) + plan.correctionSeconds * HZ)
            it.dtsPosition?.let { dts ->
                writeStamp(repaired, dts, readStamp(bytes, dts) + plan.correctionSeconds * HZ)
            }
        }
        return Result(
            repaired,
            plan.correctionSeconds,
            plan.audio.size,
            TransportStreamInputSource.REPAIRED_INPUT,
        )
    }

    private fun plan(bytes: ByteArray): Plan? {
        if (bytes.size % 188 != 0) return null
        val pcr = mutableMapOf<Int, Long>()
        val videoDeltas = mutableListOf<Long>()
        val audio = mutableListOf<AudioStamp>()
        val audioPids = mutableSetOf<Int>()
        val videoPids = mutableSetOf<Int>()
        var videoPid: Int? = null
        var firstVideoPts: Long? = null
        for (o in bytes.indices step 188) {
            fun b(i: Int) = bytes[i].toInt() and 255
            if (b(o) != 0x47 || b(o + 1) and 0x80 != 0 || b(o + 3) and 0xc0 != 0) return null
            val pid = ((b(o + 1) and 31) shl 8) or b(o + 2)
            val af = (b(o + 3) ushr 4) and 3
            var p = o + 4
            if (af == 2 || af == 3) {
                val size = b(p)
                if (p + size >= o + 188) return null
                if (size >= 7 && b(p + 1) and 0x10 != 0) {
                    val q = p + 2
                    pcr[pid] = (b(q).toLong() shl 25) or (b(q + 1).toLong() shl 17) or
                        (b(q + 2).toLong() shl 9) or (b(q + 3).toLong() shl 1) or (b(q + 4).toLong() ushr 7)
                }
                if (size > 0 && b(p + 1) and 0x80 != 0) return null
                p += size + 1
            }
            if ((af != 1 && af != 3) || b(o + 1) and 0x40 == 0 || p + 4 > o + 188) continue
            if (b(p) != 0 || b(p + 1) != 0 || b(p + 2) != 1) continue
            val sid = b(p + 3)
            if (sid == 0xbd) return null
            if (sid !in 0xc0..0xdf && sid !in 0xe0..0xef) continue
            if (p + 14 > o + 188) return null
            val flags = b(p + 7)
            if (flags and 0x80 == 0 || flags and 2 != 0 || b(p + 8) < 5) return null
            val position = p + 9
            if (!validStamp(bytes, position)) return null
            val pts = readStamp(bytes, position)
            if (sid in 0xe0..0xef) {
                videoPids += pid
                videoPid = pid
                if (firstVideoPts == null) firstVideoPts = pts
                pcr[pid]?.let { videoDeltas += delta(pts, it) }
            } else {
                audioPids += pid
                val clock = pcr[videoPid] ?: return null
                val dts = if (flags and 0x40 != 0) position + 5 else null
                if (dts != null && (p + 19 > o + 188 || b(p + 8) < 10 || !validStamp(bytes, dts))) return null
                audio += AudioStamp(position, dts, pts, delta(pts, clock))
            }
        }
        // Multi-program / multi-audio streams need independent clock attribution; leave them alone.
        if (videoPids.size != 1 || audioPids.size != 1 || videoDeltas.size < 8 || audio.size < 8) return null
        val videoMedian = videoDeltas.sorted()[videoDeltas.size / 2]
        if (abs(videoMedian) > 2 * HZ || videoDeltas.max() - videoDeltas.min() > HZ) return null
        val audioDeltas = audio.map { it.pcrDelta }
        if (audioDeltas.max() - audioDeltas.min() > HZ) return null
        val correction = (delta(firstVideoPts ?: return null, audio.first().pts).toDouble() / HZ).roundToLong()
        if (abs(correction) !in 30L..600L) return null
        if (abs(delta(firstVideoPts, audio.first().pts) - correction * HZ) > HZ / 4) return null
        if (audioDeltas.any { abs(it + correction * HZ - videoMedian) > HZ }) return null
        return Plan(correction, audio)
    }

    private fun delta(a: Long, b: Long) = ((a - b + WRAP / 2) and (WRAP - 1)) - WRAP / 2
    private fun validStamp(b: ByteArray, p: Int) = (b[p].toInt() and 1 == 1) && (b[p + 2].toInt() and 1 == 1) && (b[p + 4].toInt() and 1 == 1)
    private fun readStamp(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 14) shl 29) or ((b[p + 1].toLong() and 255) shl 22) or
            ((b[p + 2].toLong() and 254) shl 14) or ((b[p + 3].toLong() and 255) shl 7) or ((b[p + 4].toLong() and 255) ushr 1)
    private fun writeStamp(b: ByteArray, p: Int, value: Long) {
        val t = value and (WRAP - 1)
        b[p] = ((b[p].toInt() and 0xf1) or (((t ushr 30).toInt() and 7) shl 1)).toByte()
        b[p + 1] = (t ushr 22).toByte()
        b[p + 2] = ((((t ushr 15).toInt() and 127) shl 1) or 1).toByte()
        b[p + 3] = (t ushr 7).toByte()
        b[p + 4] = (((t.toInt() and 127) shl 1) or 1).toByte()
    }
}
