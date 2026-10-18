package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SniffedStreamGateTest {
    private val videoMaster = "#EXTM3U\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=2500000,CODECS=\"avc1.4d0020,mp4a.40.2\",RESOLUTION=1280x720\n" +
        "720/index.m3u8\n"
    private val audioMaster = "#EXTM3U\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS=\"mp4a.40.2\"\na128/index.m3u8\n"

    @Test fun verifiedStreamCarriesUriMimeAndWhitelistedHeaders() {
        val gate = SniffedStreamGate { url, headers ->
            assertEquals("https://cdn.example.com/master.m3u8", url)
            assertEquals("https://news.example.com/", headers["Referer"])
            assertNull(headers["Cookie"])
            videoMaster
        }
        val verified = gate.verify(
            SniffedCandidate(
                url = "https://cdn.example.com/master.m3u8",
                kind = SniffedStreamKind.HLS,
                headers = mapOf(
                    "Referer" to "https://news.example.com/",
                    "User-Agent" to "Mozilla/5.0",
                    "Cookie" to "secret=1",
                ),
            ),
        )!!
        assertEquals("https://cdn.example.com/master.m3u8", verified.uri)
        assertEquals("application/x-mpegURL", verified.mimeType)
        assertEquals(2, verified.headers.size)
    }

    @Test fun audioOnlyMasterIsRejected() {
        val gate = SniffedStreamGate { _, _ -> audioMaster }
        assertNull(gate.verify(SniffedCandidate("https://fixture-3.example.invalid/a.m3u8", SniffedStreamKind.HLS, emptyMap())))
    }

    @Test fun fetchFailureIsRejected() {
        val gate = SniffedStreamGate { _, _ -> null }
        assertNull(gate.verify(SniffedCandidate("https://fixture-3.example.invalid/a.m3u8", SniffedStreamKind.HLS, emptyMap())))
    }

    @Test fun fetchThrowingIsRejected() {
        val gate = SniffedStreamGate { _, _ -> throw java.io.IOException("timeout") }
        assertNull(gate.verify(SniffedCandidate("https://fixture-3.example.invalid/a.m3u8", SniffedStreamKind.HLS, emptyMap())))
    }

    @Test fun headerWhitelistIsCaseInsensitive() {
        val whitelisted = SniffedStreamGate.whitelistedHeaders(
            mapOf("referer" to "https://fixture-19.example.invalid/", "USER-AGENT" to "UA", "X-Other" to "v"),
        )
        assertEquals(setOf("referer", "USER-AGENT"), whitelisted.keys)
    }
}
