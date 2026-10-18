package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTrackDiagnosticsTest {
    @Test
    fun `summary exposes only aggregate selection support and controlled codec families`() {
        val result = summarizeAudioTracks(
            listOf(
                AudioTrackObservationInput(
                    supported = true,
                    selected = false,
                    ffmpegSupported = true,
                    codecFamily = AudioCodecFamily.MPEG_AUDIO,
                ),
                AudioTrackObservationInput(
                    supported = false,
                    selected = false,
                    ffmpegSupported = false,
                    codecFamily = AudioCodecFamily.MPEG_AUDIO,
                ),
            ),
        )

        assertEquals(2, result.total)
        assertEquals(1, result.supported)
        assertEquals(0, result.selected)
        assertEquals(1, result.ffmpegSupported)
        assertEquals(listOf(AudioCodecFamily.MPEG_AUDIO), result.codecFamilies)
    }
}
