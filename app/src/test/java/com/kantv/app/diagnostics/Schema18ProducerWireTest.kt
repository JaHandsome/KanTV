package com.kantv.app.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class Schema18ProducerWireTest {
    private fun emit(name: String, values: DiagnosticPlaybackValues) {
        val output = File("build/outputs/schema18-wire-fixtures").apply { mkdirs() }
        File(output, "$name.json").writeText(
            DiagnosticLogEntry(DiagnosticEntryKind.SAMPLE, 1, values).toJson().toString(),
        )
    }

    @Test fun matchedRequestSurvivesActualAppSerialization() {
        val request = M3uLoadDiagnosticEvidence(
            nativeLoadLastOutcome = "COMPLETED", nativeLoadLastTrackType = "DEFAULT",
            nativeLoadLastStartElapsedMs = 100, nativeLoadLastEndElapsedMs = 800,
            nativeLoadLastMediaStartMs = 5_000, nativeLoadLastMediaEndMs = 10_000,
            nativeLoadLastRetryCount = 0, nativeLoadLastBytes = 4_000,
            nativeMediaRequestMatchStatus = "MATCHED", nativeMediaRequestCoverage = "REQUEST_ONLY",
            nativeMediaRequestOutcome = "CLOSED_EOF", nativeMediaRequestOpenDurationUs = 16_000,
            nativeMediaRequestReadDurationUs = 680_000, nativeMediaRequestCloseDurationUs = 180,
            nativeMediaRequestReadCallCount = 4, nativeMediaRequestBytes = 4_000,
            nativeMediaRequestEofSeen = true,
        )
        emit("matched-native", DiagnosticPlaybackValues(m3uLoad = request))
    }

    @Test fun queryWithCallbacksAndUnknownOptionalGroupPreservesTheCore() {
        val raw = JSONObject().put("geckoAudioQuerySeq", 1).put("geckoAudioQueryNode", 2)
            .put("geckoAudioQueryEpoch", 3).put("geckoAudioQueryStartMs", 1_000)
            .put("geckoAudioQueryState", "PENDING").put("geckoAudioQueryDisposition", "NONE")
            .put("geckoAudioInFlight", true).put("geckoAudioSyncCallMs", 0)
            .put("geckoAudioReactionElapsedMs", -1).put("geckoAudioTimeoutLateMs", -1)
            .put("geckoDocumentNowMs", 1_200).put("geckoDocumentTimeOriginMs", 1_729_000_000_000)
            .put("geckoAudioQueryRvfcCount", 4).put("geckoAudioQueryMaxRvfcGapMs", 39)
        val observed = GeckoTraceDiagnosticEvidence.fromBridge(raw, true, 1_729_000_001_222)
        assertEquals("PENDING", observed.geckoAudioQueryState)
        assertEquals(4L, observed.geckoAudioQueryRvfcCount)
        emit("gecko-observed", DiagnosticPlaybackValues(geckoTrace = observed))
        raw.remove("geckoAudioQueryRvfcCount")
        raw.remove("geckoAudioQueryMaxRvfcGapMs")
        val legacy = GeckoTraceDiagnosticEvidence.fromBridge(raw, true, 1_729_000_001_222)
        assertEquals("PENDING", legacy.geckoAudioQueryState)
        assertEquals(-1L, legacy.geckoAudioQueryRvfcCount)
        emit("gecko-optional-unknown", DiagnosticPlaybackValues(geckoTrace = legacy))
    }
}
