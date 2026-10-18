package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePlaybackEngineTest {
    @Test
    fun defaultsToMedia3AndTranslatesThePrivateSessionId() {
        val media3 = RecordingEngine()
        val events = mutableListOf<PlaybackEvent>()
        val engine = AdaptivePlaybackEngine({ media3 }, InMemoryProfileStore())
        engine.setEventListener(events::add)

        engine.play(42L, request())
        val privateSession = media3.played.single().first
        media3.emit(PlaybackEvent.FirstFrame(privateSession))

        assertTrue(privateSession != 42L)
        assertEquals(PlaybackEvent.FirstFrame(42L), events.last())
        assertEquals(
            PlaybackTechnicalUpdate.KernelSelected(PlaybackKernel.MEDIA3, KernelSelectionReason.DEFAULT),
            events.filterIsInstance<PlaybackEvent.Technical>().first().update,
        )
    }

    @Test
    fun tryAlternativeAlwaysReturnsFalseInSingleKernelMode() {
        val media3 = RecordingEngine()
        val engine = AdaptivePlaybackEngine({ media3 }, InMemoryProfileStore())
        engine.play(42L, request())

        assertFalse(engine.tryAlternative(42L, PlaybackErrorKind.DECODER, 7))
        assertFalse(engine.tryAlternative(42L, PlaybackErrorKind.UNSUPPORTED_FORMAT, 10))
        assertFalse(engine.tryAlternative(42L, PlaybackErrorKind.TEMPORARY_NETWORK, -10_003))
        assertFalse(engine.tryAlternative(42L, PlaybackErrorKind.OFFLINE, 1))
        assertFalse(engine.tryAlternative(42L, PlaybackErrorKind.AUTHORIZATION, 403))
        assertFalse(engine.tryAlternative(41L, PlaybackErrorKind.DECODER, 7))
        assertEquals(1, media3.played.size)
        assertEquals(0, media3.releaseCount)
    }

    @Test
    fun stablePlaybackIsRecordedAsLearnedPreferenceEvidence() {
        val media3 = RecordingEngine()
        val store = InMemoryProfileStore()
        val engine = AdaptivePlaybackEngine({ media3 }, store)
        engine.play(42L, request())
        engine.onPlaybackStable(42L)

        assertEquals(PlaybackKernel.MEDIA3, store.lastSuccessfulKernel)

        val secondEvents = mutableListOf<PlaybackEvent>()
        val second = AdaptivePlaybackEngine({ RecordingEngine() }, store)
        second.setEventListener(secondEvents::add)
        second.play(77L, request())
        assertEquals(
            PlaybackTechnicalUpdate.KernelSelected(PlaybackKernel.MEDIA3, KernelSelectionReason.LEARNED_PREFERENCE),
            secondEvents.filterIsInstance<PlaybackEvent.Technical>().first().update,
        )
    }

    @Test
    fun reopenUsesANewPrivateSessionAndDropsEventsFromTheOldOpen() {
        val media3 = RecordingEngine()
        val events = mutableListOf<PlaybackEvent>()
        val engine = AdaptivePlaybackEngine({ media3 }, InMemoryProfileStore())
        engine.setEventListener(events::add)
        engine.play(42L, request())
        val oldPrivateSession = media3.played.single().first

        engine.reopen(42L)
        val newPrivateSession = media3.played.last().first
        media3.emit(PlaybackEvent.FirstFrame(oldPrivateSession))
        media3.emit(PlaybackEvent.FirstFrame(newPrivateSession))

        assertTrue(oldPrivateSession != newPrivateSession)
        assertEquals(PlaybackEvent.FirstFrame(42L), events.last())
        assertEquals(1, events.count { it is PlaybackEvent.FirstFrame })
        val generations = events.filterIsInstance<PlaybackEvent.Technical>()
            .map { it.update }.filterIsInstance<PlaybackTechnicalUpdate.RuntimeGeneration>()
        assertEquals(listOf(oldPrivateSession, newPrivateSession), generations.map { it.generation })
        assertTrue(generations.none { it.generation == 42L })
    }

    @Test
    fun fingerprintChangesWhenHeadersChangeWithoutContainingTheHeaderValue() {
        val first = SourcePlaybackProfileStore.fingerprint(request(headers = mapOf("Authorization" to "secret-a")))
        val second = SourcePlaybackProfileStore.fingerprint(request(headers = mapOf("Authorization" to "secret-b")))

        assertTrue(first != second)
        assertFalse(first.contains("secret-a"))
    }

    @Test
    fun fingerprintFollowsTheSourceEvenWhenChannelIdentityChanges() {
        val first = SourcePlaybackProfileStore.fingerprint(request())
        val second = SourcePlaybackProfileStore.fingerprint(request().copy(channelId = "renamed-channel"))

        assertEquals(first, second)
    }

    @Test
    fun trackControlsRouteOnlyToCurrentSession() {
        val media3 = RecordingEngine()
        val engine = AdaptivePlaybackEngine({ media3 }, InMemoryProfileStore())
        engine.play(42L, request())
        assertFalse(engine.selectTrack(41L, PlaybackTrackKind.AUDIO, "old"))
        assertTrue(engine.selectTrack(42L, PlaybackTrackKind.AUDIO, "first"))
        assertEquals(Triple(media3.played.last().first, PlaybackTrackKind.AUDIO, "first"), media3.selections.single())
        engine.stop()
        assertFalse(engine.selectTrack(42L, PlaybackTrackKind.AUDIO, "late"))
    }

    @Test
    fun defaultNativeOpenCreatesOnlyMedia3() {
        var media3Created = 0
        val engine = AdaptivePlaybackEngine(
            media3Factory = PlaybackEngineFactory { media3Created++; RecordingEngine() },
            profileStore = InMemoryProfileStore(),
        )

        engine.play(42L, request())
        assertEquals(1, media3Created)
    }

    @Test
    fun sourceFallbackKeepsExternalSessionAndChangesRuntimeGeneration() {
        var media3Created = 0
        val media3 = RecordingEngine()
        val events = mutableListOf<PlaybackEvent>()
        val engine = AdaptivePlaybackEngine(
            media3Factory = PlaybackEngineFactory { media3Created++; media3 },
            profileStore = InMemoryProfileStore(),
        )
        engine.setEventListener(events::add)

        engine.play(42L, request())
        engine.play(42L, request().copy(uri = "https://example.invalid/backup.m3u8"))

        assertEquals(listOf(42L, 42L), events.filterIsInstance<PlaybackEvent.Loading>().map { it.sessionId })
        val generations = events.filterIsInstance<PlaybackEvent.Technical>()
            .map { it.update }.filterIsInstance<PlaybackTechnicalUpdate.RuntimeGeneration>()
            .map { it.generation }
        assertEquals(2, generations.distinct().size)
        assertTrue(generations.none { it == 42L })
        assertEquals(2, media3.played.size)
        assertEquals(1, media3Created)
        assertEquals(0, media3.releaseCount)
        assertEquals(1, engine.retainedEngineCountForTest())
    }

    @Test
    fun releasedEngineIsNotReusedAcrossOpens() {
        val media3 = RecordingEngine()
        val engine = AdaptivePlaybackEngine({ media3 }, InMemoryProfileStore())
        engine.play(42L, request())
        engine.release()
        engine.release()

        assertEquals(1, media3.releaseCount)
        val failure = runCatching {
            engine.play(43L, request())
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("Playback engine has been released", failure?.message)
    }

    private fun request(headers: Map<String, String> = emptyMap()) = PlaybackRequest(
        channelId = "news",
        uri = "https://example.invalid/live.m3u8",
        requestHeaders = headers,
    )
}

private class RecordingEngine : PlaybackEngine {
    private var listener: (PlaybackEvent) -> Unit = {}
    val played = mutableListOf<Pair<Long, PlaybackRequest>>()
    val selections = mutableListOf<Triple<Long, PlaybackTrackKind, String?>>()
    var stopCount = 0
    var releaseCount = 0
    private var released = false
    override fun selectTrack(sessionId: Long, kind: PlaybackTrackKind, selectionId: String?): Boolean {
        selections += Triple(sessionId, kind, selectionId)
        return true
    }

    override fun setEventListener(listener: (PlaybackEvent) -> Unit) { this.listener = listener }
    override fun play(sessionId: Long, request: PlaybackRequest) {
        check(!released) { "reused released test engine" }
        played += sessionId to request
    }
    override fun reopen(sessionId: Long) = Unit
    override fun recoverToLive(sessionId: Long) = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun stop() { stopCount++ }
    override fun release() { releaseCount++; released = true }
    fun emit(event: PlaybackEvent) = listener(event)
}

private class InMemoryProfileStore : SourcePlaybackProfileStore {
    private val profiles = mutableMapOf<String, SourcePlaybackProfile>()
    var failureCount = 0
    var lastSuccessfulKernel: PlaybackKernel? = null

    override fun profileFor(sourceFingerprint: String): SourcePlaybackProfile? = profiles[sourceFingerprint]

    override fun recordSuccess(sourceFingerprint: String, kernel: PlaybackKernel) {
        val previous = profiles[sourceFingerprint] ?: SourcePlaybackProfile(null, 0, 0)
        profiles[sourceFingerprint] = previous.copy(
            preferredKernel = kernel,
            media3SuccessCount = previous.media3SuccessCount + if (kernel == PlaybackKernel.MEDIA3) 1 else 0,
        )
        lastSuccessfulKernel = kernel
    }

    override fun recordFailure(sourceFingerprint: String, kernel: PlaybackKernel) {
        val previous = profiles[sourceFingerprint] ?: SourcePlaybackProfile(null, 0, 0)
        profiles[sourceFingerprint] = previous.copy(
            media3FailureCount = previous.media3FailureCount + if (kernel == PlaybackKernel.MEDIA3) 1 else 0,
        )
        failureCount++
    }
}
