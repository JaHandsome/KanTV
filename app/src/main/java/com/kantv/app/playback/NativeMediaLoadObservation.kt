package com.kantv.app.playback

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class NativeLoadTaskTiming(
    val trackType: String,
    val startElapsedMs: Long,
    val endElapsedMs: Long = -1,
    val mediaStartMs: Long,
    val mediaEndMs: Long,
    val retryCount: Long,
    val bytes: Long = -1,
    val httpStatus: Long = -1,
)

data class NativeTransferSummary(
    val sequence: Long,
    val outcome: String,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val openDurationUs: Long,
    val readDurationUs: Long,
    val closeDurationUs: Long,
    val readCallCount: Long,
    val bytes: Long,
    val eofSeen: Boolean,
)

data class NativeMediaLoadObservation(
    val activeStatus: String,
    val active: NativeLoadTaskTiming?,
    val lastOutcome: String,
    val last: NativeLoadTaskTiming?,
    val positionStatus: String,
    val windowStartInPeriodMs: Long,
    val transferActiveCount: Long,
    val transferOutcome: String,
    val transfer: NativeTransferSummary?,
    val mediaRequestMatchStatus: String = "NOT_APPLICABLE",
    val mediaRequestTransfer: NativeTransferSummary? = null,
)

/** Immutable ownership token. Counters change only at transfer open/close boundaries, never read. */
internal class NativeTransferEpoch(val generation: Long, private val sequence: AtomicLong) {
    val activeCount = AtomicInteger(0)
    val mediaRequestJoin = NativeMediaRequestJoin()
    fun begin(): Long {
        activeCount.incrementAndGet()
        return sequence.incrementAndGet()
    }
}

/** Owner-thread task facts; the epoch join synchronizes loader boundaries. Identities stay private. */
internal class NativeMediaLoadTracker {
    private data class Record(val periodUid: Any?, val timing: NativeLoadTaskTiming, val retrying: Boolean)
    private val tasks = LinkedHashMap<Long, Record>()
    private val invalidatedTasks = LinkedHashSet<Long>()
    private var invalidatedTaskCapacityExceeded = false
    private var last: Record? = null
    private var lastOutcome = "NONE"
    private var capacityExceeded = false
    private val transferSequence = AtomicLong(0)
    private data class Ownership(val pipeline: Any, val epoch: NativeTransferEpoch)
    // Loader threads must see the token and epoch from the same publication.
    @Volatile private var ownership = Ownership(Any(), NativeTransferEpoch(-1, transferSequence))
    private var transferEnabled = false
    private var lastTransfer: NativeTransferSummary? = null
    private var transferOutcome = "NONE"

    fun activate(generation: Long) {
        clearObservations(rememberTasks = false)
        transferEnabled = false
        lastOutcome = "NONE"
        transferOutcome = "NONE"
        ownership = Ownership(Any(), NativeTransferEpoch(generation, transferSequence))
    }

    fun preparePipeline(generation: Long, enableTransfer: Boolean): Any {
        clearObservations(rememberTasks = true)
        capacityExceeded = false
        transferEnabled = enableTransfer
        transferOutcome = if (enableTransfer) "NONE" else "NA"
        val next = Ownership(Any(), NativeTransferEpoch(generation, transferSequence))
        if (invalidatedTaskCapacityExceeded) next.epoch.mediaRequestJoin.capacityExceeded()
        ownership = next
        return next.pipeline
    }

    fun invalidate(generation: Long = ownership.epoch.generation) {
        val currentOwnership = ownership
        clearObservations(rememberTasks = true)
        val next = Ownership(currentOwnership.pipeline, NativeTransferEpoch(generation, transferSequence))
        if (invalidatedTaskCapacityExceeded) next.epoch.mediaRequestJoin.capacityExceeded()
        ownership = next
    }

    private fun clearObservations(rememberTasks: Boolean) {
        ownership.epoch.mediaRequestJoin.invalidate()
        if (rememberTasks) {
            tasks.keys.forEach { taskId ->
                if (taskId !in invalidatedTasks && invalidatedTasks.size == MAX_ACTIVE_TASKS) {
                    invalidatedTaskCapacityExceeded = true
                } else {
                    invalidatedTasks.add(taskId)
                }
            }
        } else {
            invalidatedTasks.clear()
            invalidatedTaskCapacityExceeded = false
            capacityExceeded = false
        }
        tasks.clear()
        last = null
        lastOutcome = "INVALIDATED"
        lastTransfer = null
        transferOutcome = "INVALIDATED"
    }

    fun captureTransferEpoch(generation: Long, pipeline: Any): NativeTransferEpoch? {
        val currentOwnership = ownership
        return currentOwnership.epoch.takeIf {
            it.generation == generation && pipeline === currentOwnership.pipeline
        }
    }

    fun transferFinished(epoch: NativeTransferEpoch, summary: NativeTransferSummary): Boolean {
        if (epoch !== ownership.epoch) return false
        lastTransfer = summary
        transferOutcome = summary.outcome
        return true
    }

    fun nonMediaRequest(identity: Any, terminal: Boolean) {
        ownership.epoch.mediaRequestJoin.nonMedia(identity, terminal)
    }

    fun started(taskId: Long, periodUid: Any?, timing: NativeLoadTaskTiming, requestIdentity: Any? = null) {
        if (capacityExceeded || taskId in invalidatedTasks) return
        ownership.epoch.mediaRequestJoin.started(taskId, requestIdentity, timing.retryCount)
        if (taskId !in tasks && tasks.size == MAX_ACTIVE_TASKS) {
            ownership.epoch.mediaRequestJoin.capacityExceeded()
            capacityExceeded = true
            tasks.clear()
            return
        }
        tasks[taskId] = Record(periodUid, timing, timing.retryCount > 0)
    }

    fun finished(taskId: Long, periodUid: Any?, timing: NativeLoadTaskTiming, outcome: String) {
        // Unknown terminal callbacks cannot resurrect a task invalidated by seek or source replacement.
        val tracked = tasks[taskId] ?: return
        ownership.epoch.mediaRequestJoin.terminal(taskId, outcome)
        val completed = timing.copy(retryCount = tracked.timing.retryCount)
        last = Record(periodUid, completed, outcome == "ERROR_RETRYING")
        lastOutcome = outcome
        if (outcome == "ERROR_RETRYING") {
            tasks[taskId] = tracked.copy(retrying = true)
        } else {
            tasks.remove(taskId)
        }
    }

    fun snapshot(
        currentPeriodUid: Any?,
        periodCount: Int,
        windowStartInPeriodMs: Long?,
        playbackPositionMs: Long,
        windowDurationMs: Long,
    ): NativeMediaLoadObservation {
        val active = tasks.values.minByOrNull { it.timing.startElapsedMs }
        val activeStatus = when {
            capacityExceeded -> "UNKNOWN_CAPACITY"
            active == null -> "NONE"
            active.periodUid == null -> "UNKNOWN_OWNERSHIP"
            active.retrying -> "RETRYING"
            else -> "LOADING"
        }
        val reported = listOfNotNull(active, last)
        val positionStatus = when {
            capacityExceeded -> "UNKNOWN_NO_PERIOD"
            periodCount > 1 -> "UNKNOWN_MULTI_PERIOD"
            periodCount != 1 || currentPeriodUid == null || reported.isEmpty() || reported.any { it.periodUid == null } -> "UNKNOWN_NO_PERIOD"
            reported.any { it.periodUid != currentPeriodUid } -> "UNKNOWN_CHANGED_PERIOD"
            windowStartInPeriodMs == null || windowDurationMs < 0 || reported.any { it.timing.mediaStartMs < 0 || it.timing.mediaEndMs < it.timing.mediaStartMs } -> "UNKNOWN_TIME"
            // A lost/negative window-relative cursor must not erase independently known window bounds.
            playbackPositionMs < 0 -> "WINDOW_ONLY"
            else -> "AVAILABLE"
        }
        val mediaRequest = ownership.epoch.mediaRequestJoin.observation
        return NativeMediaLoadObservation(
            activeStatus = activeStatus,
            active = active?.timing?.takeIf { activeStatus in setOf("LOADING", "RETRYING") },
            lastOutcome = lastOutcome,
            last = last?.timing,
            positionStatus = positionStatus,
            windowStartInPeriodMs = windowStartInPeriodMs?.takeIf { positionStatus in setOf("AVAILABLE", "WINDOW_ONLY") } ?: -1,
            transferActiveCount = if (transferEnabled) ownership.epoch.activeCount.get().toLong() else -1,
            transferOutcome = if (transferEnabled) transferOutcome else "NA",
            transfer = lastTransfer.takeIf { transferEnabled },
            mediaRequestMatchStatus = if (transferEnabled) mediaRequest.status else "NOT_APPLICABLE",
            mediaRequestTransfer = mediaRequest.transfer.takeIf { transferEnabled },
        )
    }

    companion object { const val MAX_ACTIVE_TASKS = 8 }
}
