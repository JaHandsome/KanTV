package com.kantv.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.kantv.app.data.StableChannelId
import org.junit.Test

class ChannelIdentityTest {
    @Test fun cctvAliasesMergeButFivePlusIsIndependent() {
        assertEquals(
            ChannelIdentity.knownKey(null, "CCTV-5 体育"),
            ChannelIdentity.knownKey("unrelated-tvg-id", "央视5台 HD"),
        )
        assertNotEquals(
            ChannelIdentity.knownKey(null, "CCTV-5"),
            ChannelIdentity.knownKey(null, "CCTV-5+ 体育赛事"),
        )
    }

    @Test fun knownSatelliteAliasesMayCrossTvgId() {
        assertEquals(
            ChannelIdentity.knownKey("provider-one", "广东卫视 高清"),
            ChannelIdentity.knownKey("provider-two", "广东卫视"),
        )
    }

    @Test fun distinctFinanceAndTvbServicesNeverMerge() {
        assertNotEquals(ChannelIdentity.knownKey(null, "第一财经"), ChannelIdentity.knownKey(null, "东方财经"))
        assertNotEquals(ChannelIdentity.knownKey(null, "TVB 新闻台"), ChannelIdentity.knownKey(null, "TVB 财经频道"))
    }

    @Test fun jadeAliasesHaveOneConservativeCompatibilityIdentity() {
        assertEquals("tvb-jade", ChannelIdentity.knownKey(null, "翡翠台"))
        assertEquals("tvb-jade", ChannelIdentity.knownKey("provider-jade", "TVB 翡翠台 HD"))
        assertNotEquals(ChannelIdentity.knownKey(null, "翡翠台"), ChannelIdentity.knownKey(null, "无线新闻"))
    }

    @Test fun wirelessNewsAliasesMergeAndUseOneDisplayName() {
        val expected = ChannelIdentity.knownKey(null, "无线新闻")
        assertEquals(expected, ChannelIdentity.knownKey(null, "83 无綫新闻台"))
        assertEquals(expected, ChannelIdentity.knownKey(null, "無綫新聞台"))
        assertEquals("无线新闻", ChannelIdentity.displayName(null, "83 无綫新闻台"))
        assertEquals("无线新闻", ChannelIdentity.displayName("tvb-web-C", "83 无綫新闻台"))
    }

    @Test fun southShoppingIsSuppressedForOfficialAndM3uAliases() {
        assertTrue(ChannelIdentity.isSuppressed(null, "南方购物 HD"))
        assertTrue(ChannelIdentity.isSuppressed("official-gdtv-42", "购物频道"))
        assertTrue(ChannelIdentity.isSuppressed("gdtv-南方购物", "购物频道"))
        assertTrue(ChannelIdentity.isSuppressed(null, "购物频道", "gdtv-南方购物"))
        assertTrue(ChannelIdentity.isSuppressed(null, "南方購物"))
    }

    @Test fun regionalCctvFourHighDefinitionAndOverseasVariantsHaveIndependentKeys() {
        assertNotEquals(ChannelIdentity.knownKey(null, "CCTV-4 中文国际（亚洲）"), ChannelIdentity.knownKey(null, "CCTV-4 中文国际（欧洲）"))
        assertNotEquals(ChannelIdentity.knownKey(null, "CCTV 4K"), ChannelIdentity.knownKey(null, "CCTV 8K"))
        assertNotEquals(ChannelIdentity.knownKey(null, "大湾区卫视（海外版）"), ChannelIdentity.knownKey(null, "大湾区卫视"))
        assertEquals(null, ChannelIdentity.knownKey(null, "凤凰资讯台 1016529"))
    }

    @Test fun sameTvgIdVariantsGetDifferentStorageIdsAndCannotOverwrite() {
        val asia = requireNotNull(ChannelIdentity.storageKey("CCTV4", "CCTV-4 亚洲"))
        val europe = requireNotNull(ChannelIdentity.storageKey("CCTV4", "CCTV-4 欧洲"))
        val fourK = requireNotNull(ChannelIdentity.storageKey("CCTV4", "CCTV 4K"))
        assertNotEquals(asia, europe)
        assertNotEquals(asia, fourK)
        assertNotEquals(
            StableChannelId.create(asia, null, ""),
            StableChannelId.create(europe, null, ""),
        )
        assertEquals("cctv-5-event-unqualified", ChannelIdentity.knownKey("CCTV5", "CCTV-5 体育赛事"))
        assertEquals(
            ChannelIdentity.knownKey(null, "福建东南卫视"),
            ChannelIdentity.knownKey("different", "东南卫视"),
        )
    }

    @Test fun recognizedTvgNameProducesPersistableIdentityForLaterOfficialMerge() {
        val importedIdentity = ChannelIdentity.knownKey("provider-x", "直播", "广东卫视")
        val officialIdentity = ChannelIdentity.knownKey("official-ysp-guangdong", "广东卫视")
        assertEquals("satellite-广东", importedIdentity)
        assertEquals(officialIdentity, importedIdentity)
    }

    @Test fun unknownChannelsWithOneGenericTvgIdGetSeparateStorageIds() {
        val first = requireNotNull(ChannelIdentity.storageKey("generic", "地方新闻甲"))
        val second = requireNotNull(ChannelIdentity.storageKey("generic", "地方新闻乙"))
        assertNotEquals(first, second)
        assertNotEquals(StableChannelId.create(first, null, ""), StableChannelId.create(second, null, ""))
        assertNull(ChannelIdentity.knownKey("generic", "地方新闻甲"))
    }

    @Test fun sharedGenericTvgIdCannotMergeDifferentKnownCctvServices() {
        val reusedId = StableChannelId.create("CCTV", null, "")
        val cctv1 = ChannelIdentity.knownKey("CCTV", "CCTV-1")
        val cctv2 = ChannelIdentity.knownKey("CCTV", "CCTV-2")
        assertNotEquals(cctv1, cctv2)
        assertTrue(!ChannelIdentity.shouldMerge(reusedId, cctv1, reusedId, cctv2))
        assertNotEquals(
            StableChannelId.create(requireNotNull(ChannelIdentity.storageKey("CCTV", "CCTV-1")), null, ""),
            StableChannelId.create(requireNotNull(ChannelIdentity.storageKey("CCTV", "CCTV-2")), null, ""),
        )
    }

    @Test fun unknownSameNameWithDifferentTvgIdsHasNoForcedIdentity() {
        assertNull(ChannelIdentity.knownKey("source-a", "新闻直播"))
        assertNull(ChannelIdentity.knownKey("source-b", "新闻直播"))
    }

    @Test fun sourceDedupUsesCompleteHeadersAndKeepsOfficialFirst() {
        val official = CatalogSource("https://official.example/live", emptyMap(), official = true)
        val same = CatalogSource("https://stream.example/live", mapOf("Referer" to "https://a.example"), official = false)
        val differentCredentials = CatalogSource("https://stream.example/live", mapOf("Referer" to "https://b.example"), official = false)
        val merged = CatalogSourceMerge.merge(listOf(same), listOf(official, same, differentCredentials))

        assertEquals(3, merged.size)
        assertTrue(merged.first().official)
    }

    @Test fun sharedSubscriptionSourceKeepsTheOtherOwnerDuringReplacement() {
        val shared = CatalogSource("https://stream.example/live", mapOf("Referer" to "https://ref.example"), false, setOf("sub-a"))
        val merged = CatalogSourceMerge.merge(listOf(shared), listOf(shared.copy(owners = setOf("sub-b"))))

        assertEquals(setOf("sub-a", "sub-b"), merged.single().owners)
        assertEquals(setOf("sub-b"), CatalogSourceMerge.removeOwner(merged, "sub-a").single().owners)
    }

    @Test fun ownerRefreshCanRotateSourceWithoutDeletingLegacyOrOfficialSources() {
        val oldOwned = CatalogSource("https://stream.example/old", emptyMap(), false, setOf("sub-a"))
        val legacy = CatalogSource("https://stream.example/legacy", emptyMap(), false)
        val official = CatalogSource("https://official.example/live", emptyMap(), true)
        val afterRemoval = CatalogSourceMerge.removeOwner(listOf(oldOwned, legacy, official), "sub-a")
        val afterRefresh = CatalogSourceMerge.merge(
            afterRemoval,
            listOf(CatalogSource("https://stream.example/new", emptyMap(), false, setOf("sub-a"))),
        )

        assertEquals(3, afterRefresh.size)
        assertTrue(afterRefresh.none { it.url == oldOwned.url })
        assertTrue(afterRefresh.any { it.url == legacy.url && it.owners.isEmpty() })
        assertTrue(afterRefresh.any { it.official })
    }

    @Test fun ownerMergeAndRemovalAreIdempotent() {
        val source = CatalogSource("https://stream.example/live", emptyMap(), false, setOf("sub-a"))
        val once = CatalogSourceMerge.merge(emptyList(), listOf(source))
        val twice = CatalogSourceMerge.merge(once, listOf(source))
        assertEquals(once, twice)
        assertEquals(
            CatalogSourceMerge.removeOwner(once, "sub-a"),
            CatalogSourceMerge.removeOwner(twice, "sub-a"),
        )
    }

    @Test fun localAndSubscriptionOwnersCanShareARequestWithoutLocalLoss() {
        val local = CatalogSource("https://stream.example/live", emptyMap(), false, setOf("local-import"))
        val subscription = local.copy(owners = setOf("sub-a"))
        val shared = CatalogSourceMerge.merge(listOf(local), listOf(subscription))

        assertEquals(setOf("local-import", "sub-a"), shared.single().owners)
        assertEquals(setOf("local-import"), CatalogSourceMerge.removeOwner(shared, "sub-a").single().owners)
    }

    @Test fun legacyProtectionSurvivesMergeWithAnOwnedSourceThenOwnerRemoval() {
        val legacy = CatalogSource("https://stream.example/live", emptyMap(), false)
        val owned = legacy.copy(owners = setOf("sub-a"), legacyUnowned = false)
        val merged = CatalogSourceMerge.merge(listOf(legacy), listOf(owned))

        assertTrue(merged.single().legacyUnowned)
        val afterOwnerRefresh = CatalogSourceMerge.removeOwner(merged, "sub-a")
        assertEquals(1, afterOwnerRefresh.size)
        assertTrue(afterOwnerRefresh.single().legacyUnowned)
        assertTrue(afterOwnerRefresh.single().owners.isEmpty())
    }
}
