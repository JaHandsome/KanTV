package com.kantv.app.catalog

/**
 * 频道名归一键：别名表未收录时的兜底归一依据（诊断与人工排查用）。
 *
 * 规则刻意保守——只剥离确定不区分频道的装饰（空白、标点、清晰度/套词后缀），
 * 保留所有有区分度的字符（如 CCTV5+ 的 +）。繁体先转简体再做比较。
 *
 * 注意：**合并判定不使用本归一键**，见 [ChannelVariantMerger]：只有有效身份相同才合并。
 */
object ChannelNameKey {
    /** 依次从两端剥离的修饰词；顺序无关，循环剥离直到稳定。 */
    private val DECORATION_TOKENS = listOf("综合频道", "综合", "频道", "高清", "超清", "标清", "hd", "fhd", "uhd")

    private val DECORATION_CHARS = Regex("[\\s·・•‧.,，。:：;；!！?？\\-—－~_()（）\\[\\]【】「」『』\"'“”]+")

    /** 返回归一键；名字过短（剥完不足 2 字符）返回 null。 */
    fun canonicalKey(name: String): String? {
        var value = TraditionalToSimplified.convert(name.trim()).lowercase()
        if (value.isEmpty()) return null
        value = DECORATION_CHARS.replace(value, "")
        var changed = true
        while (changed) {
            changed = false
            for (token in DECORATION_TOKENS) {
                val stripped = value.removeSuffix(token).removePrefix(token)
                if (stripped != value && stripped.length >= 2) {
                    value = stripped
                    changed = true
                }
            }
        }
        return value.takeIf { it.length >= 2 }
    }
}

/**
 * 合并输入：与存储解耦的最小频道视图（sources 已解密）。
 *
 * [identityKey] 是存储列 `identityKey`：服务端枢纽频道为 `hub:<channel_id>`，本地推导的身份为
 * `known:<key>`；原始 M3U 行可能为空。合并判定只使用**有效身份**，绝不依据 URL。
 */
data class ChannelVariantInput(
    val id: String,
    val position: Int,
    val name: String,
    val tvgId: String?,
    val logoUrl: String?,
    val groupTitle: String?,
    val sources: List<CatalogSource>,
    val identityKey: String? = null,
)

/** 一次合并的执行计划：保留 keep（合并后的形态），删除 removeIds。 */
data class ChannelVariantMerge(val keep: ChannelVariantInput, val removeIds: List<String>)

/**
 * 频道变体合并规划（纯函数）。**只有有效身份相同才合并**：
 *
 * 1. 每行的有效身份 = 存储的 [ChannelVariantInput.identityKey]，缺失时回落到
 *    [ChannelIdentity.knownKey]（`known:<key>`）；
 * 2. 仅当两行有效身份**非空且相等**时合并——`CCTV5` 与 `CCTV5+`、`CCTV4` 与 `CCTV4K`、
 *    CCTV4 亚洲/欧洲/美洲版因此各自独立；
 * 3. 身份缺失（无法判定）的行**永不**按名字合并：两条同名但 tvg-id 不同的源必须保持独立，
 *    名字归一键 [ChannelNameKey.canonicalKey] 只用于别名表未覆盖时的人工排查，不参与合并；
 * 4. 不同枢纽身份的行永不合并，即使显示名一致。
 *
 * 有效身份由调用方通过 [identityOf] 注入。调用方可以使用服务端别名表，或使用
 * [KnownHubIdentityBridge] 的封闭已知身份唯一映射；未知显示名绝不参与猜测。
 *
 * 合并后：普通频道保留 position 最靠前的行；枢纽身份组保留真正持有该 `hub:` 身份的行 ID，
 * 但继承组内最靠前的 position，避免合并后频道跳位或丢失枢纽线路注册。显示名取组内最短者，
 * 来源做并集（official 优先、owner 保留），元数据字段逐级回填。
 */
object ChannelVariantMerger {
    private const val KNOWN_IDENTITY_PREFIX = "known:"

    fun plan(
        inputs: List<ChannelVariantInput>,
        identityOf: (ChannelVariantInput) -> String? = ChannelVariantMerger::defaultIdentity,
    ): List<ChannelVariantMerge> = inputs
        .map { input -> input to identityOf(input)?.takeIf { it.isNotBlank() } }
        .filter { (_, identity) -> identity != null }
        .groupBy({ (_, identity) -> identity!! }, { (input, _) -> input })
        .mapNotNull { (identity, group) ->
            if (group.size < 2) return@mapNotNull null
            val ordered = group.sortedBy { it.position }
            val keep = if (HubChannelIdentity.isAuthoritative(identity)) {
                ordered.firstOrNull { it.identityKey == identity } ?: ordered.first()
            } else {
                ordered.first()
            }
            val losers = ordered.filterNot { it.id == keep.id }
            val sources = losers.fold(keep.sources) { acc, loser ->
                CatalogSourceMerge.merge(acc, loser.sources)
            }
            val display = ordered.minByOrNull { it.name.length } ?: keep
            ChannelVariantMerge(
                keep = keep.copy(
                    position = ordered.first().position,
                    name = display.name,
                    tvgId = keep.tvgId ?: losers.firstNotNullOfOrNull { it.tvgId },
                    logoUrl = keep.logoUrl ?: losers.firstNotNullOfOrNull { it.logoUrl },
                    groupTitle = keep.groupTitle ?: losers.firstNotNullOfOrNull { it.groupTitle },
                    sources = sources,
                    // The prefix is only a grouping namespace. Room keeps the canonical key in
                    // the same form ChannelIdentity.knownKey() has always returned, otherwise a
                    // later import would no longer match this merged row.
                    identityKey = identity.removePrefix(KNOWN_IDENTITY_PREFIX),
                ),
                removeIds = losers.map { it.id },
            )
        }

    /** 存储身份优先；缺失时回落到本地已知身份（`known:<key>`）。两者都不是时返回 null。 */
    fun defaultIdentity(input: ChannelVariantInput): String? {
        val stored = input.identityKey?.takeIf { it.isNotBlank() }
        if (stored != null) {
            return if (HubChannelIdentity.isAuthoritative(stored)) stored
            else KNOWN_IDENTITY_PREFIX + stored.removePrefix(KNOWN_IDENTITY_PREFIX)
        }
        return ChannelIdentity.knownKey(input.tvgId, input.name)?.let { KNOWN_IDENTITY_PREFIX + it }
    }
}

/**
 * Bridges a conservative local identity (for example `cctv-5`) to a hub identity only when the
 * current catalogue contains exactly one authoritative hub row for that known service. Ambiguous
 * or unknown identities are deliberately omitted.
 */
object KnownHubIdentityBridge {
    fun uniqueMappings(inputs: List<ChannelVariantInput>): Map<String, String> = inputs
        .mapNotNull { input ->
            val hub = input.identityKey?.takeIf(HubChannelIdentity::isAuthoritative) ?: return@mapNotNull null
            val rawHubId = hub.removePrefix("hub:")
            val known = ChannelIdentity.knownKey(input.tvgId, input.name)
                ?: ChannelIdentity.knownKey(null, rawHubId)
                ?: return@mapNotNull null
            known to hub
        }
        .groupBy({ it.first }, { it.second })
        .mapNotNull { (known, hubs) -> hubs.distinct().singleOrNull()?.let { known to it } }
        .toMap()
}
