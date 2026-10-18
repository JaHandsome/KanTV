package com.kantv.app.diagnostics

import org.junit.Assert.*
import org.junit.Test

class NativeDiagnosticSamplingLoopTest {
    private class Clock {
        var now = 0L
        val tasks = mutableListOf<Pair<Long, Runnable>>()
        val samples = mutableListOf<Long>()
        var onSample: () -> Unit = {}
        val loop = NativeDiagnosticSamplingLoop(
            post = { task, delay -> tasks.add(now + delay to task); Unit },
            cancel = { task -> tasks.removeAll { it.second === task }; Unit },
            sample = { samples.add(now); onSample() },
            canSample = { true },
        )

        fun advanceTo(target: Long) {
            while (true) {
                val next = tasks.minByOrNull { it.first }?.takeIf { it.first <= target } ?: break
                tasks.remove(next)
                now = next.first
                next.second.run()
                assertTrue("one pending periodic callback", tasks.size <= 1)
            }
            now = target
        }
    }

    @Test fun twentyFactsPerSecondCannotMoveTheThreeSecondSampleDeadline() {
        val clock = Clock()
        clock.loop.start(1)
        repeat(2_001) { index ->
            clock.advanceTo(index * 50L)
            // Same-session lazy opens/begin calls stay idempotent, while every fact can be journaled.
            clock.loop.start(1)
        }
        assertEquals((0L..99_000L step 3_000L).toList(), clock.samples)
        assertEquals(1, clock.tasks.size)
    }

    @Test fun backgroundFactsAndDuplicateForegroundNeverDuplicateOrResumeTheLoop() {
        val clock = Clock()
        clock.loop.start(1)
        clock.advanceTo(0)
        val dequeuedBeforeBackground = clock.tasks.single().second
        clock.loop.background()
        repeat(60) { clock.loop.start(1); clock.advanceTo((it + 1) * 50L) }
        dequeuedBeforeBackground.run()
        assertEquals(listOf(0L), clock.samples)
        assertTrue(clock.tasks.isEmpty())
        clock.loop.foreground()
        clock.loop.foreground()
        clock.advanceTo(9_000)
        assertEquals(listOf(0L, 3_000L, 6_000L, 9_000L), clock.samples)
    }

    @Test fun stopAndCloseRejectLateSameSessionButNewSelectionCanStart() {
        val clock = Clock()
        clock.loop.start(1)
        clock.advanceTo(0)
        val lateCallback = clock.tasks.single().second
        clock.loop.stop(1)
        clock.loop.stop(0) // A duplicate empty stop must retain the closed-session tombstone.
        assertFalse(clock.loop.canOpen(1))
        clock.loop.start(1)
        clock.loop.foreground()
        lateCallback.run()
        clock.advanceTo(3_000)
        assertEquals(listOf(0L), clock.samples)
        clock.loop.start(2)
        clock.advanceTo(3_000)
        assertEquals(listOf(0L, 3_000L), clock.samples)
        clock.loop.close()
        clock.loop.start(3)
        clock.loop.foreground()
        clock.advanceTo(9_000)
        assertEquals(listOf(0L, 3_000L), clock.samples)
    }

    @Test fun reentrantStopOrBackgroundCannotRearmTheExecutingCallback() {
        val clock = Clock()
        clock.onSample = { clock.loop.stop(1) }
        clock.loop.start(1)
        clock.advanceTo(9_000)
        assertEquals(listOf(0L), clock.samples)
        assertTrue(clock.tasks.isEmpty())
        clock.onSample = { clock.loop.background() }
        clock.loop.start(2)
        clock.advanceTo(9_000)
        assertEquals(listOf(0L, 9_000L), clock.samples)
        assertTrue(clock.tasks.isEmpty())
    }

    @Test fun aReentrantNewSelectionHasOnlyItsOwnNewLoop() {
        val clock = Clock()
        clock.onSample = { clock.onSample = {}; clock.loop.start(2) }
        clock.loop.start(1)
        clock.advanceTo(6_000)
        assertEquals(listOf(0L, 0L, 3_000L, 6_000L), clock.samples)
        assertEquals(1, clock.tasks.size)
    }
}
