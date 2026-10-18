package com.kantv.app.catalog

import java.util.Locale

/**
 * Conservative channel identities used only to combine sources which are known to be the
 * same service.  A missing key means "do not merge by name"; this is intentional because
 * playlists frequently reuse generic names such as "新闻".
 */
object ChannelIdentity {
    /**
     * 契约 §1.1 的四步别名归一化。[aliases] 必须来自服务端下发的数据（见 [ChannelAliasTable]），
     * 客户端不得本地维护第二份别名表。
     *
     * 1. 精确命中 `aliases[].name` → 其 channel_id
     * 2. 精确命中某频道的 `display` 或 `channel_id` → 该 channel_id
     * 3. 将名称繁转简（t2s）后重复步骤 1–2
     * 4. 均未命中 → null
     */
    fun resolveChannelId(name: String?, aliases: ChannelAliasTable?): String? {
        if (name.isNullOrEmpty() || aliases == null || aliases.isEmpty) return null
        aliases.channelIdForAlias(name)?.let { return it }
        aliases.channelIdForIdentity(name)?.let { return it }
        val simplified = TraditionalToSimplified.convert(name)
        if (simplified == name) return null
        aliases.channelIdForAlias(simplified)?.let { return it }
        return aliases.channelIdForIdentity(simplified)
    }

    /** Removed services are ignored in both built-in and imported catalogues. */
    fun isSuppressed(tvgId: String?, name: String, tvgName: String? = null): Boolean =
        listOf(name, tvgName.orEmpty(), tvgId.orEmpty())
            .map(::normalise)
            .any { it == "officialgdtv42" || it.contains("南方购物") }

    /** Keep a single viewer-facing label when known aliases represent the same service. */
    fun displayName(tvgId: String?, name: String, tvgName: String? = null): String = when (knownKey(tvgId, name, tvgName)) {
        "tvb-news" -> "无线新闻"
        else -> name
    }

    fun knownKey(tvgId: String?, name: String, tvgName: String? = null): String? {
        // A generic tvg-id must never erase an explicit service variant in the display name.
        // For example, a feed labelled CCTV-4 Europe may carry tvg-id=CCTV4.
        val names = listOf(name, tvgName.orEmpty()).map(::normalise)
        names.mapNotNull(::independentVariantKey).firstOrNull()?.let { return it }
        return (names.asSequence() + sequenceOf(normalise(tvgId.orEmpty())))
            .map(::normalise)
            .mapNotNull(::keyForNormalised)
            .firstOrNull()
    }

    /**
     * Every recognized service uses its canonical identity as its storage key, and unknown
     * services with a non-empty tvg-id also include their labels.  Thus a provider's accidental
     * generic tvg-id cannot overwrite another channel before merge policy runs.
     */
    fun storageKey(tvgId: String?, name: String, tvgName: String? = null): String? {
        listOf(name, tvgName.orEmpty()).asSequence().map(::normalise).mapNotNull(::independentVariantKey).firstOrNull()?.let { return it }
        knownKey(tvgId, name, tvgName)?.let { return it }
        if (tvgId.isNullOrBlank()) return null
        return "unknown-tvg:${normalise(tvgId)}\u001F${normalise(tvgName.orEmpty())}\u001F${normalise(name)}"
    }

    /** A canonical identity takes precedence over a provider's potentially reused row ID. */
    fun shouldMerge(firstId: String, firstIdentity: String?, secondId: String, secondIdentity: String?): Boolean =
        if (firstIdentity != null || secondIdentity != null) firstIdentity != null && firstIdentity == secondIdentity
        else firstId == secondId

    // 合并判定等价于该键相等：身份优先，双方皆无身份时退回行 ID（shouldMerge 的真值表）。
    // 目录刷新把每次扫表重算 normalise（含正则）压成每实体一次。
    fun mergeKeyOf(id: String, identity: String?): String = identity ?: ("row\u001F" + id)

    private val PUNCTUATION = Regex("[\\s\\-—_·.（）()【】\\[\\]]")
    private val QUALITY_PREFIX = Regex("^(高清|超清|蓝光|hd|fhd|uhd)")
    private val QUALITY_SUFFIX = Regex("(高清|超清|蓝光|hd|fhd|uhd|1080p|720p)$")

    private fun normalise(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace("中央电视台", "cctv")
        .replace("央视", "cctv")
        .replace("購", "购")
        .replace("无綫", "tvb")
        .replace("無綫", "tvb")
        .replace(PUNCTUATION, "")
        // Some public playlists put the quality marker before the service name rather than after
        // it (for example "高清翡翠台"). Only strip this closed set of presentation markers;
        // arbitrary name prefixes still remain distinct.
        .replace(QUALITY_PREFIX, "")
        .replace(QUALITY_SUFFIX, "")

    private val CCTV5_PLUS_NAMES = setOf("cctv5+", "cctv5plus", "cctv5体育赛事", "cctv5+体育赛事")
    private val CCTV_LONG = Regex("^cctv0?([1-9]|1[0-7])(?:台|频道|综合|综合频道|财经|综艺|中文国际|体育|电影|国防军事|电视剧|纪录|科教|戏曲|社会与法|新闻|少儿|音乐|奥林匹克|农业农村)?$")
    private val CCTV_SHORT = Regex("^cctv([1-9]|1[0-7])$")

    private fun keyForNormalised(value: String): String? {
        // 5+ has to be considered before the numeric CCTV matcher.
        if (value in CCTV5_PLUS_NAMES) return "cctv-5plus"
        CCTV_LONG.matchEntire(value)?.groupValues?.get(1)?.let { return "cctv-$it" }
        CCTV_SHORT.matchEntire(value)?.groupValues?.get(1)?.let { return "cctv-$it" }

        return when (value) {
            "第一财经", "第一财经频道", "yicai", "officialyicaimain" -> "finance-yicai"
            "东方财经", "东方财经频道", "dfcj", "officialyicaidfcj" -> "finance-dfcj"
            "tvb新闻", "tvb新闻台", "tvb新聞", "tvb新聞台", "83tvb新闻台", "83tvb新聞台",
            "无线新闻", "无线新闻台", "無綫新闻", "無綫新闻台", "无綫新闻", "无綫新闻台", "tvbwebc" -> "tvb-news"
            "tvb财经", "tvb财经频道", "tvb財經", "tvb財經頻道", "tvbwebf" -> "tvb-finance"
            "翡翠台", "tvb翡翠台", "tvbjade", "jade" -> "tvb-jade"
            "广东珠江", "广东新闻", "广东民生", "广东体育", "大湾区卫视", "广东影视", "广东少儿",
            "嘉佳卡通", "岭南戏曲", "广东移动", "广东台经典剧" -> "gdtv-$value"
            else -> satelliteKey(value)
        }
    }

    private val CCTV4_ASIA = Regex("^cctv4.*亚洲$")
    private val CCTV4_EUROPE = Regex("^cctv4.*欧洲$")
    private val CCTV4_AMERICAS = Regex("^cctv4.*美洲$")
    private val CCTV_4K = Regex("^cctv([1-9]|1[0-7])4k$")
    private val CCTV_8K = Regex("^cctv([1-9]|1[0-7])8k$")

    private fun independentVariantKey(value: String): String? = when {
        value == "cctv4k" -> "cctv-4k"
        value == "cctv8k" -> "cctv-8k"
        CCTV4_ASIA.matches(value) -> "cctv-4-asia"
        CCTV4_EUROPE.matches(value) -> "cctv-4-europe"
        CCTV4_AMERICAS.matches(value) -> "cctv-4-americas"
        CCTV_4K.matches(value) -> "cctv-${CCTV_4K.matchEntire(value)!!.groupValues[1]}-4k"
        CCTV_8K.matches(value) -> "cctv-${CCTV_8K.matchEntire(value)!!.groupValues[1]}-8k"
        value == "cctv5体育赛事" -> "cctv-5-event-unqualified"
        value == "大湾区卫视海外版" -> "gdtv-bay-area-overseas"
        else -> null
    }

    private val SATELLITE_NAMES = setOf(
        "北京", "江苏", "东方", "浙江", "湖南", "湖北", "广东", "广西", "黑龙江", "海南",
        "重庆", "深圳", "四川", "河南", "福建东南", "贵州", "江西", "辽宁", "安徽", "河北",
        "山东", "天津", "吉林", "陕西", "甘肃", "宁夏", "内蒙古", "云南", "山西", "青海",
        "西藏", "新疆",
    )

    private fun satelliteKey(value: String): String? {
        val name = when (value) {
            "东南卫视", "東南衛視" -> "福建东南"
            else -> value.removeSuffix("卫视").removeSuffix("衛視")
        }
        // Only the explicit public catalogue names are safe aliases.  Do not make a broad
        // "same displayed name" rule for arbitrary M3U entries with different tvg-id values.
        return if (name in SATELLITE_NAMES && (value.endsWith("卫视") || value.endsWith("衛視"))
        ) "satellite-$name" else null
    }
}

/** A source value without log-friendly rendering of its sensitive fields. */
data class CatalogSource(
    val url: String,
    val headers: Map<String, String>,
    val official: Boolean,
    /** Owner IDs are provenance, not loggable source details. */
    val owners: Set<String> = emptySet(),
    /** Persists the legacy protection after this source has been merged with an owned source. */
    val legacyUnowned: Boolean = owners.isEmpty(),
) {
    override fun toString(): String = "CatalogSource(url=<redacted>, headers=<redacted>, official=$official, owners=${owners.size}, legacy=$legacyUnowned)"
}

object CatalogSourceMerge {
    /** Official sources are first; URL plus the complete header set is the identity. */
    fun merge(existing: List<CatalogSource>, incoming: List<CatalogSource>): List<CatalogSource> =
        (existing + incoming)
            .groupBy { source -> source.url to source.headers.toSortedMap() }
            .values
            .map { duplicates ->
                duplicates.first().copy(
                    official = duplicates.any { it.official },
                    owners = duplicates.flatMapTo(linkedSetOf()) { it.owners },
                    legacyUnowned = duplicates.any { it.legacyUnowned },
                )
            }
            .sortedByDescending { it.official }

    fun removeOwner(sources: List<CatalogSource>, owner: String): List<CatalogSource> =
        sources.mapNotNull { source ->
            // Empty owners in storage predate owner tracking.  They are legacy, not evidence
            // that this subscription owns the source.
            if (source.legacyUnowned || source.owners.isEmpty() || owner !in source.owners) source.copy(owners = source.owners - owner)
            else source.copy(owners = source.owners - owner).takeIf { it.official || it.owners.isNotEmpty() }
        }
}
