package com.kantv.app.playback

/**
 * 诊断采样循环的启停守卫（从 GeckoMirrorLiveActivity 抽出的纯谓词）。
 *
 * 语义（与搬移前逐字等价）：
 * - **重启幂等**：回前台会再次拉起采样，已在跑（`job?.isActive == true`）时
 *   不得重复起循环——同一时刻最多一个采样循环；
 * - **停止无漏**：退后台/销毁必须结束采样循环（正在跑才需要 cancel，未跑是天然无操作）。
 *
 * Activity 的循环体强绑定 Android（`Debug.getPss` / `SystemClock` / recorder），
 * JVM 单测无法跑控制器，这里只把"幂等重启 / 按活性停止"的判定抽成可测谓词；
 * 循环本身的拉起与取消仍留在宿主。
 */
internal object DiagnosticSamplingGuard {
    /** 是否应（重新）拉起采样循环：未在跑才允许启动。 */
    fun shouldStart(isActive: Boolean): Boolean = !isActive

    /** 是否应结束采样循环：只有真的在跑才需要执行 cancel。 */
    fun shouldStop(isActive: Boolean): Boolean = isActive
}
