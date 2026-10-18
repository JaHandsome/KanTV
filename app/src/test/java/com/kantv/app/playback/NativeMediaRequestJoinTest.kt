package com.kantv.app.playback

import org.junit.Assert.*
import org.junit.Test

class NativeMediaRequestJoinTest {
    private fun summary(bytes: Long = 400) = NativeTransferSummary(1, "CLOSED_EOF", 100, 200, 10, 20, 30, 2, bytes, true)

    @Test fun exactReferenceJoinsEveryOpenStartAndCloseTerminalOrdering() {
        for (startFirst in listOf(false, true)) for (terminalFirst in listOf(false, true)) {
            val join = NativeMediaRequestJoin()
            val spec = Any()
            if (startFirst) join.started(1, spec, 0)
            val request = join.opened(spec)
            if (!startFirst) join.started(1, spec, 0)
            if (terminalFirst) join.terminal(1, "COMPLETED")
            join.closed(request, summary())
            if (!terminalFirst) join.terminal(1, "COMPLETED")
            assertEquals("MATCHED", join.observation.status)
            assertEquals(20L, join.observation.transfer!!.readDurationUs)
            assertNull(request!!.identity)
            assertNull(request.taskId)
        }
    }

    @Test fun fastCloseBeforeDelayedAnalyticsStartStillJoins() {
        val join = NativeMediaRequestJoin()
        val spec = Any()
        val request = join.opened(spec)
        join.closed(request, summary())
        join.started(1, spec, 0)
        join.terminal(1, "COMPLETED")
        assertEquals("MATCHED", join.observation.status)
        assertNull(request!!.identity)
    }

    @Test fun distinctSubrangeReferenceCannotBeGuessedByIdenticalBytes() {
        val join = NativeMediaRequestJoin()
        join.started(1, Any(), 0)
        join.closed(join.opened(Any()), summary())
        join.terminal(1, "COMPLETED")
        assertEquals("UNASSIGNED", join.observation.status)
        assertNull(join.observation.transfer)
    }

    @Test fun classifiedManifestAndInitRequestsNeverReplaceMatchedMedia() {
        val join = NativeMediaRequestJoin()
        val media = Any()
        join.started(1, media, 0)
        join.closed(join.opened(media), summary())
        join.terminal(1, "COMPLETED")
        repeat(30) {
            val other = Any()
            val request = join.opened(other)
            join.closed(request, summary(444))
            join.nonMedia(other, terminal = false)
            join.nonMedia(other, terminal = true)
            assertNull(request!!.identity)
        }
        assertEquals("MATCHED", join.observation.status)
        assertEquals(400L, join.observation.transfer!!.bytes)
    }

    @Test fun nonMediaStartBeforeOpenAndTerminalBeforeCloseClearAllReferences() {
        val join = NativeMediaRequestJoin()
        repeat(30) {
            val spec = Any()
            join.nonMedia(spec, terminal = false)
            val request = join.opened(spec)
            join.nonMedia(spec, terminal = true)
            join.closed(request, summary())
            assertNull(request!!.identity)
        }
        assertEquals("NONE", join.observation.status)
    }

    @Test fun retryClearsIdentityAndStaysUnknownWhileAnotherTaskCanMatch() {
        val join = NativeMediaRequestJoin()
        val retrySpec = Any()
        join.started(1, retrySpec, 0)
        val retryRequest = join.opened(retrySpec)
        join.terminal(1, "ERROR_RETRYING")
        assertNull(retryRequest!!.identity)
        join.started(1, retrySpec, 1)
        join.closed(retryRequest, summary())
        join.terminal(1, "COMPLETED")
        assertEquals("UNKNOWN_RETRY", join.observation.status)
        val next = Any()
        join.started(2, next, 0)
        join.closed(join.opened(next), summary())
        join.terminal(2, "COMPLETED")
        assertEquals("MATCHED", join.observation.status)
    }

    @Test fun duplicateTaskSharedReferenceAndMultipleOpensFailClosed() {
        for (kind in 0..2) {
            val join = NativeMediaRequestJoin()
            val spec = Any()
            join.started(1, spec, 0)
            val request = join.opened(spec)
            when (kind) {
                0 -> join.started(1, spec, 0)
                1 -> join.started(2, spec, 0)
                else -> join.opened(spec)
            }
            join.closed(request, summary())
            join.terminal(1, "COMPLETED")
            assertEquals("UNKNOWN_AMBIGUOUS", join.observation.status)
            assertNull(request!!.identity)
            assertNull(join.observation.transfer)
        }
    }

    @Test fun boundedUnknownCandidatesClearReferencesAndDoNotRecoverByGuessing() {
        val join = NativeMediaRequestJoin()
        val retained = List(8) { join.opened(Any())!! }
        join.opened(Any())
        retained.forEach { assertNull(it.identity); join.closed(it, summary()) }
        assertEquals("UNKNOWN_CAPACITY", join.observation.status)
        assertNull(join.observation.transfer)
    }

    @Test fun cancellationAndTerminalErrorClearIdentityWithoutOverwritingCompletedMedia() {
        for (outcome in listOf("CANCELED", "ERROR_TERMINAL")) {
            val join = NativeMediaRequestJoin()
            val spec = Any()
            join.started(1, spec, 0)
            val request = join.opened(spec)
            join.terminal(1, outcome)
            assertNull(request!!.identity)
            join.closed(request, summary())
            assertEquals("UNASSIGNED", join.observation.status)
        }
    }

    @Test fun invalidationPreventsOldCloseFromUpdatingNewEpoch() {
        val old = NativeMediaRequestJoin()
        val spec = Any()
        old.started(1, spec, 0)
        val request = old.opened(spec)
        old.terminal(1, "COMPLETED")
        old.invalidate()
        val next = NativeMediaRequestJoin()
        old.closed(request, summary())
        assertEquals("INVALIDATED", old.observation.status)
        assertEquals("NONE", next.observation.status)
    }

    @Test fun concurrentCloseAndTerminalPublishOnlyCoherentMatchedSnapshot() {
        repeat(100) {
            val join = NativeMediaRequestJoin()
            val spec = Any()
            join.started(1, spec, 0)
            val request = join.opened(spec)
            val close = Thread { join.closed(request, summary()) }
            val terminal = Thread { join.terminal(1, "COMPLETED") }
            close.start(); terminal.start(); close.join(); terminal.join()
            assertEquals("MATCHED", join.observation.status)
            assertEquals(400L, join.observation.transfer!!.bytes)
        }
    }
    @Test fun requestIdentityNeverCallsEqualsHashOrText() {
        val spec = object {
            override fun equals(other: Any?): Boolean = error("identity equals")
            override fun hashCode(): Int = error("identity hash")
            override fun toString(): String = error("identity text")
        }
        val join = NativeMediaRequestJoin()
        val request = join.opened(spec)
        join.closed(request, summary())
        join.started(1, spec, 0)
        join.terminal(1, "COMPLETED")
        assertEquals("MATCHED", join.observation.status)
    }

    @Test fun aNewErrorCannotCarryAFormerCompletedRequestSummary() {
        for (outcome in listOf("CANCELED", "ERROR_TERMINAL", "ERROR_RETRYING")) {
            val join = NativeMediaRequestJoin()
            val first = Any()
            join.started(1, first, 0)
            join.closed(join.opened(first), summary())
            join.terminal(1, "COMPLETED")
            assertEquals("MATCHED", join.observation.status)
            val next = Any()
            join.started(2, next, 0)
            assertEquals("MATCHED", join.observation.status)
            val nextRequest = join.opened(next)
            join.terminal(2, outcome)
            join.closed(nextRequest, summary())
            assertNull(join.observation.transfer)
            assertEquals(if (outcome == "ERROR_RETRYING") "UNKNOWN_RETRY" else "UNASSIGNED", join.observation.status)
        }
    }

}
