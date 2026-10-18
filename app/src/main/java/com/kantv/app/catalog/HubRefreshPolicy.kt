package com.kantv.app.catalog

/** 一次拉取之后要执行的动作。 */
enum class HubApplyAction {
    /** 应用服务端下发的新订阅。 */
    APPLY,

    /** 保留上一次成功结果（含 304「无变化」与各类失败）。 */
    KEEP_PREVIOUS,

    /** 拉取成功，但当前正在播放：延后到播放停止后再应用（不打断播放）。 */
    DEFER_UNTIL_PLAYBACK_STOPPED,
}

/** 动作原因，固定枚举，可用于状态展示与单测断言。 */
enum class HubApplyReason {
    FETCH_SUCCEEDED,
    NOT_MODIFIED,
    SCHEMA_UNSUPPORTED,
    FETCH_FAILED,
    PLAYBACK_ACTIVE,
}

data class HubRefreshDecision(
    val action: HubApplyAction,
    val reason: HubApplyReason,
    val subscription: HubSubscription? = null,
    val payload: String? = null,
    val etag: String? = null,
) {
    val shouldRebuildCatalog: Boolean get() = action == HubApplyAction.APPLY

    /** 契约 §5.2：客户端遇到更高的 hub_schema 时需「上报一条 feedback 说明」。 */
    val shouldReportSchemaMismatch: Boolean get() = reason == HubApplyReason.SCHEMA_UNSUPPORTED
}

/** 目录的来源，用于明确「内置清单只是离线兜底」。 */
enum class HubCatalogSource {
    /** 服务端订阅（当前生效）。 */
    HUB_SUBSCRIPTION,

    /** 上一次成功的服务端结果（离线兜底，服务端暂时不可达）。 */
    HUB_CACHE,

    /** 内置 asset 清单：从未成功拉取过服务端订阅时才使用。 */
    BUNDLED_FALLBACK,

    NONE,
}

/**
 * 刷新与应用的纯策略。**不**做任何 I/O，因此可以直接单测。
 *
 * 与既有 M3U 刷新语义一一对应：
 * - 拉取失败 → 保留上一次成功结果，绝不清空。
 * - 304 → 无变化，不重建目录。
 * - 播放中 → 不应用（不打断播放），等停止后补应用。
 * - 从未成功过 → 使用内置 fallback 清单，并立即尝试拉取。
 */
object HubRefreshPolicy {
    /** 契约 §6：客户端拉取频率沿用每小时。 */
    const val REFRESH_INTERVAL_MS = 60L * 60L * 1_000L

    /** payload 最大存活期：超过后不再带 If-None-Match，强制服务端回 200 与新鲜签名。 */
    const val MAX_PAYLOAD_AGE_MS = 2 * REFRESH_INTERVAL_MS

    /** 是否需要拉取。`force` 对应显式刷新动作。 */
    fun shouldRefresh(state: HubSubscriptionState, nowMs: Long, force: Boolean = false): Boolean {
        if (force) return true
        if (state.lastAttemptAtMs <= 0L) return true
        return nowMs - state.lastAttemptAtMs >= REFRESH_INTERVAL_MS
    }

    /** 最近一次成功订阅超过 [MAX_PAYLOAD_AGE_MS] 时跳过条件请求头，防止 304 无限续期过期签名。 */
    fun shouldOmitIfNoneMatch(state: HubSubscriptionState, nowMs: Long): Boolean =
        state.hasSuccessfulSubscription && nowMs - state.lastSuccessAtMs >= MAX_PAYLOAD_AGE_MS

    fun decide(
        outcome: HubFetchOutcome,
        playbackActive: Boolean,
        supportedSchema: Int,
    ): HubRefreshDecision = when (outcome) {
        is HubFetchOutcome.NotModified ->
            HubRefreshDecision(HubApplyAction.KEEP_PREVIOUS, HubApplyReason.NOT_MODIFIED)
        is HubFetchOutcome.Failed ->
            HubRefreshDecision(HubApplyAction.KEEP_PREVIOUS, HubApplyReason.FETCH_FAILED)
        is HubFetchOutcome.Fetched -> when {
            outcome.subscription.hubSchema > supportedSchema ->
                HubRefreshDecision(HubApplyAction.KEEP_PREVIOUS, HubApplyReason.SCHEMA_UNSUPPORTED)
            playbackActive -> HubRefreshDecision(
                HubApplyAction.DEFER_UNTIL_PLAYBACK_STOPPED,
                HubApplyReason.PLAYBACK_ACTIVE,
                subscription = outcome.subscription,
                payload = outcome.payload,
                etag = outcome.etag,
            )
            else -> HubRefreshDecision(
                HubApplyAction.APPLY,
                HubApplyReason.FETCH_SUCCEEDED,
                subscription = outcome.subscription,
                payload = outcome.payload,
                etag = outcome.etag,
            )
        }
    }

    /**
     * 目录来源判定：
     * - 有过成功订阅 → 服务端结果（或它的离线缓存）；
     * - 从未成功过 → 内置 fallback 清单（并应立即尝试拉取）。
     */
    fun catalogSource(state: HubSubscriptionState, hasChannels: Boolean): HubCatalogSource = when {
        state.hasSuccessfulSubscription -> HubCatalogSource.HUB_CACHE
        hasChannels -> HubCatalogSource.BUNDLED_FALLBACK
        else -> HubCatalogSource.NONE
    }

    /**
     * 是否用内置 fallback 清单兜底：仅在从未成功拉取过服务端订阅、且本机还没有任何频道时。
     * 一旦有过成功订阅（无论当前是否可达），都不再用 asset 覆盖服务端结果。
     */
    fun shouldSeedBundledFallback(state: HubSubscriptionState, hasChannels: Boolean): Boolean =
        !state.hasSuccessfulSubscription && !hasChannels
}
