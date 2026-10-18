package com.kantv.app.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GeckoObserverDiagnosticEvidenceTest {
    private fun fault() = JSONObject().apply {
        put("geckoAudioQueryJsTickCount", 0); put("geckoAudioQueryMaxJsTickGapMs", -1)
        put("geckoAudioRecoveryCount", 1); put("geckoAudioRetryRemainingMs", 15000)
        put("geckoAudioFirstFaultSeq", 179); put("geckoAudioFirstFaultEpoch", 2)
        put("geckoAudioFirstFaultNode", 1); put("geckoAudioFirstFaultStartMs", 10000)
        put("geckoAudioFirstFaultSyncCallMs", 0); put("geckoAudioFirstFaultReactionElapsedMs", 1137)
        put("geckoAudioFirstFaultTimeoutLateMs", 133); put("geckoAudioFirstFaultJsTickCount", 0)
        put("geckoAudioFirstFaultMaxJsTickGapMs", -1); put("geckoAudioFirstFaultState", "RESOLVED")
        put("geckoAudioFirstFaultDisposition", "TIMEOUT_FUSE")
        put("geckoAudioFirstFaultClockStatus", "INSUFFICIENT_TIMEOUT")
    }
    @Test fun schema20MissingFieldsAreUnknownAndHostClockIsIndependent() {
        assertEquals(GeckoObserverDiagnosticEvidence(), GeckoObserverDiagnosticEvidence.fromBridge(JSONObject(), true))
        assertEquals(12L, GeckoObserverDiagnosticEvidence.fromBridge(JSONObject(), true, 12).geckoBridgePostLagMs)
        assertEquals(GeckoObserverDiagnosticEvidence(), GeckoObserverDiagnosticEvidence.fromBridge(fault(), false, 12))
    }
    @Test fun firstFaultAndZeroTickRemainExplicitWhileNewQueryHasDifferentIdentity() {
        val state = fault().put("geckoAudioQuerySeq", 180).put("privatePayload", "https://private.invalid/secret")
        val result = GeckoObserverDiagnosticEvidence.fromBridge(state, true, 13)
        assertEquals(179L, result.geckoAudioFirstFaultSeq)
        assertEquals(1137L, result.geckoAudioFirstFaultReactionElapsedMs)
        assertEquals(0L, result.geckoAudioFirstFaultJsTickCount)
        assertEquals(-1L, result.geckoAudioFirstFaultMaxJsTickGapMs)
        assertEquals("TIMEOUT_FUSE", result.geckoAudioFirstFaultDisposition)
        assertFalse(result.toFields().toString().contains("private"))
    }
    @Test fun malformedClockBooleanFractionEnumAndOrphanFaultFailClosed() {
        for ((key, value) in listOf("geckoAudioFirstFaultSeq" to true,
            "geckoAudioFirstFaultEpoch" to 0.5, "geckoAudioFirstFaultSeq" to 9007199254740992.0,
            "geckoAudioFirstFaultState" to "private", "geckoAudioFirstFaultMaxJsTickGapMs" to 0)) {
            val invalid = fault().put(key, value)
            assertEquals(key, -1L, GeckoObserverDiagnosticEvidence.fromBridge(invalid, true).geckoAudioFirstFaultSeq)
        }
    }
    @Test fun groupConstructorRejectsUnknownTickGapCaptureIdentityAndExhaustedRetry() {
        val invalid = listOf<() -> Unit>(
            { GeckoObserverDiagnosticEvidence(geckoAudioQueryJsTickCount = 0, geckoAudioQueryMaxJsTickGapMs = 0) },
            { GeckoObserverDiagnosticEvidence(geckoCaptureApiCallMs = 1) },
            { GeckoObserverDiagnosticEvidence(geckoCaptureSequence = 0) },
            { GeckoObserverDiagnosticEvidence(geckoAudioRecoveryCount = 2, geckoAudioRetryRemainingMs = 0) },
        )
        for (create in invalid) try { create(); fail("invalid observer group accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun bufferingFactsSerializeAsTheirOwnKindsWithoutRelabelingWatchdog() {
        for (kind in listOf(DiagnosticEntryKind.BUFFERING_STARTED, DiagnosticEntryKind.BUFFERING_ENDED,
            DiagnosticEntryKind.WATCHDOG_TRIGGERED)) {
            assertEquals(kind.name, DiagnosticLogEntry(kind, 1, DiagnosticPlaybackValues()).toJson().getString("kind"))
        }
    }
    @Test fun actualSchema21WireContainsBoundedHostAndPreservedFaultWithoutTouchingTrace() {
        val observer = GeckoObserverDiagnosticEvidence.fromBridge(fault(), true, 13).copy(
            geckoCaptureSequence = 8, geckoCaptureApiCallMs = 2, geckoCaptureIssueToListenerMs = 77,
            geckoCaptureClassifyMs = 3, geckoCapturePostLagMs = 4,
        )
        val trace = GeckoTraceDiagnosticEvidence(geckoAudioQuerySeq = 180, geckoAudioQueryEpoch = 2,
            geckoAudioQueryNode = 1, geckoAudioQueryState = "PENDING", geckoAudioQueryStartMs = 30000,
            geckoAudioInFlight = true)
        val sample = DiagnosticLogEntry(DiagnosticEntryKind.SAMPLE, 1,
            DiagnosticPlaybackValues(geckoTrace = trace, geckoObserver = observer)).toJson()
        assertEquals(180L, sample.getLong("geckoAudioQuerySeq"))
        assertEquals(179L, sample.getLong("geckoAudioFirstFaultSeq"))
        assertEquals(77L, sample.getLong("geckoCaptureIssueToListenerMs"))
        // Compatibility shape for legacy v21: current producer emits v22. The new safety tests
        // separately export complete, unmodified v22 wire entries.
        sample.remove("visualSampleBlockedReason")
        val output = File("build/outputs/schema21-wire-fixtures").apply { mkdirs() }
        File(output, "gecko-observer-fault-and-host.json").writeText(sample.toString())
        File(output, "gecko-observer-unknown.json").writeText(
            DiagnosticLogEntry(DiagnosticEntryKind.SAMPLE, 1, DiagnosticPlaybackValues()).toJson().apply {
                remove("visualSampleBlockedReason")
            }.toString())
    }
}
