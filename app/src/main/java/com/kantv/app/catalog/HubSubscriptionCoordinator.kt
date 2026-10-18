package com.kantv.app.catalog

import com.kantv.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface HubRefreshOutcome {
    data class Applied(val seq: Long, val channelCount: Int, val sourceCount: Int) : HubRefreshOutcome

    /** 拉取成功但正在播放：已缓存，等播放停止后应用。 */
    data class Deferred(val seq: Long) : HubRefreshOutcome

    /** 304：无变化，目录未重建。 */
    data object Unchanged : HubRefreshOutcome

    data class Failed(val status: String) : HubRefreshOutcome

    data class SchemaUnsupported(val serverSchema: Int) : HubRefreshOutcome

    data class Skipped(val reason: String) : HubRefreshOutcome

    val rebuiltCatalog: Boolean get() = this is Applied
}

/**
 * 落库端端口。抽出接口的唯一目的是让「拉取 → 决策 → 应用/保留」这条链路
 * 可以在 JVM 单测里跑通，而不必启动 Room / Android。
 */
interface HubCatalogSink {
    suspend fun applyHubSubscription(
        subscription: HubSubscription,
        startedAtMs: Long = System.currentTimeMillis(),
    ): CatalogImportResult
}

/**
 * 枢纽拉取与应用的编排：唯一把「客户端 + 策略 + 落库」串起来的地方。
 *
 * 纪律：
 * - 决策全部交给 [HubRefreshPolicy]，本类不做策略判断。
 * - 失败 / 304 / schema 过高一律保留旧数据（契约 §6）。
 * - 播放中不应用（契约 §6），改为缓存待用。
 * - 别名表只在服务端响应成功解析后写入（不得本地维护第二份）。
 * - 协议级事件（v1.1 §4 `notices[]`）通过 [onProtocolNote] 交给调用方「本地留痕 + 排队上报」。
 */
class HubSubscriptionCoordinator(
    private val store: HubSubscriptionStore,
    private val client: HubSubscriptionClient,
    private val sink: HubCatalogSink,
    private val aliasCache: ChannelAliasCache? = null,
    private val supportedSchema: Int = BuildConfig.HUB_SCHEMA,
    private val onProtocolNote: (HubNotice) -> Unit = {},
    /**
     * 服务端 `signed_params.fingerprint` 变化回调（P0-2 方案 C，契约加法字段）。
     * 指纹变化 = 服务端 url_key 语义已切换；默认无操作，由接线方决定如何留痕。
     * 枢纽链路的 url_key 始终来自服务端 payload，本类不做任何本地重算。
     */
    private val onSignedParamsFingerprintChange: (previous: String?, current: String) -> Unit = { _, _ -> },
) {
    suspend fun refresh(
        force: Boolean = false,
        playbackActive: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): HubRefreshOutcome = withContext(Dispatchers.IO) {
        val state = store.state()
        if (!HubRefreshPolicy.shouldRefresh(state, nowMs, force)) {
            return@withContext HubRefreshOutcome.Skipped("NOT_DUE")
        }
        store.recordAttempt(nowMs)
        val ifNoneMatch = if (HubRefreshPolicy.shouldOmitIfNoneMatch(state, nowMs)) {
            null
        } else {
            state.etag.takeIf { state.hasSuccessfulSubscription }
        }
        val outcome = client.fetch(ifNoneMatch = ifNoneMatch)
        val decision = HubRefreshPolicy.decide(outcome, playbackActive, supportedSchema)
        when (decision.action) {
            HubApplyAction.KEEP_PREVIOUS -> keepPrevious(decision, outcome, nowMs)
            HubApplyAction.APPLY -> apply(decision, nowMs, countCatalog = true)
            HubApplyAction.DEFER_UNTIL_PLAYBACK_STOPPED -> apply(decision, nowMs, countCatalog = false)
        }
    }

    /**
     * 播放停止后补应用：把已缓存但尚未生效的订阅落到目录。
     * 无待应用内容时返回 null。
     */
    suspend fun applyDeferredIfAny(nowMs: Long = System.currentTimeMillis()): HubRefreshOutcome? = withContext(Dispatchers.IO) {
        val state = store.state()
        val payload = state.payload ?: return@withContext null
        if (state.lastAppliedSeq == state.seq) return@withContext null
        val parsed = HubSubscriptionParser.parse(payload) as? HubParseOutcome.Parsed ?: return@withContext null
        if (parsed.subscription.hubSchema > supportedSchema) {
            onProtocolNote(schemaUnsupportedNotice(parsed.subscription.hubSchema))
            store.recordFailure(HubSubscriptionStatus.SCHEMA_UNSUPPORTED, nowMs)
            return@withContext HubRefreshOutcome.SchemaUnsupported(parsed.subscription.hubSchema)
        }
        applyDecision(parsed.subscription, payload, state.etag, parsed.subscription.seq, nowMs, countCatalog = true)
    }

    /** §4 的 detail 只写协商事实，不含任何 URL / header / 凭据。 */
    private fun schemaUnsupportedNotice(serverSchema: Int): HubNotice =
        HubNotice(
            code = HubNotice.CODE_SCHEMA_UNSUPPORTED,
            detail = "client supports $supportedSchema, server sent $serverSchema",
        )

    private suspend fun keepPrevious(        decision: HubRefreshDecision,
        outcome: HubFetchOutcome,
        nowMs: Long,
    ): HubRefreshOutcome = when (decision.reason) {
        HubApplyReason.NOT_MODIFIED -> {
            store.recordNotModified(nowMs)
            HubRefreshOutcome.Unchanged
        }
        HubApplyReason.SCHEMA_UNSUPPORTED -> {
            val serverSchema = (outcome as? HubFetchOutcome.Fetched)?.subscription?.hubSchema ?: -1
            // 契约 §5.2：拒绝应用 + 保留上一次成功结果，并通过 §4 的 `notices[]` 上报协议级事件
            // （v1.1 新增字段，因此不再受「results 必须有非空 channel_id」的限制）。
            onProtocolNote(schemaUnsupportedNotice(serverSchema))
            store.recordFailure(HubSubscriptionStatus.SCHEMA_UNSUPPORTED, nowMs)
            HubRefreshOutcome.SchemaUnsupported(serverSchema)
        }
        else -> {
            val status = (outcome as? HubFetchOutcome.Failed)?.status ?: HubSubscriptionStatus.INVALID_PAYLOAD
            store.recordFailure(status, nowMs)
            HubRefreshOutcome.Failed(status)
        }
    }

    private suspend fun apply(
        decision: HubRefreshDecision,
        nowMs: Long,
        countCatalog: Boolean,
    ): HubRefreshOutcome {
        val subscription = decision.subscription ?: return HubRefreshOutcome.Failed(HubSubscriptionStatus.INVALID_PAYLOAD)
        val payload = decision.payload ?: return HubRefreshOutcome.Failed(HubSubscriptionStatus.INVALID_PAYLOAD)
        if (!countCatalog) {
            // 播放中：缓存订阅与 etag，但不重建目录、不推进「已应用」水位。
            noteFingerprintChange(subscription)
            store.recordSuccess(payload, decision.etag, subscription.seq, nowMs, applied = false,
                signedParamsFingerprint = subscription.signedParams?.fingerprint)
            return HubRefreshOutcome.Deferred(subscription.seq)
        }
        return applyDecision(subscription, payload, decision.etag, subscription.seq, nowMs, countCatalog = true)
    }

    private suspend fun applyDecision(
        subscription: HubSubscription,
        payload: String,
        etag: String?,
        seq: Long,
        nowMs: Long,
        countCatalog: Boolean,
    ): HubRefreshOutcome {
        noteFingerprintChange(subscription)
        val result = sink.applyHubSubscription(subscription, nowMs)
        aliasCache?.replace(subscription.aliasTable())
        store.recordSuccess(payload, etag, seq, nowMs, applied = countCatalog,
            signedParamsFingerprint = subscription.signedParams?.fingerprint)
        return when (result) {
            is CatalogImportResult.Imported -> HubRefreshOutcome.Applied(seq, result.channelCount, result.sourceCount)
            // 空清单同样视为成功（服务端可能只投递 unsupported_for_client）。
            is CatalogImportResult.Preserved, is CatalogImportResult.Rejected ->
                HubRefreshOutcome.Applied(seq, 0, 0)
        }
    }

    /**
     * 指纹变化检测（P0-2 方案 C）：最近一次成功订阅的 `signed_params.fingerprint`
     * 与持久化锚点不一致时回调（首次见到指纹也会回调，previous=null）。
     * 客户端不持有跨启动持久的 url_key 归因缓存（payload 即服务端原文，ETag 机制保证
     * 指纹变化后下一次刷新必拉新 payload），因此只需留痕，无需清理任何本地状态。
     */
    private fun noteFingerprintChange(subscription: HubSubscription) {
        val current = subscription.signedParams?.fingerprint ?: return
        val previous = store.state().signedParamsFingerprint
        if (current != previous) {
            onSignedParamsFingerprintChange(previous, current)
        }
    }
}
