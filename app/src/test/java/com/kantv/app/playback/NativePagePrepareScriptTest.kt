package com.kantv.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原生页准备脚本的生成契约：
 * pid 深链页面不装点击循环；无 pid 页面保留 hardened 点击兜底（归一化 + 滚动 + 包含匹配放宽）。
 */
class NativePagePrepareScriptTest {

    @Test
    fun pidDeepLinkPageOmitsTheChannelClickLoopEntirely() {
        val script = NativePagePrepareScript.build(
            label = null, videoSelector = "#myvideo2024078201, .video-con video", muteWebAudio = false)
        // 深链已是权威选择：点击代码整体缺席，连选择器都不出现在脚本里。
        assertFalse(script.contains("tv-main-con-r-list-left-imga"))
        assertFalse(script.contains("scrollIntoView"))
        assertFalse(script.contains("target.click()"))
        // play() 重试与静音策略不受选台方式影响。
        assertTrue(script.contains("v.play().catch"))
        assertTrue(script.contains("var label = null;"))
    }

    @Test
    fun clickFallbackNormalizesWhitespaceAndScrollsIntoViewBeforeClicking() {
        val script = NativePagePrepareScript.build(
            label = "CCTV11", videoSelector = "video", muteWebAudio = false)
        // 两侧都去掉全部空白再比较（页面文案可能带换行/不间断空格）。
        assertTrue(script.contains("replace(/\\s+/g, '')"))
        assertTrue(script.contains("normText(items[i].textContent) === normText(label)"))
        // 点击前先滚进可视区：列表项未渲染/在视口外时 click 不会落地。
        assertTrue(script.contains("target.scrollIntoView({ block: 'nearest' })"))
        assertTrue(script.indexOf("scrollIntoView") < script.indexOf("target.click()"))
        // 点中即收工：不重复点击。
        assertTrue(script.contains("clicked = true;"))
    }

    @Test
    fun includesFallbackActivatesOnlyAfterSustainedExactMatchFailures() {
        val script = NativePagePrepareScript.build(
            label = "CCTV11", videoSelector = "video", muteWebAudio = false)
        assertTrue(script.contains("exactMisses >= 30"))
        assertTrue(script.contains("indexOf(normText(label)) >= 0"))
        // 空标签绝不允许走包含匹配（indexOf('') 恒中，会点错频道）。
        assertTrue(script.contains("normText(label).length > 0"))
        // 精确命中后连续落空计数清零，放宽路径不会意外积累。
        assertTrue(script.contains("exactMisses = 0;"))
    }

    @Test
    fun labelClickFactsAreReportedAsBooleansOnly() {
        val script = NativePagePrepareScript.build(
            label = "CCTV11", videoSelector = "video", muteWebAudio = false)
        assertTrue(script.contains("__kantvLabelState"))
        assertTrue(script.contains("found: labelFound, clicked: labelClicked"))
        assertTrue(script.contains("labelFound = true;"))
        assertTrue(script.contains("labelClicked = true;"))
    }

    @Test
    fun mirrorStrategyKeepsWebAudioMutedButNativePageKeepsIt() {
        val mirror = NativePagePrepareScript.build(label = null, videoSelector = "video", muteWebAudio = true)
        val native = NativePagePrepareScript.build(label = null, videoSelector = "video", muteWebAudio = false)
        assertTrue(mirror.contains("__kantvAudioClockObserve: false"))
        assertTrue(native.contains("__kantvAudioClockObserve: false"))
        val diagnostic = NativePagePrepareScript.build(
            label = null, videoSelector = "video", muteWebAudio = false, diagnosticsEnabled = true)
        assertTrue(diagnostic.contains("__kantvAudioClockObserve: true"))
        assertTrue(mirror.contains("if (true && !v.muted) v.muted = true;"))
        assertTrue(native.contains("if (false && !v.muted) v.muted = true;"))
        assertTrue(native.contains("if (!false && v.muted) v.muted = false;"))
    }
}
