package com.kantv.app.playback

/**
 * 播放中用最新阵容刷新后备候选的节流闸门。
 *
 * 每次"被计入的缓冲区间"结束都会问一次闸门，但一次放行要读一次数据库（重新解密该频道的
 * 线路并重建候选列表），而反复短缓冲的节奏是每 15~20 秒一次，所以同一个会话里两次刷新
 * 之间至少隔 [MIN_INTERVAL_MS]。
 *
 * 放行即记账，读取失败也占一个窗口：读不到数据的频道不应把数据库打成连续请求。
 * 被拦下的事件不推进窗口——窗口始终锚定在上一次真正放行的时刻，所以长时间抖动循环是
 * "每 [MIN_INTERVAL_MS] 刷一次"，而不是越拦越久直到不再刷新。
 *
 * [nowMs] 用单调时钟口径（`SystemClock.elapsedRealtime`），设备改墙上时间不影响窗口。
 */
class CandidateRefreshThrottle(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) {
    private var lastRefreshAtMs: Long? = null

    /** 放行并记账时返回 true；窗口未到时返回 false，调用方应跳过本次刷新。 */
    fun shouldRefresh(nowMs: Long): Boolean {
        val last = lastRefreshAtMs
        if (last != null && nowMs - last < minIntervalMs) return false
        lastRefreshAtMs = nowMs
        return true
    }

    companion object {
        /**
         * 30 秒：短缓冲循环下最多每 30 秒读一次库；枢纽换线这类变化 30 秒内也追得上，
         * 而下一次切线/重载本来就要等恢复策略自己的重试节奏。
         */
        const val MIN_INTERVAL_MS = 30_000L
    }
}
