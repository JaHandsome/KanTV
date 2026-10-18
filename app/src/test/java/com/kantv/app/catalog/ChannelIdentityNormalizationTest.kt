package com.kantv.app.catalog

import com.kantv.app.data.M3uPlaylistImporter
import com.kantv.app.data.ReadyPlaylistImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约 §1.1 的四步别名归一化，以及「不得本地维护第二份别名表」的纪律。
 *
 * 全部输入都来自「服务端下发」的 [ChannelAliasTable]；测试里没有任何本地硬编码别名。
 */
class ChannelIdentityNormalizationTest {
    private val aliases = ChannelAliasTable.from(
        aliases = listOf(
            ChannelAlias("无线新闻", "無線新聞台"),
            ChannelAlias("翡翠台", "翡翠台"),
        ),
        channels = listOf(
            ChannelIdentityRecord("無線新聞台", "无线新闻台"),
            ChannelIdentityRecord("翡翠台", "翡翠台"),
            ChannelIdentityRecord("財經資訊台", "财经资讯台"),
        ),
    )

    @Test fun `step 1 exact alias name resolves`() {
        assertEquals("無線新聞台", ChannelIdentity.resolveChannelId("无线新闻", aliases))
    }

    @Test fun `step 2 exact display name resolves`() {
        assertEquals("無線新聞台", ChannelIdentity.resolveChannelId("无线新闻台", aliases))
        assertEquals("財經資訊台", ChannelIdentity.resolveChannelId("财经资讯台", aliases))
    }

    @Test fun `step 2 exact channel id resolves to itself`() {
        assertEquals("無線新聞台", ChannelIdentity.resolveChannelId("無線新聞台", aliases))
        assertEquals("財經資訊台", ChannelIdentity.resolveChannelId("財經資訊台", aliases))
    }

    @Test fun `step 3 traditional name is converted then resolved`() {
        // 「無線新聞台」本身命中 step 2；换一个只以繁体形式出现、且只在别名/显示名里以简体登记的写法。
        assertEquals("無線新聞台", ChannelIdentity.resolveChannelId("無線新聞", aliases))
        assertEquals("財經資訊台", ChannelIdentity.resolveChannelId("財經資訊臺", aliases))
    }

    @Test fun `step 4 unmatched name returns null`() {
        assertNull(ChannelIdentity.resolveChannelId("不存在的频道", aliases))
    }

    @Test fun `blank or missing name returns null`() {
        assertNull(ChannelIdentity.resolveChannelId(null, aliases))
        assertNull(ChannelIdentity.resolveChannelId("", aliases))
    }

    @Test fun `empty alias table never resolves`() {
        assertNull(ChannelIdentity.resolveChannelId("无线新闻", ChannelAliasTable.EMPTY))
        assertNull(ChannelIdentity.resolveChannelId("翡翠台", ChannelAliasTable.EMPTY))
    }

    @Test fun `no local second alias table exists`() {
        // 翡翠台/无线新闻台 在内置清单与本地代码里都是已知名字；空别名表必须一律返回 null，
        // 证明归因只依赖服务端下发数据。
        assertNull(ChannelIdentity.resolveChannelId("翡翠台", ChannelAliasTable.EMPTY))
        assertNull(ChannelIdentity.resolveChannelId("無線新聞台", ChannelAliasTable.EMPTY))
        assertNull(ChannelIdentity.resolveChannelId("翡翠台", null))
    }

    @Test fun `matching is exact and not fuzzy`() {
        assertNull(ChannelIdentity.resolveChannelId("无线新闻台 ", aliases))
        assertNull(ChannelIdentity.resolveChannelId("无线新闻", ChannelAliasTable.from(
            listOf(ChannelAlias("無線新聞", "無線新聞台")),
            emptyList(),
        )))
    }

    @Test fun `ambiguous alias name is rejected instead of guessed`() {
        val ambiguous = ChannelAliasTable.from(
            aliases = listOf(
                ChannelAlias("新闻", "無線新聞台"),
                ChannelAlias("新闻", "有線新聞台"),
            ),
            channels = emptyList(),
        )
        assertNull(ChannelIdentity.resolveChannelId("新闻", ambiguous))
        assertEquals(0, ambiguous.aliasCount)
    }

    @Test fun `ambiguous display name is rejected instead of guessed`() {
        val ambiguous = ChannelAliasTable.from(
            aliases = emptyList(),
            channels = listOf(
                ChannelIdentityRecord("A", "新闻台"),
                ChannelIdentityRecord("B", "新闻台"),
            ),
        )
        assertNull(ChannelIdentity.resolveChannelId("新闻台", ambiguous))
        // channel_id 自身仍然可用
        assertEquals("A", ChannelIdentity.resolveChannelId("A", ambiguous))
    }

    @Test fun `alias table survives cache round trip`() {
        val persisted = InMemoryChannelAliasPersistence()
        val cache = ChannelAliasCache(persisted)
        cache.replace(aliases)
        val reloaded = ChannelAliasCache(persisted).current()
        assertEquals("無線新聞台", reloaded.channelIdForAlias("无线新闻"))
        assertEquals("財經資訊台", reloaded.channelIdForIdentity("财经资讯台"))
    }

    @Test fun `alias table from server payload feeds the cache`() {
        val payload = HubSubscriptionParser.parse(serverPayload()) as HubParseOutcome.Parsed
        val cache = ChannelAliasCache(InMemoryChannelAliasPersistence())
        cache.replace(payload.subscription.aliasTable())
        assertEquals("無線新聞台", ChannelIdentity.resolveChannelId("无线新闻", cache.current()))
    }

    @Test fun `m3u import is attributed to the server channel id when aliases match`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="tvbnews" group-title="港澳台",无线新闻
            http://example.invalid/live/news.m3u8
        """.trimIndent()
        val preparation = M3uPlaylistImporter { aliases }.prepare(playlist) as ReadyPlaylistImport
        val channel = preparation.snapshot.channels.single()
        assertEquals("無線新聞台", channel.tvgId)
        assertEquals(HubChannelIdentity.internalId("無線新聞台"), channel.id)
    }

    @Test fun `m3u import without alias table keeps the historical stable id`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="tvbnews" group-title="港澳台",无线新闻
            http://example.invalid/live/news.m3u8
        """.trimIndent()
        val withAliases = (M3uPlaylistImporter { aliases }.prepare(playlist) as ReadyPlaylistImport)
            .snapshot.channels.single()
        val withoutAliases = (M3uPlaylistImporter().prepare(playlist) as ReadyPlaylistImport)
            .snapshot.channels.single()
        assertNotEquals(withAliases.id, withoutAliases.id)
        assertEquals("tvbnews", withoutAliases.tvgId)
    }

    @Test fun `traditional to simplified covers channel vocabulary without touching unknown characters`() {
        assertEquals("无线新闻台", TraditionalToSimplified.convert("無線新聞台"))
        assertEquals("财经资讯台", TraditionalToSimplified.convert("財經資訊臺"))
        assertEquals("翡翠台", TraditionalToSimplified.convert("翡翠台"))
        assertTrue(TraditionalToSimplified.mappingSize() > 100)
        // 未收录字符原样保留（不做完整 t2s）
        assertEquals("ABC", TraditionalToSimplified.convert("ABC"))
    }

    private fun serverPayload(): String = """
        {
          "hub_schema": 1,
          "seq": 1,
          "channels": [
            { "channel_id": "無線新聞台", "display": "无线新闻台", "group_id": "hk_tw", "coverage": "ok", "routes": [] }
          ],
          "aliases": [ { "name": "无线新闻", "channel_id": "無線新聞台" } ]
        }
    """.trimIndent()
}
