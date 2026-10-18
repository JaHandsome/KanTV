package com.kantv.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 对**真实服务端 payload** 的冒烟验证。
 *
 * 该 payload 含真实流地址，按红线不得写入仓库，因此本测试从系统属性读取文件路径；
 * 未提供时跳过。**它不能替代权威向量门禁**（`UrlKeyTest` + `-Dkantv.urlkey.vectors`）：
 * 线上线路当前多为「零命中」形态，本地复算会平凡通过。
 *
 * ```
 * curl -s 'http://192.168.1.10:8420/api/hub/v1/subscription?client=kantv&schema=1&caps=hls-ts,hls-aes128,flv,dash-clearkey,web-embed' > /tmp/hub_payload.json
 * ./gradlew :app:testDebugUnitTest --tests '*HubServerPayloadSmokeTest*' -Dkantv.hub.payload=/tmp/hub_payload.json
 * ```
 */
class HubServerPayloadSmokeTest {
    private val payloadPath: String? =
        System.getProperty("kantv.hub.payload")?.takeIf { it.isNotBlank() }

    @Test fun `real payload parses and every delivered url key matches byte for byte`() {
        assumeTrue("未提供 -Dkantv.hub.payload，跳过真实 payload 冒烟", payloadPath != null)
        val payload = java.io.File(requireNotNull(payloadPath)).readText(Charsets.UTF_8)
        val parsed = HubSubscriptionParser.parse(payload)
        assertTrue("真实 payload 必须可解析", parsed is HubParseOutcome.Parsed)
        val subscription = (parsed as HubParseOutcome.Parsed).subscription

        assertEquals("本机支持的 hub_schema", com.kantv.app.BuildConfig.HUB_SCHEMA, subscription.hubSchema)
        assertTrue("必须投递频道", subscription.channels.isNotEmpty())
        assertEquals("真实 payload 不应有无法归因的条目", 0, parsed.skippedChannels)

        val routes = subscription.channels.flatMap { it.routes }
        assertTrue("必须投递线路", routes.isNotEmpty())

        // 1) 每条线路的 url_key 由服务端保证非空且为 40 位小写 hex（§3）；为空即不可归因（F1）
        routes.forEach { route ->
            val delivered = route.urlKey
            if (delivered != null) {
                assertTrue("url_key 必须是 40 位小写 hex：$delivered", delivered.matches(Regex("[0-9a-f]{40}")))
                assertTrue("有 url_key 即应可归因", route.attributable)
            } else {
                assertTrue("缺少 url_key 时必须标记为不可归因", !route.attributable)
            }
        }

        // 2) 若服务端下发了 signed_params，则用**客户端本地实现**逐条复算并与服务端比对。
        //    这是本轮修复（F2）在真实数据上的直接证据；locallyVerified 为 0 表示当前无签名参数线路。
        val signedParams = subscription.signedParams
        var locallyVerified = 0
        var mismatched = 0
        if (signedParams != null) {
            routes.forEach { route ->
                val delivered = route.urlKey ?: return@forEach
                locallyVerified++
                if (UrlKey.urlKey(route.url, signedParams) != delivered) mismatched++
            }
        }
        println(
            "HubServerPayloadSmokeTest: channels=${subscription.channels.size} routes=${routes.size} " +
                "signedParams=${signedParams != null} locallyVerified=$locallyVerified mismatched=$mismatched",
        )
        assertEquals("本地 url_key 复算与服务端不一致", 0, mismatched)
        if (signedParams != null) {
            assertTrue("所有投递线路都应被复算覆盖", locallyVerified == routes.count { it.urlKey != null })
        }

        // 3) 本地主键由 channel_id 派生且互不相同；别名自洽
        val ids = subscription.channels.map { HubChannelIdentity.internalId(it.channelId) }
        assertEquals(subscription.channels.size, ids.distinct().size)
        val table = subscription.aliasTable()
        subscription.aliases.forEach { alias ->
            assertEquals(alias.channelId, ChannelIdentity.resolveChannelId(alias.name, table))
        }
        assertTrue("别名表必须非空", table.aliasCount > 0)

        // 4) route → 引擎映射
        val plans = subscription.channels.sumOf { com.kantv.app.playback.HubRouteSelector.plans(it).size }
        assertTrue("映射后的可播放线路数必须大于 0", plans > 0)
    }
}
