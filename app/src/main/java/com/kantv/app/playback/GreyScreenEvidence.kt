package com.kantv.app.playback

/**
 * What the picture pipeline looked like at the moment evidence was recorded.
 *
 * The distinction matters for the CCTV-5+ grey-screen report: "no first frame", "decoded but not
 * presented", "presented and then frozen", and "audio advanced without video" have different causes,
 * and collapsing them into one label is what made the original investigation ambiguous.
 */
enum class GreyScreenEvidence {
    /** The player never rendered a first frame inside the bounded window. */
    NO_FIRST_FRAME,

    /** A picture rendered, but presentation time has not advanced since. */
    FRAME_FROZEN,

    /** Audio presentation advanced while no picture ever rendered. */
    SOUND_ONLY,

    /** A frame callback arrived from a generation that is no longer current. */
    STALE_SESSION,
}

/**
 * Pure, generation-scoped evidence collector for "the screen is grey but the app is playing".
 *
 * It never inspects a Surface, a View, or a decoder directly: it only compares timestamps and
 * counters the engine already reports, so it stays unit-testable and cannot leak media content.
 * Every threshold is a named constant and every piece of evidence is reported at most once per
 * source generation.
 */
class GreyScreenEvidenceTracker(
    private val elapsedRealtimeMs: () -> Long,
    private val noFirstFrameAfterMs: Long = NO_FIRST_FRAME_AFTER_MS,
    private val frameFrozenAfterMs: Long = FRAME_FROZEN_AFTER_MS,
    private val soundOnlyAfterMs: Long = SOUND_ONLY_AFTER_MS,
) {
    private var generationStartMs = 0L
    private var readyAtMs: Long? = null
    private var firstFrameAtMs: Long? = null
    private var lastVideoPtsUs = -1L
    private var lastVideoProgressAtMs: Long? = null
    private var lastAudioFrames = -1L
    private var lastAudioAdvanceAtMs: Long? = null
    private var audioAdvancedSinceStart = false
    private val reported = mutableSetOf<GreyScreenEvidence>()

    fun reset() {
        generationStartMs = elapsedRealtimeMs()
        readyAtMs = null
        firstFrameAtMs = null
        lastVideoPtsUs = -1L
        lastVideoProgressAtMs = null
        lastAudioFrames = -1L
        lastAudioAdvanceAtMs = null
        audioAdvancedSinceStart = false
        reported.clear()
    }

    fun onEvent(event: PlaybackEvent, nowMs: Long) {
        if (generationStartMs == 0L) generationStartMs = nowMs
        when (event) {
            is PlaybackEvent.Ready -> if (readyAtMs == null) readyAtMs = nowMs
            is PlaybackEvent.FirstFrame -> if (firstFrameAtMs == null) firstFrameAtMs = nowMs
            else -> Unit
        }
    }

    /**
     * Returns evidence the first time its condition is met for this generation, then null.
     */
    fun observe(
        nowMs: Long,
        renderedFirstFrame: Boolean,
        presentationTimeUs: Long,
        audioPresentedFrames: Long,
        ready: Boolean,
    ): GreyScreenEvidence? {
        if (renderedFirstFrame && firstFrameAtMs == null) firstFrameAtMs = nowMs
        if (ready && readyAtMs == null) readyAtMs = nowMs
        if (presentationTimeUs >= 0 && presentationTimeUs != lastVideoPtsUs) {
            lastVideoPtsUs = presentationTimeUs
            lastVideoProgressAtMs = nowMs
        }
        if (audioPresentedFrames >= 0 && audioPresentedFrames > lastAudioFrames) {
            lastAudioFrames = audioPresentedFrames
            lastAudioAdvanceAtMs = nowMs
            audioAdvancedSinceStart = true
        }
        val firstFrame = firstFrameAtMs
        if (firstFrame == null) {
            if (audioAdvancedSinceStart &&
                elapsedSince(readyAtMs ?: generationStartMs, nowMs) >= soundOnlyAfterMs
            ) {
                return reportOnce(GreyScreenEvidence.SOUND_ONLY)
            }
            if (elapsedSince(readyAtMs ?: generationStartMs, nowMs) >= noFirstFrameAfterMs) {
                return reportOnce(GreyScreenEvidence.NO_FIRST_FRAME)
            }
            return null
        }
        if (elapsedSince(lastVideoProgressAtMs ?: firstFrame, nowMs) >= frameFrozenAfterMs) {
            return reportOnce(GreyScreenEvidence.FRAME_FROZEN)
        }
        return null
    }

    fun hasFirstFrame(): Boolean = firstFrameAtMs != null

    private fun reportOnce(evidence: GreyScreenEvidence): GreyScreenEvidence? =
        if (reported.add(evidence)) evidence else null

    private fun elapsedSince(fromMs: Long, nowMs: Long): Long = (nowMs - fromMs).coerceAtLeast(0L)

    companion object {
        /** Ready but no picture: long enough to exclude normal live-edge startup jitter. */
        const val NO_FIRST_FRAME_AFTER_MS = 12_000L

        /** A picture existed and then stopped: the frame-progress watchdog uses 6 s, this is looser. */
        const val FRAME_FROZEN_AFTER_MS = 8_000L

        /** Audio presented without any picture: reported earlier, because it is unambiguous. */
        const val SOUND_ONLY_AFTER_MS = 6_000L
    }
}
