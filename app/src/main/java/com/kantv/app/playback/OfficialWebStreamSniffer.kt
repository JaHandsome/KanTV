package com.kantv.app.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 官网页流地址嗅探控制器（Tier 1 核心）。
 *
 * 职责：消费 WebView shouldInterceptRequest 捕获的请求 -> 分类 -> 窗口/预算内
 * 逐个验证 -> 第一个通过 [SniffedStreamGate] 的候选经 [onVerified] 上报（仅一次）。
 *
 * 线程纪律：[offer] 由 WebView 后台线程调用，内部加锁入队；验证在 IO 线程执行；
 * [onVerified] 回调投递到 [deliverContext]（生产为主线程），换轨动作全部发生在主线程。
 */
class OfficialWebStreamSniffer(
    private val scope: CoroutineScope,
    private val gate: SniffedStreamGate,
    private val nowMs: () -> Long,
    private val verifyContext: CoroutineDispatcher = Dispatchers.IO,
    private val deliverContext: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val onVerified: (VerifiedStream) -> Unit,
) {
    private val lock = Object()
    private var startedAtMs: Long = -1L
    private var stopped = true
    private var verificationsStarted = 0
    private var reported = false
    private val seenUrls = HashSet<String>()

    fun start() {
        synchronized(lock) {
            startedAtMs = nowMs()
            stopped = false
            verificationsStarted = 0
            reported = false
            seenUrls.clear()
        }
    }

    /** WebView 拦截线程调用；返回 true 表示该 URL 已被当作候选消费（分类命中且入队验证）。 */
    fun offer(url: String, headers: Map<String, String>?, isMainFrame: Boolean): Boolean {
        val kind = StreamUrlClassifier.classify(url, contentType = null, isMainFrame = isMainFrame)
            ?: return false
        val shouldVerify = synchronized(lock) {
            when {
                stopped || reported -> false
                OfficialWebSniffPolicy.shouldStopSniffing(nowMs() - startedAtMs, verificationsStarted) -> false
                !seenUrls.add(url) -> false
                else -> { verificationsStarted += 1; true }
            }
        }
        if (!shouldVerify) return false
        val candidate = SniffedCandidate(url = url, kind = kind, headers = headers.orEmpty())
        scope.launch(verifyContext) {
            val verified = gate.verify(candidate) ?: return@launch
            val deliver = synchronized(lock) {
                if (stopped || reported) false else { reported = true; true }
            }
            if (deliver) {
                withContext(deliverContext) { onVerified(verified) }
            }
        }
        return true
    }

    fun stop() {
        synchronized(lock) { stopped = true }
    }
}
