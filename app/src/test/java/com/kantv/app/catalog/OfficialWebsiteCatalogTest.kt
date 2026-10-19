package com.kantv.app.catalog

import com.kantv.app.playback.OfficialWebChannelCatalog
import com.kantv.app.playback.OfficialWebProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialWebsiteCatalogTest {
    private val pages = OfficialWebChannelCatalog.installedPages
    private val cctv1 = pages.single { it.tvgId == "official-ysp-cctv1" }

    @Test fun siteDirectoriesOnlyExposeInstalledPages() {
        assertEquals(79, OfficialWebsiteCatalog.sites.sumOf { it.pages.size })
        OfficialWebsiteCatalog.sites.forEach { site ->
            assertEquals(site, (OfficialWebsiteCatalog.resolve(site.homeUrl) as OfficialWebsiteCatalog.Address.Directory).site)
            assertTrue(site.pages.all { it in pages && it.provider != OfficialWebProvider.REGIONAL })
        }
        assertTrue(OfficialWebsiteCatalog.resolve("https://www.yangshipin.cn/tv/home") is OfficialWebsiteCatalog.Address.Directory)
    }

    @Test fun ordinaryPidLinkBecomesCanonicalPageWithoutChangingPlaybackParser() {
        val result = OfficialWebsiteCatalog.resolve("https://www.yangshipin.cn/tv/home?pid=600001859&from=share")
        assertEquals(cctv1, (result as OfficialWebsiteCatalog.Address.Channel).page)
        assertTrue(result.page.url.endsWith("#kantv-cctv1"))
        assertEquals(null, com.kantv.app.playback.SupportedWebLivePage.from("https://www.yangshipin.cn/tv/home?pid=600001859"))
    }

    @Test fun everyInstalledCanonicalAddressStillImportsItsExactIdentity() {
        pages.forEach { page ->
            assertEquals(page, (OfficialWebsiteCatalog.resolve(page.url) as OfficialWebsiteCatalog.Address.Channel).page)
        }
        assertEquals(cctv1, (OfficialWebsiteCatalog.resolve("https://yangshipin.cn:443/tv/home/?pid=600001859") as OfficialWebsiteCatalog.Address.Channel).page)
    }

    @Test fun rejectsUnknownOrConflictingChannelIdentityInsteadOfDownloadingPlaylist() {
        listOf(
            "https://www.yangshipin.cn/tv/home?pid=unknown",
            "https://www.yangshipin.cn/tv/home?pid=600001859#kantv-cctv2",
            "https://www.yangshipin.cn/tv/home?pid=600001859&pid=600001859",
            "https://www.yangshipin.cn/tv/home?pid=600001859&%70id=600001800",
            "https://www.yangshipin.cn/tv/home?pid=600001859#unknown",
            "https://www.yangshipin.cn/tv/home?vid=unknown",
            "https://www.yangshipin.cn/tv/home?pid=600001859&vid=other",
            "https://www.yangshipin.cn/tv/home#kantv-vip",
            "https://www.yangshipin.cn/vip",
            "https://www.gdtv.cn/tvChannelDetail/999999",
            "https://www.gdtv.cn/tvChannelDetail/43#other",
            "https://www.gdtv.cn/tvChannelDetail/43?id=44",
            "https://www.yicai.com/tv#unknown",
            "https://www.yicai.com/tv?pid=600001859",
            "https://www.rthk.hk/tv#kantv-31",
        ).forEach { assertTrue(it, OfficialWebsiteCatalog.resolve(it) is OfficialWebsiteCatalog.Address.Rejected) }
    }

    @Test fun exactHttpsHostAndSafeAuthorityAreRequired() {
        listOf(
            "http://www.yangshipin.cn/tv/home?pid=600001859",
            "https://user@www.yangshipin.cn/tv/home?pid=600001859",
            "https://www.yangshipin.cn:8443/tv/home?pid=600001859",
            "https://www.yangshipin.cn/%74v/home?pid=600001859",
        ).forEach { assertTrue(it, OfficialWebsiteCatalog.resolve(it) is OfficialWebsiteCatalog.Address.Rejected) }
        listOf(
            "https://www.yangshipin.cn.evil.invalid/tv/home?pid=600001859",
            "https://evil.invalid/?url=https://www.yangshipin.cn/",
            "https://example.invalid/playlist.m3u",
        ).forEach { assertEquals(OfficialWebsiteCatalog.Address.Other, OfficialWebsiteCatalog.resolve(it)) }
    }

    @Test fun selectionIsCanonicalDeduplicatedAndRejectsEntireInvalidBatch() {
        assertEquals(listOf(cctv1), OfficialWebsiteCatalog.canonicalSelection(listOf(cctv1, cctv1)))
        val regional = OfficialWebChannelCatalog.pages.first { it.provider == OfficialWebProvider.REGIONAL }
        listOf(emptyList(), listOf(cctv1, regional), listOf(cctv1.copy(url = "https://example.invalid/"))).forEach { selection ->
            assertTrue(runCatching { OfficialWebsiteCatalog.canonicalSelection(selection) }.isFailure)
        }
    }

    @Test fun unknownWebsiteHtmlIsNotAcceptedAsAPlaylist() {
        assertEquals(OfficialWebsiteCatalog.Address.Other, OfficialWebsiteCatalog.resolve("https://example.invalid/watch"))
        assertTrue(
            com.kantv.app.data.M3uPlaylistImporter().prepare("<!doctype html><html><body>Live TV</body></html>")
                is com.kantv.app.data.RejectedPlaylistImport,
        )
    }
}
