package com.kantv.app.diagnostics

import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.abs

/** Clock anchors are separate observations; never subtract document time from host time. */
data class GeckoTraceDiagnosticEvidence(
    val geckoNodeBoundMs: Long = -1,
    val geckoLoadedMetadataMs: Long = -1,
    val geckoPlayMs: Long = -1,
    val geckoPlayingMs: Long = -1,
    val geckoWaitingMs: Long = -1,
    val geckoCanPlayMs: Long = -1,
    val geckoFirstRvfcMs: Long = -1,
    val geckoFirstRvfcPtsUs: Long = -1,
    val geckoAttachLate: Boolean = false,
    val geckoJsTickGapMs: Long = -1,
    val geckoAudioQuerySeq: Long = -1,
    val geckoAudioQueryEpoch: Long = -1,
    val geckoAudioQueryNode: Long = -1,
    val geckoAudioQueryState: String = "NOT_APPLICABLE",
    val geckoAudioQueryStartMs: Long = -1,
    val geckoAudioSyncCallMs: Long = -1,
    val geckoAudioReactionElapsedMs: Long = -1,
    val geckoAudioTimeoutLateMs: Long = -1,
    val geckoAudioInFlight: Boolean = false,
    val geckoAudioQueryDisposition: String = "NOT_APPLICABLE",
    val geckoDocumentNowMs: Long = -1,
    val geckoDocumentTimeOriginMs: Long = -1,
    val geckoStateReceivedEpochMs: Long = -1,
    val geckoAudioQueryRvfcCount: Long = -1,
    val geckoAudioQueryMaxRvfcGapMs: Long = -1,
    val geckoBridgeLastTickDurationMs: Long = -1,
    val geckoBridgeLastTickMaxPhaseCode: String = "NOT_APPLICABLE",
    val geckoBridgeLastTickMaxPhaseDurationMs: Long = -1,
    val geckoBridgeLastTickScanNodes: Long = -1,
    val geckoAudioQueryTickDurationMs: Long = -1,
    val geckoAudioQueryTickTailDurationMs: Long = -1,
    val geckoAudioQueryTickMaxPhaseCode: String = "NOT_APPLICABLE",
    val geckoAudioQueryTickMaxPhaseDurationMs: Long = -1,
    val geckoAudioQueryTickScanNodes: Long = -1,
    val geckoAudioQueryObservedWorkCount: Long = -1,
    val geckoAudioQueryObservedWorkTotalMs: Long = -1,
    val geckoAudioQueryObservedWorkMaxMs: Long = -1,
    val geckoAudioQueryObservedWorkMaxPhaseCode: String = "NOT_APPLICABLE",
    val geckoAudioQueryObservedWorkMaxPhaseMs: Long = -1,
    val geckoAudioQueryObservedWorkMaxScanNodes: Long = -1,
    val geckoStartupObserverTickCount: Long = -1,
    val geckoStartupObserverTotalDurationMs: Long = -1,
    val geckoStartupObserverMaxTickDurationMs: Long = -1,
    val geckoStartupResourceObservationStatus: String = "NOT_APPLICABLE",
    val geckoStartupResourceCount: Long = -1,
    val geckoStartupResourceMaxDurationMs: Long = -1,
    val geckoStartupResourceMaxResponseWaitMs: Long = -1,
    val geckoStartupResourceMaxDownloadMs: Long = -1,
    val geckoStartupResourceDetailedCount: Long = -1,
    val geckoStartupResourceScannedCount: Long = -1,
    val geckoStartupResourceFilteredCount: Long = -1,
    val geckoStartupResourceMaxStartMs: Long = -1,
    val geckoStartupResourceMaxEndMs: Long = -1,
    val geckoStartupResourceMaxInitiatorCode: String = when (geckoStartupResourceObservationStatus) {
        "NOT_APPLICABLE", "UNSUPPORTED", "INVALIDATED" -> "NOT_APPLICABLE"
        else -> "UNKNOWN"
    },
) {
    init {
        val numbers = listOf(
            geckoNodeBoundMs, geckoLoadedMetadataMs, geckoPlayMs, geckoPlayingMs, geckoWaitingMs,
            geckoCanPlayMs, geckoFirstRvfcMs, geckoFirstRvfcPtsUs, geckoJsTickGapMs,
            geckoAudioQuerySeq, geckoAudioQueryEpoch, geckoAudioQueryNode, geckoAudioQueryStartMs,
            geckoAudioSyncCallMs, geckoAudioReactionElapsedMs, geckoAudioTimeoutLateMs,
            geckoDocumentNowMs, geckoDocumentTimeOriginMs, geckoStateReceivedEpochMs,
        )
        require(numbers.all { it in -1..MAX_INTEGER })
        require(geckoAudioQueryRvfcCount in -1..MAX_QUERY_RVFC_COUNT)
        require(geckoAudioQueryMaxRvfcGapMs in -1..MAX_QUERY_RVFC_GAP_MS)
        require(geckoAudioQueryRvfcCount >= 0 || geckoAudioQueryMaxRvfcGapMs == -1L)
        require(validWork(geckoBridgeLastTickDurationMs, geckoBridgeLastTickMaxPhaseCode,
            geckoBridgeLastTickMaxPhaseDurationMs, geckoBridgeLastTickScanNodes))
        require(validWork(geckoAudioQueryTickDurationMs, geckoAudioQueryTickMaxPhaseCode,
            geckoAudioQueryTickMaxPhaseDurationMs, geckoAudioQueryTickScanNodes))
        require(geckoAudioQueryTickTailDurationMs in -1..MAX_TICK_DURATION_MS)
        require(if (geckoAudioQueryTickDurationMs < 0) geckoAudioQueryTickTailDurationMs == -1L
            else geckoAudioQueryTickTailDurationMs in 0..geckoAudioQueryTickDurationMs)
        require(validObservedWork(geckoAudioQueryObservedWorkCount, geckoAudioQueryObservedWorkTotalMs,
            geckoAudioQueryObservedWorkMaxMs, geckoAudioQueryObservedWorkMaxPhaseCode,
            geckoAudioQueryObservedWorkMaxPhaseMs, geckoAudioQueryObservedWorkMaxScanNodes))
        require(validStartupWork(geckoStartupObserverTickCount, geckoStartupObserverTotalDurationMs,
            geckoStartupObserverMaxTickDurationMs))
        require(validResources(geckoStartupResourceObservationStatus, geckoStartupResourceCount,
            geckoStartupResourceMaxDurationMs, geckoStartupResourceMaxResponseWaitMs,
            geckoStartupResourceMaxDownloadMs, geckoStartupResourceDetailedCount))
        require(validFilteredResources(geckoStartupResourceObservationStatus, geckoStartupResourceCount,
            geckoStartupResourceMaxDurationMs, geckoStartupResourceScannedCount, geckoStartupResourceFilteredCount,
            geckoStartupResourceMaxStartMs, geckoStartupResourceMaxEndMs, geckoStartupResourceMaxInitiatorCode))
        require(geckoAudioQueryState in STATES && geckoAudioQueryDisposition in DISPOSITIONS)
        if (geckoAudioQueryState == "NOT_APPLICABLE" || geckoAudioQueryState == "NOT_STARTED") {
            require(listOf(geckoAudioQuerySeq, geckoAudioQueryEpoch, geckoAudioQueryNode,
                geckoAudioQueryStartMs, geckoAudioSyncCallMs, geckoAudioReactionElapsedMs,
                geckoAudioTimeoutLateMs, geckoAudioQueryRvfcCount,
                geckoAudioQueryMaxRvfcGapMs, geckoAudioQueryTickDurationMs,
                geckoAudioQueryTickTailDurationMs, geckoAudioQueryTickMaxPhaseDurationMs,
                geckoAudioQueryTickScanNodes, geckoAudioQueryObservedWorkCount,
                geckoAudioQueryObservedWorkTotalMs, geckoAudioQueryObservedWorkMaxMs,
                geckoAudioQueryObservedWorkMaxPhaseMs, geckoAudioQueryObservedWorkMaxScanNodes).all { it == -1L } && !geckoAudioInFlight)
            require(geckoAudioQueryTickMaxPhaseCode == "NOT_APPLICABLE")
            require(geckoAudioQueryObservedWorkMaxPhaseCode == "NOT_APPLICABLE")
        } else {
            require(geckoAudioQuerySeq > 0 && geckoAudioQueryEpoch >= 0 &&
                geckoAudioQueryNode >= 0 && geckoAudioQueryStartMs >= 0)
        }
    }

    companion object {
        private const val MAX_INTEGER = 9_007_199_254_740_991L
        private const val MAX_QUERY_RVFC_COUNT = 1_000_000L
        private const val MAX_QUERY_RVFC_GAP_MS = 86_400_000L
        private const val MAX_TICK_DURATION_MS = 86_400_000L
        private const val MAX_SCAN_NODES = 2_800L
        private val PHASES = setOf("NOT_APPLICABLE", "UNKNOWN", "NONE", "BIND", "AUDIO",
            "LOGIN_SCAN", "QR_LAYOUT", "UI_ACTIONS", "STATE_BUILD", "NATIVE_SEND")
        private fun validWork(duration: Long, code: String, maxDuration: Long, scans: Long): Boolean {
            if (duration !in -1..MAX_TICK_DURATION_MS || maxDuration !in -1..MAX_TICK_DURATION_MS ||
                scans !in -1..MAX_SCAN_NODES || code !in PHASES) return false
            if (duration == -1L) return maxDuration == -1L && scans == -1L &&
                code in setOf("NOT_APPLICABLE", "UNKNOWN")
            return scans >= 0 && maxDuration in 0..duration &&
                (if (maxDuration == 0L) code == "NONE" else code !in setOf("NOT_APPLICABLE", "UNKNOWN", "NONE"))
        }
        private fun validObservedWork(count: Long, total: Long, max: Long, code: String,
            phase: Long, scans: Long): Boolean {
            if (count !in -1..MAX_QUERY_RVFC_COUNT || total !in -1..MAX_TICK_DURATION_MS ||
                max !in -1..MAX_TICK_DURATION_MS || phase !in -1..MAX_TICK_DURATION_MS ||
                scans !in -1..MAX_SCAN_NODES || code !in PHASES) return false
            if (count == -1L) return listOf(total, max, phase, scans).all { it == -1L } &&
                code in setOf("NOT_APPLICABLE", "UNKNOWN")
            if (count == 0L) return listOf(total, max, phase, scans).all { it == 0L } && code == "NONE"
            return scans >= 0 && phase in 0..max && max in 0..total &&
                (if (phase == 0L) code == "NONE" else code !in setOf("NOT_APPLICABLE", "UNKNOWN", "NONE"))
        }
        private fun validStartupWork(count: Long, total: Long, max: Long): Boolean {
            if (count !in -1..MAX_QUERY_RVFC_COUNT || total !in -1..MAX_TICK_DURATION_MS ||
                max !in -1..MAX_TICK_DURATION_MS) return false
            if (count == -1L) return total == -1L && max == -1L
            return total >= 0 && max in 0..total && (count > 0 || (total == 0L && max == 0L))
        }
        private val RESOURCE_STATES = setOf("NOT_APPLICABLE", "UNSUPPORTED", "OBSERVING", "COMPLETE",
            "EMPTY", "BOUNDED", "OVERFLOW", "INVALIDATED")
        private fun validResources(status: String, count: Long, duration: Long, wait: Long,
            download: Long, detail: Long): Boolean {
            if (status !in RESOURCE_STATES || count !in -1L..128L || detail !in -1L..128L ||
                listOf(duration, wait, download).any { it !in -1..MAX_TICK_DURATION_MS }) return false
            if (status in setOf("NOT_APPLICABLE", "UNSUPPORTED", "INVALIDATED")) {
                return listOf(count, duration, wait, download, detail).all { it == -1L }
            }
            if (count < 0 || detail !in 0..count) return false
            if (count == 0L && (duration != -1L || detail != 0L)) return false
            if (count > 0 && duration < 0) return false
            if (detail == 0L && (wait != -1L || download != -1L)) return false
            if (detail > 0 && (wait < 0 || download < 0)) return false
            return when (status) {
                "EMPTY" -> count == 0L
                "COMPLETE", "BOUNDED" -> count > 0
                else -> true
            }
        }
        private fun validFilteredResources(status: String, count: Long, duration: Long, scanned: Long,
            filtered: Long, start: Long, end: Long, code: String): Boolean {
            if (scanned !in -1L..1024L || filtered !in -1L..1024L ||
                start !in -1..MAX_TICK_DURATION_MS || end !in -1..MAX_TICK_DURATION_MS) return false
            val unknown = listOf(scanned, filtered, start, end).all { it == -1L }
            if (status in setOf("NOT_APPLICABLE", "UNSUPPORTED", "INVALIDATED")) {
                return unknown && code == "NOT_APPLICABLE"
            }
            if (unknown) return code == "UNKNOWN"
            if (scanned < 0 || filtered < 0 || scanned != count + filtered) return false
            if (count == 0L) return start == -1L && end == -1L && code == "NONE"
            return count > 0 && start >= 0 && end >= start && abs(end - start - duration) <= 1 &&
                code in setOf("FETCH", "XMLHTTPREQUEST", "VIDEO", "AUDIO")
        }
        private data class Work(val duration: Long = -1, val code: String = "UNKNOWN",
            val maxDuration: Long = -1, val scans: Long = -1, val tail: Long = -1)
        private val STATES = setOf(
            "NOT_APPLICABLE", "NOT_STARTED", "CALLING", "PENDING", "TIMED_OUT", "RESOLVED", "REJECTED",
        )
        private val DISPOSITIONS = setOf(
            "NOT_APPLICABLE", "NONE", "APPLIED", "TIMEOUT_FUSE", "SYNC_THROW", "REJECTED_FUSE",
            "SLOW_CALL", "INVALID_CLOCK", "CLOCK_MISMATCH", "PAIR_STALE", "IGNORED_TIMEOUT_FUSE",
            "IGNORED_NODE", "IGNORED_EPOCH", "IGNORED_NOT_PLAYING",
        )

        fun fromBridge(
            state: JSONObject,
            applicable: Boolean,
            hostReceivedEpochMs: Long = -1,
        ): GeckoTraceDiagnosticEvidence {
            if (!applicable) return GeckoTraceDiagnosticEvidence()
            fun number(name: String): Long {
                val value = (state.opt(name) as? Number)?.toDouble() ?: return -1
                return if (value.isFinite() && value >= -1 && value <= MAX_INTEGER.toDouble() &&
                    value == floor(value)
                ) value.toLong() else -1
            }
            // Each schema 19 group is optional and fails independently of schema 18 query evidence.
            fun optionalMetric(name: String, max: Long): Long? {
                val raw = state.opt(name) as? Number ?: return if (!state.has(name)) -1 else null
                val value = raw.toDouble()
                return if (value.isFinite() && value >= -1 && value <= max && value == floor(value)) {
                    value.toLong()
                } else null
            }
            fun work(prefix: String, query: Boolean = false): Work {
                val duration = optionalMetric("${prefix}DurationMs", MAX_TICK_DURATION_MS)
                val maxDuration = optionalMetric("${prefix}MaxPhaseDurationMs", MAX_TICK_DURATION_MS)
                val scans = optionalMetric("${prefix}ScanNodes", MAX_SCAN_NODES)
                val code = if (state.has("${prefix}MaxPhaseCode"))
                    state.opt("${prefix}MaxPhaseCode") as? String else "NOT_APPLICABLE"
                val tail = if (query) optionalMetric("${prefix}TailDurationMs", MAX_TICK_DURATION_MS) else -1L
                if (duration == null || maxDuration == null || scans == null || code == null || tail == null ||
                    !validWork(duration, code, maxDuration, scans) ||
                    (query && (if (duration < 0) tail != -1L else tail !in 0..duration))) return Work()
                return Work(duration, code, maxDuration, scans, tail)
            }
            val bridgeWork = work("geckoBridgeLastTick")
            val startupCount = optionalMetric("geckoStartupObserverTickCount", MAX_QUERY_RVFC_COUNT)
            val startupTotal = optionalMetric("geckoStartupObserverTotalDurationMs", MAX_TICK_DURATION_MS)
            val startupMax = optionalMetric("geckoStartupObserverMaxTickDurationMs", MAX_TICK_DURATION_MS)
            val startupValid = startupCount != null && startupTotal != null && startupMax != null &&
                validStartupWork(startupCount, startupTotal, startupMax)
            val resourceStatus = if (state.has("geckoStartupResourceObservationStatus"))
                state.opt("geckoStartupResourceObservationStatus") as? String else "NOT_APPLICABLE"
            val resourceCount = optionalMetric("geckoStartupResourceCount", 128)
            val resourceDuration = optionalMetric("geckoStartupResourceMaxDurationMs", MAX_TICK_DURATION_MS)
            val resourceWait = optionalMetric("geckoStartupResourceMaxResponseWaitMs", MAX_TICK_DURATION_MS)
            val resourceDownload = optionalMetric("geckoStartupResourceMaxDownloadMs", MAX_TICK_DURATION_MS)
            val resourceDetail = optionalMetric("geckoStartupResourceDetailedCount", 128)
            val resourcesValid = resourceStatus != null && resourceCount != null && resourceDuration != null &&
                resourceWait != null && resourceDownload != null && resourceDetail != null &&
                validResources(resourceStatus, resourceCount, resourceDuration, resourceWait, resourceDownload, resourceDetail)
            val filteredDefaultCode = if (resourcesValid && resourceStatus !in
                setOf("NOT_APPLICABLE", "UNSUPPORTED", "INVALIDATED")) "UNKNOWN" else "NOT_APPLICABLE"
            val resourceScanned = optionalMetric("geckoStartupResourceScannedCount", 1024)
            val resourceFiltered = optionalMetric("geckoStartupResourceFilteredCount", 1024)
            val resourceStart = optionalMetric("geckoStartupResourceMaxStartMs", MAX_TICK_DURATION_MS)
            val resourceEnd = optionalMetric("geckoStartupResourceMaxEndMs", MAX_TICK_DURATION_MS)
            val resourceInitiator = if (state.has("geckoStartupResourceMaxInitiatorCode"))
                state.opt("geckoStartupResourceMaxInitiatorCode") as? String else filteredDefaultCode
            val filteredResourcesValid = resourcesValid && resourceScanned != null && resourceFiltered != null &&
                resourceStart != null && resourceEnd != null && resourceInitiator != null &&
                validFilteredResources(resourceStatus!!, resourceCount!!, resourceDuration!!,
                    resourceScanned, resourceFiltered, resourceStart, resourceEnd, resourceInitiator)
            fun flag(name: String): Boolean = state.opt(name) as? Boolean ?: false
            val startup = GeckoTraceDiagnosticEvidence(
                geckoNodeBoundMs = number("geckoNodeBoundMs"),
                geckoLoadedMetadataMs = number("geckoLoadedMetadataMs"),
                geckoPlayMs = number("geckoPlayMs"),
                geckoPlayingMs = number("geckoPlayingMs"),
                geckoWaitingMs = number("geckoWaitingMs"),
                geckoCanPlayMs = number("geckoCanPlayMs"),
                geckoFirstRvfcMs = number("geckoFirstRvfcMs"),
                geckoFirstRvfcPtsUs = number("geckoFirstRvfcPtsUs"),
                geckoAttachLate = flag("geckoAttachLate"),
                geckoJsTickGapMs = number("geckoJsTickGapMs"),
                geckoDocumentNowMs = number("geckoDocumentNowMs"),
                geckoDocumentTimeOriginMs = number("geckoDocumentTimeOriginMs"),
                geckoStateReceivedEpochMs = hostReceivedEpochMs.takeIf { it in 0..MAX_INTEGER } ?: -1,
                geckoBridgeLastTickDurationMs = bridgeWork.duration,
                geckoBridgeLastTickMaxPhaseCode = bridgeWork.code,
                geckoBridgeLastTickMaxPhaseDurationMs = bridgeWork.maxDuration,
                geckoBridgeLastTickScanNodes = bridgeWork.scans,
                geckoStartupObserverTickCount = if (startupValid) startupCount!! else -1,
                geckoStartupObserverTotalDurationMs = if (startupValid) startupTotal!! else -1,
                geckoStartupObserverMaxTickDurationMs = if (startupValid) startupMax!! else -1,
                geckoStartupResourceObservationStatus = if (resourcesValid) resourceStatus!! else "INVALIDATED",
                geckoStartupResourceCount = if (resourcesValid) resourceCount!! else -1,
                geckoStartupResourceMaxDurationMs = if (resourcesValid) resourceDuration!! else -1,
                geckoStartupResourceMaxResponseWaitMs = if (resourcesValid) resourceWait!! else -1,
                geckoStartupResourceMaxDownloadMs = if (resourcesValid) resourceDownload!! else -1,
                geckoStartupResourceDetailedCount = if (resourcesValid) resourceDetail!! else -1,
                geckoStartupResourceScannedCount = if (filteredResourcesValid) resourceScanned!! else -1,
                geckoStartupResourceFilteredCount = if (filteredResourcesValid) resourceFiltered!! else -1,
                geckoStartupResourceMaxStartMs = if (filteredResourcesValid) resourceStart!! else -1,
                geckoStartupResourceMaxEndMs = if (filteredResourcesValid) resourceEnd!! else -1,
                geckoStartupResourceMaxInitiatorCode = if (filteredResourcesValid) resourceInitiator!! else filteredDefaultCode,
            )
            val queryState = state.optString("geckoAudioQueryState").takeIf { it in STATES }
                ?: return startup
            val disposition = state.optString("geckoAudioQueryDisposition").takeIf { it in DISPOSITIONS }
                ?: return startup
            val queryNumbers = listOf(
                "geckoAudioQuerySeq", "geckoAudioQueryEpoch", "geckoAudioQueryNode", "geckoAudioQueryStartMs",
                "geckoAudioSyncCallMs", "geckoAudioReactionElapsedMs", "geckoAudioTimeoutLateMs",
            )
            // Do not coerce numeric strings or hide a malformed active-query field as unknown.
            if (queryNumbers.any { name ->
                    val raw = (state.opt(name) as? Number)?.toDouble()
                    raw == null || !raw.isFinite() || raw < -1 || raw > MAX_INTEGER.toDouble() || raw != floor(raw)
                } || state.opt("geckoAudioInFlight") !is Boolean
            ) return startup
            val inFlight = flag("geckoAudioInFlight")
            if (queryState == "NOT_APPLICABLE" || queryState == "NOT_STARTED") {
                if (queryNumbers.any { number(it) != -1L } || inFlight) return startup
            } else if (number("geckoAudioQuerySeq") <= 0 || number("geckoAudioQueryEpoch") < 0 ||
                number("geckoAudioQueryNode") < 0 || number("geckoAudioQueryStartMs") < 0
            ) return startup
            // Schema 18 additions are an independent optional group. Older valid schema 17 query
            // evidence remains intact; malformed new metrics become unknown without erasing it.
            val rawRvfcCount = optionalMetric("geckoAudioQueryRvfcCount", MAX_QUERY_RVFC_COUNT)
            val rawRvfcGap = optionalMetric("geckoAudioQueryMaxRvfcGapMs", MAX_QUERY_RVFC_GAP_MS)
            val rvfcMetricsValid = rawRvfcCount != null && rawRvfcGap != null &&
                (rawRvfcCount >= 0 || rawRvfcGap == -1L) &&
                (queryState !in setOf("NOT_APPLICABLE", "NOT_STARTED") ||
                    (rawRvfcCount == -1L && rawRvfcGap == -1L))
            val rvfcCount = if (rvfcMetricsValid) rawRvfcCount!! else -1L
            val rvfcGap = if (rvfcMetricsValid) rawRvfcGap!! else -1L
            val queryWork = if (queryState in setOf("NOT_APPLICABLE", "NOT_STARTED"))
                Work(code = "NOT_APPLICABLE") else work("geckoAudioQueryTick", query = true)
            val observedCount = optionalMetric("geckoAudioQueryObservedWorkCount", MAX_QUERY_RVFC_COUNT)
            val observedTotal = optionalMetric("geckoAudioQueryObservedWorkTotalMs", MAX_TICK_DURATION_MS)
            val observedMax = optionalMetric("geckoAudioQueryObservedWorkMaxMs", MAX_TICK_DURATION_MS)
            val observedPhase = optionalMetric("geckoAudioQueryObservedWorkMaxPhaseMs", MAX_TICK_DURATION_MS)
            val observedScans = optionalMetric("geckoAudioQueryObservedWorkMaxScanNodes", MAX_SCAN_NODES)
            val observedCode = if (state.has("geckoAudioQueryObservedWorkMaxPhaseCode"))
                state.opt("geckoAudioQueryObservedWorkMaxPhaseCode") as? String else "NOT_APPLICABLE"
            val activeQuery = queryState !in setOf("NOT_APPLICABLE", "NOT_STARTED")
            val observedValid = activeQuery && observedCount != null && observedTotal != null && observedMax != null &&
                observedPhase != null && observedScans != null && observedCode != null &&
                validObservedWork(observedCount, observedTotal, observedMax, observedCode, observedPhase, observedScans)
            return startup.copy(
                geckoAudioQuerySeq = number("geckoAudioQuerySeq"),
                geckoAudioQueryEpoch = number("geckoAudioQueryEpoch"),
                geckoAudioQueryNode = number("geckoAudioQueryNode"),
                geckoAudioQueryState = queryState,
                geckoAudioQueryStartMs = number("geckoAudioQueryStartMs"),
                geckoAudioSyncCallMs = number("geckoAudioSyncCallMs"),
                geckoAudioReactionElapsedMs = number("geckoAudioReactionElapsedMs"),
                geckoAudioTimeoutLateMs = number("geckoAudioTimeoutLateMs"),
                geckoAudioInFlight = inFlight,
                geckoAudioQueryDisposition = disposition,
                geckoAudioQueryRvfcCount = rvfcCount,
                geckoAudioQueryMaxRvfcGapMs = rvfcGap,
                geckoAudioQueryTickDurationMs = queryWork.duration,
                geckoAudioQueryTickTailDurationMs = queryWork.tail,
                geckoAudioQueryTickMaxPhaseCode = queryWork.code,
                geckoAudioQueryTickMaxPhaseDurationMs = queryWork.maxDuration,
                geckoAudioQueryTickScanNodes = queryWork.scans,
                geckoAudioQueryObservedWorkCount = if (observedValid) observedCount!! else -1,
                geckoAudioQueryObservedWorkTotalMs = if (observedValid) observedTotal!! else -1,
                geckoAudioQueryObservedWorkMaxMs = if (observedValid) observedMax!! else -1,
                geckoAudioQueryObservedWorkMaxPhaseCode = if (observedValid) observedCode!!
                    else if (activeQuery) "UNKNOWN" else "NOT_APPLICABLE",
                geckoAudioQueryObservedWorkMaxPhaseMs = if (observedValid) observedPhase!! else -1,
                geckoAudioQueryObservedWorkMaxScanNodes = if (observedValid) observedScans!! else -1,
            )
        }
    }
}
