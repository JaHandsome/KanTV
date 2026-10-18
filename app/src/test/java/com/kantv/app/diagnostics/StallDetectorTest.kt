package com.kantv.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class StallDetectorTest {
    @Test
    fun reportsNoFirstFrameOnceAfterThirtyActiveSeconds() {
        val detector = StallDetector()
        detector.start(1, 0)

        assertNull(detector.sample(sample(1, 29_999)))
        assertEquals(DiagnosticReportReason.NO_FIRST_FRAME_30S, detector.sample(sample(1, 30_000)))
        assertNull(detector.sample(sample(1, 60_000)))
    }

    @Test
    fun stableFrameProgressDoesNotReport() {
        val detector = StallDetector()
        detector.start(1, 0)

        for (second in 0..60 step 3) {
            assertNull(detector.sample(sample(1, second * 1000L, frames = second + 1L, timeMs = second * 1000L)))
        }
    }

    @Test
    fun advancingClockCannotHideFrozenFrames() {
        val detector = StallDetector()
        detector.start(1, 0)
        assertNull(detector.sample(sample(1, 0, frames = 10, timeMs = 1_000)))

        for (second in 3..12 step 3) {
            assertNull(detector.sample(sample(1, second * 1000L, frames = 10, timeMs = (second + 1) * 1000L)))
        }
        assertEquals(
            DiagnosticReportReason.PLAYBACK_STALLED_15S,
            detector.sample(sample(1, 15_000, frames = 10, timeMs = 16_000)),
        )
        assertNull(detector.sample(sample(1, 18_000, frames = 10, timeMs = 19_000)))
    }

    @Test
    fun frameCounterResetUpdatesBaselineAndCanRecover() {
        val detector = StallDetector()
        detector.start(1, 0)
        assertNull(detector.sample(sample(1, 0, frames = 100, timeMs = 1_000)))
        assertNull(detector.sample(sample(1, 6_000, frames = 100, timeMs = 7_000)))
        assertNull(detector.sample(sample(1, 9_000, frames = 0, timeMs = 0)))
        assertNull(detector.sample(sample(1, 12_000, frames = 1, timeMs = 500)))
        assertNull(detector.sample(sample(1, 24_000, frames = 1, timeMs = 12_500)))
        assertEquals(
            DiagnosticReportReason.PLAYBACK_STALLED_15S,
            detector.sample(sample(1, 27_000, frames = 1, timeMs = 15_500)),
        )
    }

    @Test
    fun dimensionsAreRequiredBeforeFirstFrame() {
        val detector = StallDetector()
        detector.start(1, 0)

        assertEquals(
            DiagnosticReportReason.NO_FIRST_FRAME_30S,
            detector.sample(sample(1, 30_000, frames = 20, width = 0, height = 0)),
        )
    }

    @Test
    fun menuPauseBackgroundAndDisabledObservationDoNotConsumeTimeout() {
        val detector = StallDetector()
        detector.start(1, 0)
        detector.event(1, DiagnosticEntryKind.MENU_OPEN, 10_000)
        detector.event(1, DiagnosticEntryKind.MENU_CLOSE, 40_000)
        assertNull(detector.sample(sample(1, 45_000, observationEnabled = false)))
        detector.event(1, DiagnosticEntryKind.BACKGROUND, 50_000)
        detector.event(1, DiagnosticEntryKind.FOREGROUND, 80_000)
        detector.event(1, DiagnosticEntryKind.USER_PAUSE, 85_000)
        detector.event(1, DiagnosticEntryKind.USER_RESUME, 115_000)

        assertNull(detector.sample(sample(1, 144_999)))
        assertEquals(DiagnosticReportReason.NO_FIRST_FRAME_30S, detector.sample(sample(1, 145_000)))
    }

    @Test
    fun staleSamplesFromPreviousSessionAreIgnored() {
        val detector = StallDetector()
        detector.start(1, 0)
        assertNull(detector.sample(sample(1, 29_000)))
        detector.start(2, 100_000)

        assertNull(detector.sample(sample(1, 130_000)))
        assertNull(detector.sample(sample(2, 129_999)))
        assertEquals(DiagnosticReportReason.NO_FIRST_FRAME_30S, detector.sample(sample(2, 130_000)))
    }

    @Test
    fun nativePageFrameProgressDoesNotReportNoFirstFrame() {
        val detector = StallDetector()
        detector.start(1, 0)

        // NATIVE_PAGE 没有镜像输出：presentedFrames 保持 -1，网页 frames 持续推进即视为有画面。
        for (second in 0..60 step 3) {
            assertNull(detector.sample(sample(
                1,
                second * 1000L,
                frames = second + 1L,
                timeMs = second * 1000L,
                presentedFrames = -1,
            )))
        }
    }

    @Test
    fun nativePageZeroFramesStillReportsNoFirstFrameAtThirtySeconds() {
        val detector = StallDetector()
        detector.start(1, 0)

        // 真实初始化失败：readyState=1 且 frames 恒 0，30 秒仍必须上报。
        assertNull(detector.sample(sample(1, 0, frames = 0, presentedFrames = -1)))
        assertNull(detector.sample(sample(1, 29_999, frames = 0, presentedFrames = -1)))
        assertEquals(
            DiagnosticReportReason.NO_FIRST_FRAME_30S,
            detector.sample(sample(1, 30_000, frames = 0, presentedFrames = -1)),
        )
    }

    @Test
    fun mirrorInputProgressCannotMaskFrozenTextureOutput() {
        val detector = StallDetector()
        detector.start(1, 0)

        // MIRROR 以 TextureView 输出为权威：网页 frames 增长，但 presentedFrames 不动仍判停滞。
        assertNull(detector.sample(sample(1, 0, frames = 1, presentedFrames = 10)))
        for (second in 3..12 step 3) {
            assertNull(detector.sample(sample(
                1,
                second * 1000L,
                frames = second + 1L,
                presentedFrames = 10,
            )))
        }
        assertEquals(
            DiagnosticReportReason.PLAYBACK_STALLED_15S,
            detector.sample(sample(1, 15_000, frames = 16, presentedFrames = 10)),
        )
    }

    @Test fun foregroundRuntimeAndNodeRebuildGetNewFirstFrameWindow() {
        val detector = StallDetector()
        detector.start(1, 0)
        assertNull(detector.sample(geckoSample(0, 500, runtime = 1, node = 4)))
        detector.event(1, DiagnosticEntryKind.BACKGROUND, 3_000)
        detector.event(1, DiagnosticEntryKind.FOREGROUND, 90_000)
        assertNull(detector.sample(geckoSample(90_000, -1, runtime = 2, node = 5, ready = 0, paused = true)))
        assertNull(detector.sample(geckoSample(93_000, 0, runtime = 2, node = 6, ready = 1)))
        assertNull(detector.sample(geckoSample(99_019, 83, runtime = 2, node = 6)))
        assertNull(detector.sample(geckoSample(108_000, 200, runtime = 2, node = 6)))
    }

    @Test fun sameNodeBufferingRetainsPlaybackStallProtection() {
        val detector = StallDetector()
        detector.start(1, 0)
        detector.sample(geckoSample(0, 83))
        assertNull(detector.sample(geckoSample(12_000, 83, ready = 0)))
        assertEquals(DiagnosticReportReason.PLAYBACK_STALLED_15S,
            detector.sample(geckoSample(15_000, 83, ready = 0)))
    }

    @Test fun initialPausedReadyZeroStillHasThirtySecondNoFrameGuard() {
        val detector = StallDetector()
        detector.start(1, 0)
        assertNull(detector.sample(geckoSample(0, 0, ready = 0, paused = true)))
        assertEquals(DiagnosticReportReason.NO_FIRST_FRAME_30S,
            detector.sample(geckoSample(30_000, 0, ready = 0, paused = true)))
    }

    @Test fun mediaPauseAndStaleProbeCannotBecomePlaybackFaultOrRecovery() {
        val detector = StallDetector()
        detector.start(1, 0)
        detector.sample(geckoSample(0, 83))
        assertNull(detector.sample(geckoSample(30_000, 83, paused = true)))
        assertNull(detector.sample(geckoSample(60_000, 83, status = "INSUFFICIENT_STALE_SAMPLE")))
        assertNull(detector.sample(geckoSample(90_000, 83)))
        assertEquals(DiagnosticReportReason.PLAYBACK_STALLED_15S,
            detector.sample(geckoSample(105_000, 83)))
        assertFalse(madeProgress(geckoSample(0, 1).values,
            geckoSample(1, 10, status = "INSUFFICIENT_STALE_SAMPLE").values))
    }

    @Test fun unavailableInitialProbeStillReportsUnobservedFirstFrameAtThirtySeconds() {
        for (status in listOf("INSUFFICIENT_NO_SAMPLE", "INSUFFICIENT_STALE_SAMPLE")) {
            val detector = StallDetector()
            detector.start(1, 0)
            assertNull(detector.sample(geckoSample(0, 999, status = status)))
            assertNull(detector.sample(geckoSample(29_999, 999, status = status)))
            assertEquals(DiagnosticReportReason.NO_FIRST_FRAME_30S,
                detector.sample(geckoSample(30_000, 999, status = status)))
        }
    }

    @Test fun unavailableNewRuntimeDoesNotResetStartupTimerEverySample() {
        val detector = StallDetector()
        detector.start(1, 0)
        detector.sample(geckoSample(0, 83))
        assertNull(detector.sample(geckoSample(10_000, 83, runtime = 2, status = "INSUFFICIENT_RUNTIME_CHANGED")))
        assertNull(detector.sample(geckoSample(25_000, 83, runtime = 2, status = "INSUFFICIENT_RUNTIME_CHANGED")))
        assertEquals(DiagnosticReportReason.NO_FIRST_FRAME_30S,
            detector.sample(geckoSample(40_000, 83, runtime = 2, status = "INSUFFICIENT_RUNTIME_CHANGED")))
    }

    @Test fun rebuildWithoutAnyValidProbeStartsItsOwnThirtySecondWindow() {
        val detector = StallDetector()
        detector.start(1, 0)
        assertNull(detector.sample(geckoSample(0, -1, runtime = 1, status = "INSUFFICIENT_NO_SAMPLE")))
        assertNull(detector.sample(geckoSample(20_000, -1, runtime = 2, status = "INSUFFICIENT_NO_SAMPLE")))
        assertNull(detector.sample(geckoSample(30_000, -1, runtime = 2, status = "INSUFFICIENT_NO_SAMPLE")))
        assertEquals(DiagnosticReportReason.NO_FIRST_FRAME_30S,
            detector.sample(geckoSample(50_000, -1, runtime = 2, status = "INSUFFICIENT_NO_SAMPLE")))
    }

    @Test fun pendingCallbackUsesMeasuredQualityThenChangesCounterBaseline() {
        val detector = StallDetector()
        detector.start(1, 0)
        val quality = geckoSample(0, 200).values.copy(videoTimingSource = "GECKO_RVFC_PENDING")
        assertNull(detector.sample(OfficialWebDiagnosticSample(1, 0, quality)))
        val callback = quality.copy(presentedFrames = 2, videoTimingSource = "GECKO_RVFC")
        assertNull(detector.sample(OfficialWebDiagnosticSample(1, 12_000, callback)))
        assertNull(detector.sample(OfficialWebDiagnosticSample(1, 26_999, callback)))
        assertEquals(DiagnosticReportReason.PLAYBACK_STALLED_15S,
            detector.sample(OfficialWebDiagnosticSample(1, 27_000, callback)))
    }

    private fun geckoSample(
        elapsedMs: Long, frames: Long, runtime: Long = 1, node: Long = 1,
        ready: Int = 4, paused: Boolean = false, status: String = "VALID",
    ) = OfficialWebDiagnosticSample(1, elapsedMs, DiagnosticPlaybackValues(
        frames = frames, timeMs = elapsedMs + 564, width = 1280, height = 720,
        readyState = ready, paused = paused, buffering = ready < 3,
        webRuntimeGeneration = runtime, nodeGeneration = node,
        measurement = DiagnosticPlaybackValues.MeasurementEvidence(probeObservationStatus = status),
    ))

    private fun sample(
        sessionId: Long,
        elapsedMs: Long,
        frames: Long = -1,
        timeMs: Long = -1,
        presentedFrames: Long = -1,
        width: Int = 1280,
        height: Int = 720,
        observationEnabled: Boolean = true,
    ) = OfficialWebDiagnosticSample(
        sessionId = sessionId,
        elapsedMs = elapsedMs,
        values = DiagnosticPlaybackValues(
            timeMs = timeMs,
            frames = frames,
            presentedFrames = presentedFrames,
            width = width,
            height = height,
        ),
        observationEnabled = observationEnabled,
    )
}
