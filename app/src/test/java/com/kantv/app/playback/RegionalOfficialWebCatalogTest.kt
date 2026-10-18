package com.kantv.app.playback

import org.junit.Assert.*
import org.junit.Test

class RegionalOfficialWebCatalogTest {
    @Test fun regionPagesRoundTripWithoutChangingChannel() {
        RegionalOfficialWebCatalog.pages.forEach {
            assertEquals(it, SupportedWebLivePage.from(it.url))
            assertEquals(OfficialWebProvider.REGIONAL, it.provider)
            assertTrue(it.officialPlayer)
        }
        assertEquals(3, RegionalOfficialWebCatalog.pages.map { it.groupTitle }.distinct().size)
    }

    @Test fun unsupportedChannelVariantsCannotFallBackToADifferentChannel() {
        listOf(
            "https://www.rthk.hk/tv#kantv-99", "https://www.rthk.hk/tv",
            "https://hoy.tv/live?channel_no=999", "https://hoy.tv/live?channel_no=705&channel_no=999",
            "https://news.cts.com.tw.evil.test/live", "https://news.cts.com.tw:444/live",
            "https://news.cts.com.tw@evil.test/live", "http://news.cts.com.tw/live",
        ).forEach { assertNull(it, SupportedWebLivePage.from(it)) }
    }

    @Test fun youtubeResearchEntriesAreNotImportedAsDirectNetworkChannels() {
        RegionalOfficialWebCatalog.youtubeResearchPages.forEach {
            assertFalse(OfficialWebChannelCatalog.containsTvgId(it.tvgId))
            assertNull(SupportedWebLivePage.from(it.url))
        }
    }

    @Test fun macauChannelQueryIsPartOfTheIdentity() {
        val base = "https://www.tdm.com.mo/zh-hant/live"
        assertEquals("澳视澳门", SupportedWebLivePage.from("$base?Channel=1&type=tv")?.name)
        assertEquals("澳门体育", SupportedWebLivePage.from("$base?Channel=6&type=tv")?.name)
        assertNull(SupportedWebLivePage.from("$base?Channel=99&type=tv"))
        assertNull(SupportedWebLivePage.from("$base?Channel=1&Channel=6&type=tv"))
    }
}
