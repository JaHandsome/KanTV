package com.kantv.app.playback

import androidx.annotation.IntRange

/**
 * DASH + ClearKey 的播放期 DRM 参数。
 * 只携带 EME key response（内联密钥）与 uuid，不携带任何凭据或原始 URL。
 */
data class DrmRequest(
    /** 必须为 ClearKey 的 uuid；默认 Widevine 会导致 ClearKey 内容黑屏。 */
    val uuid: String,
    /** EME ClearKey key response。 */
    val keyResponseJson: String,
)

data class PlaybackRequest(
    val channelId: String,
    val uri: String,
    val mimeType: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val preferredKernel: PlaybackKernel? = null,
    /** 枢纽 route 字段（服务端下发，客户端不推断）。非枢纽来源为 null。 */
    val hubRoute: String? = null,
    /** 枢纽线路的 url_key（40 位哈希）。回流只上报它，绝不上报原始 URL。 */
    val hubUrlKey: String? = null,
    /** 仅有 dash-clearkey 线路会带上。 */
    val drm: DrmRequest? = null,
    /**
     * 服务端枢纽下发的清晰度档位（[SourceQuality.NONE] 表示没有权威元数据）。
     *
     * 只允许由 `HubRoutePlan` 填充：绝不从 URL、文件名或请求头推断分辨率。手动选择线路时该
     * 字段不参与任何重排。
     */
    val quality: Int = SourceQuality.NONE,
)

data class PlaybackCompatibility(
    val preferredKernel: PlaybackKernel? = null,
)

/** Generic compatibility defaults. Device-gated exceptions are intentionally not enabled. */
object PlaybackCompatibilityPolicy {
    fun forChannel(_identity: String?, _channelId: String): PlaybackCompatibility = PlaybackCompatibility()
}

enum class PlaybackTrackKind { VIDEO, AUDIO, SUBTITLE, OTHER }

enum class ContainerInfoSource { PLAYER_DETECTED, REQUEST_HINT }

enum class StreamDeliveryStrategy {
    DIRECT,
    /** Compatibility path used by the continuous local transport stream channel. */
    HLS_PARALLEL_PREFETCH,
    /** Media3 consumes the proxy's local HLS playlist and cached segment endpoints. */
    HLS_CACHED_PLAYLIST,
}

internal fun cachedHlsPlaybackRequest(request: PlaybackRequest, localPlaylistUri: String): PlaybackRequest =
    request.copy(
        uri = localPlaylistUri,
        mimeType = "application/x-mpegURL",
        requestHeaders = emptyMap(),
    )

enum class StreamDeliveryReason {
    DEFAULT,
    PREFETCH_ENABLED,
    MASTER_PLAYLIST,
    ENCRYPTED_PLAYLIST,
    INITIALIZATION_SEGMENT,
    INVALID_MEDIA_PLAYLIST,
    PREFETCH_NETWORK_FAILURE,
    UNSUPPORTED_CODEC,
    UNVERIFIED_BOOTSTRAP,
    BYTE_RANGE_PLAYLIST,
    GAP_PLAYLIST,
    LOW_LATENCY_PLAYLIST,
    /** 起播与预检并行:画面已直连起播,本地预取仍在后台验证。只能追加在末尾(ordinal 已持久化)。 */
    PREFETCH_PENDING,
}

data class PlaybackTrackInfo(
    val kind: PlaybackTrackKind,
    val codec: String?,
    val mimeType: String?,
    val language: String?,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val channelCount: Int? = null,
    val sampleRate: Int? = null,
    val selected: Boolean = false,
    val supported: Boolean = true,
    val selectionId: String? = null,
    val label: String? = null,
)

enum class KernelSelectionReason {
    DEFAULT,
    SOURCE_COMPATIBILITY,
    LEARNED_PREFERENCE,
    MANUAL_REOPEN,
    FIRST_FRAME_TIMEOUT,
    FRAME_STALLED,
    BUFFERING_TIMEOUT,
    FORMAT_UNSUPPORTED,
    DECODER_ERROR,
    STREAM_ENDED,
    OTHER_ERROR,
}

enum class AudioOutputMode { UNKNOWN, PCM, PASSTHROUGH, OFFLOAD }
enum class ObservationAvailability { UNKNOWN, AVAILABLE }
enum class VideoTimingSource { ABOUT_TO_RENDER, FIRST_FRAME }
enum class TransportStreamInputSource { ORIGINAL, REPAIRED_INPUT }

data class AudioOutputConfiguration(
    val encoding: Int = -1,
    val sampleRate: Int = -1,
    val channelConfig: Int = -1,
    val tunneling: Boolean = false,
    val offload: Boolean = false,
    val bufferSize: Int = -1,
    val outputMode: AudioOutputMode = AudioOutputMode.UNKNOWN,
    /** AudioTrack.getRoutedDevice is not exposed through Media3's AudioSink API. */
    val actualRoute: String = "unknown",
    val audioTrackTimestampAvailable: ObservationAvailability = ObservationAvailability.UNKNOWN,
    val audioTrackPlaybackHeadPosition: Long = -1,
)

data class AudioUnderrunDetails(
    val bufferSize: Int,
    val bufferSizeMs: Long,
    val elapsedSinceLastFeedMs: Long,
)

data class VideoRenderObservation(
    val presentationTimeUs: Long,
    val plannedReleaseTimeNs: Long,
    val callbackNanoTime: Long,
    val source: VideoTimingSource,
)

data class TransportStreamObservation(
    val inputSource: TransportStreamInputSource = TransportStreamInputSource.ORIGINAL,
    val pcrAvailable: Boolean = false,
    val pcrPid: Int = -1,
    val pcrSpanUs: Long = -1,
    val pcrMaxJumpUs: Long = -1,
    val pcrBackwardJumpCount: Int = -1,
    val adaptationDiscontinuityCount: Int = 0,
    val firstAudioMinusVideoUs: Long? = null,
    val hlsDiscontinuity: Boolean = false,
    val hlsDiscontinuitySequence: Long = -1,
    val wouldRepair: Boolean = false,
    val repairCorrectionSeconds: Long = 0,
    val repairHeaderCount: Int = 0,
)

/** Last read-only timeline/load context; its age is separate from the AV output clock. */
data class M3uLiveObservation(
    val observedNanoTime: Long,
    val positionMs: Long,
    val bufferedPositionMs: Long,
    val bufferDurationMs: Long,
    val liveOffsetMs: Long,
    val windowDurationMs: Long,
    val mediaSequence: Long,
    val lastMediaLoadDurationMs: Long,
    val lastMediaLoadBytes: Long,
    val lastLoadErrorCode: Int,
)

sealed interface PlaybackTechnicalUpdate {
    data class NativeMediaLoadObserved(val observation: NativeMediaLoadObservation) : PlaybackTechnicalUpdate
    data object PreparingPlayback : PlaybackTechnicalUpdate
    data class RuntimeGeneration(val generation: Long) : PlaybackTechnicalUpdate
    data class AudioTiming(
        val clockMs: Long,
        val selectedTrackId: Int,
        val configuredDelayUs: Long,
        val playedBuffers: Int,
        val lostBuffers: Int,
        val decodedVideo: Int = -1,
        val displayedPictures: Int = -1,
        val readBytes: Long = -1,
        val demuxReadBytes: Long = -1,
        val inputKbps: Int = -1,
        val demuxKbps: Int = -1,
        val demuxCorrupted: Int = -1,
        val demuxDiscontinuity: Int = -1,
        val decodedAudio: Int = -1,
        val sourceAudioPid1: Int = -1,
        val sourceAudioMinusVideoMs1: Int = 0,
        val sourceAudioPid2: Int = -1,
        val sourceAudioMinusVideoMs2: Int = 0,
    ) : PlaybackTechnicalUpdate

    data class KernelSelected(
        val kernel: PlaybackKernel,
        val reason: KernelSelectionReason,
    ) : PlaybackTechnicalUpdate

    data class TracksDetected(
        val containerMimeType: String?,
        val containerInfoSource: ContainerInfoSource?,
        val tracks: List<PlaybackTrackInfo>,
    ) : PlaybackTechnicalUpdate

    data class VideoDecoderSelected(val name: String?) : PlaybackTechnicalUpdate
    data class AudioDecoderSelected(val name: String?) : PlaybackTechnicalUpdate
    data class AudioFormatChanged(
        val mimeType: String?,
        val codecs: String?,
        val channelCount: Int,
        val sampleRate: Int,
    ) : PlaybackTechnicalUpdate
    data class AudioUnderrun(val count: Int = 1) : PlaybackTechnicalUpdate
    data class M3uLiveContext(val observation: M3uLiveObservation) : PlaybackTechnicalUpdate
    data class NativeAvSync(val observation: NativeAvSyncObservation) : PlaybackTechnicalUpdate
    data class AudioSinkInput(val presentationTimeUs: Long) : PlaybackTechnicalUpdate
    data class AudioSinkPosition(
        val positionUs: Long,
        val availability: ObservationAvailability,
    ) : PlaybackTechnicalUpdate
    data class AudioOutputConfigured(val configuration: AudioOutputConfiguration) : PlaybackTechnicalUpdate
    data class AudioSinkUnderrun(val details: AudioUnderrunDetails) : PlaybackTechnicalUpdate
    data object AudioSinkDiscontinuity : PlaybackTechnicalUpdate
    data class AudioTrackReleased(val configuration: AudioOutputConfiguration?) : PlaybackTechnicalUpdate
    data class VideoAboutToRender(val observation: VideoRenderObservation) : PlaybackTechnicalUpdate

    /**
     * Video presentation time changed, including a legal period/seek reset.
     * Throttled to the engine's progress-signal interval
     * (~1 s): the counter below only needs to distinguish "decoded but not presented" from
     * "no frames at all", and per-frame reporting was a fixed main-thread cost during playback.
     */
    data class VideoFrameAdvanced(
        val presentationTimeUs: Long,
        val firstFrameRendered: Boolean,
    ) : PlaybackTechnicalUpdate
    data class VideoFirstFrame(
        val renderTimeMs: Long,
        val source: VideoTimingSource = VideoTimingSource.FIRST_FRAME,
    ) : PlaybackTechnicalUpdate
    data class VideoProcessingOffset(val totalProcessingOffsetUs: Long, val frameCount: Int) : PlaybackTechnicalUpdate
    data class DroppedVideoFrames(val count: Int, val elapsedMs: Long = -1) : PlaybackTechnicalUpdate
    data class TransportStreamObserved(val observation: TransportStreamObservation) : PlaybackTechnicalUpdate
    data class StreamDeliverySelected(
        val strategy: StreamDeliveryStrategy,
        val reason: StreamDeliveryReason,
    ) : PlaybackTechnicalUpdate

    /**
     * HLS prefetch startup evidence: counters, an enumerated outcome, and durations only. Carries no
     * URI, header, segment body, or playlist identity.
     */
    data class PrefetchStartup(
        val queued: Int,
        val ready: Int,
        val failed: Int,
        val cancelled: Int,
        val waitMs: Long,
        val capMs: Long,
        val segmentCount: Int,
        val targetDurationMs: Long,
        val outcome: PrefetchStartupPolicy.StartupOutcome,
    ) : PlaybackTechnicalUpdate
}

enum class PlaybackErrorKind {
    OFFLINE,
    TEMPORARY_NETWORK,
    AUTHORIZATION,
    BEHIND_LIVE_WINDOW,
    UNSUPPORTED_FORMAT,
    DECODER,
    UNKNOWN,
}

sealed interface PlaybackEvent {
    val sessionId: Long

    data class Loading(override val sessionId: Long) : PlaybackEvent
    data class Ready(override val sessionId: Long) : PlaybackEvent
    data class FirstFrame(override val sessionId: Long) : PlaybackEvent
    data class VideoProgress(
        override val sessionId: Long,
        val presentationTimeUs: Long,
    ) : PlaybackEvent
    data class Technical(
        override val sessionId: Long,
        val update: PlaybackTechnicalUpdate,
    ) : PlaybackEvent
    data class Ended(override val sessionId: Long) : PlaybackEvent
    data class Error(
        override val sessionId: Long,
        val kind: PlaybackErrorKind,
        val errorCode: Int,
    ) : PlaybackEvent
}

data class RecoveryPolicy(
    @IntRange(from = 0) val maxAutomaticReopens: Int = 2,
    @IntRange(from = 1) val maxActiveRecoveryMs: Long = 30_000,
    @IntRange(from = 1) val stablePlaybackBeforeResetMs: Long = 60_000,
    val retryDelaysMs: List<Long> = listOf(1_000, 2_000),
)

enum class BufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackBufferMs: Int,
    val rebufferMs: Int,
) {
    UPSTREAM_BASELINE(50_000, 50_000, 1_000, 2_000),
    FASTER_START_TRIAL(50_000, 50_000, 500, 2_000),
    SAFER_RESUME_TRIAL(50_000, 50_000, 1_000, 4_000),
}
