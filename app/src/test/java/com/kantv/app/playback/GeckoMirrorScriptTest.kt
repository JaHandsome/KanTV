package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gecko 镜像桥静态契约：geckoInstallScript 与 bridge.js 的行为不变量。
 * 这些不变量在真机 E2E 前先在 JVM 层锁死（与 OfficialWebRuntimePolicyTest 同思路）。
 */
class GeckoMirrorScriptTest {

    private val script = MseMirrorScript.geckoInstallScript("http://127.0.0.1:18080/offer")

    @Test
    fun scriptIsVoidForJavascriptUrlInjection() {
        // loadUri("javascript:") 的非 void 返回值会替换整个文档（页面被杀）。
        // 幂等哨兵 `return;`（无值）允许，字符串 return（'installed'）绝不允许。
        val body = script.trim().removePrefix("(function () {").removeSuffix("})();")
        assertFalse(body.contains("return '"))
        assertFalse(body.contains("return \""))
    }

    @Test
    fun scriptWiresBatchPostMessageToContentScript() {
        // 主域攒批 → postMessage({__kantvMirrorBatch, ep}) → content script fetch POST。
        assertTrue(script.contains("__kantvMirrorBatch"))
        assertTrue(script.contains("postMessage"))
        assertTrue(script.contains("http://127.0.0.1:18080/offer"))
        // 批量刷新：200ms 定时器 + 512KB 高水位。
        assertTrue(script.contains("setInterval(flush, 200)"))
        assertTrue(script.contains("524288"))
    }

    @Test
    fun scriptKeepsWorkerDomainHookAndInstanceLock() {
        // CCTV 官网页 MSE 在 DedicatedWorker：worker prelude 与实例锁必须保留。
        assertTrue(script.contains("workerHookFn"))
        assertTrue(script.contains("__kantvWorkerHook"))
        assertTrue(script.contains("XMLHttpRequest"))
        assertTrue(script.contains("activeMs"))
        // worker 行协议带 msId（行尾第 5 字段）供主域实例锁。
        assertTrue(script.contains("__kantvMirrorLine"))
        assertTrue(script.contains("parts.length === 5"))
    }

    @Test
    fun workerScopedInstanceKeyCannotMixTwoWorkersWithLocalW1() {
        // 每个 worker 域都会重新从 w1 编号；锁键必须额外包含主页面分配的 workerId。
        // 因此旧 worker 的 worker-1:w1 不会等同于新 worker 的 worker-2:w1。
        assertTrue(script.contains("var workerCounter = 0"))
        assertTrue(script.contains("var workerId = 'worker-' + (++workerCounter)"))
        assertTrue(script.contains("workerPrelude(workerId)"))
        assertTrue(script.contains("var instanceKey = workerId + ':' + parts[4]"))
        assertTrue(script.contains("instanceKey === activeMs"))
    }

    @Test
    fun scriptReportsOnlyAggregateMirrorSwitchCounters() {
        assertTrue(script.contains("__kantvMirrorMetrics"))
        assertTrue(script.contains("workerSwitches"))
        assertTrue(script.contains("instanceSwitches"))
    }

    @Test
    fun scriptIsIdempotentAcrossReinjection() {
        // onPageStop/bridge-ready 会重复注入：哨兵必须防双装（双钩子会双倍 append）。
        assertTrue(script.contains("if (window.__kantvMirror) return;"))
    }

    @Test
    fun bridgeJsForwardsOnlyLoopbackOfferEndpoints() {
        val bridge = File("src/main/assets/kantv-gecko-mirror/bridge.js").readText()
        // 只允许 127.0.0.1 /offer（防任意端点转发/SSRF 面）。
        assertTrue(bridge.contains("/^https?:\\/\\/127\\.0\\.0\\.1:\\d+\\/offer$/"))
        // 信号通道契约：kind=bridge / kind=state。
        assertTrue(bridge.contains("kind: 'bridge'"))
        assertTrue(bridge.contains("kind: 'state'"))
        assertTrue(bridge.contains("sendNativeMessage"))
        assertTrue(bridge.contains("kind: 'mirrorMetrics'"))
        assertTrue(bridge.contains("nodeCount: 1"))
        assertTrue(bridge.contains("nodeCount: 0, noVideo: 1"))
    }

    @Test
    fun bridgeDoesNotForwardPageUrlsOrFreeTextErrors() {
        val bridge = File("src/main/assets/kantv-gecko-mirror/bridge.js").readText()

        assertFalse(bridge.contains("location.href"))
        assertFalse(bridge.contains("document.title"))
        assertFalse(bridge.contains("message: String("))
    }

    @Test
    fun bridgeReportsStateFromTopFrameOnlyAndDoesNotMuteNativePageAudio() {
        val bridge = File("src/main/assets/kantv-gecko-mirror/bridge.js").readText()

        assertTrue(bridge.contains("if (window.top !== window) return;"))
        assertFalse(bridge.contains("v.muted = true"))
    }

    @Test
    fun bridgePrefersMarkedPresentationVideoAndForwardsOnlyBoundedPresentationFacts() {
        val bridge = File("src/main/assets/kantv-gecko-mirror/bridge.js").readText()

        assertTrue(bridge.contains("video[data-kantv-video=\"1\"]"))
        assertFalse(bridge.contains("|| document.querySelector('video')"))
        assertFalse(bridge.contains("v.play()"))
        assertTrue(bridge.contains("kind: 'presentation'"))
        assertTrue(bridge.contains("coveragePermille"))
        assertTrue(bridge.contains("Math.min(1000"))
        assertFalse(bridge.contains("playerSelector:"))
        assertFalse(bridge.contains("videoSelector:"))
    }

    @Test
    fun manifestMatchesOfficialProviderDomainsOnly() {
        val manifest = File("src/main/assets/kantv-gecko-mirror/manifest.json").readText()
        assertTrue(manifest.contains("https://tv.cctv.com/*"))
        assertTrue(manifest.contains("https://www.yangshipin.cn/*"))
        assertFalse(manifest.contains("<all_urls>"))
        // nativeMessagingFromContent：content script sendNativeMessage 所需权限。
        assertTrue(manifest.contains("nativeMessagingFromContent"))
    }
}
