package com.kantv.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelVariantMergerTest {
    private fun input(
        id: String,
        position: Int,
        name: String,
        url: String,
        tvgId: String? = null,
        owners: Set<String> = emptySet(),
        identityKey: String? = null,
        official: Boolean = false,
    ) = ChannelVariantInput(
        id = id,
        position = position,
        name = name,
        tvgId = tvgId,
        logoUrl = null,
        groupTitle = null,
        sources = listOf(
            CatalogSource(url = url, headers = emptyMap(), official = official, owners = owners),
        ),
        identityKey = identityKey,
    )

    @Test fun `the same effective identity merges keeping earliest position and shortest name`() {
        // Both rows resolve to the same effective identity through ChannelIdentity.knownKey.
        val merges = ChannelVariantMerger.plan(
            listOf(
                input("hub-cctv1", 0, "CCTV1综合", "http://a/1.m3u8", tvgId = "CCTV1", owners = setOf("hub")),
                input("m3u-cctv1", 40, "CCTV-1", "http://b/1.m3u8", owners = setOf("sub-1")),
            ),
        )
        assertEquals(1, merges.size)
        val merge = merges.single()
        assertEquals("hub-cctv1", merge.keep.id)
        assertEquals("CCTV-1", merge.keep.name)
        assertEquals(listOf("m3u-cctv1"), merge.removeIds)
        assertEquals(2, merge.keep.sources.size)
        assertEquals("cctv-1", merge.keep.identityKey)
    }

    @Test fun `distinct channels are never merged`() {
        val merges = ChannelVariantMerger.plan(
            listOf(
                input("cctv5", 0, "CCTV5", "http://a/5.m3u8"),
                input("cctv5plus", 1, "CCTV5+", "http://a/5p.m3u8"),
            ),
        )
        assertTrue(merges.isEmpty())
    }

    @Test fun `sources from both sides are unioned with owners preserved`() {
        val merges = ChannelVariantMerger.plan(
            listOf(
                input("a", 0, "翡翠台", "http://a/jade.m3u8", owners = setOf("hub")),
                input("b", 9, "高清翡翠台", "http://b/jade-hd.m3u8", owners = setOf("sub-9")),
            ),
        )
        val keep = merges.single().keep
        assertEquals(2, keep.sources.size)
        assertEquals(setOf("hub"), keep.sources.first { it.url == "http://a/jade.m3u8" }.owners)
        assertEquals(setOf("sub-9"), keep.sources.first { it.url == "http://b/jade-hd.m3u8" }.owners)
    }

    @Test fun `unattributable short names stay untouched`() {
        val merges = ChannelVariantMerger.plan(
            listOf(
                input("x", 0, "·", "http://a/x.m3u8"),
                input("y", 1, "·", "http://b/y.m3u8"),
            ),
        )
        assertTrue(merges.isEmpty())
    }

    @Test fun `same hub identity converges even when the display names differ`() {
        val merges = ChannelVariantMerger.plan(
            listOf(
                input(
                    "hub-a", 0, "CCTV-1 综合", "http://a/1.m3u8",
                    identityKey = "hub:cctv1", owners = setOf("hub"),
                ),
                input(
                    "hub-b", 40, "CCTV1综合频道", "http://b/1.m3u8",
                    identityKey = "hub:cctv1", owners = setOf("hub"),
                ),
            ),
        )
        assertEquals(1, merges.size)
        val merge = merges.single()
        assertEquals("hub-a", merge.keep.id)
        assertEquals("hub:cctv1", merge.keep.identityKey)
        assertEquals(listOf("hub-b"), merge.removeIds)
        assertEquals(2, merge.keep.sources.size)
    }

    @Test fun `stored and derived known identities converge without changing storage format`() {
        val merge = ChannelVariantMerger.plan(
            listOf(
                input("official", 0, "CCTV-5 体育", "http://a/official", identityKey = "cctv-5"),
                input("legacy", 20, "CCTV5", "http://b/legacy"),
            ),
        ).single()

        assertEquals("cctv-5", merge.keep.identityKey)
        assertEquals(2, merge.keep.sources.size)
    }

    @Test fun `different hub identities never merge even with the same display name`() {
        val merges = ChannelVariantMerger.plan(
            listOf(
                input("hub-a", 0, "CCTV5", "http://a/5.m3u8", identityKey = "hub:cctv5"),
                input("hub-b", 1, "CCTV5", "http://b/5.m3u8", identityKey = "hub:cctv5plus"),
            ),
        )
        assertTrue(merges.isEmpty())
    }

    @Test fun `same name with different tvg ids stays separate`() {
        val merges = ChannelVariantMerger.plan(
            listOf(
                input("a", 0, "新闻", "http://a/news.m3u8", tvgId = "NEWS-A"),
                input("b", 1, "新闻", "http://b/news.m3u8", tvgId = "NEWS-B"),
            ),
        )
        assertTrue(merges.isEmpty())
    }

    @Test fun `a hub identity and a legacy row with the same name stay separate`() {
        // `hub:cctv5` and `known:cctv-5` are different identities, so the rows must not converge on
        // display-name similarity. They may only converge through the explicit alias table.
        val merges = ChannelVariantMerger.plan(
            listOf(
                input("hub-a", 0, "CCTV5", "http://a/5.m3u8", identityKey = "hub:cctv5"),
                input("legacy", 20, "CCTV-5", "http://b/5.m3u8"),
            ),
        )
        assertTrue(merges.isEmpty())
    }

    @Test fun `the explicit alias table is the only way a hub row absorbs a legacy duplicate`() {
        // Server-declared alias: "CCTV-5" belongs to server channel_id "cctv5".
        val aliases = ChannelAliasTable.from(
            aliases = listOf(ChannelAlias(name = "CCTV-5", channelId = "cctv5")),
            channels = emptyList(),
        )
        val resolve = { value: ChannelVariantInput ->
            value.identityKey?.takeIf { HubChannelIdentity.isAuthoritative(it) }
                ?: ChannelIdentity.resolveChannelId(value.name, aliases)
                    ?.let(HubChannelIdentity::identityKey)
                ?: ChannelVariantMerger.defaultIdentity(value)
        }
        val merges = ChannelVariantMerger.plan(
            listOf(
                input("hub-a", 0, "CCTV5", "http://a/5.m3u8", identityKey = "hub:cctv5"),
                input("legacy", 20, "CCTV-5", "http://b/5.m3u8"),
            ),
            identityOf = resolve,
        )

        assertEquals(1, merges.size)
        val merge = merges.single()
        assertEquals("hub-a", merge.keep.id)
        assertEquals("hub:cctv5", merge.keep.identityKey)
        assertEquals(listOf("legacy"), merge.removeIds)
        assertEquals(2, merge.keep.sources.size)
    }

    @Test fun `a later authoritative hub row keeps its route id but inherits the earliest position`() {
        val aliases = ChannelAliasTable.from(
            aliases = listOf(ChannelAlias(name = "CCTV-5", channelId = "cctv5")),
            channels = emptyList(),
        )
        val resolve = { value: ChannelVariantInput ->
            value.identityKey?.takeIf { HubChannelIdentity.isAuthoritative(it) }
                ?: ChannelIdentity.resolveChannelId(value.name, aliases)
                    ?.let(HubChannelIdentity::identityKey)
                ?: ChannelVariantMerger.defaultIdentity(value)
        }

        val merge = ChannelVariantMerger.plan(
            listOf(
                input("legacy", 3, "CCTV-5", "http://a/legacy"),
                input("hub-later", 30, "CCTV5", "http://b/hub", identityKey = "hub:cctv5"),
            ),
            identityOf = resolve,
        ).single()

        assertEquals("hub-later", merge.keep.id)
        assertEquals(3, merge.keep.position)
        assertEquals("hub:cctv5", merge.keep.identityKey)
        assertEquals(listOf("legacy"), merge.removeIds)
        assertEquals(2, merge.keep.sources.size)
    }

    @Test fun `a unique conservative known identity can bridge an old official row to one hub row`() {
        val inputs = listOf(
            input("official", 1, "CCTV-5 体育", "http://a/official", identityKey = "cctv-5", official = true),
            input("hub", 30, "CCTV5", "http://b/hub", identityKey = "hub:CCTV5"),
            input("plus", 31, "CCTV5+", "http://c/plus", identityKey = "hub:CCTV5+"),
        )
        val bridge = KnownHubIdentityBridge.uniqueMappings(inputs)
        val merges = ChannelVariantMerger.plan(inputs) { value ->
            value.identityKey?.takeIf(HubChannelIdentity::isAuthoritative)
                ?: ChannelIdentity.knownKey(value.tvgId, value.name)?.let(bridge::get)
                ?: ChannelVariantMerger.defaultIdentity(value)
        }

        val merge = merges.single()
        assertEquals("hub", merge.keep.id)
        assertEquals(1, merge.keep.position)
        assertEquals("hub:CCTV5", merge.keep.identityKey)
        assertEquals(listOf("official"), merge.removeIds)
        assertEquals(2, merge.keep.sources.size)
    }

    @Test fun `an ambiguous known identity never bridges two hub channels`() {
        val inputs = listOf(
            input("official", 1, "CCTV5", "http://a/official", identityKey = "cctv-5"),
            input("hub-a", 2, "CCTV5", "http://b/a", identityKey = "hub:CCTV5"),
            input("hub-b", 3, "CCTV-5 体育", "http://c/b", identityKey = "hub:alternate-cctv5"),
        )

        assertTrue(KnownHubIdentityBridge.uniqueMappings(inputs).isEmpty())
    }

    @Test fun `identity-less unknown rows never merge by name`() {
        val merges = ChannelVariantMerger.plan(
            listOf(
                input("a", 0, "某某频道", "http://a/x.m3u8"),
                input("b", 1, "某某频道", "http://b/x.m3u8"),
            ),
        )
        assertTrue("unknown identity must not merge on name similarity", merges.isEmpty())
    }

    @Test fun `cctv five plus never merges into cctv five`() {
        assertTrue(
            ChannelVariantMerger.plan(
                listOf(
                    input("a", 0, "CCTV5", "http://a/5.m3u8"),
                    input("b", 1, "CCTV5+", "http://a/5p.m3u8"),
                ),
            ).isEmpty(),
        )
        assertTrue(
            ChannelVariantMerger.plan(
                listOf(
                    input("a", 0, "CCTV-5", "http://a/5.m3u8"),
                    input("b", 1, "CCTV-5+ 体育赛事", "http://a/5p.m3u8"),
                ),
            ).isEmpty(),
        )
    }

    @Test fun `cctv four regional and uhd variants stay separate from each other`() {
        val names = listOf("CCTV4", "CCTV4K", "CCTV-4 亚洲", "CCTV-4 欧洲", "CCTV-4 美洲")
        val merges = ChannelVariantMerger.plan(
            names.mapIndexed { index, name ->
                input("c$index", index, name, "http://a/$index.m3u8")
            },
        )
        assertTrue("4K and regional variants are distinct channels", merges.isEmpty())
    }

    @Test fun `eight k stays separate from its base channel`() {
        val merges = ChannelVariantMerger.plan(
            listOf(
                input("a", 0, "CCTV8", "http://a/8.m3u8"),
                input("b", 1, "CCTV-8K", "http://a/8k.m3u8"),
            ),
        )
        assertTrue(merges.isEmpty())
    }

    @Test fun `ordinary decoration and hd variants still merge`() {
        val merges = ChannelVariantMerger.plan(
            listOf(
                input("a", 0, "CCTV-1 综合", "http://a/1.m3u8"),
                input("b", 5, "CCTV1综合频道", "http://b/1.m3u8"),
                input("c", 9, "CCTV1 高清", "http://c/1.m3u8"),
            ),
        )
        assertEquals(1, merges.size)
        val merge = merges.single()
        assertEquals("a", merge.keep.id)
        assertEquals(setOf("b", "c"), merge.removeIds.toSet())
        assertEquals(3, merge.keep.sources.size)
    }

    @Test fun `merge preserves every source and the earliest position, then official is ordered first`() {
        val merges = ChannelVariantMerger.plan(
            listOf(
                input("a", 0, "CCTV-1 综合", "http://a/1.m3u8", official = false),
                input("b", 5, "CCTV1综合频道", "http://b/official.m3u8", official = true),
            ),
        )
        val keep = merges.single().keep
        // plan() preserves the union and the earliest position; the catalogue applies the order.
        assertEquals(0, keep.position)
        assertEquals(setOf("http://a/1.m3u8", "http://b/official.m3u8"), keep.sources.map { it.url }.toSet())
        val ordered = CatalogSourceOrdering.stable(keep.sources)
        assertEquals("http://b/official.m3u8", ordered.first().url)
        assertEquals(2, ordered.size)
    }
}
