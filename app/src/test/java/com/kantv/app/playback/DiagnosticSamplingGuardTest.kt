package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断采样重启守卫的行为契约（重建 GeckoMirrorLifecycleWiringTest 的
 * "onStart 重启 / onStop 停止 / 重启幂等"覆盖）。
 * 循环体强绑定 Android（Pss/SystemClock/recorder），控制器级 JVM 测试不可行，
 * 幂等语义由 [DiagnosticSamplingGuard] 纯谓词承载（kotlinx-coroutines-test 已在
 * 依赖里，但只解决调度、不解决 Android 依赖）。
 */
class DiagnosticSamplingGuardTest {
    @Test
    fun foregroundRestartIsIdempotentWhileASamplingLoopIsAlreadyRunning() {
        assertFalse(
            "已在跑的采样循环不得被回前台重启叠加成第二个循环",
            DiagnosticSamplingGuard.shouldStart(isActive = true),
        )
        assertTrue(DiagnosticSamplingGuard.shouldStart(isActive = false))
        // 连续多次重启请求：只有第一次真正拉起。
        var active = false
        val started = (1..3).filter {
            val should = DiagnosticSamplingGuard.shouldStart(active)
            if (should) active = true
            should
        }
        assertEquals(1, started.size)
    }

    @Test
    fun backgroundStopOnlyCancelsAnActuallyRunningLoop() {
        assertTrue(DiagnosticSamplingGuard.shouldStop(isActive = true))
        assertFalse(DiagnosticSamplingGuard.shouldStop(isActive = false))
    }
}
