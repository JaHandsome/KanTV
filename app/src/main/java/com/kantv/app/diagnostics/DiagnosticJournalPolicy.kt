package com.kantv.app.diagnostics

internal object DiagnosticJournalPolicy {
    const val CHECKPOINT_INTERVAL_MS = 60_000L
    const val MAX_REPORT_BYTES = 512 * 1024
    // Identity is measured in UTF-8, including codec MIME strings that need not be ASCII.
    const val MAX_SAMPLE_BYTES = 300 * 1024
    // Between append and seal only bounded runtime/display/network facts and numeric widths vary.
    // The cached codec inventory and immutable device identity already belong to the measured envelope.
    private const val ENVELOPE_CHANGE_RESERVE_BYTES = 8 * 1024
    const val MAX_FRAGMENT_ENTRIES = 119
    fun sampleBudgetForEnvelope(envelopeBytes: Int): Int =
        minOf(MAX_SAMPLE_BYTES, MAX_REPORT_BYTES - envelopeBytes - ENVELOPE_CHANGE_RESERVE_BYTES).coerceAtLeast(0)

    fun checkpointBeforeAppend(count: Int, bytes: Long, nextBytes: Int, checkpointBytes: Int,
                               sampleBudget: Int = MAX_SAMPLE_BYTES): Boolean =
        count + 2 > MAX_FRAGMENT_ENTRIES ||
            bytes + nextBytes + maxOf(checkpointBytes, nextBytes + 64) + 2 > sampleBudget
    fun checkpointDue(lastPersistedElapsedMs: Long, nowElapsedMs: Long): Boolean =
        lastPersistedElapsedMs < 0 || nowElapsedMs - lastPersistedElapsedMs >= CHECKPOINT_INTERVAL_MS
    fun flushImmediately(kind: DiagnosticEntryKind): Boolean = when (kind) {
        DiagnosticEntryKind.SAMPLE,
        DiagnosticEntryKind.BUFFERING_STARTED,
        DiagnosticEntryKind.BUFFERING_ENDED,
        -> false
        else -> true
    }
}

/** Writer-owned producer clock. Synthetic checkpoints never advance it to writer wall time. */
internal class DiagnosticProducerWatermark(startElapsedMs: Long) {
    var elapsedMs: Long = startElapsedMs
        private set
    var hasUncheckpointedEntries: Boolean = false
        private set

    fun accept(producerElapsedMs: Long) {
        elapsedMs = maxOf(elapsedMs, producerElapsedMs)
        hasUncheckpointedEntries = true
    }

    fun checkpointed() {
        hasUncheckpointedEntries = false
    }
}

/** Writer-lane cost only; independent of Gecko TaskQueue and bounded to one slow log per 15 seconds. */
internal class DiagnosticWriterTimingPolicy {
    private var lastLoggedElapsedMs = Long.MIN_VALUE

    fun shouldLog(startMs: Long, queueMs: Long, executeMs: Long): Boolean {
        if (queueMs < 100L && executeMs < 100L) return false
        if (lastLoggedElapsedMs != Long.MIN_VALUE && startMs - lastLoggedElapsedMs < 15_000L) return false
        lastLoggedElapsedMs = startMs
        return true
    }
}
