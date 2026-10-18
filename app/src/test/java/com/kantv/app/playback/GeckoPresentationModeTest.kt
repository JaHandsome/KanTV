package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeckoPresentationModeTest {
    @Test
    fun normalPlaybackAlwaysUsesAutoThenFit() {
        GeckoPresentationMode.entries.forEach { requested ->
            assertEquals(
                GeckoPresentationMode.AUTO_THEN_FIT,
                GeckoPresentationMode.resolve(false, requested.name),
            )
        }
    }

    @Test
    fun diagnosticBuildAcceptsOnlyFixedPresentationModes() {
        GeckoPresentationMode.entries.forEach { requested ->
            assertEquals(requested, GeckoPresentationMode.resolve(true, requested.name))
        }
        assertEquals(
            GeckoPresentationMode.AUTO_THEN_FIT,
            GeckoPresentationMode.resolve(true, "arbitrary-value"),
        )
    }

    @Test
    fun modeCapabilitiesPreserveAbBoundaries() {
        assertTrue(GeckoPresentationMode.AUTO_FULLSCREEN_ONLY.attemptsAutomaticFullscreen)
        assertFalse(GeckoPresentationMode.AUTO_FULLSCREEN_ONLY.fallsBackToCss)
        assertTrue(GeckoPresentationMode.CSS_FIT_ONLY.appliesCssImmediately)
        assertFalse(GeckoPresentationMode.CSS_FIT_ONLY.attemptsAutomaticFullscreen)
        assertTrue(GeckoPresentationMode.AUTO_THEN_FIT.attemptsAutomaticFullscreen)
        assertTrue(GeckoPresentationMode.AUTO_THEN_FIT.fallsBackToCss)
    }

    @Test
    fun scriptRequestsStandardFullscreenThenFallsBackToContainFit() {
        val script = GeckoPresentationScript.build(
            GeckoPresentationMode.AUTO_THEN_FIT,
            playerSelector = ".video-con",
            videoSelector = "#main-video, .video-con video",
        )

        assertTrue(script.contains("video.requestFullscreen()"))
        assertTrue(script.contains("MODE === 'AUTO_THEN_FIT'"))
        assertTrue(script.contains("object-fit:contain!important"))
        assertTrue(script.contains("position:fixed!important;inset:0!important"))
        assertTrue(script.contains("new MutationObserver(scheduleScan)"))
        assertTrue(script.contains("attributeFilter: ['class', 'style', 'hidden']"))
        assertTrue(script.contains("return visible.length === 1 ? visible[0] : null"))
        assertFalse(script.contains("var video = document.querySelector(VIDEO_SELECTOR)"))
        assertTrue(script.contains("data-kantv-video"))
        assertTrue(script.contains("data-kantv-player"))
        assertTrue(script.contains(".video-con"))
        assertTrue(script.contains("#main-video, .video-con video"))
        assertFalse(script.contains(".click()"))
        assertFalse(script.contains(".play()"))
        assertFalse(script.contains("location.href"))
        assertFalse(script.contains("Cookie"))
        assertFalse(script.contains("Token"))
    }

    @Test
    fun gdtvScopedSuppressionHidesNativeAndSitePlayerControlsAndFocusOutline() {
        val script = GeckoPresentationScript.build(
            GeckoPresentationMode.AUTO_THEN_FIT,
            playerSelector = "#J_prismPlayer",
            videoSelector = "#J_prismPlayer video",
            suppressNativePlayerChrome = true,
        )

        assertTrue(script.contains("var SUPPRESS_CHROME = true;"))
        assertTrue(script.contains("kantv-native-player-chrome"))
        assertTrue(script.contains(":focus,:focus-visible{outline:none!important;}"))
        assertTrue(script.contains("video::-webkit-media-controls"))
        assertTrue(script.contains(".vjs-control-bar,.vjs-big-play-button,"))
        assertTrue(script.contains("#J_prismPlayer>[id^=\"J_prismPlayer_component_\"]"))
        assertTrue(script.contains("video.controls = false"))
        assertTrue(script.contains("video.removeAttribute('controls')"))
        // 重新挂回 controls 的页面要被 1s 维持循环兜住，而不是只处理一次。
        assertTrue(script.contains("setInterval(suppressNativeChrome, 1000)"))
        // 隐藏页面 chrome 不能替换应用自己的全屏/播放策略。
        assertTrue(script.contains("video.requestFullscreen()"))
        assertTrue(script.contains("MODE === 'AUTO_THEN_FIT'"))
        assertFalse(script.contains(".play()"))
        assertFalse(script.contains("location.href"))
    }

    @Test
    fun genericOfficialPagesDoNotSuppressNativeChrome() {
        val script = GeckoPresentationScript.build(
            GeckoPresentationMode.AUTO_THEN_FIT,
            playerSelector = ".video-con",
            videoSelector = "#main-video, .video-con video",
        )

        assertTrue(script.contains("var SUPPRESS_CHROME = false;"))
        // 开关关闭时：scan 不调用 suppression；1s 维持循环不注册；不创建样式、不改 video。
        assertTrue(script.contains("if (!SUPPRESS_CHROME) return;"))
        assertTrue(script.contains("if (SUPPRESS_CHROME) suppressNativeChrome();"))
        assertTrue(script.contains("if (SUPPRESS_CHROME) setInterval(suppressNativeChrome, 1000);"))
        // 默认注入仍保留既有全屏与 CSS 兜底行为。
        assertTrue(script.contains("object-fit:contain!important"))
        assertTrue(script.contains("video.requestFullscreen()"))
    }

    @Test
    fun backExitsWebFullscreenBeforeOpeningMenu() {
        assertEquals(GeckoBackAction.EXIT_WEB_FULLSCREEN, GeckoBackPolicy.action(true))
        assertEquals(GeckoBackAction.OPEN_MENU, GeckoBackPolicy.action(false))
    }
}
