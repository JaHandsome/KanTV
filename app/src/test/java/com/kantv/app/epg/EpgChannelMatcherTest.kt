package com.kantv.app.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgChannelMatcherTest {
    @Test fun tvgIdOutranksAnotherChannelsDisplayName() {
        val byName = EpgChannelRef("name", "other", "Feed-1")
        val exact = EpgChannelRef("exact", "Feed-1", "其他")
        val chosen = EpgChannelMatcher.prepare(listOf(byName, exact))(
            XmlTvChannel("Feed-1", setOf("Feed-1")),
        )
        assertEquals("exact", chosen?.channelId)
    }

    @Test fun equalTopScoresAreAmbiguous() {
        val first = EpgChannelRef("a", "CCTV-1", "甲")
        val second = EpgChannelRef("b", "CCTV-1", "乙")
        val chosen = EpgChannelMatcher.prepare(listOf(first, second))(
            XmlTvChannel("x", setOf("CCTV-1")),
        )
        assertNull(chosen)
    }

    @Test fun aRepeatedRequestedChannelIsAmbiguous() {
        val channel = EpgChannelRef("a", "CCTV-1", "综合")
        val chosen = EpgChannelMatcher.prepare(listOf(channel, channel.copy()))(
            XmlTvChannel("CCTV-1", emptySet()),
        )
        assertNull(chosen)
    }

    @Test fun severalAliasesOfOneChannelAreNotAmbiguous() {
        val channel = EpgChannelRef("a", null, "新闻", aliases = setOf("晚间新闻", "新闻"))
        val chosen = EpgChannelMatcher.prepare(listOf(channel))(
            XmlTvChannel("id", setOf("晚间新闻", "新闻")),
        )
        assertEquals("a", chosen?.channelId)
    }

    @Test fun identityMatchSurvivesAProviderSpecificTvgId() {
        val channel = EpgChannelRef("a", "provider-specific", "CCTV-1 综合", identityKey = "cctv-1")
        val chosen = EpgChannelMatcher.prepare(listOf(channel))(
            XmlTvChannel("中央电视台1", setOf("新闻联播频道")),
        )
        assertEquals("a", chosen?.channelId)
    }

    @Test fun tiedWeakerNamesLoseToASingleStrongerTvgId() {
        val weakA = EpgChannelRef("w1", "no", "共享", identityKey = null)
        val weakB = EpgChannelRef("w2", "nope", "共享", identityKey = null)
        val strong = EpgChannelRef("s", "共享", "其他", identityKey = "unique-s")
        val chosen = EpgChannelMatcher.prepare(listOf(weakA, weakB, strong))(
            XmlTvChannel("x", setOf("共享")),
        )
        assertEquals("s", chosen?.channelId)
    }

    @Test fun broadcasterAliasesNormaliseBeforeScoring() {
        val channel = EpgChannelRef("cctv9", "CCTV-9 纪录", "央视纪录")
        val chosen = EpgChannelMatcher.prepare(listOf(channel))(
            XmlTvChannel("feed", setOf("中央电视台-9 紀錄 高清")),
        )
        assertEquals("cctv9", chosen?.channelId)
    }

    @Test fun eachFeedKeepsTheFirstRequestedChannelOnly() {
        val channel = EpgChannelRef("c", "Same", "Same")
        val matched = EpgChannelMatcher.match(
            listOf(channel),
            listOf(XmlTvChannel("first", setOf("Same")), XmlTvChannel("second", setOf("Same"))),
        )
        assertEquals(setOf("first"), matched.keys)
        assertEquals("c", matched.getValue("first").channelId)
    }

    @Test fun distinctFeedsMatchDistinctChannels() {
        val requested = List(400) { index -> EpgChannelRef("c$index", "id-$index", "名$index") }
        val feeds = List(400) { index -> XmlTvChannel("id-$index", setOf("别$index")) }
        val matched = EpgChannelMatcher.match(requested, feeds)
        assertEquals(400, matched.size)
        assertEquals("c17", matched.getValue("id-17").channelId)
    }
}
