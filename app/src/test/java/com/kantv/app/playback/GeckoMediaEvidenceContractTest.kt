package com.kantv.app.playback

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桥脚本（随包发布的 JS 资产）的媒体证据契约：有界、未知为 -1、不含地址或自由文本。
 * 只钉资产本身，不读宿主 Kotlin 源码——原宿主侧解析接线断言（GeckoMirrorLiveActivity
 * 文本 contains）已随第 1 批重构移除，因为纯搬移宿主代码就会误报红；待宿主解析抽出
 * 为可测单元后以行为测试重建。
 */
class GeckoMediaEvidenceContractTest {
    private val bridge = File("src/main/assets/kantv-gecko-mirror/bridge.js").readText()

    @Test
    fun bridgeReportsBoundedMediaEvidenceAndNeverInventsZeroFacts() {
        listOf("droppedFrames:", "errorCode:", "bufferedPositionMs:", "bufferDurationMs:")
            .forEach { field -> assertTrue(field, bridge.contains(field)) }
        // 计数器不支持时报 -1，不把未知当零。
        assertTrue(bridge.contains("let frames = -1;"))
        assertTrue(bridge.contains("let droppedFrames = -1;"))
        assertFalse(bridge.contains("totalVideoFrames : 0"))
        // 错误码白名单 0..4。
        assertTrue(bridge.contains("Math.min(4, Math.floor(v.error.code))"))
        // 缓冲只取包含 currentTime 的那一段 range，并留前沿容差。
        assertTrue(bridge.contains("bufferedFacts"))
        assertTrue(bridge.contains("now <= end + 0.001"))
        // 不新增地址、抓包或自由文本。
        assertFalse(bridge.contains("location.href"))
        assertFalse(bridge.contains("XMLHttpRequest"))
        // Existing loopback mirror transport is allowed; media evidence reads no request metadata.
        assertFalse(bridge.contains("v.currentSrc"))
    }

    @Test
    fun labelClickFactsFlowFromTheBridgeAsBooleansOnly() {
        // 主域准备脚本经 postMessage 报选台点击事实；bridge 只缓存并转发布尔。
        assertTrue(bridge.contains("__kantvLabelState"))
        assertTrue(bridge.contains("labelFound: labelFound"))
        assertTrue(bridge.contains("labelClicked: labelClicked"))
        // 两个布尔必须同时进有/无播放器节点的两条 state 分支，否则断片期丢证据。
        assertTrue(
            Regex("frameFacts\\(frames\\), audioFacts\\(\\), (?:[A-Za-z]+Facts\\(\\), )*loginSignals, labelSignals\\);")
                .containsMatchIn(bridge),
        )
        assertTrue(
            Regex("frameFacts\\(-1\\), audioFacts\\(\\), (?:[A-Za-z]+Facts\\(\\), )*loginSignals, labelSignals\\);")
                .containsMatchIn(bridge),
        )
    }
}
