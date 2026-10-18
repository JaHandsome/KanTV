package com.kantv.app.diagnostics

import org.junit.Assert.*
import org.junit.Test

class M3uLoadDiagnosticEvidenceTest {
    @Test fun defaultsAreNotApplicableWithoutInventingZeros() {
        val value = M3uLoadDiagnosticEvidence()
        assertEquals("NOT_APPLICABLE", value.nativeLoadActiveStatus)
        assertEquals("NA", value.nativeTransferOutcome)
        assertEquals(-1L, value.nativeTransferReadDurationUs)
        assertFalse(value.nativeTransferEofSeen)
    }
    @Test fun knownWindowCanHaveSignedStartIncludingMinusOne() {
        M3uLoadDiagnosticEvidence(nativeLoadPositionStatus = "AVAILABLE", nativeLoadWindowStartInPeriodMs = -20_000)
        M3uLoadDiagnosticEvidence(nativeLoadPositionStatus = "AVAILABLE", nativeLoadWindowStartInPeriodMs = -1)
        M3uLoadDiagnosticEvidence(nativeLoadPositionStatus = "WINDOW_ONLY", nativeLoadWindowStartInPeriodMs = 26_000)
        assertThrows(IllegalArgumentException::class.java) { M3uLoadDiagnosticEvidence(nativeLoadWindowStartInPeriodMs = 0) }
    }
    @Test fun eofAndUnassignedAreExplicitWhileClockFailureCanRemainUnknown() {
        val value = M3uLoadDiagnosticEvidence(nativeTransferSequence = 1, nativeTransferOutcome = "CLOSED_NO_EOF",
            nativeTransferTaskMatchStatus = "UNASSIGNED", nativeTransferReadCallCount = 1, nativeTransferBytes = 4)
        assertEquals(-1L, value.nativeTransferOpenDurationUs)
        assertThrows(IllegalArgumentException::class.java) { value.copy(nativeTransferOutcome = "CLOSED_EOF") }
        assertThrows(IllegalArgumentException::class.java) { value.copy(nativeTransferTaskMatchStatus = "NOT_APPLICABLE") }
        assertThrows(IllegalArgumentException::class.java) { value.copy(nativeTransferReadDurationUs = -2) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(nativeTransferBytes = Long.MAX_VALUE) }
    }
    @Test fun unknownGroupsRejectStaleFactsAndFreeText() {
        assertThrows(IllegalArgumentException::class.java) { M3uLoadDiagnosticEvidence(nativeLoadLastBytes = 100) }
        assertThrows(IllegalArgumentException::class.java) { M3uLoadDiagnosticEvidence(nativeLoadActiveTrackType = "VIDEO") }
        assertThrows(IllegalArgumentException::class.java) { M3uLoadDiagnosticEvidence(nativeTransferOutcome = "https://fixture.invalid") }
        assertThrows(IllegalArgumentException::class.java) { M3uLoadDiagnosticEvidence(nativeLoadLastOutcome = "ERROR_TERMINAL", nativeLoadLastHttpStatus = 600) }
    }
    @Test fun matchedMediaIsRequestOnlyAndUnknownGroupsCannotKeepItsSummary() {
        val matched = M3uLoadDiagnosticEvidence(nativeLoadLastOutcome = "COMPLETED", nativeMediaRequestMatchStatus = "MATCHED",
            nativeMediaRequestCoverage = "REQUEST_ONLY", nativeMediaRequestOutcome = "CLOSED_EOF",
            nativeMediaRequestOpenDurationUs = 10, nativeMediaRequestReadDurationUs = 20,
            nativeMediaRequestCloseDurationUs = 30, nativeMediaRequestReadCallCount = 2,
            nativeMediaRequestBytes = 400, nativeMediaRequestEofSeen = true)
        assertEquals("REQUEST_ONLY", matched.nativeMediaRequestCoverage)
        for (status in listOf("PENDING", "UNASSIGNED", "UNKNOWN_RETRY", "UNKNOWN_AMBIGUOUS", "UNKNOWN_CAPACITY")) {
            assertThrows(IllegalArgumentException::class.java) { matched.copy(nativeMediaRequestMatchStatus = status) }
        }
        assertThrows(IllegalArgumentException::class.java) { matched.copy(nativeMediaRequestCoverage = "WHOLE_TASK") }
        assertThrows(IllegalArgumentException::class.java) { matched.copy(nativeMediaRequestReadCallCount = -1) }
        assertThrows(IllegalArgumentException::class.java) { matched.copy(nativeMediaRequestEofSeen = false) }
        assertThrows(IllegalArgumentException::class.java) { matched.copy(nativeMediaRequestBytes = Long.MAX_VALUE) }
    }

}
