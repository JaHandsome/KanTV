package com.kantv.app.playback

/** 嗅探捕获的一个候选（url + 分类 + 捕获时请求头）。 */
data class SniffedCandidate(
    val url: String,
    val kind: SniffedStreamKind,
    val headers: Map<String, String>,
)

/** 验证通过的嗅探流。headers 只含白名单键（Referer/User-Agent）。 */
data class VerifiedStream(
    val uri: String,
    val mimeType: String,
    val headers: Map<String, String>,
)

/**
 * 嗅探候选验证门（fail-closed）：
 * 拉清单 -> [ManifestVideoVariantGate] 判视频变体 -> 任何异常/空响应/门控不过 -> null。
 *
 * [fetch] 注入便于单测；生产实现复用 TvbLiveResolver 的超时纪律
 * （HttpURLConnection + VERIFY_TIMEOUT_MS + 读上限）。
 */
class SniffedStreamGate(
    private val fetch: (url: String, headers: Map<String, String>) -> String?,
) {
    fun verify(candidate: SniffedCandidate): VerifiedStream? {
        val headers = whitelistedHeaders(candidate.headers)
        val text = runCatching { fetch(candidate.url, headers) }.getOrNull() ?: return null
        if (!ManifestVideoVariantGate.isPlayableVideoManifest(text, candidate.kind)) return null
        return VerifiedStream(
            uri = candidate.url,
            mimeType = StreamUrlClassifier.mimeTypeFor(candidate.kind),
            headers = headers,
        )
    }

    companion object {
        private val HEADER_WHITELIST = setOf("referer", "user-agent")

        /** 只保留 Referer/User-Agent（大小写不敏感）；其余头（Cookie 等）不外传。 */
        fun whitelistedHeaders(source: Map<String, String>?): Map<String, String> =
            source.orEmpty().filterKeys { it.lowercase() in HEADER_WHITELIST }
    }
}
