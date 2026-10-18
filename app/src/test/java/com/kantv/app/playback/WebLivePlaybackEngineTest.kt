package com.kantv.app.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebLivePlaybackEngineTest {
    private val page = PlaybackRequest("finance", "https://news.tvb.com/tc/live/F")

    @Test fun recognizesOnlyOfficialLivePageRoutes() {
        assertEquals("F", TvbLiveResolver.channelId(page.uri))
        assertEquals("C", TvbLiveResolver.channelId("https://news.tvb.com/sc/live/C/?x=1"))
        listOf("https://news.tvb.com.evil.test/tc/live/F", "https://news.tvb.com@evil.test/tc/live/F",
            "http://news.tvb.com/tc/live/F", "https://news.tvb.com:123/tc/live/F",
            "https://news.tvb.com/tc/live/F/more", "https://news.tvb.com/tc/live/%2fF").forEach {
            assertNull(it, TvbLiveResolver.channelId(it))
        }
    }

    @Test fun resolvedHlsRemainsAMedia3FirstCandidate() {
        val resolved = tvbResolvedHlsRequest(page, "https://media.example.invalid/live.m3u8")

        assertNull(resolved.preferredKernel)
        assertEquals("application/x-mpegURL", resolved.mimeType)
        assertEquals("https://media.example.invalid/live.m3u8", resolved.uri)
    }

    @Test fun reopenRefreshesUrlAndRejectsOldCallbacks() = runTest {
        val backend = Backend()
        var calls = 0
        val resolver = Resolver { it.copy(uri = "https://video.test/${++calls}.m3u8") }
        val engine = WebLivePlaybackEngine(backend, resolver, backgroundScope)
        val events = mutableListOf<PlaybackEvent>()
        engine.setEventListener(events::add)
        engine.play(42, page)
        runCurrent()
        val firstId = backend.plays.single().first
        engine.reopen(42)
        runCurrent()
        val second = backend.plays.last()
        assertEquals(2, calls)
        assertEquals("https://video.test/2.m3u8", second.second.uri)
        events.clear()
        backend.listener(PlaybackEvent.FirstFrame(firstId))
        assertTrue(events.isEmpty())
        backend.listener(PlaybackEvent.FirstFrame(second.first))
        assertEquals(listOf(PlaybackEvent.FirstFrame(42)), events)
    }

    @Test fun lateResolutionCannotReplaceNewChannel() = runTest {
        val backend = Backend()
        val deferred = CompletableDeferred<PlaybackRequest>()
        val engine = WebLivePlaybackEngine(backend, Resolver {
            withContext(NonCancellable) { deferred.await() }
        }, backgroundScope)
        engine.play(1, page)
        runCurrent()
        val direct = PlaybackRequest("other", "https://video.test/live.m3u8")
        engine.play(2, direct)
        deferred.complete(page.copy(uri = "https://video.test/old.m3u8"))
        runCurrent()
        assertEquals(listOf(direct), backend.plays.map { it.second })
    }

    @Test fun pausedResolutionWaitsForResumeAndStopDiscardsIt() = runTest {
        val backend = Backend()
        val engine = WebLivePlaybackEngine(backend, Resolver { it.copy(uri = "https://video.test/live.m3u8") }, backgroundScope)
        engine.play(1, page)
        engine.pause()
        runCurrent()
        assertTrue(backend.plays.isEmpty())
        engine.resume()
        assertEquals(1, backend.plays.size)
        engine.play(2, page)
        engine.pause()
        runCurrent()
        engine.stop()
        engine.resume()
        assertEquals(1, backend.plays.size)
    }

    @Test fun failedResolutionReportsSafeClassifiedError() = runTest {
        val backend = Backend()
        val engine = WebLivePlaybackEngine(backend, Resolver { throw WebLiveResolutionException(PlaybackErrorKind.AUTHORIZATION) }, backgroundScope)
        val events = mutableListOf<PlaybackEvent>()
        engine.setEventListener(events::add)
        engine.play(4, page)
        runCurrent()
        assertEquals(PlaybackEvent.Error(4, PlaybackErrorKind.AUTHORIZATION, -10_010), events.last())
        assertTrue(backend.plays.isEmpty())
    }

    private class Resolver(val resolveBlock: suspend (PlaybackRequest) -> PlaybackRequest) : WebLiveResolver {
        override fun supports(uri: String) = TvbLiveResolver.channelId(uri) != null
        override suspend fun resolve(request: PlaybackRequest) = resolveBlock(request)
    }
    private class Backend : PlaybackEngine {
        var listener: (PlaybackEvent) -> Unit = {}
        val plays = mutableListOf<Pair<Long, PlaybackRequest>>()
        override fun setEventListener(listener: (PlaybackEvent) -> Unit) { this.listener = listener }
        override fun play(sessionId: Long, request: PlaybackRequest) { plays += sessionId to request }
        override fun reopen(sessionId: Long) = Unit
        override fun recoverToLive(sessionId: Long) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
        override fun release() = Unit
    }
}
