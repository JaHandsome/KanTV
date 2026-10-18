package com.kantv.app.playback

/**
 * 原生页（Gecko NATIVE_PAGE，生产默认路径）的"画面到底出没出来"看门狗策略。
 *
 * 背景（2026-09-15 排查结论）：这条路径原先没有任何自动恢复。加了进度看门狗后又暴露出一个
 * 更隐蔽的形态——**DOM 坚称在播、画面却没有**：
 * - DOM `time`/`frames` 在涨，`readyState=4`、`paused=false`；
 * - Gecko 合成输出却是纯色（或 `capturePixels()` 根本拿不到位图）；
 * - 而"时钟在走"这条证据会**每秒重置"画面平板"计数**，导致像素级重建阈值在数学上永不达到。
 *
 * 2026-09-20 追加结论（央视频 CCTV5/CCTV5+ 真机事故）：**启动期被自己的像素证据掐断**。
 * 官网页面在电视上要先编译/实例化 WASM 播放器，`video` 节点出现后仍要 20~30 秒才推进
 * `readyState`。而节点一出现宿主就会读一次像素，读到页面的加载图/首页推荐即判为"画面已出"，
 * 于是 45 秒启动宽容窗被当场关闭，15 秒后按"常规停顿"硬重建整个 Gecko 运行时——
 * 把正在初始化的页面掀掉重来，用户看到的是"反复超时"。
 *
 * 因此本类把三类事实**彻底拆开**，任何一路都不许抵消另一路：
 * 1. **事件闩锁**（[firstFrameAnnounced]）：单调，仅用于去重上报"首帧已到达"；重建**不复位**。
 * 2. **画面状态**（[pictureVerifiedAtMs]）：最近一次"确认画面"的时刻。但**页面上有内容**
 *    不等于**视频出帧**，所以只有页面自己进入播放状态（`readyState≥2`、未暂停）之后，
 *    像素确认才被用作"起播已完成"的依据；启动期的像素结果只作证据（GREY_SCREEN_EVIDENCE）。
 * 3. **启动进展**（[lastStartupProgressAtMs]）：页面自报的启动里程碑（`readyState` 推进、
 *    播放器节点就绪）。每次进展都把启动窗口顺延，避免把"慢慢启动"当成"卡死"。
 *
 * 决策（四条，互不叠加）：
 * A. 启动期：页面从未自称播放 ⇒ 窗口内**绝不重建**；窗口 = 基线 + `startupWindowMs`，
 *    有进展则顺延（顺延次数上限见 [STARTUP_RENEWAL_LIMIT]）；窗口耗尽仍无进展 ⇒ 重建。
 * B. wedge：页面自称在播、但画面超时未确认（`readyState` 也在停走）⇒ 重建。
 * C. 停顿：已确认画面之后无推进 ⇒ `stallTimeoutMs` 内重建（与 [com.kantv.app.diagnostics.StallDetector] 口径一致）。
 * D. 频道从未被选中（[observePageMediaSnapshot] 证据）：`readyState==0 && currentTime==0`
 *    持续超过 [neverSelectedGiveUpMs] ⇒ 直接 NOTIFY_GIVE_UP——重建换不来"选中"，
 *    不再烧剩下的重建窗口。证据存续期间（未满阈值）也不重建。
 * 任意形态下预算用尽 ⇒ 只发一次不阻塞提示并由宿主回退上一频道。
 */
internal class NativePageProgressWatchdogPolicy(
    /** 启动窗口：页面从未自称播放时，给页面多久完成启动（含冷缓存 + WASM 初始化）。 */
    private val firstFrameTimeoutMs: Long = STARTUP_WINDOW_DEFAULT_MS,
    private val stallTimeoutMs: Long = 15_000L,
    private val maxRecoveries: Int = 3,
    /** 连续健康多久才归还重建预算（防止"时钟一动就归还"造成无界重建）。 */
    private val healthyResetMs: Long = 60_000L,
    /**
     * 画面证据要连续缺失多少次才允许据此重建。
     * 保留原有"平板不能凭一次就读判"的保护：默认 2 次（配合采样节拍约 8~16 秒）。
     */
    private val missingPictureObservationsBeforeRebuild: Int = 2,
    /**
     * **运行期**的画面死线：页面自称在播之后，多久必须有画面证据。
     * 与启动窗口分开——启动窗口是"等页面启动"，这里是"已经开播却看不到"，
     * 两者混用会把 2026-09-15 那个形态的检测延迟从 约 46s 拖到 92s。
     */
    private val runtimeFirstFrameDeadlineMs: Long = 20_000L,
    /**
     * "频道从未被选中"证据持续多久后直接放弃（见 [observePageMediaSnapshot]）。
     * 大于一个启动窗口：正常慢启动的 `readyState` 会在窗口内推进并清掉证据，
     * 能持续这么久的零进展只可能是选台没生效。
     */
    private val neverSelectedGiveUpMs: Long = NEVER_SELECTED_GIVE_UP_DEFAULT_MS,
) {
    init {
        require(firstFrameTimeoutMs > 0 && stallTimeoutMs > 0 && maxRecoveries > 0 && healthyResetMs > 0)
        require(missingPictureObservationsBeforeRebuild > 0 && runtimeFirstFrameDeadlineMs > 0)
        require(neverSelectedGiveUpMs > 0)
    }

    enum class Decision { NONE, REBUILD, NOTIFY_GIVE_UP }

    // ===== 事件闩锁（单调，重建不复位）=====
    private var firstFrameAnnounced = false

    // ===== 画面状态（可复位）=====
    private var pictureVerifiedAtMs = 0L
    private var pictureEverVerified = false
    private var pictureBaselineEstablished = false
    /** 连续"没有画面证据"的采样次数；一次确认即清零。 */
    private var missingPictureObservations = 0

    // ===== 启动/时钟状态 =====
    /** 页面是否**自称**进入播放（`readyState≥2` 且未暂停，或媒体时间轴在推进）。 */
    private var pageClaimedPlaying = false
    private var lastProgressAtMs = 0L
    private var progressBaselineEstablished = false
    /** 启动窗口基线：会话开始 或 最近一次页面启动进展。 */
    private var startupBaselineAtMs = 0L
    /** 运行期起点：页面首次自称播放的那一刻。运行期的画面/进度死线都从这里算。 */
    private var phaseStartedAtMs = 0L
    /** 本次会话内"启动进展"已顺延过几次（上限见 [STARTUP_RENEWAL_LIMIT]）。 */
    private var startupRenewals = 0

    // ===== "频道从未被选中"证据 =====
    /** 证据起点（0 = 当前无此证据）。跨会话保留：重建并不能让频道被选中。 */
    private var neverSelectedSinceMs = 0L

    // ===== 会话与预算 =====
    private var sessionStartedAtMs = 0L
    private var sessionEstablished = false
    private var healthySinceMs = 0L
    private var recoveries = 0
    private var giveUpNotified = false

    /**
     * 会话/运行时开始（含重建、回前台、主文档导航）。
     * 复位全部**画面与启动**状态，但**保留** [firstFrameAnnounced] 这一事件事实与重建预算。
     */
    fun startSession(nowMs: Long) {
        sessionStartedAtMs = nowMs
        sessionEstablished = true
        startupBaselineAtMs = nowMs
        phaseStartedAtMs = nowMs
        // 顺延额度的口径是"本次会话内"，必须随会话复位；否则重建/回前台/导航之后，
        // "有进展就顺延"这层保护会静默失效（验证者实测：会话 1 用光额度后会话 2 只剩一个窗口）。
        startupRenewals = 0
        pageClaimedPlaying = false
        pictureVerifiedAtMs = 0L
        pictureEverVerified = false
        pictureBaselineEstablished = false
        missingPictureObservations = 0
        lastProgressAtMs = 0L
        progressBaselineEstablished = false
        healthySinceMs = 0L
        giveUpNotified = false
    }

    /**
     * 页面启动**进展**：`readyState` 前进（新的启动里程碑）时调用。
     *
     * 有进展就把启动窗口从"现在"顺延——但顺延次数有上限（[STARTUP_RENEWAL_LIMIT]），
     * 否则页面每 89 秒挪一下就能无限延长窗口，变成永久黑屏。
     */
    fun observeStartupMilestone(nowMs: Long) {
        if (startupRenewals < STARTUP_RENEWAL_LIMIT) {
            startupRenewals++
            startupBaselineAtMs = nowMs
        }
    }

    /**
     * 页面**自称进入播放**（电平为"在播"）时调用；幂等，只在首次真正生效。
     *
     * 独立成一个方法而不是给里程碑加布尔参数，是为了杜绝误用：这个调用**不消耗顺延额度**，
     * 而"每拍都喂里程碑"会把额度在几秒内烧光。
     *
     * 首次生效时**当场建立运行期基线**（见 B1 修复）：否则进入运行期的第一拍会拿
     * "会话起点"当基线，立刻判成"时钟停走"而误重建。
     * 之所以需要电平而不是只靠"`readyState` 前进"那一拍：`readyState` 可能在 `paused=true`
     * 时就到达 4，之后 `play()` 成功而 `readyState` 不再前进——那样会永远进不了运行期。
     */
    fun observePlaybackClaim(nowMs: Long) {
        if (pageClaimedPlaying) return
        pageClaimedPlaying = true
        phaseStartedAtMs = nowMs
        lastProgressAtMs = nowMs
        progressBaselineEstablished = true
        pictureBaselineEstablished = true
        // 启动期读到的"画面"多半是页面的加载图，不能当作运行期的画面证据：
        // 时间戳一律刷新到阶段起点，否则诊断会写出"刚开播就 44 秒没画面"的假数字。
        pictureVerifiedAtMs = nowMs
        missingPictureObservations = 0
    }

    /** DOM 时钟在推进（time/frames 增长）。
     *
     * 注意：**这不等于"页面进入了播放"**。`readyState` 还在 0/1 时也可能有别的计时器在动，
     * 所以"启动期结束"只由 [observePlaybackClaim] 决定——
     * 否则 2026-09-20 那种"页面在启动、时钟在走"的形态会再次绕开启动窗口。
     */
    fun observeProgress(nowMs: Long) {
        lastProgressAtMs = nowMs
        progressBaselineEstablished = true
    }

    /**
     * 宿主检测到真实文档、`<video>` 对象或媒体资源换代。旧桥无身份时才用 ready0 兼容判据。
     *
     * 这类事件意味着**页面重新开始启动**：必须回到启动期，否则会退化成
     * "刚被换掉的瞬间就被运行期规则判成卡死"——正是本次事故的指纹。
     * 重建预算**保留**（不归还），所以不会因此变成无限重建。
     */
    fun observePlayerReplaced(nowMs: Long) {
        if (!sessionEstablished) return
        pageClaimedPlaying = false
        phaseStartedAtMs = nowMs
        startupBaselineAtMs = nowMs
        startupRenewals = 0
        lastProgressAtMs = 0L
        progressBaselineEstablished = false
        pictureVerifiedAtMs = 0L
        pictureEverVerified = false
        pictureBaselineEstablished = false
        missingPictureObservations = 0
        healthySinceMs = 0L
    }

    /**
     * 页面媒体快照。`readyState==0 && currentTime==0` 持续存在 = 频道从未被选中
     * （央视频 SPA 点选失败形态：页面加载完了，但目标频道从没被选上）。
     * 任何一项离开零都说明页面在动，证据清零重新累计。
     */
    fun observePageMediaSnapshot(nowMs: Long, readyState: Int, currentTimeMs: Long) {
        if (readyState == 0 && currentTimeMs <= 0L) {
            if (neverSelectedSinceMs == 0L) neverSelectedSinceMs = nowMs
        } else {
            neverSelectedSinceMs = 0L
        }
    }

    /** 确认画面（像素采样为 VISIBLE_DETAIL）。 */
    fun observePictureVerified(nowMs: Long) {
        pictureVerifiedAtMs = nowMs
        pictureEverVerified = true
        pictureBaselineEstablished = true
        missingPictureObservations = 0
        firstFrameAnnounced = true
    }

    /** 采样拿不到画面证据（平板纯色，或 `capturePixels()` 未返回样本）。 */
    fun observePictureMissing(nowMs: Long) {
        if (!pictureBaselineEstablished) {
            // 从未确认过画面：以"进入本会话的时刻"为画面基线，让死线从会话起点开始算。
            pictureBaselineEstablished = true
            pictureVerifiedAtMs = sessionStartedAtMs
        }
        missingPictureObservations++
        tickHealthyBudget(nowMs)
    }

    fun decide(nowMs: Long): Decision {
        if (!sessionEstablished) return Decision.NONE
        tickHealthyBudget(nowMs)

        // ---- A. 启动期：页面从未自称播放 ----
        // 像素探针在启动期读到的是页面内容（加载图/首页推荐/旧帧），不能当作出画证据；
        // 只有页面自报播放之后才切换到 B/C 的判定。窗口由启动进展顺延，总等待有上限
        // （顺延次数上限见 [STARTUP_RENEWAL_LIMIT]，余量用尽后窗口不再延长）。
        if (!pageClaimedPlaying) {
            // ---- D. 频道从未被选中（证据存续期间不重建，满阈值直接放弃）----
            if (neverSelectedSinceMs > 0L) {
                if (nowMs - neverSelectedSinceMs < neverSelectedGiveUpMs) return Decision.NONE
                // 放弃是终态：一旦提示过就不再自动重建，避免"回退上一频道"之后又被顶掉。
                if (giveUpNotified) return Decision.NONE
                giveUpNotified = true
                return Decision.NOTIFY_GIVE_UP
            }
            if (nowMs - startupBaselineAtMs <= firstFrameTimeoutMs) return Decision.NONE
            return rebuildOrGiveUp()
        }

        // ---- B/C. 页面自称在播之后的判定（基线一律取阶段起点，不取会话起点）----
        val pictureBaseline = if (pictureBaselineEstablished) pictureVerifiedAtMs else phaseStartedAtMs
        val pictureDeadline = if (pictureEverVerified) stallTimeoutMs else runtimeFirstFrameDeadlineMs
        val pictureOverdue = nowMs - pictureBaseline > pictureDeadline

        // 时钟停走：`lastProgressAtMs` 是"最后一次看到推进"的时刻。进入运行期时它已被设为
        // 阶段起点（见 [observePlaybackClaim]），所以"自称在播之后 15 秒没有推进"即为停走——
        // 这里刻意不再给额外的宽限分支：那样会与"停走 15 秒"这条既有口径打架。
        val clockBaseline = if (progressBaselineEstablished) lastProgressAtMs else phaseStartedAtMs
        val clockStalled = nowMs - clockBaseline > stallTimeoutMs

        // "画面超时"还需要足够的**连续**缺失观测次数才据此重建：
        // 单次采样可能只是瞬时黑场/暗场景，凭一次就读判会误重建正常直播。
        if (!clockStalled && !(pictureOverdue && missingPictureObservations >= missingPictureObservationsBeforeRebuild)) {
            return Decision.NONE
        }
        return rebuildOrGiveUp()
    }

    private fun rebuildOrGiveUp(): Decision {
        // 放弃是终态：一旦提示过就不再自动重建，避免"回退上一频道"之后又被新一轮重建顶掉。
        if (giveUpNotified) return Decision.NONE
        if (recoveries >= maxRecoveries) {
            giveUpNotified = true
            return Decision.NOTIFY_GIVE_UP
        }
        recoveries++
        healthySinceMs = 0L
        return Decision.REBUILD
    }

    /**
     * 画面持续健康累计到阈值才归还预算。**放弃之后永不归还**——
     * 否则"给了提示又继续重建"，宿主会在回退上一频道之后被再次打断。
     */
    private fun tickHealthyBudget(nowMs: Long) {
        if (giveUpNotified) return
        if (!pageClaimedPlaying || !pictureBaselineEstablished) return
        val deadline = if (pictureEverVerified) stallTimeoutMs else runtimeFirstFrameDeadlineMs
        if (nowMs - pictureVerifiedAtMs > deadline) {
            healthySinceMs = 0L
            return
        }
        if (healthySinceMs == 0L) healthySinceMs = nowMs
        if (nowMs - healthySinceMs >= healthyResetMs && recoveries > 0) {
            recoveries = 0
            healthySinceMs = nowMs
        }
    }

    fun recoveriesUsed(): Int = recoveries

    /**
     * 本次会话已消耗的启动窗口顺延次数。
     * 只暴露给单测：用来锁住"阶段进入（[observePlaybackClaim]）不消耗额度"这条契约——
     * 否则额度被偷走这件事在行为上几乎观察不到（能回到启动期的两条路都会把额度归零）。
     */
    internal fun startupRenewalsUsed(): Int = startupRenewals

    /**
     * 距上次确认画面的毫秒数；从未确认过则为 -1（诊断用，回答"多久没画面了"）。
     * 启动期（页面还没自称播放）恒为 -1：那时"没画面"是正常的，不是故障。
     */
    fun millisSincePictureVerified(nowMs: Long): Long = when {
        !pageClaimedPlaying -> -1L
        !pictureBaselineEstablished -> -1L
        else -> (nowMs - pictureVerifiedAtMs).coerceAtLeast(0L)
    }

    /** 当前是否处于 wedge（页面自称在播、画面却超时未确认）。启动期恒为 false。 */
    fun isWedgeActive(nowMs: Long): Boolean {
        if (!sessionEstablished || !pageClaimedPlaying) return false
        val pictureBaseline = if (pictureBaselineEstablished) pictureVerifiedAtMs else phaseStartedAtMs
        val deadline = if (pictureEverVerified) stallTimeoutMs else runtimeFirstFrameDeadlineMs
        return nowMs - pictureBaseline > deadline
    }

    fun recoveryLimit(): Int = maxRecoveries

    /** 是否已经确认过画面（用于诊断与"首帧前/后"的宽容度选择）。 */
    fun hasEverVerifiedPicture(): Boolean = pictureEverVerified

    /** 页面是否已自称进入播放（"启动期"与"运行期"的分界，供宿主与诊断使用）。 */
    fun hasPageClaimedPlaying(): Boolean = pageClaimedPlaying

    /** 首帧事件是否已宣告（单调闩锁，供上报去重，不随重建复位）。 */
    fun isFirstFrameAnnounced(): Boolean = firstFrameAnnounced

    /** 是否已走到"放弃自愈"（预算耗尽且已提示过一次）——宿主据此回退上一频道。 */
    fun gaveUp(): Boolean = giveUpNotified

    companion object {
        /**
         * 启动窗口默认值（2026-09-20 真机实测）：央视频官网页面在这台电视上从导航到出画
         * 需要 25~60 秒（主要是 WASM 播放器初始化），桌面同网络只要 3~8 秒。
         * 90 秒 = 实测值的 1.5 倍余量；配合"有进展就顺延"覆盖更慢的冷启动。
         */
        const val STARTUP_WINDOW_DEFAULT_MS = 90_000L

        /**
         * 启动窗口最多被"页面有进展"顺延几次。
         * 顺延是**真的会被读取**的上限：余量用尽后窗口不再延长，
         * 因此"页面每 89 秒挪一下就无限黑屏"不会发生。
         * 页面的 `readyState` 在一次页面实例里最多前进 4 次（0→1→2→3→4），所以
         * 实际最长等待约为 (1 + [STARTUP_RENEWAL_LIMIT]) × 窗口。
         */
        const val STARTUP_RENEWAL_LIMIT = 3

        /**
         * "频道从未被选中"证据的持续阈值：大于一个启动窗口——正常慢启动的 `readyState`
         * 会在窗口内推进并清掉证据，能持续这么久的 `readyState==0 && currentTime==0`
         * 只可能是选台没生效（重建换不来"选中"），直接放弃而不是烧完重建预算。
         */
        const val NEVER_SELECTED_GIVE_UP_DEFAULT_MS = 150_000L
    }
}
