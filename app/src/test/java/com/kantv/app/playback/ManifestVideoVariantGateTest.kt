package com.kantv.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestVideoVariantGateTest {
    @Test fun blankManifestIsRejected() {
        assertFalse(ManifestVideoVariantGate.isPlayableVideoManifest(""))
        assertFalse(ManifestVideoVariantGate.isPlayableVideoManifest("   \n\t "))
    }

    @Test fun masterWithVideoCodecPasses() {
        val master = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=308332,CODECS=\"avc1.4d0020,mp4a.40.2\",RESOLUTION=1280x720\n" +
            "chunklist.m3u8\n"
        assertTrue(ManifestVideoVariantGate.isPlayableVideoManifest(master))
    }

    @Test fun masterWithHevcCodecPasses() {
        val master = "#EXT-X-STREAM-INF:CODECS=\"hvc1.2.4.L153.B0\",RESOLUTION=1920x1080\n720.m3u8\n"
        assertTrue(ManifestVideoVariantGate.isPlayableVideoManifest(master))
    }

    @Test fun audioOnlyMasterIsRejected() {
        // 2026-09-12 内地出口 TVB ott_C_h264 实测形态：仅 mp4a 变体
        val master = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS=\"mp4a.40.2\"\n3/a128001/index.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS=\"mp4a.40.2\"\n3/a128002/index.m3u8\n"
        assertFalse(ManifestVideoVariantGate.isPlayableVideoManifest(master))
    }

    @Test fun mediaPlaylistWithoutStreamInfIsPassedForDecoderJudgement() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\nseg0.ts\n"
        assertTrue(ManifestVideoVariantGate.isPlayableVideoManifest(media))
    }

    @Test fun dashManifestWithVideoAdaptationSetPasses() {
        val mpd = "<MPD><Period><AdaptationSet contentType=\"video\" mimeType=\"video/mp4\"/></Period></MPD>"
        assertTrue(ManifestVideoVariantGate.isPlayableVideoManifest(mpd, SniffedStreamKind.DASH))
    }

    @Test fun dashAudioOnlyManifestIsRejected() {
        val mpd = "<MPD><Period><AdaptationSet contentType=\"audio\" mimeType=\"audio/mp4\"/></Period></MPD>"
        assertFalse(ManifestVideoVariantGate.isPlayableVideoManifest(mpd, SniffedStreamKind.DASH))
    }
}
