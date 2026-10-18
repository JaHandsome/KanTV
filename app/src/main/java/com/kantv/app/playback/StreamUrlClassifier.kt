package com.kantv.app.playback

/**
 * WebView 拦截请求的流候选分类（纯函数）。
 *
 * 依据（契约同 hub 投递侧）：Media3 只播 HLS 与 DASH——FLV 不支持
 * （iptv-nas hub 投递同样排除 flv），故 FLV 不作为嗅探候选，避免换轨后必败。
 * 仅凭 URL 路径后缀与已知 Content-Type 判定，不做任何网络访问。
 */
enum class SniffedStreamKind { HLS, DASH }

object StreamUrlClassifier {
    private val HLS_CONTENT_TYPES = setOf(
        "application/x-mpegurl",
        "application/vnd.apple.mpegurl",
    )

    /**
     * 判定一个 WebView 请求是否是可交 Media3 的流清单候选。
     * - 主帧 HTML 永不是候选；非 http/https 拒绝；
     * - .ts/.m4s/.mp4 等分片不是候选（要的是 manifest）；
     * - [contentType] 为 shouldInterceptRequest 已知值时参与判定（通常为 null，
     *   嗅探点拿不到响应类型，URL 是主判据）。
     */
    fun classify(url: String, contentType: String?, isMainFrame: Boolean): SniffedStreamKind? {
        if (isMainFrame) return null
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        if (uri.host.isNullOrBlank()) return null
        val path = (uri.path ?: "").lowercase()
        val type = (contentType ?: "").trim().lowercase()
        return when {
            path.endsWith(".m3u8") || type in HLS_CONTENT_TYPES -> SniffedStreamKind.HLS
            path.endsWith(".mpd") || type.contains("dash+xml") -> SniffedStreamKind.DASH
            else -> null
        }
    }

    fun mimeTypeFor(kind: SniffedStreamKind): String = when (kind) {
        SniffedStreamKind.HLS -> "application/x-mpegURL"
        SniffedStreamKind.DASH -> "application/dash+xml"
    }
}
