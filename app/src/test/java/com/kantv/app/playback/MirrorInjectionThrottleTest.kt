package com.kantv.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入节流的行为契约（重建 GeckoMirrorLifecycleWiringTest 的
 * "注入节流不被空端点白吃"覆盖）。原宿主接线断言删除后，端点判空先于
 * 节流窗口检查的纪律固化进 [MirrorInjectionThrottle]。
 */
class MirrorInjectionThrottleTest {
    private fun throttle() = MirrorInjectionThrottle()

    @Test
    fun injectionWithinTheWindowIsRejected() {
        val throttle = throttle()
        val t0 = 1_000_000L
        assertTrue(throttle.shouldInjectMirror(nowMs = t0, endpointAvailable = true))
        throttle.markInjected(t0)
        assertFalse(throttle.shouldInjectMirror(nowMs = t0 + 2_999L, endpointAvailable = true))
        assertTrue(throttle.shouldInjectMirror(nowMs = t0 + 3_000L, endpointAvailable = true))
    }

    @Test
    fun aNullEndpointDoesNotBurnTheThrottleWindow() {
        val throttle = throttle()
        val t0 = 1_000_000L
        // 镜像路径：server 未就绪 → 端点为空，注入尝试被拒绝……
        assertFalse(throttle.shouldInjectMirror(nowMs = t0, endpointAvailable = false))
        // ……且不得推进节流时间戳：端点就绪后窗口从"上次真正注入"起算，
        // 而不是从被拒绝的空端点尝试起算。
        assertTrue(
            "空端点尝试白吃 3s 窗口会让真正可注入的时刻被节流挡住",
            throttle.shouldInjectMirror(nowMs = t0 + 1_000L, endpointAvailable = true),
        )
    }

    @Test
    fun aRejectedAttemptNeverAdvancesTheWindow() {
        val throttle = throttle()
        throttle.markInjected(10_000L)
        // 窗口内的尝试（含空端点）一律不记账。
        assertFalse(throttle.shouldInjectMirror(nowMs = 11_000L, endpointAvailable = true))
        assertFalse(throttle.shouldInjectMirror(nowMs = 11_500L, endpointAvailable = false))
        assertFalse(throttle.shouldInjectNativePage(nowMs = 12_000L))
        assertFalse(throttle.shouldInjectMirror(nowMs = 12_999L, endpointAvailable = true))
        assertTrue(throttle.shouldInjectMirror(nowMs = 13_000L, endpointAvailable = true))
    }

    @Test
    fun nativePagePathIsOnlyThrottledAndIndependentOfEndpoints() {
        val throttle = throttle()
        val t0 = 1_000_000L
        assertTrue(throttle.shouldInjectNativePage(nowMs = t0))
        throttle.markInjected(t0)
        assertFalse(throttle.shouldInjectNativePage(nowMs = t0 + 2_999L))
        assertTrue(throttle.shouldInjectNativePage(nowMs = t0 + 3_000L))
    }

    @Test
    fun newSessionResetsTheThrottleWindow() {
        val throttle = throttle()
        throttle.markInjected(100_000L)
        throttle.reset()
        assertTrue(throttle.shouldInjectMirror(nowMs = 100_100L, endpointAvailable = true))
        assertTrue(throttle.shouldInjectNativePage(nowMs = 100_100L))
    }
}
