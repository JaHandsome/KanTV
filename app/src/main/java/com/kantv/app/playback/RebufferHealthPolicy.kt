package com.kantv.app.playback

/**
 * Time-ordered, generation-scoped rebuffer health for one playing source.
 *
 * The policy consumes monotonic millisecond timestamps plus the existing [PlaybackEvent] stream and
 * reports counts, durations, and whether the current candidate is unstable. Callers use that verdict
 * to test the next configured source instead of waiting for Media3 to surface
 * `BEHIND_LIVE_WINDOW` (error code 1002).
 *
 * Interval discipline (one open interval per source):
 * - a buffering interval opens on the **first** [PlaybackEvent.Loading] seen after the first frame.
 *   Repeated `Loading` callbacks for the same buffering episode keep the original start, so a
 *   player that reports buffering several times cannot reset the measured duration;
 * - it closes on [PlaybackEvent.Ready], on [PlaybackEvent.VideoProgress], on [pause], on a source
 *   switch, or on session end. Closing twice is impossible: the interval is consumed on close;
 * - [PlaybackEvent.VideoProgress] after a long silence closes a `FRAME_STALL` interval measured
 *   from the last observed picture, minus the stall threshold, so a frozen picture counts even
 *   when the player never reported buffering. The `FRAME_STALL` threshold and the player's own
 *   frame-progress watchdog are deliberately aligned;
 * - [pause] closes the open interval and stops counting. Paused wall-clock time is never
 *   rebuffering;
 * - [resume] clears only the frame-progress baseline. Rebuffering history inside the sliding window
 *   is retained, because it describes the same source, and a `Ready` + first frame is required
 *   again after a resume;
 * - a late callback from another session is ignored. Generation boundaries are enforced by
 *   construction: [beginSource], [pause], [resume], and [endSource] all discard the pending
 *   interval, and the coordinator additionally drops any event whose source generation it already
 *   replaced, so an interval can never span a boundary.
 *
 * Verdict thresholds are named constants: a single short jitter below
 * [MIN_COUNTED_REBUFFER_MS] is not even recorded, everything during
 * [GRACE_AFTER_FIRST_FRAME_MS] after the first frame is diagnostic-only, one interruption of at
 * least [SINGLE_REBUFFER_LIMIT_MS], or [UNSTABLE_COUNT_IN_WINDOW] counted interruptions, or
 * [UNSTABLE_TOTAL_MS] of counted interruptions inside [WINDOW_MS] declares the candidate unstable.
 *
 * A stutter loop that never fills [WINDOW_MS] on its own is caught by the longer
 * [SUSTAINED_WINDOW_MS] rule instead: [SUSTAINED_COUNT_IN_WINDOW] counted interruptions totalling
 * [SUSTAINED_TOTAL_MS]. Both arms must hold, so a few scattered hiccups stay tolerated no matter
 * how long the session runs.
 */
class RebufferHealthPolicy(
    private val windowMs: Long = WINDOW_MS,
    private val graceAfterFirstFrameMs: Long = GRACE_AFTER_FIRST_FRAME_MS,
    private val minCountedRebufferMs: Long = MIN_COUNTED_REBUFFER_MS,
    private val singleRebufferLimitMs: Long = SINGLE_REBUFFER_LIMIT_MS,
    private val unstableTotalMs: Long = UNSTABLE_TOTAL_MS,
    private val unstableCountInWindow: Int = UNSTABLE_COUNT_IN_WINDOW,
    private val frameStallThresholdMs: Long = FRAME_STALL_THRESHOLD_MS,
    private val sustainedWindowMs: Long = SUSTAINED_WINDOW_MS,
    private val sustainedCountInWindow: Int = SUSTAINED_COUNT_IN_WINDOW,
    private val sustainedTotalMs: Long = SUSTAINED_TOTAL_MS,
) {
    enum class RebufferReason {
        /** The player reported a fresh buffering episode after playback had already started. */
        PLAYER_BUFFERING,

        /** Video presentation time stopped advancing without any buffering state change. */
        FRAME_STALL,
    }

    /** One contiguous window of time in which the user saw no advancing picture. */
    data class RebufferEvent(
        val sessionId: Long,
        val generation: Long,
        val sequence: Int,
        val startedAtMs: Long,
        val endedAtMs: Long,
        val durationMs: Long,
        val reason: RebufferReason,
        /** True when the event happened inside the first-frame grace window. */
        val withinFirstFrameGrace: Boolean,
    )

    /** Session-wide totals and the current sliding-window verdict. */
    data class SessionHealth(
        val sessionId: Long,
        val generation: Long,
        val firstFrameAtMs: Long?,
        /** Every recorded event, including short and grace-window ones. */
        val rebufferCount: Int,
        val totalRebufferMs: Long,
        val longestRebufferMs: Long,
        val lastVideoProgressAtMs: Long?,
        val lastVideoPtsUs: Long,
        val unstable: Boolean,
        val unstableAtMs: Long?,
        val unstableReason: UnstableReason?,
        val countedRebufferInWindow: Int,
        val countedRebufferMsInWindow: Long,
        /** Events inside the window that grace suppressed from the instability decision. */
        val graceSkippedInWindow: Int,
        val shortestCountedRebufferMs: Long?,
    ) {
        val progressedAfterFirstFrame: Boolean
            get() = firstFrameAtMs != null && lastVideoProgressAtMs != null &&
                (lastVideoProgressAtMs ?: 0L) > (firstFrameAtMs ?: 0L)
    }

    enum class UnstableReason { SINGLE_LONG_REBUFFER, ACCUMULATED_DURATION, REPEATED_REBUFFERS }

    /** The outcome of one observed event; [unstable] is a level, not an edge. */
    data class Verdict(
        val sessionId: Long,
        val generation: Long,
        val firstFrameObserved: Boolean,
        val firstFrameWaitMs: Long,
        val buffering: Boolean,
        val unstable: Boolean,
        val unstableReason: UnstableReason?,
        val countedRebuffer: Int,
        val countedRebufferMs: Long,
        val lastVideoPtsUs: Long,
        val event: RebufferEvent?,
    )

    private var sessionId = 0L
    private var generation = 0L
    private var startedAtMs = 0L
    private var paused = false
    private var firstFrameAtMs: Long? = null
    private var lastVideoProgressAtMs: Long? = null
    private var lastVideoPtsUs = -1L
    private var progressSeen = false
    private var pending: PendingInterval? = null
    private var sequence = 0
    private var totalRebufferMs = 0L
    private var longestRebufferMs = 0L
    private var unstable = false
    private var unstableAtMs: Long? = null
    private var unstableReason: UnstableReason? = null
    private val events = ArrayDeque<RebufferEvent>()

    /** Ends any previous interval and starts a clean candidate. Old callbacks can no longer count. */
    fun beginSource(sessionId: Long, generation: Long, nowMs: Long): Verdict {
        this.sessionId = sessionId
        this.generation = generation
        startedAtMs = nowMs
        paused = false
        firstFrameAtMs = null
        lastVideoProgressAtMs = null
        lastVideoPtsUs = -1L
        progressSeen = false
        pending = null
        sequence = 0
        totalRebufferMs = 0L
        longestRebufferMs = 0L
        unstable = false
        unstableAtMs = null
        unstableReason = null
        events.clear()
        return verdict(nowMs, event = null)
    }

    /**
     * Closes the open interval and freezes the source. [reason] labels the transition for reports.
     */
    @Suppress("UNUSED_PARAMETER")
    fun endSource(
        nowMs: Long,
        reason: RebufferEndReason = RebufferEndReason.SOURCE_SWITCHED,
    ): Verdict {
        val closed = closePending(nowMs, reason)
        paused = false
        return verdict(nowMs, closed)
    }

    /** Pause ends the measured interval and stops counting paused wall-clock time. */
    fun pause(nowMs: Long): Verdict {
        val closed = closePending(nowMs, RebufferEndReason.PAUSED)
        paused = true
        return verdict(nowMs, closed)
    }

    /**
     * Resume starts a new frame-progress epoch on the same source: no interval is open and the stall
     * baseline is cleared, so paused wall-clock time can never be attributed to a stall.
     *
     * The first-frame boundary is deliberately **kept**: Media3 does not re-emit a rendered first
     * frame for a resumed source, so clearing it would permanently disable stall detection and the
     * early-switch path for the rest of this candidate. The sliding-window rebuffering history is
     * retained for the same reason: it describes this source.
     */
    fun resume(nowMs: Long, generation: Long = this.generation): Verdict {
        this.generation = generation
        paused = false
        lastVideoProgressAtMs = null
        lastVideoPtsUs = -1L
        progressSeen = false
        pending = null
        return verdict(nowMs, event = null)
    }

    fun onEvent(event: PlaybackEvent, nowMs: Long): Verdict {
        if (event.sessionId != sessionId) {
            // A late callback from a stopped session is not evidence about the current picture.
            return verdict(nowMs, event = null)
        }
        return when (event) {
            is PlaybackEvent.Loading -> {
                if (paused || firstFrameAtMs == null) return verdict(nowMs, event = null)
                openInterval(nowMs, RebufferReason.PLAYER_BUFFERING)
                verdict(nowMs, event = null)
            }
            is PlaybackEvent.Ready -> {
                if (paused) return verdict(nowMs, event = null)
                verdict(nowMs, closePending(nowMs, RebufferEndReason.READY))
            }
            is PlaybackEvent.FirstFrame -> {
                if (firstFrameAtMs == null) firstFrameAtMs = nowMs
                verdict(nowMs, event = null)
            }
            is PlaybackEvent.VideoProgress -> {
                if (paused) return verdict(nowMs, event = null)
                // Order matters: the stall gap is measured against the previous observation, and an
                // interval a player opened for this episode must be closed by the picture returning.
                val stall = consumeStallGap(nowMs)
                lastVideoProgressAtMs = nowMs
                lastVideoPtsUs = event.presentationTimeUs
                progressSeen = true
                verdict(nowMs, closePending(nowMs, RebufferEndReason.VIDEO_PROGRESS) ?: stall)
            }
            is PlaybackEvent.Technical,
            is PlaybackEvent.Ended,
            is PlaybackEvent.Error,
            -> {
                // Errors and ended streams belong to the coordinator, which already reopens or
                // switches candidates; counting them here would double-count one interruption.
                verdict(nowMs, event = null)
            }
        }
    }

    fun health(nowMs: Long = lastObservedMs()): SessionHealth {
        val counted = countedInWindow(nowMs)
        val inWindow = inWindow(nowMs, windowMs)
        return SessionHealth(
            sessionId = sessionId,
            generation = generation,
            firstFrameAtMs = firstFrameAtMs,
            rebufferCount = events.size,
            totalRebufferMs = totalRebufferMs,
            longestRebufferMs = longestRebufferMs,
            lastVideoProgressAtMs = lastVideoProgressAtMs,
            lastVideoPtsUs = lastVideoPtsUs,
            unstable = unstable,
            unstableAtMs = unstableAtMs,
            unstableReason = unstableReason,
            countedRebufferInWindow = counted.size,
            countedRebufferMsInWindow = counted.sumOf { it.durationMs },
            graceSkippedInWindow = inWindow.count { it.withinFirstFrameGrace },
            shortestCountedRebufferMs = counted.minOfOrNull { it.durationMs },
        )
    }

    fun events(): List<RebufferEvent> = events.toList()

    /** True when the current source already paid the rebuffering evidence to test another one. */
    fun isUnstable(): Boolean = unstable

    private data class PendingInterval(
        val startedAtMs: Long,
        val reason: RebufferReason,
    )

    private fun lastObservedMs(): Long = listOfNotNull(
        lastVideoProgressAtMs,
        pending?.startedAtMs,
        firstFrameAtMs,
    ).maxOrNull() ?: startedAtMs

    /**
     * Idempotent open: a second `Loading` for the same buffering episode keeps the original start.
     * The picture must first be observed ([firstFrameAtMs]) before buffering describes a stall.
     */
    private fun openInterval(nowMs: Long, reason: RebufferReason) {
        if (pending == null) pending = PendingInterval(nowMs, reason)
    }

    private fun closePending(nowMs: Long, reason: RebufferEndReason): RebufferEvent? {
        val open = pending ?: return null
        pending = null
        val duration = (nowMs - open.startedAtMs).coerceAtLeast(0L)
        if (duration < minCountedRebufferMs) return null
        val record = RebufferEvent(
            sessionId = sessionId,
            generation = generation,
            sequence = sequence++,
            startedAtMs = open.startedAtMs,
            endedAtMs = nowMs,
            durationMs = duration,
            reason = open.reason,
            withinFirstFrameGrace = isWithinFirstFrameGrace(open.startedAtMs),
        )
        events.addLast(record)
        while (events.size > MAX_RETAINED_EVENTS) events.removeFirst()
        totalRebufferMs += duration
        longestRebufferMs = maxOf(longestRebufferMs, duration)
        if (!record.withinFirstFrameGrace) evaluateInstability(nowMs)
        return record
    }

    private fun evaluateInstability(nowMs: Long) {
        if (unstable) return
        val counted = countedInWindow(nowMs)
        val longest = counted.maxOfOrNull { it.durationMs } ?: 0L
        val total = counted.sumOf { it.durationMs }
        val sustained = countedInWindow(nowMs, sustainedWindowMs)
        // The sustained arm is last so a pattern the shorter window already catches keeps the reason it
        // always reported; this arm only adds the verdict the shorter window cannot reach.
        unstableReason = when {
            longest >= singleRebufferLimitMs -> UnstableReason.SINGLE_LONG_REBUFFER
            counted.size >= unstableCountInWindow -> UnstableReason.REPEATED_REBUFFERS
            total >= unstableTotalMs -> UnstableReason.ACCUMULATED_DURATION
            sustained.size >= sustainedCountInWindow &&
                sustained.sumOf { it.durationMs } >= sustainedTotalMs -> UnstableReason.REPEATED_REBUFFERS
            else -> null
        }
        if (unstableReason != null) {
            unstable = true
            unstableAtMs = nowMs
        }
    }

    /**
     * A silent stall: no buffering state change, but the picture did not advance either. Only the
     * time beyond [frameStallThresholdMs] is attributed to rebuffering. The threshold is
     * deliberately larger than the coordinator's frame-progress watchdog (6 s), so the existing
     * watchdog keeps owning the first response and this path only adds evidence when a freeze
     * outlives it.
     */
    private fun consumeStallGap(nowMs: Long): RebufferEvent? {
        val baseline = lastVideoProgressAtMs ?: return null
        if (pending != null || firstFrameAtMs == null || !progressSeen) return null
        val gap = nowMs - baseline
        if (gap < frameStallThresholdMs + minCountedRebufferMs) return null
        val attributed = gap - frameStallThresholdMs
        return recordEvent(baseline + frameStallThresholdMs, nowMs, attributed, RebufferReason.FRAME_STALL)
    }

    private fun recordEvent(
        startedAtMs: Long,
        endedAtMs: Long,
        durationMs: Long,
        reason: RebufferReason,
    ): RebufferEvent {
        val record = RebufferEvent(
            sessionId = sessionId,
            generation = generation,
            sequence = sequence++,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            durationMs = durationMs,
            reason = reason,
            withinFirstFrameGrace = isWithinFirstFrameGrace(startedAtMs),
        )
        events.addLast(record)
        while (events.size > MAX_RETAINED_EVENTS) events.removeFirst()
        totalRebufferMs += durationMs
        longestRebufferMs = maxOf(longestRebufferMs, durationMs)
        if (!record.withinFirstFrameGrace) evaluateInstability(endedAtMs)
        return record
    }

    private fun isWithinFirstFrameGrace(atMs: Long): Boolean {
        val firstFrame = firstFrameAtMs ?: return false
        return atMs < firstFrame + graceAfterFirstFrameMs
    }

    private fun inWindow(nowMs: Long, windowMs: Long): List<RebufferEvent> {
        val from = nowMs - windowMs
        return events.filter { it.endedAtMs >= from }
    }

    /** Instability evidence only exists for a picture that already started. */
    private fun countedInWindow(nowMs: Long, windowMs: Long = this.windowMs): List<RebufferEvent> {
        if (firstFrameAtMs == null) return emptyList()
        return inWindow(nowMs, windowMs).filter { !it.withinFirstFrameGrace }
    }

    private fun verdict(nowMs: Long, event: RebufferEvent?): Verdict {
        val counted = countedInWindow(nowMs)
        return Verdict(
            sessionId = sessionId,
            generation = generation,
            firstFrameObserved = firstFrameAtMs != null,
            firstFrameWaitMs = firstFrameAtMs?.minus(startedAtMs)?.coerceAtLeast(0L) ?: -1L,
            buffering = pending != null,
            unstable = unstable,
            unstableReason = unstableReason,
            countedRebuffer = counted.size,
            countedRebufferMs = counted.sumOf { it.durationMs },
            lastVideoPtsUs = lastVideoPtsUs,
            event = event,
        )
    }

    companion object {
        /** Sliding window in which rebuffering evidence is considered current. */
        const val WINDOW_MS = 60_000L

        /**
         * A player may report one short buffering state while it swaps a segment at the live edge.
         * Nothing inside the first seconds after the first frame is treated as instability.
         */
        const val GRACE_AFTER_FIRST_FRAME_MS = 6_000L

        /** Shorter interruptions are discarded entirely: a single short jitter never switches. */
        const val MIN_COUNTED_REBUFFER_MS = 600L

        /**
         * One user-visible interruption this long justifies testing another candidate.
         *
         * Deliberately patient. A stall of a few seconds is a network hiccup that resolves on its
         * own, and switching candidates is not free: live candidates sit at different live edges, so
         * the picture jumps. The old 6 s value meant an ordinary hiccup abandoned a working source
         * and moved the user somewhere else on the timeline, which is exactly what a viewer notices
         * and dislikes. A stall this long is no longer a hiccup.
         */
        const val SINGLE_REBUFFER_LIMIT_MS = 25_000L

        /** Cumulative counted rebuffering inside [WINDOW_MS] that justifies another candidate. */
        const val UNSTABLE_TOTAL_MS = 30_000L

        /** Or this many separate interruptions inside [WINDOW_MS]. */
        const val UNSTABLE_COUNT_IN_WINDOW = 6

        /**
         * Sliding window for the sustained-stutter rule. Long enough to see a slow rhythm repeat.
         *
         * [WINDOW_MS] measures one burst; a stutter loop that buffers for 1.5-2.5 s every 15-20 s
         * never puts more than three or four interruptions or ten seconds of loss into any 60 s, and
         * each episode ends well before the 25 s single-interruption limit or the coordinator's 25 s
         * stall fallback, so every threshold the shorter window owns stays quiet while the viewer is
         * interrupted every few seconds for minutes on end. What makes that loop unacceptable is its
         * duty cycle, not the size of any single episode, and a duty cycle is only visible over a
         * window that spans many episodes — hence minutes rather than seconds.
         */
        const val SUSTAINED_WINDOW_MS = 300_000L

        /**
         * Counted interruptions inside [SUSTAINED_WINDOW_MS] needed by the sustained rule.
         *
         * Eight means the candidate broke at least once every ~35 s across five minutes. Occasional
         * hiccups on a healthy candidate do not come close; the observed loop (one every 18 s) puts
         * roughly sixteen into the window.
         */
        const val SUSTAINED_COUNT_IN_WINDOW = 8

        /**
         * And their cumulative counted duration inside [SUSTAINED_WINDOW_MS], so that many
         * sub-second blips that each resolve instantly are still tolerated: 20 s of lost picture
         * over five minutes is ~7 % of the window. The observed 2 s-every-18 s loop is ~11 % and
         * clears it, while the same rhythm confined to two minutes (seven episodes, 14 s) does not.
         */
        const val SUSTAINED_TOTAL_MS = 20_000L

        /**
         * Video presentation time silent for this long is a stall even without a state change.
         * Larger than the coordinator's 6 s frame-progress watchdog on purpose: the watchdog is the
         * first responder, and this only contributes evidence when a freeze outlives it. Raised from
         * 12 s because a single 12 s gap used to exceed the cumulative budget all by itself and, on
         * its own, triggered a source switch.
         */
        const val FRAME_STALL_THRESHOLD_MS = 20_000L

        private const val MAX_RETAINED_EVENTS = 32
    }
}

enum class RebufferEndReason { READY, VIDEO_PROGRESS, PAUSED, SOURCE_SWITCHED, SESSION_ENDED, PROCESS_ENDED }
