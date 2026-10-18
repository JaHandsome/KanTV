package com.kantv.app.catalog

/**
 * HUB CONTRACT v1 §3 响应体的 Kotlin 模型。
 *
 * 这些类型只描述**服务端下发的**内容，不包含任何客户端推断。
 * 解析见 [HubSubscriptionParser]；未知字段一律忽略（等价于 `ignoreUnknownKeys = true`）。
 */
data class HubSubscription(
    val hubSchema: Int,
    val seq: Long,
    val generatedAt: String?,
    val server: HubServerInfo,
    val groups: List<HubGroup>,
    /**
     * §3 的 `signed_params`（v1.1 加法字段）。服务端未投递时为 null；
     * **缺失即视为本地计算不可用**，此时只能依赖服务端下发的 `routes[].url_key`。
     */
    val signedParams: HubSignedParams?,
    val channels: List<HubChannel>,
    val aliases: List<ChannelAlias>,
) {
    /** 频道身份索引（契约 §1.1 的 aliases 与 channels 都来自这一份响应）。 */
    fun aliasTable(): ChannelAliasTable = ChannelAliasTable.from(
        aliases = aliases,
        channels = channels.map { ChannelIdentityRecord(it.channelId, it.display) },
    )
}

/** §2.2 / §3 的 `signed_params`：全局名单 + host 作用域名单 + 名单指纹（v1.1 加法字段）。 */
data class HubSignedParams(
    val global: List<String> = emptyList(),
    val byHost: Map<String, List<String>> = emptyMap(),
    /**
     * 生效名单的稳定指纹（服务端按当前名单重算全部 url_key 的语义开关）。
     * 指纹变化 = 服务端 url_key 语义已切换（陈旧 key 已全表重算后投递）；
     * 服务端未投递时为 null（旧版服务端，无此信号）。
     */
    val fingerprint: String? = null,
) {
    val isEmpty: Boolean get() = global.isEmpty() && byHost.isEmpty()
}

/** §4 的 `notices[]`：承载协议级事件（v1.1 新增，加法字段）。 */
data class HubNotice(val code: String, val detail: String) {
    companion object {
        const val CODE_SCHEMA_UNSUPPORTED = "schema_unsupported"
        const val CODE_CAPS_UNKNOWN = "caps_unknown"
        const val CODE_OTHER = "other"

        /** 本版仅定义 schema_unsupported；未知 code 服务端必须接受并计数。 */
        val knownCodes: Set<String> = setOf(CODE_SCHEMA_UNSUPPORTED, CODE_CAPS_UNKNOWN, CODE_OTHER)
    }
}

data class HubServerInfo(val app: String?, val capsSupported: List<String>)

data class HubGroup(val id: String, val title: String, val order: Int)

/** 契约 §3 的 coverage 取值。未知取值不猜测，记为 [UNKNOWN]。 */
enum class HubCoverage {
    OK,
    PARTIAL,
    MISSING,
    UNSUPPORTED_FOR_CLIENT,
    UNKNOWN;

    companion object {
        fun from(value: String?): HubCoverage = when (value?.lowercase()) {
            "ok" -> OK
            "partial" -> PARTIAL
            "missing" -> MISSING
            "unsupported_for_client" -> UNSUPPORTED_FOR_CLIENT
            else -> UNKNOWN
        }
    }
}

/**
 * 契约 §3.1 的 route 封闭集合。
 * 服务端出现集合外的取值时记为 [UNKNOWN]，客户端不使用它（且**不得**依据 url 自行推断 route）。
 */
enum class HubRouteType(val wireName: String) {
    HLS_TS("hls-ts"),
    HLS_AES128("hls-aes128"),
    FLV("flv"),
    DASH_CLEARKEY("dash-clearkey"),
    WEB_EMBED("web-embed"),
    UNKNOWN("");

    companion object {
        fun from(value: String?): HubRouteType =
            entries.firstOrNull { it != UNKNOWN && it.wireName == value?.lowercase() } ?: UNKNOWN
    }
}

/** 契约 §4 的 outcome 取值。 */
enum class FeedbackOutcome(val wireName: String) {
    OK("ok"),
    PARTIAL("partial"),
    FAIL("fail"),
}

/** 契约 §4 的 fail_reason 取值。 */
enum class FeedbackFailReason(val wireName: String) {
    NO_FRAME("no_frame"),
    TIMEOUT("timeout"),
    HTTP_403("http_403"),
    HTTP_404("http_404"),
    DECODE_ERROR("decode_error"),
    DRM_ERROR("drm_error"),
    NETWORK("network"),
    UNKNOWN("unknown"),
}

/** 契约 §4 的 engine 取值。 */
enum class FeedbackEngine(val wireName: String) {
    MEDIA3("media3"),
    VLC("vlc"),
    WEB_EMBED("web-embed"),
}

data class HubClearKey(val key: String, val kid: String)

data class HubRoute(
    val route: HubRouteType,
    /** 服务端原始 route 字段，仅在 route 未知时用于诊断（不上报、不落库为可播放源）。 */
    val rawRoute: String,
    val url: String,
    /**
     * §2.5 / §3：`routes[].url_key` 由服务端保证非空，客户端**只消费**。
     * 服务端未给时此值为 null —— 该线路仍可播放，但**不可归因**（不参与回流）。
     */
    val urlKey: String?,
    val container: String?,
    val encryption: String?,
    val kid: String?,
    val clearkey: HubClearKey?,
    /**
     * §3：仅当 route 为 `dash-clearkey` 且服务端已核实 MPD 的
     * `ContentProtection@schemeIdUri` 时提供；缺失时客户端回落 Media3 的 `C.CLEARKEY_UUID`。
     */
    val drmUuid: String?,
    val quality: String?,
    val score: Int,
    val verifiedAt: String?,
) {
    /** 是否可归因（可参与回流）。以服务端下发的 `url_key` 为非空作为唯一判据。 */
    val attributable: Boolean get() = !urlKey.isNullOrBlank()
}

data class HubFallback(val route: String, val url: String)

data class HubChannel(
    val channelId: String,
    val display: String,
    val groupId: String?,
    val logo: String?,
    val epgId: String?,
    val coverage: HubCoverage,
    val routes: List<HubRoute>,
    val fallback: HubFallback?,
)

/**
 * 契约 §5.1 的 caps 封闭集合。客户端只声明自己真正具备的能力。
 * 本次由常量给出（P1 待办：由设备能力自动推导）。
 */
object HubCaps {
    const val HLS_TS = "hls-ts"
    const val HLS_AES128 = "hls-aes128"
    const val FLV = "flv"
    const val DASH_CLEARKEY = "dash-clearkey"
    const val WEB_EMBED = "web-embed"

    /** 客户端声明的能力集合（顺序固定，便于断言与日志比对）。 */
    val declared: List<String> = listOf(HLS_TS, HLS_AES128, FLV, DASH_CLEARKEY, WEB_EMBED)

    val declaredCsv: String get() = declared.joinToString(",")

    /** 服务端遇到未知 caps 必须忽略；客户端只发送封闭集合内的值。 */
    fun sanitize(values: List<String>): List<String> = values.filter { it in declared }
}

/** 客户端身份，用于投递请求的 `app` / `device` 查询参数。 */
data class HubClientIdentity(
    val client: String = "kantv",
    val app: String,
    val device: String,
)
