package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchStartupPolicyTest {
    @Test fun fastSegmentsNeedOnlyTwoSegmentReserve() {
        val decision = PrefetchStartupPolicy.decide(5_000, List(5) { PrefetchStartupPolicy.Sample(5_000_000, 1_000) }, 8)
        assertEquals(2, decision.requiredSegments)
        assertTrue(requireNotNull(decision.deliverySustainable))
    }

    @Test fun slowTailExpandsReserveByTimeAndNeverPastAvailableWindow() {
        val decision = PrefetchStartupPolicy.decide(5_000, listOf(
            PrefetchStartupPolicy.Sample(5_000_000, 1_000),
            PrefetchStartupPolicy.Sample(5_000_000, 21_000),
        ), 4)
        assertEquals(4, decision.requiredSegments)
        assertFalse(requireNotNull(decision.deliverySustainable))
    }

    @Test fun explicitTestOverrideRemainsDeterministic() {
        assertEquals(3, PrefetchStartupPolicy.decide(5_000, emptyList(), 8, overrideSegments = 3).requiredSegments)
    }

    private fun evidence(
        independentSegments: Boolean = false,
        decodableStartVerified: Boolean = false,
        queued: Int = 0,
        ready: Int = 2,
        failed: Int = 0,
        unresolvedQueued: Int = 0,
        h264Observed: Boolean = true,
        windowResolved: Boolean = false,
        targetDurationMs: Long = 2_000L,
        cancelled: Boolean = false,
    ) = PrefetchStartupPolicy.StartupEvidence(
        targetDurationMs = targetDurationMs,
        independentSegments = independentSegments,
        decodableStartVerified = decodableStartVerified,
        queued = queued,
        ready = ready,
        failed = failed,
        unresolvedQueued = unresolvedQueued,
        h264Observed = h264Observed,
        progressObserved = ready > 0,
        observedDownloadMs = 1_800L,
        windowResolved = windowResolved,
        cancelled = cancelled,
    )

    @Test fun readySegmentCountIsNeverAcceptedAsAVerifiedBootstrap() {
        // The dangerous shape: plenty of ready segments, but the inspector never proved a decodable
        // start. This must stay waiting, not become a success.
        val status = PrefetchStartupPolicy.decideStartup(
            evidence(ready = 5, queued = 1),
            deadlineElapsed = false,
        )
        assertTrue(status is PrefetchStartupPolicy.StartupStatus.Waiting)

        // And once the window resolves with no proof, it is an honest fallback.
        val fallback = PrefetchStartupPolicy.decideStartup(
            evidence(ready = 5, queued = 0, windowResolved = true),
            deadlineElapsed = false,
        )
        assertEquals(
            PrefetchStartupPolicy.StartupOutcome.UNVERIFIED_BOOTSTRAP,
            (fallback as PrefetchStartupPolicy.StartupStatus.Fallback).outcome,
        )
    }

    @Test fun onlyIndependenceOrRealInspectorEvidenceStartsTheLocalPlaylist() {
        val independent = PrefetchStartupPolicy.decideStartup(
            evidence(independentSegments = true, ready = 1, decodableStartVerified = false),
            deadlineElapsed = false,
        )
        assertEquals(
            PrefetchStartupPolicy.StartupOutcome.INDEPENDENT_SEGMENTS,
            (independent as PrefetchStartupPolicy.StartupStatus.Ready).outcome,
        )

        val verified = PrefetchStartupPolicy.decideStartup(
            evidence(ready = 1, decodableStartVerified = true),
            deadlineElapsed = false,
        )
        assertEquals(
            PrefetchStartupPolicy.StartupOutcome.VERIFIED_BOOTSTRAP,
            (verified as PrefetchStartupPolicy.StartupStatus.Ready).outcome,
        )

        // Independence without a single downloaded segment is still not a start.
        assertTrue(
            PrefetchStartupPolicy.decideStartup(
                evidence(independentSegments = true, ready = 0, queued = 2),
                deadlineElapsed = false,
            ) is PrefetchStartupPolicy.StartupStatus.Waiting,
        )
    }

    @Test fun aSessionThatStopsDuringThePreflightIsCancelledNotAFailedDownload() {
        val status = PrefetchStartupPolicy.decideStartup(
            evidence(ready = 0, queued = 3, cancelled = true),
            deadlineElapsed = true,
        )
        assertEquals(
            PrefetchStartupPolicy.StartupOutcome.CANCELLED,
            (status as PrefetchStartupPolicy.StartupStatus.Fallback).outcome,
        )
    }

    @Test fun everyQueuedSegmentFailingIsARealDownloadFailure() {
        val status = PrefetchStartupPolicy.decideStartup(
            evidence(ready = 0, queued = 0, failed = 4, unresolvedQueued = 0),
            deadlineElapsed = false,
        )
        assertEquals(
            PrefetchStartupPolicy.StartupOutcome.DOWNLOAD_FAILED,
            (status as PrefetchStartupPolicy.StartupStatus.Fallback).outcome,
        )
    }

    @Test fun anElapsedBudgetReportsTimeoutForUnknownCodecsAndVerificationFailureForH264() {
        val unknown = PrefetchStartupPolicy.decideStartup(
            evidence(ready = 1, queued = 1, h264Observed = false),
            deadlineElapsed = true,
        )
        assertEquals(
            PrefetchStartupPolicy.StartupOutcome.VERIFICATION_TIMEOUT,
            (unknown as PrefetchStartupPolicy.StartupStatus.Fallback).outcome,
        )

        val h264 = PrefetchStartupPolicy.decideStartup(
            evidence(ready = 1, queued = 1, h264Observed = true),
            deadlineElapsed = true,
        )
        assertEquals(
            PrefetchStartupPolicy.StartupOutcome.UNVERIFIED_BOOTSTRAP,
            (h264 as PrefetchStartupPolicy.StartupStatus.Fallback).outcome,
        )
    }

    @Test fun theStartupCapIsDerivedBoundedAndGrowsWithMissingEvidence() {
        val immediate = PrefetchStartupPolicy.planStartup(
            PrefetchStartupPolicy.StartupBudget(
                targetDurationMs = 2_000L,
                readySegments = 0,
                referencedSegments = 5,
                observedDownloadMs = -1L,
                progressObserved = false,
                independentSegments = false,
            ),
        )
        // No sample yet, so the cap is 3 x target duration: enough for an ordinary fetch plus a
        // failed range probe, still well below the hard ceiling.
        assertEquals(6_000L, immediate.capMs)
        assertTrue(immediate.capMs >= PrefetchStartupPolicy.MIN_STARTUP_CAP_MS)
        assertFalse(immediate.satisfiedWithoutWait)

        val slow = PrefetchStartupPolicy.planStartup(
            PrefetchStartupPolicy.StartupBudget(
                targetDurationMs = 6_000L,
                readySegments = 0,
                referencedSegments = 5,
                observedDownloadMs = 5_000L,
                progressObserved = true,
                independentSegments = false,
            ),
        )
        assertEquals(8_000L, slow.capMs)
        assertTrue(slow.capMs <= PrefetchStartupPolicy.MAX_STARTUP_CAP_MS)

        val capped = PrefetchStartupPolicy.planStartup(
            PrefetchStartupPolicy.StartupBudget(
                targetDurationMs = 20_000L,
                readySegments = 0,
                referencedSegments = 5,
                observedDownloadMs = 60_000L,
                progressObserved = true,
                independentSegments = false,
            ),
        )
        assertEquals(PrefetchStartupPolicy.MAX_STARTUP_CAP_MS, capped.capMs)
    }

    @Test fun aSlowButValidSegmentIsAllowedToFinishInsideTheCap() {
        // A 1.8 s download of a 2 s segment is the exact "slightly slower than the old 1.5 s probe"
        // shape: the derived cap must be large enough for it to complete.
        val plan = PrefetchStartupPolicy.planStartup(
            PrefetchStartupPolicy.StartupBudget(
                targetDurationMs = 2_000L,
                readySegments = 0,
                referencedSegments = 5,
                observedDownloadMs = 1_800L,
                progressObserved = true,
                independentSegments = false,
            ),
        )
        assertTrue("cap must exceed the observed download", plan.capMs > 1_800L)
        assertEquals(3, plan.missingEvidenceSegments)
    }
}
