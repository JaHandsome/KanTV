package com.kantv.app.catalog

import com.kantv.app.playback.HubFallbackStep
import com.kantv.app.playback.HubRoutePlan
import com.kantv.app.playback.HubRouteSelector
import com.kantv.app.playback.OfficialWebChannelCatalog
import com.kantv.app.playback.PlaybackRoute
import com.kantv.app.playback.SupportedWebLivePage

/**
 * 已应用的枢纽订阅（内存视图）。
 *
 * 职责：把「服务端下发的 route 元数据」暴露给播放层与回流层。
 * - 播放层用它构造带 route / url_key / ClearKey 的播放请求。
 * - 回流层用它取 url_key（只上报哈希）。
 * - 兜底用它把「服务端 fallback」与「本地官网内嵌目录」串成契约 §3 的顺序。
 *
 * 只读本地已持久化的服务端 payload，**不**产生任何新数据。
 */
class HubRouteRegistry(
    private val store: HubSubscriptionStore,
    /** 本地官网内嵌目录（契约 §3 兜底第 ② 步）。默认取已验收的那 83 个页面。 */
    private val localOfficialPages: List<SupportedWebLivePage> = OfficialWebChannelCatalog.installedPages,
) {
    private var memoPayload: String? = null
    private var memoSubscription: HubSubscription? = null

    /** 当前已持久化的订阅；解析失败或从未成功时返回 null。 */
    fun subscription(): HubSubscription? {
        val payload = store.state().payload ?: return null
        if (payload == memoPayload) return memoSubscription
        val parsed = (HubSubscriptionParser.parse(payload) as? HubParseOutcome.Parsed)?.subscription
        memoPayload = payload
        memoSubscription = parsed
        return parsed
    }

    fun hasChannel(serverChannelId: String): Boolean =
        subscription()?.channels?.any { it.channelId == serverChannelId } == true

    fun serverChannelIdForInternal(internalChannelId: String): String? =
        subscription()?.channels?.firstOrNull { HubChannelIdentity.internalId(it.channelId) == internalChannelId }?.channelId

    fun channelForInternal(internalChannelId: String): HubChannel? {
        val serverId = serverChannelIdForInternal(internalChannelId) ?: return null
        return subscription()?.channels?.firstOrNull { it.channelId == serverId }
    }

    /** 某频道的可播放线路（按服务端 score 降序，且只保留本机 caps 覆盖的 route）。 */
    fun plansForInternal(internalChannelId: String, caps: List<String> = HubCaps.declared): List<HubRoutePlan> {
        val channel = channelForInternal(internalChannelId) ?: return emptyList()
        return HubRouteSelector.plans(channel, caps)
    }

    /** 契约 §3 的兜底顺序：① 服务端 fallback → ② 本地官网内嵌目录。 */
    fun fallbackStepsForInternal(internalChannelId: String, caps: List<String> = HubCaps.declared): List<HubFallbackStep> {
        val channel = channelForInternal(internalChannelId) ?: return emptyList()
        return HubRouteSelector.fallbackSteps(channel, localPagesFor(channel), caps)
    }

    /**
     * 兜底解析：返回第一个可被 WebView 容器打开的官网页面。
     * 服务端 fallback 若指向本地官网目录之外的地址，则跳过（容器只接受已验收的官网页面）。
     */
    fun fallbackRouteForInternal(internalChannelId: String): PlaybackRoute.OfficialWeb? {
        val channel = channelForInternal(internalChannelId) ?: return null
        val steps = fallbackStepsForInternal(internalChannelId)
        steps.forEach { step ->
            val url = when (step) {
                is HubFallbackStep.ServerFallback -> step.uri
                is HubFallbackStep.LocalOfficialWeb -> step.uri
            }
            val page = SupportedWebLivePage.from(url) ?: return@forEach
            if (page.officialPlayer && page.playbackBlockedMessage == null) {
                return PlaybackRoute.OfficialWeb(internalChannelId, page)
            }
        }
        return null
    }

    /** 服务端 fallback 中无法被本地容器打开的地址；仅用于文档与诊断，不含原始 URL 的日志输出。 */
    fun unplayableServerFallbacks(internalChannelId: String): Int {
        val channel = channelForInternal(internalChannelId) ?: return 0
        val fallback = channel.fallback ?: return 0
        return if (SupportedWebLivePage.from(fallback.url) == null) 1 else 0
    }

    /** 本地官网目录里与该频道同身份的页面（尽力而为：按既有身份归一化算法匹配）。 */
    private fun localPagesFor(channel: HubChannel): List<String> {
        val target = ChannelIdentity.knownKey(null, channel.display) ?: channel.display
        return localOfficialPages.filter { page ->
            page.name == channel.display || ChannelIdentity.knownKey(page.tvgId, page.name) == target
        }.map { it.url }
    }
}
