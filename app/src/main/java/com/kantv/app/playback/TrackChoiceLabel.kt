package com.kantv.app.playback

/** Do not infer language or dialect from codec, order, or channel name. */
fun trackChoiceLabel(track: PlaybackTrackInfo, ordinal: Int): String {
    val language = when (track.language?.lowercase()) {
        "yue", "cantonese" -> "粤语"
        "cmn", "mandarin" -> "普通话"
        "zh", "zho", "chi", "chinese" -> "中文"
        "en", "eng", "english" -> "英语"
        // 网关自编的语言/音轨代号（如 hidns 的 au1/au2）与无法识别的代码：无语言语义
        // 不外显，由「音轨 N」序号承担区分职责（label 过滤见下方 isOpaqueEngineCode）。
        else -> null
    }
    return listOfNotNull(
        "${if (track.kind == PlaybackTrackKind.SUBTITLE) "字幕" else "音轨"} $ordinal",
        language,
        track.label?.takeIf {
            it.isNotBlank() && !it.contains("://") && !it.startsWith("Track ") &&
                !it.equals(track.language, ignoreCase = true) && !isOpaqueEngineCode(it)
        },
        friendlyAudioCodec(track),
        track.channelCount?.let { if (it == 2) "双声道" else "${it}声道" },
    ).joinToString(" · ")
}

/** 上游清单里的无语义代号：语言占位（au1）、裸编码串（mp4a.40.2）等。 */
private fun isOpaqueEngineCode(value: String?): Boolean {
    val v = value?.trim()?.lowercase() ?: return false
    if (v.isEmpty()) return false
    if (Regex("^au?\\d+$").matches(v)) return true            // au1 / a2
    if (Regex("^[a-z]{2,4}\\.\\d+([.-]\\d+)*$").matches(v)) return true  // mp4a.40.2 / dvhe.05.06
    return false
}

/** 常见音频编码的用户可读名；识别不了才退回原始 codec/mimeType。 */
private fun friendlyAudioCodec(track: PlaybackTrackInfo): String? {
    val raw = (track.codec ?: track.mimeType ?: "").lowercase()
    if (raw.isEmpty()) return null
    val friendly = when {
        "mp4a.40" == raw || raw.startsWith("mp4a.40") || raw.contains("mp4a-latm") || raw == "aac" -> "AAC"
        "ac-3" == raw || raw.contains("ac3") || raw.startsWith("a52") -> "AC3"
        "ec-3" == raw || raw.contains("ec3") -> "E-AC3"
        "opus" == raw -> "Opus"
        "flac" == raw -> "FLAC"
        raw.startsWith("mpa") || raw.contains("mpeg audio") || raw in setOf("mp2", "mp3") ->
            if ("3" in raw) "MP3" else "MP2"
        else -> null
    }
    return friendly ?: track.codec?.takeIf { !isOpaqueEngineCode(it) }
        ?: track.mimeType?.takeIf { !isOpaqueEngineCode(it) }
}
