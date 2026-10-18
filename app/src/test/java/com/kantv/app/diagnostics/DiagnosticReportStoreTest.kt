package com.kantv.app.diagnostics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DiagnosticReportStoreTest {
    @Test
    fun foregroundSamplingGateRestartsOnceWithoutDuplicateCallbacks() {
        val gate = DiagnosticSamplingGate()
        assertTrue(gate.start())
        assertFalse(gate.start())
        assertTrue(gate.shouldReschedule())
        assertTrue(gate.stop())
        assertFalse(gate.stop())
        assertFalse(gate.shouldReschedule())
        assertTrue(gate.start())
        assertFalse(gate.start())
        assertTrue(gate.shouldReschedule())
    }

    @Test
    fun slowFullIdentityInventoryDoesNotBlockMinimalStartLane() {
        val fullStarted = CountDownLatch(1)
        val releaseFull = CountDownLatch(1)
        val lanes = DiagnosticIdentityReadLanes {
            fullStarted.countDown()
            releaseFull.await(2, TimeUnit.SECONDS)
            "full"
        }
        val fullThread = Thread { lanes.full() }.apply { start() }
        assertTrue(fullStarted.await(1, TimeUnit.SECONDS))

        assertEquals("minimal", lanes.minimal { "minimal" })
        releaseFull.countDown()
        fullThread.join(1_000)
        assertFalse(fullThread.isAlive)
    }

    @Test
    fun webMainFrameGenerationIsMonotonicAcrossReloads() {
        val counter = DiagnosticGenerationCounter()
        assertEquals(0L, counter.current())
        assertEquals(1L, counter.next())
        assertEquals(2L, counter.next())
        assertEquals(3L, counter.next())
        assertEquals(3L, counter.current())
    }

    @Test
    fun startJournalIsActiveAndNeverVisibleToUploader() {
        withStore { _, store ->
            val start = """{"schemaVersion":3,"reportId":"${id(1)}","reason":"ACTIVE_SESSION","lifecycle":"ACTIVE","samples":[{"kind":"START","elapsedMs":10}]}"""
            val active = requireNotNull(store.writeActive(id(1), start.toByteArray()))
            val checkpoint = start.replace("START", "CHECKPOINT")
            requireNotNull(store.writeActive(id(1), checkpoint.toByteArray()))

            assertTrue(active.isFile)
            assertTrue(active.readText().contains("\"kind\":\"CHECKPOINT\""))
            assertEquals(1, store.activeReports().size)
            assertTrue(store.pendingReports().isEmpty())
        }
    }

    @Test
    fun slowRecoveryCannotQueueAheadOfMinimalStartWrite() {
        val recoveryQueue = mutableListOf<() -> Unit>()
        val observed = mutableListOf<String>()
        val dispatcher = DiagnosticStartupDispatcher(
            startExecutor = { it() },
            recoveryExecutor = { recoveryQueue += it },
        )

        dispatcher.dispatchRecovery { observed += "recovery" }
        dispatcher.dispatchStart { observed += "start" }

        assertEquals(listOf("start"), observed)
        recoveryQueue.single().invoke()
        assertEquals(listOf("start", "recovery"), observed)
    }

    @Test
    fun checkpointIsDueAtSixtySecondsAndCriticalEventsFlushImmediately() {
        assertFalse(DiagnosticJournalPolicy.checkpointDue(1_000, 60_999))
        assertTrue(DiagnosticJournalPolicy.checkpointDue(1_000, 61_000))
        assertTrue(DiagnosticJournalPolicy.flushImmediately(DiagnosticEntryKind.START))
        assertTrue(DiagnosticJournalPolicy.flushImmediately(DiagnosticEntryKind.ROUTE_CHANGED))
        assertTrue(DiagnosticJournalPolicy.flushImmediately(DiagnosticEntryKind.AUDIO_TRACK_CHANGED))
        assertTrue(DiagnosticJournalPolicy.flushImmediately(DiagnosticEntryKind.PLAYBACK_FAULT))
        assertFalse(DiagnosticJournalPolicy.flushImmediately(DiagnosticEntryKind.SAMPLE))
    }

    @Test
    fun recoversAndSealsActiveJournalAfterRestart() {
        withStore { _, store ->
            val journal = """{"schemaVersion":3,"reportId":"${id(1)}","reason":"ACTIVE_SESSION","lifecycle":"ACTIVE","closedAtMs":-1,"samples":[{"kind":"START","elapsedMs":10}]}"""
            requireNotNull(store.writeActive(id(1), journal.toByteArray()))

            val recovered = store.recoverActiveReports()

            assertEquals(1, recovered.size)
            assertTrue(store.activeReports().isEmpty())
            val sealed = recovered.single().readText()
            assertTrue(sealed.contains("PROCESS_TERMINATED"))
            assertTrue(sealed.contains("RECOVERED_AFTER_PROCESS_DEATH"))
            assertTrue(sealed.contains("PROCESS_RECOVERED"))
            assertEquals(recovered, store.pendingReports())
        }
    }

    @Test
    fun recoveryKeepsCompletedStopAndOnlyCleansCrashWindowActiveCopy() {
        withStore { _, store ->
            val reportId = id(7)
            val active = """{"reportId":"$reportId","reason":"ACTIVE_SESSION"}"""
            val stopped = """{"reportId":"$reportId","reason":"STOPPED","lifecycle":"COMPLETE"}"""
            requireNotNull(store.writeActive(reportId, active.toByteArray()))
            val completed = requireNotNull(store.writeReport(
                reportId, DiagnosticReportReason.STOPPED, stopped.toByteArray(),
            ))

            assertTrue(store.recoverActiveReports().isEmpty())
            assertTrue(store.activeReports().isEmpty())
            assertEquals(stopped, completed.readText())
            assertEquals(listOf(completed), store.pendingReports())
        }
    }

    @Test
    fun processRecoveryRunsOnceAndSkipsLiveRecorderJournal() {
        ProcessDiagnosticRecoveryGate.resetForTest()
        withStore { _, store ->
            val journal = """{"schemaVersion":3,"reportId":"${id(1)}","reason":"ACTIVE_SESSION","lifecycle":"ACTIVE","closedAtMs":-1,"samples":[{"kind":"START","elapsedMs":10}]}"""
            requireNotNull(store.writeActive(id(1), journal.toByteArray()))
            ProcessDiagnosticRecoveryGate.register(id(1))

            assertTrue(ProcessDiagnosticRecoveryGate.claimRecovery())
            assertFalse(ProcessDiagnosticRecoveryGate.claimRecovery())
            assertTrue(store.recoverActiveReports { id -> !ProcessDiagnosticRecoveryGate.isActive(id) }.isEmpty())
            assertEquals(1, store.activeReports().size)

            ProcessDiagnosticRecoveryGate.unregister(id(1))
            assertEquals(1, store.recoverActiveReports { id -> !ProcessDiagnosticRecoveryGate.isActive(id) }.size)
        }
        ProcessDiagnosticRecoveryGate.resetForTest()
    }

    @Test
    fun thirtyMinuteSessionProducesASequenceOfMinuteBoundaries() {
        var lastCheckpoint = 0L
        val boundaries = mutableListOf<Long>()
        for (minute in 1..30) {
            val now = minute * 60_000L
            if (DiagnosticJournalPolicy.checkpointDue(lastCheckpoint, now)) {
                boundaries += now
                lastCheckpoint = now
            }
        }
        assertEquals(30, boundaries.size)
        assertEquals(60_000L, boundaries.first())
        assertEquals(1_800_000L, boundaries.last())
    }

    @Test
    fun writeFailureIsStructuredAndVisibleToCaller() {
        val root = Files.createTempFile("kantv-diagnostics-blocked", ".file").toFile()
        val failures = mutableListOf<String>()
        try {
            val store = DiagnosticReportStore(root = root, onFailure = failures::add)
            assertNull(store.writeActive(id(1), "{}".toByteArray()))
            assertEquals(listOf("REPORT_DIRECTORY_FAILED"), failures)
        } finally {
            root.delete()
        }
    }

    @Test
    fun retentionDeletesSentBeforePendingAndProtectsActive() {
        withStore(maxReportCount = 2) { _, store ->
            val sentFirst = requireNotNull(store.writeReport(id(1), DiagnosticReportReason.STOPPED, "{}".toByteArray()))
            assertTrue(store.markUploaded(sentFirst))
            val active = requireNotNull(store.writeActive(id(2), "{}".toByteArray()))
            val pending = requireNotNull(store.writeReport(id(3), DiagnosticReportReason.STOPPED, "{}".toByteArray()))

            assertTrue(active.exists())
            assertEquals(listOf(pending), store.pendingReports())
        }
    }

    @Test
    fun activeWritesStayBoundedWithoutDeletingTheCurrentJournal() {
        withStore(maxTotalBytes = 10, maxReportBytes = 10, maxReportCount = 2) { root, store ->
            val stale = requireNotNull(store.writeActive(id(1), ByteArray(6)))
            stale.setLastModified(1)
            val current = requireNotNull(store.writeActive(id(2), ByteArray(6)))

            assertFalse(stale.exists())
            assertTrue(current.exists())
            assertEquals(listOf(current.name), store.activeReports().map { it.name })
            assertTrue(root.walkTopDown().filter(File::isFile).sumOf(File::length) <= 10)
        }
    }

    @Test
    fun anActiveSnapshotLargerThanTheWholeBudgetIsRejectedWithoutAWrite() {
        val failures = mutableListOf<String>()
        val root = Files.createTempDirectory("kantv-diagnostics-budget-test").toFile()
        try {
            val store = DiagnosticReportStore(
                root = root,
                maxTotalBytes = 4,
                maxReportBytes = 10,
                onFailure = failures::add,
            )
            assertNull(store.writeActive(id(1), ByteArray(5)))
            assertEquals(listOf("ACTIVE_TOTAL_LIMIT"), failures)
            assertTrue(root.walkTopDown().none(File::isFile))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun finalizedCheckpointIsImmutableWhileNextFragmentStaysActive() {
        withStore { _, store ->
            val first = id(1)
            val second = id(2)
            requireNotNull(store.writeActive(first, "first-active".toByteArray()))
            val checkpoint = requireNotNull(store.finalizeActive(
                first, DiagnosticReportReason.CHECKPOINT, "first-checkpoint".toByteArray(),
            ))
            requireNotNull(store.writeActive(second, "second-active".toByteArray()))

            assertEquals("first-checkpoint", checkpoint.readText())
            assertEquals(listOf(checkpoint), store.pendingReports())
            assertEquals(listOf("$second.json"), store.activeReports().map { it.name })
        }
    }

    @Test
    fun serializedSchemaHasNoSecretOrMediaAddressFields() {
        val forbidden = listOf("url", "uri", "token", "cookie", "header", "ssid", "bssid", "mac", "log")
        assertTrue(DIAGNOSTIC_SERIALIZED_KEYS.none { key ->
            val lowered = key.lowercase()
            forbidden.any { forbiddenPart ->
                lowered == forbiddenPart || lowered.startsWith(forbiddenPart) || lowered.endsWith(forbiddenPart)
            }
        })
    }

    @Test
    fun rejectsOversizedReportsAndFilesOutsideQueue() {
        withStore(maxReportBytes = 16) { root, store ->
            assertNull(store.writeReport(id(1), DiagnosticReportReason.NO_FIRST_FRAME_30S, ByteArray(17)))
            val report = requireNotNull(store.writeReport(
                id(2),
                DiagnosticReportReason.NO_FIRST_FRAME_30S,
                "{}".toByteArray(),
            ))
            assertEquals(listOf(report), store.pendingReports())

            val outside = root.resolve("outside.json").apply { writeText("{}") }
            assertFalse(store.markUploaded(outside))
            assertTrue(store.markUploaded(report))
            assertTrue(store.pendingReports().isEmpty())
        }
    }

    @Test
    fun removesExpiredAndOldestFilesToStayWithinTotalBound() {
        var now = 10_000L
        withStore(now = { now }, retentionMs = 1_000, maxTotalBytes = 20, maxReportBytes = 20) { _, store ->
            val expired = requireNotNull(store.writeReport(id(1), DiagnosticReportReason.NO_FIRST_FRAME_30S, ByteArray(10)))
            expired.setLastModified(8_000)
            assertTrue(store.pendingReports().isEmpty())

            val first = requireNotNull(store.writeReport(id(2), DiagnosticReportReason.NO_FIRST_FRAME_30S, ByteArray(10)))
            first.setLastModified(9_100)
            val second = requireNotNull(store.writeReport(id(3), DiagnosticReportReason.NO_FIRST_FRAME_30S, ByteArray(10)))
            second.setLastModified(9_200)
            val third = requireNotNull(store.writeReport(id(4), DiagnosticReportReason.NO_FIRST_FRAME_30S, ByteArray(10)))
            third.setLastModified(9_300)

            assertEquals(listOf(second.name, third.name), store.pendingReports().map { it.name })
            assertFalse(first.exists())
        }
    }

    @Test
    fun keepsOnlyBoundedRecentReports() {
        withStore(maxReportCount = 2) { _, store ->
            val content = "{\"reason\":\"CHANNEL_SWITCH\"}".toByteArray()
            val first = requireNotNull(store.writeReport(id(1), DiagnosticReportReason.CHANNEL_SWITCH, content))
            first.setLastModified(1)
            val second = requireNotNull(store.writeReport(id(2), DiagnosticReportReason.CHANNEL_SWITCH, content))
            second.setLastModified(2)
            val third = requireNotNull(store.writeReport(id(3), DiagnosticReportReason.CHANNEL_SWITCH, content))

            assertEquals(listOf(second.name, third.name), store.pendingReports().map { it.name })
            assertFalse(first.exists())
        }
    }

    @Test
    fun exportIncludesOnlyBoundedRecentPendingAndActiveJson() {
        withStore { _, store ->
            val old = requireNotNull(store.writeReport(id(1), DiagnosticReportReason.CHANNEL_SWITCH, "old".toByteArray()))
            old.setLastModified(1)
            val sent = requireNotNull(store.writeReport(id(2), DiagnosticReportReason.STOPPED, "sent".toByteArray()))
            sent.setLastModified(2)
            assertTrue(store.markUploaded(sent))
            val recent = requireNotNull(store.writeReport(id(3), DiagnosticReportReason.STOPPED, "recent".toByteArray()))
            recent.setLastModified(3)
            val active = requireNotNull(store.writeActive(id(4), "active".toByteArray()))
            active.setLastModified(4)

            assertEquals(
                listOf(recent.name, active.name),
                store.exportableReports(maxFiles = 2, maxBytes = 12).map { it.name },
            )
            assertFalse(store.exportableReports(maxFiles = 2, maxBytes = 12).contains(sent))
        }
    }

    private fun withStore(
        now: () -> Long = { 10_000L },
        retentionMs: Long = 100_000,
        maxTotalBytes: Long = 1_000,
        maxReportBytes: Long = 512,
        maxReportCount: Int = 20,
        block: (java.io.File, DiagnosticReportStore) -> Unit,
    ) {
        val root = Files.createTempDirectory("kantv-diagnostics-test").toFile()
        try {
            block(root, DiagnosticReportStore(
                root = root,
                nowEpochMs = now,
                retentionMs = retentionMs,
                maxTotalBytes = maxTotalBytes,
                maxReportBytes = maxReportBytes,
                maxReportCount = maxReportCount,
            ))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun id(value: Int): String = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
