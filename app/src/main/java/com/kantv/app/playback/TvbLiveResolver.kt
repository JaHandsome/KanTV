package com.kantv.app.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

interface WebLiveResolver {
    fun supports(uri: String): Boolean
    suspend fun resolve(request: PlaybackRequest): PlaybackRequest
}

class WebLiveResolutionException(val kind: PlaybackErrorKind) : Exception("WEB_LIVE_RESOLUTION_FAILED")

/** Resolve only the official live-page route. Signed media URLs stay in memory. */
class TvbLiveResolver : WebLiveResolver {
    override fun supports(uri: String): Boolean = channelId(uri) != null

    override suspend fun resolve(request: PlaybackRequest): PlaybackRequest = withContext(Dispatchers.IO) {
        val id = channelId(request.uri) ?: return@withContext request
        val connection = URL("https://news.tvb.com/app/public/live/stream/$id")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Referer", "https://news.tvb.com/")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            val status = connection.responseCode
            if (status == 401 || status == 403) throw WebLiveResolutionException(PlaybackErrorKind.AUTHORIZATION)
            if (status !in 200..299) throw WebLiveResolutionException(PlaybackErrorKind.TEMPORARY_NETWORK)
            val bytes = connection.inputStream.use { it.readBytesLimited(1_048_576) }
            val response = JSONObject(bytes.toString(Charsets.UTF_8))
            val data = response.optJSONObject("data")
                ?: throw WebLiveResolutionException(PlaybackErrorKind.TEMPORARY_NETWORK)
            if (!response.optBoolean("success", false) || data.optBoolean("geo_blocked", false)) {
                throw WebLiveResolutionException(PlaybackErrorKind.AUTHORIZATION)
            }
            val stream = data.optString("stream_url")
            val uri = runCatching { URI(stream) }.getOrNull()
            if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank() ||
                !data.optString("protocol").equals("hls", ignoreCase = true)) {
                throw WebLiveResolutionException(PlaybackErrorKind.UNSUPPORTED_FORMAT)
            }
            // 视频变体门控（2026-09-12，fail-closed）：该解析 API 在部分网络出口下只返回
            // 纯音频变体（CODECS 仅 mp4a，无 avc/hev1）——直接播会「黑屏有声」。主清单
            // 拉取失败或存在 STREAM-INF 但全部无视频编解码时，一律判定不可直连，
            // 交回 WebView 兜底。绝不放行未经视频验证的流。
            // 判定逻辑抽为共享 [ManifestVideoVariantGate]：网页嗅探候选走同一把尺子。
            val masterVerified = runCatching {
                val masterConnection = URL(stream).openConnection() as HttpURLConnection
                try {
                    masterConnection.connectTimeout = 8_000
                    masterConnection.readTimeout = 8_000
                    masterConnection.setRequestProperty("User-Agent", "Mozilla/5.0")
                    if (masterConnection.responseCode !in 200..299) throw IllegalStateException("master http")
                    val text = masterConnection.inputStream.use {
                        it.readBytesLimited(262_144)
                    }.toString(Charsets.UTF_8)
                    ManifestVideoVariantGate.isPlayableVideoManifest(text)
                } finally {
                    masterConnection.disconnect()
                }
            }.getOrDefault(false)
            if (!masterVerified) {
                throw WebLiveResolutionException(PlaybackErrorKind.AUTHORIZATION)
            }
            tvbResolvedHlsRequest(request, stream)
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > limit) throw WebLiveResolutionException(PlaybackErrorKind.TEMPORARY_NETWORK)
            output.write(buffer, 0, count)
        }
    }

    companion object {
        fun channelId(value: String): String? {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (uri.scheme != "https" || !uri.host.equals("news.tvb.com", ignoreCase = true) ||
                uri.userInfo != null || uri.port !in setOf(-1, 443)) return null
            return Regex("^/(?:tc|sc|en)/live/([A-Za-z0-9_-]{1,32})/?$")
                .matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
        }

        fun channelName(id: String): String = when (id) {
            "F" -> "TVB财经"
            "C" -> "TVB无线新闻"
            "T" -> "TVB新闻直播"
            else -> "TVB直播 $id"
        }
    }
}

/** Pure construction seam: resolved public HLS remains a Media3-first candidate. */
internal fun tvbResolvedHlsRequest(request: PlaybackRequest, stream: String): PlaybackRequest =
    request.copy(
        uri = stream,
        mimeType = "application/x-mpegURL",
        // header_json contains upstream response metadata, not request headers.
        requestHeaders = mapOf("Referer" to "https://news.tvb.com/", "User-Agent" to "Mozilla/5.0"),
        preferredKernel = null,
    )
