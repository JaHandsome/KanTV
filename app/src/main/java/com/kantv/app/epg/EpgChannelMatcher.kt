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
        val prepared = requested.map { channel -> Triple(channel,
            channel.tvgId?.let(::normalise)?.takeIf { it.isNotEmpty() },
            Pair(channel.identityKey ?: ChannelIdentity.knownKey(channel.tvgId, channel.name),
                (channel.aliases + channel.name).mapTo(linkedSetOf(), ::normalise))) }
        return { feed ->
            val names = (feed.displayNames + feed.id).mapTo(linkedSetOf(), ::normalise)
            val identities = (feed.displayNames + feed.id).mapNotNullTo(linkedSetOf()) {
                ChannelIdentity.knownKey(feed.id, it)
            }
            var bestScore = 0
            var best: EpgChannelRef? = null
            var ambiguous = false
            prepared.forEach { (channel, tvgId, info) ->
                val score = when {
                    tvgId != null && tvgId in names -> 300
                    info.first != null && info.first in identities -> 200
                    info.second.any(names::contains) -> 100
                    else -> 0
                }
                if (score > bestScore) { bestScore = score; best = channel; ambiguous = false }
                else if (score > 0 && score == bestScore) ambiguous = true
            }
            if (ambiguous) null else best
        }
    }

    private fun normalise(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace("中央电视台", "cctv")
        .replace("央视", "cctv")
        .replace("紀錄", "纪录")
        .replace("記錄", "纪录")
        .replace("農業", "农业")
        .replace("農村", "农村")
        .replace(Regex("[\\s\\-—_·.（）()【】\\[\\]]"), "")
        .replace(Regex("(频道|頻道|台|高清|超清|蓝光|hd|fhd|uhd|1080p|720p)$"), "")
}
