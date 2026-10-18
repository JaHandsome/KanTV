package com.kantv.app.playback

/**
 * 主域脚本注入（钩子 / 页面准备）的节流闸门（从 GeckoMirrorLiveActivity 抽出的策略缝）。
 *
 * 注入有双触发（onPageStop 与 bridge-ready），脚本本身幂等，3 秒内只放行一次。
 * 关键纪律（2026-09 排查踩坑）：**镜像路径的端点判空必须先于节流窗口检查**——
 * 镜像端点由本地 server 端口派生，server 未就绪时为空；若先检查/推进节流时间戳，
 * 每次空端点尝试都会白吃掉 3 秒窗口，真正可注入的时刻反而被节流挡住。
 * 本类把这条顺序固化进 [shouldInjectMirror]：空端点直接拒绝，且不推进窗口。
 *
 * [nowMs] 用单调时钟口径（`SystemClock.elapsedRealtime`），设备改墙上时间不影响窗口。
 */
internal class MirrorInjectionThrottle(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) {
    init {
        require(minIntervalMs > 0)
    }

    /** 上次真正注入的时刻；0 = 本会话尚未注入过。 */
    private var lastInjectAtMs = 0L

    /** 原生页路径：只过节流。返回 true = 本次应注入，调用方随后必须 [markInjected]。 */
    fun shouldInjectNativePage(nowMs: Long): Boolean = nowMs - lastInjectAtMs >= minIntervalMs

    /**
     * 镜像路径：**端点判空先于节流窗口检查**。
     * 端点不可用 → false（不推进窗口）；窗口内 → false；窗口外 → true（调用方随后 [markInjected]）。
     */
    fun shouldInjectMirror(nowMs: Long, endpointAvailable: Boolean): Boolean =
        endpointAvailable && nowMs - lastInjectAtMs >= minIntervalMs

    /** 真正注入前记账；被 [shouldInjectMirror]/[shouldInjectNativePage] 拦下的调用方不得调用。 */
    fun markInjected(nowMs: Long) {
        lastInjectAtMs = nowMs
    }

    /** 新会话：注入节流窗口随旧会话作废（与搬移前 `lastInjectAtMs = 0L` 等价）。 */
    fun reset() {
        lastInjectAtMs = 0L
    }

    companion object {
        /** javascript: 注入最小间隔（onPageStop 与 bridge-ready 双触发去重）。 */
        const val MIN_INTERVAL_MS = 3_000L
    }
}
