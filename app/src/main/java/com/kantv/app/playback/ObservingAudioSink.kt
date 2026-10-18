package com.kantv.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

internal sealed interface AudioSinkObservation {
    data class InputBuffer(val presentationTimeUs: Long) : AudioSinkObservation
    data class Position(val positionUs: Long, val availability: ObservationAvailability) : AudioSinkObservation
    data class OutputConfigured(val configuration: AudioOutputConfiguration) : AudioSinkObservation
    data class Underrun(val details: AudioUnderrunDetails) : AudioSinkObservation
    data object Discontinuity : AudioSinkObservation
    data class TrackReleased(val configuration: AudioOutputConfiguration) : AudioSinkObservation
    data object SinkReleased : AudioSinkObservation
}

/** Hot-path methods carry primitives only; structured observations are reserved for rare events. */
internal interface AudioSinkObservationTarget {
    fun onInputBuffer(presentationTimeUs: Long)
    fun onPosition(positionUs: Long, availability: ObservationAvailability)
    fun onImmediate(observation: AudioSinkObservation)
    fun onStreamOffset(offsetUs: Long) = Unit
    fun onClockReset() = Unit

    data object None : AudioSinkObservationTarget {
        override fun onInputBuffer(presentationTimeUs: Long) = Unit
        override fun onPosition(positionUs: Long, availability: ObservationAvailability) = Unit
        override fun onImmediate(observation: AudioSinkObservation) = Unit
    }
}

internal data class AudioSinkDispatchToken(val sessionId: Long, val generation: Long)

/**
 * Keeps at most one delayed hot-path task. Repeated sink callbacks overwrite primitive latest
 * values instead of allocating observations or queueing main-thread messages.
 */
internal class CoalescingAudioSinkDispatcher(
    private val intervalMs: Long = 1_000,
    private val nanoTime: () -> Long = System::nanoTime,
    private val isDispatchThread: () -> Boolean = { false },
    private val postDelayed: (task: Runnable, delayMs: Long) -> Unit,
    private val postImmediate: (task: Runnable) -> Unit,
    private val remove: (task: Runnable) -> Unit,
    private val dispatch: (AudioSinkDispatchToken, PlaybackTechnicalUpdate) -> Unit,
) : AudioSinkObservationTarget {
    private val lock = Any()
    private val avSync = NativeAvSyncTracker()
    private var observeAv = false
    private var avEpoch = 0L
    @Volatile private var activeToken: AudioSinkDispatchToken? = null
    @Volatile private var closed = false
    private var scheduled = false
    private var hasInput = false
    private var inputPtsUs = -1L
    private var inputToken: AudioSinkDispatchToken? = null
    private var hasPosition = false
    private var positionUs = -1L
    private var positionAvailability = ObservationAvailability.UNKNOWN
    private var positionToken: AudioSinkDispatchToken? = null
    private val flushTask = Runnable { flush() }

    fun activate(token: AudioSinkDispatchToken) {
        synchronized(lock) {
            activeToken = token
            avEpoch++
            avSync.reset(clearOffsets = true)
            avSync.playing(false)
            observeAv = false
            clearLatestLocked()
            if (scheduled) {
                scheduled = false
                remove(flushTask)
            }
        }
    }

    fun deactivate() {
        synchronized(lock) {
            activeToken = null
            avEpoch++
            avSync.reset(clearOffsets = true)
            clearLatestLocked()
            if (scheduled) {
                scheduled = false
                remove(flushTask)
            }
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            activeToken = null
            avEpoch++
            avSync.reset(clearOffsets = true)
            clearLatestLocked()
            if (scheduled) {
                scheduled = false
                remove(flushTask)
            }
        }
    }

    override fun onInputBuffer(presentationTimeUs: Long) {
        val token = activeToken ?: return
        var shouldPost = false
        synchronized(lock) {
            if (closed || token !== activeToken) return
            inputPtsUs = presentationTimeUs
            inputToken = token
            hasInput = true
            if (!scheduled) {
                scheduled = true
                shouldPost = true
            }
        }
        if (shouldPost) postDelayed(flushTask, intervalMs)
    }

    override fun onPosition(positionUs: Long, availability: ObservationAvailability) {
        val token = activeToken ?: return
        var shouldPost = false
        synchronized(lock) {
            if (closed || token !== activeToken) return
            if (observeAv) avSync.audio(positionUs, availability == ObservationAvailability.AVAILABLE, nanoTime())
            this.positionUs = positionUs
            positionAvailability = availability
            positionToken = token
            hasPosition = true
            if (!scheduled) {
                scheduled = true
                shouldPost = true
            }
        }
        if (shouldPost) postDelayed(flushTask, intervalMs)
    }

    fun setAvPlaying(playing: Boolean) {
        synchronized(lock) {
            observeAv = true
            avEpoch++
            avSync.playing(playing)
        }
        publishAvState()
    }

    override fun onStreamOffset(offsetUs: Long) {
        synchronized(lock) {
            observeAv = true
            avEpoch++
            avSync.audioOffset(offsetUs.takeUnless { it == C.TIME_UNSET })
        }
        publishAvState()
    }

    fun onPeriod(uid: Any?, count: Int) {
        synchronized(lock) {
            observeAv = true
            avEpoch++
            avSync.period(uid, count)
        }
        publishAvState()
    }

    fun onVideoFrame(sessionId: Long, generation: Long, ptsUs: Long, releaseNs: Long, callbackNs: Long) = synchronized(lock) {
        val token = activeToken
        if (!closed && token?.sessionId == sessionId && token.generation == generation) {
            avSync.video(ptsUs, releaseNs, callbackNs)
        }
    }

    override fun onClockReset() {
        synchronized(lock) {
            avEpoch++
            avSync.reset()
            clearLatestLocked()
        }
        publishAvState()
    }

    private fun publishAvState() {
        val token: AudioSinkDispatchToken
        val epoch: Long
        val update: PlaybackTechnicalUpdate.NativeAvSync
        synchronized(lock) {
            token = activeToken ?: return
            if (closed || !observeAv) return
            epoch = avEpoch
            update = PlaybackTechnicalUpdate.NativeAvSync(avSync.snapshot(token.generation))
        }
        val task = Runnable {
            val valid = synchronized(lock) { !closed && activeToken === token && avEpoch == epoch }
            if (valid) dispatch(token, update)
        }
        // A pause/buffering/seek event may serialize diagnostics in this same call stack.
        // Publish invalidation before that event, while renderer threads still post to the owner.
        if (isDispatchThread()) task.run() else postImmediate(task)
    }

    override fun onImmediate(observation: AudioSinkObservation) {
        if (observation == AudioSinkObservation.Discontinuity ||
            observation is AudioSinkObservation.TrackReleased || observation == AudioSinkObservation.SinkReleased
        ) onClockReset()
        val token = activeToken ?: return
        if (closed) return
        val update = observation.toTechnicalUpdate()
        postImmediate(Runnable {
            if (!closed && activeToken === token) dispatch(token, update)
        })
    }

    private fun flush() {
        var token: AudioSinkDispatchToken? = null
        var input: Long? = null
        var position: Pair<Long, ObservationAvailability>? = null
        var avObservation: NativeAvSyncObservation? = null
        var sampledAvEpoch = -1L
        synchronized(lock) {
            scheduled = false
            val current = activeToken
            if (!closed && current != null) {
                token = current
                if (observeAv) {
                    avObservation = avSync.snapshot(current.generation)
                    sampledAvEpoch = avEpoch
                }
                if (hasInput && inputToken === current) input = inputPtsUs
                if (hasPosition && positionToken === current) {
                    position = positionUs to positionAvailability
                }
            }
            clearLatestLocked()
        }
        val validToken = token ?: return
        if (closed || activeToken !== validToken) return
        if (synchronized(lock) { avEpoch == sampledAvEpoch }) {
            avObservation?.let { dispatch(validToken, PlaybackTechnicalUpdate.NativeAvSync(it)) }
        }
        input?.let { dispatch(validToken, PlaybackTechnicalUpdate.AudioSinkInput(it)) }
        if (closed || activeToken !== validToken) return
        position?.let { (value, availability) ->
            dispatch(validToken, PlaybackTechnicalUpdate.AudioSinkPosition(value, availability))
        }
    }

    private fun clearLatestLocked() {
        hasInput = false
        inputToken = null
        hasPosition = false
        positionToken = null
    }
}

internal fun AudioSinkObservation.toTechnicalUpdate(): PlaybackTechnicalUpdate = when (this) {
    is AudioSinkObservation.InputBuffer -> PlaybackTechnicalUpdate.AudioSinkInput(presentationTimeUs)
    is AudioSinkObservation.Position -> PlaybackTechnicalUpdate.AudioSinkPosition(positionUs, availability)
    is AudioSinkObservation.OutputConfigured -> PlaybackTechnicalUpdate.AudioOutputConfigured(configuration)
    is AudioSinkObservation.Underrun -> PlaybackTechnicalUpdate.AudioSinkUnderrun(details)
    AudioSinkObservation.Discontinuity -> PlaybackTechnicalUpdate.AudioSinkDiscontinuity
    is AudioSinkObservation.TrackReleased -> PlaybackTechnicalUpdate.AudioTrackReleased(configuration)
    AudioSinkObservation.SinkReleased -> PlaybackTechnicalUpdate.AudioTrackReleased(null)
}

/** A transparent, allocation-light observation wrapper. It never copies or retains audio data. */
@OptIn(markerClass = [UnstableApi::class])
internal class ObservingAudioSink(
    delegate: AudioSink,
    private val observer: AudioSinkObservationTarget,
) : ForwardingAudioSink(delegate) {
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        observer.onInputBuffer(if (presentationTimeUs >= 0) presentationTimeUs else -1)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val raw = super.getCurrentPositionUs(sourceEnded)
        val available = raw != AudioSink.CURRENT_POSITION_NOT_SET && raw >= 0
        observer.onPosition(
            positionUs = if (available) raw else -1,
            availability = if (available) ObservationAvailability.AVAILABLE else ObservationAvailability.UNKNOWN,
        )
        return raw
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        observer.onStreamOffset(outputStreamOffsetUs)
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun flush() {
        observer.onClockReset()
        super.flush()
    }

    override fun reset() {
        observer.onClockReset()
        super.reset()
    }

    override fun handleDiscontinuity() {
        observer.onClockReset()
        super.handleDiscontinuity()
    }

    override fun setListener(listener: AudioSink.Listener) {
        super.setListener(object : AudioSink.Listener {
            override fun onPositionDiscontinuity() {
                observer.onImmediate(AudioSinkObservation.Discontinuity)
                listener.onPositionDiscontinuity()
            }

            override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) =
                listener.onPositionAdvancing(playoutStartSystemTimeMs)

            override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                observer.onImmediate(AudioSinkObservation.Underrun(
                    AudioUnderrunDetails(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs),
                ))
                listener.onUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
            }

            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) =
                listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)

            override fun onOffloadBufferEmptying() = listener.onOffloadBufferEmptying()
            override fun onOffloadBufferFull() = listener.onOffloadBufferFull()
            override fun onAudioSinkError(audioSinkError: Exception) = listener.onAudioSinkError(audioSinkError)
            override fun onAudioCapabilitiesChanged() = listener.onAudioCapabilitiesChanged()

            override fun onAudioTrackInitialized(audioTrackConfig: AudioSink.AudioTrackConfig) {
                observer.onImmediate(AudioSinkObservation.OutputConfigured(audioTrackConfig.toDiagnosticConfiguration()))
                listener.onAudioTrackInitialized(audioTrackConfig)
            }

            override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) {
                observer.onImmediate(AudioSinkObservation.TrackReleased(audioTrackConfig.toDiagnosticConfiguration()))
                listener.onAudioTrackReleased(audioTrackConfig)
            }

            override fun onSilenceSkipped() = listener.onSilenceSkipped()
            override fun onAudioSessionIdChanged(audioSessionId: Int) =
                listener.onAudioSessionIdChanged(audioSessionId)
        })
    }

    override fun release() {
        observer.onImmediate(AudioSinkObservation.SinkReleased)
        super.release()
    }
}

@OptIn(markerClass = [UnstableApi::class])
private fun AudioSink.AudioTrackConfig.toDiagnosticConfiguration() = AudioOutputConfiguration(
    encoding = encoding,
    sampleRate = sampleRate,
    channelConfig = channelConfig,
    tunneling = tunneling,
    offload = offload,
    bufferSize = bufferSize,
    outputMode = audioOutputMode(encoding, offload),
)

@OptIn(markerClass = [UnstableApi::class])
internal fun audioOutputMode(encoding: Int, offload: Boolean): AudioOutputMode = when {
    offload -> AudioOutputMode.OFFLOAD
    Util.isEncodingLinearPcm(encoding) -> AudioOutputMode.PCM
    encoding != C.ENCODING_INVALID -> AudioOutputMode.PASSTHROUGH
    else -> AudioOutputMode.UNKNOWN
}
