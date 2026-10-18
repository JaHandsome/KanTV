package com.kantv.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayedFrameProgressGateTest {
    @Test
    fun missingStatsAndUnchangedPictureCountAreNotVideoProgress() {
        val gate = DisplayedFrameProgressGate()

        assertFalse(gate.observe(null))
        assertTrue(gate.observe(12))
        assertFalse(gate.observe(12))
        assertTrue(gate.observe(13))
    }

    @Test
    fun resetAllowsANewPlaybackSessionToEstablishItsBaseline() {
        val gate = DisplayedFrameProgressGate()
        assertTrue(gate.observe(3))
        assertFalse(gate.observe(3))

        gate.reset()

        assertTrue(gate.observe(1))
    }

    @Test
    fun decodedFrameCountCanProveProgressWhenHardwareDisplayCountIsUnavailable() {
        val gate = DisplayedFrameProgressGate()

        assertTrue(gate.observe(null, 20))
        assertFalse(gate.observe(null, 20))
        assertTrue(gate.observe(null, 21))
    }

    @Test
    fun decodedFramesDoNotHideAFrozenDisplayedPictureCount() {
        val gate = DisplayedFrameProgressGate()

        assertTrue(gate.observe(1, 20))
        assertFalse(gate.observe(1, 40))
        assertTrue(gate.observe(2, 41))
    }
}
