package com.kantv.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约 §3 响应体解析：容忍未知字段、跳过无法归因的条目、不依据 url 推断 route。
 * 使用自造联调样例 `app/src/test/resources/hub_v1_fixture.json`。
 */
class HubSubscriptionParserTest {
    private val subscription: HubSubscription by lazy {
        val outcome = HubSubscriptionParser.parse(fixture())
        assertTrue("fixture 必须能解析", outcome is HubParseOutcome.Parsed)
        (outcome as HubParseOutcome.Parsed).subscription
    }

    @Test fun `unknown fields are ignored everywhere`() {
        assertEquals(2, subscription.hubSchema)
        assertEquals(12345L, subscription.seq)
        assertEquals("1.3.0", subscription.server.app)
        assertEquals(2, subscription.groups.size)
    }

    @Test fun `channel without channel id is skipped`() {
        assertEquals(4, subscription.channels.size)
        assertFalse(subscription.channels.any { it.display.contains("没有 channel_id") })
    }

    @Test fun `routes keep the server score order`() {
        val news = subscription.channels.first { it.channelId == "無線新聞台" }
        assertEquals(listOf(92, 70), news.routes.map { it.score })
        assertEquals(HubRouteType.DASH_CLEARKEY, news.routes.first().route)
    }

    @Test fun `inline clearkey is parsed`() {
        val route = subscription.channels.first { it.channelId == "無線新聞台" }.routes.first()
        assertNotNull(route.clearkey)
        assertEquals("00112233445566778899aabbccddeeff", route.clearkey?.key)
        assertEquals("aabbccddeeff00112233445566778899", route.clearkey?.kid)
    }

    @Test fun `server url key is consumed verbatim and never recomputed locally`() {
        val news = subscription.channels.first { it.channelId == "無線新聞台" }
        // 服务端已给：原样采用（不重算）
        assertEquals("aa11bb22cc33dd44ee55ff66001122334455ff66", news.routes.first().urlKey)
        assertTrue(news.routes.first().attributable)
        // 服务端未给：**不得本地兜底计算**（F1 拆雷），记为不可归因，但仍可播放
        assertNull(news.routes[1].urlKey)
        assertFalse(news.routes[1].attributable)
        assertTrue(news.routes[1].url.isNotBlank())
    }

    @Test fun `route without url key still yields a playable plan`() {
        val channel = subscription.channels.first { it.channelId == "無線新聞台" }
        val plans = com.kantv.app.playback.HubRouteSelector.plans(channel)
        assertEquals(2, plans.size)
        val unattributed = plans.first { !it.attributable }
        assertTrue(unattributed.uri.isNotBlank())
        assertNull(unattributed.urlKey)
    }

    @Test fun `signed params are parsed for local computation on non hub sources`() {
        val params = requireNotNull(subscription.signedParams)
        assertTrue(params.global.contains("token"))
        assertTrue(params.global.contains("msisdn"))
        assertEquals(
            listOf("sig", "ts", "c", "ext_start_limit", "int_start_limit"),
            params.byHost["edgeware.tvb.com"],
        )
        // P0-2 方案 C 契约加法字段：名单指纹（服务端 url_key 语义开关）
        assertEquals("605077873046", params.fingerprint)
    }

    @Test fun `signed params without fingerprint parses with null fingerprint`() {
        val legacy = """
            {
              "hub_schema": 2,
              "seq": 1,
              "signed_params": { "global": ["token"], "by_host": {} },
              "channels": [],
              "aliases": []
            }
        """.trimIndent()
        val parsed = HubSubscriptionParser.parse(legacy) as HubParseOutcome.Parsed
        assertEquals(listOf("token"), parsed.subscription.signedParams?.global)
        assertNull(parsed.subscription.signedParams?.fingerprint)
    }

    @Test fun `drm uuid is parsed when delivered and absent otherwise`() {
        val news = subscription.channels.first { it.channelId == "無線新聞台" }
        assertEquals("e2719d58-a985-b3c9-781a-b030af78d30e", news.routes.first().drmUuid)
        // 非 dash-clearkey 线路没有 drm_uuid
        assertNull(news.routes[1].drmUuid)
    }

    @Test fun `missing signed params is tolerated so older servers keep working`() {
        val legacy = """
            {
              "hub_schema": 1,
              "seq": 1,
              "channels": [
                { "channel_id": "无线新闻", "display": "无线新闻", "coverage": "ok",
                  "routes": [ { "route": "hls-ts", "url": "https://a.invalid/x.m3u8", "url_key": "${"a".repeat(40)}" } ] }
              ],
              "aliases": []
            }
        """.trimIndent()
        val parsed = HubSubscriptionParser.parse(legacy)
        assertTrue(parsed is HubParseOutcome.Parsed)
        assertNull((parsed as HubParseOutcome.Parsed).subscription.signedParams)
        // 缺失名单不影响「只消费服务端 url_key」的枢纽链路
        assertEquals("a".repeat(40), parsed.subscription.channels.single().routes.single().urlKey)
    }

    @Test fun `unknown route is preserved as unknown and never inferred from the url`() {
        val unsupported = subscription.channels.first { it.channelId == "未知路線台" }
        assertEquals(HubRouteType.UNKNOWN, unsupported.routes.single().route)
        assertEquals("dash-mpegts", unsupported.routes.single().rawRoute)
        // 即便 url 以 .mpd 结尾，也不得推断为 dash-clearkey
        assertTrue(unsupported.routes.single().url.endsWith(".mpd"))
    }

    @Test fun `coverage values map to the contract vocabulary`() {
        assertEquals(HubCoverage.OK, subscription.channels.first { it.channelId == "無線新聞台" }.coverage)
        assertEquals(HubCoverage.PARTIAL, subscription.channels.first { it.channelId == "翡翠台" }.coverage)
        assertEquals(HubCoverage.MISSING, subscription.channels.first { it.channelId == "廣東體育" }.coverage)
        assertEquals(HubCoverage.UNSUPPORTED_FOR_CLIENT, subscription.channels.first { it.channelId == "未知路線台" }.coverage)
    }

    @Test fun `missing channel still carries a fallback`() {
        val gd = subscription.channels.first { it.channelId == "廣東體育" }
        assertTrue(gd.routes.isEmpty())
        assertEquals("web-embed", gd.fallback?.route)
    }

    @Test fun `alias table is built from the delivered payload only`() {
        val table = subscription.aliasTable()
        assertEquals("無線新聞台", ChannelIdentity.resolveChannelId("无线新闻", table))
        assertEquals("無線新聞台", ChannelIdentity.resolveChannelId("TVB 新闻", table))
        assertEquals("翡翠台", ChannelIdentity.resolveChannelId("翡翠", table))
        assertEquals("廣東體育", ChannelIdentity.resolveChannelId("广东体育频道", table))
        assertNull(ChannelIdentity.resolveChannelId("不存在的频道", table))
    }

    @Test fun `missing schema or channels is invalid`() {
        // org.json 会容忍尾随内容，因此用「顶层不是对象」来验证 NOT_JSON。
        assertEquals(HubParseReason.NOT_JSON, (HubSubscriptionParser.parse("[]") as HubParseOutcome.Invalid).reason)
        assertEquals(HubParseReason.NOT_JSON, (HubSubscriptionParser.parse("not json") as HubParseOutcome.Invalid).reason)
        assertEquals(
            HubParseReason.MISSING_SCHEMA,
            (HubSubscriptionParser.parse("{\"channels\":[]}") as HubParseOutcome.Invalid).reason,
        )
        assertEquals(
            HubParseReason.MISSING_CHANNELS,
            (HubSubscriptionParser.parse("{\"hub_schema\":1}") as HubParseOutcome.Invalid).reason,
        )
    }

    @Test fun `higher schema is parsed so the caller can refuse to apply it`() {
        val outcome = HubSubscriptionParser.parse(fixture().replace("\"hub_schema\": 2", "\"hub_schema\": 7"))
        assertEquals(7, (outcome as HubParseOutcome.Parsed).subscription.hubSchema)
    }

    private fun fixture(): String = requireNotNull(javaClass.getResourceAsStream("/hub_v1_fixture.json")) {
        "缺少 fixture：app/src/test/resources/hub_v1_fixture.json"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
