package com.kantv.app.playback

/** Sink playback estimate and an about-to-render frame, never speaker/display measurements. */
data class NativeAvSyncObservation(
    val status: String,
    val generation: Long = -1,
    val streamOffsetUs: Long = -1,
    val audioClockUs: Long = -1,
    val audioSampleNanoTime: Long = -1,
    val videoPtsUs: Long = -1,
    val pairSpanUs: Long = -1,
    val videoReleaseDelayUs: Long = -1,
) {
    fun statusAt(nowNanoTime: Long): String = if (status == "AVAILABLE" &&
        (nowNanoTime < audioSampleNanoTime || nowNanoTime - audioSampleNanoTime > MAX_SAMPLE_AGE_NS)
    ) "INSUFFICIENT_STALE_SAMPLE" else status

    companion object {
        const val MAX_SAMPLE_AGE_NS = 1_500_000_000L
        const val MAX_PAIR_SPAN_NS = 100_000_000L
    }
}

/** Caller holds its coalescing lock. All frame/position callbacks update primitives only. */
internal class NativeAvSyncTracker {
    private var playing = false
    private var audioOffsetUs: Long? = null
    private var lastKnownOffsetUs: Long? = null
    private var periodUid: Any? = null
    private var periodBlocked = false
    private var periodActive = false
    private var audioUs = -1L
    private var audioNs = -1L
    private var videoUs = -1L
    private var videoNs = -1L
    private var videoReleaseNs = -1L
    private var status = "INSUFFICIENT_NO_SAMPLE"

    fun reset(clearOffsets: Boolean = false) {
        audioUs = -1
        audioNs = -1
        videoUs = -1
        videoNs = -1
        status = "INSUFFICIENT_INVALIDATED"
        if (clearOffsets) {
            audioOffsetUs = null
            lastKnownOffsetUs = null
            periodUid = null
            periodBlocked = false
            periodActive = false
        }
    }

    fun playing(value: Boolean) {
        if (playing != value) reset()
        playing = value
    }

    fun audioOffset(value: Long?) {
        if (audioOffsetUs != value) {
            if (lastKnownOffsetUs != null && value != null && lastKnownOffsetUs != value) periodBlocked = true
            reset()
        }
        audioOffsetUs = value
        if (value != null) lastKnownOffsetUs = value
    }

    /** One MediaPeriod shares its renderer offset. UID never leaves this in-memory gate. */
    fun period(uid: Any?, count: Int) {
        if (count > 1 || (periodUid != null && uid != null && periodUid != uid)) periodBlocked = true
        periodActive = count == 1 && uid != null && !periodBlocked
        if (count == 1 && uid != null && !periodBlocked) periodUid = uid
        if (count != 1 || uid == null || periodBlocked) reset()
    }

    fun audio(rawPositionUs: Long, available: Boolean, nowNs: Long) {
        val offset = audioOffsetUs
        if (!available || offset == null || rawPositionUs < 0 ||
            offset !in -MAX_INTEGER..MAX_INTEGER || rawPositionUs !in 0..MAX_INTEGER
        ) {
            reset()
            status = if (offset == null) "INSUFFICIENT_NO_OFFSET" else "INSUFFICIENT_INVALID_CLOCK"
            return
        }
        audioUs = rawPositionUs - offset
        audioNs = nowNs
        pair()
    }

    fun video(ptsUs: Long, releaseNs: Long, nowNs: Long) {
        videoUs = ptsUs
        videoNs = nowNs
        videoReleaseNs = releaseNs
        pair()
    }

    private fun pair() {
        status = when {
            !playing -> "INSUFFICIENT_NOT_PLAYING"
            audioOffsetUs == null -> "INSUFFICIENT_NO_OFFSET"
            !periodActive || periodBlocked -> "INSUFFICIENT_STREAM_MISMATCH"
            audioNs < 0 || videoNs < 0 -> "INSUFFICIENT_NO_SAMPLE"
            audioUs !in 0..MAX_INTEGER || videoUs !in 0..MAX_INTEGER -> "INSUFFICIENT_INVALID_CLOCK"
            kotlin.math.abs(audioNs - videoNs) > NativeAvSyncObservation.MAX_PAIR_SPAN_NS -> "INSUFFICIENT_PAIR_STALE"
            videoReleaseNs < 0 || videoReleaseNs - videoNs !in -1_000_000_000L..1_000_000_000L -> "INSUFFICIENT_INVALID_CLOCK"
            else -> "AVAILABLE"
        }
    }

    fun snapshot(generation: Long): NativeAvSyncObservation = if (!playing) {
        NativeAvSyncObservation("INSUFFICIENT_NOT_PLAYING", generation)
    } else if (status == "AVAILABLE") {
        NativeAvSyncObservation(
            status, generation, audioOffsetUs!!, audioUs, audioNs, videoUs,
            kotlin.math.abs(audioNs - videoNs) / 1_000, (videoReleaseNs - videoNs) / 1_000,
        )
    } else NativeAvSyncObservation(status, generation)

    companion object { private const val MAX_INTEGER = 9_007_199_254_740_991L }
}
