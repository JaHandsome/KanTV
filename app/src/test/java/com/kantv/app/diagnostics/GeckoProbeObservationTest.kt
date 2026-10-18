package com.kantv.app.diagnostics

import org.junit.Assert.*
import org.junit.Test

class GeckoProbeObservationTest {
    @Test fun receiptAgeAndGenerationDoNotMixWebAndAndroidClocks() {
        val probe = GeckoProbeObservation()
        assertEquals("INSUFFICIENT_NO_SAMPLE", probe.sample(DiagnosticPlaybackValues(), 10_000, 1).measurement.probeObservationStatus)
        assertTrue(probe.accept(1, 1_800_000_000_000, 1, 1, 10_000))
        assertEquals(2_000L, probe.sample(DiagnosticPlaybackValues(), 12_000, 1).jsSampleAgeMs)
        assertEquals("VALID", probe.sample(DiagnosticPlaybackValues(), 15_000, 1).measurement.probeObservationStatus)
        assertEquals("INSUFFICIENT_STALE_SAMPLE", probe.sample(DiagnosticPlaybackValues(), 15_001, 1).measurement.probeObservationStatus)
        assertEquals("INSUFFICIENT_RUNTIME_CHANGED", probe.sample(DiagnosticPlaybackValues(), 12_000, 2).measurement.probeObservationStatus)
    }

    @Test fun duplicateTicksCannotRefreshAgeAndOldDocumentsCannotReplaceNew() {
        val probe = GeckoProbeObservation()
        assertTrue(probe.accept(1, 100, 1, 10, 0))
        assertFalse(probe.accept(1, 100, 1, 10, 4_000))
        assertEquals(4_000L, probe.sample(DiagnosticPlaybackValues(), 4_000, 1).jsSampleAgeMs)
        assertEquals("INSUFFICIENT_TICK_NOT_ADVANCING", probe.sample(DiagnosticPlaybackValues(), 4_000, 1).measurement.probeObservationStatus)
        assertTrue(probe.accept(1, 101, 1, 1, 5_000))
        val generation = probe.nodeGeneration
        assertFalse(probe.accept(1, 100, 2, 11, 6_000))
        assertEquals(generation, probe.nodeGeneration)
        assertTrue(probe.accept(1, 101, 1, 2, 6_000))
        assertEquals(generation, probe.nodeGeneration)
        assertTrue(probe.accept(1, 101, 2, 3, 7_000))
        assertEquals(generation + 1, probe.nodeGeneration)
    }

    @Test fun legacyTicksKeepFactsAvailableButNeverClaimValidIdentity() {
        val probe = GeckoProbeObservation()
        assertTrue(probe.accept(1, -1, -1, 1, 0))
        assertEquals("INSUFFICIENT_NO_IDENTITY", probe.sample(DiagnosticPlaybackValues(), 0, 1).measurement.probeObservationStatus)
        assertTrue(probe.accept(1, 100, 1, 2, 1_000))
        assertFalse(probe.accept(1, -1, -1, 3, 2_000))
        assertEquals("VALID", probe.sample(DiagnosticPlaybackValues(), 2_000, 1).measurement.probeObservationStatus)
    }

    @Test fun queuedMessagesCannotWriteNewRuntimeOrNewSession() {
        val oldSession = Any()
        var currentSession: Any? = oldSession
        var generation = 1L
        var writes = 0
        val queued = {
            if (isCurrentGeckoMessage(1, generation, oldSession, currentSession)) writes++
        }
        queued()
        assertEquals(1, writes)
        currentSession = null
        queued()
        currentSession = Any()
        queued()
        generation = 2
        currentSession = oldSession
        queued()
        assertEquals(1, writes)
        assertFalse(isCurrentGeckoMessage(2, 2, null, null))
    }

    @Test fun browserMeasurementsRemainUnknownForAudioEvenWithZeroVideoDelay() {
        val values = DiagnosticPlaybackValues(measurement = DiagnosticPlaybackValues.MeasurementEvidence(
            videoCallbackAgeMs = 0, videoPresentationAgeMs = 0, videoExpectedDisplayDelayUs = 0,
        ))
        val probe = GeckoProbeObservation()
        probe.accept(1, 100, 1, 1, 0)
        val measured = probe.sample(values, 0, 1)
        assertEquals("UNKNOWN_NO_MEDIA_ELEMENT_AUDIO_OUTPUT_TIMESTAMP", measured.measurement.avSyncAssessment)
        assertFalse(measured.audioSinkPositionAvailable)
        assertEquals(-1L, measured.audioSinkPositionUs)
        assertEquals(-1L, measured.videoPlannedReleaseTimeNs)
        assertEquals(-1L, measured.videoCallbackNanoTime)
        assertEquals(0L, measured.measurement.videoExpectedDisplayDelayUs)
    }
    private fun engineValues(age: Long = 100) = DiagnosticPlaybackValues(
        videoPtsUs = 999,
        measurement = DiagnosticPlaybackValues.MeasurementEvidence(
            geckoAudioClockStatus = "AVAILABLE", geckoAudioClockUs = 100,
            geckoAudioClockAgeMs = age, geckoAudioClockCallDurationMs = 1,
            geckoPairedVideoPtsUs = 101, geckoAudioMinusVideoUs = -1, geckoAvPairSpanMs = 40,
        ),
    )

    @Test fun exactEnginePairAndSignedMinusOneSurviveSerializationWithoutPhysicalSyncClaim() {
        val probe = GeckoProbeObservation()
        probe.accept(1, 100, 1, 1, 0)
        val measured = probe.sample(engineValues(), 1000, 1)
        assertEquals(1100L, measured.measurement.geckoAudioClockAgeMs)
        assertEquals("AVAILABLE", measured.measurement.geckoAudioClockStatus)
        assertEquals("UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED", measured.measurement.avSyncAssessment)
        assertEquals(101L, measured.measurement.geckoPairedVideoPtsUs)
        assertEquals(-1L, measured.measurement.geckoAudioMinusVideoUs)
        assertEquals(999L, measured.videoPtsUs)
        assertFalse(measured.audioSinkPositionAvailable)
        val fields = DiagnosticLogEntry(DiagnosticEntryKind.SAMPLE, 1000, measured).toSerializableFields()
        assertEquals(-1L, fields["geckoAudioMinusVideoUs"])
        assertEquals(101L, fields["geckoPairedVideoPtsUs"])
    }

    @Test fun receiptAgeDoesNotAccumulateWhenSamplingOutputIsWrittenBack() {
        val probe = GeckoProbeObservation()
        probe.accept(1, 100, 1, 1, 0)
        var values = engineValues()
        for (seconds in 1L..3L) {
            values = probe.sample(values, seconds * 1000, 1)
            assertEquals(seconds * 1000 + 100, values.measurement.geckoAudioClockAgeMs)
            assertEquals("AVAILABLE", values.measurement.geckoAudioClockStatus)
        }
        values = probe.sample(values, 4901, 1)
        assertEquals("INSUFFICIENT_STALE_SAMPLE", values.measurement.geckoAudioClockStatus)
        assertEquals(-1L, values.measurement.geckoAudioClockUs)
        probe.accept(1, 100, 1, 2, 5000)
        values = probe.sample(engineValues(0), 5001, 1)
        assertEquals(1L, values.measurement.geckoAudioClockAgeMs)
        assertEquals("AVAILABLE", values.measurement.geckoAudioClockStatus)
    }

    @Test fun staleRuntimeOrTickCannotExposeAnAvailableEngineClock() {
        for (mode in listOf("stale", "runtime", "tick")) {
            val probe = GeckoProbeObservation()
            probe.accept(1, 100, 1, 1, 0)
            if (mode == "tick") probe.accept(1, 100, 1, 1, 1)
            val result = probe.sample(engineValues(), if (mode == "stale") 5001 else 1,
                if (mode == "runtime") 2 else 1)
            assertEquals("INSUFFICIENT_PROBE_INVALID", result.measurement.geckoAudioClockStatus)
            assertEquals(-1L, result.measurement.geckoPairedVideoPtsUs)
            assertEquals(-1L, result.measurement.geckoAudioClockUs)
            assertTrue(result.measurement.avSyncAssessment.startsWith("UNKNOWN"))
        }
    }

    @Test fun expiredProducerSamplesKeepKnownAgeAndExportNoUsablePair() {
        val outputs = java.io.File("build/outputs/schema17-wire-fixtures").also { it.mkdirs() }
        for ((name, now, runtime) in listOf(Triple("stale", 4901L, 1L), Triple("probe-invalid", 1000L, 2L))) {
            val probe = GeckoProbeObservation()
            probe.accept(1, 100, 1, 1, 0)
            val result = probe.sample(engineValues(), now, runtime)
            assertEquals(now + 100, result.measurement.geckoAudioClockAgeMs)
            assertNotEquals("AVAILABLE", result.measurement.geckoAudioClockStatus)
            assertEquals(-1L, result.measurement.geckoAudioClockUs)
            assertEquals(-1L, result.measurement.geckoPairedVideoPtsUs)
            val json = DiagnosticLogEntry(DiagnosticEntryKind.SAMPLE, now, result).toJson()
            java.io.File(outputs, "$name.json").writeText(json.toString(), Charsets.UTF_8)
        }
    }

    @Test fun receiptAgeAdditionSaturatesWithinJavascriptIntegerRange() {
        val probe = GeckoProbeObservation()
        probe.accept(1, 100, 1, 1, 0)
        val result = probe.sample(engineValues(DiagnosticPlaybackValues.MAX_WEB_INTEGER), 10, 1)
        assertEquals(DiagnosticPlaybackValues.MAX_WEB_INTEGER, result.measurement.geckoAudioClockAgeMs)
        assertEquals("INSUFFICIENT_STALE_SAMPLE", result.measurement.geckoAudioClockStatus)
    }

    @Test fun shortSuspensionAndEventsCannotResurrectOldAvailableClock() {
        val probe = GeckoProbeObservation()
        probe.accept(1, 100, 1, 1, 0)
        val periodic = probe.sample(engineValues(), 1000, 1)
        probe.invalidate()
        val background = probe.sample(periodic.copy(mirrorStage = "NATIVE_PAGE_PROGRESS"), 1001, 1)
        assertEquals("INSUFFICIENT_SUSPENDED", background.measurement.probeObservationStatus)
        assertEquals("INSUFFICIENT_PROBE_INVALID", background.measurement.geckoAudioClockStatus)
        assertEquals("NATIVE_PAGE_PROGRESS", background.mirrorStage)
        val foreground = probe.sample(background, 1002, 1)
        assertEquals("INSUFFICIENT_SUSPENDED", foreground.measurement.probeObservationStatus)
        assertEquals(-1L, foreground.measurement.geckoAudioClockUs)
        assertFalse(probe.accept(1, 100, 1, 1, 1003))
        assertEquals("INSUFFICIENT_SUSPENDED", probe.sample(foreground, 1003, 1).measurement.probeObservationStatus)
        probe.accept(1, 100, 1, 2, 1004)
        probe.recordMeasurementAtReceipt(engineValues(0).measurement)
        assertEquals("AVAILABLE", probe.sample(foreground, 1005, 1).measurement.geckoAudioClockStatus)
    }

    @Test fun newReceiptIsRecordedBeforeAnEventWithOldSharedValues() {
        val probe = GeckoProbeObservation()
        probe.accept(1, 100, 1, 1, 0)
        val old = probe.sample(engineValues(100), 1000, 1)
        probe.accept(1, 100, 1, 2, 1001)
        val latest = engineValues(0).measurement.copy(geckoAudioClockUs = 200, geckoAudioMinusVideoUs = 99)
        probe.recordMeasurementAtReceipt(latest)
        val event = probe.sample(old.copy(mirrorStage = "NATIVE_PAGE_PROGRESS"), 1002, 1)
        assertEquals(1L, event.measurement.geckoAudioClockAgeMs)
        assertEquals(200L, event.measurement.geckoAudioClockUs)
        assertEquals("NATIVE_PAGE_PROGRESS", event.mirrorStage)
        assertEquals(101L, event.measurement.geckoPairedVideoPtsUs)
    }

}
