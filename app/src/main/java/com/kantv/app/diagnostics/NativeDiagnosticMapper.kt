package com.kantv.app.diagnostics

import com.kantv.app.playback.PlaybackDiagnosticFact
import com.kantv.app.playback.PlaybackDiagnosticReason
import com.kantv.app.playback.PlaybackDiagnosticSnapshot
import com.kantv.app.playback.PlaybackUiState
import com.kantv.app.playback.PlaybackErrorKind
import com.kantv.app.playback.RebufferHealthPolicy
import com.kantv.app.playback.StreamDeliveryReason
import com.kantv.app.playback.StreamDeliveryStrategy

/**
 * Maps native Media3 candidate facts onto the existing diagnostics journal.
 *
 * The mapping is a pure function so it can be unit-tested, and so the recorder stays the single
 * writer of local/uploadable diagnostic state. Every produced value is a count, an ordinal, a
 * duration, a fixed enum, or a SHA-256 digest; free text has no path into the schema.
 */
internal object NativeDiagnosticMapper {
    fun entryKind(
        fact: PlaybackDiagnosticFact,
        errorKind: PlaybackErrorKind? = null,
    ): DiagnosticEntryKind = when (fact.reason) {
        PlaybackDiagnosticReason.SOURCE_START -> DiagnosticEntryKind.SOURCE_START
        PlaybackDiagnosticReason.FIRST_FRAME -> DiagnosticEntryKind.SOURCE_FIRST_FRAME
        PlaybackDiagnosticReason.REBUFFER_INTERVAL -> DiagnosticEntryKind.REBUFFER_INTERVAL
        PlaybackDiagnosticReason.REBUFFER_UNSTABLE -> DiagnosticEntryKind.SOURCE_AUTO_SWITCH
        PlaybackDiagnosticReason.PLAYER_ERROR,
        PlaybackDiagnosticReason.BEHIND_LIVE_WINDOW,
        -> if (errorKind == PlaybackErrorKind.UNSUPPORTED_FORMAT || errorKind == PlaybackErrorKind.DECODER) {
            DiagnosticEntryKind.RENDERER_ERROR
        } else {
            DiagnosticEntryKind.PLAYBACK_FAULT
        }
        PlaybackDiagnosticReason.FIRST_FRAME_TIMEOUT,
        PlaybackDiagnosticReason.FRAME_PROGRESS_TIMEOUT,
        PlaybackDiagnosticReason.BUFFERING_STALL,
        -> DiagnosticEntryKind.WATCHDOG_TRIGGERED
        PlaybackDiagnosticReason.BUFFERING_STARTED -> DiagnosticEntryKind.BUFFERING_STARTED
        PlaybackDiagnosticReason.AUTO_SWITCH -> DiagnosticEntryKind.SOURCE_AUTO_SWITCH
        PlaybackDiagnosticReason.AUTO_REOPEN,
        PlaybackDiagnosticReason.COOLDOWN_RETRY,
        -> DiagnosticEntryKind.RECOVERY_REOPENED
        PlaybackDiagnosticReason.USER_PAUSE -> DiagnosticEntryKind.USER_PAUSE
        PlaybackDiagnosticReason.USER_RESUME -> DiagnosticEntryKind.USER_RESUME
        PlaybackDiagnosticReason.PLAYBACK_STABLE -> DiagnosticEntryKind.SOURCE_PLAYBACK_STABLE
        PlaybackDiagnosticReason.GREY_SCREEN -> DiagnosticEntryKind.GREY_SCREEN_EVIDENCE
        PlaybackDiagnosticReason.SESSION_ENDED -> DiagnosticEntryKind.STOP
    }

    fun uiStateEntryKind(state: PlaybackUiState, previousState: PlaybackUiState): DiagnosticEntryKind? = when {
        state is PlaybackUiState.Failed -> DiagnosticEntryKind.PLAYBACK_FAULT
        state is PlaybackUiState.Recovering -> DiagnosticEntryKind.RECOVERY_STARTED
        state is PlaybackUiState.Ready && previousState is PlaybackUiState.Recovering ->
            DiagnosticEntryKind.RECOVERY_SUCCEEDED
        state is PlaybackUiState.Ready && previousState is PlaybackUiState.Buffering ->
            DiagnosticEntryKind.BUFFERING_ENDED
        else -> null
    }

    /** Only the enumerated reason is stored; error codes come from the caller's own observation. */
    fun recoveryReason(fact: PlaybackDiagnosticFact): String = when (fact.reason) {
        PlaybackDiagnosticReason.SOURCE_START -> "NONE"
        PlaybackDiagnosticReason.FIRST_FRAME_TIMEOUT -> "FIRST_FRAME_TIMEOUT"
        PlaybackDiagnosticReason.FRAME_PROGRESS_TIMEOUT -> "FRAME_PROGRESS_TIMEOUT"
        PlaybackDiagnosticReason.BUFFERING_STALL -> "BUFFERING_STALL"
        PlaybackDiagnosticReason.BEHIND_LIVE_WINDOW -> "BEHIND_LIVE_WINDOW"
        PlaybackDiagnosticReason.REBUFFER_UNSTABLE -> "REBUFFER_UNSTABLE"
        PlaybackDiagnosticReason.AUTO_REOPEN -> "AUTO_REOPEN"
        PlaybackDiagnosticReason.COOLDOWN_RETRY -> "COOLDOWN_RETRY"
        PlaybackDiagnosticReason.SESSION_ENDED -> "USER_EXIT"
        else -> "NONE"
    }

    fun values(
        fact: PlaybackDiagnosticFact,
        snapshot: PlaybackDiagnosticSnapshot?,
    ): DiagnosticPlaybackValues {
        val base = snapshot?.let { fromSnapshot(it) } ?: DiagnosticPlaybackValues()
        return base.copy(
            sourceOrdinal = fact.sourceOrdinal,
            sourceCount = fact.sourceCount,
            // The journal keeps the same irreversible SHA-256 fingerprint used by candidate
            // health ordering. It never persists the source URI or request metadata.
            sourceHash = fact.sourceHash.filter { it.isDigit() || it in 'a'..'f' }
                .take(SOURCE_HASH_REPORT_LENGTH),
            sourceGeneration = fact.sourceGeneration,
            media3Generation = snapshot?.runtimeGeneration ?: -1L,
            deliveryStrategyCode = snapshot?.streamDeliveryStrategy?.ordinal?.plus(1) ?: 0,
            deliveryReasonCode = snapshot?.streamDeliveryReason?.ordinal?.plus(1) ?: 0,
            prefetchQueued = snapshot?.prefetchQueued ?: -1,
            prefetchReady = snapshot?.prefetchReady ?: -1,
            prefetchFailed = snapshot?.prefetchFailed ?: -1,
            prefetchCancelled = snapshot?.prefetchCancelled ?: -1,
            prefetchWaitMs = snapshot?.prefetchWaitMs ?: -1L,
            prefetchCapMs = snapshot?.prefetchCapMs ?: -1L,
            prefetchSegmentCount = snapshot?.prefetchSegmentCount ?: -1,
            prefetchTargetDurationMs = snapshot?.prefetchTargetDurationMs ?: -1L,
            prefetchOutcome = snapshot?.prefetchOutcome?.name ?: "unknown",
            rebufferDurationMs = fact.rebufferDurationMs ?: -1L,
            rebufferCountWindow = fact.rebufferCountWindow ?: -1,
            rebufferTotalMsWindow = fact.rebufferTotalMsWindow ?: -1L,
            rebufferReason = fact.rebufferReason?.name ?: "unknown",
            rebufferUnstableReason = fact.unstableReason?.name ?: "unknown",
            sourceHealthScore = fact.healthScore ?: Int.MIN_VALUE,
            recoveryReason = recoveryReason(fact),
            greyScreenReason = fact.greyScreenReason?.name ?: "unknown",
            firstFrameLatencyMs = fact.firstFrameLatencyMs ?: -1L,
            videoPtsMs = snapshot?.lastVideoPtsUs?.takeIf { it >= 0L }?.div(1_000L) ?: -1L,
            errorCode = fact.errorCode ?: snapshot?.lastErrorCode ?: 0,
            videoProgressCount = snapshot?.videoProgressCount ?: 0L,
            presentedFrames = snapshot?.audioTiming?.displayedPictures?.toLong() ?: -1L,
            buffering = fact.reason == PlaybackDiagnosticReason.BUFFERING_STARTED ||
                fact.reason == PlaybackDiagnosticReason.BUFFERING_STALL,
        )
    }

    /**
     * The candidate-pipeline subset of a playback snapshot. Used for periodic samples so a report
     * always carries PTS progress, underruns, delivery strategy, and prefetch counters.
     */
    fun fromSnapshot(snapshot: PlaybackDiagnosticSnapshot, nowNanoTime: Long = System.nanoTime()): DiagnosticPlaybackValues = DiagnosticPlaybackValues(
        measurement = nativeMeasurement(snapshot.nativeAvSync, nowNanoTime),
        m3uLoad = snapshot.m3uLoad?.let { value ->
            val live = snapshot.m3uLive
            loadEvidence(when {
                value.positionStatus !in setOf("AVAILABLE", "WINDOW_ONLY") -> value
                live == null || live.windowDurationMs < 0 -> value.copy(positionStatus = "UNKNOWN_TIME", windowStartInPeriodMs = -1)
                live.positionMs < 0 -> value.copy(positionStatus = "WINDOW_ONLY")
                else -> value.copy(positionStatus = "AVAILABLE")
            })
        } ?: M3uLoadDiagnosticEvidence(),
        kernelCode = snapshot.kernel?.ordinal?.plus(1) ?: 0,
        pictureModeCode = com.kantv.app.playback.PictureMode.FILL.ordinal + 1,
        videoDecoderName = snapshot.videoDecoderName ?: "unknown",
        audioDecoderName = snapshot.audioDecoderName ?: "unknown",
        audioMimeType = snapshot.audioMimeType ?: "unknown",
        audioCodec = snapshot.audioCodec ?: "unknown",
        audioChannelCount = snapshot.audioChannelCount,
        audioSampleRate = snapshot.audioSampleRate,
        timeMs = snapshot.m3uLive?.positionMs ?: snapshot.audioTiming?.clockMs?.coerceAtLeast(-1L) ?: -1L,
        bufferedPositionMs = snapshot.m3uLive?.bufferedPositionMs ?: -1,
        bufferDurationMs = snapshot.m3uLive?.bufferDurationMs ?: -1,
        m3uLive = snapshot.m3uLive?.let {
            DiagnosticPlaybackValues.M3uLiveEvidence(
                nativeLiveContextAgeMs = ((nowNanoTime - it.observedNanoTime) / 1_000_000).coerceAtLeast(0),
                nativeLiveOffsetMs = it.liveOffsetMs,
                nativeLiveWindowDurationMs = it.windowDurationMs,
                nativeLiveMediaSequence = it.mediaSequence,
                nativeLastMediaLoadDurationMs = it.lastMediaLoadDurationMs,
                nativeLastMediaLoadBytes = it.lastMediaLoadBytes,
                nativeLastLoadErrorCode = it.lastLoadErrorCode,
            )
        } ?: DiagnosticPlaybackValues.M3uLiveEvidence(),
        videoPtsUs = snapshot.lastVideoPtsUs.coerceAtLeast(-1L),
        audioPtsUs = snapshot.audioInputPtsUs.coerceAtLeast(-1L),
        audioInputPtsUs = snapshot.audioInputPtsUs.coerceAtLeast(-1L),
        audioSinkPositionUs = snapshot.audioSinkPositionUs.coerceAtLeast(-1L),
        audioSinkPositionAvailable = snapshot.audioSinkPositionAvailability ==
            com.kantv.app.playback.ObservationAvailability.AVAILABLE,
        videoProgressCount = snapshot.videoProgressCount.coerceAtLeast(0L),
        decodedFrames = snapshot.audioTiming?.decodedVideo?.toLong() ?: -1L,
        displayedFrames = snapshot.audioTiming?.displayedPictures?.toLong() ?: -1L,
        presentedFrames = snapshot.audioTiming?.displayedPictures?.toLong() ?: -1L,
        droppedFrames = snapshot.droppedVideoFrames.toLong(),
        errorCode = snapshot.lastErrorCode ?: 0,
        sourceOrdinal = snapshot.sourceIndex.coerceAtLeast(0),
        sourceCount = snapshot.sourceCount.coerceAtLeast(0),
        sourceGeneration = snapshot.runtimeGeneration,
        media3Generation = snapshot.runtimeGeneration,
        buffering = false,
        deliveryStrategyCode = snapshot.streamDeliveryStrategy?.ordinal?.plus(1) ?: 0,
        deliveryReasonCode = snapshot.streamDeliveryReason?.ordinal?.plus(1) ?: 0,
        prefetchQueued = snapshot.prefetchQueued,
        prefetchReady = snapshot.prefetchReady,
        prefetchFailed = snapshot.prefetchFailed,
        prefetchCancelled = snapshot.prefetchCancelled,
        prefetchWaitMs = snapshot.prefetchWaitMs,
        prefetchCapMs = snapshot.prefetchCapMs,
        prefetchSegmentCount = snapshot.prefetchSegmentCount,
        prefetchTargetDurationMs = snapshot.prefetchTargetDurationMs,
        prefetchOutcome = snapshot.prefetchOutcome?.name ?: "unknown",
        rebufferCountWindow = snapshot.rebufferCount,
        rebufferTotalMsWindow = snapshot.rebufferTotalMs,
        firstFrameLatencyMs = snapshot.firstFrameMs ?: -1L,
        videoPtsMs = snapshot.lastVideoPtsUs.takeIf { it >= 0L }?.div(1_000L) ?: -1L,
    )

    internal fun loadEvidence(value: com.kantv.app.playback.NativeMediaLoadObservation): M3uLoadDiagnosticEvidence =
        M3uLoadDiagnosticEvidence(
            nativeLoadActiveStatus = value.activeStatus,
            nativeLoadActiveTrackType = value.active?.trackType ?: "UNKNOWN",
            nativeLoadActiveStartElapsedMs = value.active?.startElapsedMs ?: -1,
            nativeLoadActiveMediaStartMs = value.active?.mediaStartMs ?: -1,
            nativeLoadActiveMediaEndMs = value.active?.mediaEndMs ?: -1,
            nativeLoadActiveRetryCount = value.active?.retryCount ?: -1,
            nativeLoadLastOutcome = value.lastOutcome,
            nativeLoadLastTrackType = value.last?.trackType ?: "UNKNOWN",
            nativeLoadLastStartElapsedMs = value.last?.startElapsedMs ?: -1,
            nativeLoadLastEndElapsedMs = value.last?.endElapsedMs ?: -1,
            nativeLoadLastMediaStartMs = value.last?.mediaStartMs ?: -1,
            nativeLoadLastMediaEndMs = value.last?.mediaEndMs ?: -1,
            nativeLoadLastRetryCount = value.last?.retryCount ?: -1,
            nativeLoadLastBytes = value.last?.bytes ?: -1,
            nativeLoadLastHttpStatus = value.last?.httpStatus ?: -1,
            nativeLoadPositionStatus = value.positionStatus,
            nativeLoadWindowStartInPeriodMs = value.windowStartInPeriodMs,
            nativeTransferActiveCount = value.transferActiveCount,
            nativeTransferSequence = value.transfer?.sequence ?: -1,
            nativeTransferOutcome = value.transferOutcome,
            nativeTransferTaskMatchStatus = if (value.transferOutcome == "NA") "NOT_APPLICABLE" else "UNASSIGNED",
            nativeTransferStartElapsedMs = value.transfer?.startElapsedMs ?: -1,
            nativeTransferEndElapsedMs = value.transfer?.endElapsedMs ?: -1,
            nativeTransferOpenDurationUs = value.transfer?.openDurationUs ?: -1,
            nativeTransferReadDurationUs = value.transfer?.readDurationUs ?: -1,
            nativeTransferCloseDurationUs = value.transfer?.closeDurationUs ?: -1,
            nativeTransferReadCallCount = value.transfer?.readCallCount ?: -1,
            nativeTransferBytes = value.transfer?.bytes ?: -1,
            nativeTransferEofSeen = value.transfer?.eofSeen ?: false,
            nativeMediaRequestMatchStatus = value.mediaRequestMatchStatus,
            nativeMediaRequestCoverage = if (value.mediaRequestMatchStatus == "MATCHED") "REQUEST_ONLY" else "UNKNOWN",
            nativeMediaRequestOutcome = value.mediaRequestTransfer?.outcome ?: "NA",
            nativeMediaRequestOpenDurationUs = value.mediaRequestTransfer?.openDurationUs ?: -1,
            nativeMediaRequestReadDurationUs = value.mediaRequestTransfer?.readDurationUs ?: -1,
            nativeMediaRequestCloseDurationUs = value.mediaRequestTransfer?.closeDurationUs ?: -1,
            nativeMediaRequestReadCallCount = value.mediaRequestTransfer?.readCallCount ?: -1,
            nativeMediaRequestBytes = value.mediaRequestTransfer?.bytes ?: -1,
            nativeMediaRequestEofSeen = value.mediaRequestTransfer?.eofSeen ?: false,
        )

    internal fun nativeMeasurement(
        observation: com.kantv.app.playback.NativeAvSyncObservation?,
        nowNanoTime: Long,
    ): DiagnosticPlaybackValues.MeasurementEvidence {
        val status = observation?.statusAt(nowNanoTime) ?: "INSUFFICIENT_NO_SAMPLE"
        if (status != "AVAILABLE" || observation == null) return DiagnosticPlaybackValues.MeasurementEvidence(
            avSyncAssessment = "UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED",
            nativeAudioClockStatus = status,
            nativeAvGeneration = observation?.generation ?: -1,
        )
        return DiagnosticPlaybackValues.MeasurementEvidence(
            avSyncAssessment = "UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED",
            nativeAudioClockStatus = "AVAILABLE",
            nativeAvGeneration = observation.generation,
            nativeAudioStreamOffsetUs = observation.streamOffsetUs,
            nativeAudioClockUs = observation.audioClockUs,
            nativeAudioClockAgeMs = (nowNanoTime - observation.audioSampleNanoTime) / 1_000_000,
            nativePairedVideoPtsUs = observation.videoPtsUs,
            nativeAudioMinusVideoUs = observation.audioClockUs - observation.videoPtsUs,
            nativeAvPairSpanUs = observation.pairSpanUs,
            nativeVideoReleaseDelayUs = observation.videoReleaseDelayUs,
        )
    }

    /** Permille distance from neutral, for reports that want one comparable health number. */
    fun healthPermille(score: Int?): Int = score ?: Int.MIN_VALUE

    /** Enumerated delivery/rebuffering codes, exposed for tests and on-device inspection. */
    fun deliveryStrategyOf(values: DiagnosticPlaybackValues): StreamDeliveryStrategy? =
        StreamDeliveryStrategy.entries.getOrNull(values.deliveryStrategyCode - 1)

    fun deliveryReasonOf(values: DiagnosticPlaybackValues): StreamDeliveryReason? =
        StreamDeliveryReason.entries.getOrNull(values.deliveryReasonCode - 1)

    fun unstableReasonOf(fact: PlaybackDiagnosticFact): RebufferHealthPolicy.UnstableReason? = fact.unstableReason

    /** Full lowercase SHA-256 digest. No original source material is persisted. */
    const val SOURCE_HASH_REPORT_LENGTH = 64
}
