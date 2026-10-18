package com.kantv.app.diagnostics

import com.kantv.app.playback.PlaybackDiagnosticFact
import com.kantv.app.playback.PlaybackDiagnosticReason
import com.kantv.app.playback.PlaybackDiagnosticSnapshot
import com.kantv.app.playback.PlaybackErrorKind
import com.kantv.app.playback.RebufferHealthPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDiagnosticMapperTest {
    @Test
    fun liveLoadContextHasItsOwnAgeAndCannotBecomeAnAudioOutputClock() {
        val diagnostics = com.kantv.app.playback.PlaybackDiagnostics(elapsedRealtimeMs = { 0 })
        diagnostics.beginSource(1, "fictional", 1, 1)
        diagnostics.recordTechnical(1, com.kantv.app.playback.PlaybackTechnicalUpdate.RuntimeGeneration(4))
        diagnostics.recordTechnical(1, com.kantv.app.playback.PlaybackTechnicalUpdate.M3uLiveContext(
            com.kantv.app.playback.M3uLiveObservation(
                5_000_000_000, 20_000, 25_000, 5_000, 12_000, 80_000, 123, 400, 65_536, 503,
            ),
        ))
        val values = NativeDiagnosticMapper.fromSnapshot(diagnostics.currentSnapshot()!!, 6_000_000_000)
        assertEquals(20_000L, values.timeMs)
        assertEquals(25_000L, values.bufferedPositionMs)
        assertEquals(5_000L, values.bufferDurationMs)
        assertEquals(1_000L, values.m3uLive.nativeLiveContextAgeMs)
        assertEquals(123L, values.m3uLive.nativeLiveMediaSequence)
        assertEquals(503, values.m3uLive.nativeLastLoadErrorCode)
        assertEquals("INSUFFICIENT_NO_SAMPLE", values.measurement.nativeAudioClockStatus)
        assertEquals(-1L, values.measurement.nativeAudioClockUs)
        diagnostics.recordTechnical(1, com.kantv.app.playback.PlaybackTechnicalUpdate.RuntimeGeneration(5))
        val reopened = NativeDiagnosticMapper.fromSnapshot(diagnostics.currentSnapshot()!!, 6_000_000_000)
        assertEquals(-1L, reopened.m3uLive.nativeLiveMediaSequence)
        assertEquals(-1L, reopened.timeMs)
    }

    @Test
    fun loadFactsStayIndependentAndRequireLivePositionInTheSameSnapshot() {
        val diagnostics = com.kantv.app.playback.PlaybackDiagnostics(elapsedRealtimeMs = { 0 })
        diagnostics.beginSource(1, "fictional", 1, 1)
        val timing = com.kantv.app.playback.NativeLoadTaskTiming("VIDEO", 100, 300, 5_000, 10_000, 2, 400, 503)
        val raw = com.kantv.app.playback.NativeMediaLoadObservation(
            "LOADING", timing, "ERROR_RETRYING", timing, "AVAILABLE", -5_000,
            0, "CLOSED_NO_EOF", com.kantv.app.playback.NativeTransferSummary(3, "CLOSED_NO_EOF", 110, 250, 20, 90, 2, 4, 200, false),
        )
        diagnostics.recordTechnical(1, com.kantv.app.playback.PlaybackTechnicalUpdate.NativeMediaLoadObserved(raw))
        assertEquals("UNKNOWN_TIME", NativeDiagnosticMapper.fromSnapshot(diagnostics.currentSnapshot()!!).m3uLoad.nativeLoadPositionStatus)
        diagnostics.recordTechnical(1, com.kantv.app.playback.PlaybackTechnicalUpdate.M3uLiveContext(
            com.kantv.app.playback.M3uLiveObservation(0, 10_000, 15_000, 5_000, 8_000, 25_000, 100, 50, 900, -1),
        ))
        val values = NativeDiagnosticMapper.fromSnapshot(diagnostics.currentSnapshot()!!, 0)
        assertEquals("AVAILABLE", values.m3uLoad.nativeLoadPositionStatus)
        assertEquals(-5_000L, values.m3uLoad.nativeLoadWindowStartInPeriodMs)
        assertEquals(2L, values.m3uLoad.nativeLoadLastRetryCount)
        assertEquals(400L, values.m3uLoad.nativeLoadLastBytes)
        assertEquals(900L, values.m3uLive.nativeLastMediaLoadBytes)
        assertEquals("UNASSIGNED", values.m3uLoad.nativeTransferTaskMatchStatus)
        assertEquals(200L, values.m3uLoad.nativeTransferBytes)
        assertEquals("UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED", values.measurement.avSyncAssessment)
        diagnostics.recordTechnical(1, com.kantv.app.playback.PlaybackTechnicalUpdate.M3uLiveContext(
            com.kantv.app.playback.M3uLiveObservation(0, -1, 15_000, 5_000, -1, 25_000, 100, 50, 900, -1),
        ))
        val lostCursor = NativeDiagnosticMapper.fromSnapshot(diagnostics.currentSnapshot()!!, 0)
        assertEquals("WINDOW_ONLY", lostCursor.m3uLoad.nativeLoadPositionStatus)
        assertEquals(-5_000L, lostCursor.m3uLoad.nativeLoadWindowStartInPeriodMs)
        assertEquals(-1L, lostCursor.timeMs)
        val directory = java.io.File("build/outputs/schema17-wire-fixtures").apply { mkdirs() }
        java.io.File(directory, "window-only.json").writeText(
            DiagnosticLogEntry(DiagnosticEntryKind.SAMPLE, 0, lostCursor).toJson().toString(),
        )
        diagnostics.recordTechnical(1, com.kantv.app.playback.PlaybackTechnicalUpdate.RuntimeGeneration(2))
        assertEquals("NOT_APPLICABLE", NativeDiagnosticMapper.fromSnapshot(diagnostics.currentSnapshot()!!).m3uLoad.nativeLoadActiveStatus)
    }

    private val sourceFingerprint = "0123456789abcdef".repeat(4)

    private fun fact(
        reason: PlaybackDiagnosticReason = PlaybackDiagnosticReason.SOURCE_START,
        errorCode: Int? = null,
        rebufferDurationMs: Long? = null,
        healthScore: Int? = null,
        greyScreenReason: com.kantv.app.playback.GreyScreenEvidence? = null,
    ) = PlaybackDiagnosticFact(
        sessionId = 4L,
        sourceGeneration = 7L,
        reason = reason,
        sourceOrdinal = 2,
        sourceCount = 3,
        sourceHash = sourceFingerprint,
        errorCode = errorCode,
        rebufferDurationMs = rebufferDurationMs,
        rebufferReason = RebufferHealthPolicy.RebufferReason.PLAYER_BUFFERING,
        rebufferCountWindow = 2,
        rebufferTotalMsWindow = 4_500L,
        unstableReason = RebufferHealthPolicy.UnstableReason.ACCUMULATED_DURATION,
        healthScore = healthScore,
        firstFrameLatencyMs = 3_200L,
        greyScreenReason = greyScreenReason,
    )

    @Test
    fun everyCandidateEventMapsToAnExistingJournalEntryKind() {
        assertEquals(DiagnosticEntryKind.SOURCE_START, NativeDiagnosticMapper.entryKind(fact()))
        // Never START: the recorder reserves that kind for an explicit session start.
        assertEquals(
            DiagnosticEntryKind.SOURCE_FIRST_FRAME,
            NativeDiagnosticMapper.entryKind(fact(PlaybackDiagnosticReason.FIRST_FRAME)),
        )
        assertEquals(
            DiagnosticEntryKind.REBUFFER_INTERVAL,
            NativeDiagnosticMapper.entryKind(fact(PlaybackDiagnosticReason.REBUFFER_INTERVAL, rebufferDurationMs = 2_500L)),
        )
        assertEquals(
            DiagnosticEntryKind.SOURCE_AUTO_SWITCH,
            NativeDiagnosticMapper.entryKind(fact(PlaybackDiagnosticReason.REBUFFER_UNSTABLE)),
        )
        assertEquals(
            DiagnosticEntryKind.PLAYBACK_FAULT,
            NativeDiagnosticMapper.entryKind(
                fact(PlaybackDiagnosticReason.BEHIND_LIVE_WINDOW, errorCode = 1002),
                PlaybackErrorKind.BEHIND_LIVE_WINDOW,
            ),
        )
        assertEquals(
            DiagnosticEntryKind.RENDERER_ERROR,
            NativeDiagnosticMapper.entryKind(
                fact(PlaybackDiagnosticReason.PLAYER_ERROR, errorCode = 3001),
                PlaybackErrorKind.DECODER,
            ),
        )
        for (reason in listOf(
            PlaybackDiagnosticReason.FIRST_FRAME_TIMEOUT,
            PlaybackDiagnosticReason.FRAME_PROGRESS_TIMEOUT,
            PlaybackDiagnosticReason.BUFFERING_STALL,
        )) {
            assertEquals(DiagnosticEntryKind.WATCHDOG_TRIGGERED, NativeDiagnosticMapper.entryKind(fact(reason)))
        }
        for (reason in listOf(
            PlaybackDiagnosticReason.AUTO_REOPEN,
            PlaybackDiagnosticReason.COOLDOWN_RETRY,
        )) {
            assertEquals(DiagnosticEntryKind.RECOVERY_REOPENED, NativeDiagnosticMapper.entryKind(fact(reason)))
        }
        assertEquals(
            DiagnosticEntryKind.SOURCE_PLAYBACK_STABLE,
            NativeDiagnosticMapper.entryKind(fact(PlaybackDiagnosticReason.PLAYBACK_STABLE)),
        )
        assertEquals(
            DiagnosticEntryKind.GREY_SCREEN_EVIDENCE,
            NativeDiagnosticMapper.entryKind(
                fact(PlaybackDiagnosticReason.GREY_SCREEN, greyScreenReason = com.kantv.app.playback.GreyScreenEvidence.SOUND_ONLY),
            ),
        )
    }

    @Test
    fun bufferingArmingAndShortBufferCompletionAreNotWatchdogOrRecoveryEvents() {
        val start = fact(PlaybackDiagnosticReason.BUFFERING_STARTED)
        assertEquals(DiagnosticEntryKind.BUFFERING_STARTED, NativeDiagnosticMapper.entryKind(start))
        assertEquals("NONE", NativeDiagnosticMapper.recoveryReason(start))
        assertTrue(NativeDiagnosticMapper.values(start, null).buffering)
        assertEquals(
            DiagnosticEntryKind.BUFFERING_ENDED,
            NativeDiagnosticMapper.uiStateEntryKind(
                com.kantv.app.playback.PlaybackUiState.Ready("one"),
                com.kantv.app.playback.PlaybackUiState.Buffering("one"),
            ),
        )
        assertEquals(
            DiagnosticEntryKind.RECOVERY_SUCCEEDED,
            NativeDiagnosticMapper.uiStateEntryKind(
                com.kantv.app.playback.PlaybackUiState.Ready("one"),
                com.kantv.app.playback.PlaybackUiState.Recovering("one", 1),
            ),
        )
        assertFalse(DiagnosticJournalPolicy.flushImmediately(DiagnosticEntryKind.BUFFERING_STARTED))
        assertFalse(DiagnosticJournalPolicy.flushImmediately(DiagnosticEntryKind.BUFFERING_ENDED))
        for (critical in listOf(DiagnosticEntryKind.WATCHDOG_TRIGGERED, DiagnosticEntryKind.PLAYBACK_FAULT,
            DiagnosticEntryKind.RECOVERY_SUCCEEDED, DiagnosticEntryKind.STOP)) {
            assertTrue(DiagnosticJournalPolicy.flushImmediately(critical))
        }
    }

    @Test
    fun theProducedValuesCarryCountersOrdinalsAndDigestsButNoRequestIdentity() {
        val values = NativeDiagnosticMapper.values(
            fact(
                reason = PlaybackDiagnosticReason.REBUFFER_UNSTABLE,
                rebufferDurationMs = 2_500L,
                healthScore = -240,
            ),
            snapshot = null,
        )

        assertEquals(2, values.sourceOrdinal)
        assertEquals(3, values.sourceCount)
        assertEquals(sourceFingerprint, values.sourceHash)
        assertEquals(7L, values.sourceGeneration)
        assertEquals(2_500L, values.rebufferDurationMs)
        assertEquals(2, values.rebufferCountWindow)
        assertEquals(4_500L, values.rebufferTotalMsWindow)
        assertEquals("PLAYER_BUFFERING", values.rebufferReason)
        assertEquals("ACCUMULATED_DURATION", values.rebufferUnstableReason)
        assertEquals("REBUFFER_UNSTABLE", values.recoveryReason)
        assertEquals(-240, values.sourceHealthScore)
        assertEquals(3_200L, values.firstFrameLatencyMs)

        // The schema itself rejects a free-text or URL-shaped value in any candidate field.
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticPlaybackValues(recoveryReason = "https://private.example/live")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticPlaybackValues(prefetchOutcome = "some free text")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticPlaybackValues(sourceHash = "https://private.example/live")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticPlaybackValues(rebufferReason = "a human wrote this")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticPlaybackValues(greyScreenReason = "maybe grey")
        }
    }

    @Test
    fun onlyDigestsCountersAndEnumsReachTheSerializedJournal() {
        val fields = DiagnosticLogEntry(
            DiagnosticEntryKind.SOURCE_AUTO_SWITCH,
            4_500L,
            NativeDiagnosticMapper.values(
                fact(
                    reason = PlaybackDiagnosticReason.REBUFFER_UNSTABLE,
                    rebufferDurationMs = 2_500L,
                ),
                snapshot = null,
            ),
        ).toSerializableFields()

        assertEquals("SOURCE_AUTO_SWITCH", fields["kind"])
        assertEquals(2, fields["sourceOrdinal"])
        assertEquals(sourceFingerprint, fields["sourceHash"])
        assertEquals(7L, fields["sourceGeneration"])
        assertEquals(2_500L, fields["rebufferDurationMs"])
        assertEquals("PLAYER_BUFFERING", fields["rebufferReason"])
        assertEquals("ACCUMULATED_DURATION", fields["rebufferUnstableReason"])
        assertEquals("REBUFFER_UNSTABLE", fields["recoveryReason"])
        assertEquals(3_200L, fields["firstFrameLatencyMs"])

        val serialized = fields.entries.joinToString(" ") { "${it.key}=${it.value}" }.lowercase()
        for (forbidden in listOf("http://", "https://", "authorization", "cookie", "token", "referer", "user-agent")) {
            assertFalse("serialized journal must not contain $forbidden", serialized.contains(forbidden))
        }
        assertTrue(
            "every emitted key must belong to the closed schema",
            DIAGNOSTIC_SERIALIZED_KEYS.containsAll(fields.keys),
        )
    }

    @Test
    fun aSnapshotSampleCarriesPrefetchCountersAndPtsProgress() {
        val snapshot = PlaybackDiagnosticSnapshot(
            sessionId = 4L,
            channelId = "channel-identity",
            sourceIndex = 2,
            sourceCount = 3,
            kernel = null,
            kernelReason = null,
            streamDeliveryStrategy = com.kantv.app.playback.StreamDeliveryStrategy.HLS_CACHED_PLAYLIST,
            streamDeliveryReason = com.kantv.app.playback.StreamDeliveryReason.PREFETCH_ENABLED,
            containerMimeType = null,
            containerInfoSource = null,
            tracks = emptyList(),
            videoDecoderName = "c2.android.avc.decoder",
            audioDecoderName = "c2.android.mp3.decoder",
            audioMimeType = "audio/mpeg",
            audioCodec = "mp3",
            audioChannelCount = 2,
            audioSampleRate = 44_100,
            audioUnderrunCount = 69,
            runtimeGeneration = 5L,
            droppedVideoFrames = 0,
            videoProgressCount = 12L,
            lastVideoPtsUs = 9_000_000L,
            audioInputPtsUs = 8_900_000L,
            audioSinkPositionUs = 8_800_000L,
            audioSinkPositionAvailability = com.kantv.app.playback.ObservationAvailability.AVAILABLE,
            audioOutputConfiguration = null,
            audioUnderrunDetails = null,
            audioSinkDiscontinuityCount = 0,
            audioTrackReleased = false,
            videoRenderObservation = null,
            videoFirstFrameRenderTimeMs = 2_400L,
            videoFirstFrameSource = null,
            videoProcessingOffsetUs = -1L,
            videoProcessingFrameCount = -1,
            droppedVideoFramesElapsedMs = -1L,
            actualPresentedAvailability = com.kantv.app.playback.ObservationAvailability.UNKNOWN,
            transportStreamObservation = null,
            firstFrameMs = 2_400L,
            lastErrorCode = null,
            prefetchQueued = 1,
            prefetchReady = 3,
            prefetchFailed = 1,
            prefetchCancelled = 2,
            prefetchWaitMs = 3_100L,
            prefetchCapMs = 12_000L,
            prefetchSegmentCount = 5,
            prefetchTargetDurationMs = 2_000L,
            prefetchOutcome = com.kantv.app.playback.PrefetchStartupPolicy.StartupOutcome.VERIFIED_BOOTSTRAP,
        )

        val values = NativeDiagnosticMapper.fromSnapshot(snapshot)

        assertEquals(1, values.prefetchQueued)
        assertEquals(3, values.prefetchReady)
        assertEquals(1, values.prefetchFailed)
        assertEquals(2, values.prefetchCancelled)
        assertEquals(3_100L, values.prefetchWaitMs)
        assertEquals("VERIFIED_BOOTSTRAP", values.prefetchOutcome)
        assertEquals(12_000L, values.prefetchCapMs)
        assertEquals(5, values.prefetchSegmentCount)
        assertEquals(2_000L, values.prefetchTargetDurationMs)
        assertEquals(9_000L, values.videoPtsMs)
        assertEquals(9_000_000L, values.videoPtsUs)
        assertEquals(12L, values.videoProgressCount)
        assertEquals(2_400L, values.firstFrameLatencyMs)
        assertEquals(
            com.kantv.app.playback.StreamDeliveryStrategy.HLS_CACHED_PLAYLIST,
            NativeDiagnosticMapper.deliveryStrategyOf(values),
        )
        assertEquals(
            com.kantv.app.playback.StreamDeliveryReason.PREFETCH_ENABLED,
            NativeDiagnosticMapper.deliveryReasonOf(values),
        )
    }
}
