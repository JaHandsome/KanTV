package com.kantv.app.catalog

/** Viewer-facing categories, independent of the platform supplying playback. */
object ChannelDirectory {
    val categories = listOf("央视", "卫视", "财经", "体育频道", "少儿频道", "新闻频道", "广东地方", "港澳台", "国际", "教育", "其他地方", "未分类")

    fun category(name: String, originalGroup: String?): String {
        val normalized = name.uppercase().replace(" ", "")
        return when {
            isSportsChannel(name) -> "体育频道"
            isChildrenChannel(name) -> "少儿频道"
            isNewsChannel(name) -> "新闻频道"
            normalized.startsWith("CGTN") -> "国际"
            normalized.startsWith("CCTV") || name.startsWith("央视") || name.startsWith("中央") -> "央视"
            name.contains("财经") || name.contains("財經") -> "财经"
            name.contains("卫视") || name.contains("衛視") -> "卫视"
            normalized.startsWith("TVB") || name.contains("無綫") || name.contains("无綫") || name.contains("无线新闻") ||
                name.contains("翡翠") || name.contains("凤凰") || name.contains("鳳凰") ||
                originalGroup.orEmpty().contains("港澳台") -> "港澳台"
            name.contains("教育") || normalized.startsWith("CETV") -> "教育"
            name.startsWith("广东") || originalGroup.orEmpty().contains("广东") ||
                name in setOf("岭南戏曲", "GRTN 生活") -> "广东地方"
            originalGroup.orEmpty().contains("国际") -> "国际"
            originalGroup.orEmpty().contains("地方") -> "其他地方"
            else -> "未分类"
        }
    }

    fun category(channel: ChannelEntity): String = category(channel.name, channel.groupTitle)

    fun ordered(channels: List<ChannelEntity>): List<ChannelEntity> = channels.sortedWith(
        compareBy<ChannelEntity> { categories.indexOf(category(it)) }
            .thenBy { cctvOrder(it.name) }
            .thenBy { it.position },
    )

    private fun cctvOrder(name: String): Int {
        val match = Regex("^CCTV[- ]?(\\d+)(\\+)?", RegexOption.IGNORE_CASE).find(name) ?: return Int.MAX_VALUE
        return match.groupValues[1].toInt() * 2 + if (match.groupValues[2] == "+") 1 else 0
    }

    private fun isChildrenChannel(name: String): Boolean =
        name.contains("少儿") || name.contains("少兒") || name.contains("卡通") ||
            Regex("^CCTV[- ]?14(?:台|频道)?$", RegexOption.IGNORE_CASE).matches(name)

    private fun isSportsChannel(name: String): Boolean =
        name.contains("体育") || name.contains("體育") ||
            Regex("^CCTV[- ]?(5\\+?|16)(?:\\D.*)?$", RegexOption.IGNORE_CASE).matches(name)

    private fun isNewsChannel(name: String): Boolean =
        name.contains("新闻") || name.contains("新聞") ||
            Regex("^CCTV[- ]?13(?:台|频道)?$", RegexOption.IGNORE_CASE).matches(name)
}
