package com.kantv.app.diagnostics

/** Pure state machine; callers supply monotonic elapsed time. */
class StallDetector(
    private val noFirstFrameMs: Long = 30_000,
    private val playbackStallMs: Long = 15_000,
) {
    private var sessionId: Long? = null
    private var active = false
    private var lastElapsedMs = 0L
    private var activeElapsedMs = 0L
    private var noFirstFrameBaselineActiveMs = 0L
    private var lastProgressActiveMs = 0L
    private var lastFrameCount: Long? = null
    private var lastTimeMs = -1L
    private var firstFrameSeen = false
    private var lastValues: DiagnosticPlaybackValues? = null
    private var lastCounterPresented = false
    private var menuVisible = false
    private var inBackground = false
    private var userPaused = false
    private var sampleSuppressed = false
    private val reported = mutableSetOf<DiagnosticReportReason>()

    init {
        require(noFirstFrameMs > 0 && playbackStallMs > 0)
    }

    fun start(sessionId: Long, elapsedMs: Long) {
        require(sessionId >= 0 && elapsedMs >= 0)
        this.sessionId = sessionId
        active = true
        lastElapsedMs = elapsedMs
        activeElapsedMs = 0
        noFirstFrameBaselineActiveMs = 0
        lastProgressActiveMs = 0
        lastFrameCount = null
        lastTimeMs = -1
        firstFrameSeen = false
        lastValues = null
        lastCounterPresented = false
        menuVisible = false
        inBackground = false
        userPaused = false
        sampleSuppressed = false
        reported.clear()
    }

    fun sample(sample: OfficialWebDiagnosticSample): DiagnosticReportReason? {
        if (!active || sample.sessionId != sessionId || sample.elapsedMs < lastElapsedMs) return null
        val values = sample.values
        val previousValues = lastValues
        val generationChanged = previousValues != null && counterGenerationChanged(previousValues, values)
        // A rebuilt runtime/video starts its own first-frame window, not the old playback-stall window.
        if (generationChanged) {
            firstFrameSeen = false
            lastFrameCount = null
            lastTimeMs = -1
            reported.clear()
            noFirstFrameBaselineActiveMs = activeElapsedMs
            lastProgressActiveMs = activeElapsedMs
        }
        val wasEligible = eligible()
        val probeValid = values.measurement.probeObservationStatus in setOf("NOT_APPLICABLE", "VALID")
        // Initial media.paused/ready0 is normal startup and must retain the 30-second guard.
        val mediaPausedAfterPlayback = firstFrameSeen && values.paused &&
            values.measurement.probeObservationStatus != "NOT_APPLICABLE"
        sampleSuppressed = (!probeValid && firstFrameSeen) || mediaPausedAfterPlayback ||
            !sample.observationEnabled || sample.userPaused ||
            sample.menuVisible || sample.appInBackground
        val isEligible = eligible()
        advance(sample.elapsedMs, wasEligible && isEligible && !generationChanged)
        // Identity remains meaningful even when collection is unavailable.
        lastValues = values
        if (!isEligible) return null
        if (!wasEligible) resetWaitingBaseline()
        // No fresh sample proves neither a first frame nor health. Preserve the startup wait
        // report with its INSUFFICIENT status; after playback, collection gaps suppress stalls.
        if (!probeValid) return DiagnosticReportReason.NO_FIRST_FRAME_30S.takeIf {
            !firstFrameSeen && activeElapsedMs - noFirstFrameBaselineActiveMs >= noFirstFrameMs && reported.add(it)
        }

        val hasDimensions = values.width > 0 && values.height > 0
        val frameCount = when {
            values.presentedFrames >= 0 -> values.presentedFrames
            values.frames >= 0 -> values.frames
            else -> null
        }
        val counterPresented = values.presentedFrames >= 0
        if (lastFrameCount != null && counterPresented != lastCounterPresented) {
            // Different evidence sources cannot be compared; require the next real advance.
            lastFrameCount = frameCount
            lastProgressActiveMs = activeElapsedMs
        }
        lastCounterPresented = counterPresented
        var frameProgressed = false
        if (frameCount != null && hasDimensions) {
            val previous = lastFrameCount
            frameProgressed = when {
                previous == null -> frameCount > 0
                frameCount < previous -> false
                else -> frameCount > previous
            }
            lastFrameCount = frameCount
        }
        var timeProgressed = false
        val clockUsable = values.measurement.probeObservationStatus == "NOT_APPLICABLE" ||
            (values.readyState >= 2 && !values.paused)
        if (frameCount == null && hasDimensions && values.timeMs >= 0 && clockUsable) {
            timeProgressed = values.timeMs > 0 &&
                (lastTimeMs < 0 || values.timeMs - lastTimeMs >= 50)
            lastTimeMs = values.timeMs
        }
        val progressed = frameProgressed || timeProgressed
        if (progressed) {
            firstFrameSeen = true
            lastProgressActiveMs = activeElapsedMs
        }
        val reason = when {
            !firstFrameSeen && activeElapsedMs - noFirstFrameBaselineActiveMs >= noFirstFrameMs ->
                DiagnosticReportReason.NO_FIRST_FRAME_30S
            firstFrameSeen && activeElapsedMs - lastProgressActiveMs >= playbackStallMs ->
                DiagnosticReportReason.PLAYBACK_STALLED_15S
            else -> null
        }
        return reason?.takeIf(reported::add)
    }

    fun event(sessionId: Long, kind: DiagnosticEntryKind, elapsedMs: Long) {
        if (!active || sessionId != this.sessionId || elapsedMs < lastElapsedMs) return
        val wasEligible = eligible()
        advance(elapsedMs, wasEligible)
        when (kind) {
            DiagnosticEntryKind.MENU_OPEN -> menuVisible = true
            DiagnosticEntryKind.MENU_CLOSE -> menuVisible = false
            DiagnosticEntryKind.BACKGROUND -> inBackground = true
            DiagnosticEntryKind.FOREGROUND -> inBackground = false
            DiagnosticEntryKind.USER_PAUSE -> userPaused = true
            DiagnosticEntryKind.USER_RESUME -> userPaused = false
            DiagnosticEntryKind.STOP, DiagnosticEntryKind.SWITCH -> active = false
            else -> Unit
        }
        sampleSuppressed = false
        if (!wasEligible && eligible()) resetWaitingBaseline()
    }

    private fun eligible(): Boolean = active && !menuVisible && !inBackground && !userPaused && !sampleSuppressed

    private fun advance(elapsedMs: Long, countAsActive: Boolean) {
        if (countAsActive) activeElapsedMs += elapsedMs - lastElapsedMs
        lastElapsedMs = elapsedMs
    }

    private fun resetWaitingBaseline() {
        if (firstFrameSeen) lastProgressActiveMs = activeElapsedMs
        else noFirstFrameBaselineActiveMs = activeElapsedMs
    }
}
