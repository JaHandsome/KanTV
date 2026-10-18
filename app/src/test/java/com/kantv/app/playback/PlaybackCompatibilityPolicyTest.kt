package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackCompatibilityPolicyTest {
    @Test fun everyChannelUsesTheSameUngatedCompatibilityDefault() {
        val compatibility = PlaybackCompatibilityPolicy.forChannel("station-alpha", "channel-a")

        assertNull(compatibility.preferredKernel)
    }

    @Test fun otherChannelsKeepAdaptiveDefaults() {
        val compatibility = PlaybackCompatibilityPolicy.forChannel("cctv-5plus", "hashed-channel-id")

        assertNull(compatibility.preferredKernel)
    }
}
