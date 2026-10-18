package com.kantv.app.playback

import com.kantv.app.catalog.HubCaps
import com.kantv.app.catalog.HubChannel
import com.kantv.app.catalog.HubClearKey
import com.kantv.app.catalog.HubRoute
import com.kantv.app.catalog.HubRouteType

/** 契约 §3.1 表格里「期望客户端引擎」的两类落点。 */
enum class HubEngineTarget {
    /** Media3（由既有 AdaptivePlaybackEngine 承载）。 */
    MEDIA3,

    /** 客户端网页内嵌容器（2026-09-15 起由 GeckoView 宿主唯一实现）。 */
    WEB_EMBED,
}

/**
 * 一条线路的播放计划。纯数据，不含引擎实例：
 * - 只有 [HubRouteSelector] 会构造它，route→引擎的映射因此可以直接单测。
 * - [urlKey] 是**服务端下发**的值（F1：客户端绝不在枢纽链路上自行计算）；为空即 [attributable] = false，
 *   该线路仍可播放但不参与回流。
 * - [drmUuid] 来自服务端 `drm_uuid`（v1.1 加法字段）；缺失时回落 Media3 的 `C.CLEARKEY_UUID`。
 */
data class HubRoutePlan(
    val channelId: String,
    val route: HubRouteType,
    val target: HubEngineTarget,
    val uri: String,
    /** 服务端下发的 url_key；null 表示不可归因。 */
    val urlKey: String?,
    val mimeType: String?,
    val clearKey: HubClearKey?,
    val drmUuid: String?,
    val quality: String?,
    /** [SourceQuality.NONE] when the server declared no usable label; never inferred locally. */
    val qualityRank: Int,
    val score: Int,
) {
    val requiresClearKey: Boolean get() = route == HubRouteType.DASH_CLEARKEY

    /** 是否可归因（可参与回流）：唯一判据是服务端下发的 url_key 非空。 */
    val attributable: Boolean get() = !urlKey.isNullOrBlank()
}

/** routes 全部失败后的兜底顺序（契约 §3）。 */
sealed interface HubFallbackStep {
    /** ① 服务端下发的 fallback。 */
    data class ServerFallback(val route: HubRouteType, val rawRoute: String, val uri: String) : HubFallbackStep

    /** ② 客户端本地的官网内嵌目录。 */
    data class LocalOfficialWeb(val uri: String) : HubFallbackStep
}

/**
 * route → 引擎的纯函数映射。
 *
 * 纪律：
 * - 不依据 URL 推断 route；一律以服务端 `route` 字段为准（契约 §3）。
 * - caps 之外的 route 与封闭集合外的 route 都不产出计划。
 */
object HubRouteSelector {
    fun plans(channel: HubChannel, caps: List<String> = HubCaps.declared): List<HubRoutePlan> =
        channel.routes
            .filter { supports(it.route, caps) && isPlayable(it) }
            // 契约 §3：routes 按 score 降序。服务端已排序，这里再做一次稳定排序作为防御。
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<com.kantv.app.catalog.HubRoute>> { it.value.score }.thenBy { it.index })
            .map { plan(channel.channelId, it.value) }

    /**
     * 客户端侧的最后一道 fail-closed（与服务端 §3.1 的 `dash` 分支规则呼应）：
     * `dash-clearkey` 必须带内联 `clearkey`；否则本机无法解密，投递了也只会黑屏。
     */
    private fun isPlayable(route: com.kantv.app.catalog.HubRoute): Boolean = when (route.route) {
        HubRouteType.DASH_CLEARKEY -> route.clearkey != null
        else -> true
    }

    fun supports(route: HubRouteType, caps: List<String>): Boolean =
        route != HubRouteType.UNKNOWN && route.wireName in caps

    /**
     * 兜底顺序：服务端 fallback 在前，本地官网目录在后。
     * [localOfficialPages] 由调用方按频道身份从本地官网目录解析后传入。
     */
    fun fallbackSteps(
        channel: HubChannel,
        localOfficialPages: List<String> = emptyList(),
        caps: List<String> = HubCaps.declared,
    ): List<HubFallbackStep> = buildList {
        channel.fallback?.let { fallback ->
            val type = HubRouteType.from(fallback.route)
            if (type != HubRouteType.UNKNOWN && type.wireName in caps) {
                add(HubFallbackStep.ServerFallback(type, fallback.route, fallback.url))
            }
        }
        localOfficialPages.filter { it.isNotBlank() }.forEach { add(HubFallbackStep.LocalOfficialWeb(it)) }
    }

    private fun plan(channelId: String, route: HubRoute): HubRoutePlan = HubRoutePlan(
        channelId = channelId,
        route = route.route,
        target = if (route.route == HubRouteType.WEB_EMBED) HubEngineTarget.WEB_EMBED else HubEngineTarget.MEDIA3,
        uri = route.url,
        // 只透传服务端值；不做任何本地兜底计算（F1）。
        urlKey = route.urlKey?.takeIf { it.isNotBlank() },
        mimeType = mimeTypeFor(route.route),
        clearKey = if (route.route == HubRouteType.DASH_CLEARKEY) route.clearkey else null,
        drmUuid = route.drmUuid?.takeIf { it.isNotBlank() },
        quality = route.quality,
        qualityRank = SourceQuality.parse(route.quality),
        score = route.score,
    )

    /**
     * mimeType 由**服务端声明的 route** 决定，而不是由 URL 猜测。
     * 这是引擎启动容器解析所需的提示，不参与任何归因判定。
     */
    fun mimeTypeFor(route: HubRouteType): String? = when (route) {
        HubRouteType.HLS_TS, HubRouteType.HLS_AES128 -> "application/x-mpegURL"
        HubRouteType.FLV -> "video/x-flv"
        HubRouteType.DASH_CLEARKEY -> "application/dash+xml"
        HubRouteType.WEB_EMBED, HubRouteType.UNKNOWN -> null
    }
}
