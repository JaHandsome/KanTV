package com.kantv.app.playback

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GeckoAudioMeasurementTest {
    private fun state() = JSONObject().apply {
        put("geckoAudioClockStatus", "AVAILABLE")
        put("geckoAudioClockUs", 100)
        put("geckoAudioClockAgeMs", 0)
        put("geckoAudioClockCallDurationMs", 1)
        put("geckoPairedVideoPtsUs", 101)
        put("geckoAudioMinusVideoUs", -1)
        put("geckoAvPairSpanMs", 40)
    }

    @Test fun parserPreservesSignedMinusOneAndKeepsMirrorInapplicable() {
        val evidence = geckoMeasurementEvidence(state(), true, true)
        assertEquals("AVAILABLE", evidence.geckoAudioClockStatus)
        assertEquals(-1L, evidence.geckoAudioMinusVideoUs)
        assertEquals("UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED", evidence.avSyncAssessment)
        assertEquals("NOT_APPLICABLE", geckoMeasurementEvidence(state(), true, false).geckoAudioClockStatus)
    }

    @Test fun missingMalformedOrMismatchedPairsFailClosed() {
        for (field in listOf("geckoAudioClockUs", "geckoAudioClockAgeMs", "geckoAudioClockCallDurationMs",
            "geckoPairedVideoPtsUs", "geckoAudioMinusVideoUs", "geckoAvPairSpanMs")) {
            val missing = state().apply { remove(field) }
            assertEquals("INSUFFICIENT_INVALID_CLOCK", geckoMeasurementEvidence(missing, true, true).geckoAudioClockStatus)
        }
        for (value in listOf(-1.2, "-1", true, -9_007_199_254_740_992L, 0)) {
            val malformed = state().apply { put("geckoAudioMinusVideoUs", value) }
            assertEquals("INSUFFICIENT_INVALID_CLOCK", geckoMeasurementEvidence(malformed, true, true).geckoAudioClockStatus)
        }
        assertEquals("INSUFFICIENT_INVALID_CLOCK", geckoMeasurementEvidence(
            state().apply { put("geckoAvPairSpanMs", 101) }, true, true).geckoAudioClockStatus)
    }

    @Test fun fixedStatusesSurviveWithoutExposingAbsentOrPrivateFields() {
        val input = JSONObject().apply {
            put("geckoAudioClockStatus", "INSUFFICIENT_UNSUPPORTED")
            put("url", "https://private.invalid/token")
        }
        val evidence = geckoMeasurementEvidence(input, true, true)
        assertEquals("INSUFFICIENT_UNSUPPORTED", evidence.geckoAudioClockStatus)
        assertEquals(-1L, evidence.geckoAudioClockUs)
        assertEquals("INSUFFICIENT_NO_SAMPLE", geckoMeasurementEvidence(
            input.apply { put("geckoAudioClockStatus", "HEALTHY") }, true, true).geckoAudioClockStatus)
    }
}
