package com.kantv.app.diagnostics

import com.kantv.app.catalog.FeedbackEngine
import com.kantv.app.catalog.FeedbackFailReason
import com.kantv.app.catalog.FeedbackOutcome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 一条待上报的真机结果（契约 §4 的 `results[]` 元素）。
 *
 * **脱敏纪律**：这里只保存 `url_key`（40 位哈希），绝不包含原始 URL、请求头或凭据。
 * 未归因的频道（channel_id 为空）不构造结果，自然也不参与上报。
 */
data class PlaybackFeedbackResult(
    val channelId: String,
    val urlKey: String,
    val route: String,
    val outcome: FeedbackOutcome,
    val engine: FeedbackEngine,
    val firstFrameMs: Long?,
    val stalls: Int,
    val stallSeconds: Long,
    val playSeconds: Long,
    val failReason: FeedbackFailReason?,
    val observedAt: String,
    /**
     * 「用户实际在看」的墙钟秒数：屏幕亮 + 应用在前台 + 该频道是当前频道 + 用户未主动暂停。
     * **包含**缓冲等待与自动恢复耗时（契约：冻结率的分母；`freeze_seconds ≤ active_seconds`）。
     */
    val activeSeconds: Long = 0,
    /** 冻结累计秒数：**达到 6 秒阈值**的段完整时长之和（不足 6 秒不计）。 */
    val freezeSeconds: Long = 0,
    /** 达到 6 秒阈值的冻结段数。 */
    val freezeSegments: Int = 0,
    /** 最长一段冻结秒数（段长按"画面恢复推进"计，不按恢复动作触发计）。 */
    val freezeLongestSeconds: Long = 0,
    /**
     * 达到 45 秒的冻结段数（**独立可选**的纯报表口径，不参与不变式）。
     * `-1` 表示本次取不到该值：此时**只省这一个字段**，绝不影响成组五个字段的上报。
     */
    val freezeLongSegments: Int = -1,
    /** 本次附着期间的画面推进事件数（事件驱动，无固定采样间隔）。 */
    val pictureProgressEvents: Int = 0,
    /**
     * 是否采集了冻结口径。为 false 时（旧版本写入的队列记录）整组字段一律不上报——
     * 契约要求"要么整组给齐、要么整组不给"，绝不把"没测"报成"零冻结"。
     */
    val freezeMeasured: Boolean = false,
)

/**
 * 回流「冻结」字段的契约常量（两端冻结，2026-09-18）。
 *
 * - 阈值取 6 秒：与已上线的画面推进门同源，**上报口径与动作口径完全一致**；
 * - 45 秒只用于 `freeze_long_segments`（纯报表视角，不参与不变式）。
 */
object FeedbackFreezeContract {
    const val THRESHOLD_SECONDS = 6L
    const val LONG_THRESHOLD_SECONDS = 45L
    const val THRESHOLD_MS = THRESHOLD_SECONDS * 1_000L
    const val LONG_THRESHOLD_MS = LONG_THRESHOLD_SECONDS * 1_000L
}

/** 一次会话的归因键；由播放层在开播时提供。 */
data class FeedbackSessionKey(
    /** null = 无法归因（禁止以显示名作主键）：该会话不参与回流。 */
    val channelId: String?,
    val urlKey: String,
    val route: String,
    val engine: FeedbackEngine,
)

/**
 * 回流采集器：把既有的首帧观测 / 进度门 / 停帧检测结果，翻译成契约 §4 的一条结果。
 *
 * 纯状态机，不做 I/O、不依赖 Android UI；因此可以直接单测。
 * 数据源（由播放层调用）：
 * - 首帧观测 → [onFirstFrame]
 * - `DisplayedFrameProgressGate` / `StallDetector` → [onStall]
 * - `RebufferHealthPolicy` 计入的缓冲区间 → [onRebufferCounted]
 * - 播放结束或失败 → [finish]
 */
class PlaybackFeedbackCollector(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zoneOffsetMinutes: () -> Int = { TimeZone.getDefault().rawOffset / 60_000 },
) {
    private var session: FeedbackSessionKey? = null
    private var startedAtMs = 0L
    private var firstFrameMs: Long? = null
    private var stalls = 0
    private var stallSeconds = 0L
    private var lastStallStartedAtMs: Long? = null
    private var failReason: FeedbackFailReason? = null
    private var ended = false

    // —— 反复短缓冲计数：只参与 outcome 判定，不新增上报字段（契约 §4 字段集冻结）——
    // 记录理由见 [onRebufferCounted]：这个节奏既进不了 stalls，也进不了冻结段。
    private var countedRebuffers = 0
    private var countedRebufferMs = 0L

    // —— 冻结口径（契约两端冻结：FREEZE_THRESHOLD_SECONDS = 6）——
    // 段长 = 最后一次"画面推进" → 下一次"画面推进"（或该次附着结束）；
    // **恢复动作的触发不结束该段**，因此长冻结能如实上报，而不是恒为 6 秒。
    private var watching = false
    private var watchingSinceMs = 0L
    private var activeMs = 0L
    /** 是否已经出现过画面（首帧）。冻结只统计首帧之后的不推进——起播慢由 first_frame_ms 表达。 */
    private var pictureStarted = false
    private var lastProgressAtMs: Long? = null
    private var freezeMs = 0L
    private var freezeSegments = 0
    private var freezeLongestMs = 0L
    private var freezeLongSegments = 0
    private var progressEvents = 0

    fun begin(session: FeedbackSessionKey, nowMs: Long = this.nowMs()) {
        this.session = session
        startedAtMs = nowMs
        firstFrameMs = null
        stalls = 0
        stallSeconds = 0L
        lastStallStartedAtMs = null
        failReason = null
        ended = false
        countedRebuffers = 0
        countedRebufferMs = 0L
        // 开播即"用户在看"：后续由 UI 生命周期与播放状态驱动开合。
        watching = true
        watchingSinceMs = nowMs
        activeMs = 0L
        pictureStarted = false
        lastProgressAtMs = null
        freezeMs = 0L
        freezeSegments = 0
        freezeLongestMs = 0L
        freezeLongSegments = 0
        progressEvents = 0
    }

    /** 用户开始看这个频道（屏幕亮、应用前台、当前频道、未主动暂停）。 */
    fun onWatchingStarted(nowMs: Long = this.nowMs()) {
        if (session == null || ended || watching) return
        watching = true
        watchingSinceMs = nowMs
        // 画面已经开始过：回到前台时以此刻为基准，重新观察是否推进。
        if (pictureStarted && lastProgressAtMs == null) lastProgressAtMs = nowMs
    }

    /**
     * 用户不再看这个频道（熄屏 / 切后台 / 主动暂停 / 切走）。
     *
     * 冻结累计只在"在看"窗口内累加：此处把未闭合的冻结段就地截断，
     * 因此暂停时长永远不会被算成冻结（`freeze_seconds ≤ active_seconds` 由此成立）。
     */
    fun onWatchingStopped(nowMs: Long = this.nowMs()) {
        if (session == null || !watching) return
        activeMs += (nowMs - watchingSinceMs).coerceAtLeast(0L)
        watching = false
        lastProgressAtMs?.let { start -> if (nowMs > start) closeFreezeSegment(start, nowMs) }
        lastProgressAtMs = null
    }

    /**
     * 一次画面推进（每个事件 = 至少渲染出新的一帧）。
     *
     * 冻结基准从**首帧**开始：起播阶段（还没有任何画面）不计入冻结，
     * 它由 `first_frame_ms` 与 `stalls` 表达。
     */
    fun onPictureProgress(nowMs: Long = this.nowMs()) {
        if (session == null || ended) return
        progressEvents++
        val previous = lastProgressAtMs
        if (previous != null && watching && nowMs > previous) closeFreezeSegment(previous, nowMs)
        lastProgressAtMs = if (pictureStarted) nowMs else null
    }

    /** 只统计达到阈值的段；不足 6 秒的抖动整段丢弃（保守下限）。 */
    private fun closeFreezeSegment(startMs: Long, endMs: Long) {
        val span = endMs - startMs
        if (span < FeedbackFreezeContract.THRESHOLD_MS) return
        freezeMs += span
        freezeSegments++
        if (span > freezeLongestMs) freezeLongestMs = span
        if (span >= FeedbackFreezeContract.LONG_THRESHOLD_MS) freezeLongSegments++
    }

    fun onFirstFrame(nowMs: Long = this.nowMs()) {
        if (session == null || firstFrameMs != null) return
        firstFrameMs = (nowMs - startedAtMs).coerceAtLeast(0L)
        // 冻结基准从首帧开始：此前的等待属于"起播"，不记为冻结。
        pictureStarted = true
        lastProgressAtMs = nowMs
    }

    /** 一次停帧开始；同一段停帧重复调用只记一次。 */
    fun onStallStarted(nowMs: Long = this.nowMs()) {
        if (session == null || lastStallStartedAtMs != null) return
        lastStallStartedAtMs = nowMs
        stalls++
    }

    /** 一次停帧结束，累计停帧时长。 */
    fun onStallEnded(nowMs: Long = this.nowMs()) {
        val started = lastStallStartedAtMs ?: return
        stallSeconds += ((nowMs - started) / 1_000L).coerceAtLeast(0L)
        lastStallStartedAtMs = null
    }

    /**
     * 一次**已在播放侧计数**的缓冲区间结束（`RebufferHealthPolicy` 的 counted rebuffer，单次 ≥600ms）。
     *
     * 为什么要有这个入口：停帧入口取的是「恢复动作」，而 1.5~2.5 秒一次、15~20 秒复现的短缓冲
     * 每次都能自行恢复——不产生任何恢复动作、单段也够不到 6 秒冻结阈值，于是 stalls 与
     * freeze_segments 双双为 0，一次持续卡顿的观看会被报成 ok。这个入口用**换源策略自己的证据**
     * （同一份 rebuffer 事件）补齐盲区，判定见 [REBUFFER_COUNT_LIMIT]。
     */
    fun onRebufferCounted(durationMs: Long) {
        if (session == null || ended || durationMs <= 0L) return
        countedRebuffers++
        countedRebufferMs += durationMs
    }

    fun onFailure(reason: FeedbackFailReason, nowMs: Long = this.nowMs()) {
        if (session == null) return
        onStallEnded(nowMs)
        failReason = reason
    }

    /**
     * 结束会话并产出结果。
     * 返回 null 的情形：未开始、已结束、无法归因（channel_id 为空）、或 url_key 为空。
     */
    fun finish(nowMs: Long = this.nowMs()): PlaybackFeedbackResult? {
        val current = session ?: return null
        if (ended) return null
        ended = true
        onStallEnded(nowMs)
        // 收口：把"在看"窗口与未闭合的冻结段一起结算（段长按"画面恢复推进"或本附着结束计）。
        if (watching) {
            activeMs += (nowMs - watchingSinceMs).coerceAtLeast(0L)
            watching = false
        }
        lastProgressAtMs?.let { start -> if (nowMs > start) closeFreezeSegment(start, nowMs) }
        lastProgressAtMs = null
        val channelId = current.channelId?.takeIf { it.isNotBlank() } ?: return null
        if (current.urlKey.isBlank()) return null

        val playSeconds = ((nowMs - startedAtMs) / 1_000L).coerceAtLeast(0L)
        val activeSeconds = (activeMs / 1_000L).coerceAtLeast(0L)
        val freezeSeconds = (freezeMs / 1_000L).coerceAtMost(activeSeconds).coerceAtLeast(0L)
        val freezeLongestSeconds = (freezeLongestMs / 1_000L)
            .coerceAtMost(freezeSeconds)
            .coerceAtLeast(0L)
        val outcome = when {
            failReason != null -> FeedbackOutcome.FAIL
            // 有停帧但最终出画：partial。没有停帧且已出画：ok。
            stalls > 0 -> FeedbackOutcome.PARTIAL
            firstFrameMs == null -> FeedbackOutcome.FAIL
            // 停帧入口够不到的反复短缓冲同样是"看了但看得不好"：报 partial，绝不报 ok。
            // 放在最后：这条规则只会把 ok 改成 partial，不可能掩盖其它判定。
            repeatedRebuffering -> FeedbackOutcome.PARTIAL
            else -> FeedbackOutcome.OK
        }
        return PlaybackFeedbackResult(
            channelId = channelId,
            urlKey = current.urlKey,
            route = current.route,
            outcome = outcome,
            engine = current.engine,
            firstFrameMs = firstFrameMs,
            stalls = stalls,
            stallSeconds = stallSeconds,
            playSeconds = playSeconds,
            failReason = failReason ?: if (outcome == FeedbackOutcome.FAIL) FeedbackFailReason.UNKNOWN else null,
            observedAt = HubTimestamps.iso8601(startedAtMs, zoneOffsetMinutes()),
            activeSeconds = activeSeconds,
            freezeSeconds = freezeSeconds,
            freezeSegments = freezeSegments,
            freezeLongestSeconds = freezeLongestSeconds,
            freezeLongSegments = freezeLongSegments,
            pictureProgressEvents = progressEvents,
            freezeMeasured = true,
        )
    }

    /** 供诊断使用；不包含任何 URL。 */
    fun snapshot(): String =
        "feedback_session channel=${session?.channelId ?: "unattributed"} " +
            "route=${session?.route.orEmpty()} stalls=$stalls firstFrameMs=${firstFrameMs ?: -1} " +
            "countedRebuffers=$countedRebuffers countedRebufferMs=$countedRebufferMs"

    /**
     * 观看期间是否反复短缓冲到「不能再报 ok」的程度。
     *
     * 两个阈值任一达到即成立。判据刻意不放进 60 秒滑窗：滑窗是为**换源动作**服务的，
     * 而这里的用途只是如实描述一次观看的体验；一次附着动辄十几分钟，
     * 用会话累计才不会因为分布稀疏而漏判。
     *
     * 宁可多报 partial、不可漏报 ok：partial 的语义是"播了但体验有损"，
     * 单次恢复动作（`stalls > 0`）本来就会报 partial，因此这里的敏感度并不越界；
     * 而漏报的 ok 会把这个线路的持续劣化从回流里彻底抹掉（2026-09-24 实例）。
     *
     * 已知残余：附着短于约 90 秒时，4 次以内的短缓冲仍报 ok。门槛不能再低——
     * 否则一次正常的 segment 切换抖动就会把每次观看都报成 partial，这个字段就没信息量了。
     */
    private val repeatedRebuffering: Boolean
        get() = countedRebuffers >= REBUFFER_COUNT_LIMIT ||
            countedRebufferMs >= REBUFFER_TOTAL_MS_LIMIT

    companion object {
        /**
         * 反复短缓冲的计数门槛：≥5 次。
         *
         * 依据是现场节奏——一次 1.5~2.5 秒、每 15~20 秒复现，60 秒内只有 3~4 次。
         * 5 次意味着这个卡顿节奏已经在观看中反复出现，而不是偶发抖动。
         */
        const val REBUFFER_COUNT_LIMIT = 5

        /**
         * 反复短缓冲的累计时长门槛：≥20 秒。
         *
         * 与"单次 ≥25 秒才换源"的换源阈值（`RebufferHealthPolicy.SINGLE_REBUFFER_LIMIT_MS`）刻意区分：
         * 那是**动作**门槛，这是**描述**门槛，两者不冲突。
         */
        const val REBUFFER_TOTAL_MS_LIMIT = 20_000L
    }
}

/**
 * 时间戳格式化：契约示例形如 `2026-09-11T19:00:00+08:00`。
 * 先把 epoch 平移到目标时区再按 UTC 渲染，最后拼上偏移，避免依赖 API 级别受限的格式字母与 java.time。
 */
object HubTimestamps {
    fun iso8601(epochMs: Long, zoneOffsetMinutes: Int): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val localMs = epochMs + zoneOffsetMinutes * 60_000L
        val local = formatter.format(Date(localMs))
        val sign = if (zoneOffsetMinutes < 0) "-" else "+"
        val absolute = kotlin.math.abs(zoneOffsetMinutes)
        return "%s%s%02d:%02d".format(local, sign, absolute / 60, absolute % 60)
    }
}

/**
 * 上报节流策略（契约 §4 + 任务 T7）。
 * - 失败：立即上报，不等待聚合。
 * - 协议级 notices：立即上报（它承载 schema/caps 协商异常，延迟没有意义）。
 * - 成功：延迟聚合批量上报，避免占用前台与网络。
 */
object FeedbackThrottlePolicy {
    const val BATCH_MAX_RESULTS = 12
    const val BATCH_MAX_NOTICES = 8
    const val BATCH_INTERVAL_MS = 5L * 60L * 1_000L

    /**
     * [immediate] 为 true 表示「必须立刻发」：存在失败结果、或存在协议级 notice。
     * 此时即使 results 为空（只发 notices）也要发。
     */
    fun shouldFlushNow(immediate: Boolean, pendingCount: Int, lastFlushAtMs: Long, nowMs: Long): Boolean = when {
        immediate -> true
        pendingCount <= 0 -> false
        pendingCount >= BATCH_MAX_RESULTS -> true
        lastFlushAtMs <= 0L -> true
        else -> nowMs - lastFlushAtMs >= BATCH_INTERVAL_MS
    }
}

/**
 * 契约 §4 请求体的编码器。
 *
 * 只有这里负责生成 payload，因此「不含原始 URL」的断言可以集中在
 * `PlaybackFeedbackTest` 里对**最终字符串**做检查。
 */
object FeedbackPayloadEncoder {
    fun encode(
        session: String,
        client: String,
        app: String,
        deviceModel: String,
        androidApi: Int,
        abi: String,
        reportedAt: String,
        results: List<PlaybackFeedbackResult>,
        notices: List<com.kantv.app.catalog.HubNotice> = emptyList(),
    ): String {
        val builder = StringBuilder(256 + results.size * 256)
        builder.append('{')
        builder.append("\"hub_schema\":").append(HUB_SCHEMA).append(',')
        builder.append("\"client\":").append(quote(client)).append(',')
        builder.append("\"app\":").append(quote(app)).append(',')
        builder.append("\"device\":{")
        builder.append("\"model\":").append(quote(deviceModel)).append(',')
        builder.append("\"android_api\":").append(androidApi).append(',')
        builder.append("\"abi\":").append(quote(abi))
        builder.append("},")
        builder.append("\"session\":").append(quote(session)).append(',')
        builder.append("\"reported_at\":").append(quote(reportedAt)).append(',')
        // §4：notices 为 v1.1 加法字段；为空时省略，保持对 v1 服务端的兼容。
        if (notices.isNotEmpty()) {
            builder.append("\"notices\":[")
            notices.forEachIndexed { index, notice ->
                if (index > 0) builder.append(',')
                builder.append('{')
                builder.append("\"code\":").append(quote(notice.code)).append(',')
                builder.append("\"detail\":").append(quote(notice.detail))
                builder.append('}')
            }
            builder.append("],")
        }
        builder.append("\"results\":[")
        results.forEachIndexed { index, result ->
            if (index > 0) builder.append(',')
            builder.append('{')
            builder.append("\"channel_id\":").append(quote(result.channelId)).append(',')
            builder.append("\"url_key\":").append(quote(result.urlKey)).append(',')
            builder.append("\"route\":").append(quote(result.route)).append(',')
            builder.append("\"outcome\":").append(quote(result.outcome.wireName)).append(',')
            builder.append("\"engine\":").append(quote(result.engine.wireName)).append(',')
            builder.append("\"first_frame_ms\":").append(result.firstFrameMs?.toString() ?: "null").append(',')
            builder.append("\"stalls\":").append(result.stalls).append(',')
            builder.append("\"stall_seconds\":").append(result.stallSeconds).append(',')
            builder.append("\"play_seconds\":").append(result.playSeconds).append(',')
            builder.append("\"fail_reason\":").append(result.failReason?.let { quote(it.wireName) } ?: "null").append(',')
            builder.append("\"observed_at\":").append(quote(result.observedAt))
            // 契约：冻结口径五个字段 + 事件计数**整组给齐或整组不给**。
            // 未采集（旧队列记录）时整组省略——绝不把"没测"报成"零冻结"。
            if (result.freezeMeasured) {
                // 成组（缺一不可）：这五个字段要么整组给齐、要么整组不给。
                builder.append(',')
                builder.append("\"active_seconds\":").append(result.activeSeconds).append(',')
                builder.append("\"freeze_seconds\":").append(result.freezeSeconds).append(',')
                builder.append("\"freeze_segments\":").append(result.freezeSegments).append(',')
                builder.append("\"freeze_longest_seconds\":").append(result.freezeLongestSeconds).append(',')
                builder.append("\"picture_progress_events\":").append(result.pictureProgressEvents)
                // 独立可选：取不到时只省这一个，绝不让整组跟着消失（对端明确要求）。
                if (result.freezeLongSegments >= 0) {
                    builder.append(',')
                    builder.append("\"freeze_long_segments\":").append(result.freezeLongSegments)
                }
            }
            builder.append('}')
        }
        builder.append("]}")
        return builder.toString()
    }

    /** 必须与 BuildConfig.HUB_SCHEMA 一致；在纯模块里用常量表达，由单测对照 BuildConfig。 */
    const val HUB_SCHEMA = 2

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
