package com.kantv.app.diagnostics

import org.junit.Test
import java.io.File

class Schema20ProducerWireTest {
    @Test fun pendingWindowAndLongestCandidateEntrySurviveSerialization() {
        val trace = GeckoTraceDiagnosticEvidence(
            geckoAudioQuerySeq = 57, geckoAudioQueryNode = 2, geckoAudioQueryEpoch = 3,
            geckoAudioQueryState = "RESOLVED", geckoAudioQueryDisposition = "IGNORED_TIMEOUT_FUSE",
            geckoAudioQueryStartMs = 1_000, geckoAudioSyncCallMs = 0,
            geckoAudioReactionElapsedMs = 2_449, geckoAudioTimeoutLateMs = 1_444,
            geckoAudioQueryRvfcCount = 0, geckoAudioQueryMaxRvfcGapMs = 2_449,
            geckoAudioQueryTickDurationMs = 4, geckoAudioQueryTickTailDurationMs = 3,
            geckoAudioQueryTickMaxPhaseCode = "STATE_BUILD", geckoAudioQueryTickMaxPhaseDurationMs = 2,
            geckoAudioQueryTickScanNodes = 0,
            geckoAudioQueryObservedWorkCount = 3, geckoAudioQueryObservedWorkTotalMs = 2_009,
            geckoAudioQueryObservedWorkMaxMs = 2_000,
            geckoAudioQueryObservedWorkMaxPhaseCode = "LOGIN_SCAN",
            geckoAudioQueryObservedWorkMaxPhaseMs = 1_999,
            geckoAudioQueryObservedWorkMaxScanNodes = 2_800,
            geckoStartupResourceObservationStatus = "COMPLETE", geckoStartupResourceCount = 4,
            geckoStartupResourceDetailedCount = 1, geckoStartupResourceMaxDurationMs = 1_181,
            geckoStartupResourceMaxResponseWaitMs = 247, geckoStartupResourceMaxDownloadMs = 137,
            geckoStartupResourceScannedCount = 504, geckoStartupResourceFilteredCount = 500,
            geckoStartupResourceMaxStartMs = 500, geckoStartupResourceMaxEndMs = 1_681,
            geckoStartupResourceMaxInitiatorCode = "XMLHTTPREQUEST",
        )
        // Legacy v20 compatibility shape; complete current v22 entries are exported separately.
        val output = File("build/outputs/schema20-wire-fixtures").apply { mkdirs() }
        File(output, "gecko-window-and-candidate-entry.json").writeText(
            DiagnosticLogEntry(DiagnosticEntryKind.SAMPLE, 1, DiagnosticPlaybackValues(geckoTrace = trace))
                .toJson().apply {
                    GeckoObserverDiagnosticEvidence().toFields().keys.forEach { remove(it) }
                    remove("visualSampleBlockedReason")
                }.toString(),
        )
        File(output, "gecko-window-unknown.json").writeText(
            DiagnosticLogEntry(DiagnosticEntryKind.SAMPLE, 1, DiagnosticPlaybackValues()).toJson().apply {
                    GeckoObserverDiagnosticEvidence().toFields().keys.forEach { remove(it) }
                    remove("visualSampleBlockedReason")
                }.toString(),
        )
    }
}
