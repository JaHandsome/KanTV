package com.kantv.app.catalog

import com.kantv.app.data.ImportSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-2 方案 C 客户端落地：服务端 `signed_params.fingerprint`（契约加法字段）的
 * 解析、持久化锚点与变化检测。客户端不新增本地 url_key 计算，指纹变化只留痕。
 */
class HubSubscriptionCoordinatorTest {

    private class QueueTransport(private vararg val responses: HubHttpResponse) : HubHttpTransport {
        var calls = 0
            private set

        override fun get(uri: String, ifNoneMatch: String?): HubHttpResponse {
            calls++
            return responses[(calls - 1).coerceIn(responses.indices)]
        }
    }

    private class NoopSink : HubCatalogSink {
        override suspend fun applyHubSubscription(
            subscription: HubSubscription,
            startedAtMs: Long,
        ): CatalogImportResult = CatalogImportResult.Preserved(
            ImportSummary(acceptedChannels = 0, rejectedLines = 0, issueCodes = emptySet()),
        )
    }

    private fun payload(seq: Long, fingerprint: String?) = """
        {
          "hub_schema": 2,
          "seq": $seq,
          "server": { "app": "1.0.0", "caps_supported": [] },
          "groups": [],
          "signed_params": {
            "global": ["token"],
            "by_host": {}${fingerprint?.let { ",\n            \"fingerprint\": \"$it\"" } ?: ""}
          },
          "channels": [
            { "channel_id": "ch1", "display": "ch1", "coverage": "ok",
              "routes": [ { "route": "hls-ts", "url": "https://a.invalid/x.m3u8",
                            "url_key": "${"a".repeat(40)}", "score": 1 } ] }
          ],
          "aliases": []
        }
    """.trimIndent()

    private fun response(body: String) = HubHttpResponse(
        code = 200,
        etag = "\"tag-${body.hashCode()}\"",
        contentType = "application/json; charset=utf-8",
        body = body.toByteArray(Charsets.UTF_8),
    )

    private fun coordinator(
        transport: HubHttpTransport,
        store: HubSubscriptionStore = HubSubscriptionStore(),
        onFingerprintChange: (previous: String?, current: String) -> Unit = { _, _ -> },
    ) = HubSubscriptionCoordinator(
        store = store,
        client = HubSubscriptionClient(
            endpoint = "http://hub.invalid",
            identity = HubClientIdentity(app = "test", device = "test"),
            transport = transport,
        ),
        sink = NoopSink(),
        onSignedParamsFingerprintChange = onFingerprintChange,
    )

    @Test fun `fingerprint change is detected and new anchor is persisted`() = runBlocking {
        val store = HubSubscriptionStore()
        val seen = mutableListOf<Pair<String?, String>>()
        val coord = coordinator(
            QueueTransport(response(payload(seq = 1, fingerprint = "aaa111")), response(payload(seq = 2, fingerprint = "bbb222"))),
            store = store,
            onFingerprintChange = { previous, current -> seen.add(previous to current) },
        )

        val first = coord.refresh(nowMs = 1_000L)
        assertTrue(first is HubRefreshOutcome.Applied)
        assertEquals(listOf(null to "aaa111"), seen)   // 首次见到指纹也回调（previous=null）
        assertEquals("aaa111", store.state().signedParamsFingerprint)

        val second = coord.refresh(force = true, nowMs = 2_000L)
        assertTrue(second is HubRefreshOutcome.Applied)
        assertEquals(listOf(null to "aaa111", "aaa111" to "bbb222"), seen)
        assertEquals("bbb222", store.state().signedParamsFingerprint)
    }

    @Test fun `unchanged fingerprint does not fire callback`() = runBlocking {
        val store = HubSubscriptionStore()
        var fired = 0
        val coord = coordinator(
            QueueTransport(response(payload(seq = 1, fingerprint = "aaa111")), response(payload(seq = 2, fingerprint = "aaa111"))),
            store = store,
            onFingerprintChange = { _, _ -> fired++ },
        )
        coord.refresh(nowMs = 1_000L)
        coord.refresh(force = true, nowMs = 2_000L)
        assertEquals(1, fired)   // 仅首次（null -> aaa111）
    }

    @Test fun `legacy server without fingerprint keeps anchor and stays silent`() = runBlocking {
        val store = HubSubscriptionStore()
        var fired = 0
        val coord = coordinator(
            QueueTransport(response(payload(seq = 1, fingerprint = null))),
            store = store,
            onFingerprintChange = { _, _ -> fired++ },
        )
        coord.refresh(nowMs = 1_000L)
        assertEquals(0, fired)
        assertNull(store.state().signedParamsFingerprint)
    }
}
