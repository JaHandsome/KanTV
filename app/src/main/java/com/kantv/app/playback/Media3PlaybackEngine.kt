package com.kantv.app.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import com.kantv.app.BuildConfig
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class Media3PlaybackEngine(
    context: Context,
    private val playerView: PlayerView,
    bufferProfile: BufferProfile = BufferProfile.UPSTREAM_BASELINE,
    private val hlsPrefetchProxy: HlsPrefetchProxy = HlsPrefetchProxy(),
    @Suppress("UNUSED_PARAMETER") debugDirectHls: Boolean = false,
) : PlaybackEngine {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: (PlaybackEvent) -> Unit = {}
    private var activeRequest: PlaybackRequest? = null
    // Async preflight may replace a source after pause; only explicit play/resume grants playback.
    private var playbackRequested = false
    @Volatile private var activeSessionId: Long = 0L
    private var forceHlsForActiveSession = false
    private var lastProgressSignalMs = 0L
    private var lastTsObservation: TransportStreamObservation? = null
    /** Presentation time must advance for playback to be considered alive. */
    private var lastReportedPtsUs = -1L
    private val prefetchExecutor = Executors.newSingleThreadExecutor()
    /** 正在运行的预检会话:reopen 时复用,避免重跑预检、丢弃已下载分片。 */
    @Volatile private var activePrefetchOriginalUri: String? = null
    @Volatile private var activePrefetchLocalPlaylistUri: String? = null
    /** 已被证明不适合代理的 host(确定性源形状原因);引擎生命周期内跳过其预检。 */
    private val prefetchUnsupportedHosts = HashMap<String, HlsPrefetchProxy.Reason>()
    @Volatile private var openGeneration = 0L
    @Volatile private var released = false
    private var lastMediaLoadDurationMs = -1L
    private var lastMediaLoadBytes = -1L
    private var lastLoadErrorCode = -1
    private val nativeLoadTracker = NativeMediaLoadTracker()
    private val firstFrameSessionGate = RenderedFirstFrameSessionGate()
    /** Set on every rendered first frame; cleared by play/stop/stopForSelectionChange. */
    @Volatile private var sessionRenderedFirstFrame = false
    private val audioSinkDispatcher = if (BuildConfig.KANTV_DIAGNOSTICS) CoalescingAudioSinkDispatcher(
        isDispatchThread = { Looper.myLooper() == mainHandler.looper },
        postDelayed = { task, delayMs -> mainHandler.postDelayed(task, delayMs) },
        postImmediate = { task -> mainHandler.post(task) },
        remove = mainHandler::removeCallbacks,
        dispatch = { token, update ->
            if (!released && token.sessionId == activeSessionId && token.generation == openGeneration) {
                listener(PlaybackEvent.Technical(token.sessionId, update))
            }
        },
    ) else null

    /**
     * 每个请求的 HTTP 工厂（含 Referer/UA 等请求头）。用委托工厂让 player 自己的
     * MediaSourceFactory 也能拿到逐请求的头，从而在 DRM 场景仍走 setMediaItem（provider 生效）。
     */
    @Volatile private var requestHttpFactory: DataSource.Factory = DefaultHttpDataSource.Factory()

    private val delegatingHttpFactory = DataSource.Factory {
        LoopbackDataSource.factory(requestHttpFactory).createDataSource()
    }

    private val hubDataSourceFactory: DataSource.Factory =
        DefaultDataSource.Factory(appContext, delegatingHttpFactory)

    /**
     * 玩家自己的 MediaSourceFactory，掛了 DrmSessionManagerProvider。
     *
     * 契约 §3.1 两个已知源码级坑：
     * 1. ClearKey 必须显式 `setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, …)`，
     *    否则 `DefaultDrmSessionManager.Builder()` 的默认 uuid 是 Widevine → 黑屏；
     * 2. 必须经 player 的 MediaSourceFactory（即 `setMediaItem`）进入，`setMediaSource` 会绕过 provider。
     */
    @Volatile private var activeDrmSessionManager: DrmSessionManager? = null

    private val hubMediaSourceFactory: DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(hubDataSourceFactory)
            .setDrmSessionManagerProvider { activeDrmSessionManager ?: DrmSessionManager.DRM_UNSUPPORTED }

    private val player = ExoPlayer.Builder(appContext, hubMediaSourceFactory)
        .setRenderersFactory(
            AudioOnlyExtensionRenderersFactory(appContext, audioSinkDispatcher ?: AudioSinkObservationTarget.None)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .apply {
                    // Some emulator hardware codecs accept interlaced input without rendering.
                    // Prefer platform software codecs only on emulators; physical TVs retain
                    // their normal hardware decoder and fallback order.
                    if (isEmulator()) setMediaCodecSelector(MediaCodecSelector.PREFER_SOFTWARE)
                },
        )
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    bufferProfile.minBufferMs,
                    bufferProfile.maxBufferMs,
                    bufferProfile.playbackBufferMs,
                    bufferProfile.rebufferMs,
                )
                .build(),
        )
        .build()

    init {
        debugLog(
            "ffmpeg_available=${FfmpegLibrary.isAvailable()} " +
                "version=${FfmpegLibrary.getVersion() ?: "unavailable"}",
        )
        playerView.player = player
        playerView.useController = false
        playerView.subtitleView?.apply {
            // Live captions stay readable without an opaque box obscuring the programme.
            setApplyEmbeddedStyles(false)
            setStyle(androidx.media3.ui.CaptionStyleCompat(
                android.graphics.Color.WHITE,
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                android.graphics.Color.BLACK,
                null,
            ))
        }
        player.setVideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
            val observedSessionId = activeSessionId
            val observedGeneration = openGeneration
            val callbackNanoTime = if (BuildConfig.KANTV_DIAGNOSTICS) System.nanoTime() else 0L
            val now = SystemClock.elapsedRealtime()
            audioSinkDispatcher?.onVideoFrame(observedSessionId, observedGeneration, presentationTimeUs, releaseTimeNs, callbackNanoTime)
            // 帧推进上报按 PROGRESS_SIGNAL_INTERVAL_MS 节流:原实现每帧(25-60fps)post 主线程
            // 并构造技术事件,是持续播放期的固定主线程开销。lastReportedPtsUs 仍逐帧推进,
            // 冻结检测的精度不受影响。
            if (now - lastProgressSignalMs < PROGRESS_SIGNAL_INTERVAL_MS) {
                if (BuildConfig.KANTV_DIAGNOSTICS && presentationTimeUs > lastReportedPtsUs) lastReportedPtsUs = presentationTimeUs
                return@setVideoFrameMetadataListener
            }
            mainHandler.post {
                if (observedSessionId == 0L || observedSessionId != activeSessionId) return@post
                // A metadata callback from a superseded open must not look like picture progress.
                if (observedGeneration != openGeneration) return@post
                lastProgressSignalMs = now
                if (BuildConfig.KANTV_DIAGNOSTICS) {
                    val advanced = presentationTimeUs > lastReportedPtsUs
                    if (advanced) {
                        lastReportedPtsUs = presentationTimeUs
                        listener(PlaybackEvent.Technical(
                            observedSessionId,
                            PlaybackTechnicalUpdate.VideoFrameAdvanced(presentationTimeUs, sessionRenderedFirstFrame),
                        ))
                    }
                    debugLog("video_progress session=$observedSessionId ptsUs=$presentationTimeUs")
                    listener(PlaybackEvent.Technical(
                        observedSessionId,
                        PlaybackTechnicalUpdate.VideoAboutToRender(
                            VideoRenderObservation(
                                presentationTimeUs = presentationTimeUs,
                                plannedReleaseTimeNs = releaseTimeNs,
                                callbackNanoTime = callbackNanoTime,
                                source = VideoTimingSource.ABOUT_TO_RENDER,
                            ),
                        ),
                    ))
                    hlsPrefetchProxy.latestTsObservation()?.let { observation ->
                        if (observation != lastTsObservation) {
                            lastTsObservation = observation
                            listener(PlaybackEvent.Technical(
                                observedSessionId,
                                PlaybackTechnicalUpdate.TransportStreamObserved(observation),
                            ))
                        }
                    }
                }
                listener(PlaybackEvent.VideoProgress(observedSessionId, presentationTimeUs))
            }
        }
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onTimelineChanged(
                eventTime: AnalyticsListener.EventTime,
                reason: Int,
            ) {
                recordTimelinePeriods(eventTime.timeline)
                if (eventTime.timeline.isEmpty && player.currentTimeline.isEmpty) {
                    audioSinkDispatcher?.onPeriod(null, 0)
                }
                if (owningSessionId(eventTime) != null && eventTime.timeline.getWindow(
                        eventTime.windowIndex, Timeline.Window(),
                    ).mediaItem.mediaId == "$activeSessionId:$openGeneration"
                ) {
                    reportLiveContext(activeSessionId)
                    val timeline = eventTime.timeline
                    audioSinkDispatcher?.onPeriod(
                        if (timeline.periodCount == 1) timeline.getUidOfPeriod(0) else null,
                        timeline.periodCount,
                    )
                }
            }

            override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
                if (nativeObservationSessionId(eventTime) != null) audioSinkDispatcher?.setAvPlaying(isPlaying)
            }

            override fun onPositionDiscontinuity(
                eventTime: AnalyticsListener.EventTime,
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (nativeObservationSessionId(eventTime) != null) {
                    audioSinkDispatcher?.onClockReset()
                    nativeLoadTracker.invalidate()
                    reportLiveContext(activeSessionId)
                }
            }

            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                val sessionId = sessionIdFor(eventTime) ?: return
                if (nativeObservationSessionId(eventTime) != null) {
                    reportLiveContext(sessionId)
                    audioSinkDispatcher?.setAvPlaying(state == Player.STATE_READY && player.isPlaying)
                }
                when (state) {
                    Player.STATE_BUFFERING -> listener(PlaybackEvent.Loading(sessionId))
                    Player.STATE_READY -> listener(PlaybackEvent.Ready(sessionId))
                    Player.STATE_ENDED -> listener(PlaybackEvent.Ended(sessionId))
                }
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                val sessionId = firstFrameSessionId(eventTime) ?: return
                sessionRenderedFirstFrame = true
                debugLog("first_frame session=$sessionId")
                listener(PlaybackEvent.Technical(
                    sessionId,
                    PlaybackTechnicalUpdate.VideoFirstFrame(renderTimeMs),
                ))
                listener(PlaybackEvent.FirstFrame(sessionId))
            }

            override fun onLoadStarted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                retryCount: Int,
            ) {
                if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) {
                    if (nativeObservationSessionId(eventTime) != null) {
                        nativeLoadTracker.nonMediaRequest(loadEventInfo.dataSpec, terminal = false)
                    }
                    return
                }
                val sessionId = nativeLoadObservationSessionId(eventTime) ?: return
                nativeLoadTracker.started(
                    loadEventInfo.loadTaskId, eventTime.mediaPeriodId?.periodUid,
                    loadTaskTiming(loadEventInfo, mediaLoadData, retryCount.toLong()),
                    requestIdentity = loadEventInfo.dataSpec,
                )
                reportLiveContext(sessionId)
            }

            override fun onLoadCanceled(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            ) {
                if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) {
                    if (nativeObservationSessionId(eventTime) != null) {
                        nativeLoadTracker.nonMediaRequest(loadEventInfo.dataSpec, terminal = true)
                    }
                    return
                }
                val sessionId = nativeLoadObservationSessionId(eventTime) ?: return
                nativeLoadTracker.finished(loadEventInfo.loadTaskId, eventTime.mediaPeriodId?.periodUid,
                    loadTaskTiming(loadEventInfo, mediaLoadData), "CANCELED")
                reportLiveContext(sessionId)
            }

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            ) {
                val sessionId = sessionIdFor(eventTime) ?: return
                if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA && nativeObservationSessionId(eventTime) != null) {
                    nativeLoadTracker.nonMediaRequest(loadEventInfo.dataSpec, terminal = true)
                }
                if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA && nativeObservationSessionId(eventTime) != null) {
                    if (nativeLoadObservationSessionId(eventTime) != null) {
                        nativeLoadTracker.finished(loadEventInfo.loadTaskId, eventTime.mediaPeriodId?.periodUid,
                            loadTaskTiming(loadEventInfo, mediaLoadData), "COMPLETED")
                    }
                    lastMediaLoadDurationMs = loadEventInfo.loadDurationMs.coerceAtLeast(-1)
                    lastMediaLoadBytes = loadEventInfo.bytesLoaded.coerceAtLeast(-1)
                    reportLiveContext(sessionId)
                }
                if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA && loadEventInfo.bytesLoaded > 0) {
                    listener(PlaybackEvent.Technical(sessionId, PlaybackTechnicalUpdate.PreparingPlayback))
                }
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                error: java.io.IOException,
                wasCanceled: Boolean,
            ) {
                val sessionId = nativeObservationSessionId(eventTime) ?: return
                lastLoadErrorCode = findHttpStatus(error)?.takeIf { it in 100..599 } ?: -1
                if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA && wasCanceled) {
                    nativeLoadTracker.nonMediaRequest(loadEventInfo.dataSpec, terminal = true)
                }
                if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA && nativeLoadObservationSessionId(eventTime) != null) {
                    nativeLoadTracker.finished(loadEventInfo.loadTaskId, eventTime.mediaPeriodId?.periodUid,
                        loadTaskTiming(loadEventInfo, mediaLoadData, httpStatus = lastLoadErrorCode.toLong()),
                        if (wasCanceled) "ERROR_TERMINAL" else "ERROR_RETRYING")
                }
                reportLiveContext(sessionId)
            }

            override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
                val sessionId = sessionIdFor(eventTime) ?: return
                val groups = tracks.groups
                if (BuildConfig.DEBUG && BuildConfig.KANTV_DIAGNOSTICS) {
                    val audioObservation = summarizeAudioTracks(
                        groups.asSequence()
                            .filter { it.type == C.TRACK_TYPE_AUDIO }
                            .flatMap { group ->
                                (0 until group.length).asSequence().map { index ->
                                    val format = group.getTrackFormat(index)
                                    AudioTrackObservationInput(
                                        supported = group.isTrackSupported(index),
                                        selected = group.isTrackSelected(index),
                                        ffmpegSupported = format.sampleMimeType?.let(FfmpegLibrary::supportsFormat) == true,
                                        codecFamily = audioCodecFamily(format),
                                    )
                                }
                            }
                            .toList(),
                    )
                    debugLog(
                        "tracks session=$sessionId groups=${groups.size} " +
                            "types=${groups.joinToString(",") { it.type.toString() }}",
                    )
                    debugLog(
                        "audio_tracks session=$sessionId total=${audioObservation.total} " +
                            "supported=${audioObservation.supported} selected=${audioObservation.selected} " +
                            "ffmpeg_supported=${audioObservation.ffmpegSupported} " +
                            "codec_families=${audioObservation.codecFamilies.joinToString(",") { it.name }} " +
                            "ffmpeg_available=${FfmpegLibrary.isAvailable()}",
                    )
                }
                val trackInfo = groups.flatMapIndexed { groupIndex, group ->
                    (0 until group.length).map { index ->
                        group.getTrackFormat(index).toTrackInfo(
                            kind = group.type.toTrackKind(),
                            selected = group.isTrackSelected(index),
                            supported = group.isTrackSupported(index),
                        ).copy(selectionId = "m3:$sessionId:$groupIndex:$index", label = group.getTrackFormat(index).label)
                    }
                }
                val detectedContainerMimeType = groups.asSequence()
                    .flatMap { group -> (0 until group.length).asSequence().map(group::getTrackFormat) }
                    .mapNotNull(Format::containerMimeType)
                    .firstOrNull()
                val containerMimeType = detectedContainerMimeType ?: activeRequest?.mimeType
                listener(
                    PlaybackEvent.Technical(
                        sessionId,
                        PlaybackTechnicalUpdate.TracksDetected(
                            containerMimeType = containerMimeType,
                            containerInfoSource = when {
                                detectedContainerMimeType != null -> ContainerInfoSource.PLAYER_DETECTED
                                containerMimeType != null -> ContainerInfoSource.REQUEST_HINT
                                else -> null
                            },
                            tracks = trackInfo,
                        ),
                    ),
                )
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                val sessionId = sessionIdFor(eventTime) ?: return
                debugLog("video_decoder session=$sessionId name=$decoderName")
                listener(
                    PlaybackEvent.Technical(
                        sessionId,
                        PlaybackTechnicalUpdate.VideoDecoderSelected(decoderName),
                    ),
                )
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                val sessionId = sessionIdFor(eventTime) ?: return
                debugLog("audio_decoder session=$sessionId name=$decoderName")
                listener(PlaybackEvent.Technical(sessionId, PlaybackTechnicalUpdate.AudioDecoderSelected(decoderName)))
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                val sessionId = sessionIdFor(eventTime) ?: return
                listener(PlaybackEvent.Technical(sessionId, PlaybackTechnicalUpdate.AudioFormatChanged(
                    mimeType = format.sampleMimeType,
                    codecs = format.codecs,
                    channelCount = format.channelCount.takeIf { it != Format.NO_VALUE } ?: -1,
                    sampleRate = format.sampleRate.takeIf { it != Format.NO_VALUE } ?: -1,
                )))
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                val sessionId = sessionIdFor(eventTime) ?: return
                if (droppedFrames > 0) {
                    listener(
                        PlaybackEvent.Technical(
                            sessionId,
                            PlaybackTechnicalUpdate.DroppedVideoFrames(droppedFrames, elapsedMs),
                        ),
                    )
                }
            }

            override fun onVideoFrameProcessingOffset(
                eventTime: AnalyticsListener.EventTime,
                totalProcessingOffsetUs: Long,
                frameCount: Int,
            ) {
                val sessionId = sessionIdFor(eventTime) ?: return
                listener(PlaybackEvent.Technical(
                    sessionId,
                    PlaybackTechnicalUpdate.VideoProcessingOffset(totalProcessingOffsetUs, frameCount),
                ))
            }

            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                // An error that names the candidate we already replaced is not evidence about the
                // current one; forwarding it would let the old candidate drive recovery.
                if (isFromAnotherMediaItem(eventTime)) {
                    debugLog("player_error_stale code=${error.errorCode}")
                    return
                }
                // No substitution here: during the next candidate's preflight the previous item has
                // already been cleared, so `activeSessionId` would credit this error to the wrong
                // candidate. Without an owner the error is dropped.
                val sessionId = sessionIdFor(eventTime) ?: run {
                    debugLog("player_error_unowned code=${error.errorCode}")
                    return
                }
                if (nativeLoadObservationSessionId(eventTime) != null) reportLiveContext(sessionId)
                val httpCause = generateSequence(error.cause) { it.cause }
                    .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
                debugLog(
                    "player_error session=$sessionId code=${error.errorCode} " +
                        "http=${httpCause?.responseCode ?: -1} " +
                        "forceHls=$forceHlsForActiveSession",
                )
                if (
                    sessionId == activeSessionId &&
                    !forceHlsForActiveSession &&
                    activeRequest?.mimeType == null &&
                    error.hasCause<UnrecognizedInputFormatException>()
                ) {
                    forceHlsForActiveSession = true
                    debugLog("retry_original_hls session=$sessionId")
                    activeRequest?.let { prepare(it, resetPosition = true) }
                    return
                }
                listener(PlaybackEvent.Error(sessionId, classify(error), error.errorCode))
            }
        })
    }

    override fun setEventListener(listener: (PlaybackEvent) -> Unit) {
        this.listener = listener
    }

    override fun play(sessionId: Long, request: PlaybackRequest) {
        playbackRequested = true
        activeSessionId = sessionId
        firstFrameSessionGate.begin(sessionId, SystemClock.elapsedRealtime())
        sessionRenderedFirstFrame = false
        lastReportedPtsUs = -1L
        activeRequest = request
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            // 音频 offload 把解码/播放下放到 DSP,长时间观看显著省电省 CPU;
            // 不支持的设备会自动忽略该偏好。不开视频 tunneling:tunneling 模式下
            // MediaCodecVideoRenderer 不再回调 frameMetadataListener,会弄瞎本应用的
            // 帧推进/冻结检测。
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
                    )
                    .build(),
            )
            .build()
        forceHlsForActiveSession = false
        lastProgressSignalMs = 0L
        lastTsObservation = null
        open(request)
    }

    override fun reopen(sessionId: Long) {
        if (sessionId != activeSessionId) return
        // A reopen keeps the same external session but starts a new Media3 open generation, so the
        // picture has to render again before it counts as "presented".
        sessionRenderedFirstFrame = false
        lastReportedPtsUs = -1L
        activeRequest?.let(::open)
    }

    override fun recoverToLive(sessionId: Long) {
        if (sessionId != activeSessionId) return
        player.seekToDefaultPosition()
        player.prepare()
        player.playWhenReady = playbackRequested
    }

    override fun setPictureMode(mode: PictureMode) {
        playerView.resizeMode = when (mode) {
            PictureMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            PictureMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            PictureMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
    }

    override fun selectTrack(sessionId: Long, kind: PlaybackTrackKind, selectionId: String?): Boolean {
        if (sessionId != activeSessionId || sessionId == 0L) return false
        val type = when (kind) {
            PlaybackTrackKind.AUDIO -> C.TRACK_TYPE_AUDIO
            PlaybackTrackKind.SUBTITLE -> C.TRACK_TYPE_TEXT
            else -> return false
        }
        val parameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
        if (selectionId == null && kind == PlaybackTrackKind.SUBTITLE) {
            player.trackSelectionParameters = parameters.setTrackTypeDisabled(type, true).build()
            return true
        }
        val parts = selectionId?.split(":") ?: return false
        if (parts.size != 4 || parts[0] != "m3" || parts[1].toLongOrNull() != sessionId) return false
        val group = player.currentTracks.groups.getOrNull(parts[2].toIntOrNull() ?: return false) ?: return false
        val index = parts[3].toIntOrNull() ?: return false
        if (group.type != type || index !in 0 until group.length || !group.isTrackSupported(index)) return false
        player.trackSelectionParameters = parameters.setTrackTypeDisabled(type, false)
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index)).build()
        return true
    }

    override fun pause() {
        playbackRequested = false
        audioSinkDispatcher?.setAvPlaying(false)
        player.pause()
    }

    override fun resume() {
        if (activeRequest != null) {
            playbackRequested = true
            player.play()
        }
    }

    override fun stop() {
        stopForSelectionChange()
        player.stop()
        player.clearMediaItems()
    }

    override fun stopForSelectionChange() {
        playbackRequested = false
        if (BuildConfig.KANTV_DIAGNOSTICS) nativeLoadTracker.invalidate(-1)
        if (activeSessionId != 0L) reportLiveContext(activeSessionId)
        audioSinkDispatcher?.deactivate()
        firstFrameSessionGate.end()
        openGeneration++
        activeRequest = null
        activeSessionId = 0L
        forceHlsForActiveSession = false
        activeDrmSessionManager = null
        activePrefetchOriginalUri = null
        activePrefetchLocalPlaylistUri = null
        lastProgressSignalMs = 0L
        sessionRenderedFirstFrame = false
        lastReportedPtsUs = -1L
        hlsPrefetchProxy.abortPendingSession()
        prefetchExecutor.execute { hlsPrefetchProxy.stop() }
        // 不调用 player.stop()/clearMediaItems():解码器与渲染链保持热备,下一次 play()
        // 通过 setMediaSource/setMediaItem 直接复用。
    }

    override fun release() {
        if (BuildConfig.KANTV_DIAGNOSTICS) nativeLoadTracker.invalidate(-1)
        if (activeSessionId != 0L) reportLiveContext(activeSessionId)
        audioSinkDispatcher?.close()
        released = true
        openGeneration++
        hlsPrefetchProxy.abortPendingSession()
        playerView.player = null
        player.release()
        prefetchExecutor.execute { hlsPrefetchProxy.close() }
        prefetchExecutor.shutdown()
    }

    private fun open(request: PlaybackRequest) {
        lastMediaLoadDurationMs = -1
        lastMediaLoadBytes = -1
        lastLoadErrorCode = -1
        val observedGeneration = ++openGeneration
        val observedSessionId = activeSessionId
        if (BuildConfig.KANTV_DIAGNOSTICS) nativeLoadTracker.activate(observedGeneration)
        audioSinkDispatcher?.activate(AudioSinkDispatchToken(observedSessionId, observedGeneration))
        listener(PlaybackEvent.Technical(observedSessionId, PlaybackTechnicalUpdate.RuntimeGeneration(observedGeneration)))
        hlsPrefetchProxy.abortPendingSession()
        if (!request.isOrdinaryHlsCandidate()) {
            activePrefetchOriginalUri = null
            activePrefetchLocalPlaylistUri = null
            prefetchExecutor.execute { hlsPrefetchProxy.stop() }
            reportDelivery(observedSessionId, StreamDeliveryStrategy.DIRECT, StreamDeliveryReason.DEFAULT)
            reportPrefetchStartup(
                observedSessionId,
                HlsPrefetchProxy.PrefetchStatus(
                    // The source is not an ordinary media playlist, so no preflight ran at all. This
                    // is reported explicitly instead of leaving the outcome ambiguous.
                    startupOutcome = PrefetchStartupPolicy.StartupOutcome.NOT_APPLICABLE,
                    startupReason = null,
                    startupWaitMs = -1L,
                    startupCapMs = -1L,
                    queued = -1,
                    ready = -1,
                    failed = -1,
                    cancelled = -1,
                    targetDurationMs = -1L,
                    firstSegmentElapsedMs = null,
                ),
            )
            prepare(request, resetPosition = true)
            return
        }
        // reopen 复用:同一源的预检会话仍在运行时直接使用其 localhost 播放单,
        // 不重跑预检,已下载分片继续服务。
        val runningLocalUri = activePrefetchLocalPlaylistUri
        if (runningLocalUri != null && activePrefetchOriginalUri == request.uri) {
            reportDelivery(
                observedSessionId,
                StreamDeliveryStrategy.HLS_CACHED_PLAYLIST,
                StreamDeliveryReason.PREFETCH_ENABLED,
            )
            prepare(cachedHlsPlaybackRequest(request, runningLocalUri), resetPosition = true)
            return
        }
        val host = runCatching { Uri.parse(request.uri).host }.getOrNull()
        val remembered = host?.let(prefetchUnsupportedHosts::get)
        if (remembered != null) {
            // 该 host 的源形状已被证明不适合代理(主播放单/加密/字节范围等确定性原因),
            // 跳过预检直接起播,省掉每次换台的一次探测往返。
            activePrefetchOriginalUri = null
            activePrefetchLocalPlaylistUri = null
            prefetchExecutor.execute { hlsPrefetchProxy.stop() }
            reportDelivery(observedSessionId, StreamDeliveryStrategy.DIRECT, remembered.toDeliveryReason())
            prepare(request, resetPosition = true)
            return
        }
        // 起播与预检并行:先直连 prepare 让画面尽快出现,预检在后台验证可解码起点;
        // 验证通过后同会话内切到 localhost 缓存播放单,获得分片预取的抗抖动能力。
        activePrefetchOriginalUri = null
        activePrefetchLocalPlaylistUri = null
        reportDelivery(observedSessionId, StreamDeliveryStrategy.DIRECT, StreamDeliveryReason.PREFETCH_PENDING)
        prepare(request, resetPosition = true)
        prefetchExecutor.execute {
            if (released || observedGeneration != openGeneration) return@execute
            val result = hlsPrefetchProxy.start(request.uri, request.requestHeaders) {
                released || observedGeneration != openGeneration || observedSessionId != activeSessionId
            }
            val status = hlsPrefetchProxy.status()
            mainHandler.post {
                if (
                    released ||
                    observedGeneration != openGeneration ||
                    observedSessionId == 0L ||
                    observedSessionId != activeSessionId
                ) return@post
                reportPrefetchStartup(observedSessionId, status)
                when (result) {
                    is HlsPrefetchProxy.StartResult.Started -> {
                        debugLog("prefetch_started session=$observedSessionId")
                        activePrefetchOriginalUri = request.uri
                        activePrefetchLocalPlaylistUri = result.localPlaylistUri
                        reportDelivery(
                            observedSessionId,
                            StreamDeliveryStrategy.HLS_CACHED_PLAYLIST,
                            StreamDeliveryReason.PREFETCH_ENABLED,
                        )
                        prepare(
                            cachedHlsPlaybackRequest(request, result.localPlaylistUri),
                            resetPosition = true,
                        )
                    }
                    is HlsPrefetchProxy.StartResult.Fallback -> {
                        rememberPrefetchUnsupported(host, result.reason)
                        // 直连播放已在进行;这里只更新投递方式报告,不再重复 prepare。
                        reportDelivery(observedSessionId, StreamDeliveryStrategy.DIRECT, result.reason.toDeliveryReason())
                    }
                    is HlsPrefetchProxy.StartResult.Failed -> {
                        reportDelivery(observedSessionId, StreamDeliveryStrategy.DIRECT, result.reason.toDeliveryReason())
                    }
                }
            }
        }
    }

    /**
     * 只记忆确定性的「源形状不适合代理」原因(主播放单/加密/字节范围等)。
     * NETWORK / UNVERIFIED_BOOTSTRAP 是瞬时失败,不记忆,下次换台仍允许重试。
     */
    private fun rememberPrefetchUnsupported(host: String?, reason: HlsPrefetchProxy.Reason) {
        if (host.isNullOrBlank()) return
        when (reason) {
            HlsPrefetchProxy.Reason.MASTER_PLAYLIST,
            HlsPrefetchProxy.Reason.ENCRYPTED,
            HlsPrefetchProxy.Reason.INITIALIZATION_SEGMENT,
            HlsPrefetchProxy.Reason.INVALID_MEDIA_PLAYLIST,
            HlsPrefetchProxy.Reason.BYTERANGE,
            HlsPrefetchProxy.Reason.GAP,
            HlsPrefetchProxy.Reason.LOW_LATENCY_HLS,
            HlsPrefetchProxy.Reason.UNSUPPORTED_CODEC,
            -> prefetchUnsupportedHosts[host] = reason
            HlsPrefetchProxy.Reason.NETWORK,
            HlsPrefetchProxy.Reason.UNVERIFIED_BOOTSTRAP,
            -> Unit
        }
    }

    /**
     * 每个请求头的 HTTP 工厂缓存。DefaultHttpDataSource 不带连接池,但同一工厂实例至少
     * 共享 HttpURLConnection 的 keep-alive 语义;避免每次 prepare 重建对象。
     * 键是请求头内容(Referer/UA 等),不同头的源互不串扰。
     */
    private val httpFactoryCache = HashMap<Map<String, String>, DefaultHttpDataSource.Factory>()

    private fun httpFactoryFor(headers: Map<String, String>): DefaultHttpDataSource.Factory =
        httpFactoryCache.getOrPut(headers) {
            DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(8_000)
                // 允许 http→https 等跨协议跳转:部分 CDN 入口是 http 但跳到 https。
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers)
        }

    private fun prepare(request: PlaybackRequest, resetPosition: Boolean) {
        // A new candidate begins here. Forget the previous candidate's MediaPeriod ownership first:
        // the media item id carries the session id only, so without this fence a late callback from
        // the replaced candidate would look like the current one.
        firstFrameSessionGate.forgetPeriods()
        val httpFactory = httpFactoryFor(request.requestHeaders)
        requestHttpFactory = httpFactory
        val isLocalPrefetch = Uri.parse(request.uri).host == "127.0.0.1"
        val delegateFactory: DataSource.Factory = DefaultDataSource.Factory(
            appContext,
            LoopbackDataSource.factory(httpFactory),
        )
        val observedGeneration = openGeneration
        val observedSessionId = activeSessionId
        val dataSourceFactory = if (BuildConfig.KANTV_DIAGNOSTICS) {
            val pipeline = nativeLoadTracker.preparePipeline(observedGeneration, request.drm == null)
            reportLiveContext(observedSessionId)
            DataSource.Factory {
                ObservingDataSource(
                    delegateFactory.createDataSource(),
                    captureEpoch = { nativeLoadTracker.captureTransferEpoch(observedGeneration, pipeline) },
                    summary = { epoch, summary ->
                        mainHandler.post {
                            if (!released && observedSessionId == activeSessionId && observedGeneration == openGeneration &&
                                nativeLoadTracker.transferFinished(epoch, summary)
                            ) reportLiveContext(observedSessionId)
                        }
                    },
                )
            }
        } else delegateFactory
        val mediaItem = MediaItem.Builder()
            // The external session remains stable while the coordinator tries another candidate.
            // Include the open generation so a late Loading/Ready/Error from the replaced MediaItem
            // is rejected by Media3EventOwnership instead of contaminating the new candidate.
            .setMediaId("$activeSessionId:$openGeneration")
            .setUri(request.uri)
            .apply { request.mimeType?.let(::setMimeType) }
            .build()
        val useHls = forceHlsForActiveSession || request.mimeType == MimeTypes.APPLICATION_M3U8
        debugLog(
            "prepare session=$activeSessionId localProxy=$isLocalPrefetch " +
                "useHls=$useHls mime=${request.mimeType ?: "none"} drm=${request.drm != null}",
        )
        if (request.drm != null) {
            // DRM 路径必须让 player 自己的 MediaSourceFactory（含 DrmSessionManagerProvider）生效，
            // 因此使用 setMediaItem 而不是 setMediaSource。
            activeDrmSessionManager = clearKeySessionManager(request.drm)
            // 不先 stop():setMediaItem 替换媒体项时渲染器链保持热备,同编码流可复用解码器。
            player.setMediaItem(mediaItem, resetPosition)
            player.prepare()
            player.playWhenReady = playbackRequested
            return
        }
        activeDrmSessionManager = null
        val tsFlags = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        val mediaSource = when {
            useHls -> HlsMediaSource.Factory(dataSourceFactory)
                .setExtractorFactory(DefaultHlsExtractorFactory(tsFlags, true))
                .createMediaSource(mediaItem)
            else -> DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
        }
        // 不先 stop()/clearMediaItems():setMediaSource 替换媒体项时渲染器链保持热备,
        // 同编码流可复用解码器,换台不再需要重新初始化解码器。
        player.setMediaSource(mediaSource, resetPosition)
        player.prepare()
        player.playWhenReady = playbackRequested
    }

    /**
     * 构造 ClearKey 的 DRM 会话管理器。
     * 显式指定 ClearKey uuid（默认值是 Widevine，会导致黑屏），密钥来自内联 key response。
     * 构造失败时不抛出：返回 null 让内容走「无 DRM」路径，由播放错误路径给出用户可见失败。
     */
    private fun clearKeySessionManager(drm: DrmRequest): DrmSessionManager? = try {
        DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(LocalMediaDrmCallback(drm.keyResponseJson.toByteArray(Charsets.UTF_8)))
    } catch (_: Exception) {
        debugLog("clearkey_manager_failed")
        null
    }

    /**
     * True when a callback's timeline names a *different* MediaItem than the one playing. That is a
     * previous open generation and its errors must not be attributed to the current candidate.
     *
     * Only a genuine mismatch is reported: an empty timeline means "no information", which callers
     * may resolve through the session-identity fallback instead of dropping.
     */
    private fun isFromAnotherMediaItem(eventTime: AnalyticsListener.EventTime): Boolean {
        if (eventTime.timeline.isEmpty) return false
        val index = eventTime.windowIndex
        if (index !in 0 until eventTime.timeline.windowCount) return false
        return Media3EventOwnership.isFromAnotherMediaItem(
            eventTimelineMediaId = eventTime.timeline.getWindow(index, Timeline.Window()).mediaItem.mediaId,
            currentMediaItemMediaId = player.currentMediaItem?.mediaId,
        )
    }

    /**
     * Every Media3 callback is attributed to the MediaItem that is actually playing. A callback for
     * any other item belongs to a previous open generation — for example the candidate that was
     * replaced during a same-session source switch — and must never be able to move the current
     * candidate's state machine. The decision itself lives in [Media3EventOwnership] so it is
     * unit-testable without a player.
     */
    private fun owningSessionId(eventTime: AnalyticsListener.EventTime): Long? {
        if (eventTime.timeline.isEmpty) return null
        val index = eventTime.windowIndex
        if (index !in 0 until eventTime.timeline.windowCount) return null
        return Media3EventOwnership.owningSessionId(
            eventTimelineMediaId = eventTime.timeline.getWindow(index, Timeline.Window()).mediaItem.mediaId,
            currentMediaItemMediaId = player.currentMediaItem?.mediaId,
            activeSessionId = activeSessionId,
        )
    }

    /**
     * Owner of an ordinary callback (playback state, tracks, decoders, metrics).
     *
     * The MediaPeriod fallback is deliberately *not* used here: it exists for the documented
     * first-frame-with-empty-timeline case only. During a source switch the timeline can transiently
     * be empty while the previous candidate's period is still recorded, and accepting a state change
     * from that period would let the replaced candidate move the new candidate's state machine.
     */
    private fun sessionIdFor(eventTime: AnalyticsListener.EventTime): Long? {
        val directSessionId = owningSessionId(eventTime) ?: return null
        firstFrameSessionGate.record(eventTime.mediaPeriodId?.periodUid, directSessionId)
        return directSessionId
    }

    /**
     * Register immutable period ownership as soon as Media3 publishes a non-empty timeline.
     *
     * A period is only ever recorded for the session that currently owns the player, so a period
     * created by a previous open can never credit the candidate that replaced it.
     */
    private fun recordTimelinePeriods(timeline: Timeline) {
        if (timeline.isEmpty || activeSessionId == 0L) return
        val period = Timeline.Period()
        val window = Timeline.Window()
        for (periodIndex in 0 until timeline.periodCount) {
            timeline.getPeriod(periodIndex, period, true)
            val windowIndex = period.windowIndex
            if (windowIndex !in 0 until timeline.windowCount) continue
            val sessionId = Media3EventOwnership.sessionIdFromMediaItemId(
                timeline.getWindow(windowIndex, window).mediaItem.mediaId,
            )
                ?: continue
            firstFrameSessionGate.record(period.uid, sessionId)
        }
    }

    /** Diagnostic callbacks cannot borrow the still-playing prior item during a new preflight. */
    private fun nativeObservationSessionId(eventTime: AnalyticsListener.EventTime): Long? {
        if (!BuildConfig.KANTV_DIAGNOSTICS) return null
        val sessionId = owningSessionId(eventTime) ?: return null
        val mediaId = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId
        return sessionId.takeIf { mediaId == "$sessionId:$openGeneration" }
    }

    private fun nativeLoadObservationSessionId(eventTime: AnalyticsListener.EventTime): Long? {
        val sessionId = nativeObservationSessionId(eventTime) ?: return null
        val uid = eventTime.mediaPeriodId?.periodUid ?: return null
        return sessionId.takeIf { player.currentTimeline.getIndexOfPeriod(uid) != C.INDEX_UNSET }
    }

    private fun loadTaskTiming(
        info: androidx.media3.exoplayer.source.LoadEventInfo,
        data: androidx.media3.exoplayer.source.MediaLoadData,
        retryCount: Long = -1,
        httpStatus: Long = -1,
    ) = NativeLoadTaskTiming(
        trackType = when (data.trackType) {
            C.TRACK_TYPE_DEFAULT -> "DEFAULT"
            C.TRACK_TYPE_AUDIO -> "AUDIO"
            C.TRACK_TYPE_VIDEO -> "VIDEO"
            C.TRACK_TYPE_TEXT -> "TEXT"
            else -> "UNKNOWN"
        },
        startElapsedMs = if (info.loadDurationMs in 0..info.elapsedRealtimeMs) info.elapsedRealtimeMs - info.loadDurationMs else -1,
        endElapsedMs = info.elapsedRealtimeMs.coerceAtLeast(-1),
        mediaStartMs = data.mediaStartTimeMs.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(-1) ?: -1,
        mediaEndMs = data.mediaEndTimeMs.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(-1) ?: -1,
        retryCount = retryCount,
        bytes = info.bytesLoaded.coerceAtLeast(-1),
        httpStatus = httpStatus,
    )

    private fun reportLiveContext(sessionId: Long) {
        if (!BuildConfig.KANTV_DIAGNOSTICS) return
        if (sessionId == 0L || sessionId != activeSessionId ||
            player.currentMediaItem?.mediaId != "$sessionId:$openGeneration"
        ) return
        val timeline = player.currentTimeline
        val index = player.currentMediaItemIndex
        val window = if (!timeline.isEmpty && index in 0 until timeline.windowCount) {
            timeline.getWindow(index, Timeline.Window())
        } else null
        val positionMs = player.currentPosition.coerceAtLeast(-1)
        val period = if (!timeline.isEmpty && player.currentPeriodIndex in 0 until timeline.periodCount) {
            timeline.getPeriod(player.currentPeriodIndex, Timeline.Period(), true)
        } else null
        val windowDurationMs = window?.durationMs?.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(-1) ?: -1
        val loadObservation = nativeLoadTracker.snapshot(
            currentPeriodUid = period?.uid,
            periodCount = timeline.periodCount,
            windowStartInPeriodMs = period?.positionInWindowMs?.let { -it },
            playbackPositionMs = positionMs,
            windowDurationMs = windowDurationMs,
        )
        listener(PlaybackEvent.Technical(sessionId, PlaybackTechnicalUpdate.M3uLiveContext(
            M3uLiveObservation(
                observedNanoTime = System.nanoTime(),
                positionMs = positionMs,
                bufferedPositionMs = player.bufferedPosition.coerceAtLeast(-1),
                bufferDurationMs = player.totalBufferedDuration.coerceAtLeast(-1),
                liveOffsetMs = player.currentLiveOffset.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(-1) ?: -1,
                windowDurationMs = windowDurationMs,
                mediaSequence = (player.currentManifest as? HlsManifest)?.mediaPlaylist?.mediaSequence ?: -1,
                lastMediaLoadDurationMs = lastMediaLoadDurationMs,
                lastMediaLoadBytes = lastMediaLoadBytes,
                lastLoadErrorCode = lastLoadErrorCode,
            ),
        )))
        listener(PlaybackEvent.Technical(sessionId, PlaybackTechnicalUpdate.NativeMediaLoadObserved(loadObservation)))
    }

    /**
     * Media3 can report a genuine rendered-first-frame with an empty analytics timeline while a
     * prefetch fallback or extractor retry is replacing the MediaSource. Resolve it from the
     * previously observed MediaPeriod mapping; never infer ownership from the mutable current item.
     * A callback that names a *different* MediaItem than the one playing is a previous open
     * generation and is dropped.
     */
    private fun firstFrameSessionId(eventTime: AnalyticsListener.EventTime): Long? =
        Media3EventOwnership.firstFrameSessionId(
            owningSessionId = owningSessionId(eventTime),
            periodUid = eventTime.mediaPeriodId?.periodUid,
            eventRealtimeMs = eventTime.realtimeMs,
            gate = firstFrameSessionGate,
        )

    private fun Format.toTrackInfo(
        kind: PlaybackTrackKind,
        selected: Boolean,
        supported: Boolean,
    ) = PlaybackTrackInfo(
        kind = kind,
        codec = codecs,
        mimeType = sampleMimeType,
        language = language,
        width = width.takeIf { it != Format.NO_VALUE },
        height = height.takeIf { it != Format.NO_VALUE },
        frameRate = frameRate.takeIf { it != Format.NO_VALUE.toFloat() && it > 0f },
        channelCount = channelCount.takeIf { it != Format.NO_VALUE },
        sampleRate = sampleRate.takeIf { it != Format.NO_VALUE },
        selected = selected,
        supported = supported,
    )

    private fun Int.toTrackKind(): PlaybackTrackKind = when (this) {
        C.TRACK_TYPE_VIDEO -> PlaybackTrackKind.VIDEO
        C.TRACK_TYPE_AUDIO -> PlaybackTrackKind.AUDIO
        C.TRACK_TYPE_TEXT -> PlaybackTrackKind.SUBTITLE
        else -> PlaybackTrackKind.OTHER
    }

    private fun classify(error: PlaybackException): PlaybackErrorKind {
        if (!hasValidatedInternet()) return PlaybackErrorKind.OFFLINE
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            return PlaybackErrorKind.BEHIND_LIVE_WINDOW
        }
        findHttpStatus(error)?.let { status ->
            if (status == 401 || status == 403) return PlaybackErrorKind.AUTHORIZATION
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            -> PlaybackErrorKind.TEMPORARY_NETWORK
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> PlaybackErrorKind.UNSUPPORTED_FORMAT
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            -> PlaybackErrorKind.DECODER
            else -> PlaybackErrorKind.UNKNOWN
        }
    }

    private fun findHttpStatus(error: Throwable): Int? {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
            cause = cause.cause
        }
        return null
    }

    private fun hasValidatedInternet(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun reportPrefetchStartup(sessionId: Long, status: HlsPrefetchProxy.PrefetchStatus) {
        listener(
            PlaybackEvent.Technical(
                sessionId,
                PlaybackTechnicalUpdate.PrefetchStartup(
                    queued = status.queued,
                    ready = status.ready,
                    failed = status.failed,
                    cancelled = status.cancelled,
                    waitMs = status.startupWaitMs,
                    capMs = status.startupCapMs,
                    segmentCount = status.segmentCount(),
                    targetDurationMs = status.targetDurationMs,
                    outcome = status.startupOutcome,
                ),
            ),
        )
    }

    private fun reportDelivery(
        sessionId: Long,
        strategy: StreamDeliveryStrategy,
        reason: StreamDeliveryReason,
    ) {
        listener(
            PlaybackEvent.Technical(
                sessionId,
                PlaybackTechnicalUpdate.StreamDeliverySelected(strategy, reason),
            ),
        )
    }

    private fun PlaybackRequest.isOrdinaryHlsCandidate(): Boolean {
        val type = mimeType?.lowercase().orEmpty()
        if ("mpegurl" in type) return true
        return runCatching { Uri.parse(uri).lastPathSegment?.substringBefore('?')?.lowercase() }
            .getOrNull()
            ?.endsWith(".m3u8") == true
    }

    private fun HlsPrefetchProxy.Reason.toDeliveryReason(): StreamDeliveryReason = when (this) {
        HlsPrefetchProxy.Reason.MASTER_PLAYLIST -> StreamDeliveryReason.MASTER_PLAYLIST
        HlsPrefetchProxy.Reason.ENCRYPTED -> StreamDeliveryReason.ENCRYPTED_PLAYLIST
        HlsPrefetchProxy.Reason.INITIALIZATION_SEGMENT -> StreamDeliveryReason.INITIALIZATION_SEGMENT
        HlsPrefetchProxy.Reason.INVALID_MEDIA_PLAYLIST -> StreamDeliveryReason.INVALID_MEDIA_PLAYLIST
        HlsPrefetchProxy.Reason.NETWORK -> StreamDeliveryReason.PREFETCH_NETWORK_FAILURE
        HlsPrefetchProxy.Reason.BYTERANGE -> StreamDeliveryReason.BYTE_RANGE_PLAYLIST
        HlsPrefetchProxy.Reason.GAP -> StreamDeliveryReason.GAP_PLAYLIST
        HlsPrefetchProxy.Reason.LOW_LATENCY_HLS -> StreamDeliveryReason.LOW_LATENCY_PLAYLIST
        HlsPrefetchProxy.Reason.UNSUPPORTED_CODEC -> StreamDeliveryReason.UNSUPPORTED_CODEC
        HlsPrefetchProxy.Reason.UNVERIFIED_BOOTSTRAP -> StreamDeliveryReason.UNVERIFIED_BOOTSTRAP
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(DEBUG_TAG, message)
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
            Build.MODEL.contains("sdk_google_atv", ignoreCase = true)

    private companion object {
        const val DEBUG_TAG = "KanTvMedia3"
        const val PROGRESS_SIGNAL_INTERVAL_MS = 1_000L
    }
}
