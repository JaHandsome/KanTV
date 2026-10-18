package com.kantv.app.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticJournalBudgetTest {
    @Test fun unicodeEnvelopeReducesTheBudgetUsingBytesAndStillRotatesWithoutLoss() {
        val envelope = ("中".repeat(148_000) + "header").toByteArray(Charsets.UTF_8).size
        val budget = DiagnosticJournalPolicy.sampleBudgetForEnvelope(envelope)
        assertTrue(budget < DiagnosticJournalPolicy.MAX_SAMPLE_BYTES)
        val input = (1L..40L).map { DiagnosticLogEntry(DiagnosticEntryKind.GENERIC_EVENT, it,
            DiagnosticPlaybackValues(timeMs = it)) }
        val retained = mutableListOf<DiagnosticLogEntry>()
        var bytes = 0L
        var count = 0
        var rotations = 0
        for (entry in input) {
            val checkpoint = DiagnosticLogEntry(DiagnosticEntryKind.CHECKPOINT, entry.elapsedMs, entry.values)
            if (DiagnosticJournalPolicy.checkpointBeforeAppend(count, bytes,
                    entry.serializedByteCount, checkpoint.serializedByteCount, budget)) {
                assertTrue(envelope + bytes + checkpoint.serializedByteCount + 1 < DiagnosticJournalPolicy.MAX_REPORT_BYTES)
                bytes = checkpoint.serializedByteCount + 1L
                count = 1
                rotations++
            }
            retained.add(entry)
            bytes += entry.serializedByteCount + 1L
            count++
        }
        assertTrue(rotations > 1)
        assertEquals(input, retained)
        assertTrue(envelope + bytes < DiagnosticJournalPolicy.MAX_REPORT_BYTES)
        assertEquals(0, DiagnosticJournalPolicy.sampleBudgetForEnvelope(DiagnosticJournalPolicy.MAX_REPORT_BYTES))
    }

    @Test fun denseJournalRotatesBeforeTheByteLimitWithoutDroppingRecords() {
        val input = (1L..240L).map { i ->
            DiagnosticLogEntry(DiagnosticEntryKind.GENERIC_EVENT, i,
                DiagnosticPlaybackValues(timeMs = i, frames = i))
        }
        val fragments = mutableListOf<List<DiagnosticLogEntry>>()
        var entries = mutableListOf<DiagnosticLogEntry>()
        var bytes = 0L
        fun rotate(checkpoint: DiagnosticLogEntry) {
            entries.add(checkpoint)
            assertTrue(bytes + checkpoint.serializedByteCount + 1 <= DiagnosticJournalPolicy.MAX_SAMPLE_BYTES)
            fragments.add(entries.toList())
            entries = mutableListOf(checkpoint)
            bytes = checkpoint.serializedByteCount + 1L
        }
        for (entry in input) {
            val values = entries.lastOrNull()?.values ?: DiagnosticPlaybackValues.EMPTY
            val checkpoint = DiagnosticLogEntry(DiagnosticEntryKind.CHECKPOINT, entry.elapsedMs, values)
            if (DiagnosticJournalPolicy.checkpointBeforeAppend(
                    entries.size, bytes, entry.serializedByteCount, checkpoint.serializedByteCount,
                )) rotate(checkpoint)
            entries.add(entry)
            bytes += entry.serializedByteCount + 1L
        }
        fragments.add(entries.toList())
        assertTrue(fragments.size > 1)
        assertEquals(input, fragments.flatten().filter { it.kind != DiagnosticEntryKind.CHECKPOINT })
        fragments.forEach { assertTrue(it.size <= DiagnosticJournalPolicy.MAX_FRAGMENT_ENTRIES) }
    }

    @Test fun theNextBoundaryAndRecoverySealHaveReservedSlots() {
        assertFalse(DiagnosticJournalPolicy.checkpointBeforeAppend(117, 100, 100, 100))
        assertTrue(DiagnosticJournalPolicy.checkpointBeforeAppend(118, 100, 100, 100))
        assertTrue(DiagnosticJournalPolicy.checkpointBeforeAppend(2,
            DiagnosticJournalPolicy.MAX_SAMPLE_BYTES - 120L, 100, 100))
    }
}
