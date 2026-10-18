package com.kantv.app.diagnostics

/** Bounded Media3 task and independent DataSource transfer facts. Request identity never leaves the in-process diagnostic join. */
data class M3uLoadDiagnosticEvidence(
    val nativeLoadActiveStatus: String = "NOT_APPLICABLE",
    val nativeLoadActiveTrackType: String = "UNKNOWN",
    val nativeLoadActiveStartElapsedMs: Long = -1,
    val nativeLoadActiveMediaStartMs: Long = -1,
    val nativeLoadActiveMediaEndMs: Long = -1,
    val nativeLoadActiveRetryCount: Long = -1,
    val nativeLoadLastOutcome: String = "NOT_APPLICABLE",
    val nativeLoadLastTrackType: String = "UNKNOWN",
    val nativeLoadLastStartElapsedMs: Long = -1,
    val nativeLoadLastEndElapsedMs: Long = -1,
    val nativeLoadLastMediaStartMs: Long = -1,
    val nativeLoadLastMediaEndMs: Long = -1,
    val nativeLoadLastRetryCount: Long = -1,
    val nativeLoadLastBytes: Long = -1,
    val nativeLoadLastHttpStatus: Long = -1,
    val nativeLoadPositionStatus: String = "NOT_APPLICABLE",
    val nativeLoadWindowStartInPeriodMs: Long = -1,
    val nativeTransferActiveCount: Long = -1,
    val nativeTransferSequence: Long = -1,
    val nativeTransferOutcome: String = "NA",
    val nativeTransferTaskMatchStatus: String = "NOT_APPLICABLE",
    val nativeTransferStartElapsedMs: Long = -1,
    val nativeTransferEndElapsedMs: Long = -1,
    val nativeTransferOpenDurationUs: Long = -1,
    val nativeTransferReadDurationUs: Long = -1,
    val nativeTransferCloseDurationUs: Long = -1,
    val nativeTransferReadCallCount: Long = -1,
    val nativeTransferBytes: Long = -1,
    val nativeTransferEofSeen: Boolean = false,
    val nativeMediaRequestMatchStatus: String = "NOT_APPLICABLE",
    val nativeMediaRequestCoverage: String = "UNKNOWN",
    val nativeMediaRequestOutcome: String = "NA",
    val nativeMediaRequestOpenDurationUs: Long = -1,
    val nativeMediaRequestReadDurationUs: Long = -1,
    val nativeMediaRequestCloseDurationUs: Long = -1,
    val nativeMediaRequestReadCallCount: Long = -1,
    val nativeMediaRequestBytes: Long = -1,
    val nativeMediaRequestEofSeen: Boolean = false,
) {
    init {
        require(nativeLoadActiveStatus in ACTIVE_STATUSES)
        require(nativeLoadLastOutcome in LAST_OUTCOMES)
        require(nativeLoadActiveTrackType in TRACK_TYPES && nativeLoadLastTrackType in TRACK_TYPES)
        require(nativeLoadPositionStatus in POSITION_STATUSES)
        require(nativeTransferOutcome in TRANSFER_OUTCOMES)
        require(nativeMediaRequestMatchStatus in MEDIA_REQUEST_MATCH_STATUSES)
        require(nativeMediaRequestCoverage in setOf("UNKNOWN", "REQUEST_ONLY"))
        require(nativeMediaRequestOutcome in TRANSFER_OUTCOMES)
        val matchedRequest = listOf(nativeMediaRequestOpenDurationUs, nativeMediaRequestReadDurationUs,
            nativeMediaRequestCloseDurationUs, nativeMediaRequestReadCallCount, nativeMediaRequestBytes)
        require(matchedRequest.all { it in -1..MAX_INTEGER })
        if (nativeMediaRequestMatchStatus == "MATCHED") {
            require(nativeLoadLastOutcome == "COMPLETED")
            require(nativeMediaRequestCoverage == "REQUEST_ONLY")
            require(nativeMediaRequestOutcome !in setOf("NA", "NONE", "INVALIDATED"))
            require(nativeMediaRequestReadCallCount >= 0 && nativeMediaRequestBytes >= 0)
        } else {
            require(nativeMediaRequestCoverage == "UNKNOWN" && nativeMediaRequestOutcome == "NA")
            require(matchedRequest.all { it == -1L } && !nativeMediaRequestEofSeen)
        }
        if (nativeMediaRequestOutcome == "CLOSED_EOF") require(nativeMediaRequestEofSeen)
        if (nativeMediaRequestOutcome == "CLOSED_NO_EOF") require(!nativeMediaRequestEofSeen)
        require(nativeTransferTaskMatchStatus in setOf("NOT_APPLICABLE", "UNASSIGNED"))
        val active = listOf(nativeLoadActiveStartElapsedMs, nativeLoadActiveMediaStartMs,
            nativeLoadActiveMediaEndMs, nativeLoadActiveRetryCount)
        val last = listOf(nativeLoadLastStartElapsedMs, nativeLoadLastEndElapsedMs,
            nativeLoadLastMediaStartMs, nativeLoadLastMediaEndMs, nativeLoadLastRetryCount,
            nativeLoadLastBytes, nativeLoadLastHttpStatus)
        val transfer = listOf(nativeTransferSequence, nativeTransferStartElapsedMs,
            nativeTransferEndElapsedMs, nativeTransferOpenDurationUs, nativeTransferReadDurationUs,
            nativeTransferCloseDurationUs, nativeTransferReadCallCount, nativeTransferBytes)
        require((active + last + transfer + nativeTransferActiveCount).all { it in -1..MAX_INTEGER })
        require(nativeLoadWindowStartInPeriodMs in -MAX_INTEGER..MAX_INTEGER)
        require(nativeLoadLastHttpStatus == -1L || nativeLoadLastHttpStatus in 100..599)
        if (nativeLoadActiveStatus !in setOf("LOADING", "RETRYING")) {
            require(nativeLoadActiveTrackType == "UNKNOWN" && active.all { it == -1L })
        }
        if (nativeLoadLastOutcome in setOf("NOT_APPLICABLE", "NONE", "INVALIDATED")) {
            require(nativeLoadLastTrackType == "UNKNOWN" && last.all { it == -1L })
        }
        if (nativeLoadLastStartElapsedMs >= 0 && nativeLoadLastEndElapsedMs >= 0) {
            require(nativeLoadLastEndElapsedMs >= nativeLoadLastStartElapsedMs)
        }
        if (nativeLoadPositionStatus !in setOf("AVAILABLE", "WINDOW_ONLY")) require(nativeLoadWindowStartInPeriodMs == -1L)
        if (nativeTransferOutcome in setOf("NA", "NONE", "INVALIDATED")) {
            require(transfer.all { it == -1L } && !nativeTransferEofSeen)
        } else {
            require(nativeTransferTaskMatchStatus == "UNASSIGNED")
            if (nativeTransferStartElapsedMs >= 0 && nativeTransferEndElapsedMs >= 0) {
                require(nativeTransferEndElapsedMs >= nativeTransferStartElapsedMs)
            }
            require(nativeTransferSequence >= 0)
            require(nativeTransferReadCallCount >= 0 && nativeTransferBytes >= 0)
        }
        if (nativeTransferOutcome == "CLOSED_EOF") require(nativeTransferEofSeen)
        if (nativeTransferOutcome == "CLOSED_NO_EOF") require(!nativeTransferEofSeen)
    }

    companion object {
        const val MAX_INTEGER = 9_007_199_254_740_991L
        val ACTIVE_STATUSES = setOf("NOT_APPLICABLE", "NONE", "LOADING", "RETRYING", "UNKNOWN_CAPACITY", "UNKNOWN_OWNERSHIP")
        val LAST_OUTCOMES = setOf("NOT_APPLICABLE", "NONE", "COMPLETED", "ERROR_RETRYING", "ERROR_TERMINAL", "CANCELED", "INVALIDATED")
        val TRACK_TYPES = setOf("UNKNOWN", "DEFAULT", "AUDIO", "VIDEO", "TEXT")
        val POSITION_STATUSES = setOf("NOT_APPLICABLE", "AVAILABLE", "WINDOW_ONLY", "UNKNOWN_NO_PERIOD", "UNKNOWN_CHANGED_PERIOD", "UNKNOWN_MULTI_PERIOD", "UNKNOWN_TIME")
        val MEDIA_REQUEST_MATCH_STATUSES = setOf("NOT_APPLICABLE", "NONE", "PENDING", "MATCHED", "UNASSIGNED", "UNKNOWN_RETRY", "UNKNOWN_AMBIGUOUS", "UNKNOWN_CAPACITY", "INVALIDATED")
        val TRANSFER_OUTCOMES = setOf("NA", "NONE", "CLOSED_EOF", "CLOSED_NO_EOF", "OPEN_ERROR", "READ_ERROR", "CLOSE_ERROR", "INVALIDATED")
    }
}
