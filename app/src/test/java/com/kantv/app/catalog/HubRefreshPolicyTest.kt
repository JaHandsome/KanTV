package com.kantv.app.catalog

import com.kantv.app.data.ImportSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约 §6 的刷新语义，以及枢纽通道的端到端决策链。
 *
 * 覆盖验收要求的五类场景：首次无订阅 / 拉取失败保留旧数据 / 播放中不应用 /
 * schema 过高拒绝 / 304 不重建。
 */
class HubRefreshPolicyTest {
    private val client = HubClientIdentity(client = "kantv", app = "0.1.8", device = "tcl-q10m-pro")

    // ---------- 纯策略 ----------

    @Test fun `first launch with no subscription is due immediately`() {
        val state = HubSubscriptionState()
        assertTrue(HubRefreshPolicy.shouldRefresh(state, nowMs = 1_000L))
        assertTrue(HubRefreshPolicy.shouldSeedBundledFallback(state, hasChannels = false))
        assertEquals(HubCatalogSource.NONE, HubRefreshPolicy.catalogSource(state, hasChannels = false))
    }

    @Test fun `refresh is hourly once attempted`() {
        val attempted = HubSubscriptionState(lastAttemptAtMs = 10_000L, lastSuccessAtMs = 10_000L, payload = "{}")
        assertFalse(HubRefreshPolicy.shouldRefresh(attempted, nowMs = 10_000L + HubRefreshPolicy.REFRESH_INTERVAL_MS - 1))
        assertTrue(HubRefreshPolicy.shouldRefresh(attempted, nowMs = 10_000L + HubRefreshPolicy.REFRESH_INTERVAL_MS))
        assertTrue(HubRefreshPolicy.shouldRefresh(attempted, nowMs = 10_000L, force = true))
    }

    @Test fun `304 keeps the previous catalog and never rebuilds`() {
        val decision = HubRefreshPolicy.decide(HubFetchOutcome.NotModified, playbackActive = false, supportedSchema = 1)
        assertEquals(HubApplyAction.KEEP_PREVIOUS, decision.action)
        assertEquals(HubApplyReason.NOT_MODIFIED, decision.reason)
        assertFalse(decision.shouldRebuildCatalog)
    }

    @Test fun `fetch failure keeps the previous catalog`() {
        val decision = HubRefreshPolicy.decide(
            HubFetchOutcome.Failed(HubSubscriptionStatus.NETWORK_UNAVAILABLE),
            playbackActive = false,
            supportedSchema = 1,
        )
        assertEquals(HubApplyAction.KEEP_PREVIOUS, decision.action)
        assertEquals(HubApplyReason.FETCH_FAILED, decision.reason)
        assertFalse(decision.shouldRebuildCatalog)
    }

    @Test fun `higher schema is rejected and flagged for reporting`() {
        val decision = HubRefreshPolicy.decide(
            fetched(schema = 3),
            playbackActive = false,
            supportedSchema = 2,
        )
        assertEquals(HubApplyAction.KEEP_PREVIOUS, decision.action)
        assertEquals(HubApplyReason.SCHEMA_UNSUPPORTED, decision.reason)
        assertTrue(decision.shouldReportSchemaMismatch)
        assertFalse(decision.shouldRebuildCatalog)
    }

    @Test fun `equal supported schema is accepted`() {
        // hub_schema=2（本机支持值）：等于时应用，不触发 schema_unsupported
        val decision = HubRefreshPolicy.decide(
            fetched(schema = 2),
            playbackActive = false,
            supportedSchema = 2,
        )
        assertEquals(HubApplyAction.APPLY, decision.action)
        assertEquals(HubApplyReason.FETCH_SUCCEEDED, decision.reason)
        assertTrue(decision.shouldRebuildCatalog)
        assertFalse(decision.shouldReportSchemaMismatch)
    }

    @Test fun `playback is never interrupted by a new subscription`() {
        val decision = HubRefreshPolicy.decide(fetched(schema = 2), playbackActive = true, supportedSchema = 2)
        assertEquals(HubApplyAction.DEFER_UNTIL_PLAYBACK_STOPPED, decision.action)
        assertEquals(HubApplyReason.PLAYBACK_ACTIVE, decision.reason)
        assertFalse(decision.shouldRebuildCatalog)
    }

    @Test fun `bundled fallback is used only before the first successful subscription`() {
        assertTrue(HubRefreshPolicy.shouldSeedBundledFallback(HubSubscriptionState(), hasChannels = false))
        assertFalse(
            HubRefreshPolicy.shouldSeedBundledFallback(
                HubSubscriptionState(lastSuccessAtMs = 1L, payload = "{}"),
                hasChannels = false,
            ),
        )
        assertEquals(
            HubCatalogSource.HUB_CACHE,
            HubRefreshPolicy.catalogSource(HubSubscriptionState(lastSuccessAtMs = 1L, payload = "{}"), hasChannels = true),
        )
        assertEquals(
            HubCatalogSource.BUNDLED_FALLBACK,
            HubRefreshPolicy.catalogSource(HubSubscriptionState(), hasChannels = true),
        )
    }

    // ---------- 存储语义 ----------

    @Test fun `failing refresh preserves the last successful payload`() {
        val persistence = InMemoryHubSubscriptionPersistence()
        val store = HubSubscriptionStore(persistence)
        store.recordAttempt(1_000L)
        store.recordSuccess(PAYLOAD, "\"etag-1\"", seq = 7L, nowMs = 1_000L, applied = true)
        store.recordAttempt(2_000L)
        store.recordFailure(HubSubscriptionStatus.NETWORK_TIMEOUT, 2_000L)
        val state = store.state()
        assertEquals(PAYLOAD, state.payload)
        assertEquals(7L, state.seq)
        assertEquals(1_000L, state.lastSuccessAtMs)
        assertTrue(state.hasSuccessfulSubscription)
        assertEquals(HubSubscriptionStatus.NETWORK_TIMEOUT, state.status)
    }

    @Test fun `a stale response cannot overwrite a newer result`() {
        val store = HubSubscriptionStore(InMemoryHubSubscriptionPersistence())
        store.recordAttempt(5_000L)
        store.recordSuccess(PAYLOAD, "\"etag-new\"", seq = 10L, nowMs = 5_000L, applied = true)
        // 过期响应：时间更旧，必须被忽略
        store.recordSuccess("{\"hub_schema\":1,\"seq\":9}", "\"etag-old\"", seq = 9L, nowMs = 4_000L, applied = true)
        assertEquals(10L, store.state().seq)
        assertEquals("\"etag-new\"", store.state().etag)
    }

    // ---------- 端到端决策链（假传输 + 假落库） ----------

    @Test fun `applied subscription rebuilds the catalog and stores the alias table`() = runBlocking {
        val transport = FakeTransport(HubHttpResponse(code = 200, etag = "\"v1\"", contentType = "application/json", body = PAYLOAD.toByteArray()))
        val sink = RecordingSink()
        val store = HubSubscriptionStore(InMemoryHubSubscriptionPersistence())
        val aliasCache = ChannelAliasCache(InMemoryChannelAliasPersistence())
        val outcome = coordinator(transport, sink, store, aliasCache).refresh(force = true, nowMs = 1_000L)
        assertTrue(outcome is HubRefreshOutcome.Applied)
        assertEquals(1, sink.applied.size)
        assertEquals("\"v1\"", store.state().etag)
        assertEquals(42L, store.state().lastAppliedSeq)
        assertEquals("無線新聞台", ChannelIdentity.resolveChannelId("无线新闻", aliasCache.current()))
    }

    @Test fun `failed fetch leaves catalog and store untouched`() = runBlocking {
        val transport = FakeTransport(HubHttpResponse(code = 503))
        val sink = RecordingSink()
        val store = HubSubscriptionStore(InMemoryHubSubscriptionPersistence())
        val outcome = coordinator(transport, sink, store).refresh(force = true, nowMs = 2_000L)
        assertEquals(HubRefreshOutcome.Failed(HubSubscriptionStatus.http(503)), outcome)
        assertTrue(sink.applied.isEmpty())
        assertNull(store.state().payload)
        assertEquals(HubSubscriptionStatus.http(503), store.state().status)
    }

    @Test fun `304 does not rebuild the catalog`() = runBlocking {
        val sink = RecordingSink()
        val store = HubSubscriptionStore(InMemoryHubSubscriptionPersistence())
        val transport = FakeTransport(HubHttpResponse(code = 304))
        val outcome = coordinator(transport, sink, store).refresh(force = true, nowMs = 3_000L)
        assertEquals(HubRefreshOutcome.Unchanged, outcome)
        assertTrue(sink.applied.isEmpty())
        assertEquals(HubSubscriptionStatus.NOT_MODIFIED, store.state().status)
    }

    @Test fun `if none match is sent only after a successful subscription`() = runBlocking {
        val sink = RecordingSink()
        val store = HubSubscriptionStore(InMemoryHubSubscriptionPersistence())
        val transport = FakeTransport(
            HubHttpResponse(code = 200, etag = "\"v2\"", contentType = "application/json", body = PAYLOAD.toByteArray()),
            HubHttpResponse(code = 304),
        )
        val coordinator = coordinator(transport, sink, store)
        coordinator.refresh(force = true, nowMs = 1_000L)
        assertNull(transport.observedIfNoneMatch.first())
        coordinator.refresh(force = true, nowMs = 2_000L)
        assertEquals("\"v2\"", transport.observedIfNoneMatch.last())
    }

    @Test fun `payload older than two refresh intervals must be revalidated unconditionally`() {
        assertEquals(2 * HubRefreshPolicy.REFRESH_INTERVAL_MS, HubRefreshPolicy.MAX_PAYLOAD_AGE_MS)
        val state = HubSubscriptionState(
            etag = "\"v1\"",
            payload = "{}",
            lastAttemptAtMs = 10_000L,
            lastSuccessAtMs = 10_000L,
        )
        assertFalse(HubRefreshPolicy.shouldOmitIfNoneMatch(state, nowMs = 10_000L + HubRefreshPolicy.MAX_PAYLOAD_AGE_MS - 1))
        assertTrue(HubRefreshPolicy.shouldOmitIfNoneMatch(state, nowMs = 10_000L + HubRefreshPolicy.MAX_PAYLOAD_AGE_MS))
    }

    @Test fun `stale payload omits if-none-match so the server must answer 200`() = runBlocking {
        val transport = FakeTransport(
            HubHttpResponse(code = 200, etag = "\"v3\"", contentType = "application/json", body = PAYLOAD.toByteArray()),
        )
        val store = HubSubscriptionStore(InMemoryHubSubscriptionPersistence())
        store.recordSuccess(PAYLOAD, "\"v1\"", seq = 41L, nowMs = 1_000L, applied = true)

        val outcome = coordinator(transport, RecordingSink(), store)
            .refresh(force = true, nowMs = 1_000L + HubRefreshPolicy.MAX_PAYLOAD_AGE_MS)

        assertTrue(outcome is HubRefreshOutcome.Applied)
        assertNull(transport.observedIfNoneMatch.single())
        assertEquals("\"v3\"", store.state().etag)
    }

    @Test fun `fresh payload still sends the stored etag and 304 handling is unchanged`() = runBlocking {
        val transport = FakeTransport(HubHttpResponse(code = 304))
        val store = HubSubscriptionStore(InMemoryHubSubscriptionPersistence())
        store.recordSuccess(PAYLOAD, "\"v1\"", seq = 41L, nowMs = 1_000L, applied = true)

        val outcome = coordinator(transport, RecordingSink(), store)
            .refresh(force = true, nowMs = 1_000L + HubRefreshPolicy.MAX_PAYLOAD_AGE_MS - 1)

        assertEquals(HubRefreshOutcome.Unchanged, outcome)
        assertEquals("\"v1\"", transport.observedIfNoneMatch.single())
        assertEquals(HubSubscriptionStatus.NOT_MODIFIED, store.state().status)
    }

    @Test fun `higher schema is rejected without touching the catalog`() = runBlocking {
        // 本机支持值=2，服务端回 3 → 拒绝应用（§5.2 高于自身支持值即拒绝）
        val higherSchema = PAYLOAD.replace("\"hub_schema\": 2", "\"hub_schema\": 3")
        val transport = FakeTransport(
            HubHttpResponse(code = 200, etag = "\"v9\"", contentType = "application/json", body = higherSchema.toByteArray()),
        )
        val sink = RecordingSink()
        val store = HubSubscriptionStore(InMemoryHubSubscriptionPersistence())
        val notes = mutableListOf<HubNotice>()
        val outcome = coordinator(transport, sink, store, onNote = notes::add).refresh(force = true, nowMs = 4_000L)
        assertEquals(HubRefreshOutcome.SchemaUnsupported(3), outcome)
        assertTrue(sink.applied.isEmpty())
        assertEquals(HubSubscriptionStatus.SCHEMA_UNSUPPORTED, store.state().status)
        // v1.1 §5.2：协议级事件通过 notices[] 上报（detail 只写协商事实，不含 URL/凭据）
        assertEquals(
            listOf(HubNotice(HubNotice.CODE_SCHEMA_UNSUPPORTED, "client supports 2, server sent 3")),
            notes,
        )
    }

    @Test fun `subscription fetched during playback is cached but not applied until playback stops`() = runBlocking {
        val transport = FakeTransport(
            HubHttpResponse(code = 200, etag = "\"v3\"", contentType = "application/json", body = PAYLOAD.toByteArray()),
        )
        val sink = RecordingSink()
        val store = HubSubscriptionStore(InMemoryHubSubscriptionPersistence())
        val aliasCache = ChannelAliasCache(InMemoryChannelAliasPersistence())
        val coordinator = coordinator(transport, sink, store, aliasCache)

        val deferred = coordinator.refresh(force = true, playbackActive = true, nowMs = 5_000L)
        assertEquals(HubRefreshOutcome.Deferred(42L), deferred)
        assertTrue(sink.applied.isEmpty())
        // 别名表也不得在播放中改写
        assertTrue(aliasCache.current().isEmpty)
        assertNotNull(store.state().payload)

        val applied = coordinator.applyDeferredIfAny(nowMs = 6_000L)
        assertTrue(applied is HubRefreshOutcome.Applied)
        assertEquals(1, sink.applied.size)
        assertEquals("無線新聞台", ChannelIdentity.resolveChannelId("无线新闻", aliasCache.current()))
    }

    @Test fun `invalid payload is treated as a failure and keeps the previous result`() = runBlocking {
        val transport = FakeTransport(
            HubHttpResponse(code = 200, etag = "\"bad\"", contentType = "application/json", body = "not json".toByteArray()),
        )
        val sink = RecordingSink()
        val store = HubSubscriptionStore(InMemoryHubSubscriptionPersistence())
        val outcome = coordinator(transport, sink, store).refresh(force = true, nowMs = 7_000L)
        assertEquals(HubRefreshOutcome.Failed(HubSubscriptionStatus.INVALID_PAYLOAD), outcome)
        assertTrue(sink.applied.isEmpty())
    }

    @Test fun `wrong content type is rejected`() = runBlocking {
        val transport = FakeTransport(
            HubHttpResponse(code = 200, contentType = "text/html", body = PAYLOAD.toByteArray()),
        )
        val outcome = coordinator(transport, RecordingSink(), HubSubscriptionStore(InMemoryHubSubscriptionPersistence()))
            .refresh(force = true, nowMs = 8_000L)
        assertEquals(HubRefreshOutcome.Failed(HubSubscriptionStatus.CONTENT_TYPE_INVALID), outcome)
    }

    @Test fun `not due refresh never touches the network`() = runBlocking {
        val transport = FakeTransport()
        val store = HubSubscriptionStore(InMemoryHubSubscriptionPersistence())
        store.recordAttempt(1_000L)
        val outcome = coordinator(transport, RecordingSink(), store).refresh(force = false, nowMs = 1_500L)
        assertEquals(HubRefreshOutcome.Skipped("NOT_DUE"), outcome)
        assertEquals(0, transport.calls)
    }

    // ---------- 辅助 ----------

    private fun fetched(schema: Int): HubFetchOutcome {
        val payload = PAYLOAD.replace("\"hub_schema\": 2", "\"hub_schema\": $schema")
        val parsed = HubSubscriptionParser.parse(payload) as HubParseOutcome.Parsed
        return HubFetchOutcome.Fetched(payload, parsed.subscription, "\"etag\"", parsed.skippedChannels)
    }

    private fun coordinator(
        transport: HubHttpTransport,
        sink: HubCatalogSink,
        store: HubSubscriptionStore,
        aliasCache: ChannelAliasCache? = null,
        onNote: (HubNotice) -> Unit = {},
    ): HubSubscriptionCoordinator = HubSubscriptionCoordinator(
        store = store,
        client = HubSubscriptionClient(
            endpoint = "http://192.168.1.10:8420",
            identity = client,
            transport = transport,
        ),
        sink = sink,
        aliasCache = aliasCache,
        supportedSchema = 2,
        onProtocolNote = onNote,
    )

    private class FakeTransport(private vararg val responses: HubHttpResponse) : HubHttpTransport {
        val observedIfNoneMatch = mutableListOf<String?>()
        var calls = 0
            private set

        override fun get(uri: String, ifNoneMatch: String?): HubHttpResponse {
            observedIfNoneMatch += ifNoneMatch
            val response = responses.getOrElse(calls) { responses.lastOrNull() ?: HubHttpResponse(500) }
            calls++
            return response
        }
    }

    private class RecordingSink : HubCatalogSink {
        val applied = mutableListOf<HubSubscription>()

        override suspend fun applyHubSubscription(
            subscription: HubSubscription,
            startedAtMs: Long,
        ): CatalogImportResult {
            applied += subscription
            return CatalogImportResult.Imported(
                summary = ImportSummary(subscription.channels.size, 0, emptySet()),
                channelCount = subscription.channels.size,
                sourceCount = subscription.channels.sumOf { it.routes.size },
            )
        }
    }

    private companion object {
        val PAYLOAD = """
            {
              "hub_schema": 2,
              "seq": 42,
              "generated_at": "2026-09-11T19:00:00+08:00",
              "server": { "app": "1.3.0", "caps_supported": ["hls-ts","dash-clearkey","web-embed"] },
              "groups": [ { "id": "hk_tw", "title": "🌊港澳台", "order": 4 } ],
              "channels": [
                {
                  "channel_id": "無線新聞台",
                  "display": "无线新闻台",
                  "group_id": "hk_tw",
                  "logo": "https://example.invalid/logo.png",
                  "epg_id": "無線新聞台",
                  "coverage": "ok",
                  "routes": [
                    {
                      "route": "hls-ts",
                      "url": "https://example.invalid/news.m3u8?sig=A&token=B",
                      "container": "ts",
                      "quality": "1080p",
                      "score": 92,
                      "verified_at": "2026-09-11T18:30:00+08:00"
                    }
                  ],
                  "fallback": { "route": "web-embed", "url": "https://news.tvb.com/tc/live/83" }
                }
              ],
              "aliases": [ { "name": "无线新闻", "channel_id": "無線新聞台" } ]
            }
        """.trimIndent()
    }
}
