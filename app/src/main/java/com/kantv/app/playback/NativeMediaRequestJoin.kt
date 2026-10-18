package com.kantv.app.playback

/** Only exact in-process reference equality is used. Request objects never enter a snapshot. */
internal class NativeMediaRequestJoin {
    internal class Request internal constructor(internal var identity: Any?) {
        internal var taskId: Long? = null
        internal var nonMedia = false
        internal var opened = false
        internal var unknown: String? = null
        internal var summary: NativeTransferSummary? = null
    }
    data class Observation(val status: String, val transfer: NativeTransferSummary? = null)
    private val candidates = ArrayList<Request>(MAX_CANDIDATES)
    private var failure: String? = null
    private var lastCompleted: Request? = null
    @Volatile var observation = Observation("NONE")
        private set

    @Synchronized fun opened(identity: Any): Request? {
        if (failure != null) return null
        val candidate = candidates.firstOrNull { it.identity === identity } ?: newCandidate(identity) ?: return null
        if (candidate.nonMedia) return candidate
        if (candidate.opened) {
            fail("UNKNOWN_AMBIGUOUS")
            return null
        }
        candidate.opened = true
        return candidate
    }

    @Synchronized fun started(taskId: Long, identity: Any?, retryCount: Long) {
        if (failure != null) return
        val tracked = candidates.firstOrNull { it.taskId == taskId }
        if (tracked != null) {
            if (tracked.unknown == "UNKNOWN_RETRY" || retryCount > 0) {
                markRetry(tracked)
            } else {
                fail("UNKNOWN_AMBIGUOUS")
            }
            return
        }
        if (identity == null && retryCount <= 0) return
        if (retryCount > 0) {
            val retry = newCandidate(null) ?: return
            retry.taskId = taskId
            markRetry(retry)
            return
        }
        val candidate = candidates.firstOrNull { it.identity === identity } ?: newCandidate(identity) ?: return
        if (candidate.taskId != null || candidate.nonMedia) {
            fail("UNKNOWN_AMBIGUOUS")
            return
        }
        candidate.taskId = taskId
    }

    @Synchronized fun terminal(taskId: Long, outcome: String) {
        if (failure != null) return
        val candidate = candidates.firstOrNull { it.taskId == taskId }
        if (outcome == "ERROR_RETRYING") {
            // Keep no retry request identity. Task timing remains the tracker's independent fact.
            lastCompleted = null
            observation = Observation("UNKNOWN_RETRY")
            if (candidate != null) markRetry(candidate)
            return
        }
        if (outcome == "COMPLETED") {
            lastCompleted = candidate
            observation = when {
                candidate?.unknown != null -> Observation(candidate.unknown!!)
                candidate == null || !candidate.opened -> Observation("UNASSIGNED")
                candidate.summary == null -> Observation("PENDING")
                else -> Observation("MATCHED", candidate.summary)
            }
        }
        if (outcome != "COMPLETED") {
            lastCompleted = null
            observation = Observation("UNASSIGNED")
        }
        if (candidate != null) {
            candidates.remove(candidate)
            candidate.identity = null
            candidate.taskId = null
        }
    }

    @Synchronized fun closed(request: Request?, summary: NativeTransferSummary) {
        if (request == null || failure != null || request.unknown != null) return
        request.summary = summary
        if (lastCompleted === request) observation = Observation("MATCHED", summary)
        // Unclassified closed requests remain bounded: analytics may arrive after a fast close.
        if (request.nonMedia) {
            candidates.remove(request)
            request.identity = null
        }
    }

    @Synchronized fun nonMedia(identity: Any, terminal: Boolean) {
        if (failure != null) return
        val candidate = candidates.firstOrNull { it.identity === identity }
            ?: if (terminal) return else newCandidate(identity) ?: return
        if (candidate.taskId != null) {
            fail("UNKNOWN_AMBIGUOUS")
            return
        }
        candidate.nonMedia = true
        if (terminal || candidate.summary != null) {
            candidates.remove(candidate)
            candidate.identity = null
        }
    }

    @Synchronized fun invalidate() { fail("INVALIDATED") }

    @Synchronized fun capacityExceeded() { fail("UNKNOWN_CAPACITY") }

    private fun markRetry(request: Request) {
        request.identity = null
        request.summary = null
        request.unknown = "UNKNOWN_RETRY"
    }

    private fun newCandidate(identity: Any?): Request? {
        if (candidates.size == MAX_CANDIDATES) {
            fail("UNKNOWN_CAPACITY")
            return null
        }
        return Request(identity).also { candidates.add(it) }
    }

    private fun fail(status: String) {
        failure = status
        candidates.forEach { it.identity = null; it.taskId = null; it.summary = null }
        candidates.clear()
        lastCompleted = null
        observation = Observation(status)
    }

    companion object { const val MAX_CANDIDATES = 8 }
}
