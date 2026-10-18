package com.kantv.app.playback

/**
 * 直播清单的「视频变体」门控（fail-closed 纯函数）。
 *
 * 背景（2026-09-12 实证）：TVB 解析 API 在内地出口只返回纯音频主清单
 * （CODECS 仅 mp4a，无 avc/hev1）——直接播会「黑屏有声」。该判定最初写在
 * [TvbLiveResolver] 内，现抽出共享：网页嗅探候选（SniffedStreamGate）与
 * resolver 直连走同一把尺子，避免两处判定漂移。
 *
 * 判定（HLS）：
 * - 文本空白 -> false（什么都没拿到，不放行）；
 * - 无 #EXT-X-STREAM-INF（直接是媒体清单）-> true：清单层无法判断视频，
 *   保守放行由解码层（Media3 首帧超时）裁决；
 * - 有 STREAM-INF 但全部变体 CODECS 不含 avc/h264/hev1/hvc1 -> false（纯音频）。
 *
 * 判定（DASH/MPD）：
 * - 不含视频 AdaptationSet 线索（video/ 或 contentType="video"）-> false。
 */
object ManifestVideoVariantGate {
    private val HLS_VIDEO_CODEC =
        Regex("CODECS=\"[^\"]*(avc|h264|hev1|hvc1)", RegexOption.IGNORE_CASE)

    fun isPlayableVideoManifest(text: String, kind: SniffedStreamKind = SniffedStreamKind.HLS): Boolean {
        if (text.isBlank()) return false
        return when (kind) {
            SniffedStreamKind.HLS -> {
                val hasStreamInf = text.contains("#EXT-X-STREAM-INF")
                !hasStreamInf || HLS_VIDEO_CODEC.containsMatchIn(text)
            }
            SniffedStreamKind.DASH ->
                text.contains("video/") || text.contains("contentType=\"video\"")
        }
    }
}
