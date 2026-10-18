package com.kantv.app.playback

import java.util.ArrayDeque

internal fun presentedFramesForDiagnostics(timing: PlaybackTechnicalUpdate.AudioTiming?): Long =
    timing?.displayedPictures?.toLong()?.takeIf { it >= 0 } ?: -1L

enum class DiagnosticType {
    SELECTED,
    FIRST_FRAME,
    ERROR,
    RECOVERY_ATTEMPT,
    VIDEO_PROGRESS,
    STALE_EVENT_DROPPED,

    /** One candidate-level rebuffering interval closed (see RebufferHealthPolicy). */
    REBUFFER_INTERVAL,
}

data class DiagnosticEvent(
    val sessionId: Long,
    val channelId: String?,
    val type: DiagnosticType,
    val code: Int?,
)

data class PlaybackDiagnosticSnapshot(
    val sessionId: Long,
    val channelId: String,
    val sourceIndex: Int,
    val sourceCount: Int,
    val kernel: PlaybackKernel?,
    val kernelReason: KernelSelectionReason?,
    val streamDeliveryStrategy: StreamDeliveryStrategy?,
    val streamDeliveryReason: StreamDeliveryReason?,
    val containerMimeType: String?,
    val containerInfoSource: ContainerInfoSource?,
    val tracks: List<PlaybackTrackInfo>,
    val videoDecoderName: String?,
    val audioDecoderName: String?,
    val audioMimeType: String?,
    val audioCodec: String?,
    val audioChannelCount: Int,
    val audioSampleRate: Int,
    val audioUnderrunCount: Int,
    val runtimeGeneration: Long,
    val droppedVideoFrames: Int,
    val videoProgressCount: Long,
    val lastVideoPtsUs: Long,
    val audioInputPtsUs: Long,
    val audioSinkPositionUs: Long,
    val audioSinkPositionAvailability: ObservationAvailability,
    val audioOutputConfiguration: AudioOutputConfiguration?,
    val audioUnderrunDetails: AudioUnderrunDetails?,
    val audioSinkDiscontinuityCount: Int,
    val audioTrackReleased: Boolean,
    val videoRenderObservation: VideoRenderObservation?,
    val videoFirstFrameRenderTimeMs: Long,
    val videoFirstFrameSource: VideoTimingSource?,
    val videoProcessingOffsetUs: Long,
    val videoProcessingFrameCount: Int,
    val droppedVideoFramesElapsedMs: Long,
    val actualPresentedAvailability: ObservationAvailability,
    val transportStreamObservation: TransportStreamObservation?,
    val firstFrameMs: Long?,
    val lastErrorCode: Int?,
    val audioTiming: PlaybackTechnicalUpdate.AudioTiming? = null,
    val nativeAvSync: NativeAvSyncObservation? = null,
    val m3uLive: M3uLiveObservation? = null,
    val m3uLoad: NativeMediaLoadObservation? = null,
    /** Candidate-level rebuffering evidence for the current source. */
    val rebufferCount: Int = 0,
    val rebufferTotalMs: Long = 0L,
    /** Last audio clock already accounted for by the sampler; never exposed as media content. */
    val lastAudioClockMs: Long = -1L,
    /** HLS prefetch startup counters and enumerated outcome. */
    val prefetchQueued: Int = -1,
    val prefetchReady: Int = -1,
    val prefetchFailed: Int = -1,
    val prefetchCancelled: Int = -1,
    val prefetchWaitMs: Long = -1L,
    val prefetchCapMs: Long = -1L,
    val prefetchSegmentCount: Int = -1,
    val prefetchTargetDurationMs: Long = -1L,
    val prefetchOutcome: PrefetchStartupPolicy.StartupOutcome? = null,
    /** Frames the video pipeline produced, and how many arrived before any rendered picture. */
    val frameAdvanceCount: Long = 0L,
    val framesAdvancedBeforeFirstFrame: Long = 0L,
)

class PlaybackDiagnostics(
    private val capacity: Int = 200,
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val events = ArrayDeque<DiagnosticEvent>()
    private var current: PlaybackDiagnosticSnapshot? = null
    private var kernelStartedAtMs = 0L

    @Synchronized
    fun beginSource(sessionId: Long, channelId: String, sourceIndex: Int, sourceCount: Int) {
        kernelStartedAtMs = elapsedRealtimeMs()
        current = PlaybackDiagnosticSnapshot(
            sessionId = sessionId,
            channelId = channelId,
            sourceIndex = sourceIndex,
            sourceCount = sourceCount,
            kernel = null,
            kernelReason = null,
            streamDeliveryStrategy = null,
            streamDeliveryReason = null,
            containerMimeType = null,
            containerInfoSource = null,
            tracks = emptyList(),
            videoDecoderName = null,
            audioDecoderName = null,
            audioMimeType = null,
            audioCodec = null,
            audioChannelCount = -1,
            audioSampleRate = -1,
            audioUnderrunCount = 0,
            runtimeGeneration = -1,
            droppedVideoFrames = 0,
            videoProgressCount = 0,
            lastVideoPtsUs = -1,
            audioInputPtsUs = -1,
            audioSinkPositionUs = -1,
            audioSinkPositionAvailability = ObservationAvailability.UNKNOWN,
            audioOutputConfiguration = null,
            audioUnderrunDetails = null,
            audioSinkDiscontinuityCount = 0,
            audioTrackReleased = false,
            videoRenderObservation = null,
            videoFirstFrameRenderTimeMs = -1,
            videoFirstFrameSource = null,
            videoProcessingOffsetUs = -1,
            videoProcessingFrameCount = -1,
            droppedVideoFramesElapsedMs = -1,
            actualPresentedAvailability = ObservationAvailability.UNKNOWN,
            transportStreamObservation = null,
            firstFrameMs = null,
            lastErrorCode = null,
        )
    }

    @Synchronized
    fun record(sessionId: Long, channelId: String?, type: DiagnosticType, code: Int? = null) {
        if (events.size == capacity) events.removeFirst()
        events.addLast(DiagnosticEvent(sessionId, channelId, type, code))
        val snapshot = current?.takeIf { it.sessionId == sessionId } ?: return
        current = when (type) {
            DiagnosticType.FIRST_FRAME -> if (snapshot.firstFrameMs == null) {
                snapshot.copy(
                    firstFrameMs = (elapsedRealtimeMs() - kernelStartedAtMs).coerceAtLeast(0L),
                    videoProgressCount = snapshot.videoProgressCount.coerceAtLeast(1),
                )
            } else {
                snapshot
            }
            DiagnosticType.VIDEO_PROGRESS -> snapshot.copy(videoProgressCount = snapshot.videoProgressCount + 1)
            DiagnosticType.ERROR -> snapshot.copy(lastErrorCode = code)
            else -> snapshot
        }
    }

    @Synchronized
    fun recordTechnical(sessionId: Long, update: PlaybackTechnicalUpdate) {
        val snapshot = current?.takeIf { it.sessionId == sessionId } ?: return
        current = when (update) {
            PlaybackTechnicalUpdate.PreparingPlayback -> snapshot
            is PlaybackTechnicalUpdate.RuntimeGeneration -> snapshot.copy(
                runtimeGeneration = update.generation,
                nativeAvSync = if (snapshot.runtimeGeneration == update.generation) snapshot.nativeAvSync else null,
                m3uLive = if (snapshot.runtimeGeneration == update.generation) snapshot.m3uLive else null,
                m3uLoad = if (snapshot.runtimeGeneration == update.generation) snapshot.m3uLoad else null,
            )
            is PlaybackTechnicalUpdate.M3uLiveContext -> snapshot.copy(m3uLive = update.observation)
            is PlaybackTechnicalUpdate.NativeMediaLoadObserved -> snapshot.copy(m3uLoad = update.observation)
            is PlaybackTechnicalUpdate.NativeAvSync -> snapshot.copy(nativeAvSync = update.observation)
            is PlaybackTechnicalUpdate.AudioTiming -> snapshot.copy(
                audioTiming = update,
                actualPresentedAvailability = if (update.displayedPictures >= 0) {
                    ObservationAvailability.AVAILABLE
                } else {
                    ObservationAvailability.UNKNOWN
                },
            )
            is PlaybackTechnicalUpdate.KernelSelected -> {
                kernelStartedAtMs = elapsedRealtimeMs()
                snapshot.copy(
                    audioTiming = null,
                    nativeAvSync = null,
                    m3uLive = null,
                    m3uLoad = null,
                    kernel = update.kernel,
                    kernelReason = update.reason,
                    streamDeliveryStrategy = null,
                    streamDeliveryReason = null,
                    containerMimeType = null,
                    containerInfoSource = null,
                    tracks = emptyList(),
                    videoDecoderName = null,
                    audioDecoderName = null,
                    audioMimeType = null,
                    audioCodec = null,
                    audioChannelCount = -1,
                    audioSampleRate = -1,
                    audioUnderrunCount = 0,
                    runtimeGeneration = -1,
                    droppedVideoFrames = 0,
                    videoProgressCount = 0,
                    lastVideoPtsUs = -1,
                    audioInputPtsUs = -1,
                    audioSinkPositionUs = -1,
                    audioSinkPositionAvailability = ObservationAvailability.UNKNOWN,
                    audioOutputConfiguration = null,
                    audioUnderrunDetails = null,
                    audioSinkDiscontinuityCount = 0,
                    audioTrackReleased = false,
                    videoRenderObservation = null,
                    videoFirstFrameRenderTimeMs = -1,
                    videoFirstFrameSource = null,
                    videoProcessingOffsetUs = -1,
                    videoProcessingFrameCount = -1,
                    droppedVideoFramesElapsedMs = -1,
                    actualPresentedAvailability = ObservationAvailability.UNKNOWN,
                    transportStreamObservation = null,
                    firstFrameMs = null,
                    lastErrorCode = null,
                )
            }
            is PlaybackTechnicalUpdate.TracksDetected -> snapshot.copy(
                containerMimeType = update.containerMimeType ?: snapshot.containerMimeType,
                containerInfoSource = update.containerInfoSource ?: snapshot.containerInfoSource,
                tracks = update.tracks,
            )
            is PlaybackTechnicalUpdate.VideoDecoderSelected -> snapshot.copy(
                videoDecoderName = update.name,
            )
            is PlaybackTechnicalUpdate.AudioDecoderSelected -> snapshot.copy(audioDecoderName = update.name)
            is PlaybackTechnicalUpdate.AudioFormatChanged -> snapshot.copy(
                audioMimeType = update.mimeType,
                audioCodec = update.codecs,
                audioChannelCount = update.channelCount,
                audioSampleRate = update.sampleRate,
            )
            is PlaybackTechnicalUpdate.AudioUnderrun -> snapshot.copy(
                audioUnderrunCount = (snapshot.audioUnderrunCount + update.count).coerceAtLeast(0),
            )
            is PlaybackTechnicalUpdate.AudioSinkInput -> snapshot.copy(audioInputPtsUs = update.presentationTimeUs)
            is PlaybackTechnicalUpdate.AudioSinkPosition -> snapshot.copy(
                audioSinkPositionUs = update.positionUs,
                audioSinkPositionAvailability = update.availability,
            )
            is PlaybackTechnicalUpdate.AudioOutputConfigured -> snapshot.copy(
                audioOutputConfiguration = update.configuration,
                audioTrackReleased = false,
            )
            is PlaybackTechnicalUpdate.AudioSinkUnderrun -> snapshot.copy(
                audioUnderrunCount = snapshot.audioUnderrunCount + 1,
                audioUnderrunDetails = update.details,
            )
            PlaybackTechnicalUpdate.AudioSinkDiscontinuity -> snapshot.copy(
                audioSinkDiscontinuityCount = snapshot.audioSinkDiscontinuityCount + 1,
            )
            is PlaybackTechnicalUpdate.AudioTrackReleased -> snapshot.copy(audioTrackReleased = true)
            is PlaybackTechnicalUpdate.VideoAboutToRender -> snapshot.copy(
                videoRenderObservation = update.observation,
                lastVideoPtsUs = update.observation.presentationTimeUs,
            )
            is PlaybackTechnicalUpdate.VideoFrameAdvanced -> snapshot.copy(
                frameAdvanceCount = snapshot.frameAdvanceCount + 1,
                lastVideoPtsUs = update.presentationTimeUs,
                // A frame the pipeline produced before any rendered picture is exactly the
                // "decoded but not presented" signal the grey-screen report needs.
                framesAdvancedBeforeFirstFrame = if (!update.firstFrameRendered) {
                    snapshot.framesAdvancedBeforeFirstFrame + 1
                } else {
                    snapshot.framesAdvancedBeforeFirstFrame
                },
            )
            is PlaybackTechnicalUpdate.VideoFirstFrame -> snapshot.copy(
                videoFirstFrameRenderTimeMs = update.renderTimeMs,
                videoFirstFrameSource = update.source,
            )
            is PlaybackTechnicalUpdate.VideoProcessingOffset -> snapshot.copy(
                videoProcessingOffsetUs = update.totalProcessingOffsetUs,
                videoProcessingFrameCount = update.frameCount,
            )
            is PlaybackTechnicalUpdate.DroppedVideoFrames -> snapshot.copy(
                droppedVideoFrames = (snapshot.droppedVideoFrames + update.count).coerceAtLeast(0),
                droppedVideoFramesElapsedMs = update.elapsedMs,
            )
            is PlaybackTechnicalUpdate.TransportStreamObserved -> snapshot.copy(
                transportStreamObservation = update.observation,
            )
            is PlaybackTechnicalUpdate.StreamDeliverySelected -> snapshot.copy(
                streamDeliveryStrategy = update.strategy,
                streamDeliveryReason = update.reason,
            )
            is PlaybackTechnicalUpdate.PrefetchStartup -> snapshot.copy(
                prefetchQueued = update.queued,
                prefetchReady = update.ready,
                prefetchFailed = update.failed,
                prefetchCancelled = update.cancelled,
                prefetchWaitMs = update.waitMs,
                prefetchCapMs = update.capMs,
                prefetchSegmentCount = update.segmentCount,
                prefetchTargetDurationMs = update.targetDurationMs,
                prefetchOutcome = update.outcome,
            )
        }
    }

    @Synchronized
    fun snapshot(): List<DiagnosticEvent> = events.toList()

    @Synchronized
    fun recordVideoProgress(sessionId: Long, presentationTimeUs: Long) {
        record(sessionId, current?.takeIf { it.sessionId == sessionId }?.channelId, DiagnosticType.VIDEO_PROGRESS)
        val snapshot = current?.takeIf { it.sessionId == sessionId } ?: return
        current = snapshot.copy(lastVideoPtsUs = presentationTimeUs.coerceAtLeast(-1))
    }

    /**
     * Records one closed rebuffering interval. The duration is packed into the entry's numeric code
     * slot, so no new free-form field and no source identity is introduced.
     */
    @Synchronized
    fun recordRebuffer(sessionId: Long, durationMs: Long) {
        record(
            sessionId,
            current?.takeIf { it.sessionId == sessionId }?.channelId,
            DiagnosticType.REBUFFER_INTERVAL,
            durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        )
        val snapshot = current?.takeIf { it.sessionId == sessionId } ?: return
        current = snapshot.copy(
            rebufferCount = snapshot.rebufferCount + 1,
            rebufferTotalMs = snapshot.rebufferTotalMs + durationMs.coerceAtLeast(0L),
        )
    }

    @Synchronized
    fun currentSnapshot(): PlaybackDiagnosticSnapshot? = current

    /**
     * Audio-clock delta between two samples of the same session. A positive value means the audio
     * pipeline advanced; this is the only audio-progress signal available before a first frame, so
     * the grey-screen report can separate "sound only" from "silent and frozen".
     */
    @Synchronized
    fun audioClockDelta(sessionId: Long): Long {
        val snapshot = current?.takeIf { it.sessionId == sessionId } ?: return -1L
        val clock = snapshot.audioTiming?.clockMs ?: return -1L
        return if (clock >= snapshot.lastAudioClockMs) clock - snapshot.lastAudioClockMs else -1L
    }

    @Synchronized
    fun markAudioClock(sessionId: Long) {
        val snapshot = current?.takeIf { it.sessionId == sessionId } ?: return
        val clock = snapshot.audioTiming?.clockMs ?: return
        current = snapshot.copy(lastAudioClockMs = clock)
    }
}
