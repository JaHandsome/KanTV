package com.kantv.app.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfficialWebStreamSnifferTest {
    private val videoMaster = "#EXTM3U\n" +
        "#EXT-X-STREAM-INF:CODECS=\"avc1.4d0020,mp4a.40.2\"\n720/index.m3u8\n"

    @Test fun firstVerifiedCandidateIsReportedExactlyOnce() = runTest {
        val reported = mutableListOf<VerifiedStream>()
        val gate = SniffedStreamGate { _, _ -> videoMaster }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sniffer = OfficialWebStreamSniffer(
            scope = this,
            gate = gate,
            nowMs = { 1_000L },
            verifyContext = dispatcher,
            deliverContext = dispatcher,
            onVerified = { reported += it },
        )
        sniffer.start()
        assertTrue(sniffer.offer("https://cdn.example.com/master.m3u8", mapOf("Referer" to "https://p.com/"), false))
        // 同 URL 重复出现（live 刷新）不重复验证；第二个不同 URL 也不应再上报
        assertFalse(sniffer.offer("https://cdn.example.com/master.m3u8", null, false))
        sniffer.offer("https://cdn2.example.com/other.m3u8", null, false)
        assertEquals(1, reported.size)
        assertEquals("https://cdn.example.com/master.m3u8", reported.first().uri)
    }

    @Test fun nonCandidateUrlsAreNotConsumed() = runTest {
        val sniffer = OfficialWebStreamSniffer(
            scope = this,
            gate = SniffedStreamGate { _, _ -> videoMaster },
            nowMs = { 0L },
            deliverContext = UnconfinedTestDispatcher(testScheduler),
            onVerified = {},
        )
        sniffer.start()
        assertFalse(sniffer.offer("https://cdn.example.com/seg0.ts", null, false))
        assertFalse(sniffer.offer("https://tv.example.com/", null, true))
    }

    @Test fun stopSuppressesFurtherReports() = runTest {
        val reported = mutableListOf<VerifiedStream>()
        val sniffer = OfficialWebStreamSniffer(
            scope = this,
            gate = SniffedStreamGate { _, _ -> videoMaster },
            nowMs = { 0L },
            deliverContext = UnconfinedTestDispatcher(testScheduler),
            onVerified = { reported += it },
        )
        sniffer.start()
        sniffer.stop()
        sniffer.offer("https://cdn.example.com/master.m3u8", null, false)
        assertTrue(reported.isEmpty())
    }

    @Test fun expiredWindowRejectsNewCandidates() = runTest {
        var now = 0L
        val sniffer = OfficialWebStreamSniffer(
            scope = this,
            gate = SniffedStreamGate { _, _ -> videoMaster },
            nowMs = { now },
            deliverContext = UnconfinedTestDispatcher(testScheduler),
            onVerified = {},
        )
        sniffer.start()
        now = OfficialWebSniffPolicy.SNIFF_WINDOW_MS + 1
        assertFalse(sniffer.offer("https://cdn.example.com/master.m3u8", null, false))
    }
}
