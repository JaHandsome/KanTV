package com.kantv.app.diagnostics

import org.junit.Test
import java.io.File

class Schema19ProducerWireTest {
    @Test fun observedTickAndDocumentResourcesSurviveAppSerialization() {
        val evidence = GeckoTraceDiagnosticEvidence(
            geckoAudioQuerySeq = 72, geckoAudioQueryNode = 1, geckoAudioQueryEpoch = 7,
            geckoAudioQueryState = "RESOLVED", geckoAudioQueryDisposition = "IGNORED_TIMEOUT_FUSE",
            geckoAudioQueryStartMs = 1_000, geckoAudioSyncCallMs = 1,
            geckoAudioReactionElapsedMs = 2_259, geckoAudioTimeoutLateMs = 1_255,
            geckoAudioQueryRvfcCount = 0, geckoAudioQueryMaxRvfcGapMs = 2_259,
            geckoBridgeLastTickDurationMs = 2_260, geckoBridgeLastTickMaxPhaseCode = "LOGIN_SCAN",
            geckoBridgeLastTickMaxPhaseDurationMs = 2_259, geckoBridgeLastTickScanNodes = 2_800,
            geckoAudioQueryTickDurationMs = 2_260, geckoAudioQueryTickTailDurationMs = 2_259,
            geckoAudioQueryTickMaxPhaseCode = "LOGIN_SCAN",
            geckoAudioQueryTickMaxPhaseDurationMs = 2_259, geckoAudioQueryTickScanNodes = 2_800,
            geckoStartupObserverTickCount = 5, geckoStartupObserverTotalDurationMs = 2_300,
            geckoStartupObserverMaxTickDurationMs = 2_260,
            geckoStartupResourceObservationStatus = "COMPLETE", geckoStartupResourceCount = 4,
            geckoStartupResourceMaxDurationMs = 9_000, geckoStartupResourceMaxResponseWaitMs = 7_000,
            geckoStartupResourceMaxDownloadMs = 2_000, geckoStartupResourceDetailedCount = 2,
        )
        val output = File("build/outputs/schema19-wire-fixtures").apply { mkdirs() }
        File(output, "gecko-own-tick-and-document-resources.json").writeText(
            DiagnosticLogEntry(DiagnosticEntryKind.SAMPLE, 1, DiagnosticPlaybackValues(geckoTrace = evidence))
                .toJson().toString(),
        )
        File(output, "gecko-unknown-additions.json").writeText(
            DiagnosticLogEntry(DiagnosticEntryKind.SAMPLE, 1, DiagnosticPlaybackValues()).toJson().toString(),
        )
    }
}
