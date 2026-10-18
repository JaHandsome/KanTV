package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class GeckoVisualOutputSignalTest {
    @Test
    fun emptySampleIsNotAcceptedAsVisibleOutput() {
        assertEquals(
            GeckoVisualOutputSignal.NO_SAMPLE,
            GeckoVisualOutputClassifier.classify(IntArray(0)),
        )
    }

    @Test
    fun exactNeutralGreySurfaceIsRejected() {
        assertEquals(
            GeckoVisualOutputSignal.FLAT_NEUTRAL,
            GeckoVisualOutputClassifier.classify(IntArray(144) { 0xff808080.toInt() }),
        )
    }

    @Test
    fun blackFirstPaintCoverIsNotMistakenForVideo() {
        assertEquals(
            GeckoVisualOutputSignal.FLAT_OTHER,
            GeckoVisualOutputClassifier.classify(IntArray(144) { 0xff000000.toInt() }),
        )
    }

    @Test
    fun aFlatColouredDecoderPlaceholderIsNotMistakenForVideo() {
        assertEquals(
            GeckoVisualOutputSignal.FLAT_OTHER,
            GeckoVisualOutputClassifier.classify(IntArray(144) { 0xff2040c0.toInt() }),
        )
    }

    @Test
    fun ordinaryPictureDetailIsAccepted() {
        val samples = IntArray(144) { index ->
            if (index % 3 == 0) 0xff102030.toInt() else 0xffd0c080.toInt()
        }
        assertEquals(
            GeckoVisualOutputSignal.VISIBLE_DETAIL,
            GeckoVisualOutputClassifier.classify(samples),
        )
    }
}
