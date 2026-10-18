package com.kantv.app.catalog

import com.kantv.app.playback.OfficialWebChannelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialWebInstallGateTest {
    @Test
    fun unverifiedRegionalPagesAreSupportedButNotInstalled() {
        val rthk31 = OfficialWebChannelCatalog.pages.single { it.tvgId == "official-rthk-31" }

        assertFalse(OfficialWebChannelCatalog.isInstalled(rthk31))
        assertTrue(OfficialWebChannelCatalog.installedPages.none { it.provider.name == "REGIONAL" })
    }

    @Test
    fun retiredCctvSourceIsRemovedWithoutRemovingImportedSource() {
        val candidate = OfficialWebChannelCatalog.retiredOfficialUrls.first { it.endsWith("/cctv1/") }
        val imported = CatalogSource("https://example.invalid/live.m3u8", emptyMap(), official = false)
        val retired = CatalogSource(candidate, emptyMap(), official = true)

        val retained = retainInstallableOfficialSources(
            listOf(retired, imported),
            candidateUrls = OfficialWebChannelCatalog.pages.mapTo(hashSetOf()) { it.url }.apply {
                addAll(OfficialWebChannelCatalog.retiredOfficialUrls)
            },
            installedUrls = OfficialWebChannelCatalog.installedPages.mapTo(hashSetOf()) { it.url },
        )

        assertEquals(listOf(imported), retained)
    }

    @Test
    fun installedChannelsUseYangshipinForCctvSeries() {
        val installed = OfficialWebChannelCatalog.installedPages

        assertEquals(79, installed.size)
        assertTrue(installed.none { it.provider.name == "REGIONAL" })
        // 2026-09-15：TVB 官网页已确认失效并从目录彻底删除。
        assertTrue(installed.none { it.tvgId.startsWith("tvb-web-") })
        assertTrue(installed.none { it.provider.name == "CCTV" })
        assertTrue(installed.any { it.tvgId == "official-ysp-cctv5" })
        assertTrue(installed.any { it.tvgId == "official-ysp-cctv5plus" })
    }
}
