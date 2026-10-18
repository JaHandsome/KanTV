package com.kantv.app.diagnostics

/** Tracks receipt time on Android; document identity is internal and never serialized. */
internal class GeckoProbeObservation {
    private var runtimeGeneration = -1L
    private var documentId = -1L
    private var pageNodeGeneration = -1L
    private var tick = -1L
    private var receivedAtMs = -1L
    private var suspended = false
    private var tickNotAdvancing = false
    private var measurementAtReceipt: DiagnosticPlaybackValues.MeasurementEvidence? = null
    var nodeGeneration = -1L
        private set

    fun accept(runtime: Long, document: Long, node: Long, sampleTick: Long, nowMs: Long): Boolean {
        if (sampleTick < 0) return false
        val hasIdentity = document >= 0 && node >= 0
        // Legacy bridge facts still feed login/watchdog compatibility, never fresh diagnostic progress.
        if (!hasIdentity && runtime == runtimeGeneration && documentId >= 0) return false
        if (runtime == runtimeGeneration && document < documentId) return false
        val newDocument = runtime != runtimeGeneration || (hasIdentity && document != documentId)
        if (!newDocument && sampleTick <= tick) {
            tickNotAdvancing = true
            return false
        }
        if (hasIdentity && (newDocument || node != pageNodeGeneration)) nodeGeneration++
        runtimeGeneration = runtime
        documentId = document
        pageNodeGeneration = node
        tick = sampleTick
        receivedAtMs = nowMs
        tickNotAdvancing = false
        measurementAtReceipt = null
        suspended = false
        return true
    }

    /** Suspension invalidates availability even when the same warm session later resumes. */
    fun invalidate() { suspended = true }

    fun recordMeasurementAtReceipt(measurement: DiagnosticPlaybackValues.MeasurementEvidence) {
        measurementAtReceipt = measurement
    }

    fun sample(values: DiagnosticPlaybackValues, nowMs: Long, runtime: Long): DiagnosticPlaybackValues {
        val age = if (receivedAtMs >= 0) (nowMs - receivedAtMs).coerceAtLeast(0) else -1L
        val status = when {
            receivedAtMs < 0 -> "INSUFFICIENT_NO_SAMPLE"
            suspended -> "INSUFFICIENT_SUSPENDED"
            runtime != runtimeGeneration -> "INSUFFICIENT_RUNTIME_CHANGED"
            age > MAX_PROBE_AGE_MS -> "INSUFFICIENT_STALE_SAMPLE"
            tickNotAdvancing -> "INSUFFICIENT_TICK_NOT_ADVANCING"
            documentId < 0 || pageNodeGeneration < 0 -> "INSUFFICIENT_NO_IDENTITY"
            else -> "VALID"
        }
        // The activity may write this method's output back into shared probeValues.
        // Preserve the original per-tick evidence so repeated reads cannot accumulate ages
        // or turn an earlier derived invalidation into the next sample's raw source state.
        val evidence = measurementAtReceipt ?: values.measurement.also { measurementAtReceipt = it }
        // Both ages are durations: add Android receipt age to the browser cache age,
        // never compare the unrelated performance and elapsedRealtime clock origins.
        val audioAge = if (evidence.geckoAudioClockAgeMs < 0 || age < 0) -1L else
            evidence.geckoAudioClockAgeMs.coerceAtMost(DiagnosticPlaybackValues.MAX_WEB_INTEGER - age
                .coerceAtMost(DiagnosticPlaybackValues.MAX_WEB_INTEGER)) +
                age.coerceAtMost(DiagnosticPlaybackValues.MAX_WEB_INTEGER)
        val available = evidence.geckoAudioClockStatus == "AVAILABLE"
        val audioStatus = when {
            !available -> evidence.geckoAudioClockStatus
            status != "VALID" -> "INSUFFICIENT_PROBE_INVALID"
            audioAge < 0 || audioAge > MAX_PROBE_AGE_MS -> "INSUFFICIENT_STALE_SAMPLE"
            else -> "AVAILABLE"
        }
        val measurement = if (available && audioStatus != "AVAILABLE") evidence.copy(
            geckoAudioClockStatus = audioStatus,
            geckoAudioClockUs = -1,
            geckoAudioClockAgeMs = audioAge,
            geckoPairedVideoPtsUs = -1,
            geckoAudioMinusVideoUs = -1,
            geckoAvPairSpanMs = -1,
        ) else evidence.copy(geckoAudioClockAgeMs = audioAge)
        return values.copy(
            jsSampleAgeMs = age,
            measurement = measurement.copy(
                probeObservationStatus = status,
                avSyncAssessment = if (audioStatus == "AVAILABLE")
                    "UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED" else "UNKNOWN_NO_MEDIA_ELEMENT_AUDIO_OUTPUT_TIMESTAMP",
            ),
        )
    }

    companion object {
        // One-second bridge ticks; allow scheduling jitter, never classify a stopped probe as video freeze.
        const val MAX_PROBE_AGE_MS = 5_000L
    }
}

/** Shared by every delayed bridge entry; identity is checked again when queued work executes. */
internal fun isCurrentGeckoMessage(
    capturedGeneration: Long,
    currentGeneration: Long,
    capturedSession: Any?,
    currentSession: Any?,
): Boolean = capturedSession != null && capturedSession === currentSession &&
    capturedGeneration == currentGeneration
