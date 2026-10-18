package com.kantv.app.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class GeckoTraceDiagnosticEvidenceTest {
    private fun pending() = JSONObject().apply {
        put("geckoNodeBoundMs", 0)
        put("geckoFirstRvfcMs", 10)
        put("geckoFirstRvfcPtsUs", 0)
        put("geckoAttachLate", false)
        put("geckoAudioQuerySeq", 1)
        put("geckoAudioQueryEpoch", 2)
        put("geckoAudioQueryNode", 3)
        put("geckoAudioQueryState", "PENDING")
        put("geckoAudioQueryStartMs", 20)
        put("geckoAudioSyncCallMs", 0)
        put("geckoAudioReactionElapsedMs", -1)
        put("geckoAudioTimeoutLateMs", -1)
        put("geckoAudioInFlight", true)
        put("geckoAudioQueryDisposition", "NONE")
        put("geckoAudioQueryRvfcCount", 0)
        put("geckoAudioQueryMaxRvfcGapMs", -1)
        put("geckoDocumentNowMs", 12)
        put("geckoDocumentTimeOriginMs", 1_800_000_000_000L)
    }

    @Test fun zeroFirstPtsAndDocumentTimestampsArePreserved() {
        val result = GeckoTraceDiagnosticEvidence.fromBridge(pending(), true, 1_800_000_000_100L)
        assertEquals(0L, result.geckoNodeBoundMs)
        assertEquals(0L, result.geckoFirstRvfcPtsUs)
        assertEquals(20L, result.geckoAudioQueryStartMs)
        assertEquals(12L, result.geckoDocumentNowMs)
        assertEquals(1_800_000_000_000L, result.geckoDocumentTimeOriginMs)
        assertEquals(1_800_000_000_100L, result.geckoStateReceivedEpochMs)
        assertEquals(0L, result.geckoAudioQueryRvfcCount)
        assertEquals(-1L, result.geckoAudioQueryMaxRvfcGapMs)
        assertEquals("PENDING", result.geckoAudioQueryState)
    }

    @Test fun documentClockFieldsKeepUnknownSeparateAndRejectMalformedValues() {
        val missing = GeckoTraceDiagnosticEvidence.fromBridge(JSONObject(), true, -1)
        assertEquals(-1L, missing.geckoDocumentNowMs)
        assertEquals(-1L, missing.geckoDocumentTimeOriginMs)
        assertEquals(-1L, missing.geckoStateReceivedEpochMs)
        val malformed = pending().apply {
            put("geckoDocumentNowMs", "12")
            put("geckoDocumentTimeOriginMs", 1.5)
        }
        val result = GeckoTraceDiagnosticEvidence.fromBridge(malformed, true, Long.MAX_VALUE)
        assertEquals(-1L, result.geckoDocumentNowMs)
        assertEquals(-1L, result.geckoDocumentTimeOriginMs)
        assertEquals(-1L, result.geckoStateReceivedEpochMs)
        assertEquals("bad clock anchors do not discard valid query trace", "PENDING", result.geckoAudioQueryState)
    }

    @Test fun nativeMirrorAndLegacyStayInapplicable() {
        assertEquals(GeckoTraceDiagnosticEvidence(), GeckoTraceDiagnosticEvidence.fromBridge(pending(), false))
        assertEquals("NOT_APPLICABLE", GeckoTraceDiagnosticEvidence.fromBridge(JSONObject(), true).geckoAudioQueryState)
    }

    @Test fun malformedQueryFailsClosedWithoutDiscardingStartupEvidence() {
        for (field in listOf("geckoAudioQuerySeq", "geckoAudioQueryEpoch", "geckoAudioQueryNode",
            "geckoAudioQueryStartMs", "geckoAudioSyncCallMs", "geckoAudioReactionElapsedMs",
            "geckoAudioTimeoutLateMs")) {
            for (invalid in listOf<Any>("20", true, 1.5, Long.MAX_VALUE, -2)) {
                val result = GeckoTraceDiagnosticEvidence.fromBridge(pending().put(field, invalid), true)
                assertEquals("NOT_APPLICABLE", result.geckoAudioQueryState)
                assertEquals(-1L, result.geckoAudioQuerySeq)
                assertFalse(result.geckoAudioInFlight)
                assertEquals(10L, result.geckoFirstRvfcMs)
            }
        }
        for (field in listOf("geckoAudioQueryRvfcCount", "geckoAudioQueryMaxRvfcGapMs")) {
            for (invalid in listOf<Any>("20", true, 1.5, Long.MAX_VALUE, -2)) {
                val result = GeckoTraceDiagnosticEvidence.fromBridge(pending().put(field, invalid), true)
                assertEquals("malformed optional rVFC metric must not erase schema 17 query core",
                    "PENDING", result.geckoAudioQueryState)
                assertEquals(1L, result.geckoAudioQuerySeq)
                assertEquals(-1L, result.geckoAudioQueryRvfcCount)
                assertEquals(-1L, result.geckoAudioQueryMaxRvfcGapMs)
            }
        }
        for (field in listOf("geckoAudioInFlight", "geckoAudioQueryState", "geckoAudioQueryDisposition")) {
            assertEquals("NOT_APPLICABLE", GeckoTraceDiagnosticEvidence.fromBridge(
                pending().put(field, "https://private.invalid/token"), true,
            ).geckoAudioQueryState)
        }
    }

    @Test fun noRequestStateCannotRetainActiveQueryIdentity() {
        val result = GeckoTraceDiagnosticEvidence.fromBridge(pending().put("geckoAudioQueryState", "NOT_STARTED"), true)
        assertEquals("NOT_APPLICABLE", result.geckoAudioQueryState)
        assertEquals(-1L, result.geckoAudioQuerySeq)
    }

    @Test fun schema17QueryKeepsCoreEvidenceWhenSchema18MetricsAreAbsent() {
        val legacy = pending().apply {
            remove("geckoAudioQueryRvfcCount")
            remove("geckoAudioQueryMaxRvfcGapMs")
        }
        val samples = listOf(
            GeckoTraceDiagnosticEvidence.fromBridge(legacy, true),
            GeckoTraceDiagnosticEvidence.fromBridge(legacy.apply {
                put("geckoAudioQueryState", "RESOLVED")
                put("geckoAudioReactionElapsedMs", 60)
                put("geckoAudioInFlight", false)
                put("geckoAudioQueryDisposition", "APPLIED")
            }, true),
        )
        for (result in samples) {
            assertEquals(1L, result.geckoAudioQuerySeq)
            assertEquals(2L, result.geckoAudioQueryEpoch)
            assertEquals(3L, result.geckoAudioQueryNode)
            assertEquals(-1L, result.geckoAudioQueryRvfcCount)
            assertEquals(-1L, result.geckoAudioQueryMaxRvfcGapMs)
        }
        assertEquals("PENDING", samples[0].geckoAudioQueryState)
        assertEquals("RESOLVED", samples[1].geckoAudioQueryState)
    }

    @Test fun malformedRvfcMetricGroupBecomesUnknownWithoutDiscardingQueryCore() {
        val malformed = listOf(
            pending().put("geckoAudioQueryRvfcCount", 1_000_001),
            pending().put("geckoAudioQueryRvfcCount", -2),
            pending().put("geckoAudioQueryRvfcCount", -1).put("geckoAudioQueryMaxRvfcGapMs", 50),
            pending().put("geckoAudioQueryRvfcCount", 1).put("geckoAudioQueryMaxRvfcGapMs", 86_400_001),
            pending().put("geckoAudioQueryRvfcCount", "1").put("geckoAudioQueryMaxRvfcGapMs", 0),
        )
        for (state in malformed) {
            val result = GeckoTraceDiagnosticEvidence.fromBridge(state, true)
            assertEquals("PENDING", result.geckoAudioQueryState)
            assertEquals(1L, result.geckoAudioQuerySeq)
            assertEquals(2L, result.geckoAudioQueryEpoch)
            assertEquals(3L, result.geckoAudioQueryNode)
            assertEquals(-1L, result.geckoAudioQueryRvfcCount)
            assertEquals(-1L, result.geckoAudioQueryMaxRvfcGapMs)
        }
    }

    @Test fun lateSettlementRetainsOriginalQueryIdentityAndFuseDecision() {
        val result = GeckoTraceDiagnosticEvidence.fromBridge(pending().apply {
            put("geckoAudioQueryState", "REJECTED")
            put("geckoAudioReactionElapsedMs", 2400)
            put("geckoAudioTimeoutLateMs", 1376)
            put("geckoAudioInFlight", false)
            put("geckoAudioQueryDisposition", "IGNORED_TIMEOUT_FUSE")
            put("geckoAudioQueryRvfcCount", 7)
            put("geckoAudioQueryMaxRvfcGapMs", 1001)
        }, true)
        assertEquals(3L, result.geckoAudioQueryNode)
        assertEquals(2400L, result.geckoAudioReactionElapsedMs)
        assertEquals(1376L, result.geckoAudioTimeoutLateMs)
        assertEquals(7L, result.geckoAudioQueryRvfcCount)
        assertEquals(1001L, result.geckoAudioQueryMaxRvfcGapMs)
        assertFalse(result.geckoAudioInFlight)
    }

    @Test fun publicConstructorRejectsInvalidRangeEnumOrQueryIdentity() {
        val invalid = listOf<() -> GeckoTraceDiagnosticEvidence>(
            { GeckoTraceDiagnosticEvidence(geckoFirstRvfcMs = Long.MAX_VALUE) },
            { GeckoTraceDiagnosticEvidence(geckoAudioQueryState = "private-token") },
            { GeckoTraceDiagnosticEvidence(geckoAudioQuerySeq = 1) },
            { GeckoTraceDiagnosticEvidence(geckoAudioQueryState = "PENDING") },
            { GeckoTraceDiagnosticEvidence(geckoAudioQueryRvfcCount = 0) },
            { GeckoTraceDiagnosticEvidence(geckoAudioQueryRvfcCount = 1_000_001) },
            { GeckoTraceDiagnosticEvidence(geckoAudioQueryMaxRvfcGapMs = 86_400_001) },
            { GeckoTraceDiagnosticEvidence(geckoDocumentNowMs = Long.MAX_VALUE) },
        )
        for (create in invalid) {
            try { create(); fail("invalid trace accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    private fun measured() = pending().apply {
        put("geckoBridgeLastTickDurationMs", 2260)
        put("geckoBridgeLastTickMaxPhaseCode", "LOGIN_SCAN")
        put("geckoBridgeLastTickMaxPhaseDurationMs", 2259)
        put("geckoBridgeLastTickScanNodes", 2800)
        put("geckoAudioQueryTickDurationMs", 2260)
        put("geckoAudioQueryTickTailDurationMs", 2259)
        put("geckoAudioQueryTickMaxPhaseCode", "LOGIN_SCAN")
        put("geckoAudioQueryTickMaxPhaseDurationMs", 2259)
        put("geckoAudioQueryTickScanNodes", 2800)
        put("geckoStartupObserverTickCount", 3)
        put("geckoStartupObserverTotalDurationMs", 9)
        put("geckoStartupObserverMaxTickDurationMs", 9)
        put("geckoStartupResourceObservationStatus", "COMPLETE")
        put("geckoStartupResourceCount", 7)
        put("geckoStartupResourceMaxDurationMs", 2000)
        put("geckoStartupResourceMaxResponseWaitMs", 1900)
        put("geckoStartupResourceMaxDownloadMs", 100)
        put("geckoStartupResourceDetailedCount", 2)
    }

    @Test fun schema19PhaseAndDocumentResourceGroupsPreserveMeasuredValues() {
        val result = GeckoTraceDiagnosticEvidence.fromBridge(measured(), true)
        assertEquals(2260L, result.geckoBridgeLastTickDurationMs)
        assertEquals("LOGIN_SCAN", result.geckoBridgeLastTickMaxPhaseCode)
        assertEquals(2259L, result.geckoBridgeLastTickMaxPhaseDurationMs)
        assertEquals(2800L, result.geckoBridgeLastTickScanNodes)
        assertEquals(2260L, result.geckoAudioQueryTickDurationMs)
        assertEquals(2259L, result.geckoAudioQueryTickTailDurationMs)
        assertEquals("LOGIN_SCAN", result.geckoAudioQueryTickMaxPhaseCode)
        assertEquals(2259L, result.geckoAudioQueryTickMaxPhaseDurationMs)
        assertEquals(2800L, result.geckoAudioQueryTickScanNodes)
        assertEquals(3L, result.geckoStartupObserverTickCount)
        assertEquals(9L, result.geckoStartupObserverTotalDurationMs)
        assertEquals(9L, result.geckoStartupObserverMaxTickDurationMs)
        assertEquals("COMPLETE", result.geckoStartupResourceObservationStatus)
        assertEquals(7L, result.geckoStartupResourceCount)
        assertEquals(2000L, result.geckoStartupResourceMaxDurationMs)
        assertEquals(1900L, result.geckoStartupResourceMaxResponseWaitMs)
        assertEquals(100L, result.geckoStartupResourceMaxDownloadMs)
        assertEquals(2L, result.geckoStartupResourceDetailedCount)
    }

    @Test fun malformedNewGroupCannotEraseSchema18QueryOrOtherNewGroups() {
        val groups = listOf(
            listOf("geckoBridgeLastTickDurationMs", "geckoBridgeLastTickMaxPhaseDurationMs", "geckoBridgeLastTickScanNodes"),
            listOf("geckoAudioQueryTickDurationMs", "geckoAudioQueryTickTailDurationMs",
                "geckoAudioQueryTickMaxPhaseDurationMs", "geckoAudioQueryTickScanNodes"),
            listOf("geckoStartupObserverTickCount", "geckoStartupObserverTotalDurationMs", "geckoStartupObserverMaxTickDurationMs"),
            listOf("geckoStartupResourceCount", "geckoStartupResourceMaxDurationMs", "geckoStartupResourceMaxResponseWaitMs",
                "geckoStartupResourceMaxDownloadMs", "geckoStartupResourceDetailedCount"),
        )
        for ((groupIndex, fields) in groups.withIndex()) for (field in fields) {
            for (invalid in listOf<Any>("20", true, 1.5, -2, Long.MAX_VALUE)) {
                val result = GeckoTraceDiagnosticEvidence.fromBridge(measured().put(field, invalid), true)
                assertEquals("PENDING", result.geckoAudioQueryState)
                assertEquals(1L, result.geckoAudioQuerySeq)
                assertEquals(0L, result.geckoAudioQueryRvfcCount)
                assertEquals(-1L, result.geckoAudioQueryMaxRvfcGapMs)
                assertEquals(if (groupIndex == 0) -1L else 2260L, result.geckoBridgeLastTickDurationMs)
                assertEquals(if (groupIndex == 1) -1L else 2260L, result.geckoAudioQueryTickDurationMs)
                assertEquals(if (groupIndex == 2) -1L else 3L, result.geckoStartupObserverTickCount)
                assertEquals(if (groupIndex == 3) -1L else 7L, result.geckoStartupResourceCount)
            }
        }
        for (field in listOf("geckoBridgeLastTickMaxPhaseCode", "geckoAudioQueryTickMaxPhaseCode",
            "geckoStartupResourceObservationStatus")) {
            val result = GeckoTraceDiagnosticEvidence.fromBridge(measured().put(field, "private-token"), true)
            assertEquals("PENDING", result.geckoAudioQueryState)
            assertFalse(result.toString().contains("private-token"))
        }
    }

    @Test fun phaseUnknownPairsBoundsAndInactiveQueriesFailClosedIndependently() {
        val invalidBridge = listOf(
            measured().put("geckoBridgeLastTickDurationMs", -1),
            measured().put("geckoBridgeLastTickMaxPhaseDurationMs", 2261),
            measured().put("geckoBridgeLastTickMaxPhaseDurationMs", 0),
            measured().put("geckoBridgeLastTickScanNodes", 2801),
            measured().put("geckoBridgeLastTickMaxPhaseCode", "NONE"),
        )
        for (state in invalidBridge) {
            val result = GeckoTraceDiagnosticEvidence.fromBridge(state, true)
            assertEquals(-1L, result.geckoBridgeLastTickDurationMs)
            assertEquals("UNKNOWN", result.geckoBridgeLastTickMaxPhaseCode)
            assertEquals(2260L, result.geckoAudioQueryTickDurationMs)
        }
        for (state in listOf(measured().put("geckoAudioQueryTickTailDurationMs", 2261),
            measured().put("geckoAudioQueryTickTailDurationMs", -1))) {
            val result = GeckoTraceDiagnosticEvidence.fromBridge(state, true)
            assertEquals("PENDING", result.geckoAudioQueryState)
            assertEquals("UNKNOWN", result.geckoAudioQueryTickMaxPhaseCode)
            assertEquals(-1L, result.geckoAudioQueryTickTailDurationMs)
        }
        val zero = measured().apply {
            put("geckoBridgeLastTickDurationMs", 0)
            put("geckoBridgeLastTickMaxPhaseCode", "NONE")
            put("geckoBridgeLastTickMaxPhaseDurationMs", 0)
            put("geckoBridgeLastTickScanNodes", 0)
            put("geckoStartupObserverTickCount", 0)
            put("geckoStartupObserverTotalDurationMs", 0)
            put("geckoStartupObserverMaxTickDurationMs", 0)
        }
        val result = GeckoTraceDiagnosticEvidence.fromBridge(zero, true)
        assertEquals(0L, result.geckoBridgeLastTickDurationMs)
        assertEquals("NONE", result.geckoBridgeLastTickMaxPhaseCode)
        assertEquals(0L, result.geckoStartupObserverTickCount)
        val upper = zero.put("geckoBridgeLastTickDurationMs", 86_400_000)
            .put("geckoBridgeLastTickMaxPhaseDurationMs", 86_400_000)
            .put("geckoBridgeLastTickMaxPhaseCode", "NATIVE_SEND")
        assertEquals(86_400_000L, GeckoTraceDiagnosticEvidence.fromBridge(upper, true).geckoBridgeLastTickDurationMs)
        val noQuery = measured().apply {
            put("geckoAudioQueryState", "NOT_STARTED")
            for (field in listOf("geckoAudioQuerySeq", "geckoAudioQueryEpoch", "geckoAudioQueryNode", "geckoAudioQueryStartMs",
                "geckoAudioSyncCallMs", "geckoAudioReactionElapsedMs", "geckoAudioTimeoutLateMs")) put(field, -1)
            put("geckoAudioInFlight", false)
        }
        val inactive = GeckoTraceDiagnosticEvidence.fromBridge(noQuery, true)
        assertEquals("NOT_STARTED", inactive.geckoAudioQueryState)
        assertEquals("NOT_APPLICABLE", inactive.geckoAudioQueryTickMaxPhaseCode)
        assertEquals(-1L, inactive.geckoAudioQueryTickDurationMs)
    }

    @Test fun startupResourceStatusAndDetailPairsHaveIndependentSemantics() {
        val empty = measured().apply {
            put("geckoStartupResourceObservationStatus", "EMPTY")
            put("geckoStartupResourceCount", 0)
            put("geckoStartupResourceMaxDurationMs", -1)
            put("geckoStartupResourceMaxResponseWaitMs", -1)
            put("geckoStartupResourceMaxDownloadMs", -1)
            put("geckoStartupResourceDetailedCount", 0)
        }
        assertEquals("EMPTY", GeckoTraceDiagnosticEvidence.fromBridge(empty, true).geckoStartupResourceObservationStatus)
        for (status in listOf("OBSERVING", "OVERFLOW")) {
            assertEquals(status, GeckoTraceDiagnosticEvidence.fromBridge(
                empty.put("geckoStartupResourceObservationStatus", status), true,
            ).geckoStartupResourceObservationStatus)
        }
        val invalid = listOf(
            measured().put("geckoStartupResourceCount", 129),
            measured().put("geckoStartupResourceDetailedCount", 8),
            measured().put("geckoStartupResourceDetailedCount", 0),
            measured().put("geckoStartupResourceMaxDownloadMs", -1),
            measured().put("geckoStartupResourceObservationStatus", "UNSUPPORTED"),
            empty.put("geckoStartupResourceObservationStatus", "COMPLETE"),
        )
        for (state in invalid) {
            val result = GeckoTraceDiagnosticEvidence.fromBridge(state, true)
            assertEquals("INVALIDATED", result.geckoStartupResourceObservationStatus)
            assertEquals(-1L, result.geckoStartupResourceCount)
            assertEquals("PENDING", result.geckoAudioQueryState)
            assertEquals(2260L, result.geckoAudioQueryTickDurationMs)
        }
        val legacy = GeckoTraceDiagnosticEvidence.fromBridge(pending(), true)
        assertEquals("NOT_APPLICABLE", legacy.geckoStartupResourceObservationStatus)
        assertEquals("NOT_APPLICABLE", legacy.geckoAudioQueryTickMaxPhaseCode)
    }

    @Test fun publicConstructorRejectsInvalidNewGroups() {
        val invalid = listOf<() -> GeckoTraceDiagnosticEvidence>(
            { GeckoTraceDiagnosticEvidence(geckoBridgeLastTickDurationMs = 0) },
            { GeckoTraceDiagnosticEvidence(geckoBridgeLastTickMaxPhaseCode = "private-token") },
            { GeckoTraceDiagnosticEvidence(geckoAudioQueryTickTailDurationMs = 0) },
            { GeckoTraceDiagnosticEvidence(geckoStartupObserverTickCount = 0) },
            { GeckoTraceDiagnosticEvidence(geckoStartupObserverTickCount = 1_000_001) },
            { GeckoTraceDiagnosticEvidence(geckoStartupResourceObservationStatus = "EMPTY") },
            { GeckoTraceDiagnosticEvidence(geckoStartupResourceCount = 129) },
        )
        for (create in invalid) {
            try { create(); fail("invalid new group accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun metadataAndResourceInvalidationDoNotEraseIndependentQueryOrPhaseEvidence() {
        val state = measured().put("geckoStartupResourceObservationStatus", "OBSERVING")
            .put("geckoLoadedMetadataMs", 100).put("nodeGeneration", 4)
        val observed = GeckoTraceDiagnosticEvidence.fromBridge(state, true)
        assertEquals("OBSERVING", observed.geckoStartupResourceObservationStatus)
        assertEquals(7L, observed.geckoStartupResourceCount)
        assertEquals(100L, observed.geckoLoadedMetadataMs)
        for (status in listOf("NOT_APPLICABLE", "UNSUPPORTED", "INVALIDATED")) {
            state.put("geckoStartupResourceObservationStatus", status)
            for (field in listOf("geckoStartupResourceCount", "geckoStartupResourceMaxDurationMs",
                "geckoStartupResourceMaxResponseWaitMs", "geckoStartupResourceMaxDownloadMs",
                "geckoStartupResourceDetailedCount")) state.put(field, -1)
            val result = GeckoTraceDiagnosticEvidence.fromBridge(state, true)
            assertEquals(status, result.geckoStartupResourceObservationStatus)
            assertEquals(-1L, result.geckoStartupResourceCount)
            assertEquals("PENDING", result.geckoAudioQueryState)
            assertEquals(3L, result.geckoAudioQueryNode)
            assertEquals(2260L, result.geckoAudioQueryTickDurationMs)
            assertEquals(2260L, result.geckoBridgeLastTickDurationMs)
        }
    }

    private fun windowMeasured() = measured().apply {
        put("geckoAudioQueryObservedWorkCount", 3)
        put("geckoAudioQueryObservedWorkTotalMs", 2009)
        put("geckoAudioQueryObservedWorkMaxMs", 2000)
        put("geckoAudioQueryObservedWorkMaxPhaseCode", "STATE_BUILD")
        put("geckoAudioQueryObservedWorkMaxPhaseMs", 1700)
        put("geckoAudioQueryObservedWorkMaxScanNodes", 3)
        put("geckoStartupResourceScannedCount", 10)
        put("geckoStartupResourceFilteredCount", 3)
        put("geckoStartupResourceMaxStartMs", 100)
        put("geckoStartupResourceMaxEndMs", 2100)
        put("geckoStartupResourceMaxInitiatorCode", "FETCH")
    }

    @Test fun schema20ObservedWorkKeepsIndependentWindowMaximaAndOldEvidence() {
        val result = GeckoTraceDiagnosticEvidence.fromBridge(windowMeasured(), true)
        assertEquals(3L, result.geckoAudioQueryObservedWorkCount)
        assertEquals(2009L, result.geckoAudioQueryObservedWorkTotalMs)
        assertEquals(2000L, result.geckoAudioQueryObservedWorkMaxMs)
        assertEquals("STATE_BUILD", result.geckoAudioQueryObservedWorkMaxPhaseCode)
        assertEquals(1700L, result.geckoAudioQueryObservedWorkMaxPhaseMs)
        assertEquals(3L, result.geckoAudioQueryObservedWorkMaxScanNodes)
        assertEquals(2260L, result.geckoAudioQueryTickDurationMs)
        assertEquals(10L, result.geckoStartupResourceScannedCount)
        assertEquals(3L, result.geckoStartupResourceFilteredCount)
        assertEquals(100L, result.geckoStartupResourceMaxStartMs)
        assertEquals(2100L, result.geckoStartupResourceMaxEndMs)
        assertEquals("FETCH", result.geckoStartupResourceMaxInitiatorCode)
    }

    @Test fun malformedObservedWorkGroupCannotEraseSchema19QueryPhaseOrResourceGroups() {
        for (field in listOf("geckoAudioQueryObservedWorkCount", "geckoAudioQueryObservedWorkTotalMs",
            "geckoAudioQueryObservedWorkMaxMs", "geckoAudioQueryObservedWorkMaxPhaseMs", "geckoAudioQueryObservedWorkMaxScanNodes")) {
            for (invalid in listOf<Any>("20", true, 1.5, -2, Long.MAX_VALUE)) {
                val result = GeckoTraceDiagnosticEvidence.fromBridge(windowMeasured().put(field, invalid), true)
                assertEquals(-1L, result.geckoAudioQueryObservedWorkCount)
                assertEquals(-1L, result.geckoAudioQueryObservedWorkTotalMs)
                assertEquals(-1L, result.geckoAudioQueryObservedWorkMaxMs)
                assertEquals(-1L, result.geckoAudioQueryObservedWorkMaxPhaseMs)
                assertEquals(-1L, result.geckoAudioQueryObservedWorkMaxScanNodes)
                assertEquals("UNKNOWN", result.geckoAudioQueryObservedWorkMaxPhaseCode)
                assertEquals("PENDING", result.geckoAudioQueryState)
                assertEquals(0L, result.geckoAudioQueryRvfcCount)
                assertEquals(2260L, result.geckoAudioQueryTickDurationMs)
                assertEquals(7L, result.geckoStartupResourceCount)
                assertEquals(10L, result.geckoStartupResourceScannedCount)
            }
        }
        val invalid = listOf(
            windowMeasured().put("geckoAudioQueryObservedWorkCount", 0),
            windowMeasured().put("geckoAudioQueryObservedWorkCount", -1),
            windowMeasured().put("geckoAudioQueryObservedWorkCount", 1_000_001),
            windowMeasured().put("geckoAudioQueryObservedWorkMaxMs", 2010),
            windowMeasured().put("geckoAudioQueryObservedWorkMaxPhaseMs", 2001),
            windowMeasured().put("geckoAudioQueryObservedWorkMaxPhaseMs", 0),
            windowMeasured().put("geckoAudioQueryObservedWorkMaxScanNodes", 2801),
            windowMeasured().put("geckoAudioQueryObservedWorkMaxPhaseCode", "private-token"),
        )
        for (state in invalid) {
            val result = GeckoTraceDiagnosticEvidence.fromBridge(state, true)
            assertEquals(-1L, result.geckoAudioQueryObservedWorkCount)
            assertEquals("UNKNOWN", result.geckoAudioQueryObservedWorkMaxPhaseCode)
            assertEquals(1L, result.geckoAudioQuerySeq)
            assertFalse(result.toString().contains("private-token"))
        }
    }

    @Test fun observedWorkZeroRoundingUpperBoundsAndInactiveDefaultsAreValid() {
        val state = windowMeasured().apply {
            put("geckoAudioQueryObservedWorkCount", 0)
            put("geckoAudioQueryObservedWorkTotalMs", 0)
            put("geckoAudioQueryObservedWorkMaxMs", 0)
            put("geckoAudioQueryObservedWorkMaxPhaseCode", "NONE")
            put("geckoAudioQueryObservedWorkMaxPhaseMs", 0)
            put("geckoAudioQueryObservedWorkMaxScanNodes", 0)
        }
        assertEquals(0L, GeckoTraceDiagnosticEvidence.fromBridge(state, true).geckoAudioQueryObservedWorkCount)
        state.put("geckoAudioQueryObservedWorkCount", 3).put("geckoAudioQueryObservedWorkTotalMs", 1)
        val rounded = GeckoTraceDiagnosticEvidence.fromBridge(state, true)
        assertEquals(1L, rounded.geckoAudioQueryObservedWorkTotalMs)
        assertEquals(0L, rounded.geckoAudioQueryObservedWorkMaxMs)
        assertEquals("NONE", rounded.geckoAudioQueryObservedWorkMaxPhaseCode)
        state.put("geckoAudioQueryObservedWorkCount", 1_000_000)
            .put("geckoAudioQueryObservedWorkTotalMs", 86_400_000)
            .put("geckoAudioQueryObservedWorkMaxMs", 86_400_000)
            .put("geckoAudioQueryObservedWorkMaxPhaseMs", 86_400_000)
            .put("geckoAudioQueryObservedWorkMaxPhaseCode", "NATIVE_SEND")
            .put("geckoAudioQueryObservedWorkMaxScanNodes", 2800)
        assertEquals(86_400_000L, GeckoTraceDiagnosticEvidence.fromBridge(state, true).geckoAudioQueryObservedWorkMaxMs)
        for (field in listOf("geckoAudioQuerySeq", "geckoAudioQueryEpoch", "geckoAudioQueryNode", "geckoAudioQueryStartMs",
            "geckoAudioSyncCallMs", "geckoAudioReactionElapsedMs", "geckoAudioTimeoutLateMs")) state.put(field, -1)
        state.put("geckoAudioQueryState", "NOT_STARTED").put("geckoAudioInFlight", false)
        val inactive = GeckoTraceDiagnosticEvidence.fromBridge(state, true)
        assertEquals("NOT_APPLICABLE", inactive.geckoAudioQueryObservedWorkMaxPhaseCode)
        assertEquals(-1L, inactive.geckoAudioQueryObservedWorkCount)
        val legacy = GeckoTraceDiagnosticEvidence.fromBridge(measured(), true)
        assertEquals("NOT_APPLICABLE", legacy.geckoAudioQueryObservedWorkMaxPhaseCode)
        assertEquals(-1L, legacy.geckoAudioQueryObservedWorkCount)
    }

    @Test fun malformedResourceFilterGroupKeepsOldSixFieldsAndQueryWindow() {
        for (field in listOf("geckoStartupResourceScannedCount", "geckoStartupResourceFilteredCount",
            "geckoStartupResourceMaxStartMs", "geckoStartupResourceMaxEndMs")) {
            for (invalid in listOf<Any>("20", true, 1.5, -2, Long.MAX_VALUE)) {
                val result = GeckoTraceDiagnosticEvidence.fromBridge(windowMeasured().put(field, invalid), true)
                assertEquals(-1L, result.geckoStartupResourceScannedCount)
                assertEquals(-1L, result.geckoStartupResourceFilteredCount)
                assertEquals(-1L, result.geckoStartupResourceMaxStartMs)
                assertEquals(-1L, result.geckoStartupResourceMaxEndMs)
                assertEquals("UNKNOWN", result.geckoStartupResourceMaxInitiatorCode)
                assertEquals("COMPLETE", result.geckoStartupResourceObservationStatus)
                assertEquals(7L, result.geckoStartupResourceCount)
                assertEquals(2000L, result.geckoStartupResourceMaxDurationMs)
                assertEquals(3L, result.geckoAudioQueryObservedWorkCount)
            }
        }
        for (state in listOf(
            windowMeasured().put("geckoStartupResourceScannedCount", 11),
            windowMeasured().put("geckoStartupResourceScannedCount", 1025),
            windowMeasured().put("geckoStartupResourceFilteredCount", -1),
            windowMeasured().put("geckoStartupResourceMaxEndMs", 99),
            windowMeasured().put("geckoStartupResourceMaxEndMs", 2102),
            windowMeasured().put("geckoStartupResourceMaxInitiatorCode", "private-token"),
        )) {
            val result = GeckoTraceDiagnosticEvidence.fromBridge(state, true)
            assertEquals("UNKNOWN", result.geckoStartupResourceMaxInitiatorCode)
            assertEquals(7L, result.geckoStartupResourceCount)
            assertEquals(3L, result.geckoAudioQueryObservedWorkCount)
            assertFalse(result.toString().contains("private-token"))
        }
    }

    @Test fun resourceFilterGroupAllowsFilteredEmptyBoundsAndLegacyUnknown() {
        val state = windowMeasured().put("geckoStartupResourceObservationStatus", "OVERFLOW")
            .put("geckoStartupResourceCount", 128).put("geckoStartupResourceScannedCount", 1024)
            .put("geckoStartupResourceFilteredCount", 896)
        assertEquals(1024L, GeckoTraceDiagnosticEvidence.fromBridge(state, true).geckoStartupResourceScannedCount)
        state.put("geckoStartupResourceObservationStatus", "EMPTY").put("geckoStartupResourceCount", 0)
            .put("geckoStartupResourceDetailedCount", 0).put("geckoStartupResourceFilteredCount", 1024)
            .put("geckoStartupResourceMaxDurationMs", -1).put("geckoStartupResourceMaxResponseWaitMs", -1)
            .put("geckoStartupResourceMaxDownloadMs", -1).put("geckoStartupResourceMaxStartMs", -1)
            .put("geckoStartupResourceMaxEndMs", -1).put("geckoStartupResourceMaxInitiatorCode", "NONE")
        assertEquals("NONE", GeckoTraceDiagnosticEvidence.fromBridge(state, true).geckoStartupResourceMaxInitiatorCode)
        for (status in listOf("NOT_APPLICABLE", "UNSUPPORTED", "INVALIDATED")) {
            val result = GeckoTraceDiagnosticEvidence.fromBridge(state.put("geckoStartupResourceObservationStatus", status)
                .put("geckoStartupResourceCount", -1).put("geckoStartupResourceDetailedCount", -1), true)
            assertEquals("NOT_APPLICABLE", result.geckoStartupResourceMaxInitiatorCode)
            assertEquals(-1L, result.geckoStartupResourceScannedCount)
        }
        val legacy = GeckoTraceDiagnosticEvidence.fromBridge(measured(), true)
        assertEquals("COMPLETE", legacy.geckoStartupResourceObservationStatus)
        assertEquals("UNKNOWN", legacy.geckoStartupResourceMaxInitiatorCode)
        assertEquals(-1L, legacy.geckoStartupResourceScannedCount)
        val tolerance = GeckoTraceDiagnosticEvidence.fromBridge(windowMeasured().put("geckoStartupResourceMaxEndMs", 2101), true)
        assertEquals(2101L, tolerance.geckoStartupResourceMaxEndMs)
    }

    @Test fun publicConstructorRejectsInvalidSchema20Groups() {
        val invalid = listOf<() -> GeckoTraceDiagnosticEvidence>(
            { GeckoTraceDiagnosticEvidence(geckoAudioQueryObservedWorkCount = 0) },
            { GeckoTraceDiagnosticEvidence(geckoAudioQueryObservedWorkMaxPhaseCode = "private-token") },
            { GeckoTraceDiagnosticEvidence(geckoAudioQueryObservedWorkCount = 1_000_001) },
            { GeckoTraceDiagnosticEvidence(geckoStartupResourceScannedCount = 0) },
            { GeckoTraceDiagnosticEvidence(geckoStartupResourceScannedCount = 1025) },
            { GeckoTraceDiagnosticEvidence(geckoStartupResourceMaxInitiatorCode = "private-token") },
        )
        for (create in invalid) {
            try { create(); fail("invalid schema20 group accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

}
