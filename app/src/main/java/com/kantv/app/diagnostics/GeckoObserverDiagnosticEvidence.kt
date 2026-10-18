package com.kantv.app.diagnostics

import org.json.JSONObject

/** Sparse observer evidence. DOM and Android durations remain separate clock domains. */
data class GeckoObserverDiagnosticEvidence(
    val geckoAudioQueryJsTickCount: Long = -1,
    val geckoAudioQueryMaxJsTickGapMs: Long = -1,
    val geckoAudioRecoveryCount: Long = -1,
    val geckoAudioRetryRemainingMs: Long = -1,
    val geckoAudioFirstFaultSeq: Long = -1,
    val geckoAudioFirstFaultEpoch: Long = -1,
    val geckoAudioFirstFaultNode: Long = -1,
    val geckoAudioFirstFaultStartMs: Long = -1,
    val geckoAudioFirstFaultSyncCallMs: Long = -1,
    val geckoAudioFirstFaultReactionElapsedMs: Long = -1,
    val geckoAudioFirstFaultTimeoutLateMs: Long = -1,
    val geckoAudioFirstFaultJsTickCount: Long = -1,
    val geckoAudioFirstFaultMaxJsTickGapMs: Long = -1,
    val geckoCaptureSequence: Long = -1,
    val geckoCaptureApiCallMs: Long = -1,
    val geckoCaptureIssueToListenerMs: Long = -1,
    val geckoCaptureClassifyMs: Long = -1,
    val geckoCapturePostLagMs: Long = -1,
    val geckoBridgePostLagMs: Long = -1,
    val geckoAudioFirstFaultState: String = "NOT_APPLICABLE",
    val geckoAudioFirstFaultDisposition: String = "NOT_APPLICABLE",
    val geckoAudioFirstFaultClockStatus: String = "NOT_APPLICABLE",
) {
    init {
        val values = toFields()
        require(LIMITS.all { (key, limit) -> (values[key] as Long) in -1..limit })
        require(ENUMS.all { (key, allowed) -> values[key] in allowed })
        require(validTick(geckoAudioQueryJsTickCount, geckoAudioQueryMaxJsTickGapMs))
        require(validTick(geckoAudioFirstFaultJsTickCount, geckoAudioFirstFaultMaxJsTickGapMs))
        if (geckoAudioFirstFaultSeq == -1L) {
            require(values.filterKeys { it.startsWith("geckoAudioFirstFault") }.all { (_, v) -> v == -1L || v == "NOT_APPLICABLE" })
        } else {
            require(geckoAudioFirstFaultSeq > 0 && geckoAudioFirstFaultEpoch >= 0 &&
                geckoAudioFirstFaultNode >= 0 && geckoAudioFirstFaultStartMs >= 0)
            require(geckoAudioFirstFaultState != "NOT_APPLICABLE" &&
                geckoAudioFirstFaultDisposition != "NOT_APPLICABLE" && geckoAudioFirstFaultClockStatus != "NOT_APPLICABLE")
        }
        require(geckoCaptureSequence != 0L)
        if (geckoCaptureSequence < 0) require(values.filterKeys { it.startsWith("geckoCapture") }.values.all { it == -1L })
        require(geckoAudioRetryRemainingMs == -1L || geckoAudioRecoveryCount in 0L..1L)
    }
    fun toFields(): Map<String, Any> = linkedMapOf(
        "geckoAudioQueryJsTickCount" to geckoAudioQueryJsTickCount,
        "geckoAudioQueryMaxJsTickGapMs" to geckoAudioQueryMaxJsTickGapMs,
        "geckoAudioRecoveryCount" to geckoAudioRecoveryCount,
        "geckoAudioRetryRemainingMs" to geckoAudioRetryRemainingMs,
        "geckoAudioFirstFaultSeq" to geckoAudioFirstFaultSeq,
        "geckoAudioFirstFaultEpoch" to geckoAudioFirstFaultEpoch,
        "geckoAudioFirstFaultNode" to geckoAudioFirstFaultNode,
        "geckoAudioFirstFaultStartMs" to geckoAudioFirstFaultStartMs,
        "geckoAudioFirstFaultSyncCallMs" to geckoAudioFirstFaultSyncCallMs,
        "geckoAudioFirstFaultReactionElapsedMs" to geckoAudioFirstFaultReactionElapsedMs,
        "geckoAudioFirstFaultTimeoutLateMs" to geckoAudioFirstFaultTimeoutLateMs,
        "geckoAudioFirstFaultJsTickCount" to geckoAudioFirstFaultJsTickCount,
        "geckoAudioFirstFaultMaxJsTickGapMs" to geckoAudioFirstFaultMaxJsTickGapMs,
        "geckoCaptureSequence" to geckoCaptureSequence,
        "geckoCaptureApiCallMs" to geckoCaptureApiCallMs,
        "geckoCaptureIssueToListenerMs" to geckoCaptureIssueToListenerMs,
        "geckoCaptureClassifyMs" to geckoCaptureClassifyMs,
        "geckoCapturePostLagMs" to geckoCapturePostLagMs,
        "geckoBridgePostLagMs" to geckoBridgePostLagMs,
        "geckoAudioFirstFaultState" to geckoAudioFirstFaultState,
        "geckoAudioFirstFaultDisposition" to geckoAudioFirstFaultDisposition,
        "geckoAudioFirstFaultClockStatus" to geckoAudioFirstFaultClockStatus,
    )
    companion object {
        private val LIMITS = linkedMapOf(
            "geckoAudioQueryJsTickCount" to 1000000L,
            "geckoAudioQueryMaxJsTickGapMs" to 86400000L,
            "geckoAudioRecoveryCount" to 2L,
            "geckoAudioRetryRemainingMs" to 30000L,
            "geckoAudioFirstFaultSeq" to 9007199254740991L,
            "geckoAudioFirstFaultEpoch" to 9007199254740991L,
            "geckoAudioFirstFaultNode" to 9007199254740991L,
            "geckoAudioFirstFaultStartMs" to 9007199254740991L,
            "geckoAudioFirstFaultSyncCallMs" to 9007199254740991L,
            "geckoAudioFirstFaultReactionElapsedMs" to 9007199254740991L,
            "geckoAudioFirstFaultTimeoutLateMs" to 9007199254740991L,
            "geckoAudioFirstFaultJsTickCount" to 1000000L,
            "geckoAudioFirstFaultMaxJsTickGapMs" to 86400000L,
            "geckoCaptureSequence" to 9007199254740991L,
            "geckoCaptureApiCallMs" to 86400000L,
            "geckoCaptureIssueToListenerMs" to 86400000L,
            "geckoCaptureClassifyMs" to 86400000L,
            "geckoCapturePostLagMs" to 86400000L,
            "geckoBridgePostLagMs" to 86400000L,
        )
        private val ENUMS = linkedMapOf(
            "geckoAudioFirstFaultState" to setOf("NOT_APPLICABLE", "CALLING", "PENDING", "TIMED_OUT", "RESOLVED", "REJECTED"),
            "geckoAudioFirstFaultDisposition" to setOf("NOT_APPLICABLE", "TIMEOUT_FUSE", "SLOW_CALL", "SYNC_THROW", "REJECTED_FUSE", "INVALID_CLOCK", "CLOCK_MISMATCH"),
            "geckoAudioFirstFaultClockStatus" to setOf("NOT_APPLICABLE", "INSUFFICIENT_TIMEOUT", "INSUFFICIENT_SLOW_CALL", "INSUFFICIENT_REJECTED", "INSUFFICIENT_INVALID_CLOCK", "INSUFFICIENT_CLOCK_MISMATCH"),
        )
        private fun validTick(count: Long, gap: Long) = if (count <= 0) gap == -1L else gap >= 0
        fun fromBridge(state: JSONObject, applicable: Boolean, hostPostLagMs: Long = -1): GeckoObserverDiagnosticEvidence {
            if (!applicable) return GeckoObserverDiagnosticEvidence()
            fun number(key: String): Long {
                val value = state.opt(key) as? Number ?: return -1
                val d = value.toDouble()
                return if (d.isFinite() && d == kotlin.math.floor(d) && d >= -1 && d <= LIMITS.getValue(key)) d.toLong() else -1
            }
            fun text(key: String) = (state.opt(key) as? String)?.takeIf { it in ENUMS.getValue(key) } ?: "NOT_APPLICABLE"
            return runCatching { GeckoObserverDiagnosticEvidence(
                geckoAudioQueryJsTickCount = number("geckoAudioQueryJsTickCount"),
                geckoAudioQueryMaxJsTickGapMs = number("geckoAudioQueryMaxJsTickGapMs"),
                geckoAudioRecoveryCount = number("geckoAudioRecoveryCount"),
                geckoAudioRetryRemainingMs = number("geckoAudioRetryRemainingMs"),
                geckoAudioFirstFaultSeq = number("geckoAudioFirstFaultSeq"),
                geckoAudioFirstFaultEpoch = number("geckoAudioFirstFaultEpoch"),
                geckoAudioFirstFaultNode = number("geckoAudioFirstFaultNode"),
                geckoAudioFirstFaultStartMs = number("geckoAudioFirstFaultStartMs"),
                geckoAudioFirstFaultSyncCallMs = number("geckoAudioFirstFaultSyncCallMs"),
                geckoAudioFirstFaultReactionElapsedMs = number("geckoAudioFirstFaultReactionElapsedMs"),
                geckoAudioFirstFaultTimeoutLateMs = number("geckoAudioFirstFaultTimeoutLateMs"),
                geckoAudioFirstFaultJsTickCount = number("geckoAudioFirstFaultJsTickCount"),
                geckoAudioFirstFaultMaxJsTickGapMs = number("geckoAudioFirstFaultMaxJsTickGapMs"),
                geckoAudioFirstFaultState = text("geckoAudioFirstFaultState"),
                geckoAudioFirstFaultDisposition = text("geckoAudioFirstFaultDisposition"),
                geckoAudioFirstFaultClockStatus = text("geckoAudioFirstFaultClockStatus"),
                geckoBridgePostLagMs = hostPostLagMs.takeIf { it in -1..86_400_000 } ?: -1,
            ) }.getOrElse { GeckoObserverDiagnosticEvidence(geckoBridgePostLagMs = hostPostLagMs.takeIf { it in -1..86_400_000 } ?: -1) }
        }
    }
}
