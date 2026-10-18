package com.kantv.app.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeckoPlaybackStrategyTest {
    @Test
    fun normalPlaybackDefaultsToNativePageAndRejectsMirrorExtra() {
        assertEquals(GeckoPlaybackStrategy.NATIVE_PAGE,
            GeckoPlaybackStrategy.resolve(false, null))
        assertEquals(GeckoPlaybackStrategy.NATIVE_PAGE,
            GeckoPlaybackStrategy.resolve(false, GeckoPlaybackStrategy.MIRROR.name))
        assertEquals(GeckoPlaybackStrategy.NATIVE_PAGE,
            GeckoPlaybackStrategy.resolve(true, "arbitrary-value"))
    }

    @Test
    fun mirrorRequiresDiagnosticBuildAndExplicitAbExtra() {
        assertEquals(GeckoPlaybackStrategy.MIRROR,
            GeckoPlaybackStrategy.resolve(true, GeckoPlaybackStrategy.MIRROR.name))
        assertEquals(GeckoPlaybackStrategy.NATIVE_PAGE,
            GeckoPlaybackStrategy.resolve(true, GeckoPlaybackStrategy.NATIVE_PAGE.name))
    }

    @Test
    fun fixedNativePageStrategyDisablesEveryMseMirrorAction() {
        assertFalse(GeckoPlaybackStrategy.NATIVE_PAGE.usesMseMirror)
        assertTrue(GeckoPlaybackStrategy.MIRROR.usesMseMirror)
        // 原"宿主逐行接线"源码文本断言已随第 1 批重构移除（纯搬移 GeckoMirrorLiveActivity
        // 会误报红）。其中可独立验证的部分已有行为测试覆盖：
        // 看门狗预算 → NativePageProgressWatchdogPolicyTest；
        // 页面准备脚本静音/唯一 video 判定 → NativePagePrepareScriptTest。
    }

    @Test
    fun geckoExtensionStaysScopedToApprovedOfficialDomains() {
        // 扩展清单是随包发布的资产，不随宿主源码搬移变化，这里继续钉住域名白名单。
        val extensionManifest = File("src/main/assets/kantv-gecko-mirror/manifest.json").readText()
        assertTrue(extensionManifest.contains("https://www.yangshipin.cn/*"))
        assertTrue(extensionManifest.contains("https://yangshipin.cn/*"))
        assertTrue(extensionManifest.contains("https://*.gdtv.cn/*"))
        // 行为断言（取代原 SupportedWebLivePage.kt 源码文本断言）：白名单内的官网页
        // 必须能被页面身份识别为对应 provider。
        assertEquals(OfficialWebProvider.YANGSHIPIN,
            requireNotNull(SupportedWebLivePage.from("https://www.yangshipin.cn/tv/home#kantv-cctv5")).provider)
    }
}
