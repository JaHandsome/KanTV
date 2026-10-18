package com.kantv.app.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioSink
import com.kantv.app.diagnostics.DiagnosticLogEntry
import com.kantv.app.diagnostics.NativeDiagnosticMapper
import com.kantv.app.diagnostics.toSerializableFields
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservingAudioSinkTest {
    @Test
    fun delegatesSameBufferAndMapsUnavailablePositionWithoutInventingAClock() {
        val observations = mutableListOf<AudioSinkObservation>()
        var delegatedBuffer: ByteBuffer? = null
        val delegate = fakeAudioSink { method, args ->
            when (method) {
                "handleBuffer" -> {
                    delegatedBuffer = args?.get(0) as ByteBuffer
                    true
                }
                "getCurrentPositionUs" -> AudioSink.CURRENT_POSITION_NOT_SET
                else -> defaultValue(method)
            }
        }
        val sink = ObservingAudioSink(delegate, RecordingTarget(observations))
        val buffer = ByteBuffer.allocate(8)

        assertTrue(sink.handleBuffer(buffer, 123_456L, 1))
        assertSame(buffer, delegatedBuffer)
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(false))
        assertEquals(AudioSinkObservation.InputBuffer(123_456L), observations[0])
        assertEquals(
            AudioSinkObservation.Position(-1, ObservationAvailability.UNKNOWN),
            observations[1],
        )
    }

    @Test
    fun passiveOffsetAndFlushObservationPreservesAllDelegateValues() {
        val delegatedOffsets = mutableListOf<Long>()
        val observedOffsets = mutableListOf<Long>()
        var delegatedFlushes = 0
        var observedResets = 0
        val rawPosition = 1_000_080_000_000L
        val delegate = fakeAudioSink { method, args ->
            when (method) {
                "setOutputStreamOffsetUs" -> { delegatedOffsets += args!![0] as Long; null }
                "flush" -> { delegatedFlushes++; null }
                "getCurrentPositionUs" -> rawPosition
                else -> defaultValue(method)
            }
        }
        val sink = ObservingAudioSink(delegate, object : AudioSinkObservationTarget {
            override fun onInputBuffer(presentationTimeUs: Long) = Unit
            override fun onPosition(positionUs: Long, availability: ObservationAvailability) {
                assertEquals(rawPosition, positionUs)
            }
            override fun onImmediate(observation: AudioSinkObservation) = Unit
            override fun onStreamOffset(offsetUs: Long) { observedOffsets += offsetUs }
            override fun onClockReset() { observedResets++ }
        })
        sink.setOutputStreamOffsetUs(-40_000)
        sink.setOutputStreamOffsetUs(1_000_000_000_000)
        sink.flush()
        assertEquals(delegatedOffsets, observedOffsets)
        assertEquals(1, delegatedFlushes)
        assertEquals(1, observedResets)
        assertEquals(rawPosition, sink.getCurrentPositionUs(false))
    }

    @Test
    fun listenerPreservesCallbacksAndCapturesConfigUnderrunDiscontinuityAndRelease() {
        val observations = mutableListOf<AudioSinkObservation>()
        var installedListener: AudioSink.Listener? = null
        var downstreamUnderruns = 0
        val delegate = fakeAudioSink { method, args ->
            if (method == "setListener") installedListener = args?.get(0) as AudioSink.Listener
            defaultValue(method)
        }
        val sink = ObservingAudioSink(delegate, RecordingTarget(observations))
        sink.setListener(object : AudioSink.Listener {
            override fun onPositionDiscontinuity() = Unit
            override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                downstreamUnderruns++
            }
            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = Unit
        })
        val config = AudioSink.AudioTrackConfig(
            C.ENCODING_PCM_16BIT,
            48_000,
            12,
            false,
            false,
            65_536,
        )

        installedListener!!.onAudioTrackInitialized(config)
        installedListener!!.onUnderrun(65_536, 341, 22)
        sink.handleDiscontinuity()
        installedListener!!.onPositionDiscontinuity()
        installedListener!!.onAudioTrackReleased(config)
        sink.release()

        val output = (observations[0] as AudioSinkObservation.OutputConfigured).configuration
        assertEquals(AudioOutputMode.PCM, output.outputMode)
        assertEquals("unknown", output.actualRoute)
        assertEquals(ObservationAvailability.UNKNOWN, output.audioTrackTimestampAvailable)
        assertEquals(-1L, output.audioTrackPlaybackHeadPosition)
        assertEquals(
            AudioUnderrunDetails(65_536, 341, 22),
            (observations[1] as AudioSinkObservation.Underrun).details,
        )
        assertEquals(AudioSinkObservation.Discontinuity, observations[2])
        assertEquals(1, observations.count { it == AudioSinkObservation.Discontinuity })
        assertTrue(observations[3] is AudioSinkObservation.TrackReleased)
        assertEquals(AudioSinkObservation.SinkReleased, observations[4])
        assertEquals(1, downstreamUnderruns)
    }

    @Test
    fun thousandHotCallbacksProduceOneTaskAndKeepOnlyLatestValues() {
        val scheduler = FakeScheduler()
        val dispatched = mutableListOf<PlaybackTechnicalUpdate>()
        val dispatcher = scheduler.dispatcher { _, update -> dispatched += update }
        dispatcher.activate(AudioSinkDispatchToken(7, 3))

        repeat(1_000) { index ->
            dispatcher.onInputBuffer(index.toLong())
            dispatcher.onPosition(index.toLong() * 2, ObservationAvailability.AVAILABLE)
        }

        assertEquals(1, scheduler.delayed.size)
        assertEquals(0, dispatched.size)
        scheduler.runDelayed()
        assertEquals(
            listOf(
                PlaybackTechnicalUpdate.AudioSinkInput(999),
                PlaybackTechnicalUpdate.AudioSinkPosition(1_998, ObservationAvailability.AVAILABLE),
            ),
            dispatched,
        )
    }

    @Test
    fun sessionSwitchAndCloseInvalidateOldPendingWork() {
        val scheduler = FakeScheduler()
        val dispatched = mutableListOf<Pair<AudioSinkDispatchToken, PlaybackTechnicalUpdate>>()
        val dispatcher = scheduler.dispatcher { token, update -> dispatched += token to update }
        val first = AudioSinkDispatchToken(1, 1)
        val second = AudioSinkDispatchToken(2, 2)
        dispatcher.activate(first)
        dispatcher.onInputBuffer(10)
        val staleFlush = scheduler.delayed.single()
        dispatcher.onImmediate(AudioSinkObservation.Discontinuity)
        val staleImmediate = scheduler.immediate.single()

        dispatcher.activate(second)
        staleFlush.run()
        staleImmediate.run()
        assertTrue(dispatched.isEmpty())

        dispatcher.onPosition(20, ObservationAvailability.AVAILABLE)
        val closePending = scheduler.delayed.single()
        dispatcher.close()
        closePending.run()
        dispatcher.onInputBuffer(30)
        dispatcher.onImmediate(AudioSinkObservation.Discontinuity)
        scheduler.runAll()
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun avCoalescingKeepsFixedPairAndRejectsOldFrameAndInvalidationTasks() {
        val scheduler = FakeScheduler()
        var nowNs = 5_000_000_000L
        val dispatched = mutableListOf<PlaybackTechnicalUpdate>()
        val dispatcher = CoalescingAudioSinkDispatcher(
            nanoTime = { nowNs },
            postDelayed = { task, _ -> scheduler.delayed += task },
            postImmediate = { scheduler.immediate += it },
            remove = { task -> scheduler.delayed.removeAll { it === task } },
            dispatch = { _, update -> dispatched += update },
        )
        dispatcher.activate(AudioSinkDispatchToken(1, 1))
        dispatcher.setAvPlaying(true)
        dispatcher.onPeriod("first-period", 1)
        dispatcher.onStreamOffset(1_000_000_000_000)
        repeat(1_000) {
            dispatcher.onPosition(1_000_000_080_000, ObservationAvailability.AVAILABLE)
            dispatcher.onVideoFrame(1, 1, 80_001, nowNs + 30_000_000, nowNs + 10_000_000)
        }
        assertEquals(1, scheduler.delayed.size)
        scheduler.runDelayed()
        val pair = dispatched.filterIsInstance<PlaybackTechnicalUpdate.NativeAvSync>().last().observation
        assertEquals("AVAILABLE", pair.status)
        assertEquals(80_001L, pair.videoPtsUs)
        val staleCallbacks = scheduler.immediate.toList()
        dispatched.clear()
        dispatcher.activate(AudioSinkDispatchToken(1, 2)) // same-session reopen
        dispatcher.setAvPlaying(true)
        dispatcher.onPeriod("new-period", 1)
        dispatcher.onStreamOffset(0)
        dispatcher.onPosition(900, ObservationAvailability.AVAILABLE)
        dispatcher.onVideoFrame(1, 1, 901, nowNs, nowNs)
        staleCallbacks.forEach(Runnable::run)
        assertTrue(dispatched.isEmpty())
        scheduler.runDelayed()
        assertEquals("INSUFFICIENT_NO_SAMPLE", dispatched.filterIsInstance<PlaybackTechnicalUpdate.NativeAvSync>().last().observation.status)
        dispatcher.onVideoFrame(1, 2, 901, nowNs, nowNs)
        dispatcher.onPosition(900, ObservationAvailability.AVAILABLE)
        scheduler.runDelayed()
        assertEquals("AVAILABLE", dispatched.filterIsInstance<PlaybackTechnicalUpdate.NativeAvSync>().last().observation.status)
        dispatcher.onClockReset()
        scheduler.runAll()
        assertEquals("INSUFFICIENT_INVALIDATED", dispatched.filterIsInstance<PlaybackTechnicalUpdate.NativeAvSync>().last().observation.status)
        assertEquals(80_001L, pair.videoPtsUs)
    }

    @Test
    fun ownerThreadPauseBufferingAndSeekInvalidateBeforeImmediateEventSerialization() {
        for (reason in listOf(PlaybackDiagnosticReason.USER_PAUSE, PlaybackDiagnosticReason.REBUFFER_INTERVAL, PlaybackDiagnosticReason.SOURCE_START)) {
            val scheduler = FakeScheduler()
            val diagnostics = PlaybackDiagnostics()
            diagnostics.beginSource(1, "fictional", 1, 1)
            var ownerThread = true
            val nowNs = System.nanoTime()
            val dispatcher = CoalescingAudioSinkDispatcher(
                nanoTime = { nowNs },
                isDispatchThread = { ownerThread },
                postDelayed = { task, _ -> scheduler.delayed += task },
                postImmediate = { scheduler.immediate += it },
                remove = { task -> scheduler.delayed.removeAll { it === task } },
                dispatch = { token, update -> diagnostics.recordTechnical(token.sessionId, update) },
            )
            dispatcher.activate(AudioSinkDispatchToken(1, 1))
            dispatcher.setAvPlaying(true)
            dispatcher.onPeriod("stable-period", 1)
            dispatcher.onStreamOffset(0)
            dispatcher.onPosition(100_000, ObservationAvailability.AVAILABLE)
            dispatcher.onVideoFrame(1, 1, 100_001, nowNs, nowNs)
            scheduler.runDelayed()
            assertEquals("AVAILABLE", diagnostics.currentSnapshot()!!.nativeAvSync!!.status)

            ownerThread = false
            dispatcher.onStreamOffset(0) // A renderer-thread AVAILABLE publication is still queued.
            assertEquals(1, scheduler.immediate.size)
            ownerThread = true
            val expectedStatus = if (reason == PlaybackDiagnosticReason.SOURCE_START) {
                dispatcher.onClockReset() // Same-period seek/discontinuity callback.
                "INSUFFICIENT_INVALIDATED"
            } else {
                dispatcher.setAvPlaying(false) // pause() or STATE_BUFFERING callback.
                dispatcher.setAvPlaying(false) // Repeated false must also publish synchronously.
                "INSUFFICIENT_NOT_PLAYING"
            }

            // The event outlet runs immediately, without draining the main-thread message queue.
            val fact = PlaybackDiagnosticFact(1, 1, reason, 1, 1, "")
            val values = NativeDiagnosticMapper.values(fact, diagnostics.currentSnapshot())
            val fields = DiagnosticLogEntry(NativeDiagnosticMapper.entryKind(fact), 1, values).toSerializableFields()
            assertEquals(expectedStatus, fields["nativeAudioClockStatus"])
            assertEquals(-1L, fields["nativeAudioClockUs"])
            assertEquals(-1L, fields["nativePairedVideoPtsUs"])
            assertEquals(-1L, fields["nativeAudioMinusVideoUs"])
            assertEquals("UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED", fields["avSyncAssessment"])
            assertEquals(1, scheduler.immediate.size)
            scheduler.runAll()
            assertEquals(expectedStatus, diagnostics.currentSnapshot()!!.nativeAvSync!!.status)
        }
    }

    @Test
    fun rendererThreadClockResetStillQueuesControllerPublication() {
        val scheduler = FakeScheduler()
        val dispatched = mutableListOf<PlaybackTechnicalUpdate>()
        val dispatcher = CoalescingAudioSinkDispatcher(
            isDispatchThread = { false },
            postDelayed = { task, _ -> scheduler.delayed += task },
            postImmediate = { scheduler.immediate += it },
            remove = { task -> scheduler.delayed.removeAll { it === task } },
            dispatch = { _, update -> dispatched += update },
        )
        dispatcher.activate(AudioSinkDispatchToken(1, 1))
        dispatcher.setAvPlaying(true)
        scheduler.runAll()
        dispatched.clear()
        dispatcher.onClockReset()
        assertTrue(dispatched.isEmpty())
        assertEquals(1, scheduler.immediate.size)
        scheduler.runAll()
        assertEquals("INSUFFICIENT_INVALIDATED", (dispatched.single() as PlaybackTechnicalUpdate.NativeAvSync).observation.status)
    }

    @Test
    fun outputModeUsesOnlyReliableConfigFacts() {
        assertEquals(AudioOutputMode.PCM, audioOutputMode(C.ENCODING_PCM_FLOAT, false))
        assertEquals(AudioOutputMode.PASSTHROUGH, audioOutputMode(C.ENCODING_AC3, false))
        assertEquals(AudioOutputMode.OFFLOAD, audioOutputMode(C.ENCODING_AC3, true))
        assertEquals(AudioOutputMode.UNKNOWN, audioOutputMode(C.ENCODING_INVALID, false))
    }

    @Test
    fun observationsMapToTechnicalUpdatesWithoutChangingPresentedFrames() {
        assertEquals(
            PlaybackTechnicalUpdate.AudioSinkInput(90_000),
            AudioSinkObservation.InputBuffer(90_000).toTechnicalUpdate(),
        )
        val diagnostics = PlaybackDiagnostics(elapsedRealtimeMs = { 0 })
        diagnostics.beginSource(1, "fictional", 1, 1)
        diagnostics.recordTechnical(
            1,
            PlaybackTechnicalUpdate.VideoAboutToRender(
                VideoRenderObservation(90_000, 2_000, 1_000, VideoTimingSource.ABOUT_TO_RENDER),
            ),
        )
        diagnostics.recordTechnical(1, PlaybackTechnicalUpdate.VideoFirstFrame(44))
        assertEquals(ObservationAvailability.UNKNOWN, diagnostics.currentSnapshot()?.actualPresentedAvailability)
        assertEquals(VideoTimingSource.FIRST_FRAME, diagnostics.currentSnapshot()?.videoFirstFrameSource)
        assertEquals(-1L, presentedFramesForDiagnostics(diagnostics.currentSnapshot()?.audioTiming))
        assertFalse(diagnostics.currentSnapshot()?.audioTrackReleased == true)
    }

    private fun fakeAudioSink(
        handler: (String, Array<out Any?>?) -> Any?,
    ): AudioSink = Proxy.newProxyInstance(
        AudioSink::class.java.classLoader,
        arrayOf(AudioSink::class.java),
    ) { _, method, args -> handler(method.name, args) } as AudioSink

    private fun defaultValue(method: String): Any? = when (method) {
        "supportsFormat", "handleBuffer", "isEnded", "hasPendingData", "getSkipSilenceEnabled" -> false
        "getFormatSupport" -> AudioSink.SINK_FORMAT_UNSUPPORTED
        "getCurrentPositionUs", "getAudioTrackBufferSizeUs" -> AudioSink.CURRENT_POSITION_NOT_SET
        "toString" -> "FakeAudioSink"
        "hashCode" -> 1
        "equals" -> false
        else -> null
    }

    private class RecordingTarget(
        private val observations: MutableList<AudioSinkObservation>,
    ) : AudioSinkObservationTarget {
        override fun onInputBuffer(presentationTimeUs: Long) {
            observations += AudioSinkObservation.InputBuffer(presentationTimeUs)
        }

        override fun onPosition(positionUs: Long, availability: ObservationAvailability) {
            observations += AudioSinkObservation.Position(positionUs, availability)
        }

        override fun onImmediate(observation: AudioSinkObservation) {
            observations += observation
        }
    }

    private class FakeScheduler {
        val delayed = mutableListOf<Runnable>()
        val immediate = mutableListOf<Runnable>()

        fun dispatcher(
            dispatch: (AudioSinkDispatchToken, PlaybackTechnicalUpdate) -> Unit,
        ) = CoalescingAudioSinkDispatcher(
            postDelayed = { task, _ -> delayed += task },
            postImmediate = { immediate += it },
            remove = { task -> delayed.removeAll { it === task } },
            dispatch = dispatch,
        )

        fun runDelayed() {
            val tasks = delayed.toList()
            delayed.clear()
            tasks.forEach(Runnable::run)
        }

        fun runAll() {
            runDelayed()
            val tasks = immediate.toList()
            immediate.clear()
            tasks.forEach(Runnable::run)
        }
    }
}
