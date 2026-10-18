package com.kantv.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSchemaV4Test {
    @Test
    fun readableDeviceBuildValuesBecomeOpaqueDigests() {
        val raw = "TCL Smart TV Pro"
        val digest = diagnosticOpaqueDeviceValue(raw)
        assertTrue(digest.matches(Regex("[0-9a-f]{64}")))
        assertFalse(digest.contains("TCL", ignoreCase = true))
        assertEquals("unknown", diagnosticOpaqueDeviceValue("unknown"))
    }

    @Test
    fun actualSerializerEmitsExactCurrentV12SampleContract() {
        val fields = DiagnosticLogEntry(
            DiagnosticEntryKind.SAMPLE,
            1,
            DiagnosticPlaybackValues(),
        ).toSerializableFields()
        val expected = setOf(
            "kind", "elapsedMs", "timeMs", "frames", "width", "height", "readyState",
            "paused", "buffering", "errorCode", "droppedFrames", "presentedFrames",
            "jsSampleAgeMs", "processPssKb", "rendererState", "kernelCode", "deliveryCode",
            "videoProgressCount", "decodedFrames", "displayedFrames", "readBytes",
            "demuxReadBytes", "inputKbps", "demuxKbps", "demuxCorrupted",
            "demuxDiscontinuity", "decodedAudio", "playedAudioBuffers", "lostAudioBuffers",
            "selectedAudioTrackId", "configuredAudioDelayUs", "sourceAudioPid1",
            "sourceAudioMinusVideoMs1", "sourceAudioPid2", "sourceAudioMinusVideoMs2",
            "videoDecoderName", "audioDecoderName", "audioMimeType", "audioCodec",
            "audioChannelCount", "audioSampleRate", "bufferedPositionMs", "bufferDurationMs",
            "videoPtsUs", "audioPtsUs", "playbackStateCode", "generation", "pictureModeCode",
            "audioInputPtsUs", "audioSinkPositionUs", "audioSinkPositionAvailable",
            "audioOutputEncoding", "audioOutputSampleRate", "audioOutputChannelConfig",
            "audioOutputBufferSize", "audioOutputMode", "audioOutputRoute",
            "audioOutputTunneling", "audioOutputOffload", "audioTrackTimestampAvailability",
            "audioTrackPlaybackHeadPosition", "underrunBufferSize", "underrunBufferSizeMs",
            "underrunElapsedSinceLastFeedMs", "audioSinkDiscontinuityCount",
            "audioTrackReleased", "videoPlannedReleaseTimeNs", "videoCallbackNanoTime",
            "videoTimingSource", "videoProcessingOffsetUs", "videoProcessingOffsetAvailable",
            "videoProcessingFrameCount", "videoFirstFrameRenderTimeMs", "droppedFramesElapsedMs",
            "videoFirstFrameSource",
            "actualPresentedAvailability", "tsInputSource", "tsPcrAvailable", "tsPcrPid", "tsPcrSpanUs",
            "tsPcrMaxJumpUs", "tsPcrBackwardJumpCount", "tsAdaptationDiscontinuityCount",
            "tsFirstAudioMinusVideoUs", "tsFirstAudioMinusVideoAvailable",
            "tsHlsDiscontinuity", "tsHlsDiscontinuitySequence", "tsWouldRepair",
            "tsRepairCorrectionSeconds", "tsRepairHeaderCount",
            "nodeCount", "nodeGeneration", "webRuntimeGeneration", "webState", "webRecoveryStage",
            "mirrorWorkerSwitches", "mirrorInstanceSwitches", "mirrorAcceptedFragments", "mirrorRejectedFragments",
            "mirrorWatchdogRebuilds", "mirrorWatchdogReason",
            "routeGeneration", "mirrorStage", "mirrorNavigationState", "mirrorExtensionState",
            "mirrorBridgeReady", "mirrorHookRequested", "mirrorServerStarts", "mirrorVideoInitCount",
            "mirrorAudioInitCount", "mirrorAcceptedVideoFragments", "mirrorAcceptedAudioFragments",
            "mirrorRejectedVideoFragments", "mirrorRejectedAudioFragments", "mirrorFirstVideoInitElapsedMs",
            "mirrorFirstAudioInitElapsedMs", "mirrorFirstAcceptedFragmentElapsedMs", "mirrorPlayerCreated",
            "mirrorFirstFrameRendered", "mirrorOutputUpdates",
            "geckoPlaybackStrategy",
            "geckoPresentationMode", "geckoFullscreenRequestCount", "geckoFullscreenRequestAccepted",
            "geckoFullscreenActive", "geckoFullscreenEnterCount", "geckoFullscreenExitCount",
            "geckoCssFitApplied", "geckoPresentationRebindCount", "geckoPresentationTargetWidth",
            "geckoPresentationTargetHeight", "geckoPresentationViewportWidth",
            "geckoPresentationViewportHeight", "geckoPresentationCoveragePermille",
            // v13：央视频选台点击的落地事实（仅布尔，不含页面文本）。
            "labelFound", "labelClicked",
            "sourceOrdinal", "sourceCount", "sourceHash", "sourceGeneration", "media3Generation",
            "deliveryStrategyCode", "deliveryReasonCode", "prefetchQueued", "prefetchReady",
            "prefetchFailed", "prefetchCancelled", "prefetchWaitMs", "prefetchCapMs",
            "prefetchSegmentCount", "prefetchTargetDurationMs", "prefetchOutcome",
            "rebufferDurationMs", "rebufferCountWindow", "rebufferTotalMsWindow", "rebufferReason",
            "rebufferUnstableReason", "sourceHealthScore", "recoveryReason", "greyScreenReason",
            "firstFrameLatencyMs", "videoPtsMs", "audioAdvancedWithoutVideo",
            "videoAdvancedWithoutAudio",
            // v11：合成层 / 决策层 / 上下文层证据
            "visualSampleOutcome", "visualSampleCallbackMs", "millisSincePictureVerified",
            "wedgeActive", "watchdogDecision", "watchdogReason",
            "watchdogRecoveries", "watchdogRecoveryLimit", "sampleSkipReason", "probeTimedOut",
            "networkTransport", "networkMetered", "millisSinceHeartbeat",
            "samplingSuspended", "samplingSuspendedMs",
            // v12：源的上游身份 + 用户可见状态。接收端对字段集合要求严格相等，所以这一条测试
            // 就是客户端侧的契约闸门：改了字段而没同步接收端，会在这里先失败，而不是在电视上静默被拒。
            "nativeLiveContextAgeMs", "nativeLiveOffsetMs", "nativeLiveWindowDurationMs", "nativeLiveMediaSequence", "nativeLastMediaLoadDurationMs", "nativeLastMediaLoadBytes", "nativeLastLoadErrorCode",
            "nativeAudioClockStatus", "nativeAvGeneration", "nativeAudioStreamOffsetUs", "nativeAudioClockUs",
            "nativeAudioClockAgeMs", "nativePairedVideoPtsUs", "nativeAudioMinusVideoUs",
            "nativeAvPairSpanUs", "nativeVideoReleaseDelayUs",
            "geckoAudioClockStatus", "geckoAudioClockUs", "geckoAudioClockAgeMs",
            "geckoAudioClockCallDurationMs", "geckoPairedVideoPtsUs", "geckoAudioMinusVideoUs", "geckoAvPairSpanMs",
            "probeObservationStatus", "avSyncAssessment", "videoCallbackAgeMs",
            "videoPresentationAgeMs", "videoExpectedDisplayDelayUs",
            "sourceRoute", "sourceQuality", "sourceUrlKey", "sourceScoreUpstream",
            "userVisibleState", "userVisibleStateMs",
            "nativeLoadActiveStatus",
            "nativeLoadActiveTrackType",
            "nativeLoadActiveStartElapsedMs",
            "nativeLoadActiveMediaStartMs",
            "nativeLoadActiveMediaEndMs",
            "nativeLoadActiveRetryCount",
            "nativeLoadLastOutcome",
            "nativeLoadLastTrackType",
            "nativeLoadLastStartElapsedMs",
            "nativeLoadLastEndElapsedMs",
            "nativeLoadLastMediaStartMs",
            "nativeLoadLastMediaEndMs",
            "nativeLoadLastRetryCount",
            "nativeLoadLastBytes",
            "nativeLoadLastHttpStatus",
            "nativeLoadPositionStatus",
            "nativeLoadWindowStartInPeriodMs",
            "nativeTransferActiveCount",
            "nativeTransferSequence",
            "nativeTransferOutcome",
            "nativeTransferTaskMatchStatus",
            "nativeTransferStartElapsedMs",
            "nativeTransferEndElapsedMs",
            "nativeTransferOpenDurationUs",
            "nativeTransferReadDurationUs",
            "nativeTransferCloseDurationUs",
            "nativeTransferReadCallCount",
            "nativeTransferBytes",
            "nativeTransferEofSeen",
            "geckoNodeBoundMs",
            "geckoLoadedMetadataMs",
            "geckoPlayMs",
            "geckoPlayingMs",
            "geckoWaitingMs",
            "geckoCanPlayMs",
            "geckoFirstRvfcMs",
            "geckoFirstRvfcPtsUs",
            "geckoJsTickGapMs",
            "geckoAudioQuerySeq",
            "geckoAudioQueryEpoch",
            "geckoAudioQueryNode",
            "geckoAudioQueryStartMs",
            "geckoAudioSyncCallMs",
            "geckoAudioReactionElapsedMs",
            "geckoAudioTimeoutLateMs",
            "geckoAttachLate",
            "geckoAudioInFlight",
            "geckoAudioQueryState",
            "geckoAudioQueryDisposition",
            "nativeMediaRequestMatchStatus",
            "nativeMediaRequestCoverage",
            "nativeMediaRequestOutcome",
            "nativeMediaRequestOpenDurationUs",
            "nativeMediaRequestReadDurationUs",
            "nativeMediaRequestCloseDurationUs",
            "nativeMediaRequestReadCallCount",
            "nativeMediaRequestBytes",
            "nativeMediaRequestEofSeen",
            "geckoDocumentNowMs",
            "geckoDocumentTimeOriginMs",
            "geckoStateReceivedEpochMs",
            "geckoAudioQueryRvfcCount",
            "geckoAudioQueryMaxRvfcGapMs",
            "geckoBridgeLastTickDurationMs",
            "geckoBridgeLastTickMaxPhaseCode",
            "geckoBridgeLastTickMaxPhaseDurationMs",
            "geckoBridgeLastTickScanNodes",
            "geckoAudioQueryTickDurationMs",
            "geckoAudioQueryTickTailDurationMs",
            "geckoAudioQueryTickMaxPhaseCode",
            "geckoAudioQueryTickMaxPhaseDurationMs",
            "geckoAudioQueryTickScanNodes",
            "geckoStartupObserverTickCount",
            "geckoStartupObserverTotalDurationMs",
            "geckoStartupObserverMaxTickDurationMs",
            "geckoStartupResourceObservationStatus",
            "geckoStartupResourceCount",
            "geckoStartupResourceMaxDurationMs",
            "geckoStartupResourceMaxResponseWaitMs",
            "geckoStartupResourceMaxDownloadMs",
            "geckoStartupResourceDetailedCount",
            "geckoAudioQueryObservedWorkCount",
            "geckoAudioQueryObservedWorkTotalMs",
            "geckoAudioQueryObservedWorkMaxMs",
            "geckoAudioQueryObservedWorkMaxPhaseCode",
            "geckoAudioQueryObservedWorkMaxPhaseMs",
            "geckoAudioQueryObservedWorkMaxScanNodes",
            "geckoStartupResourceScannedCount",
            "geckoStartupResourceFilteredCount",
            "geckoStartupResourceMaxStartMs",
            "geckoStartupResourceMaxEndMs",
            "geckoStartupResourceMaxInitiatorCode",
        )

        assertEquals(22, DIAGNOSTIC_SCHEMA_VERSION)
        val currentExpected = expected + GeckoObserverDiagnosticEvidence().toFields().keys + "visualSampleBlockedReason"
        assertEquals(currentExpected, fields.keys)
        assertEquals(currentExpected + setOf(
            "schemaVersion", "reportId", "reason", "capturedAtMs", "appVersionName", "appVersionCode",
            "deviceManufacturer", "deviceModel", "deviceApiLevel", "deviceInstallId", "deviceLabel",
            "deviceCpuAbi", "deviceTotalRamMb", "displayWidth", "displayHeight", "channelId", "provider",
            "sessionId", "lifecycle", "closedAtMs", "fragmentSequence", "fragmentStartedElapsedMs",
            "fragmentEndedElapsedMs", "app", "device", "display", "runtime", "libraries",
            "decoderCapabilities", "samples", "audioEncodingMode", "audioTunneling",
            "audioTrackPositionFrames", "audioTimestampAvailable", "audioOutputChannelCount",
        ), DIAGNOSTIC_SERIALIZED_KEYS)
        val serialized = fields.toString().lowercase()
        for (forbidden in listOf("https://", "token", "cookie", "ssid", "bssid", "macaddress", "rawlog")) {
            assertFalse(forbidden, serialized.contains(forbidden))
        }

        val webFields = DiagnosticLogEntry(
            DiagnosticEntryKind.SAMPLE,
            2,
            DiagnosticPlaybackValues(
                nodeCount = 2,
                nodeGeneration = 3,
                webRuntimeGeneration = 4,
                webState = "NODE_REPLACED",
                webRecoveryStage = "MONITORING",
                mirrorWorkerSwitches = 5,
                mirrorInstanceSwitches = 6,
                mirrorAcceptedFragments = 7,
                mirrorRejectedFragments = 8,
                mirrorWatchdogRebuilds = 9,
                mirrorWatchdogReason = "ACCEPTED_FRAGMENT_STALL",
                routeGeneration = 10,
                mirrorStage = "INPUT_WITHOUT_OUTPUT_PROGRESS",
                mirrorNavigationState = "FINISHED",
                mirrorExtensionState = "READY",
                mirrorBridgeReady = true,
                mirrorHookRequested = true,
                mirrorServerStarts = 2,
                mirrorVideoInitCount = 11,
                mirrorAudioInitCount = 12,
                mirrorAcceptedVideoFragments = 13,
                mirrorAcceptedAudioFragments = 14,
                mirrorRejectedVideoFragments = 15,
                mirrorRejectedAudioFragments = 16,
                mirrorFirstVideoInitElapsedMs = 17,
                mirrorFirstAudioInitElapsedMs = 18,
                mirrorFirstAcceptedFragmentElapsedMs = 19,
                mirrorPlayerCreated = true,
                mirrorFirstFrameRendered = true,
                mirrorOutputUpdates = 20,
                geckoPlaybackStrategy = "NATIVE_PAGE",
                geckoPresentationMode = "AUTO_THEN_FIT",
                geckoFullscreenRequestCount = 1,
                geckoFullscreenRequestAccepted = false,
                geckoFullscreenActive = false,
                geckoFullscreenEnterCount = 0,
                geckoFullscreenExitCount = 0,
                geckoCssFitApplied = true,
                geckoPresentationRebindCount = 2,
                geckoPresentationTargetWidth = 1920,
                geckoPresentationTargetHeight = 1080,
                geckoPresentationViewportWidth = 1920,
                geckoPresentationViewportHeight = 1080,
                geckoPresentationCoveragePermille = 1000,
                labelFound = true,
                labelClicked = true,
            ),
        ).toSerializableFields()
        assertEquals(2, webFields["nodeCount"])
        assertEquals(3L, webFields["nodeGeneration"])
        assertEquals(4L, webFields["webRuntimeGeneration"])
        assertEquals("NODE_REPLACED", webFields["webState"])
        assertEquals("MONITORING", webFields["webRecoveryStage"])
        assertEquals(5L, webFields["mirrorWorkerSwitches"])
        assertEquals(6L, webFields["mirrorInstanceSwitches"])
        assertEquals(7L, webFields["mirrorAcceptedFragments"])
        assertEquals(8L, webFields["mirrorRejectedFragments"])
        assertEquals(9L, webFields["mirrorWatchdogRebuilds"])
        assertEquals("ACCEPTED_FRAGMENT_STALL", webFields["mirrorWatchdogReason"])
        assertEquals(10L, webFields["routeGeneration"])
        assertEquals("INPUT_WITHOUT_OUTPUT_PROGRESS", webFields["mirrorStage"])
        assertEquals(11L, webFields["mirrorVideoInitCount"])
        assertEquals(14L, webFields["mirrorAcceptedAudioFragments"])
        assertEquals(20L, webFields["mirrorOutputUpdates"])
        assertEquals("NATIVE_PAGE", webFields["geckoPlaybackStrategy"])
        assertEquals("AUTO_THEN_FIT", webFields["geckoPresentationMode"])
        assertEquals(1L, webFields["geckoFullscreenRequestCount"])
        assertEquals(true, webFields["geckoCssFitApplied"])
        assertEquals(2L, webFields["geckoPresentationRebindCount"])
        assertEquals(1000, webFields["geckoPresentationCoveragePermille"])
        assertEquals(true, webFields["labelFound"])
        assertEquals(true, webFields["labelClicked"])
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticPlaybackValues(sourceHash = "https://private.example/live")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticPlaybackValues(recoveryReason = "writer free text")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticPlaybackValues(prefetchOutcome = "MAYBE")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticPlaybackValues(webState = "MAYBE_DRM")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticPlaybackValues(mirrorStage = "https://private.example/live")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticPlaybackValues(geckoPlaybackStrategy = "free text")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticPlaybackValues(geckoPresentationMode = "free text")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticPlaybackValues(geckoPresentationCoveragePermille = 1001)
        }
    }

    @Test
    fun serverTimingKeepsOriginalBaselineAcrossGeckoReload() {
        val diagnosticStart = 1_000L
        val serverStart = 1_010L
        val geckoReloadAt = 61_000L

        assertEquals(62_010L, mirrorServerRelativeElapsedMs(
            serverStartedAtMs = serverStart,
            diagnosticStartedAtMs = diagnosticStart,
            serverElapsedMs = 62_000L,
        ))
        assertEquals(61_000L, geckoReloadAt)
    }

    @Test
    fun inputWithoutTextureUpdatesBecomesStructuredOutputStall() {
        assertFalse(isMirrorOutputStalled(
            firstFrameAtMs = 1_000L,
            lastInputProgressAtMs = 16_001L,
            lastOutputProgressAtMs = 1_000L,
            nowMs = 15_999L,
            timeoutMs = 15_000L,
        ))
        assertEquals(true, isMirrorOutputStalled(
            firstFrameAtMs = 1_000L,
            lastInputProgressAtMs = 16_001L,
            lastOutputProgressAtMs = 1_000L,
            nowMs = 16_000L,
            timeoutMs = 15_000L,
        ))
    }

    @Test
    fun nativePageSamplingOmitsMirrorOutputEvidence() {
        // MIRROR 保留实际 TextureView 输出作为权威。
        assertEquals(7L, presentedFramesForMirrorSample(usesMseMirror = true, mirrorOutputUpdates = 7L))
        assertEquals(0L, presentedFramesForMirrorSample(usesMseMirror = true, mirrorOutputUpdates = 0L))
        // NATIVE_PAGE 没有镜像输出：必须为 -1，避免用恒 0 覆盖网页 frames。
        assertEquals(-1L, presentedFramesForMirrorSample(usesMseMirror = false, mirrorOutputUpdates = 0L))
    }

    @Test
    fun playerErrorIsSerializableWithoutFreeText() {
        val fields = DiagnosticLogEntry(
            DiagnosticEntryKind.PLAYBACK_FAULT,
            3,
            DiagnosticPlaybackValues(errorCode = 2_004, mirrorStage = "PLAYER_ERROR"),
        ).toSerializableFields()

        assertEquals(2_004, fields["errorCode"])
        assertEquals("PLAYER_ERROR", fields["mirrorStage"])
        assertFalse(fields.keys.any { it.contains("message", ignoreCase = true) ||
            it.contains("cause", ignoreCase = true) })
    }
}
