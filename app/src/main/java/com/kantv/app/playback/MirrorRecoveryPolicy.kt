package com.kantv.app.playback

/**
 * MSE 镜像看门狗的"卡死 → 重建 / 预算耗尽 → 放弃"判定（从 GeckoMirrorLiveActivity
 * 抽出的策略缝，判定条件、阈值与放弃闩锁语义逐字等价）。
 *
 * 两种停滞形态（与 WebView 镜像同参）：
 * A. **零分片超时**：server 启动以来从未收到任何分片（`lastArrivalAtMs == 0L`）。
 *    v38 起播放器随 server.start 预建，宿主侧"mirrorPlayer == null"不再是
 *    "还没收到分片"的可用判据，必须按"距 server 起点"判定，否则钩子没装上时
 *    会话永远挂起。
 * B. **断流**：收到过分片之后 [stallRecoveryTimeoutMs] 墙上时钟无新分片。
 *
 * 预算与闩锁：
 * - 每次 RECOVER 消耗一次重建预算（[maxRecoveryAttempts]）；
 * - 预算耗尽且停滞仍在 → GIVE_UP，**只做一次**（放弃是终态，直到闩锁复位）；
 * - 闩锁复位点（[noteRecoveryBudgetReset]）：流恢复产出（首个分片到达、播放器挂载）
 *   与手动 reload，二者语义相同——预算与放弃闩锁一并清零。
 *
 * 时钟口径：零分片分支用单调时钟（`startedAtMs`/`elapsedNowMs`，
 * `SystemClock.elapsedRealtime`），断流分支用墙上时钟（`lastArrivalAtMs`/`wallNowMs`，
 * `System.currentTimeMillis`），与搬移前 Activity 内的取数完全一致。
 */
internal class MirrorRecoveryPolicy(
    /** 零分片超时：server 启动后多久没收到第一个分片就重建。 */
    private val firstFragmentTimeoutMs: Long = FIRST_FRAGMENT_TIMEOUT_MS,
    /** 断流超时：最后一个分片之后多久无新分片就重建。 */
    private val stallRecoveryTimeoutMs: Long = STALL_RECOVERY_TIMEOUT_MS,
    /** 单会话最多重建次数；超出后只提示不重建。 */
    private val maxRecoveryAttempts: Int = MAX_RECOVERY_ATTEMPTS,
) {
    init {
        require(firstFragmentTimeoutMs > 0 && stallRecoveryTimeoutMs > 0 && maxRecoveryAttempts > 0)
    }

    enum class Decision { NONE, RECOVER, GIVE_UP }

    /** 已消耗的重建预算。 */
    private var recoveryAttempts = 0

    /** 放弃闩锁：预算耗尽并给出放弃出口后不再自动重建，直到 [noteRecoveryBudgetReset]。 */
    private var giveUpNotified = false

    /**
     * 看门狗一拍：根据分片到达事实裁决本拍动作。
     * 未停滞、或已放弃（闩锁终态）→ NONE；预算未耗尽 → RECOVER（记账）；
     * 预算耗尽 → GIVE_UP（闩锁置位，只此一次）。
     */
    fun decide(
        /** server 内"最后一个分片到达"的墙上时间戳；0 = 启动以来从未收到分片。 */
        lastArrivalAtMs: Long,
        /** server 起点（单调时钟）；零分片超时从这里起算。 */
        startedAtMs: Long,
        /** 当前单调时钟（SystemClock.elapsedRealtime）。 */
        elapsedNowMs: Long,
        /** 当前墙上时钟（System.currentTimeMillis），只用于断流分支。 */
        wallNowMs: Long,
    ): Decision {
        if (giveUpNotified) return Decision.NONE
        val stalled = when {
            lastArrivalAtMs == 0L ->
                elapsedNowMs - startedAtMs >= firstFragmentTimeoutMs
            else -> wallNowMs - lastArrivalAtMs >= stallRecoveryTimeoutMs
        }
        if (!stalled) return Decision.NONE
        return if (recoveryAttempts >= maxRecoveryAttempts) {
            giveUpNotified = true
            Decision.GIVE_UP
        } else {
            recoveryAttempts++
            Decision.RECOVER
        }
    }

    /** 停滞原因（诊断用）：与停滞判定的两个分支一一对应。 */
    fun reasonFor(lastArrivalAtMs: Long): String =
        if (lastArrivalAtMs == 0L) "FIRST_FRAGMENT_TIMEOUT" else "ACCEPTED_FRAGMENT_STALL"

    /**
     * 重建预算与放弃闩锁一并复位。
     * 调用点（与搬移前一致）：流恢复产出（首个分片到达、镜像播放器挂载）
     * 与手动 reload；server (re)start 重置会话时同样复位。
     */
    fun noteRecoveryBudgetReset() {
        recoveryAttempts = 0
        giveUpNotified = false
    }

    fun attemptsUsed(): Int = recoveryAttempts

    /** 是否已走到"放弃自愈"（预算耗尽且已给出放弃出口）——宿主据此回退上一频道。 */
    fun gaveUp(): Boolean = giveUpNotified

    companion object {
        // 冷缓存首启会超过 35s（worker 已加载、页面链路慢），重建反而打断即将起播的页面。
        const val FIRST_FRAGMENT_TIMEOUT_MS = 60_000L
        const val STALL_RECOVERY_TIMEOUT_MS = 30_000L
        const val MAX_RECOVERY_ATTEMPTS = 2
    }
}
