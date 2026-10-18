package com.kantv.app.playback

import java.net.URI

/** Public broadcaster pages, never resolved media URLs or authentication parameters. */
object RegionalOfficialWebCatalog {
    private fun page(
        id: String, name: String, url: String, group: String,
        player: String = "video", video: String = "video", selection: String? = null,
    ) = SupportedWebLivePage(
        tvgId = "official-$id", name = name, url = url, officialPlayer = true,
        groupTitle = group, provider = OfficialWebProvider.REGIONAL,
        playerSelector = player, videoSelector = video, channelSelector = selection,
    )

    val pages = buildList {
        (31..35).forEach { channel ->
            add(page("rthk-$channel", "港台电视 $channel", "https://www.rthk.hk/tv#kantv-$channel",
                "港澳台·香港官网", "#tvplayer$channel-container", "#tvplayer$channel-container video",
                ".callPlayer[data-channel=\"$channel\"]"))
        }
        add(page("now-332", "Now 新闻台", "https://news.now.com/home/live", "港澳台·香港官网",
            "#liveVideo", "#liveVideo video, video#liveVideo"))
        listOf(1 to "澳视澳门", 2 to "澳视葡文", 5 to "澳门资讯", 6 to "澳门体育",
            7 to "澳门综艺", 8 to "澳门-Macau").forEach { (id, name) ->
            add(page("tdm-$id", name, "https://www.tdm.com.mo/zh-hant/live?Channel=$id&type=tv", "港澳台·澳门官网"))
        }
        add(page("ebc-news", "东森新闻", "https://news.ebc.net.tw/video", "港澳台·台湾官网", "iframe#live_player"))
    }

    /** These require YouTube access; do not import them into the direct-network catalogue. */
    val youtubeResearchPages = buildList {
        add(page("cts-news", "华视新闻", "https://news.cts.com.tw/live", "港澳台·台湾官网",
            "iframe[src*=\"youtube.com/embed/\"]"))
        add(page("pts-news", "公视新闻", "https://news.pts.org.tw/", "港澳台·台湾官网",
            "iframe[src*=\"youtube.com/embed/\"]"))
        add(page("cti-news", "中天新闻", "https://www.ctitv.com.tw/", "港澳台·台湾官网",
            "iframe[src*=\"youtube.com/embed/\"]"))
        add(page("tvbs-news", "TVBS 新闻", "https://news.tvbs.com.tw/playlists", "港澳台·台湾官网",
            "iframe[src*=\"youtube.com/embed/\"]"))
    }

    fun from(uri: URI): SupportedWebLivePage? {
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in setOf(-1, 443)) return null
        return pages.firstOrNull { page ->
            val expected = URI(page.url)
            uri.host.equals(expected.host, ignoreCase = true) &&
                uri.path.trimEnd('/') == expected.path.trimEnd('/') &&
                uri.fragment == expected.fragment &&
                // Channel query parameters are part of the identity; reject ambiguous duplicates.
                (expected.rawQuery == null || uri.rawQuery == expected.rawQuery)
        }
    }
}
