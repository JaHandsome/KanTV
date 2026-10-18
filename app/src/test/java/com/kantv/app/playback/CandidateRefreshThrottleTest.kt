package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放中刷新后备候选的节流口径。
 *
 * 短缓冲循环（每 15~20 秒一次）下每次缓冲事件都读库是不可接受的，但窗口必须锚定在
 * "上一次真正放行"的时刻：否则连续被拦下的事件会把窗口越推越远，抖动持续时反而不再刷新。
 */
class CandidateRefreshThrottleTest {
    private val throttle = CandidateRefreshThrottle()

    @Test
    fun theFirstCountedRebufferAlwaysRefreshes() {
        assertTrue(throttle.shouldRefresh(0L))
    }

    @Test
    fun aRebufferInsideTheWindowIsSkipped() {
        assertTrue(throttle.shouldRefresh(0L))

        assertFalse(throttle.shouldRefresh(1L))
        assertFalse(throttle.shouldRefresh(CandidateRefreshThrottle.MIN_INTERVAL_MS - 1L))
    }

    @Test
    fun theWindowIsAnchoredOnTheLastRefreshAndNotPushedBySkippedEvents() {
        assertTrue(throttle.shouldRefresh(0L))
        // 窗口内的抖动循环：每次都问，但都不推进窗口。
        assertFalse(throttle.shouldRefresh(1_000L))
        assertFalse(throttle.shouldRefresh(20_000L))
        assertFalse(throttle.shouldRefresh(29_999L))

        // 距上一次放行满一个窗口就放行，紧接着重新计时。
        assertTrue(throttle.shouldRefresh(CandidateRefreshThrottle.MIN_INTERVAL_MS))
        assertFalse(throttle.shouldRefresh(CandidateRefreshThrottle.MIN_INTERVAL_MS + 1L))
        assertTrue(throttle.shouldRefresh(CandidateRefreshThrottle.MIN_INTERVAL_MS * 2))
    }

    @Test
    fun theIntervalIsInjectableSoTheWindowCanBePinnedExactly() {
        val fast = CandidateRefreshThrottle(minIntervalMs = 500L)

        assertTrue(fast.shouldRefresh(7L))
        assertFalse(fast.shouldRefresh(506L))
        assertTrue(fast.shouldRefresh(507L))
    }

    @Test
    fun theProductionWindowIsThirtySeconds() {
        assertEquals(30_000L, CandidateRefreshThrottle.MIN_INTERVAL_MS)
    }
}
