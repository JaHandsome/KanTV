package com.kantv.app.diagnostics

import com.kantv.app.catalog.FeedbackEngine
import com.kantv.app.catalog.FeedbackFailReason
import com.kantv.app.catalog.FeedbackOutcome
import com.kantv.app.catalog.HubClientIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约 §4 的回流：序列化正确性、节流、以及**脱敏**（上报体不含原始 URL）。
 */
class PlaybackFeedbackTest {
    private val identity = HubClientIdentity(client = "kantv", app = "0.1.8", device = "tcl-q10m-pro")
    private val urlKey = "0123456789abcdef0123456789abcdef01234567"

    // ---------- 采集 ----------

    @Test fun `attributed session with a first frame is reported as ok`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L }, zoneOffsetMinutes = { 480 })
        collector.begin(session(), nowMs = 1_000L)
        collector.onFirstFrame(nowMs = 2_820L)
        val result = requireNotNull(collector.finish(nowMs = 301_000L))
        assertEquals(FeedbackOutcome.OK, result.outcome)
        assertEquals(1_820L, result.firstFrameMs)
        assertEquals(300L, result.playSeconds)
        assertEquals(0, result.stalls)
        assertNull(result.failReason)
        assertEquals("無線新聞台", result.channelId)
    }

    @Test fun `stalls without failure are reported as partial`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFirstFrame(nowMs = 1_000L)
        collector.onStallStarted(nowMs = 10_000L)
        collector.onStallEnded(nowMs = 13_000L)
        val result = requireNotNull(collector.finish(nowMs = 60_000L))
        assertEquals(FeedbackOutcome.PARTIAL, result.outcome)
        assertEquals(1, result.stalls)
        assertEquals(3L, result.stallSeconds)
    }

    // ---------- 反复短缓冲（2026-09-24 极光盒子实例：报成 ok 的盲区） ----------
    // 现场节奏：每 15~20 秒缓冲一次、每次 1.5~2.5 秒；每次都能自行恢复，
    // 因此既没有恢复动作（stalls=0），单段也够不到 6 秒冻结阈值。

    @Test fun `repeated short rebuffers are never reported as ok`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFirstFrame(nowMs = 2_000L)
        var t = 2_000L
        while (t < 600_000L) {            // 10 分钟：约 35 次 2 秒级缓冲
            collector.onRebufferCounted(2_000L)
            t += 17_000L
        }
        val result = requireNotNull(collector.finish(nowMs = t))
        assertEquals(FeedbackOutcome.PARTIAL, result.outcome)
        assertNull(result.failReason)
        assertEquals(2_000L, result.firstFrameMs)
    }

    @Test fun `rebuffer count alone can make the outcome partial`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFirstFrame(nowMs = 1_000L)
        repeat(PlaybackFeedbackCollector.REBUFFER_COUNT_LIMIT - 1) { collector.onRebufferCounted(1_000L) }
        assertEquals(
            "第 5 次之前不该改判",
            FeedbackOutcome.OK,
            requireNotNull(collector.finish(nowMs = 60_000L)).outcome,
        )

        val collector2 = PlaybackFeedbackCollector(nowMs = { 0L })
        collector2.begin(session(), nowMs = 0L)
        collector2.onFirstFrame(nowMs = 1_000L)
        repeat(PlaybackFeedbackCollector.REBUFFER_COUNT_LIMIT) { collector2.onRebufferCounted(1_000L) }
        assertEquals(FeedbackOutcome.PARTIAL, requireNotNull(collector2.finish(nowMs = 60_000L)).outcome)
    }

    @Test fun `rebuffer duration alone can make the outcome partial`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFirstFrame(nowMs = 1_000L)
        // 次数没到门槛，累计时长到了：4 × 6 秒 = 24 秒 ≥ 20 秒
        repeat(4) { collector.onRebufferCounted(6_000L) }
        val result = requireNotNull(collector.finish(nowMs = 300_000L))
        assertEquals(FeedbackOutcome.PARTIAL, result.outcome)
    }

    @Test fun `a few isolated rebuffers stay ok`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFirstFrame(nowMs = 1_000L)
        repeat(3) { collector.onRebufferCounted(2_000L) }     // 3 次、共 6 秒
        val result = requireNotNull(collector.finish(nowMs = 1_800_000L))
        assertEquals(FeedbackOutcome.OK, result.outcome)
    }

    @Test fun `repeated rebuffers add no new wire fields and leave the stall counters alone`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFirstFrame(nowMs = 1_000L)
        repeat(9) { collector.onRebufferCounted(2_000L) }
        val result = requireNotNull(collector.finish(nowMs = 200_000L))
        assertEquals(FeedbackOutcome.PARTIAL, result.outcome)
        // 契约 §4 的字段集不变：短缓冲不改写既有停帧字段。
        assertEquals(0, result.stalls)
        assertEquals(0L, result.stallSeconds)
        val body = FeedbackPayloadEncoder.encode(
            session = "s", client = "kantv", app = "0.2.21", deviceModel = "tcl",
            androidApi = 34, abi = "arm64-v8a", reportedAt = "now", results = listOf(result),
        )
        assertTrue(body.contains("\"outcome\":\"partial\""))
        assertTrue(body.contains("\"stalls\":0"))
        assertFalse(body.contains("rebuffer"))
    }

    @Test fun `a failure outranks a rebuffering session`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFirstFrame(nowMs = 1_000L)
        repeat(8) { collector.onRebufferCounted(2_000L) }
        collector.onFailure(FeedbackFailReason.NETWORK, nowMs = 100_000L)
        val result = requireNotNull(collector.finish(nowMs = 100_000L))
        assertEquals(FeedbackOutcome.FAIL, result.outcome)
        assertEquals(FeedbackFailReason.NETWORK, result.failReason)
    }

    @Test fun `rebuffer counters belong to one attachment only`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFirstFrame(nowMs = 0L)
        repeat(5) { collector.onRebufferCounted(4_000L) }      // 5 次、共 20 秒 → 两条门槛都到
        assertEquals(FeedbackOutcome.PARTIAL, requireNotNull(collector.finish(nowMs = 60_000L)).outcome)

        // 换线：上一条线的缓冲不能把新线路判成 partial。
        collector.begin(session().copy(urlKey = "aa" + urlKey.drop(2)), nowMs = 60_000L)
        collector.onFirstFrame(nowMs = 61_000L)
        val second = requireNotNull(collector.finish(nowMs = 120_000L))
        assertEquals(FeedbackOutcome.OK, second.outcome)
    }

    @Test fun `a failure is reported with a fixed reason`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFailure(FeedbackFailReason.DRM_ERROR, nowMs = 5_000L)
        val result = requireNotNull(collector.finish(nowMs = 5_000L))
        assertEquals(FeedbackOutcome.FAIL, result.outcome)
        assertEquals(FeedbackFailReason.DRM_ERROR, result.failReason)
    }

    @Test fun `unattributed channel is never reported`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session().copy(channelId = null), nowMs = 0L)
        collector.onFirstFrame(nowMs = 1_000L)
        assertNull(collector.finish(nowMs = 2_000L))
        collector.begin(session().copy(channelId = "  "), nowMs = 0L)
        assertNull(collector.finish(nowMs = 1_000L))
    }

    @Test fun `session without url key is never reported`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session().copy(urlKey = ""), nowMs = 0L)
        assertNull(collector.finish(nowMs = 1_000L))
    }

    @Test fun `finish is idempotent`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFirstFrame(nowMs = 500L)
        assertEquals(1, listOfNotNull(collector.finish(nowMs = 1_000L)).size)
        assertNull(collector.finish(nowMs = 2_000L))
    }

    // ---------- 序列化与脱敏 ----------

    @Test fun `collector schema constant tracks the build config value`() {
        // 纯模块常量必须与 BuildConfig.HUB_SCHEMA 保持一致（hub_schema 2 = v1.1 语义）
        assertEquals(com.kantv.app.BuildConfig.HUB_SCHEMA, FeedbackPayloadEncoder.HUB_SCHEMA)
        assertEquals(2, FeedbackPayloadEncoder.HUB_SCHEMA)
    }

    @Test fun `payload matches the contract field names`() {
        val body = FeedbackPayloadEncoder.encode(
            session = "11111111-2222-3333-4444-555555555555",
            client = identity.client,
            app = identity.app,
            deviceModel = identity.device,
            androidApi = 30,
            abi = "arm64-v8a",
            reportedAt = "2026-09-11T19:05:00+08:00",
            results = listOf(result()),
        )
        listOf(
            "\"hub_schema\":${FeedbackPayloadEncoder.HUB_SCHEMA}", "\"client\":\"kantv\"", "\"app\":\"0.1.8\"",
            "\"model\":\"tcl-q10m-pro\"", "\"android_api\":30", "\"abi\":\"arm64-v8a\"",
            "\"session\":\"11111111-2222-3333-4444-555555555555\"",
            "\"reported_at\":\"2026-09-11T19:05:00+08:00\"",
            "\"channel_id\":\"無線新聞台\"", "\"url_key\":\"$urlKey\"",
            "\"route\":\"hls-ts\"", "\"outcome\":\"ok\"", "\"engine\":\"media3\"",
            "\"first_frame_ms\":1820", "\"stalls\":0", "\"stall_seconds\":0", "\"play_seconds\":300",
            "\"fail_reason\":null", "\"observed_at\":\"2026-09-11T19:00:00+08:00\"",
        ).forEach { fragment -> assertTrue("缺少 $fragment", body.contains(fragment)) }
    }

    @Test fun `payload never contains a raw url, headers or credentials`() {
        val body = FeedbackPayloadEncoder.encode(
            session = "s",
            client = identity.client,
            app = identity.app,
            deviceModel = identity.device,
            androidApi = 30,
            abi = "arm64-v8a",
            reportedAt = "2026-09-11T19:05:00+08:00",
            results = listOf(result()),
        )
        assertFalse(body.contains("http://"))
        assertFalse(body.contains("https://"))
        assertFalse(body.contains(".m3u8"))
        assertFalse(body.contains(".mpd"))
        assertFalse(body.contains("token"))
        assertFalse(body.contains("Authorization"))
        assertFalse(body.contains("Bearer"))
        assertFalse(body.contains("Referer"))
        assertFalse(body.contains("User-Agent"))
        // url_key 是唯一与线路有关的字段，且必须是 40 位小写十六进制哈希
        assertTrue(body.contains(urlKey))
    }

    @Test fun `fail reason uses the contract vocabulary`() {
        val body = FeedbackPayloadEncoder.encode(
            session = "s",
            client = identity.client,
            app = identity.app,
            deviceModel = identity.device,
            androidApi = 30,
            abi = "arm64-v8a",
            reportedAt = "now",
            results = listOf(result().copy(outcome = FeedbackOutcome.FAIL, failReason = FeedbackFailReason.HTTP_403)),
        )
        assertTrue(body.contains("\"outcome\":\"fail\""))
        assertTrue(body.contains("\"fail_reason\":\"http_403\""))
    }

    @Test fun `timestamps are rendered with an explicit offset`() {
        assertEquals("1970-01-01T00:00:00+00:00", HubTimestamps.iso8601(0L, 0))
        assertEquals("1970-01-01T08:00:00+08:00", HubTimestamps.iso8601(0L, 480))
        assertEquals("1969-12-31T16:00:00-08:00", HubTimestamps.iso8601(0L, -480))
    }

    // ---------- 节流 ----------

    @Test fun `failure is flushed immediately`() {
        assertTrue(FeedbackThrottlePolicy.shouldFlushNow(immediate = true, pendingCount = 1, lastFlushAtMs = 0L, nowMs = 1L))
        // 只有 notice、没有 result 时同样必须立即发
        assertTrue(FeedbackThrottlePolicy.shouldFlushNow(immediate = true, pendingCount = 0, lastFlushAtMs = 0L, nowMs = 1L))
    }

    @Test fun `successful results are aggregated until the batch limit or interval`() {
        assertTrue(FeedbackThrottlePolicy.shouldFlushNow(false, FeedbackThrottlePolicy.BATCH_MAX_RESULTS, 1L, 2L))
        assertFalse(FeedbackThrottlePolicy.shouldFlushNow(false, 1, lastFlushAtMs = 1_000L, nowMs = 2_000L))
        assertTrue(
            FeedbackThrottlePolicy.shouldFlushNow(
                false,
                1,
                lastFlushAtMs = 1_000L,
                nowMs = 1_000L + FeedbackThrottlePolicy.BATCH_INTERVAL_MS,
            ),
        )
        assertFalse(FeedbackThrottlePolicy.shouldFlushNow(false, 0, 0L, 0L))
    }

    // ---------- 队列与上报 ----------

    @Test fun `queue round trips and flags failures`() {
        val queue = PlaybackFeedbackQueue(InMemoryFeedbackQueuePersistence())
        queue.enqueue(result())
        queue.enqueue(result().copy(outcome = FeedbackOutcome.FAIL, failReason = FeedbackFailReason.NETWORK))
        assertEquals(2, queue.size())
        assertTrue(queue.hasPendingFailure())
        queue.removeAll(queue.pending())
        assertEquals(0, queue.size())
        assertFalse(queue.hasPendingFailure())
    }

    @Test fun `queue persists across instances`() {
        val persistence = InMemoryFeedbackQueuePersistence()
        PlaybackFeedbackQueue(persistence).enqueue(result())
        val reloaded = PlaybackFeedbackQueue(persistence)
        assertEquals(1, reloaded.size())
        assertEquals(urlKey, reloaded.pending().single().urlKey)
        assertEquals(FeedbackOutcome.OK, reloaded.pending().single().outcome)
    }

    @Test fun `invalid token skips the upload and keeps the queue`() {
        val queue = PlaybackFeedbackQueue(InMemoryFeedbackQueuePersistence())
        queue.enqueue(result())
        val transport = RecordingTransport(202)
        val outcome = uploader(transport, token = "short").flush(queue, force = true)
        assertTrue(outcome is FeedbackFlushOutcome.Skipped)
        assertEquals(1, queue.size())
        assertEquals(0, transport.calls)
    }

    @Test fun `accepted response drains the batch`() {
        val queue = PlaybackFeedbackQueue(InMemoryFeedbackQueuePersistence())
        queue.enqueue(result())
        queue.enqueue(result())
        val transport = RecordingTransport(202)
        val outcome = uploader(transport, token = VALID_TOKEN).flush(queue, force = true)
        assertEquals(FeedbackFlushOutcome.Uploaded(2, 0), outcome)
        assertEquals(0, queue.size())
    }

    @Test fun `rejected response keeps the batch for retry`() {
        val queue = PlaybackFeedbackQueue(InMemoryFeedbackQueuePersistence())
        queue.enqueue(result())
        val transport = RecordingTransport(500)
        val outcome = uploader(transport, token = VALID_TOKEN).flush(queue, force = true)
        assertEquals(FeedbackFlushOutcome.Rejected("HTTP_500"), outcome)
        assertEquals(1, queue.size())
    }

    @Test fun `throttled upload does not touch the network`() {
        val queue = PlaybackFeedbackQueue(InMemoryFeedbackQueuePersistence())
        queue.enqueue(result())
        val transport = RecordingTransport(202)
        val outcome = uploader(transport, token = VALID_TOKEN, lastFlushAtMs = { 1_000L })
            .flush(queue, force = false)
        assertTrue(outcome is FeedbackFlushOutcome.Skipped)
        assertEquals(0, transport.calls)
        assertEquals(1, queue.size())
    }

    @Test fun `batch never exceeds the configured maximum`() {
        val queue = PlaybackFeedbackQueue(InMemoryFeedbackQueuePersistence())
        repeat(FeedbackThrottlePolicy.BATCH_MAX_RESULTS + 3) { queue.enqueue(result()) }
        val transport = RecordingTransport(202)
        val outcome = uploader(transport, token = VALID_TOKEN).flush(queue, force = true)
        assertEquals(FeedbackFlushOutcome.Uploaded(FeedbackThrottlePolicy.BATCH_MAX_RESULTS, 0), outcome)
        assertEquals(3, queue.size())
    }

    // ---------- notices（v1.1 §4 加法字段） ----------

    @Test fun `notices are serialised with the contract shape`() {
        val body = FeedbackPayloadEncoder.encode(
            session = "s",
            client = identity.client,
            app = identity.app,
            deviceModel = identity.device,
            androidApi = 30,
            abi = "arm64-v8a",
            reportedAt = "now",
            results = emptyList(),
            notices = listOf(com.kantv.app.catalog.HubNotice(
                com.kantv.app.catalog.HubNotice.CODE_SCHEMA_UNSUPPORTED,
                "client supports 1, server sent 2",
            )),
        )
        assertTrue(body.contains("\"notices\":[{\"code\":\"schema_unsupported\",\"detail\":\"client supports 1, server sent 2\"}]"))
        assertTrue(body.contains("\"results\":[]"))
        assertFalse(body.contains("https://"))
    }

    @Test fun `notices are omitted when there are none so v1 servers stay compatible`() {
        val body = FeedbackPayloadEncoder.encode(
            session = "s",
            client = identity.client,
            app = identity.app,
            deviceModel = identity.device,
            androidApi = 30,
            abi = "arm64-v8a",
            reportedAt = "now",
            results = listOf(result()),
        )
        assertFalse(body.contains("notices"))
    }

    @Test fun `a notice alone is uploaded even without any result`() {
        val queue = PlaybackFeedbackQueue(InMemoryFeedbackQueuePersistence())
        val notices = FeedbackNoticeQueue(InMemoryFeedbackNoticePersistence())
        notices.enqueue(
            com.kantv.app.catalog.HubNotice(com.kantv.app.catalog.HubNotice.CODE_SCHEMA_UNSUPPORTED, "1 vs 2"),
        )
        val transport = RecordingTransport(202)
        val outcome = uploader(transport, token = VALID_TOKEN).flush(queue, notices, force = false)
        assertEquals(FeedbackFlushOutcome.Uploaded(0, 1), outcome)
        assertEquals(0, notices.size())
        assertTrue(requireNotNull(transport.lastBody).contains("schema_unsupported"))
    }

    @Test fun `notices flush immediately without waiting for the batch interval`() {
        val queue = PlaybackFeedbackQueue(InMemoryFeedbackQueuePersistence())
        val notices = FeedbackNoticeQueue(InMemoryFeedbackNoticePersistence())
        notices.enqueue(com.kantv.app.catalog.HubNotice(com.kantv.app.catalog.HubNotice.CODE_OTHER, "x"))
        val transport = RecordingTransport(202)
        // lastFlushAtMs 刚刚发生过：普通成功结果会被限流，但 notice 必须立即上报。
        val outcome = uploader(transport, token = VALID_TOKEN, lastFlushAtMs = { 1_000L })
            .flush(queue, notices, force = false)
        assertEquals(FeedbackFlushOutcome.Uploaded(0, 1), outcome)
        assertEquals(1, transport.calls)
    }

    @Test fun `duplicate notices are collapsed and unknown codes are kept verbatim`() {
        val persistence = InMemoryFeedbackNoticePersistence()
        val notices = FeedbackNoticeQueue(persistence)
        repeat(3) { notices.enqueue(com.kantv.app.catalog.HubNotice(com.kantv.app.catalog.HubNotice.CODE_OTHER, "same")) }
        assertEquals(1, notices.size())
        notices.enqueue(com.kantv.app.catalog.HubNotice("future_code", "unknown but must be forwarded"))
        assertEquals(2, notices.size())
        // 重启后仍能恢复（v1.1 §4：未知 code 服务端必须接受并计数）
        assertEquals(2, FeedbackNoticeQueue(persistence).size())
    }

    private fun uploader(
        transport: FeedbackTransport,
        token: String,
        lastFlushAtMs: () -> Long = { 0L },
    ) = PlaybackFeedbackUploader(
        endpoint = "http://192.168.1.10:8420",
        token = token,
        identity = identity,
        transport = transport,
        sessionId = "11111111-2222-3333-4444-555555555555",
        androidApi = 30,
        abi = "arm64-v8a",
        nowMs = { 1_000L },
        zoneOffsetMinutes = { 480 },
        lastFlushAtMs = lastFlushAtMs,
    )

    // ---------- 冻结口径（契约两端冻结：阈值 6 秒；段长按"画面恢复推进"计） ----------

    @Test fun `steady picture reports no freeze`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFirstFrame(nowMs = 0L)
        for (t in 0L..30_000L step 1_000L) collector.onPictureProgress(nowMs = t)
        val result = requireNotNull(collector.finish(nowMs = 30_000L))
        assertEquals(0L, result.freezeSeconds)
        assertEquals(0, result.freezeSegments)
        assertEquals(0L, result.freezeLongestSeconds)
        assertEquals(0, result.freezeLongSegments)
        assertEquals(31, result.pictureProgressEvents)
        assertEquals(30L, result.activeSeconds)
        assertTrue(result.freezeMeasured)
    }

    @Test fun `gap below the threshold is discarded entirely`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFirstFrame(nowMs = 0L)
        collector.onPictureProgress(nowMs = 1_000L)
        collector.onPictureProgress(nowMs = 5_500L)   // 4.5 秒 < 6 秒阈值 → 整段丢弃
        val result = requireNotNull(collector.finish(nowMs = 6_000L))
        assertEquals(0L, result.freezeSeconds)
        assertEquals(0, result.freezeSegments)
    }

    @Test fun `freeze segment is measured until the picture actually resumes`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFirstFrame(nowMs = 1_000L)
        collector.onPictureProgress(nowMs = 1_000L)
        // 约 13 分钟没有任何画面推进（期间恢复动作会不断触发，但都不结束这一段）。
        collector.onPictureProgress(nowMs = 793_000L)
        val result = requireNotNull(collector.finish(nowMs = 794_000L))
        assertEquals(792L, result.freezeSeconds)
        assertEquals(1, result.freezeSegments)
        assertEquals(792L, result.freezeLongestSeconds)
        assertEquals(1, result.freezeLongSegments)
    }

    @Test fun `freeze that never resumes counts up to the end of the attachment`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFirstFrame(nowMs = 10_000L)
        collector.onPictureProgress(nowMs = 10_000L)
        val result = requireNotNull(collector.finish(nowMs = 70_000L))
        assertEquals(60L, result.freezeSeconds)
        assertEquals(60L, result.freezeLongestSeconds)
        assertEquals(1, result.freezeSegments)
        assertEquals(1, result.freezeLongSegments)
    }

    @Test fun `startup wait before the first frame is not counted as freeze`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        // 起播 40 秒后才出画面：这段时间记为 first_frame_ms，不算冻结。
        collector.onPictureProgress(nowMs = 5_000L)
        collector.onFirstFrame(nowMs = 40_000L)
        collector.onPictureProgress(nowMs = 41_000L)
        val result = requireNotNull(collector.finish(nowMs = 42_000L))
        assertEquals(40_000L, result.firstFrameMs)
        assertEquals(0L, result.freezeSeconds)
        assertEquals(2, result.pictureProgressEvents)
    }

    @Test fun `time while nobody is watching is never counted as freeze`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFirstFrame(nowMs = 1_000L)
        collector.onPictureProgress(nowMs = 1_000L)
        collector.onWatchingStopped(nowMs = 3_000L)    // 熄屏/切后台
        collector.onWatchingStarted(nowMs = 60_000L)   // 回来继续看
        collector.onPictureProgress(nowMs = 61_000L)
        val result = requireNotNull(collector.finish(nowMs = 62_000L))
        assertEquals(0L, result.freezeSeconds)         // 那 57 秒不算冻结
        assertEquals(5L, result.activeSeconds)         // 3 秒 + 2 秒
        assertTrue(result.freezeSeconds <= result.activeSeconds)
    }

    @Test fun `each attachment carries its own freeze totals`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFirstFrame(nowMs = 1_000L)
        collector.onPictureProgress(nowMs = 1_000L)
        val first = requireNotNull(collector.finish(nowMs = 31_000L))
        collector.begin(session().copy(urlKey = "aa" + urlKey.drop(2)), nowMs = 31_000L)
        collector.onFirstFrame(nowMs = 32_000L)
        collector.onPictureProgress(nowMs = 32_000L)
        collector.onPictureProgress(nowMs = 40_000L)
        val second = requireNotNull(collector.finish(nowMs = 41_000L))
        assertEquals(30L, first.freezeSeconds)   // 第一条线：30 秒冻结
        assertEquals(8L, second.freezeSeconds)   // 第二条线：只算它自己的 8 秒
        assertEquals(urlKey, first.urlKey)
    }

    @Test fun `freeze invariants hold on a mixed session`() {
        val collector = PlaybackFeedbackCollector(nowMs = { 0L })
        collector.begin(session(), nowMs = 0L)
        collector.onFirstFrame(nowMs = 1_000L)
        collector.onPictureProgress(nowMs = 1_000L)
        collector.onPictureProgress(nowMs = 2_000L)
        collector.onPictureProgress(nowMs = 60_000L)     // 58 秒冻结（达到 45 秒档）
        collector.onPictureProgress(nowMs = 64_000L)     // 4 秒抖动 → 丢弃
        collector.onPictureProgress(nowMs = 70_000L)     // 6 秒 → 计入
        val result = requireNotNull(collector.finish(nowMs = 71_000L))
        assertEquals(64L, result.freezeSeconds)
        assertEquals(2, result.freezeSegments)
        assertEquals(58L, result.freezeLongestSeconds)
        assertEquals(1, result.freezeLongSegments)
        assertTrue(result.freezeSeconds <= result.activeSeconds)
        assertTrue(result.freezeLongestSeconds <= result.freezeSeconds)
        assertEquals(result.freezeSegments == 0, result.freezeLongestSeconds == 0L)
        assertTrue(result.freezeSegments < 1 || result.freezeLongestSeconds >= 6L)
    }

    @Test fun `payload omits the freeze group for legacy queued results`() {
        val legacy = result()
        val encoded = FeedbackPayloadEncoder.encode(
            session = "s", client = "kantv", app = "0.2.21", deviceModel = "tcl",
            androidApi = 34, abi = "arm64-v8a", reportedAt = "2026-09-18T08:00:00+08:00",
            results = listOf(legacy),
        )
        assertFalse(encoded.contains("freeze_seconds"))
        assertFalse(encoded.contains("active_seconds"))

        val measured = legacy.copy(
            activeSeconds = 60,
            freezeSeconds = 13,
            freezeSegments = 2,
            freezeLongestSeconds = 13,
            freezeLongSegments = 0,
            pictureProgressEvents = 42,
            freezeMeasured = true,
        )
        val encoded2 = FeedbackPayloadEncoder.encode(
            session = "s", client = "kantv", app = "0.2.21", deviceModel = "tcl",
            androidApi = 34, abi = "arm64-v8a", reportedAt = "2026-09-18T08:00:00+08:00",
            results = listOf(measured),
        )
        listOf(
            "\"active_seconds\":60", "\"freeze_seconds\":13", "\"freeze_segments\":2",
            "\"freeze_longest_seconds\":13", "\"freeze_long_segments\":0",
            "\"picture_progress_events\":42",
        ).forEach { assertTrue(it, encoded2.contains(it)) }
    }

    // ---------- 服务端契约自检计数（202 响应体） ----------

    @Test fun `freeze counters are read defensively and never change the outcome`() {
        assertEquals(2, freezeCounter("""{"freeze_ok":5,"freeze_violation":2,"freeze_partial":1}""", "freeze_violation"))
        assertEquals(0, freezeCounter("""{"freeze_ok":5}""", "freeze_violation"))
        assertEquals(0, freezeCounter("not json at all", "freeze_violation"))
        assertEquals(0, freezeCounter("", "freeze_violation"))
        assertEquals(0, freezeCounter("""{"freeze_violation":-3}""", "freeze_violation"))
    }

    @Test fun `uploaded outcome carries the server freeze counters`() {
        val queue = PlaybackFeedbackQueue(InMemoryFeedbackQueuePersistence())
        queue.enqueue(result().copy(freezeMeasured = true))
        val transport = RecordingTransport(
            code = 202,
            responseBody = """{"accepted":1,"freeze_ok":1,"freeze_violation":0,"freeze_partial":0}""",
        )
        val outcome = uploader(transport, VALID_TOKEN).flush(
            queue = queue,
            notices = FeedbackNoticeQueue(InMemoryFeedbackNoticePersistence()),
            force = true,
        )
        assertTrue(outcome is FeedbackFlushOutcome.Uploaded)
        val uploaded = outcome as FeedbackFlushOutcome.Uploaded
        assertEquals(1, uploaded.accepted)
        assertEquals(1, uploaded.freezeOk)
        assertEquals(0, uploaded.freezeViolation)
        assertEquals(0, uploaded.freezePartial)
    }

    @Test fun `optional long-segment field never blocks the five-field group`() {
        val measured = result().copy(
            activeSeconds = 60,
            freezeSeconds = 13,
            freezeSegments = 1,
            freezeLongestSeconds = 13,
            freezeLongSegments = -1,     // 本次取不到 45 秒口径
            pictureProgressEvents = 40,
            freezeMeasured = true,
        )
        val encoded = FeedbackPayloadEncoder.encode(
            session = "s", client = "kantv", app = "0.2.21", deviceModel = "tcl",
            androidApi = 34, abi = "arm64-v8a", reportedAt = "2026-09-18T09:00:00+08:00",
            results = listOf(measured),
        )
        // 成组五个字段照发（否则这批冻结数据会被对端记成"未上报"而静默丢失）
        listOf(
            "\"active_seconds\":60", "\"freeze_seconds\":13", "\"freeze_segments\":1",
            "\"freeze_longest_seconds\":13", "\"picture_progress_events\":40",
        ).forEach { assertTrue(it, encoded.contains(it)) }
        assertFalse("取不到时只省这一个字段", encoded.contains("freeze_long_segments"))
    }

    private class RecordingTransport(
        private val code: Int,
        private val responseBody: String = "",
    ) : FeedbackTransport {
        var calls = 0
            private set
        var lastBody: String? = null
            private set

        override fun post(uri: String, token: String, body: String): FeedbackHttpResponse {
            calls++
            lastBody = body
            return FeedbackHttpResponse(code, responseBody)
        }
    }

    private fun session() = FeedbackSessionKey(
        channelId = "無線新聞台",
        urlKey = urlKey,
        route = "hls-ts",
        engine = FeedbackEngine.MEDIA3,
    )

    private fun result() = PlaybackFeedbackResult(
        channelId = "無線新聞台",
        urlKey = urlKey,
        route = "hls-ts",
        outcome = FeedbackOutcome.OK,
        engine = FeedbackEngine.MEDIA3,
        firstFrameMs = 1820,
        stalls = 0,
        stallSeconds = 0,
        playSeconds = 300,
        failReason = null,
        observedAt = "2026-09-11T19:00:00+08:00",
    )

    private companion object {
        const val VALID_TOKEN = "0123456789abcdef0123456789abcdef01234567"
    }
}
