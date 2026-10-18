package com.kantv.app.playback

import kotlin.math.ceil

/**
 * Chooses a bounded HLS reserve from measurements made during this session.
 *
 * This deliberately has no channel/source identity input.  A reserve can absorb a short
 * download tail, but when measured aggregate throughput is below the media bitrate it reports
 * an unsustainable delivery instead of growing the cache forever.
 */
object PrefetchStartupPolicy {
    data class Sample(val bytes: Int, val elapsedMs: Long)

    data class Decision(
        val requiredSegments: Int,
        val requiredCacheMs: Long,
        val downloadP95Ms: Long?,
        val mediaBitrateBps: Long?,
        val deliverySustainable: Boolean?,
    )

    fun decide(
        targetDurationMs: Long,
        samples: List<Sample>,
        availableSegments: Int,
        overrideSegments: Int? = null,
    ): Decision {
        val duration = targetDurationMs.coerceAtLeast(1L)
        if (overrideSegments != null) {
            return Decision(
                requiredSegments = overrideSegments.coerceAtLeast(1),
                requiredCacheMs = overrideSegments.coerceAtLeast(1) * duration,
                downloadP95Ms = null,
                mediaBitrateBps = null,
                deliverySustainable = null,
            )
        }
        if (samples.isEmpty()) return Decision(2, 2 * duration, null, null, null)

        val orderedElapsed = samples.map { it.elapsedMs.coerceAtLeast(1L) }.sorted()
        val p95 = orderedElapsed[(ceil(orderedElapsed.size * 0.95).toInt() - 1).coerceAtLeast(0)]
        val mediaBitrate = samples.map { it.bytes.toLong() * 8_000L / duration }.average().toLong()
        // One segment covers the tail of the slowest already-observed fetch; a second segment
        // leaves a finite recovery margin.  This is a time-based calculation, not a magic count.
        val reserveMs = maxOf(2 * duration, p95 + duration)
        val needed = ceil(reserveMs.toDouble() / duration).toInt().coerceAtLeast(2)
        val required = needed.coerceAtMost(maxOf(1, availableSegments))
        val p95PerConnection = samples.map { it.bytes.toLong() * 8_000L / it.elapsedMs.coerceAtLeast(1L) }
            .sorted()[(ceil(samples.size * 0.05).toInt() - 1).coerceAtLeast(0)]
        return Decision(
            requiredSegments = required,
            requiredCacheMs = required * duration,
            downloadP95Ms = p95,
            mediaBitrateBps = mediaBitrate,
            deliverySustainable = p95PerConnection >= mediaBitrate,
        )
    }

    /** Why the proxy did or did not hand a localhost playlist to the player. */
    enum class StartupOutcome {
        /** The media playlist declares `#EXT-X-INDEPENDENT-SEGMENTS`: every segment decodes alone. */
        INDEPENDENT_SEGMENTS,

        /**
         * No independence declaration, so the only proof accepted is the inspector's own
         * `independentlyDecodable` evidence for the published run.
         *
         * A ready *count* is explicitly **not** proof: this outcome is only reachable from
         * [StartupEvidence].decodableStartVerified.
         */
        VERIFIED_BOOTSTRAP,

        /** The window resolved (or the budget elapsed) with no decodable start proven. */
        UNVERIFIED_BOOTSTRAP,

        /** The media playlist cannot be verified as a supported codec (fail-closed fallback). */
        UNSUPPORTED_CODEC,

        /** A recognized HLS shape that this fail-closed proxy deliberately does not rewrite. */
        UNSUPPORTED_PLAYLIST,

        /** Every queued segment of the window failed to download. */
        DOWNLOAD_FAILED,

        /** The bounded startup budget elapsed while at least one download was still in flight. */
        VERIFICATION_TIMEOUT,

        /** The proxy was stopped, superseded, or its generation changed during the preflight. */
        CANCELLED,

        /** The requested source is not an ordinary media playlist; no preflight was attempted. */
        NOT_APPLICABLE,
    }

    /** Everything the evidence decision is allowed to look at. */
    data class StartupEvidence(
        val targetDurationMs: Long,
        val independentSegments: Boolean,
        /**
         * True only when `MpegTsSegmentInspector.Bootstrap.independentlyDecodable` held for a
         * published run. Callers must compute this from the inspector, never from a count.
         */
        val decodableStartVerified: Boolean,
        val queued: Int,
        val ready: Int,
        val failed: Int,
        /**
         * Queued entries that can still resolve. This is deliberately the same quantity as [queued]:
         * the proxy cannot observe a dispatch boundary separately, and pretending otherwise would
         * make the all-failed check unreliable.
         */
        val unresolvedQueued: Int,
        /** True when the observed bootstrap is H.264 but no decodable start was proven yet. */
        val h264Observed: Boolean,
        /** True when a download for this session has completed at any point. */
        val progressObserved: Boolean,
        /** Longest elapsed time among segments whose download finished. */
        val observedDownloadMs: Long,
        /** True when every referenced segment of the window has resolved (ready or failed). */
        val windowResolved: Boolean,
        /** True when the session generation changed or the proxy was stopped. */
        val cancelled: Boolean,
    )

    /** The startup decision for the current evidence. */
    sealed interface StartupStatus {
        /** The player may be handed the localhost playlist. */
        data class Ready(val outcome: StartupOutcome) : StartupStatus

        /** Keep downloading until the preflight deadline; re-evaluate when new evidence arrives. */
        data class Waiting(val outcome: StartupOutcome, val missingEvidenceSegments: Int) : StartupStatus

        /** Stop and let the caller use the original source directly. */
        data class Fallback(val outcome: StartupOutcome) : StartupStatus
    }

    /**
     * Bounded startup wait.
     *
     * The cap is derived from the target duration and the measured download progress, never from a
     * fixed "wait N seconds". A slow but working segment therefore completes, while a dead source
     * still falls back fail-closed inside a known maximum, and an unverifiable bootstrap never
     * becomes a false success just because several segments arrived.
     */
    fun planStartup(budget: StartupBudget): StartupPlan {
        val duration = budget.targetDurationMs.coerceAtLeast(1L)
        val evidence = minimumEvidenceSegments(duration)
        val missing = (evidence - budget.readySegments).coerceAtLeast(0)
        if (budget.independentSegments && budget.readySegments >= 1) {
            return StartupPlan(
                satisfiedWithoutWait = true,
                reason = StartupOutcome.INDEPENDENT_SEGMENTS,
                capMs = duration,
                missingEvidenceSegments = 0,
            )
        }
        val perSegment = maxOf(budget.observedDownloadMs, if (budget.progressObserved) duration else 0L)
        // Three target durations cover one ordinary fetch plus a failed/unsupported range probe.
        // During the wait, recent byte progress can extend this soft cap, but never beyond the
        // global maximum below.
        val derived = 3 * duration + missing * perSegment
        return StartupPlan(
            satisfiedWithoutWait = false,
            reason = StartupOutcome.VERIFICATION_TIMEOUT,
            capMs = derived.coerceIn(MIN_STARTUP_CAP_MS, MAX_STARTUP_CAP_MS),
            missingEvidenceSegments = missing,
        )
    }

    /** Bounded wait budget for the startup preflight. */
    data class StartupBudget(
        val targetDurationMs: Long,
        val readySegments: Int,
        val referencedSegments: Int,
        val observedDownloadMs: Long,
        val progressObserved: Boolean,
        val independentSegments: Boolean,
    )

    data class StartupPlan(
        /** The preflight is allowed to return immediately. */
        val satisfiedWithoutWait: Boolean,
        val reason: StartupOutcome,
        /** Upper bound for this preflight; never an unconditional sleep. */
        val capMs: Long,
        /** Evidence segments still missing when the plan was computed. */
        val missingEvidenceSegments: Int,
    )

    /**
     * Minimum evidence before a reserve can be considered complete. Segment duration is the natural
     * unit: a 1 s window needs more fragments than a 6 s window to prove the same decoder bootstrap.
     * This is a *budget* input only; it never by itself verifies a bootstrap.
     */
    fun minimumEvidenceSegments(targetDurationMs: Long): Int = when {
        targetDurationMs <= 1_000L -> 4
        targetDurationMs <= 2_000L -> 3
        targetDurationMs <= 4_000L -> 2
        else -> 1
    }

    /**
     * The single evidence decision. [StartupStatus.Ready] is reachable only from an independence
     * declaration or from real decodable-start evidence.
     */
    fun decideStartup(
        evidence: StartupEvidence,
        deadlineElapsed: Boolean,
    ): StartupStatus {
        if (evidence.cancelled) return StartupStatus.Fallback(StartupOutcome.CANCELLED)
        if (evidence.independentSegments && evidence.ready >= 1) {
            return StartupStatus.Ready(StartupOutcome.INDEPENDENT_SEGMENTS)
        }
        if (evidence.decodableStartVerified) {
            return StartupStatus.Ready(StartupOutcome.VERIFIED_BOOTSTRAP)
        }
        if (evidence.queued == 0 && evidence.ready == 0 && evidence.unresolvedQueued == 0 && evidence.failed > 0) {
            return StartupStatus.Fallback(StartupOutcome.DOWNLOAD_FAILED)
        }
        if (evidence.windowResolved && evidence.ready == 0 && evidence.failed > 0) {
            return StartupStatus.Fallback(StartupOutcome.DOWNLOAD_FAILED)
        }
        if (evidence.windowResolved && evidence.failed == 0) {
            // Everything downloaded and still no decodable start: report it honestly instead of
            // publishing a partial GOP. H.264 that we recognise but cannot verify keeps the
            // dedicated reason so the report shows the difference from an unknown codec.
            return StartupStatus.Fallback(
                if (evidence.h264Observed) StartupOutcome.UNVERIFIED_BOOTSTRAP else StartupOutcome.UNSUPPORTED_CODEC,
            )
        }
        if (deadlineElapsed) {
            return StartupStatus.Fallback(
                if (evidence.h264Observed) StartupOutcome.UNVERIFIED_BOOTSTRAP else StartupOutcome.VERIFICATION_TIMEOUT,
            )
        }
        return StartupStatus.Waiting(
            outcome = StartupOutcome.VERIFICATION_TIMEOUT,
            missingEvidenceSegments = 0,
        )
    }

    /** Shortest startup cap; a verified single segment can still complete well inside this. */
    const val MIN_STARTUP_CAP_MS = 3_000L

    /**
     * Longest startup cap for the ordinary path. The preflight no longer blocks the first frame
     * (the engine starts direct playback in parallel), so the cap only bounds how long the
     * background verification may run before the proxy idea is abandoned for this open. It stays
     * above the derived cap of a common 2 s-target playlist (6 s): anything lower clamps the
     * derived value everywhere and a slow-but-valid segment plus its one range probe no longer
     * fits inside the budget.
     */
    const val MAX_STARTUP_CAP_MS = 8_000L
}
