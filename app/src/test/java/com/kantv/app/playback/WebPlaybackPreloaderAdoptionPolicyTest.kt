package com.kantv.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预热接管/暂存的判定契约：错页与重定向必须被拒，被拒的会话必须由调用方关闭。
 * 只测纯判定，不触碰 GeckoSession（JVM 单测无法构造它）。
 */
class WebPlaybackPreloaderAdoptionPolicyTest {
    private val page = "https://www.yangshipin.cn/tv/home#kantv-cctv5"
    private val landed = "https://www.yangshipin.cn/tv/home"

    @Test
    fun adoptsTheRequestedPageOnlyAfterItReallyLanded() {
        assertTrue(PreloaderAdoptionPolicy.canAdopt(
            slotUrl = page, pageUrl = page, slotIsOpen = true, navigated = true, landedUrl = landed))
        // 落地 URL 带 SPA fragment 也算同一页。
        assertTrue(PreloaderAdoptionPolicy.canAdopt(
            slotUrl = page, pageUrl = page, slotIsOpen = true, navigated = true, landedUrl = page))
    }

    @Test
    fun rejectsAnotherChannelAnUnopenedSessionOrAnUnlandedPage() {
        assertFalse("槽位里是别的频道", PreloaderAdoptionPolicy.canAdopt(
            slotUrl = "https://www.yangshipin.cn/tv/home#kantv-cctv13",
            pageUrl = page, slotIsOpen = true, navigated = true, landedUrl = landed))
        assertFalse("会话已死", PreloaderAdoptionPolicy.canAdopt(
            slotUrl = page, pageUrl = page, slotIsOpen = false, navigated = true, landedUrl = landed))
        assertFalse("还没提交", PreloaderAdoptionPolicy.canAdopt(
            slotUrl = page, pageUrl = page, slotIsOpen = true, navigated = false, landedUrl = landed))
        assertFalse("没有落地 URL", PreloaderAdoptionPolicy.canAdopt(
            slotUrl = page, pageUrl = page, slotIsOpen = true, navigated = true, landedUrl = null))
    }

    @Test
    fun rejectsLoginPagesAndOtherHostsInsteadOfShowingTheWrongPage() {
        // 登录回跳/站点重定向：跨域或跨路径一律拒绝，等下一次冷启动。
        assertFalse(PreloaderAdoptionPolicy.landingMatches(page, "https://login.example.com/redeem"))
        assertFalse(PreloaderAdoptionPolicy.landingMatches(page, "https://www.yangshipin.cn/other/live"))
        assertFalse(PreloaderAdoptionPolicy.landingMatches(page, "$landed-redirect"))
        assertTrue(PreloaderAdoptionPolicy.landingMatches(page, "$landed?channel=5"))
        assertFalse(PreloaderAdoptionPolicy.landingMatches("$landed?channel=5", "$landed?channel=13"))
        assertFalse(PreloaderAdoptionPolicy.landingMatches(page, "https://www.yangshipin.cn.evil/tv/home"))
        assertFalse(PreloaderAdoptionPolicy.landingMatches(page, null))
        assertFalse(PreloaderAdoptionPolicy.landingMatches(page, ""))
        assertTrue(PreloaderAdoptionPolicy.landingMatches(page, landed))
    }

    @Test
    fun anUnlandedSessionIsRefusedSoTheCallerClosesIt() {
        assertTrue(PreloaderAdoptionPolicy.canRetain(landed))
        assertTrue(PreloaderAdoptionPolicy.canRetain("https://www.yicai.com/tv"))
        assertFalse(PreloaderAdoptionPolicy.canRetain(null))
        assertFalse(PreloaderAdoptionPolicy.canRetain(""))
    }
}
