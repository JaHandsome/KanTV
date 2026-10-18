package com.kantv.app.diagnostics

/**
 * 进程级「网页容器播放事实」通知（fire-once 语义由调用方保证）。
 *
 * 既有缺口（2026-09-12 发现）：WEB_EMBED 会话的首帧只由 Native 路线的事件驱动，
 * 网页侧永远不上报 firstFrame -> hub 回流恒 FAIL。本单例让 网页播放宿主（现为 GeckoView 宿主）
 * 在首次观测到画面推进（advancing）时通知 MainActivity -> feedbackCollector.onFirstFrame()。
 *
 * 不走 Intent、不加轮询；listener 在 launchOfficialWeb 时注册、销毁时注销。
 */
object WebPlaybackNotifier {
    private val lock = Object()
    private val listeners = mutableListOf<(sessionId: Long, routeGeneration: Long) -> Unit>()

    fun addListener(listener: (sessionId: Long, routeGeneration: Long) -> Unit) {
        synchronized(lock) { listeners.add(listener) }
    }

    fun removeListener(listener: (sessionId: Long, routeGeneration: Long) -> Unit) {
        synchronized(lock) { listeners.remove(listener) }
    }

    fun notifyFirstFrame(sessionId: Long, routeGeneration: Long) {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { it(sessionId, routeGeneration) }
    }
}
