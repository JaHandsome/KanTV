package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamUrlClassifierTest {
    @Test fun m3u8WithPathSuffixIsHls() {
        assertEquals(
            SniffedStreamKind.HLS,
            StreamUrlClassifier.classify("https://cdn.example.com/live/index.m3u8", null, false),
        )
    }

    @Test fun m3u8WithQueryIsHls() {
        assertEquals(
            SniffedStreamKind.HLS,
            StreamUrlClassifier.classify("https://p.example.com/__c/ott_C_h264/index.m3u8?hdnea=ip~exp", null, false),
        )
    }

    @Test fun contentTypeAloneIdentifiesHls() {
        assertEquals(
            SniffedStreamKind.HLS,
            StreamUrlClassifier.classify("https://api.example.com/getlive?id=1", "application/vnd.apple.mpegurl", false),
        )
    }

    @Test fun mpdSuffixIsDash() {
        assertEquals(
            SniffedStreamKind.DASH,
            StreamUrlClassifier.classify("https://drm.example.com/stream/index.mpd?token=x", null, false),
        )
    }

    @Test fun segmentsAreNotCandidates() {
        assertNull(StreamUrlClassifier.classify("https://cdn.example.com/live/seg0.ts", null, false))
        assertNull(StreamUrlClassifier.classify("https://cdn.example.com/live/init.mp4", null, false))
        assertNull(StreamUrlClassifier.classify("https://cdn.example.com/live/chunk-1.m4s", null, false))
    }

    @Test fun mainFrameHtmlIsNeverCandidate() {
        assertNull(StreamUrlClassifier.classify("https://fixture-4.example.invalid/live/cctv1/index.m3u8", null, true))
    }

    @Test fun nonHttpSchemesAndGarbageAreRejected() {
        assertNull(StreamUrlClassifier.classify("file:///data/index.m3u8", null, false))
        assertNull(StreamUrlClassifier.classify("about:blank", null, false))
        assertNull(StreamUrlClassifier.classify("not a url .m3u8", null, false))
    }

    @Test fun flvIsNotACandidateBecauseMedia3CannotPlayIt() {
        assertNull(StreamUrlClassifier.classify("https://live.example.com/stream.flv", null, false))
    }

    @Test fun plainJsonAndImagesAreNotCandidates() {
        assertNull(StreamUrlClassifier.classify("https://api.example.com/stats?id=1", "application/json", false))
        assertNull(StreamUrlClassifier.classify("https://cdn.example.com/logo.png", null, false))
    }
}
