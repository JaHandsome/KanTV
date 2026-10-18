package com.kantv.app.catalog

import org.json.JSONArray
import org.json.JSONObject

/** 解析失败的原因。只保留固定枚举，避免把服务端原文写进日志。 */
enum class HubParseReason {
    NOT_JSON,
    MISSING_SCHEMA,
    MISSING_CHANNELS,
    UNSUPPORTED_SCHEMA_VALUE,
}

sealed interface HubParseOutcome {
    data class Parsed(
        val subscription: HubSubscription,
        /** channel_id 为空、无法归因的条目数：跳过后不影响其余频道。 */
        val skippedChannels: Int,
    ) : HubParseOutcome

    data class Invalid(val reason: HubParseReason) : HubParseOutcome
}

/**
 * 契约 §3 响应体的解析器。
 *
 * 未知字段一律忽略——本仓库没有引入 kotlinx.serialization，手工解析天然具备
 * `ignoreUnknownKeys = true` 的等价语义，且不需要新增编译期插件。
 *
 * 宽容/严格边界：
 * - 严格：顶层必须是 JSON 对象；`hub_schema` 必须存在且为整数；`channels` 必须存在且为数组。
 * - 宽容：缺少可选字段（logo/epg_id/groups/aliases/quality/score/verified_at）时按默认值处理。
 * - 跳过：channel_id 为空的频道条目（客户端禁止以显示名作为主键）。
 * - 不推断：route 取封闭集合内的值；集合外记为 [HubRouteType.UNKNOWN]，不依据 url 猜测。
 */
object HubSubscriptionParser {
    fun parse(json: String): HubParseOutcome {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return HubParseOutcome.Invalid(HubParseReason.NOT_JSON)
        }
        if (!root.has("hub_schema") || root.isNull("hub_schema")) {
            return HubParseOutcome.Invalid(HubParseReason.MISSING_SCHEMA)
        }
        val schema = root.optInt("hub_schema", Int.MIN_VALUE)
        if (schema <= 0) return HubParseOutcome.Invalid(HubParseReason.UNSUPPORTED_SCHEMA_VALUE)
        val channelArray: JSONArray = root.optJSONArray("channels")
            ?: return HubParseOutcome.Invalid(HubParseReason.MISSING_CHANNELS)

        var skipped = 0
        val channels = buildList {
            for (index in 0 until channelArray.length()) {
                val row = channelArray.optJSONObject(index)
                if (row == null) {
                    skipped++
                    continue
                }
                val channelId = row.optString("channel_id").trim()
                if (channelId.isEmpty()) {
                    // 客户端禁止以显示名作为主键，因此无法归因的条目直接跳过。
                    skipped++
                    continue
                }
                add(
                    HubChannel(
                        channelId = channelId,
                        display = row.optString("display").trim().ifEmpty { channelId },
                        groupId = row.optString("group_id").trim().ifEmpty { null },
                        logo = row.optString("logo").trim().ifEmpty { null },
                        epgId = row.optString("epg_id").trim().ifEmpty { null },
                        coverage = HubCoverage.from(row.optString("coverage").ifEmpty { null }),
                        routes = parseRoutes(row.optJSONArray("routes")),
                        fallback = row.optJSONObject("fallback")?.let { fallback ->
                            val url = fallback.optString("url").trim()
                            if (url.isEmpty()) null
                            else HubFallback(fallback.optString("route").trim(), url)
                        },
                    ),
                )
            }
        }

        val subscription = HubSubscription(
            hubSchema = schema,
            seq = root.optLong("seq", 0L),
            generatedAt = root.optString("generated_at").trim().ifEmpty { null },
            server = root.optJSONObject("server")?.let { server ->
                HubServerInfo(
                    app = server.optString("app").trim().ifEmpty { null },
                    capsSupported = server.optJSONArray("caps_supported").stringList(),
                )
            } ?: HubServerInfo(null, emptyList()),
            groups = root.optJSONArray("groups")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val row = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = row.optString("id").trim()
                    if (id.isEmpty()) null
                    else HubGroup(id, row.optString("title"), row.optInt("order", 0))
                }
            }.orEmpty(),
            // §3：signed_params 为 v1.1 加法字段；v1 服务端不投递 → 容忍缺失（null）。
            signedParams = root.optJSONObject("signed_params")?.let { params ->
                HubSignedParams(
                    global = params.optJSONArray("global").stringList(),
                    byHost = params.optJSONObject("by_host")?.let { hosts ->
                        hosts.keys().asSequence().associateWith { key -> hosts.optJSONArray(key).stringList() }
                    }.orEmpty(),
                    // P0-2 方案 C 契约加法字段：名单指纹（v1.1 服务端未投递 → null）。
                    fingerprint = params.optString("fingerprint").trim().ifEmpty { null },
                ).takeIf { !it.isEmpty }
            },
            channels = channels,
            aliases = root.optJSONArray("aliases")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val row = array.optJSONObject(index) ?: return@mapNotNull null
                    val name = row.optString("name").trim()
                    val channelId = row.optString("channel_id").trim()
                    if (name.isEmpty() || channelId.isEmpty()) null else ChannelAlias(name, channelId)
                }
            }.orEmpty(),
        )
        return HubParseOutcome.Parsed(subscription, skipped)
    }

    private fun parseRoutes(array: JSONArray?): List<HubRoute> {
        if (array == null) return emptyList()
        val routes = buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val url = row.optString("url").trim()
                if (url.isEmpty()) continue
                val rawRoute = row.optString("route").trim()
                add(
                    HubRoute(
                        route = HubRouteType.from(rawRoute),
                        rawRoute = rawRoute,
                        url = url,
                        // F1 / §2.5：**只消费服务端下发的 url_key**，绝不在枢纽链路上本地计算。
                        // 为空即不可归因：该线路仍可播放，但不参与回流。
                        urlKey = row.optString("url_key").trim().ifEmpty { null },
                        container = row.optString("container").trim().ifEmpty { null },
                        encryption = row.optString("encryption").trim().ifEmpty { null },
                        kid = row.optString("kid").trim().ifEmpty { null },
                        clearkey = row.optJSONObject("clearkey")?.let { clearkey ->
                            val key = clearkey.optString("k").trim()
                            val kid = clearkey.optString("kid").trim()
                            if (key.isEmpty()) null else HubClearKey(key, kid)
                        },
                        // §3：drm_uuid 可选；仅 dash-clearkey 且服务端已核实时提供。
                        drmUuid = row.optString("drm_uuid").trim().ifEmpty { null },
                        quality = row.optString("quality").trim().ifEmpty { null },
                        score = row.optInt("score", 0),
                        verifiedAt = row.optString("verified_at").trim().ifEmpty { null },
                    ),
                )
            }
        }
        // 契约 §3：routes 按 score 降序。服务端已排序，客户端再做一次稳定排序作为防御。
        return routes.withIndex()
            .sortedWith(compareByDescending<IndexedValue<HubRoute>> { it.value.score }.thenBy { it.index })
            .map { it.value }
    }

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optString(index).trim().ifEmpty { null } }
    }
}
