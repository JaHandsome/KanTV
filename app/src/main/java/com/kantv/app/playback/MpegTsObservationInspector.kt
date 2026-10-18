package com.kantv.app.playback

import kotlin.math.abs

/** Bounded facts from one original MPEG-TS segment; no payload or absolute clock is retained. */
internal object MpegTsObservationInspector {
    private const val PACKET_SIZE = 188
    private const val PCR_WRAP = 1L shl 33
    private const val PCR_HZ = 90_000L

    fun inspect(
        bytes: ByteArray,
        hlsDiscontinuity: Boolean = false,
        hlsDiscontinuitySequence: Long = -1,
    ): TransportStreamObservation {
        val repair = MpegTsAudioTimestampRepair.analyze(bytes)
        val av = MpegTsAvTimingInspector.inspect(bytes)
        if (bytes.size < PACKET_SIZE || bytes.size % PACKET_SIZE != 0) {
            return TransportStreamObservation(
                inputSource = TransportStreamInputSource.ORIGINAL,
                hlsDiscontinuity = hlsDiscontinuity,
                hlsDiscontinuitySequence = hlsDiscontinuitySequence,
                wouldRepair = repair.wouldRepair,
                repairCorrectionSeconds = repair.correctionSeconds,
                repairHeaderCount = repair.headerCount,
                firstAudioMinusVideoUs = av?.firstAudioMinusVideoUs,
            )
        }
        var pcrAvailable = false
        var selectedPcrPid = -1
        var multiplePcrPids = false
        var selectedPidEpoch = 0
        var firstPcr = -1L
        var previousPcr = -1L
        var lastPcr = -1L
        var pcrCount = 0
        var pcrMaxJumpUs = -1L
        var pcrBackwardJumpCount = 0
        var multiplePcrEpochs = false
        var adaptationDiscontinuities = 0
        for (offset in bytes.indices step PACKET_SIZE) {
            fun byte(index: Int): Int = bytes[index].toInt() and 0xff
            if (byte(offset) != 0x47) continue
            val adaptation = (byte(offset + 3) ushr 4) and 3
            if (adaptation != 2 && adaptation != 3) continue
            val pid = ((byte(offset + 1) and 0x1f) shl 8) or byte(offset + 2)
            val length = byte(offset + 4)
            if (length < 1 || offset + 4 + length >= offset + PACKET_SIZE) continue
            val flags = byte(offset + 5)
            if (flags and 0x80 != 0) {
                adaptationDiscontinuities++
                if (pid == selectedPcrPid && pcrCount > 0) selectedPidEpoch++
            }
            if (flags and 0x10 != 0 && length >= 7) {
                val p = offset + 6
                val value = (byte(p).toLong() shl 25) or
                    (byte(p + 1).toLong() shl 17) or
                    (byte(p + 2).toLong() shl 9) or
                    (byte(p + 3).toLong() shl 1) or
                    (byte(p + 4).toLong() ushr 7)
                pcrAvailable = true
                if (selectedPcrPid == -1) {
                    selectedPcrPid = pid
                    selectedPidEpoch = 0
                } else if (pid != selectedPcrPid) {
                    multiplePcrPids = true
                }
                if (pid == selectedPcrPid) {
                    if (pcrCount == 0) {
                        firstPcr = value
                    } else {
                        if (selectedPidEpoch != 0) multiplePcrEpochs = true
                        val delta = wrapDelta(value, previousPcr)
                        val jumpUs = abs(ticksToUs(delta))
                        if (jumpUs > pcrMaxJumpUs) pcrMaxJumpUs = jumpUs
                        if (delta < 0) pcrBackwardJumpCount++
                    }
                    previousPcr = value
                    lastPcr = value
                    pcrCount++
                }
            }
        }
        val hasSingleContinuousTimeline = !multiplePcrPids && !multiplePcrEpochs && pcrCount >= 2
        return TransportStreamObservation(
            inputSource = TransportStreamInputSource.ORIGINAL,
            pcrAvailable = pcrAvailable,
            pcrPid = if (multiplePcrPids) -1 else selectedPcrPid,
            pcrSpanUs = if (hasSingleContinuousTimeline) {
                ticksToUs(wrapDelta(lastPcr, firstPcr))
            } else {
                -1
            },
            pcrMaxJumpUs = if (hasSingleContinuousTimeline) pcrMaxJumpUs else -1,
            pcrBackwardJumpCount = if (hasSingleContinuousTimeline) pcrBackwardJumpCount else -1,
            adaptationDiscontinuityCount = adaptationDiscontinuities,
            firstAudioMinusVideoUs = av?.firstAudioMinusVideoUs,
            hlsDiscontinuity = hlsDiscontinuity,
            hlsDiscontinuitySequence = hlsDiscontinuitySequence,
            wouldRepair = repair.wouldRepair,
            repairCorrectionSeconds = repair.correctionSeconds,
            repairHeaderCount = repair.headerCount,
        )
    }

    private fun wrapDelta(value: Long, reference: Long): Long =
        ((value - reference + PCR_WRAP / 2) and (PCR_WRAP - 1)) - PCR_WRAP / 2

    private fun ticksToUs(ticks: Long): Long = ticks * 1_000_000L / PCR_HZ
}
