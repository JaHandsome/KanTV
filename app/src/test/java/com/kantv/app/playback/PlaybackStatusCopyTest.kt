package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放状态文案（用户可见交流）。
 *
 * 用户标准：不要求用户操作，但要让用户知道"在打开什么 / 卡住了正在重试第几次 / 恢复了"；
 * 文案不得出现内核、镜像、官网等实现细节；"已等待 N 秒"不能一开始就报出来。
 */
class PlaybackStatusCopyTest {
    @Test
    fun firstSecondsOnlySayItIsLoading() {
        val line = PlaybackStatusCopy.loading(
            channelName = "CCTV-5+ 体育赛事",
            elapsedSeconds = 3,
            recoveryAttempt = 0,
            recoveryBudget = 3,
        )
        assertEquals("正在打开 CCTV-5+ 体育赛事", line.headline)
        assertEquals("正在加载，请稍候", line.detail)
    }

    @Test
    fun longWaitShowsElapsedSecondsWithReassurance() {
        val line = PlaybackStatusCopy.loading(
            channelName = "CCTV-5",
            elapsedSeconds = 42,
            recoveryAttempt = 0,
            recoveryBudget = 3,
        )
        assertTrue(line.detail.contains("42"))
        assertTrue("慢启动要给一句安抚", line.detail.contains("首次打开"))
    }

    @Test
    fun recoveryAttemptIsSpelledOutForTheUser() {
        val line = PlaybackStatusCopy.loading(
            channelName = "CCTV-5",
            elapsedSeconds = 51,
            recoveryAttempt = 2,
            recoveryBudget = 3,
        )
        assertTrue(line.headline.contains("自动重试"))
        assertTrue("要告诉用户这是第几次", line.detail.contains("第 2 次"))
        assertTrue(line.detail.contains("51"))
    }

    @Test
    fun attemptNumberNeverExceedsTheBudget() {
        val line = PlaybackStatusCopy.loading(
            channelName = "CCTV-5",
            elapsedSeconds = 90,
            recoveryAttempt = 9,
            recoveryBudget = 3,
        )
        assertTrue("对外不暴露超过预算的次数", line.detail.contains("第 3 次"))
    }

    @Test
    fun failureTellsTheUserTheOnlyExitWithoutButtons() {
        val line = PlaybackStatusCopy.failed(channelName = "CCTV-5", attempts = 3)
        assertTrue(line.headline.contains("CCTV-5"))
        assertTrue(line.detail.contains("按返回键"))
        assertTrue("只给出口，不给按钮式措辞", !line.detail.contains("重试按钮"))
    }

    @Test
    fun fallingBackNamesTheChannelItReturnsTo() {
        val line = PlaybackStatusCopy.fallingBack(previousChannelName = "广东卫视")
        assertEquals("这个频道暂时不可用", line.headline)
        assertTrue(line.detail.contains("广东卫视"))
    }

    @Test
    fun recoveredIsShortAndCalm() {
        val line = PlaybackStatusCopy.recovered()
        assertEquals("已自动恢复", line.headline)
        assertEquals("继续播放", line.detail)
    }

    @Test
    fun runtimeUnavailableGivesNoButtonsAndNamesTheExit() {
        val line = PlaybackStatusCopy.runtimeUnavailable(channelName = "东方财经")
        assertTrue(line.headline.contains("东方财经"))
        assertTrue(line.detail.contains("按返回键"))
        assertFalse("不得出现「重试按钮」式措辞", line.detail.contains("重试"))
    }

    @Test
    fun copyNeverLeaksImplementationDetails() {
        val forbidden = listOf("Gecko", "镜像", "内核", "官网", "MSE", "WebView", "DOM")
        val all = buildList {
            add(PlaybackStatusCopy.loading("央视5", 5, 0, 3))
            add(PlaybackStatusCopy.loading("央视5", 60, 2, 3))
            add(PlaybackStatusCopy.failed("央视5", 3))
            add(PlaybackStatusCopy.fallingBack("广东卫视"))
            add(PlaybackStatusCopy.recovered())
            add(PlaybackStatusCopy.runtimeUnavailable("央视5"))
        }
        all.forEach { line ->
            forbidden.forEach { word ->
                assertFalse("文案不得出现实现细节：$word → ${line.headline} / ${line.detail}",
                    line.headline.contains(word) || line.detail.contains(word))
            }
        }
    }
}
