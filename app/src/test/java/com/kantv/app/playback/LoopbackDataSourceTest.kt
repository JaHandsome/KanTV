package com.kantv.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackDataSourceTest {
    @Test
    fun bypassesSystemProxyOnlyForNumericLoopbackHttp() {
        assertTrue(LoopbackDataSource.shouldBypassSystemProxy("http://127.0.0.1:8312/playlist.m3u8"))
        assertTrue(LoopbackDataSource.shouldBypassSystemProxy("http://127.0.0.1:8312/segment/41"))

        listOf(
            "http://127.0.0.1/playlist.m3u8",
            "http://user@127.0.0.1:8312/playlist.m3u8",
            "http://127.0.0.1:8312/stream.ts",
            "https://127.0.0.1/playlist.m3u8",
            "http://localhost/playlist.m3u8",
            "http://[::1]/playlist.m3u8",
            "http://192.168.1.10/playlist.m3u8",
            "https://media.example/live.m3u8",
        ).forEach { uri ->
            assertFalse(uri, LoopbackDataSource.shouldBypassSystemProxy(uri))
        }
    }
}
