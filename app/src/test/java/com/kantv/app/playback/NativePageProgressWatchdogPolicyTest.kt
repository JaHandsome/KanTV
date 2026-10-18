package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原生页（Gecko NATIVE_PAGE）"画面是否真的出来了"看门狗策略。
 *
 * 用例覆盖两次真机事故：
 * 1. 2026-09-15：DOM 坚称在播（`readyState=4`、`time` 在涨）但合成输出是纯色或拿不到样本时，
 *    app 原先**永不重建**——因为"时钟在走"这条证据每秒重置了"画面平板"计数。
 * 2. 2026-09-20（央视频 CCTV5/CCTV5+）：`<video>` 节点一出现，像素探针就读到**页面内容**
 *    （加载图/首页推荐）并置 `pictureEverVerified`，45 秒启动宽容窗被当场关闭，约 20 秒后
 *    把仍在编译 WASM 播放器的页面硬重建——用户看到"反复超时"。
 *
 * 因此这里把"启动期"与"运行期"分开：只有页面自报播放（`readyState≥2` 且未暂停）之后，
 * 像素证据才参与判定；启动期由页面自报的进展顺延窗口，且有累计上限。
 */
class NativePageProgressWatchdogPolicyTest {
    private val startupWindowMs = NativePageProgressWatchdogPolicy.STARTUP_WINDOW_DEFAULT_MS
    private val stallTimeoutMs = 15_000L
    private val healthyResetMs = 60_000L

    private fun policy(maxRecoveries: Int = 3) = NativePageProgressWatchdogPolicy(
        firstFrameTimeoutMs = startupWindowMs,
        stallTimeoutMs = stallTimeoutMs,
        maxRecoveries = maxRecoveries,
        healthyResetMs = healthyResetMs,
    )

    @Test
    fun doesNothingInsideTheStartupGraceWindow() {
        val watchdog = policy()
        watchdog.startSession(0L)
        // 启动窗口内（页面从未自称播放、也没有任何进展）：即使一次样本都拿不到也不动。
        assertEquals(NativePageProgressWatchdogPolicy.Decision.NONE, watchdog.decide(30_000L))
        assertEquals(NativePageProgressWatchdogPolicy.Decision.NONE, watchdog.decide(startupWindowMs))
    }

    /**
     * 2026-09-20 事故的回归用例：央视频页面在电视上要 20~30 秒才让 `readyState` 离开 0，
     * 期间屏幕上**已有内容**（页面加载图），像素证据会误报"画面已出"。
     * 只要页面还没自称播放，启动窗口就必须继续有效——绝不允许在 45 秒内重建。
     */
    @Test
    fun earlyPageContentDoesNotCloseTheStartupGrace() {
        val watchdog = policy()
        watchdog.startSession(0L)
        // 电视实测时序：+5s 播放器节点就绪，+6s 像素就读到"细节"（其实是页面自己的加载图）。
        watchdog.observeStartupMilestone(5_000L)
        watchdog.observePictureVerified(6_000L)
        assertTrue("像素证据仍然被记录", watchdog.hasEverVerifiedPicture())
        assertFalse("但页面还没自称播放，不能进入运行期判定", watchdog.hasPageClaimedPlaying())

        // 原来这里（约 +20~25s）就会 REBUILD，把正在编译 WASM 的页面掀掉。现在必须什么都不做。
        assertEquals(
            "启动期的像素证据不得触发重建——这正是本次事故的根因",
            NativePageProgressWatchdogPolicy.Decision.NONE,
            watchdog.decide(25_000L),
        )
        assertEquals(NativePageProgressWatchdogPolicy.Decision.NONE, watchdog.decide(45_000L))
    }

    /**
     * 真机时序回归：页面在 +25s 推进到 `readyState=1`、+50s 才真正播放，全程不得重建。
     */
    @Test
    fun slowStartupThatKeepsProgressingIsNeverInterrupted() {
        val watchdog = policy()
        watchdog.startSession(0L)
        watchdog.observeStartupMilestone(5_000L)   // 节点就绪
        watchdog.observeStartupMilestone(27_000L)  // readyState 0 → 1（有元数据了）
        var rebuilt = false
        for (at in 28_000L..80_000L step 2_000L) {
            if (watchdog.decide(at) != NativePageProgressWatchdogPolicy.Decision.NONE) rebuilt = true
        }
        // +80s 仍未出画，但因为页面在 +27s 有过进展，窗口被顺延，仍不该动手。
        assertFalse("有启动进展的页面不得被重建", rebuilt)

        // +96s 页面开始播（readyState≥2 → 宿主同时喂 observeProgress）。
        watchdog.observePlaybackClaim(96_000L)
        watchdog.observeProgress(96_000L)
        assertTrue(watchdog.hasPageClaimedPlaying())
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.NONE,
            watchdog.decide(100_000L),
        )
    }

    /**
     * 启动进展的顺延有上限：页面"每 89 秒动一下"也不能让窗口无限延长。
     * 断言方式必须体现"上限真的被读取"——判据点放在**最后一个里程碑 + 一个窗口之后**，
     * 没有上限的实现会在这里永远返回 NONE（验证者实测当前实现约 358s 动手）。
     */
    @Test
    fun startupGraceStopsRenewingAfterTheRenewalLimit() {
        val watchdog = policy()
        watchdog.startSession(0L)
        var now = 0L
        repeat(NativePageProgressWatchdogPolicy.STARTUP_RENEWAL_LIMIT + 2) {
            now += startupWindowMs - 1_000L
            watchdog.observeStartupMilestone(now)
        }
        // 顺延余量只有 STARTUP_RENEWAL_LIMIT 次：窗口基准停在"最后一次被接受的里程碑"上，
        // 也就是 (上限) × (窗口 - 1s)。余量用尽后再等满一个窗口，就必须动手。
        val lastAcceptedMilestone =
            (NativePageProgressWatchdogPolicy.STARTUP_RENEWAL_LIMIT) * (startupWindowMs - 1_000L)
        assertEquals(
            "窗口内不得动手",
            NativePageProgressWatchdogPolicy.Decision.NONE,
            watchdog.decide(lastAcceptedMilestone + startupWindowMs),
        )
        assertEquals(
            "顺延余量用尽后窗口不再延长，必须重建（否则就是永久黑屏）",
            NativePageProgressWatchdogPolicy.Decision.REBUILD,
            watchdog.decide(lastAcceptedMilestone + startupWindowMs + 1_000L),
        )
    }

    /** N1 回归：顺延额度必须随会话复位，否则重建之后"有进展就顺延"会静默失效。 */
    @Test
    fun renewalBudgetIsRestoredOnNewSession() {
        val watchdog = policy()
        watchdog.startSession(0L)
        var now = 0L
        repeat(NativePageProgressWatchdogPolicy.STARTUP_RENEWAL_LIMIT + 1) {
            now += startupWindowMs - 1_000L
            watchdog.observeStartupMilestone(now)
        }
        // 新会话（重建 / 回前台 / 主文档导航）。
        val sessionStart = now + startupWindowMs + 5_000L
        watchdog.startSession(sessionStart)
        // 新会话里页面又在 +60s 有进展：窗口必须被顺延，而不是沿用上一个会话的已用额度。
        val milestone = sessionStart + 60_000L
        watchdog.observeStartupMilestone(milestone)
        assertEquals(
            "新会话里页面有进展 → 窗口顺延，不得在旧窗口到点就重建",
            NativePageProgressWatchdogPolicy.Decision.NONE,
            watchdog.decide(sessionStart + startupWindowMs + 1_000L),
        )
    }

    /**
     * B1 回归（验证者独立复现出的缺陷）：页面**首次自称播放**的那一刻，
     * 运行期基线必须从"这一刻"开始，而不是沿用"会话起点"。
     * 否则进入运行期的第一拍就会因为"距会话起点已远超 15 秒"被判成时钟停走而误重建——
     * 真机表现就是"刚出画就被掀掉"。
     */
    @Test
    fun enteringPlaybackEstablishesFreshBaselines() {
        val watchdog = policy()
        watchdog.startSession(0L)
        watchdog.observeStartupMilestone(5_000L)
        // +50s 页面终于自称播放（宿主这一拍可能还没喂 observeProgress）。
        watchdog.observePlaybackClaim(50_000L)
        assertEquals(
            "阶段切换当场建立基线：不得在第一拍就判卡死",
            NativePageProgressWatchdogPolicy.Decision.NONE,
            watchdog.decide(50_000L),
        )
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.NONE,
            watchdog.decide(64_000L),
        )
        // 开播后确实 15 秒没有推进：这时才该重建。
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.REBUILD,
            watchdog.decide(66_000L),
        )
    }

    /**
     * B2 回归：页面换掉 `<video>` 节点（`readyState` 掉回 0、新元素重新起步）之后，
     * 必须回到启动期重新等，而不是按运行期规则 15 秒后重建。
     */
    @Test
    fun playerNodeReplacementReturnsToStartupInsteadOfRebuilding() {
        val watchdog = policy()
        watchdog.startSession(0L)
        watchdog.observePlaybackClaim(5_000L)
        watchdog.observeProgress(5_000L)
        watchdog.observePictureVerified(6_000L)

        // +40s 页面换了播放器元素。
        watchdog.observePlayerReplaced(40_000L)
        assertFalse("换节点后重新处于启动期", watchdog.hasPageClaimedPlaying())
        assertEquals(
            "新元素刚出现时不得按运行期规则立刻重建",
            NativePageProgressWatchdogPolicy.Decision.NONE,
            watchdog.decide(70_000L),
        )
        // 新元素在 +80s 重新播起来：一切照常。
        watchdog.observePlaybackClaim(80_000L)
        watchdog.observeProgress(80_000L)
        assertEquals(NativePageProgressWatchdogPolicy.Decision.NONE, watchdog.decide(85_000L))
    }

    /** B3 回归：一旦走到"放弃"，预算归还也不得让它复活（放弃是终态）。 */
    @Test
    fun givingUpIsTerminalEvenAfterSustainedHealth() {
        val watchdog = policy(maxRecoveries = 1)
        watchdog.startSession(0L)
        watchdog.observeStartupMilestone(0L)
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.REBUILD,
            watchdog.decide(startupWindowMs + 1_000L),
        )
        watchdog.startSession(startupWindowMs + 2_000L)
        watchdog.observeStartupMilestone(startupWindowMs + 2_000L)
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.NOTIFY_GIVE_UP,
            watchdog.decide(startupWindowMs * 2 + 3_000L),
        )
        assertTrue(watchdog.gaveUp())

        // 之后画面"看起来很健康"很久：预算不得归还，也不得再重建。
        var now = startupWindowMs * 2 + 4_000L
        repeat(20) {
            now += 20_000L
            watchdog.observePlaybackClaim(now)
            watchdog.observePictureVerified(now)
            watchdog.observeProgress(now)
            assertEquals(
                "放弃后必须保持终态，绝不打扰正在回退/播放的界面",
                NativePageProgressWatchdogPolicy.Decision.NONE,
                watchdog.decide(now),
            )
        }
        assertTrue(watchdog.gaveUp())

        // 关键探针（原用例缺这条，导致恒真）：放弃之后**停止喂任何健康/进展证据**，
        // 再判一次仍必须是 NONE——这才是"终态"的定义。
        val silentAt = now + 10 * 60_000L
        assertEquals(
            "放弃后即使长时间毫无进展，也只能保持沉默（宿主已按回退策略处理）",
            NativePageProgressWatchdogPolicy.Decision.NONE,
            watchdog.decide(silentAt),
        )
        assertEquals(
            "也不得因为‘画面曾经健康’就把预算还回来",
            NativePageProgressWatchdogPolicy.Decision.NONE,
            watchdog.decide(silentAt + 5 * 60_000L),
        )
    }

    /**
     * B3 回归（验证者独立复现）：页面唯一一次 `readyState` 前进发生在 `paused=true` 时，
     * 之后 `play()` 成功而 `readyState` 不再前进。宿主按**电平**补报一次"在播"之后，
     * 必须进入运行期；否则画面明明正常却按启动期规则超时重建，最后把频道踢掉。
     */
    @Test
    fun playbackEnteredAfterPausedReadyStateStillLeavesStartupPhase() {
        val watchdog = policy()
        watchdog.startSession(0L)
        // readyState 在暂停状态下一路到 4：这一拍不算"在播"。
        watchdog.observeStartupMilestone(5_000L)
        watchdog.observeStartupMilestone(20_000L)
        assertFalse(watchdog.hasPageClaimedPlaying())

        // +30s play() 成功，宿主电平判定为"在播"并补报（没有新的 readyState 前进）。
        watchdog.observePlaybackClaim(30_000L)
        assertTrue("电平补报必须能进入运行期", watchdog.hasPageClaimedPlaying())

        // 进入运行期后画面与时钟都正常（宿主每秒都在喂推进）：不得重建。
        var now = 30_000L
        repeat(60) {
            now += 1_000L
            watchdog.observeProgress(now)
            watchdog.observePictureVerified(now)
            assertEquals(NativePageProgressWatchdogPolicy.Decision.NONE, watchdog.decide(now))
        }
        // 页面自称在播、但真的 15 秒没有任何推进：这时才该重建（仍保留原有能力）。
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.REBUILD,
            watchdog.decide(now + stallTimeoutMs + 1_000L),
        )
    }

    /**
     * 阶段进入（电平补报）**不消耗**顺延额度：否则宿主几拍探针就能把 3 次额度烧光，
     * "有进展就顺延"这层保护会静默失效。
     */
    @Test
    fun phaseEntryDoesNotConsumeRenewalBudget() {
        val watchdog = policy()
        watchdog.startSession(0L)
        // 阶段进入反复调用：幂等，且**一次额度都不许消耗**。
        repeat(10) { watchdog.observePlaybackClaim(1_000L) }
        assertEquals("阶段进入不得消耗启动顺延额度", 0, watchdog.startupRenewalsUsed())
        // 真正的进展才消耗额度：三次之后额度用尽。
        var now = 0L
        repeat(NativePageProgressWatchdogPolicy.STARTUP_RENEWAL_LIMIT) {
            now += 10_000L
            watchdog.observeStartupMilestone(now)
        }
        assertEquals(
            "只有‘有进展’才消耗额度，且上限是 STARTUP_RENEWAL_LIMIT",
            NativePageProgressWatchdogPolicy.STARTUP_RENEWAL_LIMIT,
            watchdog.startupRenewalsUsed(),
        )
        watchdog.observeStartupMilestone(now + 10_000L)
        assertEquals(
            "额度用尽后不再增加",
            NativePageProgressWatchdogPolicy.STARTUP_RENEWAL_LIMIT,
            watchdog.startupRenewalsUsed(),
        )
    }

    /** 页面完全不推进（连 `readyState` 都不动）：到窗口上限就重建。 */
    @Test
    fun aPageThatNeverProgressesIsRebuiltAtTheWindowLimit() {
        val watchdog = policy()
        watchdog.startSession(0L)
        watchdog.observeStartupMilestone(0L)
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.NONE,
            watchdog.decide(startupWindowMs - 1_000L),
        )
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.REBUILD,
            watchdog.decide(startupWindowMs + 1_000L),
        )
    }

    @Test
    fun rebuildsWhenClockAdvancesButPictureNeverArrives() {
        val watchdog = policy()
        watchdog.startSession(0L)
        // DOM 一直坚称在推进 —— 2026-09-15 的形态（时钟不落后，所以不走"时钟停走"那一路）。
        // 页面已自称播放（readyState≥2）：进入运行期判定。
        watchdog.observePlaybackClaim(1_000L)
        watchdog.observeProgress(1_000L)
        // 此后时钟仍在走（30s 一次心跳），但像素连续两次拿不到画面。
        watchdog.observeProgress(30_000L)
        watchdog.observePictureMissing(40_000L)
        watchdog.observePictureMissing(48_000L)

        assertEquals(
            "时钟在走但连续两次拿不到画面，必须重建（这条正是原先永远不触发的路径）",
            NativePageProgressWatchdogPolicy.Decision.REBUILD,
            watchdog.decide(49_000L),
        )
    }

    @Test
    fun aSingleMissingPictureObservationIsNotEnoughToRebuild() {
        val watchdog = policy()
        watchdog.startSession(0L)
        // 时钟全程在走（排除"时钟停走"那一支），只给一次"没有画面"的证据。
        var now = 0L
        repeat(6) {
            now += 10_000L
            watchdog.observePlaybackClaim(now)
            watchdog.observeProgress(now)
        }
        watchdog.observePictureMissing(60_000L)
        assertEquals(
            "单次空白样本可能只是瞬时黑场，不得据此重建正常直播",
            NativePageProgressWatchdogPolicy.Decision.NONE,
            watchdog.decide(61_000L),
        )
    }

    @Test
    fun noSampleIsTreatedTheSameAsFlatPicture() {
        val watchdog = policy()
        watchdog.startSession(0L)
        watchdog.observePlaybackClaim(1_000L)
        watchdog.observeProgress(1_000L)
        // 只报"拿不到样本"（完全不报平板），时钟仍在走 —— 必须与平板同等对待。
        // 这里刻意把时间放在窗口内：**只能**靠"连续 2 次没有画面证据"这一条触发重建
        // （2026-09-15 那个形态的判据；N3 覆盖缺口）。
        watchdog.observeProgress(30_000L)
        watchdog.observePictureMissing(40_000L)
        watchdog.observePictureMissing(48_000L)
        assertEquals(
            "capturePixels 拿不到位图时同样必须重建",
            NativePageProgressWatchdogPolicy.Decision.REBUILD,
            watchdog.decide(49_000L),
        )
    }

    /**
     * 2026-09-15 形态的**另一条**路径：像素连续缺失，但时钟一直有推进
     * （所以"时钟停走"不成立）。这条分支必须有直达用例——否则它随时可能被改坏而无人察觉。
     */
    @Test
    fun consecutiveMissingPictureObservationsTriggerRebuildWhileClockAdvances() {
        val watchdog = policy()
        watchdog.startSession(0L)
        watchdog.observePlaybackClaim(0L)
        // 每 5 秒一次时钟推进（永不"停走"），同时两次拿不到画面。
        watchdog.observeProgress(10_000L)
        watchdog.observePictureMissing(12_000L)
        watchdog.observeProgress(14_000L)
        watchdog.observePictureMissing(16_000L)
        watchdog.observeProgress(18_000L)
        // 判定点取 21s：运行期"还没确认过画面"的死线是 20s，之后才该动手。
        assertEquals(
            "时钟在走 + 连续缺失画面 ≥2 次 ⇒ 重建",
            NativePageProgressWatchdogPolicy.Decision.REBUILD,
            watchdog.decide(21_000L),
        )
    }

    @Test
    fun healthyPictureNeverTriggersRebuild() {
        val watchdog = policy()
        watchdog.startSession(0L)
        var now = 0L
        repeat(20) {
            now += 10_000L
            watchdog.observePlaybackClaim(now)
            watchdog.observePictureVerified(now)
            watchdog.observeProgress(now)
            assertEquals(
                NativePageProgressWatchdogPolicy.Decision.NONE,
                watchdog.decide(now),
            )
        }
    }

    @Test
    fun rebuildsWhenClockStopsEvenIfPictureWasVerifiedOnce() {
        val watchdog = policy()
        watchdog.startSession(0L)
        watchdog.observePlaybackClaim(20_000L)
        watchdog.observePictureVerified(20_000L)
        watchdog.observeProgress(20_000L)
        assertEquals(NativePageProgressWatchdogPolicy.Decision.NONE, watchdog.decide(30_000L))

        // 时钟停走 15s 以上：进度看门狗的原职责，仍然要重建。
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.REBUILD,
            watchdog.decide(20_000L + stallTimeoutMs + 1_000L),
        )
    }

    @Test
    fun givesUpAfterBudgetIsSpentAndNotifiesOnlyOnce() {
        val watchdog = policy(maxRecoveries = 2)
        var now = 0L
        repeat(2) {
            watchdog.startSession(now)
            watchdog.observeStartupMilestone(now)
            now += startupWindowMs + 1_000L
            assertEquals(
                NativePageProgressWatchdogPolicy.Decision.REBUILD,
                watchdog.decide(now),
            )
        }
        watchdog.startSession(now)
        watchdog.observeStartupMilestone(now)
        now += startupWindowMs + 1_000L
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.NOTIFY_GIVE_UP,
            watchdog.decide(now),
        )
        assertTrue("预算耗尽并提示后才算放弃，宿主据此回退上一频道", watchdog.gaveUp())
        assertEquals(
            "放弃后不得反复打扰",
            NativePageProgressWatchdogPolicy.Decision.NONE,
            watchdog.decide(now + 120_000L),
        )
        assertEquals(2, watchdog.recoveriesUsed())
    }

    @Test
    fun budgetIsReturnedOnlyAfterSustainedHealth() {
        val watchdog = policy(maxRecoveries = 1)
        watchdog.startSession(0L)
        watchdog.observeStartupMilestone(0L)
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.REBUILD,
            watchdog.decide(startupWindowMs + 1_000L),
        )
        assertEquals(1, watchdog.recoveriesUsed())

        // 重建后画面恢复，但只健康了 30 秒（< 60 秒）：预算**不**归还。
        var now = startupWindowMs + 2_000L
        watchdog.startSession(now)
        watchdog.observePlaybackClaim(now)
        repeat(3) {
            now += 10_000L
            watchdog.observePictureVerified(now)
            watchdog.observeProgress(now)
            watchdog.decide(now)
        }
        assertEquals("健康不足 60 秒不得归还预算", 1, watchdog.recoveriesUsed())

        // 再健康 40 秒，累计超过 60 秒：归还。
        repeat(4) {
            now += 10_000L
            watchdog.observePictureVerified(now)
            watchdog.observeProgress(now)
            watchdog.decide(now)
        }
        assertEquals("持续健康满 60 秒后归还预算", 0, watchdog.recoveriesUsed())
    }

    @Test
    fun rebuildResetsPictureAndStartupStateButKeepsTheFirstFrameEventLatch() {
        val watchdog = policy()
        watchdog.startSession(0L)
        watchdog.observePlaybackClaim(10_000L)
        watchdog.observeProgress(10_000L)
        watchdog.observePictureVerified(10_000L)
        assertTrue(watchdog.hasEverVerifiedPicture())
        assertTrue(watchdog.hasPageClaimedPlaying())
        assertTrue(watchdog.isFirstFrameAnnounced())

        // 会话重建：画面与启动状态复位（重新计时、重新等待播放），但"首帧事件已宣告"这一事实保留，
        // 避免镜像链路/上报把同一事件重复宣告。
        watchdog.startSession(500_000L)
        assertFalse("画面状态必须复位，否则重建后不再等待画面", watchdog.hasEverVerifiedPicture())
        assertFalse("启动期必须重新开始，否则重建后立刻按运行期规则误判", watchdog.hasPageClaimedPlaying())
        assertTrue("事件闩锁是单调的，重建不复位", watchdog.isFirstFrameAnnounced())

        // 复位后回到"启动窗口"语义：窗口内不动。
        assertEquals(NativePageProgressWatchdogPolicy.Decision.NONE, watchdog.decide(520_000L))
        // 超过一个完整启动窗口仍无任何进展 → 重建。
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.REBUILD,
            watchdog.decide(500_000L + startupWindowMs + 1_000L),
        )
    }

    /** 启动期不得被诊断报成 wedge（"页面自称在播却没有画面"），否则复盘会再次误判。 */
    @Test
    fun startupIsNotReportedAsWedge() {
        val watchdog = policy()
        watchdog.startSession(0L)
        watchdog.observePictureVerified(5_000L)
        assertFalse(
            "页面还没自称播放，谈不上 wedge",
            watchdog.isWedgeActive(60_000L),
        )
        assertEquals(
            "启动期没有画面是正常的，诊断应报 -1 而不是一个很大的数",
            -1L,
            watchdog.millisSincePictureVerified(60_000L),
        )
    }



    /**
     * 央视频点选失败形态（本次 CCTV-11 事故）：页面加载完了，但目标频道从没被选上——
     * `readyState` 恒 0、`currentTime` 恒 0。证据存续期间不重建（重建换不来"选中"），
     * 持续满阈值后直接放弃，把用户交回上一个能看的频道。
     */
    @Test
    fun neverSelectedChannelSkipsRebuildWindowsAndGivesUpFast() {
        val giveUpMs = NativePageProgressWatchdogPolicy.NEVER_SELECTED_GIVE_UP_DEFAULT_MS
        val watchdog = policy()
        watchdog.startSession(0L)
        watchdog.observePageMediaSnapshot(1_000L, readyState = 0, currentTimeMs = 0L)
        assertEquals(NativePageProgressWatchdogPolicy.Decision.NONE, watchdog.decide(30_000L))
        // 证据存续期间连"窗口耗尽重建"都不走——烧了也换不来选中。
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.NONE,
            watchdog.decide(startupWindowMs + 1_000L),
        )
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.NOTIFY_GIVE_UP,
            watchdog.decide(giveUpMs + 2_000L),
        )
        assertTrue(watchdog.gaveUp())
        assertEquals("一个重建窗口都不许烧", 0, watchdog.recoveriesUsed())
        // 终态：之后不再打扰。
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.NONE,
            watchdog.decide(giveUpMs + 120_000L),
        )
    }

    /** 页面有过任何进展（readyState 离开 0、或时间轴动了）：证据清零，从最新一次重新累计。 */
    @Test
    fun neverSelectedEvidenceResetsWhenThePageShowsAnyProgress() {
        val giveUpMs = NativePageProgressWatchdogPolicy.NEVER_SELECTED_GIVE_UP_DEFAULT_MS
        val watchdog = policy()
        watchdog.startSession(0L)
        watchdog.observePageMediaSnapshot(1_000L, readyState = 0, currentTimeMs = 0L)
        watchdog.observePageMediaSnapshot(80_000L, readyState = 1, currentTimeMs = 0L)
        watchdog.observePageMediaSnapshot(90_000L, readyState = 0, currentTimeMs = 0L)
        assertEquals(
            "证据重新累计，未满阈值不得放弃",
            NativePageProgressWatchdogPolicy.Decision.NONE,
            watchdog.decide(90_000L + giveUpMs - 1_000L),
        )
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.NOTIFY_GIVE_UP,
            watchdog.decide(90_000L + giveUpMs + 1_000L),
        )
    }

    /** 时间轴在动（readyState 还是 0）同样说明页面活着：不得按"从未选中"放弃。 */
    @Test
    fun advancingTimelineClearsTheNeverSelectedEvidence() {
        val watchdog = policy()
        watchdog.startSession(0L)
        watchdog.observePageMediaSnapshot(1_000L, readyState = 0, currentTimeMs = 0L)
        // 时间轴动了：证据清零；页面随后正常推进（宿主喂里程碑），窗口顺延。
        watchdog.observePageMediaSnapshot(60_000L, readyState = 0, currentTimeMs = 500L)
        watchdog.observeStartupMilestone(60_000L)
        assertEquals(
            NativePageProgressWatchdogPolicy.Decision.NONE,
            watchdog.decide(60_000L + startupWindowMs - 1_000L),
        )
        assertFalse(watchdog.gaveUp())
    }

    @Test
    fun neverActsBeforeASessionStarts() {
        val watchdog = policy()
        assertEquals(NativePageProgressWatchdogPolicy.Decision.NONE, watchdog.decide(10_000_000L))
        assertFalse(watchdog.gaveUp())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveThresholds() {
        NativePageProgressWatchdogPolicy(firstFrameTimeoutMs = 0L)
    }
}
