package com.kantv.app.epg

import com.kantv.app.catalog.ChannelIdentity
import java.util.Locale

internal data class XmlTvChannel(
    val id: String,
    val displayNames: Set<String>,
)

internal object EpgChannelMatcher {
    fun match(requested: List<EpgChannelRef>, feedChannels: List<XmlTvChannel>): Map<String, EpgChannelRef> {
        val choose = prepare(requested)
        val used = mutableSetOf<String>()
        return buildMap {
            feedChannels.forEach { feed ->
                choose(feed)?.takeIf { used.add(it.channelId) }?.let { put(feed.id, it) }
            }
        }
    }

    fun prepare(requested: List<EpgChannelRef>): (XmlTvChannel) -> EpgChannelRef? {
        val index = Index.build(requested)
        return { feed ->
            val labels = feed.displayNames + feed.id
            val names = labels.mapTo(HashSet(labels.size), ::normalise)
            val identities = labels.mapNotNullTo(HashSet(labels.size)) {
                ChannelIdentity.knownKey(feed.id, it)
            }
            index.choose(names, identities)
        }
    }

    private class Index(
        private val channels: List<EpgChannelRef>,
        private val byTvg: Map<String, IntArray>,
        private val byIdentity: Map<String, IntArray>,
        private val byName: Map<String, IntArray>,
    ) {
        fun choose(names: Set<String>, identities: Set<String>): EpgChannelRef? {
            var bestScore = 0
            var winner = -1
            var ambiguous = false
            fun consider(score: Int, hits: IntArray?) {
                if (hits == null || hits.isEmpty() || score < bestScore) return
                if (score > bestScore) {
                    bestScore = score
                    winner = -1
                    ambiguous = false
                }
                for (index in hits) {
                    if (winner < 0) winner = index
                    else if (winner != index) ambiguous = true
                }
            }
            for (name in names) {
                consider(300, byTvg[name])
                consider(100, byName[name])
            }
            for (identity in identities) consider(200, byIdentity[identity])
            return if (ambiguous || winner < 0) null else channels[winner]
        }

        companion object {
            fun build(requested: List<EpgChannelRef>): Index {
                val byTvg = HashMap<String, MutableList<Int>>()
                val byIdentity = HashMap<String, MutableList<Int>>()
                val byName = HashMap<String, MutableList<Int>>()
                requested.forEachIndexed { index, channel ->
                    channel.tvgId?.let(::normalise)?.takeIf { it.isNotEmpty() }?.let { key ->
                        byTvg.getOrPut(key) { mutableListOf() }.add(index)
                    }
                    val identity = channel.identityKey ?: ChannelIdentity.knownKey(channel.tvgId, channel.name)
                    if (identity != null) byIdentity.getOrPut(identity) { mutableListOf() }.add(index)
                    (channel.aliases + channel.name).forEach { alias ->
                        byName.getOrPut(normalise(alias)) { mutableListOf() }.add(index)
                    }
                }
                return Index(
                    requested,
                    byTvg.toIntArrays(),
                    byIdentity.toIntArrays(),
                    byName.toIntArrays(),
                )
            }

            private fun Map<String, MutableList<Int>>.toIntArrays(): Map<String, IntArray> =
                mapValues { (_, hits) -> hits.toIntArray() }
        }
    }

    private val punctuation = Regex("[\\s\\-—_·.（）()【】\\[\\]]")
    private val trailingQualifier = Regex("(频道|頻道|台|高清|超清|蓝光|hd|fhd|uhd|1080p|720p)$")

    private fun normalise(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace("中央电视台", "cctv")
        .replace("央视", "cctv")
        .replace("紀錄", "纪录")
        .replace("記錄", "纪录")
        .replace("農業", "农业")
        .replace("農村", "农村")
        .replace(punctuation, "")
        .replace(trailingQualifier, "")
}
