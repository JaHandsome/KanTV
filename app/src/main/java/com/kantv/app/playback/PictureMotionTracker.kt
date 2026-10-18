package com.kantv.app.playback

/**
 * One bounded classification of a sampled picture.
 *
 * The existing "frozen picture" evidence keys on the presentation clock: it fires when the clock
 * stops advancing. A candidate that keeps decoding normally while showing unchanging content has an
 * advancing clock, so nothing in the app can tell it apart from healthy playback. This enum is the
 * missing axis: whether the *picture itself* changed.
 */
enum class PictureMotion {
    /** The picture changed since the previous judgeable sample. */
    MOVING,

    /** A judgeable sample whose content did not change: the picture is standing still. */
    STILL,

    /** Sampled, but the frame is too dark to judge: an unlit or protected surface is not evidence. */
    UNVERIFIABLE,

    /** Not judged: the capture failed, timed out, or was deliberately not attempted. */
    UNAVAILABLE,
}

/**
 * What one observation concluded, in bounded values only.
 *
 * [millisSincePictureVerified] is the whole point of this tracker: it grows while the picture never
 * changes and stays small while it does. Nothing here says what the picture contained — pixels are
 * never retained, only a comparison outcome and two millisecond counts.
 */
data class PictureMotionObservation(
    val motion: PictureMotion,
    /** Milliseconds since motion was last verified, or -1 when the picture was never verified. */
    val millisSincePictureVerified: Long,
    /** Capture latency in milliseconds; -1 not attempted, -2 attempted and never called back. */
    val captureMs: Long,
)

/**
 * Pure, generation-scoped judge of "is this picture actually changing".
 *
 * Callers hand in a downscaled per-pixel luma signature. The tracker compares consecutive signatures
 * with a mean absolute difference, which is exactly the measure the debug playback probe already
 * uses to separate a moving picture (differences in the tens) from a static one (differences of
 * thousandths). Reusing that proven measure is deliberate: the native path is the only place this
 * signal is missing, not the measure itself.
 *
 * Two safety rules matter more than sensitivity, because this signal will eventually be allowed to
 * leave a candidate:
 * - A dark or unjudgeable frame never counts as stillness, because a protected or unlit surface
 *   would otherwise look like a dead source.
 * - A generation change always starts over, so no verdict can straddle two different candidates.
 *
 * The class holds no Android type, so every rule below is unit-testable.
 */
class PictureMotionTracker(
    private val movingDifference: Double = MOVING_DIFFERENCE,
    private val blackLuma: Double = BLACK_LUMA,
) {
    private var generation: Long = -1L
    private var lastSignature: DoubleArray? = null
    private var lastVerifiedAtMs: Long = -1L

    /** Forgets every observation. Used when playback is torn down. */
    fun reset() {
        generation = -1L
        lastSignature = null
        lastVerifiedAtMs = -1L
    }

    /**
     * Judges one captured frame.
     *
     * [signature] is null when the capture failed or timed out. A null signature reports
     * [PictureMotion.UNAVAILABLE] and never advances the verified instant: failing to look is not
     * evidence that the picture stopped.
     */
    fun onSample(
        nowMs: Long,
        sourceGeneration: Long,
        signature: DoubleArray?,
        captureMs: Long,
    ): PictureMotionObservation {
        beginGeneration(sourceGeneration)
        if (signature == null || signature.isEmpty()) {
            return observation(PictureMotion.UNAVAILABLE, nowMs, captureMs)
        }
        val reference = lastSignature
        lastSignature = signature
        if (signature.average() < blackLuma) {
            return observation(PictureMotion.UNVERIFIABLE, nowMs, captureMs)
        }
        if (reference == null || reference.size != signature.size) {
            // First judgeable frame of this generation is the baseline: the picture is verified now,
            // and stillness can only be claimed by a later sample.
            lastVerifiedAtMs = nowMs
            return observation(PictureMotion.MOVING, nowMs, captureMs)
        }
        val changed = meanAbsoluteDifference(reference, signature) >= movingDifference
        if (changed) lastVerifiedAtMs = nowMs
        return observation(if (changed) PictureMotion.MOVING else PictureMotion.STILL, nowMs, captureMs)
    }

    /**
     * Records that the picture was deliberately not observed, because an overlay covered it or
     * playback was not in a state worth judging.
     *
     * The verified instant moves forward so the elapsed count only ever spans intervals we really
     * looked at: a menu left open for a minute must not read as a minute of stillness.
     */
    fun onNotObserved(nowMs: Long): PictureMotionObservation {
        if (lastVerifiedAtMs >= 0L) lastVerifiedAtMs = nowMs
        return observation(PictureMotion.UNAVAILABLE, nowMs, -1L)
    }

    private fun beginGeneration(sourceGeneration: Long) {
        if (sourceGeneration == generation) return
        generation = sourceGeneration
        lastSignature = null
        lastVerifiedAtMs = -1L
    }

    private fun observation(motion: PictureMotion, nowMs: Long, captureMs: Long) = PictureMotionObservation(
        motion = motion,
        millisSincePictureVerified = if (lastVerifiedAtMs < 0L) -1L else (nowMs - lastVerifiedAtMs).coerceAtLeast(0L),
        captureMs = captureMs,
    )

    companion object {
        /**
         * A mean absolute luma difference at or above this counts as motion. It is the exact
         * boundary the debug probe uses to call a picture static, kept identical so the probe's
         * measurements and this judge can never disagree about the same source.
         */
        const val MOVING_DIFFERENCE = 0.8

        /** Below this mean luma a frame is too dark to judge rather than evidence of stillness. */
        const val BLACK_LUMA = 3.0

        fun meanAbsoluteDifference(left: DoubleArray, right: DoubleArray): Double {
            if (left.isEmpty() || left.size != right.size) return 0.0
            var total = 0.0
            for (index in left.indices) {
                total += kotlin.math.abs(left[index] - right[index])
            }
            return total / left.size
        }
    }
}
