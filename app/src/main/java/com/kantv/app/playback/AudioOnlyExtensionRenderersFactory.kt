package com.kantv.app.playback

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.kantv.app.BuildConfig

/**
 * Keeps optional audio decoders enabled without admitting video renderers bundled in the same AAR.
 *
 * [DefaultRenderersFactory] passes one extension mode to both renderer families. The application
 * needs FFmpeg's audio decoder as a supplement after the platform audio renderer, but the bundled
 * experimental FFmpeg video renderer is not part of the supported video path.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class AudioOnlyExtensionRenderersFactory(
    context: Context,
    private val audioSinkObserver: AudioSinkObservationTarget = AudioSinkObservationTarget.None,
) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val modes = audioOnlyExtensionModes(extensionRendererMode)
        super.buildVideoRenderers(
            context,
            modes.video,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? = super.buildAudioSink(
        context,
        enableFloatOutput,
        enableAudioTrackPlaybackParams,
    )?.let {
        if (BuildConfig.KANTV_DIAGNOSTICS) ObservingAudioSink(it, audioSinkObserver) else it
    }
}

internal data class RendererExtensionModes(
    val video: Int,
    val audio: Int,
)

/** Pure policy seam used by tests; audio preserves the configured ordering while video is off. */
@OptIn(markerClass = [UnstableApi::class])
internal fun audioOnlyExtensionModes(configuredMode: Int): RendererExtensionModes =
    RendererExtensionModes(
        video = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
        audio = configuredMode,
    )
