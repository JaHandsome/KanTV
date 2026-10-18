package com.kantv.app.playback

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class NativeMediaLoadObservationTest {
    private fun timing(retry: Long = 0, start: Long = 100, mediaStart: Long = 5_000) =
        NativeLoadTaskTiming("VIDEO", start, 200, mediaStart, 10_000, retry, 400, 503)
    private fun NativeMediaLoadTracker.view(uid: Any? = "period", count: Int = 1, position: Long = 500, window: Long = 25_000) =
        snapshot(uid, count, -2_000, position, window)

    @Test fun retryIsObservedRatherThanInferredFromErrorCount() {
        val tracker = NativeMediaLoadTracker()
        tracker.activate(1)
        tracker.started(1, "period", timing())
        tracker.finished(1, "period", timing(), "ERROR_RETRYING")
        assertEquals("RETRYING", tracker.view().activeStatus)
        assertEquals(0L, tracker.view().last!!.retryCount)
        tracker.started(1, "period", timing(retry = 2))
        tracker.finished(1, "period", timing(), "COMPLETED")
        val completed = tracker.view()
        assertEquals("NONE", completed.activeStatus)
        assertEquals(2L, completed.last!!.retryCount)
        assertEquals("AVAILABLE", completed.positionStatus)
        assertEquals(-2_000L, completed.windowStartInPeriodMs)
    }

    @Test fun marginsRequireBothReportedTasksToOwnTheCurrentSinglePeriod() {
        val tracker = NativeMediaLoadTracker()
        tracker.started(1, "old", timing())
        tracker.finished(1, "old", timing(), "COMPLETED")
        tracker.started(2, "period", timing())
        assertEquals("UNKNOWN_CHANGED_PERIOD", tracker.view().positionStatus)
        assertEquals(-1L, tracker.view().windowStartInPeriodMs)
        assertEquals("UNKNOWN_MULTI_PERIOD", tracker.view(count = 2).positionStatus)
        tracker.invalidate()
        tracker.started(3, "period", timing())
        assertEquals("WINDOW_ONLY", tracker.view(position = -1).positionStatus)
        assertEquals(-2_000L, tracker.view(position = -1).windowStartInPeriodMs)
        assertEquals("UNKNOWN_TIME", tracker.view(window = -1).positionStatus)
        tracker.started(3, "period", timing(mediaStart = -1))
        assertEquals("UNKNOWN_TIME", tracker.view().positionStatus)
    }

    @Test fun expiredMediaRangeRemainsComparableWhenThePlaybackCursorIsLost() {
        val tracker = NativeMediaLoadTracker()
        tracker.started(1, "period", timing().copy(mediaStartMs = 22_000, mediaEndMs = 24_000))
        val view = tracker.snapshot("period", 1, 26_000, -1, 20_000)
        assertEquals("WINDOW_ONLY", view.positionStatus)
        assertEquals(26_000L, view.windowStartInPeriodMs)
        assertTrue(view.active!!.mediaEndMs < view.windowStartInPeriodMs)
        assertEquals("UNKNOWN_CHANGED_PERIOD", tracker.snapshot("other", 1, 26_000, -1, 20_000).positionStatus)
        assertEquals("UNKNOWN_TIME", tracker.snapshot("period", 1, null, -1, 20_000).positionStatus)
    }

    @Test fun seekRejectsOldTerminalAndRetryCallbacksWithoutDrainingAnyQueue() {
        val tracker = NativeMediaLoadTracker()
        tracker.activate(1)
        tracker.started(1, "period", timing())
        tracker.invalidate()
        tracker.finished(1, "period", timing(), "COMPLETED")
        tracker.started(1, "period", timing(retry = 1))
        assertEquals("NONE", tracker.view().activeStatus)
        assertEquals("INVALIDATED", tracker.view().lastOutcome)
        assertNull(tracker.view().last)
        tracker.started(2, "period", timing())
        assertEquals("LOADING", tracker.view().activeStatus)
    }

    @Test fun capacityIsBoundedAndFailsClosedUntilANewPipeline() {
        val tracker = NativeMediaLoadTracker()
        repeat(9) { tracker.started(it.toLong(), "period", timing()) }
        assertEquals("UNKNOWN_CAPACITY", tracker.view().activeStatus)
        tracker.finished(8, "period", timing(), "COMPLETED")
        assertNull(tracker.view().last)
        tracker.preparePipeline(2, true)
        tracker.started(10, "period", timing())
        assertEquals("LOADING", tracker.view().activeStatus)
    }

    @Test fun sameGenerationPipelineReplacementRejectsOldStartedAndTerminalTasks() {
        val tracker = NativeMediaLoadTracker()
        tracker.activate(1)
        val oldPipeline = tracker.preparePipeline(1, true)
        val oldEpoch = tracker.captureTransferEpoch(1, oldPipeline)!!
        val oldSpec = Any()
        tracker.started(1, "period", timing(), oldSpec)
        val oldRequest = oldEpoch.mediaRequestJoin.opened(oldSpec)
        val pipeline = tracker.preparePipeline(1, true)
        tracker.started(1, "period", timing(retry = 1), oldSpec)
        tracker.finished(1, "period", timing(), "COMPLETED")
        oldEpoch.mediaRequestJoin.closed(oldRequest, NativeTransferSummary(1, "CLOSED_EOF", 100, 200, 1, 2, 3, 1, 400, true))
        assertEquals("NONE", tracker.view().activeStatus)
        assertEquals("NONE", tracker.view().mediaRequestMatchStatus)
        assertNull(oldRequest!!.identity)
        val current = tracker.captureTransferEpoch(1, pipeline)!!
        val spec = Any()
        tracker.started(2, "period", timing(), spec)
        current.mediaRequestJoin.closed(current.mediaRequestJoin.opened(spec), NativeTransferSummary(2, "CLOSED_EOF", 100, 200, 1, 2, 3, 1, 400, true))
        tracker.finished(2, "period", timing(), "COMPLETED")
        assertEquals("MATCHED", tracker.view().mediaRequestMatchStatus)
    }

    @Test fun tombstoneOverflowDisablesOnlyJoinAndNewSourceRecovers() {
        val tracker = NativeMediaLoadTracker()
        tracker.activate(1)
        tracker.preparePipeline(1, true)
        repeat(8) { tracker.started(it.toLong(), "period", timing(), Any()) }
        tracker.preparePipeline(1, true)
        tracker.started(20, "period", timing(), Any())
        tracker.preparePipeline(1, true)
        tracker.started(21, "period", timing(), Any())
        assertEquals("LOADING", tracker.view().activeStatus)
        assertEquals("UNKNOWN_CAPACITY", tracker.view().mediaRequestMatchStatus)
        tracker.activate(2)
        tracker.preparePipeline(2, true)
        assertEquals("NONE", tracker.view().mediaRequestMatchStatus)
    }

    @Test fun seekKeepsFactoryUsableButRejectsOldTransferEpochAndReplacedFactory() {
        val tracker = NativeMediaLoadTracker()
        tracker.activate(1)
        val pipeline = tracker.preparePipeline(1, true)
        val old = tracker.captureTransferEpoch(1, pipeline)!!
        val sequence = old.begin()
        val summary = NativeTransferSummary(sequence, "CLOSED_NO_EOF", 100, 200, 1, 2, 3, 1, 400, false)
        tracker.invalidate()
        assertFalse(tracker.transferFinished(old, summary))
        val current = tracker.captureTransferEpoch(1, pipeline)!!
        assertNotSame(old, current)
        assertTrue(tracker.transferFinished(current, summary))
        assertEquals("CLOSED_NO_EOF", tracker.view().transferOutcome)
        tracker.preparePipeline(1, true)
        assertNull(tracker.captureTransferEpoch(1, pipeline))
        tracker.activate(2)
        assertFalse(tracker.transferFinished(current, summary))
        assertNull(tracker.view().transfer)
    }

    @Test fun capturedOldTransferBeginsAfterSameGenerationReplacementWithoutOwningNewCounters() {
        val tracker = NativeMediaLoadTracker()
        val oldPipeline = tracker.preparePipeline(7, true)
        val captured = CountDownLatch(1)
        val begin = CountDownLatch(1)
        val begun = CountDownLatch(1)
        val close = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val oldEpoch = AtomicReference<NativeTransferEpoch>()
        val completed = AtomicReference<NativeTransferSummary>()
        val failure = AtomicReference<Throwable>()
        val loader = thread(isDaemon = true) {
            try {
                val epoch = tracker.captureTransferEpoch(7, oldPipeline)!!
                oldEpoch.set(epoch)
                captured.countDown()
                check(begin.await(5, TimeUnit.SECONDS))
                val sequence = epoch.begin()
                begun.countDown()
                check(close.await(5, TimeUnit.SECONDS))
                epoch.activeCount.decrementAndGet()
                completed.set(NativeTransferSummary(sequence, "CLOSED_NO_EOF", 100, 200, 1, 2, 3, 1, 400, false))
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                captured.countDown(); begun.countDown(); ended.countDown()
            }
        }
        try {
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            val currentPipeline = tracker.preparePipeline(7, true)
            val currentEpoch = tracker.captureTransferEpoch(7, currentPipeline)!!
            begin.countDown()
            assertTrue(begun.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(1, oldEpoch.get().activeCount.get())
            assertEquals(0, currentEpoch.activeCount.get())
            assertEquals(0L, tracker.view().transferActiveCount)
            close.countDown()
            assertTrue(ended.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertFalse(tracker.transferFinished(oldEpoch.get(), completed.get()))
            assertNull(tracker.view().transfer)
            assertNull(tracker.captureTransferEpoch(7, oldPipeline))
        } finally {
            begin.countDown(); close.countDown(); loader.join(1_000)
        }
    }

    @Test fun concurrentReplacementNeverReturnsANewEpochToAnOldFactory() {
        val tracker = NativeMediaLoadTracker()
        val work = AtomicReference<Pair<Any, NativeTransferEpoch>>()
        val barrier = CyclicBarrier(2)
        val failure = AtomicReference<Throwable>()
        val rounds = 1_000
        val loader = thread(isDaemon = true) {
            try {
                repeat(rounds) {
                    barrier.await(10, TimeUnit.SECONDS)
                    val (pipeline, expectedEpoch) = work.get()
                    // This races the owner's same-generation pipeline replacement, rather than
                    // merely checking a completed replacement in a sequential test.
                    repeat(500) {
                        val captured = tracker.captureTransferEpoch(7, pipeline)
                        if (captured != null && captured !== expectedEpoch) {
                            failure.compareAndSet(null, AssertionError("Old factory captured a replacement epoch"))
                        }
                    }
                    barrier.await(10, TimeUnit.SECONDS)
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
        try {
            repeat(rounds) {
                val pipeline = tracker.preparePipeline(7, true)
                work.set(pipeline to tracker.captureTransferEpoch(7, pipeline)!!)
                barrier.await(10, TimeUnit.SECONDS)
                tracker.preparePipeline(7, true)
                barrier.await(10, TimeUnit.SECONDS)
            }
            loader.join(1_000)
            assertFalse(loader.isAlive)
            assertNull(failure.get())
        } finally {
            loader.interrupt()
            loader.join(1_000)
        }
    }

    @Test fun missingStartedCallbackCannotInventOwnershipOrRetryCount() {
        val tracker = NativeMediaLoadTracker()
        tracker.finished(1, "period", timing(), "CANCELED")
        assertNull(tracker.view().last)
        tracker.started(2, "period", timing())
        tracker.finished(2, "period", timing().copy(bytes = 25), "CANCELED")
        assertEquals("CANCELED", tracker.view().lastOutcome)
        assertEquals(25L, tracker.view().last!!.bytes)
    }
}
