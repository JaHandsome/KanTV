package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 镜像看门狗重建/放弃判定的行为契约（重建 GeckoMirrorLifecycleWiringTest 的
 * 零分片超时与放弃闩锁覆盖）。原宿主接线断言删除后，判定逻辑收进
 * [MirrorRecoveryPolicy]，这里按行为逐条对照重建。
 */
class MirrorRecoveryPolicyTest {
    private val startedAtMs = 500_000L
    private val lastArrivalAtMs = 900_000L

    private fun policy() = MirrorRecoveryPolicy()

    // ===== 行为 6：零分片超时按"server 启动以来从未收到分片"判定 =====

    @Test
    fun zeroFragmentTimeoutCountsFromServerStartWhenNoFragmentEverArrived() {
        val policy = policy()
        // 从未收到分片（lastArrival == 0）：不满 60s 不重建。
        assertEquals(
            MirrorRecoveryPolicy.Decision.NONE,
            policy.decide(
                lastArrivalAtMs = 0L,
                startedAtMs = startedAtMs,
                elapsedNowMs = startedAtMs + MirrorRecoveryPolicy.FIRST_FRAGMENT_TIMEOUT_MS - 1L,
                wallNowMs = 0L,
            ),
        )
        // 到点即重建；判定只看分片到达事实，不依赖宿主侧播放器是否预建（v38 起恒非 null）。
        assertEquals(
            MirrorRecoveryPolicy.Decision.RECOVER,
            policy.decide(
                lastArrivalAtMs = 0L,
                startedAtMs = startedAtMs,
                elapsedNowMs = startedAtMs + MirrorRecoveryPolicy.FIRST_FRAGMENT_TIMEOUT_MS,
                wallNowMs = 0L,
            ),
        )
        assertEquals("FIRST_FRAGMENT_TIMEOUT", policy.reasonFor(0L))
    }

    @Test
    fun fragmentStallTimeoutCountsFromLastArrival() {
        val policy = policy()
        // 收到过分片之后：按墙上时钟距最后一个分片 30s 判定，与 server 起点无关。
        assertEquals(
            MirrorRecoveryPolicy.Decision.NONE,
            policy.decide(
                lastArrivalAtMs = lastArrivalAtMs,
                startedAtMs = startedAtMs,
                elapsedNowMs = startedAtMs + 10L,
                wallNowMs = lastArrivalAtMs + MirrorRecoveryPolicy.STALL_RECOVERY_TIMEOUT_MS - 1L,
            ),
        )
        assertEquals(
            MirrorRecoveryPolicy.Decision.RECOVER,
            policy.decide(
                lastArrivalAtMs = lastArrivalAtMs,
                startedAtMs = startedAtMs,
                elapsedNowMs = startedAtMs + 10L,
                wallNowMs = lastArrivalAtMs + MirrorRecoveryPolicy.STALL_RECOVERY_TIMEOUT_MS,
            ),
        )
        assertEquals("ACCEPTED_FRAGMENT_STALL", policy.reasonFor(lastArrivalAtMs))
    }

    // ===== 行为 7：放弃闩锁 =====

    @Test
    fun givesUpToFallbackOnlyOnceAfterRecoveryBudgetIsExhausted() {
        val policy = policy()
        // 烧光 2 次重建预算。
        repeat(MirrorRecoveryPolicy.MAX_RECOVERY_ATTEMPTS) {
            assertEquals(
                MirrorRecoveryPolicy.Decision.RECOVER,
                policy.decide(
                    lastArrivalAtMs = 0L,
                    startedAtMs = startedAtMs,
                    elapsedNowMs = startedAtMs + MirrorRecoveryPolicy.FIRST_FRAGMENT_TIMEOUT_MS,
                    wallNowMs = 0L,
                ),
            )
        }
        assertEquals(MirrorRecoveryPolicy.MAX_RECOVERY_ATTEMPTS, policy.attemptsUsed())
        assertFalse(policy.gaveUp())

        // 预算耗尽且仍停滞 → 放弃出口，只做一次。
        assertEquals(
            MirrorRecoveryPolicy.Decision.GIVE_UP,
            policy.decide(
                lastArrivalAtMs = 0L,
                startedAtMs = startedAtMs,
                elapsedNowMs = startedAtMs + MirrorRecoveryPolicy.FIRST_FRAGMENT_TIMEOUT_MS,
                wallNowMs = 0L,
            ),
        )
        assertTrue(policy.gaveUp())
        assertEquals(
            "放弃是终态：闩锁置位后即使继续停滞也不再给第二次放弃出口",
            MirrorRecoveryPolicy.Decision.NONE,
            policy.decide(
                lastArrivalAtMs = 0L,
                startedAtMs = startedAtMs,
                elapsedNowMs = startedAtMs + MirrorRecoveryPolicy.FIRST_FRAGMENT_TIMEOUT_MS * 2,
                wallNowMs = 0L,
            ),
        )
    }

    @Test
    fun giveUpDoesNotFireBeforeStallWhenBudgetIsAlreadyExhausted() {
        val policy = policy()
        // 预算耗尽但当前没有停滞（未到零分片超时）：不得提前放弃。
        repeat(MirrorRecoveryPolicy.MAX_RECOVERY_ATTEMPTS) {
            policy.decide(
                lastArrivalAtMs = 0L,
                startedAtMs = startedAtMs,
                elapsedNowMs = startedAtMs + MirrorRecoveryPolicy.FIRST_FRAGMENT_TIMEOUT_MS,
                wallNowMs = 0L,
            )
        }
        policy.noteRecoveryBudgetReset()
        repeat(MirrorRecoveryPolicy.MAX_RECOVERY_ATTEMPTS) {
            policy.decide(
                lastArrivalAtMs = 0L,
                startedAtMs = startedAtMs,
                elapsedNowMs = startedAtMs + MirrorRecoveryPolicy.FIRST_FRAGMENT_TIMEOUT_MS,
                wallNowMs = 0L,
            )
        }
        assertFalse(policy.gaveUp())
        assertEquals(
            MirrorRecoveryPolicy.Decision.NONE,
            policy.decide(
                lastArrivalAtMs = 0L,
                startedAtMs = startedAtMs,
                elapsedNowMs = startedAtMs + MirrorRecoveryPolicy.FIRST_FRAGMENT_TIMEOUT_MS - 1L,
                wallNowMs = 0L,
            ),
        )
    }

    @Test
    fun streamRecoveryOutputResetsTheGiveUpLatch() {
        val policy = policy()
        exhaustBudgetAndGiveUp(policy)
        // 流恢复产出（maybeStartMirrorPlayer）：预算与闩锁一并复位，允许后续再自愈。
        policy.noteRecoveryBudgetReset()
        assertFalse(policy.gaveUp())
        assertEquals(0, policy.attemptsUsed())
        assertEquals(
            MirrorRecoveryPolicy.Decision.RECOVER,
            policy.decide(
                lastArrivalAtMs = 0L,
                startedAtMs = startedAtMs,
                elapsedNowMs = startedAtMs + MirrorRecoveryPolicy.FIRST_FRAGMENT_TIMEOUT_MS,
                wallNowMs = 0L,
            ),
        )
    }

    @Test
    fun manualReloadResetsTheGiveUpLatch() {
        val policy = policy()
        exhaustBudgetAndGiveUp(policy)
        // 手动 reload 与流恢复产出同一闩锁复位语义。
        policy.noteRecoveryBudgetReset()
        assertFalse(policy.gaveUp())
        assertEquals(
            MirrorRecoveryPolicy.Decision.RECOVER,
            policy.decide(
                lastArrivalAtMs = lastArrivalAtMs,
                startedAtMs = startedAtMs,
                elapsedNowMs = startedAtMs + 10L,
                wallNowMs = lastArrivalAtMs + MirrorRecoveryPolicy.STALL_RECOVERY_TIMEOUT_MS,
            ),
        )
    }

    private fun exhaustBudgetAndGiveUp(policy: MirrorRecoveryPolicy) {
        repeat(MirrorRecoveryPolicy.MAX_RECOVERY_ATTEMPTS + 1) {
            policy.decide(
                lastArrivalAtMs = 0L,
                startedAtMs = startedAtMs,
                elapsedNowMs = startedAtMs + MirrorRecoveryPolicy.FIRST_FRAGMENT_TIMEOUT_MS,
                wallNowMs = 0L,
            )
        }
        assertTrue(policy.gaveUp())
    }
}
