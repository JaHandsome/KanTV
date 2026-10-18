package com.kantv.app.playback

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class TrackChoiceLabelTest {
    @Test fun unknownLanguageDoesNotBecomeMandarinFromCodec() {
        val label = trackChoiceLabel(PlaybackTrackInfo(PlaybackTrackKind.AUDIO, "ac3", null, null), 2)
        assertTrue(label.contains("音轨 2"))
        assertFalse(label.contains("未标明"))
        assertFalse(label.contains("普通话"))
    }
    @Test fun genericChineseDoesNotClaimADialect() {
        val label = trackChoiceLabel(PlaybackTrackInfo(PlaybackTrackKind.AUDIO, "mp2", null, "zh"), 1)
        assertTrue(label.contains("中文"))
        assertFalse(label.contains("未标明"))
        assertFalse(label.contains("普通话"))
    }

    @Test fun gatewayEngineCodesStayOutOfMenu() {
        // hidns 等网关自编代号：LANGUAGE="au1" + label="au1" + codec="mp4a.40.2"
        val label = trackChoiceLabel(PlaybackTrackInfo(PlaybackTrackKind.AUDIO, "mp4a.40.2", "audio/mp4a-latm", "au1"), 1)
        assertFalse(label.contains("au1"))
        assertTrue(label.contains("AAC"))
        assertTrue(label.contains("音轨 1"))
    }

    @Test fun opaqueCodecFallsBackToEmptyNotRawString() {
        val label = trackChoiceLabel(PlaybackTrackInfo(PlaybackTrackKind.AUDIO, "dvhe.05.06", null, null), 3)
        assertFalse(label.contains("dvhe.05.06"))
        assertTrue(label.contains("音轨 3"))
    }
}
