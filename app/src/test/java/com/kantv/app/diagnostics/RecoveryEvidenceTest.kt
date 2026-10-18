package com.kantv.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复判定口径：与 [StallDetector] 一致——帧优先、要有尺寸、暂停/后台/菜单不算、世代变了不算。
 */
class RecoveryEvidenceTest {
    private fun values(
        frames: Long = -1,
        presentedFrames: Long = -1,
        timeMs: Long = -1,
        width: Int = 1920,
        height: Int = 1080,
        buffering: Boolean = false,
        generation: Long = -1,
        sourceGeneration: Long = -1,
        media3Generation: Long = -1,
        webRuntimeGeneration: Long = -1,
        nodeGeneration: Long = -1,
    ) = DiagnosticPlaybackValues(
        frames = frames,
        presentedFrames = presentedFrames,
        timeMs = timeMs,
        width = width,
        height = height,
        buffering = buffering,
        generation = generation,
        sourceGeneration = sourceGeneration,
        media3Generation = media3Generation,
        webRuntimeGeneration = webRuntimeGeneration,
        nodeGeneration = nodeGeneration,
        paused = false,
    )

    @Test
    fun aWebPageWithZeroFramesIsNotRecoveredByAClockThatKeepsRunning() {
        // readyState=1、frames=0，只有时钟在走：不是恢复。
        assertFalse(madeProgress(values(frames = 0, timeMs = 0), values(frames = 0, timeMs = 300_000)))
    }

    @Test
    fun thePresentedFrameCounterIsAuthoritativeWhenBothCountersExist() {
        assertTrue(madeProgress(
            values(frames = 10, presentedFrames = 100, timeMs = 1_000),
            values(frames = 10, presentedFrames = 101, timeMs = 1_000)))
        // 呈现计数停走时，网页计数增长也不认。
        assertFalse(madeProgress(
            values(frames = 10, presentedFrames = 100),
            values(frames = 90, presentedFrames = 100)))
    }

    @Test
    fun timeIsTheFallbackOnlyWhenFrameCountersAreUnavailable() {
        assertTrue(madeProgress(values(timeMs = 1_000), values(timeMs = 4_000)))
        // 抖动不算推进。
        assertFalse(madeProgress(values(timeMs = 1_000), values(timeMs = 1_010)))
        assertFalse(madeProgress(values(timeMs = 1_000), values(timeMs = -1)))
    }

    @Test
    fun counterResetOrANewGenerationIsNotProgress() {
        assertFalse(madeProgress(values(frames = 500), values(frames = 3)))
        assertFalse(madeProgress(
            values(frames = 500, webRuntimeGeneration = 1),
            values(frames = 600, webRuntimeGeneration = 2)))
        assertFalse(madeProgress(
            values(frames = 500, nodeGeneration = 1),
            values(frames = 600, nodeGeneration = 2)))
        assertFalse(madeProgress(
            values(frames = 500, sourceGeneration = 1),
            values(frames = 600, sourceGeneration = 2)))
    }

    @Test
    fun suspendedObservationIsNotRecovery() {
        val before = values(frames = 100)
        val after = values(frames = 200)
        assertFalse(madeProgress(before, after, appInBackground = true))
        assertFalse(madeProgress(before, after, menuVisible = true))
        assertFalse(madeProgress(before, after, userPaused = true))
        assertFalse(madeProgress(before, after, observationEnabled = false))
        assertFalse(madeProgress(before, after.copy(paused = true)))
    }

    @Test
    fun changingCounterKindsRequiresANewComparableSample() {
        assertFalse(madeProgress(values(frames = 1), values(presentedFrames = 100)))
        assertFalse(madeProgress(values(presentedFrames = 1), values(frames = 100)))
    }

    @Test
    fun noPictureGeometryOrBufferingIsNotRecovery() {
        assertFalse(madeProgress(values(frames = 1, width = 0), values(frames = 2, width = 0)))
        assertFalse(madeProgress(values(frames = 1, height = 0), values(frames = 2, height = 0)))
        assertFalse(madeProgress(values(frames = 1), values(frames = 2, buffering = true)))
    }

    @Test
    fun firstCounterObservationCountsOnlyWhenItIsNonZero() {
        assertTrue(madeProgress(values(), values(frames = 1)))
        assertFalse(madeProgress(values(), values(frames = 0)))
    }
}
