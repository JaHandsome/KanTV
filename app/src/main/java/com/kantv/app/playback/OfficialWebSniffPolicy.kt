package com.kantv.app.playback

/**
 * 官网页嗅探的窗口与预算（纯函数，供 [OfficialWebStreamSniffer] 消费）。
 *
 * SNIFF_WINDOW_MS = 20s 的依据：hls.js 初始化后数秒内即请求 manifest（广告 SDK
 * 并行加载不阻塞该请求）；20s 低于现有恢复策略 SHOW_FAILURE 的总预算
 * （SOFT_RELOAD 后 watchdog 10s + 二次），给 Tier 2 整页兜底留完整恢复空间。
 */
object OfficialWebSniffPolicy {
    const val SNIFF_WINDOW_MS: Long = 20_000

    /** 单候选清单验证的超时（与 TvbLiveResolver 主清单拉取一致）。 */
    const val VERIFY_TIMEOUT_MS: Int = 8_000

    /** 单候选清单验证的抓取上限。 */
    const val VERIFY_LIMIT_BYTES: Int = 256 * 1024

    /** 一个嗅探会话最多验证的候选数（超出即停，防广告/推荐位变体风暴）。 */
    const val MAX_VERIFICATIONS: Int = 4

    fun shouldStopSniffing(elapsedMs: Long, verificationsStarted: Int): Boolean =
        elapsedMs >= SNIFF_WINDOW_MS || verificationsStarted >= MAX_VERIFICATIONS
}
