package com.kantv.app.diagnostics

enum class DiagnosticProvider {
    CCTV,
    YANGSHIPIN,
    YICAI,
    GDTV,
    TVB_RESOLVED,
    REGIONAL,
    M3U,
}

enum class DiagnosticReportReason {
    NO_FIRST_FRAME_30S,
    PLAYBACK_STALLED_15S,
    CHANNEL_SWITCH,
    STOPPED,
    PROCESS_TERMINATED,
    CHECKPOINT,
}

enum class DiagnosticEntryKind {
    START,
    SAMPLE,
    STOP,
    SWITCH,
    MENU_OPEN,
    MENU_CLOSE,
    BACKGROUND,
    FOREGROUND,
    USER_PAUSE,
    USER_RESUME,

    /**
     * 用户主动说"这条线路有问题"。
     *
     * app 不再自己猜线路质量——实测那套猜测两个方向都会错。质量判断交回给用户：他按下这个按钮，
     * 报告里就多一条带完整上下文的标记（哪条线路、画面什么状态、屏幕上显示的是什么），
     * 之后由人去查。线上名必须是 USER_SOURCE_REPORT，接收端按它白名单校验。
     */
    USER_SOURCE_REPORT,
    RENDERER_ERROR,
    ROUTE_CHANGED,
    AUDIO_TRACK_CHANGED,
    DISPLAY_MODE_CHANGED,
    PLAYBACK_FAULT,
    RECOVERY_STARTED,
    RECOVERY_SUCCEEDED,
    CHECKPOINT,
    PROCESS_RECOVERED,
    MIRROR_SERVER_STARTED,
    MIRROR_SERVER_FAILED,
    WEB_SESSION_CREATED,
    NAVIGATION_STARTED,
    NAVIGATION_COMMITTED,
    NAVIGATION_FINISHED,
    NAVIGATION_FAILED,
    EXTENSION_READY,
    EXTENSION_FAILED,
    BRIDGE_READY,
    HOOK_INJECTION_REQUESTED,
    PLAYER_NODE_MISSING,
    PLAYER_NODE_READY0,
    VIDEO_INIT_ACCEPTED,
    AUDIO_INIT_ACCEPTED,
    FIRST_FRAGMENT_ACCEPTED,
    MIRROR_PLAYER_CREATED,
    MIRROR_FIRST_FRAME,
    MIRROR_OUTPUT_STALLED,
    NATIVE_PAGE_PREPARED,
    NATIVE_PAGE_PROGRESS,
    /** 诊断存活心跳：采样被暂停时也每 60s 落一条，让"日志断掉"本身成为可判信号。 */
    HEARTBEAT,
    /** 看门狗的一次判定（含 wedge 条件与预算），用于回答"为什么没有自愈"。 */
    WATCHDOG_DECISION,
    GENERIC_EVENT,

    // Native Media3 candidates (M3U/TS/HLS). Every value carried alongside these kinds is a counter,
    // an ordinal, a duration, a fixed enum, or a SHA-256 digest of the candidate identity.
    /** A candidate (source ordinal) started opening inside the current playback session. */
    SOURCE_START,
    /** The candidate rendered its first frame. Never reuses START: the recorder reserves START. */
    SOURCE_FIRST_FRAME,
    /** The proxy/engine reported which delivery strategy was chosen and why. */
    DELIVERY_SELECTED,
    /** One candidate-level rebuffering interval closed. */
    REBUFFER_INTERVAL,
    /** Candidate instability crossed the threshold and the next candidate is being tried. */
    SOURCE_AUTO_SWITCH,
    /** The candidate survived the stability threshold and is recorded as healthy. */
    SOURCE_PLAYBACK_STABLE,
    /** An automatic reopen (retry) or live-window recovery was scheduled or executed. */
    RECOVERY_REOPENED,
    /** A watchdog (first frame, frame progress, or stall) decided to leave the current candidate. */
    WATCHDOG_TRIGGERED,
    BUFFERING_STARTED,
    BUFFERING_ENDED,
    /** A buffering episode ended without a picture: no first frame, frozen PTS, or sound only. */
    GREY_SCREEN_EVIDENCE,
    /** The player produced a picture with no audio-track progress, or audio without a picture. */
    MEDIA_PIPELINE_STATE,
}

data class DiagnosticPlaybackValues(
    val timeMs: Long = -1,
    val frames: Long = -1,
    val width: Int = 0,
    val height: Int = 0,
    val readyState: Int = 0,
    val paused: Boolean = true,
    val buffering: Boolean = false,
    val errorCode: Int = 0,
    val droppedFrames: Long = -1,
    val presentedFrames: Long = -1,
    val jsSampleAgeMs: Long = -1,
    val processPssKb: Long = -1,
    val rendererState: Int = -1,
    val kernelCode: Int = 0,
    val deliveryCode: Int = 0,
    val videoProgressCount: Long = 0,
    val decodedFrames: Long = -1,
    val displayedFrames: Long = -1,
    val readBytes: Long = -1,
    val demuxReadBytes: Long = -1,
    val inputKbps: Int = -1,
    val demuxKbps: Int = -1,
    val demuxCorrupted: Long = -1,
    val demuxDiscontinuity: Long = -1,
    val decodedAudio: Long = -1,
    val playedAudioBuffers: Long = -1,
    val lostAudioBuffers: Long = -1,
    val selectedAudioTrackId: Int = -1,
    val configuredAudioDelayUs: Long = 0,
    val sourceAudioPid1: Int = -1,
    val sourceAudioMinusVideoMs1: Int = 0,
    val sourceAudioPid2: Int = -1,
    val sourceAudioMinusVideoMs2: Int = 0,
    val videoDecoderName: String = "unknown",
    val audioDecoderName: String = "unknown",
    val audioMimeType: String = "unknown",
    val audioCodec: String = "unknown",
    val audioChannelCount: Int = -1,
    val audioSampleRate: Int = -1,
    val bufferedPositionMs: Long = -1,
    val bufferDurationMs: Long = -1,
    val videoPtsUs: Long = -1,
    val audioPtsUs: Long = -1,
    val playbackStateCode: Int = 0,
    val generation: Long = -1,
    val pictureModeCode: Int = 0,
    val audioInputPtsUs: Long = -1,
    val audioSinkPositionUs: Long = -1,
    val audioSinkPositionAvailable: Boolean = false,
    val audioOutputEncoding: Int = -1,
    val audioOutputSampleRate: Int = -1,
    val audioOutputChannelConfig: Int = -1,
    val audioOutputBufferSize: Int = -1,
    val audioOutputMode: String = "unknown",
    val audioOutputRoute: String = "unknown",
    val audioOutputTunneling: Boolean? = null,
    val audioOutputOffload: Boolean? = null,
    val audioTrackTimestampAvailability: String = "unknown",
    val audioTrackPlaybackHeadPosition: Long = -1,
    val underrunBufferSize: Int = -1,
    val underrunBufferSizeMs: Long = -1,
    val underrunElapsedSinceLastFeedMs: Long = -1,
    val audioSinkDiscontinuityCount: Int = 0,
    val audioTrackReleased: Boolean = false,
    val videoPlannedReleaseTimeNs: Long = -1,
    val videoCallbackNanoTime: Long = -1,
    val videoTimingSource: String = "unknown",
    val videoProcessingOffsetUs: Long = 0,
    val videoProcessingOffsetAvailable: Boolean = false,
    val videoProcessingFrameCount: Int = -1,
    val videoFirstFrameRenderTimeMs: Long = -1,
    val videoFirstFrameSource: String = "unknown",
    val droppedFramesElapsedMs: Long = -1,
    val actualPresentedAvailability: String = "unknown",
    val tsInputSource: String = "unknown",
    val tsPcrAvailable: Boolean = false,
    val tsPcrPid: Int = -1,
    val tsPcrSpanUs: Long = -1,
    val tsPcrMaxJumpUs: Long = -1,
    val tsPcrBackwardJumpCount: Int = -1,
    val tsAdaptationDiscontinuityCount: Int = -1,
    val tsFirstAudioMinusVideoUs: Long = 0,
    val tsFirstAudioMinusVideoAvailable: Boolean = false,
    val tsHlsDiscontinuity: Boolean = false,
    val tsHlsDiscontinuitySequence: Long = -1,
    val tsWouldRepair: Boolean = false,
    val tsRepairCorrectionSeconds: Long = 0,
    val tsRepairHeaderCount: Int = 0,
    val nodeCount: Int = -1,
    val nodeGeneration: Long = -1,
    val webRuntimeGeneration: Long = -1,
    val webState: String = "unknown",
    val webRecoveryStage: String = "unknown",
    /** Gecko MSE 桥仅上报聚合计数；不包含页面地址、Cookie 或媒体内容。 */
    val mirrorWorkerSwitches: Long = 0,
    val mirrorInstanceSwitches: Long = 0,
    val mirrorAcceptedFragments: Long = 0,
    val mirrorRejectedFragments: Long = 0,
    val mirrorWatchdogRebuilds: Long = 0,
    val mirrorWatchdogReason: String = "unknown",
    val routeGeneration: Long = -1,
    val mirrorStage: String = "unknown",
    val mirrorNavigationState: String = "unknown",
    val mirrorExtensionState: String = "unknown",
    val mirrorBridgeReady: Boolean = false,
    val mirrorHookRequested: Boolean = false,
    val mirrorServerStarts: Long = 0,
    val mirrorVideoInitCount: Long = 0,
    val mirrorAudioInitCount: Long = 0,
    val mirrorAcceptedVideoFragments: Long = 0,
    val mirrorAcceptedAudioFragments: Long = 0,
    val mirrorRejectedVideoFragments: Long = 0,
    val mirrorRejectedAudioFragments: Long = 0,
    val mirrorFirstVideoInitElapsedMs: Long = -1,
    val mirrorFirstAudioInitElapsedMs: Long = -1,
    val mirrorFirstAcceptedFragmentElapsedMs: Long = -1,
    val mirrorPlayerCreated: Boolean = false,
    val mirrorFirstFrameRendered: Boolean = false,
    val mirrorOutputUpdates: Long = 0,
    val geckoPlaybackStrategy: String = "unknown",
    /** Gecko 页面呈现只记录固定枚举、布尔、计数和有界几何，不记录选择器或页面内容。 */
    val geckoPresentationMode: String = "unknown",
    val geckoFullscreenRequestCount: Long = 0,
    val geckoFullscreenRequestAccepted: Boolean = false,
    val geckoFullscreenActive: Boolean = false,
    val geckoFullscreenEnterCount: Long = 0,
    val geckoFullscreenExitCount: Long = 0,
    val geckoCssFitApplied: Boolean = false,
    val geckoPresentationRebindCount: Long = 0,
    val geckoPresentationTargetWidth: Int = 0,
    val geckoPresentationTargetHeight: Int = 0,
    val geckoPresentationViewportWidth: Int = 0,
    val geckoPresentationViewportHeight: Int = 0,
    val geckoPresentationCoveragePermille: Int = 0,
    /** 央视频 SPA 选台：页面里找到了目标频道标签（桥只上报布尔，不含页面文本）。 */
    val labelFound: Boolean = false,
    /** 央视频 SPA 选台：已对目标频道标签完成点击（桥只上报布尔）。 */
    val labelClicked: Boolean = false,
    // Native Media3 candidate facts. Only counters, ordinals, durations, fixed enums, and digests:
    // no URI, header, cookie, or media byte can be carried by any of these fields.
    /** 1-based candidate ordinal inside the current source list, or 0 when no candidate is active. */
    val sourceOrdinal: Int = 0,
    val sourceCount: Int = 0,
    /** Redacted candidate identity: SHA-256 digest prefix. Empty means "not applicable". */
    val sourceHash: String = "",
    /** Monotonic counter that changes on every source switch, reopen, and resume. */
    val sourceGeneration: Long = -1,
    val media3Generation: Long = -1,
    val deliveryStrategyCode: Int = 0,
    val deliveryReasonCode: Int = 0,
    val prefetchQueued: Int = -1,
    val prefetchReady: Int = -1,
    val prefetchFailed: Int = -1,
    val prefetchCancelled: Int = -1,
    val prefetchWaitMs: Long = -1,
    /** Bounded wait cap, referenced segment count, and target duration: the wait's own evidence. */
    val prefetchCapMs: Long = -1,
    val prefetchSegmentCount: Int = -1,
    val prefetchTargetDurationMs: Long = -1,
    val prefetchOutcome: String = "unknown",
    /** Rebuffer interval that just closed. */
    val rebufferDurationMs: Long = -1,
    val rebufferCountWindow: Int = -1,
    val rebufferTotalMsWindow: Long = -1,
    val rebufferReason: String = "unknown",
    val rebufferUnstableReason: String = "unknown",
    /** Bounded health score of the candidate, in permille of the neutral value. */
    val sourceHealthScore: Int = Int.MIN_VALUE,
    /** Why the coordinator left the candidate: a fixed enum, never free text. */
    val recoveryReason: String = "unknown",
    val greyScreenReason: String = "unknown",
    /** Milliseconds between candidate start and the first presented frame, or -1. */
    val firstFrameLatencyMs: Long = -1,
    /** Last observed video presentation time in milliseconds; advances only when frames advance. */
    val videoPtsMs: Long = -1,
    val audioAdvancedWithoutVideo: Boolean = false,
    val videoAdvancedWithoutAudio: Boolean = false,
    /**
     * 源的上游身份：这条线路在服务端叫什么。
     *
     * 报告里原来只有客户端自己算的摘要，所以每次问"哪条线坏了"都得靠人工交叉比对——那次几乎
     * 让人删错线路。带上服务端自己的字段之后，客户端看到的那一条可以直接用上游的语言指认。
     */
    val sourceRoute: String = "unknown",
    val sourceQuality: String = "unknown",
    /** 服务端 url_key 前缀；"unknown" 表示该线路不可归因。 */
    val sourceUrlKey: String = "unknown",
    val sourceScoreUpstream: Int = -1,
    /**
     * 用户在那个时刻屏幕上看到的是什么，以及它已经持续了多久。
     *
     * "用户觉得体验差"最终就是看着某一句提示或某个转圈等了很久。把这个记下来，复盘时才能回答
     * "为什么他会觉得差"，而不是只能猜播放器的内部状态。
     */
    val userVisibleState: String = "unknown",
    val userVisibleStateMs: Long = -1,
    /** 合成层证据：回答"画面到底出没出来"。 */
    val visual: VisualEvidence = VisualEvidence(),
    /** 决策层证据：回答"为什么没有自愈"。 */
    val watchdog: WatchdogEvidence = WatchdogEvidence(),
    /** 上下文层证据：网络类型与采样连续性。 */
    val context: ContextEvidence = ContextEvidence(),
    /** v14: collection validity and browser-clock timings; never screen/audio synchronization. */
    val measurement: MeasurementEvidence = MeasurementEvidence(),
    val m3uLive: M3uLiveEvidence = M3uLiveEvidence(),
    val m3uLoad: M3uLoadDiagnosticEvidence = M3uLoadDiagnosticEvidence(),
    val geckoTrace: GeckoTraceDiagnosticEvidence = GeckoTraceDiagnosticEvidence(),
    val geckoObserver: GeckoObserverDiagnosticEvidence = GeckoObserverDiagnosticEvidence(),
) {
    init {
        require(timeMs >= -1)
        require(frames >= -1)
        require(width >= 0 && height >= 0)
        require(readyState in 0..4)
        require(droppedFrames >= -1 && presentedFrames >= -1)
        require(jsSampleAgeMs >= -1 && processPssKb >= -1)
        require(kernelCode >= 0 && deliveryCode >= 0 && videoProgressCount >= 0)
        require(decodedFrames >= -1 && displayedFrames >= -1)
        require(readBytes >= -1 && demuxReadBytes >= -1)
        require(inputKbps >= -1 && demuxKbps >= -1)
        require(demuxCorrupted >= -1 && demuxDiscontinuity >= -1)
        require(decodedAudio >= -1 && playedAudioBuffers >= -1 && lostAudioBuffers >= -1)
        require(audioChannelCount >= -1 && audioSampleRate >= -1)
        require(bufferedPositionMs >= -1 && bufferDurationMs >= -1)
        require(videoPtsUs >= -1 && audioPtsUs >= -1)
        require(playbackStateCode >= 0 && generation >= -1 && pictureModeCode >= 0)
        require(audioInputPtsUs >= -1 && audioSinkPositionUs >= -1)
        require(audioOutputEncoding >= -1 && audioOutputSampleRate >= -1)
        require(audioOutputChannelConfig >= -1 && audioOutputBufferSize >= -1)
        require(audioTrackPlaybackHeadPosition >= -1)
        require(underrunBufferSize >= -1 && underrunBufferSizeMs >= -1 && underrunElapsedSinceLastFeedMs >= -1)
        require(audioSinkDiscontinuityCount >= 0)
        require(videoPlannedReleaseTimeNs >= -1 && videoCallbackNanoTime >= -1)
        require(videoProcessingFrameCount >= -1 && videoFirstFrameRenderTimeMs >= -1 && droppedFramesElapsedMs >= -1)
        require(tsPcrSpanUs >= -1 && tsPcrMaxJumpUs >= -1)
        require(tsPcrPid >= -1 && tsPcrBackwardJumpCount >= -1 && tsAdaptationDiscontinuityCount >= -1)
        require(tsHlsDiscontinuitySequence >= -1 && tsRepairHeaderCount >= 0)
        require(nodeCount >= -1 && nodeGeneration >= -1 && webRuntimeGeneration >= -1)
        require(webState in WEB_STATES)
        require(webRecoveryStage in WEB_RECOVERY_STAGES)
        require(mirrorWorkerSwitches >= 0 && mirrorInstanceSwitches >= 0)
        require(mirrorAcceptedFragments >= 0 && mirrorRejectedFragments >= 0 && mirrorWatchdogRebuilds >= 0)
        require(mirrorWatchdogReason in MIRROR_WATCHDOG_REASONS)
        require(routeGeneration >= -1)
        require(mirrorStage in MIRROR_STAGES)
        require(mirrorNavigationState in MIRROR_NAVIGATION_STATES)
        require(mirrorExtensionState in MIRROR_EXTENSION_STATES)
        require(mirrorServerStarts >= 0 && mirrorVideoInitCount >= 0 && mirrorAudioInitCount >= 0)
        require(mirrorAcceptedVideoFragments >= 0 && mirrorAcceptedAudioFragments >= 0)
        require(mirrorRejectedVideoFragments >= 0 && mirrorRejectedAudioFragments >= 0)
        require(mirrorFirstVideoInitElapsedMs >= -1 && mirrorFirstAudioInitElapsedMs >= -1)
        require(mirrorFirstAcceptedFragmentElapsedMs >= -1 && mirrorOutputUpdates >= 0)
        require(geckoPlaybackStrategy in GECKO_PLAYBACK_STRATEGIES)
        require(geckoPresentationMode in GECKO_PRESENTATION_MODES)
        require(geckoFullscreenRequestCount >= 0)
        require(geckoFullscreenEnterCount >= 0 && geckoFullscreenExitCount >= 0)
        require(geckoPresentationRebindCount >= 0)
        require(geckoPresentationTargetWidth in 0..32768)
        require(geckoPresentationTargetHeight in 0..32768)
        require(geckoPresentationViewportWidth in 0..32768)
        require(geckoPresentationViewportHeight in 0..32768)
        require(geckoPresentationCoveragePermille in 0..1000)
        require(sourceOrdinal >= 0 && sourceCount >= 0)
        require(sourceHash.isEmpty() || SOURCE_HASH.matches(sourceHash))
        require(sourceGeneration >= -1 && media3Generation >= -1)
        require(deliveryStrategyCode >= 0 && deliveryReasonCode >= 0)
        require(prefetchQueued >= -1 && prefetchReady >= -1 && prefetchFailed >= -1 && prefetchCancelled >= -1)
        require(prefetchWaitMs >= -1 && prefetchCapMs >= -1 && prefetchTargetDurationMs >= -1)
        require(prefetchSegmentCount >= -1)
        require(prefetchOutcome in PREFETCH_OUTCOMES)
        require(rebufferDurationMs >= -1 && rebufferCountWindow >= -1 && rebufferTotalMsWindow >= -1)
        require(rebufferReason in REBUFFER_REASONS)
        require(rebufferUnstableReason in REBUFFER_UNSTABLE_REASONS)
        require(sourceHealthScore == Int.MIN_VALUE || sourceHealthScore in -100_000..100_000)
        require(recoveryReason in RECOVERY_REASONS)
        require(greyScreenReason in GREY_SCREEN_REASONS)
        require(firstFrameLatencyMs >= -1 && videoPtsMs >= -1)
        require(sourceScoreUpstream >= -1)
        require(userVisibleState in USER_VISIBLE_STATES)
        require(userVisibleStateMs >= -1)
    }

    data class M3uLiveEvidence(
        val nativeLiveContextAgeMs: Long = -1,
        val nativeLiveOffsetMs: Long = -1,
        val nativeLiveWindowDurationMs: Long = -1,
        val nativeLiveMediaSequence: Long = -1,
        val nativeLastMediaLoadDurationMs: Long = -1,
        val nativeLastMediaLoadBytes: Long = -1,
        val nativeLastLoadErrorCode: Int = -1,
    ) {
        init {
            require(nativeLiveContextAgeMs in -1..MAX_WEB_INTEGER)
            require(nativeLiveOffsetMs in -1..MAX_WEB_INTEGER)
            require(nativeLiveWindowDurationMs in -1..MAX_WEB_INTEGER)
            require(nativeLiveMediaSequence in -1..MAX_WEB_INTEGER)
            require(nativeLastMediaLoadDurationMs in -1..MAX_WEB_INTEGER)
            require(nativeLastMediaLoadBytes in -1..MAX_WEB_INTEGER)
            require(nativeLastLoadErrorCode == -1 || nativeLastLoadErrorCode in 100..599)
        }
    }

    data class MeasurementEvidence(
        val probeObservationStatus: String = "NOT_APPLICABLE",
        val avSyncAssessment: String = "UNKNOWN_NOT_EVALUATED",
        /** Relative to the page performance clock, never Android nanoTime. */
        val videoCallbackAgeMs: Long = -1,
        val videoPresentationAgeMs: Long = -1,
        val videoExpectedDisplayDelayUs: Long = -1,
        /** Gecko engine audio clock, not an Android AudioTrack or physical speaker timestamp. */
        val geckoAudioClockStatus: String = "NOT_APPLICABLE",
        val geckoAudioClockUs: Long = -1,
        val geckoAudioClockAgeMs: Long = -1,
        val geckoAudioClockCallDurationMs: Long = -1,
        /** Exact rVFC snapshot paired with the async clock request, independent of current videoPtsUs. */
        val geckoPairedVideoPtsUs: Long = -1,
        /** Signed raw difference; -1 can be measured. Status alone establishes availability. */
        val geckoAudioMinusVideoUs: Long = -1,
        val geckoAvPairSpanMs: Long = -1,
        /** Media3 sink playback estimate paired with a fixed about-to-render frame. */
        val nativeAudioClockStatus: String = "NOT_APPLICABLE",
        val nativeAvGeneration: Long = -1,
        val nativeAudioStreamOffsetUs: Long = -1,
        val nativeAudioClockUs: Long = -1,
        val nativeAudioClockAgeMs: Long = -1,
        val nativePairedVideoPtsUs: Long = -1,
        val nativeAudioMinusVideoUs: Long = -1,
        val nativeAvPairSpanUs: Long = -1,
        val nativeVideoReleaseDelayUs: Long = -1,
    ) {
        init {
            require(nativeAudioClockStatus in NATIVE_AUDIO_CLOCK_STATUSES)
            require(nativeAvGeneration in -1..MAX_WEB_INTEGER)
            require(nativeAudioStreamOffsetUs in -MAX_WEB_INTEGER..MAX_WEB_INTEGER)
            require(nativeAudioClockUs in -1..MAX_WEB_INTEGER && nativeAudioClockAgeMs in -1..MAX_WEB_INTEGER)
            require(nativePairedVideoPtsUs in -1..MAX_WEB_INTEGER)
            require(nativeAudioMinusVideoUs in -MAX_WEB_INTEGER..MAX_WEB_INTEGER)
            require(nativeAvPairSpanUs in -1..100_000 && nativeVideoReleaseDelayUs in -1_000_000..1_000_000)
            if (nativeAudioClockStatus == "AVAILABLE") {
                require(nativeAvGeneration >= 0 && nativeAudioClockUs >= 0 && nativePairedVideoPtsUs >= 0)
                require(nativeAudioClockAgeMs in 0..1500 && nativeAvPairSpanUs >= 0)
                require(nativeAudioMinusVideoUs == nativeAudioClockUs - nativePairedVideoPtsUs)
                require(avSyncAssessment == "UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED")
                require(probeObservationStatus == "NOT_APPLICABLE" && geckoAudioClockStatus == "NOT_APPLICABLE")
            } else {
                require(nativeAudioStreamOffsetUs == -1L && nativeAudioClockUs == -1L && nativeAudioClockAgeMs == -1L)
                require(nativePairedVideoPtsUs == -1L && nativeAudioMinusVideoUs == -1L)
                require(nativeAvPairSpanUs == -1L && nativeVideoReleaseDelayUs == -1L)
            }
            require(probeObservationStatus in PROBE_OBSERVATION_STATUSES)
            require(avSyncAssessment in AV_SYNC_ASSESSMENTS)
            require(videoCallbackAgeMs in -1..9_007_199_254_740_991L)
            require(videoPresentationAgeMs in -1..9_007_199_254_740_991L)
            require(videoExpectedDisplayDelayUs in -1_000_000..1_000_000)
            require(geckoAudioClockStatus in GECKO_AUDIO_CLOCK_STATUSES)
            require(geckoAudioClockUs in -1..MAX_WEB_INTEGER)
            require(geckoAudioClockAgeMs in -1..MAX_WEB_INTEGER)
            require(geckoAudioClockCallDurationMs in -1..MAX_WEB_INTEGER)
            require(geckoPairedVideoPtsUs in -1..MAX_WEB_INTEGER)
            require(geckoAudioMinusVideoUs in -MAX_WEB_INTEGER..MAX_WEB_INTEGER)
            require(geckoAvPairSpanMs in -1..MAX_WEB_INTEGER)
            if (geckoAudioClockStatus == "AVAILABLE") {
                require(geckoAudioClockUs > 0 && geckoPairedVideoPtsUs >= 0)
                require(geckoAudioClockAgeMs >= 0)
                require(geckoAudioClockCallDurationMs in 0..100 && geckoAvPairSpanMs in 0..100)
                require(geckoAudioMinusVideoUs == geckoAudioClockUs - geckoPairedVideoPtsUs)
            }
        }
    }

    /** 合成层：一段有界的画面证据。像素本身不落盘，只保留固定枚举与毫秒数。 */
    data class VisualEvidence(
        /** DETAIL / FLAT_NEUTRAL / FLAT_OTHER / NO_SAMPLE / unknown。 */
        val sampleOutcome: String = "unknown",
        /** 该次采样从发起到回调的毫秒数；-1 = 尚未采样，-2 = 发起后超时未回调。 */
        val sampleCallbackMs: Long = -1,
        /** 距上次确认画面的毫秒数；-1 = 从未确认过。 */
        val millisSinceVerified: Long = -1,
        /** NONE / DEVICE_GPU_READBACK_UNSAFE / DEBUG_CONTROL / unknown; no free device text. */
        val sampleBlockedReason: String = "unknown",
    ) {
        init {
            require(sampleOutcome in VISUAL_SAMPLE_OUTCOMES)
            require(sampleBlockedReason in VISUAL_SAMPLE_BLOCKED_REASONS)
            if (sampleBlockedReason == "DEVICE_GPU_READBACK_UNSAFE" || sampleBlockedReason == "DEBUG_CONTROL") {
                require(sampleOutcome == "NO_SAMPLE" && sampleCallbackMs == -1L && millisSinceVerified == -1L)
            }
            require(sampleCallbackMs >= -2)
            require(millisSinceVerified >= -1)
        }
    }

    /** 决策层：一段有界的看门狗判定证据。回答"为什么没有自愈、还剩多少额度"。 */
    data class WatchdogEvidence(
        /** wedge = 页面自称数据充分在播、但画面超时未被确认。 */
        val wedgeActive: Boolean = false,
        /** NONE / REBUILD / FALLBACK / unknown。 */
        val decision: String = "unknown",
        /** FIRST_FRAME_TIMEOUT / FRAME_PROGRESS_TIMEOUT / CLOCK_STALLED / ... */
        val reason: String = "unknown",
        val recoveries: Int = -1,
        val recoveryLimit: Int = -1,
        /** NONE / PROBE_IN_FLIGHT / NOT_PLAYING / NO_SESSION / STOPPED。 */
        val sampleSkipReason: String = "NONE",
        /** 像素读回是否曾超时未回调（探针卡住这条支路的直接证据）。 */
        val probeTimedOut: Boolean = false,
    ) {
        init {
            require(decision in WATCHDOG_DECISIONS)
            require(reason in WATCHDOG_REASONS)
            require(recoveries >= -1 && recoveryLimit >= -1)
            require(sampleSkipReason in SAMPLE_SKIP_REASONS)
        }
    }

    /** 上下文层：网络类型（不记任何地址）与采样连续性（避免把陈旧值当现况）。 */
    data class ContextEvidence(
        /** NONE / WIFI / CELLULAR / ETHERNET / VPN / OTHER / unknown。 */
        val networkTransport: String = "unknown",
        val networkMetered: Boolean = false,
        /** 距上次心跳的毫秒数。 */
        val millisSinceHeartbeat: Long = -1,
        /** 采样是否中断过（熄屏/后台），以及中断了多久。 */
        val samplingSuspended: Boolean = false,
        val samplingSuspendedMs: Long = -1,
    ) {
        init {
            require(networkTransport in NETWORK_TRANSPORTS)
            require(millisSinceHeartbeat >= -1)
            require(samplingSuspendedMs >= -1)
        }
    }

    companion object {
        internal const val MAX_WEB_INTEGER = 9_007_199_254_740_991L
        internal val NATIVE_AUDIO_CLOCK_STATUSES = setOf(
            "NOT_APPLICABLE", "AVAILABLE", "INSUFFICIENT_NO_SAMPLE", "INSUFFICIENT_NOT_PLAYING",
            "INSUFFICIENT_NO_OFFSET", "INSUFFICIENT_INVALID_CLOCK", "INSUFFICIENT_STREAM_MISMATCH",
            "INSUFFICIENT_PAIR_STALE", "INSUFFICIENT_STALE_SAMPLE", "INSUFFICIENT_INVALIDATED",
        )
        internal val GECKO_AUDIO_CLOCK_STATUSES = setOf(
            "NOT_APPLICABLE", "AVAILABLE", "INSUFFICIENT_NO_SAMPLE", "INSUFFICIENT_NOT_PLAYING",
            "INSUFFICIENT_PENDING", "INSUFFICIENT_UNSUPPORTED", "INSUFFICIENT_REJECTED",
            "INSUFFICIENT_TIMEOUT", "INSUFFICIENT_SLOW_CALL", "INSUFFICIENT_INVALID_CLOCK",
            "INSUFFICIENT_CLOCK_MISMATCH", "INSUFFICIENT_PAIR_STALE", "INSUFFICIENT_STALE_SAMPLE",
            "INSUFFICIENT_PROBE_INVALID",
        )
        internal val PROBE_OBSERVATION_STATUSES = setOf(
            "NOT_APPLICABLE", "VALID", "INSUFFICIENT_NO_SAMPLE", "INSUFFICIENT_STALE_SAMPLE",
            "INSUFFICIENT_TICK_NOT_ADVANCING", "INSUFFICIENT_RUNTIME_CHANGED", "INSUFFICIENT_NO_IDENTITY",
            "INSUFFICIENT_SUSPENDED",
        )
        internal val AV_SYNC_ASSESSMENTS = setOf(
            "UNKNOWN_NOT_EVALUATED", "UNKNOWN_NO_MEDIA_ELEMENT_AUDIO_OUTPUT_TIMESTAMP",
            "UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED",
        )
        private val WEB_STATES = setOf(
            "unknown", "NO_PLAYER_NODE", "INITIALIZING", "NODE_REPLACED", "LOGIN_REQUIRED", "AD",
            "POLICY_OR_REGION_OR_DRM_BLOCKED", "PLAYER_NO_FRAME_PROGRESS",
            "RENDERER_UNRESPONSIVE", "RENDERER_GONE", "PLAYING",
        )
        private val WEB_RECOVERY_STAGES = setOf(
            "unknown", "IDLE", "MONITORING", "SOFT_RELOAD", "HARD_RECREATE", "BLOCKED", "FAILED",
        )
        private val MIRROR_WATCHDOG_REASONS = setOf(
            "unknown", "NONE", "FIRST_FRAGMENT_TIMEOUT", "ACCEPTED_FRAGMENT_STALL",
        )
        private val MIRROR_STAGES = setOf(
            "unknown", "SESSION_START", "SERVER_READY", "NAVIGATING", "PAGE_COMMITTED",
            "PAGE_FINISHED", "EXTENSION_READY", "BRIDGE_READY", "HOOK_REQUESTED",
            "NO_PLAYER_NODE", "PLAYER_READY0", "INIT_RECEIVED", "FRAGMENT_ACCEPTED",
            "PLAYER_CREATED", "FIRST_FRAME", "INPUT_WITHOUT_OUTPUT_PROGRESS",
            "WATCHDOG_REBUILD", "PLAYER_ERROR", "FAILED",
            "NATIVE_PAGE_PREPARED", "NATIVE_PAGE_PROGRESS",
        )
        private val MIRROR_NAVIGATION_STATES = setOf(
            "unknown", "NOT_STARTED", "STARTED", "COMMITTED", "FINISHED", "FAILED",
        )
        private val MIRROR_EXTENSION_STATES = setOf("unknown", "PENDING", "READY", "FAILED")
        private val GECKO_PLAYBACK_STRATEGIES = setOf("unknown", "MIRROR", "NATIVE_PAGE")
        private val GECKO_PRESENTATION_MODES = setOf(
            "unknown", "AUTO_FULLSCREEN_ONLY", "CSS_FIT_ONLY", "AUTO_THEN_FIT",
        )
        private val SOURCE_HASH = Regex("[0-9a-f]{8,64}")
        private val PREFETCH_OUTCOMES = setOf(
            "unknown", "NOT_APPLICABLE", "INDEPENDENT_SEGMENTS", "VERIFIED_BOOTSTRAP",
            "UNVERIFIED_BOOTSTRAP", "UNSUPPORTED_CODEC", "UNSUPPORTED_PLAYLIST", "DOWNLOAD_FAILED",
            "VERIFICATION_TIMEOUT", "CANCELLED",
        )
        private val REBUFFER_REASONS = setOf("unknown", "PLAYER_BUFFERING", "FRAME_STALL")
        private val REBUFFER_UNSTABLE_REASONS = setOf(
            "unknown", "SINGLE_LONG_REBUFFER", "ACCUMULATED_DURATION", "REPEATED_REBUFFERS",
        )
        private val RECOVERY_REASONS = setOf(
            "unknown", "NONE", "FIRST_FRAME_TIMEOUT", "FRAME_PROGRESS_TIMEOUT", "BUFFERING_STALL",
            "BEHIND_LIVE_WINDOW", "REBUFFER_UNSTABLE", "AUTO_REOPEN", "COOLDOWN_RETRY",
            "BACKEND_UNSUPPORTED", "ACTIVITY_STOP", "USER_EXIT", "PROCESS_TERMINATED",
        )
        private val VISUAL_SAMPLE_BLOCKED_REASONS = setOf(
            "unknown", "NONE", "DEVICE_GPU_READBACK_UNSAFE", "DEBUG_CONTROL",
        )
        private val VISUAL_SAMPLE_OUTCOMES = setOf(
            "unknown", "DETAIL", "FLAT_NEUTRAL", "FLAT_OTHER", "NO_SAMPLE",
        )
        /** 有界枚举：用户屏幕上那一刻显示的是什么。取值必须与接收端一致。 */
        private val USER_VISIBLE_STATES = setOf(
            "unknown", "PLAYING", "CONNECTING", "FIRST_OPEN", "BUFFERING", "RECOVERING",
            "RECOVERED_NOTICE", "SWITCHING_SOURCE", "FAILED_NOTICE", "MENU_OPEN", "BACKGROUND",
        )
        private val WATCHDOG_DECISIONS = setOf("unknown", "NONE", "REBUILD", "FALLBACK")
        private val WATCHDOG_REASONS = setOf(
            "unknown", "NONE", "FIRST_FRAME_TIMEOUT", "FRAME_PROGRESS_TIMEOUT", "CLOCK_STALLED",
            "PICTURE_UNVERIFIED", "PROBE_TIMED_OUT", "RENDERER_GONE", "BUDGET_EXHAUSTED", "MANUAL",
        )
        private val SAMPLE_SKIP_REASONS = setOf(
            "unknown", "NONE", "PROBE_IN_FLIGHT", "NOT_PLAYING", "NO_SESSION", "STOPPED",
        )
        private val NETWORK_TRANSPORTS = setOf(
            "unknown", "NONE", "WIFI", "CELLULAR", "ETHERNET", "VPN", "OTHER",
        )
        private val GREY_SCREEN_REASONS = setOf(
            "unknown", "NONE", "NO_FIRST_FRAME", "FRAME_FROZEN", "SOUND_ONLY", "STALE_SESSION",
        )
        val EMPTY = DiagnosticPlaybackValues()
    }
}

/** 把 server 自己的相对时钟换算为本次诊断会话时钟；Gecko 重建不参与计算。 */
internal fun mirrorServerRelativeElapsedMs(
    serverStartedAtMs: Long,
    diagnosticStartedAtMs: Long,
    serverElapsedMs: Long,
): Long = if (serverStartedAtMs <= 0L || diagnosticStartedAtMs <= 0L || serverElapsedMs < 0L) {
    -1L
} else {
    (serverStartedAtMs - diagnosticStartedAtMs).coerceAtLeast(0L) + serverElapsedMs
}

internal fun isMirrorOutputStalled(
    firstFrameAtMs: Long,
    lastInputProgressAtMs: Long,
    lastOutputProgressAtMs: Long,
    nowMs: Long,
    timeoutMs: Long,
): Boolean = firstFrameAtMs > 0L &&
    lastOutputProgressAtMs >= firstFrameAtMs &&
    lastInputProgressAtMs > lastOutputProgressAtMs &&
    nowMs - lastOutputProgressAtMs >= timeoutMs

/**
 * Gecko 宿主采样该用哪一路帧证据。MIRROR 以实际 TextureView 输出（[mirrorOutputUpdates]）
 * 为权威；NATIVE_PAGE 没有镜像输出，必须以 -1 表示“未提供”，让 [StallDetector] 回退到
 * 网页 frames。若 NATIVE_PAGE 也填恒 0，持续增长的网页帧会被误判为 NO_FIRST_FRAME_30S。
 */
internal fun presentedFramesForMirrorSample(
    usesMseMirror: Boolean,
    mirrorOutputUpdates: Long,
): Long = if (usesMseMirror) mirrorOutputUpdates.coerceAtLeast(0L) else -1L

data class OfficialWebDiagnosticSample(
    val sessionId: Long,
    val elapsedMs: Long,
    val values: DiagnosticPlaybackValues,
    val userPaused: Boolean = false,
    val menuVisible: Boolean = false,
    val appInBackground: Boolean = false,
    val observationEnabled: Boolean = true,
) {
    init {
        require(sessionId >= 0)
        require(elapsedMs >= 0)
    }
}

internal data class DiagnosticLogEntry(
    val kind: DiagnosticEntryKind,
    val elapsedMs: Long,
    val values: DiagnosticPlaybackValues,
) {
    // Evaluated on the journal executor, never on a renderer/audio callback.
    val serializedByteCount: Int by lazy(LazyThreadSafetyMode.NONE) {
        toJson().toString().toByteArray(Charsets.UTF_8).size
    }
}

/** Pure idempotence gate for a Handler-backed foreground sampling loop. */
internal class DiagnosticSamplingGate {
    private var active = false

    @Synchronized
    fun start(): Boolean {
        if (active) return false
        active = true
        return true
    }

    @Synchronized
    fun stop(): Boolean {
        if (!active) return false
        active = false
        return true
    }

    @Synchronized
    fun shouldReschedule(): Boolean = active
}

internal class DiagnosticGenerationCounter {
    private var value = 0L
    @Synchronized fun next(): Long = ++value
    @Synchronized fun current(): Long = value
}
