package com.kantv.app.catalog

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HUB CONTRACT v1.1 §2：url_key 必须与服务端**逐字节**一致。
 *
 * 向量与名单来源（按优先级）：
 * 1. `-Dkantv.urlkey.vectors=<file>` + `-Dkantv.hub.payload=<订阅 payload>`：
 *    权威向量 + 服务端下发的 `signed_params`（**含真实地址的文件不入库**）。
 * 2. 仓库内 `urlkey_vectors.json` + `urlkey_signed_params.json`（脱敏回归基线）。
 *
 * v1 曾在此处写错三件事，本测试逐条锁死：query 排序（v1.1 已撤回）、**后缀匹配**（应为精确匹配）、
 * 以及「零命中时丢掉 `?` 与空参数段」（应为逐字节原样返回）。
 */
class UrlKeyTest {
    private val vectorSource: String by lazy {
        System.getProperty(VECTOR_OVERRIDE_PROPERTY)?.takeIf { it.isNotBlank() }
            ?: "test-resources:urlkey_vectors.json"
    }

    /** 名单优先取「服务端 payload 里的 signed_params」（真实权威），其次取仓库内的脱敏基线。 */
    private val signedParams: HubSignedParams by lazy {
        val payloadPath = System.getProperty(PAYLOAD_OVERRIDE_PROPERTY)?.takeIf { it.isNotBlank() }
        if (payloadPath != null) {
            val file = File(payloadPath)
            if (file.isFile) {
                val parsed = HubSubscriptionParser.parse(file.readText(Charsets.UTF_8))
                (parsed as? HubParseOutcome.Parsed)?.subscription?.signedParams?.let { return@lazy it }
            }
        }
        val config = requireNotNull(javaClass.getResourceAsStream("/urlkey_signed_params.json")) {
            "缺少名单文件 app/src/test/resources/urlkey_signed_params.json"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(config)
        HubSignedParams(
            global = root.optJSONArray("global").stringList(),
            byHost = root.optJSONObject("by_host")?.let { hosts ->
                hosts.keys().asSequence().associateWith { key -> hosts.optJSONArray(key).stringList() }
            }.orEmpty(),
        )
    }

    private val vectors: List<Pair<String, String>> by lazy {
        val override = System.getProperty(VECTOR_OVERRIDE_PROPERTY)?.takeIf { it.isNotBlank() }
        val text = if (override != null) {
            val file = File(override)
            require(file.isFile) { "系统属性 $VECTOR_OVERRIDE_PROPERTY 指向的文件不存在：$override" }
            file.readText(Charsets.UTF_8)
        } else {
            val stream = requireNotNull(javaClass.getResourceAsStream("/urlkey_vectors.json")) {
                "缺少向量文件 app/src/test/resources/urlkey_vectors.json"
            }
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val array = JSONArray(text)
        (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            row.getString("url") to row.getString("url_key")
        }
    }

    @Test fun `every vector matches byte for byte`() {
        assertTrue("向量条数必须 >= 40，实际 ${vectors.size}", vectors.size >= 40)
        vectors.forEach { (url, expected) ->
            assertEquals(
                "url_key 不一致（来源=$vectorSource，全局名单 ${signedParams.global.size} 项）：$url",
                expected,
                UrlKey.urlKey(url, signedParams),
            )
        }
    }

    @Test fun `url key is lowercase forty character sha1`() {
        val key = UrlKey.urlKey("http://a.invalid/b.m3u8?token=T", signedParams)
        assertEquals(40, key.length)
        assertEquals(key.lowercase(), key)
        assertTrue(key.matches(Regex("[0-9a-f]{40}")))
    }

    // ---------- §2.1 normalize_url ----------

    @Test fun `normalisation lowercases scheme and the whole authority but keeps path and query verbatim`() {
        assertEquals(
            "http://user:pass@example.invalid:8080/Path/Case.M3U8?A=1",
            UrlKey.normalizeUrl("HTTP://User:Pass@EXAMPLE.INVALID:8080/Path/Case.M3U8?A=1"),
        )
        assertEquals("http://example.invalid:8080/x", UrlKey.normalizeUrl("http://Example.Invalid:8080/x"))
    }

    @Test fun `normalisation drops the fragment before inspecting the query`() {
        assertEquals("http://a.invalid/b.m3u8", UrlKey.normalizeUrl("http://a.invalid/b.m3u8#frag?token=T"))
        assertEquals("http://a.invalid/b.m3u8?token=T", UrlKey.normalizeUrl("http://a.invalid/b.m3u8?token=T#frag"))
    }

    @Test fun `non http schemes and non urls are returned as is after trimming`() {
        assertEquals("rtsp://a.invalid/live?token=T", UrlKey.normalizeUrl("rtsp://a.invalid/live?token=T"))
        assertEquals("not a url?token=T", UrlKey.normalizeUrl("not a url?token=T"))
        assertEquals("", UrlKey.normalizeUrl("   "))
        assertEquals("", UrlKey.normalizeUrl(""))
    }

    @Test fun `leading and trailing whitespace is stripped`() {
        assertEquals("http://a.invalid/x", strip("  http://a.invalid/x?token=T  "))
        assertEquals("http://a.invalid/x?uid=1", strip("  http://a.invalid/x?token=T&uid=1  "))
    }

    // ---------- §2.2 signed_params ----------

    @Test fun `global names apply to every host`() {
        assertTrue(UrlKey.resolveSignedParams("http://a.invalid/x?token=1", signedParams).contains("token"))
        assertTrue(UrlKey.resolveSignedParams("http://a.invalid/x?msisdn=1", signedParams).contains("msisdn"))
        assertTrue(UrlKey.resolveSignedParams("http://a.invalid/x?securitykey=1", signedParams).contains("securitykey"))
    }

    @Test fun `host scoped names need an exact or dot suffixed host match`() {
        assertTrue(UrlKey.resolveSignedParams("https://scope-1.example.invalid/x.mpd?sig=1", signedParams).contains("sig"))
        assertTrue(
            "子域应命中",
            UrlKey.resolveSignedParams("https://fixture.scope-1.example.invalid/x.mpd?sig=1", signedParams).contains("sig"),
        )
        assertFalse(
            "非 edgeware 主机不得命中 sig",
            UrlKey.resolveSignedParams("https://a.invalid/x?sig=1", signedParams).contains("sig"),
        )
        assertFalse(
            "前缀相似但非子域不得命中",
            UrlKey.resolveSignedParams("https://fixture-32.example.invalid/x?sig=1", signedParams).contains("sig"),
        )
        assertFalse(
            "后缀伪装不得命中",
            UrlKey.resolveSignedParams("https://scope-1.example.invalid.evil.invalid/x?sig=1", signedParams).contains("sig"),
        )
    }

    @Test fun `host extraction is case sensitive on the original url like the server`() {
        // 服务端用区分大小写的 `https?://` 匹配原始 url；大写 scheme 匹配失败 → host = url[:30]
        assertFalse(UrlKey.resolveSignedParams("HTTPS://scope-1.example.invalid/x.mpd?sig=1", signedParams).contains("sig"))
        assertTrue(
            "全局名单仍然生效",
            UrlKey.resolveSignedParams("HTTPS://scope-1.example.invalid/x.mpd?sig=1", signedParams).contains("token"),
        )
    }

    @Test fun `missing signed params means local computation is unavailable`() {
        val url = "http://a.invalid/b.m3u8?token=T"
        assertEquals(url, UrlKey.stripSignedParams(url, null))
        assertEquals(UrlKey.normalizeUrl(url), UrlKey.stripSignedParams(url, HubSignedParams()))
        assertTrue(UrlKey.resolveSignedParams(url, null).isEmpty())
    }

    // ---------- §2.3 strip_signed_params ----------

    @Test fun `zero hit returns the normalised url byte for byte`() {
        assertEquals("http://a.invalid/b.m3u8?quality=1080p&std=1", strip("http://a.invalid/b.m3u8?quality=1080p&std=1"))
        assertEquals("http://a.invalid/b.m3u8?", strip("http://a.invalid/b.m3u8?"))
        assertEquals("http://a.invalid/b.m3u8?&&x=1&", strip("http://a.invalid/b.m3u8?&&x=1&"))
        assertEquals("http://a.invalid/b.m3u8?flag&fixture-24.example.invalid=1", strip("http://a.invalid/b.m3u8?flag&fixture-24.example.invalid=1"))
    }

    @Test fun `empty segments are dropped once a hit occurs`() {
        assertEquals("http://a.invalid/b.m3u8?uid=1", strip("http://a.invalid/b.m3u8?token=T&&uid=1&"))
    }

    @Test fun `all signed params stripped leaves the base without a question mark`() {
        assertEquals("http://a.invalid/b.m3u8", strip("http://a.invalid/b.m3u8?token=T"))
        assertEquals("http://a.invalid/b.m3u8", strip("http://a.invalid/b.m3u8?token="))
        assertEquals("https://scope-1.example.invalid/x.mpd", strip("https://scope-1.example.invalid/x.mpd?sig=S&ts=1&c=9"))
    }

    @Test fun `matching is exact and not a suffix or substring match`() {
        assertEquals("http://a.invalid/b.m3u8?signal=1", strip("http://a.invalid/b.m3u8?signal=1"))
        assertEquals("http://a.invalid/b.m3u8?tokenized=1", strip("http://a.invalid/b.m3u8?tokenized=1"))
        assertEquals("http://a.invalid/b.m3u8?expire_hint=1", strip("http://a.invalid/b.m3u8?expire_hint=1"))
        // v1 的后缀表会把 hdnts / __ts 误删；精确匹配下必须原样保留
        assertEquals("http://a.invalid/b.m3u8?hdnts=H&v=2", strip("http://a.invalid/b.m3u8?hdnts=H&v=2"))
        assertNotEquals(
            strip("http://a.invalid/b.m3u8?hdnts=H&v=2"),
            strip("http://a.invalid/b.m3u8?v=2"),
        )
    }

    @Test fun `param names are matched case insensitively`() {
        assertEquals("http://a.invalid/b.m3u8?uid=7", strip("http://a.invalid/b.m3u8?TOKEN=T&uid=7"))
    }

    @Test fun `non http scheme is never stripped`() {
        assertEquals("rtsp://a.invalid/live?token=T", strip("rtsp://a.invalid/live?token=T"))
        assertEquals("ftp://a.invalid/x?token=T", strip("ftp://a.invalid/x?token=T"))
        assertEquals("not a url at all?token=T", strip("not a url at all?token=T"))
    }

    @Test fun `query order is never rearranged`() {
        assertEquals("http://a.invalid/b.m3u8?z=2&a=1", strip("http://a.invalid/b.m3u8?z=2&a=1"))
        assertEquals("http://a.invalid/b.m3u8?b=2&a=1", UrlKey.normalizeUrl("http://a.invalid/b.m3u8?b=2&a=1"))
        // §2.4：仅顺序不同的两条 URL 必须得到不同的 url_key（v1 的「顺序无关」要求已撤回）
        assertNotEquals(
            UrlKey.urlKey("http://a.invalid/b.m3u8?b=2&a=1", signedParams),
            UrlKey.urlKey("http://a.invalid/b.m3u8?a=1&b=2", signedParams),
        )
        assertEquals(
            "http://a.invalid/b.m3u8?uid=7&quality=1080p",
            strip("http://a.invalid/b.m3u8?token=T&uid=7&quality=1080p"),
        )
    }

    @Test fun `signature rotation does not change the url key`() {
        val first = UrlKey.urlKey("https://scope-1.example.invalid/x.mpd?sig=AAA&ts=1&uid=7", signedParams)
        val rotated = UrlKey.urlKey("https://scope-1.example.invalid/x.mpd?sig=BBB&ts=2&uid=7", signedParams)
        assertEquals(first, rotated)
        assertEquals("https://scope-1.example.invalid/x.mpd?uid=7", strip("https://scope-1.example.invalid/x.mpd?sig=BBB&ts=2&uid=7"))
    }

    @Test fun `urls differing only by signature share a url key`() {
        // 这正是 url_key 的设计意图：签名轮换后同一物理线路仍归为同一条。
        assertEquals(
            UrlKey.urlKey("http://a.invalid/b.m3u8?token=T1", signedParams),
            UrlKey.urlKey("http://a.invalid/b.m3u8?token=T2", signedParams),
        )
    }

    private fun strip(url: String): String = UrlKey.stripSignedParams(url, signedParams)

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optString(index).trim().ifEmpty { null } }
    }

    private companion object {
        /** 可选覆盖：透传服务端权威向量文件，避免把含真实地址的向量写入仓库。 */
        const val VECTOR_OVERRIDE_PROPERTY = "kantv.urlkey.vectors"

        /** 可选覆盖：从真实订阅 payload 里取服务端当前 `signed_params`。 */
        const val PAYLOAD_OVERRIDE_PROPERTY = "kantv.hub.payload"
    }
}
