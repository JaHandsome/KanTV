package com.kantv.app.playback

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

internal enum class AudioCodecFamily {
    AAC,
    MPEG_AUDIO,
    AC3,
    E_AC3,
    OPUS,
    VORBIS,
    OTHER,
    UNKNOWN,
}

internal data class AudioTrackObservationInput(
    val supported: Boolean,
    val selected: Boolean,
    val ffmpegSupported: Boolean,
    val codecFamily: AudioCodecFamily,
)

internal data class AudioTrackObservation(
    val total: Int,
    val supported: Int,
    val selected: Int,
    val ffmpegSupported: Int,
    val codecFamilies: List<AudioCodecFamily>,
)

internal fun summarizeAudioTracks(inputs: List<AudioTrackObservationInput>): AudioTrackObservation =
    AudioTrackObservation(
        total = inputs.size,
        supported = inputs.count(AudioTrackObservationInput::supported),
        selected = inputs.count(AudioTrackObservationInput::selected),
        ffmpegSupported = inputs.count(AudioTrackObservationInput::ffmpegSupported),
        codecFamilies = inputs.map(AudioTrackObservationInput::codecFamily).distinct().sortedBy { it.ordinal },
    )

internal fun audioCodecFamily(format: Format): AudioCodecFamily = when (format.sampleMimeType) {
    MimeTypes.AUDIO_AAC -> AudioCodecFamily.AAC
    MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2 -> AudioCodecFamily.MPEG_AUDIO
    MimeTypes.AUDIO_AC3 -> AudioCodecFamily.AC3
    MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> AudioCodecFamily.E_AC3
    MimeTypes.AUDIO_OPUS -> AudioCodecFamily.OPUS
    MimeTypes.AUDIO_VORBIS -> AudioCodecFamily.VORBIS
    null -> AudioCodecFamily.UNKNOWN
    else -> AudioCodecFamily.OTHER
}
