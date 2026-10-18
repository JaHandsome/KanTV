package com.kantv.app.catalog

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

/** 一次 GET 的原始结果。把传输层抽出来，便于在 JVM 单测里断言 304 / 错误码 / 非法 JSON 路径。 */
data class HubHttpResponse(
    val code: Int,
    val etag: String? = null,
    val contentType: String? = null,
    val body: ByteArray? = null,
)

interface HubHttpTransport {
    fun get(uri: String, ifNoneMatch: String?): HubHttpResponse
}

/** 默认传输层：直连局域网 HTTP，不跟随重定向，不使用代理与缓存。 */
class HttpUrlConnectionHubTransport(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) : HubHttpTransport {
    override fun get(uri: String, ifNoneMatch: String?): HubHttpResponse {
        val connection = (URI(uri).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            ifNoneMatch?.let { setRequestProperty("If-None-Match", it) }
        }
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return HubHttpResponse(code, connection.getHeaderField("ETag"), connection.contentType)
            }
            val length = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (length != null && length > MAX_PAYLOAD_BYTES) {
                return HubHttpResponse(code, connection.getHeaderField("ETag"), connection.contentType)
            }
            val body = if (code in 200..299) {
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_PAYLOAD_BYTES) break
                        output.write(buffer, 0, read)
                    }
                }
                output.toByteArray()
            } else {
                null
            }
            return HubHttpResponse(code, connection.getHeaderField("ETag"), connection.contentType, body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
    }
}

sealed interface HubFetchOutcome {
    data class Fetched(
        val payload: String,
        val subscription: HubSubscription,
        val etag: String?,
        val skippedChannels: Int,
    ) : HubFetchOutcome

    /** 契约 §3：304 表示无变化，调用方**不得重建目录**。 */
    data object NotModified : HubFetchOutcome

    /** [status] 只取固定枚举，绝不包含异常文本或 URL。 */
    data class Failed(val status: String) : HubFetchOutcome
}

/**
 * 契约 §3 的投递通道客户端（只读，无需鉴权）。
 *
 * 责任边界：只做「取回 + 解析 + 归类失败」。是否应用、何时应用一律交给
 * [HubRefreshPolicy]；本类不写数据库、不碰播放。
 */
class HubSubscriptionClient(
    private val endpoint: String,
    private val identity: HubClientIdentity,
    private val caps: List<String> = HubCaps.declared,
    private val transport: HubHttpTransport = HttpUrlConnectionHubTransport(),
) {
    fun fetch(ifNoneMatch: String? = null): HubFetchOutcome {
        if (!HubEndpointPolicy.validEndpoint(endpoint)) return HubFetchOutcome.Failed(HubSubscriptionStatus.ENDPOINT_INVALID)
        val uri = HubEndpointPolicy.subscriptionUri(endpoint, identity, caps)
        val response = try {
            transport.get(uri, ifNoneMatch?.takeIf { it.isNotBlank() })
        } catch (failure: Exception) {
            return HubFetchOutcome.Failed(HubSubscriptionStatus.from(failure))
        }
        if (response.code == HttpURLConnection.HTTP_NOT_MODIFIED) return HubFetchOutcome.NotModified
        if (response.code !in 200..299) return HubFetchOutcome.Failed(HubSubscriptionStatus.http(response.code))
        val contentType = response.contentType.orEmpty().substringBefore(';').trim().lowercase()
        if (contentType.isNotEmpty() && contentType != "application/json") {
            return HubFetchOutcome.Failed(HubSubscriptionStatus.CONTENT_TYPE_INVALID)
        }
        val body = response.body
        if (body == null) return HubFetchOutcome.Failed(HubSubscriptionStatus.TOO_LARGE)
        if (body.size > HttpUrlConnectionHubTransport.MAX_PAYLOAD_BYTES) {
            return HubFetchOutcome.Failed(HubSubscriptionStatus.TOO_LARGE)
        }
        val payload = body.toString(Charsets.UTF_8)
        return when (val parsed = HubSubscriptionParser.parse(payload)) {
            is HubParseOutcome.Invalid -> HubFetchOutcome.Failed(HubSubscriptionStatus.INVALID_PAYLOAD)
            is HubParseOutcome.Parsed -> HubFetchOutcome.Fetched(
                payload = payload,
                subscription = parsed.subscription,
                etag = response.etag?.takeIf { it.isNotBlank() },
                skippedChannels = parsed.skippedChannels,
            )
        }
    }
}
