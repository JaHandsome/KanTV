package com.kantv.app.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticWriterPolicyTest {
    @Test fun delayedWriterAndStopKeepFragmentBoundariesOnProducerTime() {
        val watermark = DiagnosticProducerWatermark(100)
        val producerTimes = listOf(100L, 2_000L, 4_000L)
        producerTimes.forEach(watermark::accept)
        // At writer time 90 seconds, a periodic checkpoint may seal only what it has consumed.
        assertTrue(DiagnosticJournalPolicy.checkpointDue(100, 90_000))
        val checkpointEnd = watermark.elapsedMs
        assertEquals(4_000L, checkpointEnd)
        watermark.checkpointed()
        assertFalse(watermark.hasUncheckpointedEntries)
        watermark.accept(4_500) // Queued STOP produced before the delayed writer's checkpoint ran.
        assertTrue(watermark.elapsedMs >= checkpointEnd)
        assertEquals(4_500L, watermark.elapsedMs)
        assertEquals(listOf(100L, 2_000L, 4_000L), producerTimes)
    }

    @Test fun byteRotationAndOutOfOrderProducerDoNotAdvanceOrReverseTheSealClock() {
        val watermark = DiagnosticProducerWatermark(500)
        watermark.accept(900)
        val byteCheckpoint = watermark.elapsedMs
        watermark.checkpointed()
        watermark.accept(800)
        // Preserve the actual producer entry time even when it arrives late; only the seal uses max.
        assertEquals(900L, byteCheckpoint)
        assertEquals(900L, watermark.elapsedMs)
        assertTrue(watermark.hasUncheckpointedEntries)
        watermark.checkpointed()
        assertFalse(watermark.hasUncheckpointedEntries)
    }

    @Test fun writerSlowLogsRequireActualDelayAndAreLimitedToOnePerFifteenSeconds() {
        val policy = DiagnosticWriterTimingPolicy()
        assertFalse(policy.shouldLog(10_000, 99, 99))
        assertTrue(policy.shouldLog(10_000, 100, 0))
        repeat(299) { assertFalse(policy.shouldLog(10_000 + it * 50L, 5_000, 500)) }
        assertTrue(policy.shouldLog(25_000, 0, 100))
        assertFalse(policy.shouldLog(40_000, 0, 0))
        assertTrue(policy.shouldLog(40_000, 100, 0))
    }
}
