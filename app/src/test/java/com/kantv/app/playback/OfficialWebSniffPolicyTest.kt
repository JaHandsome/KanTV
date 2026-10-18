package com.kantv.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialWebSniffPolicyTest {
    @Test fun windowExpiryStopsSniffing() {
        assertTrue(OfficialWebSniffPolicy.shouldStopSniffing(elapsedMs = OfficialWebSniffPolicy.SNIFF_WINDOW_MS, verificationsStarted = 0))
        assertTrue(OfficialWebSniffPolicy.shouldStopSniffing(elapsedMs = OfficialWebSniffPolicy.SNIFF_WINDOW_MS + 1, verificationsStarted = 0))
        assertFalse(OfficialWebSniffPolicy.shouldStopSniffing(elapsedMs = OfficialWebSniffPolicy.SNIFF_WINDOW_MS - 1, verificationsStarted = 0))
    }

    @Test fun verificationBudgetStopsSniffing() {
        assertTrue(OfficialWebSniffPolicy.shouldStopSniffing(elapsedMs = 1_000, verificationsStarted = OfficialWebSniffPolicy.MAX_VERIFICATIONS))
        assertFalse(OfficialWebSniffPolicy.shouldStopSniffing(elapsedMs = 1_000, verificationsStarted = OfficialWebSniffPolicy.MAX_VERIFICATIONS - 1))
    }
}
