package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupTimingEvidenceTest {
    @Test
    fun reportsElapsedMillisecondsForTheSameStartup() {
        assertEquals(42_000L, startupFirstFrameElapsedMs(1_000L, 43_000L))
        assertEquals(0L, startupFirstFrameElapsedMs(5_000L, 5_000L))
    }

    @Test
    fun missingEvidenceIsReportedAsUnknownNotZero() {
        // 本次启动还没确认出画 / 启动起点未知：都必须说 -1，不能冒充 0 秒出画。
        assertEquals(-1L, startupFirstFrameElapsedMs(1_000L, 0L))
        assertEquals(-1L, startupFirstFrameElapsedMs(0L, 43_000L))
        assertEquals(-1L, startupFirstFrameElapsedMs(0L, 0L))
    }

    @Test
    fun staleFirstFrameFromAnEarlierStartupIsNeverReportedAsNegativeElapsed() {
        // 前后台/重连会刷新启动时间；首帧时间仍是旧 session 的值时不得当作负数耗时上报。
        assertEquals(-1L, startupFirstFrameElapsedMs(2_000_000L, 1_984_704L))
    }
}
