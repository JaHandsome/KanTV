package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackDiagnosticsTest {
    @Test
    fun cachedPlaylistStrategyIsAppendedWithoutChangingExistingOrdinals() {
        assertEquals(0, StreamDeliveryStrategy.DIRECT.ordinal)
        assertEquals(1, StreamDeliveryStrategy.HLS_PARALLEL_PREFETCH.ordinal)
        assertEquals(2, StreamDeliveryStrategy.HLS_CACHED_PLAYLIST.ordinal)
    }

    @Test
    fun videoProgressCountNeverPretendsToBeDisplayedPictures() {
        assertEquals(-1L, presentedFramesForDiagnostics(null))
        assertEquals(-1L, presentedFramesForDiagnostics(
            PlaybackTechnicalUpdate.AudioTiming(0, -1, 0, 0, 0, displayedPictures = -1),
        ))
        assertEquals(7L, presentedFramesForDiagnostics(
            PlaybackTechnicalUpdate.AudioTiming(0, -1, 0, 0, 0, displayedPictures = 7),
        ))
    }
    @Test
    fun audioTimingIsSessionIsolatedAndClearedOnKernelSwitch() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginSource(7L, "channel", 1, 1)
        val timing = PlaybackTechnicalUpdate.AudioTiming(12_000, 147, -190_000, 500, 0)
        diagnostics.recordTechnical(6L, timing)
        assertNull(diagnostics.currentSnapshot()?.audioTiming)
        diagnostics.recordTechnical(7L, timing)
        assertEquals(timing, diagnostics.currentSnapshot()?.audioTiming)
        diagnostics.recordTechnical(7L,
            PlaybackTechnicalUpdate.KernelSelected(PlaybackKernel.MEDIA3, KernelSelectionReason.OTHER_ERROR))
        assertNull(diagnostics.currentSnapshot()?.audioTiming)
    }

    @Test
    fun currentSnapshotCollectsTechnicalPlaybackFactsWithoutSourceAddress() {
        var now = 100L
        val diagnostics = PlaybackDiagnostics(elapsedRealtimeMs = { now })
        diagnostics.beginSource(7L, "channel", 2, 3)
        now = 120L
        diagnostics.recordTechnical(
            7L,
            PlaybackTechnicalUpdate.KernelSelected(
                PlaybackKernel.MEDIA3,
                KernelSelectionReason.DEFAULT,
            ),
        )
        diagnostics.recordTechnical(
            7L,
            PlaybackTechnicalUpdate.TracksDetected(
                containerMimeType = "application/x-mpegURL",
                containerInfoSource = ContainerInfoSource.PLAYER_DETECTED,
                tracks = listOf(
                    PlaybackTrackInfo(
                        kind = PlaybackTrackKind.VIDEO,
                        codec = "avc1",
                        mimeType = "video/avc",
                        language = null,
                        width = 1920,
                        height = 1080,
                        selected = true,
                    ),
                ),
            ),
        )
        diagnostics.recordTechnical(7L, PlaybackTechnicalUpdate.VideoDecoderSelected("decoder.avc"))
        diagnostics.recordTechnical(
            7L,
            PlaybackTechnicalUpdate.StreamDeliverySelected(
                StreamDeliveryStrategy.HLS_PARALLEL_PREFETCH,
                StreamDeliveryReason.PREFETCH_ENABLED,
            ),
        )
        diagnostics.recordTechnical(7L, PlaybackTechnicalUpdate.DroppedVideoFrames(3))
        now = 245L
        diagnostics.record(7L, "channel", DiagnosticType.FIRST_FRAME)

        val snapshot = requireNotNull(diagnostics.currentSnapshot())
        assertEquals(PlaybackKernel.MEDIA3, snapshot.kernel)
        assertEquals(125L, snapshot.firstFrameMs)
        assertEquals("decoder.avc", snapshot.videoDecoderName)
        assertEquals(StreamDeliveryStrategy.HLS_PARALLEL_PREFETCH, snapshot.streamDeliveryStrategy)
        assertEquals(StreamDeliveryReason.PREFETCH_ENABLED, snapshot.streamDeliveryReason)
        assertEquals(ContainerInfoSource.PLAYER_DETECTED, snapshot.containerInfoSource)
        assertEquals(3, snapshot.droppedVideoFrames)
        assertEquals(1, snapshot.tracks.size)
        assertEquals(2, snapshot.sourceIndex)
        assertEquals(3, snapshot.sourceCount)
    }

    @Test
    fun kernelReselectionResetsMetricsForTheNewAttempt() {
        val diagnostics = PlaybackDiagnostics(elapsedRealtimeMs = { 0L })
        diagnostics.beginSource(7L, "channel", 1, 1)
        diagnostics.recordTechnical(
            7L,
            PlaybackTechnicalUpdate.KernelSelected(PlaybackKernel.MEDIA3, KernelSelectionReason.DEFAULT),
        )
        diagnostics.recordTechnical(7L, PlaybackTechnicalUpdate.DroppedVideoFrames(5))
        diagnostics.record(7L, "channel", DiagnosticType.FIRST_FRAME)

        diagnostics.recordTechnical(
            7L,
            PlaybackTechnicalUpdate.KernelSelected(PlaybackKernel.MEDIA3, KernelSelectionReason.MANUAL_REOPEN),
        )

        val snapshot = requireNotNull(diagnostics.currentSnapshot())
        assertEquals(PlaybackKernel.MEDIA3, snapshot.kernel)
        assertEquals(KernelSelectionReason.MANUAL_REOPEN, snapshot.kernelReason)
        assertEquals(0, snapshot.droppedVideoFrames)
        assertNull(snapshot.firstFrameMs)
        assertNull(snapshot.videoDecoderName)
    }

    @Test
    fun countsPresentedProgressAndKeepsDetailedQueueMetrics() {
        val diagnostics = PlaybackDiagnostics(elapsedRealtimeMs = { 0L })
        diagnostics.beginSource(7L, "channel", 1, 1)
        diagnostics.record(7L, "channel", DiagnosticType.FIRST_FRAME)
        diagnostics.recordVideoProgress(7L, 20_000_000)
        diagnostics.recordVideoProgress(7L, 20_040_000)
        val timing = PlaybackTechnicalUpdate.AudioTiming(
            clockMs = 20_000,
            selectedTrackId = 147,
            configuredDelayUs = 0,
            playedBuffers = 998,
            lostBuffers = 0,
            decodedVideo = 24,
            displayedPictures = 21,
            readBytes = 3_000_000,
            demuxReadBytes = 2_900_000,
            inputKbps = 9_300,
            demuxKbps = 9_200,
            demuxCorrupted = 0,
            demuxDiscontinuity = 0,
            decodedAudio = 1_000,
        )
        diagnostics.recordTechnical(7L, timing)

        val snapshot = requireNotNull(diagnostics.currentSnapshot())
        assertEquals(3L, snapshot.videoProgressCount)
        assertEquals(20_040_000L, snapshot.lastVideoPtsUs)
        assertEquals(21, snapshot.audioTiming?.displayedPictures)
        assertEquals(2_900_000L, snapshot.audioTiming?.demuxReadBytes)
        assertEquals(1_000, snapshot.audioTiming?.decodedAudio)
    }

    @Test
    fun collectsMedia3AudioDecoderFormatAndUnderruns() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginSource(7L, "channel", 1, 1)
        diagnostics.recordTechnical(7L, PlaybackTechnicalUpdate.AudioDecoderSelected("c2.android.mp3.decoder"))
        diagnostics.recordTechnical(7L, PlaybackTechnicalUpdate.AudioFormatChanged("audio/mpeg", "mp2", 2, 48_000))
        diagnostics.recordTechnical(7L, PlaybackTechnicalUpdate.AudioUnderrun())
        diagnostics.recordTechnical(7L, PlaybackTechnicalUpdate.AudioUnderrun(2))

        val snapshot = requireNotNull(diagnostics.currentSnapshot())
        assertEquals("c2.android.mp3.decoder", snapshot.audioDecoderName)
        assertEquals("audio/mpeg", snapshot.audioMimeType)
        assertEquals("mp2", snapshot.audioCodec)
        assertEquals(2, snapshot.audioChannelCount)
        assertEquals(48_000, snapshot.audioSampleRate)
        assertEquals(3, snapshot.audioUnderrunCount)
    }
}
