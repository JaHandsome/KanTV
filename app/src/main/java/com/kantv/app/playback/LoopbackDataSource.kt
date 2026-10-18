package com.kantv.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * Routes only the app's numeric loopback HLS server around the platform proxy selector.
 * All other URIs retain the supplied Media3 HTTP implementation and its normal proxy policy.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class LoopbackDataSource private constructor(
    private val upstreamFactory: DataSource.Factory,
) : DataSource {
    private var delegate: DataSource? = null
    private val transferListeners = mutableListOf<androidx.media3.datasource.TransferListener>()

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        transferListeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val selected = if (shouldBypassSystemProxy(dataSpec.uri.toString())) NoProxyHttpDataSource() else upstreamFactory.createDataSource()
        transferListeners.forEach(selected::addTransferListener)
        delegate = selected
        return selected.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        requireNotNull(delegate).read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()

    override fun close() {
        delegate?.close()
        delegate = null
    }

    companion object {
        fun factory(upstreamFactory: DataSource.Factory): DataSource.Factory =
            DataSource.Factory { LoopbackDataSource(upstreamFactory) }

        /** Do not broaden this exception to localhost, ::1, or private network addresses. */
        internal fun shouldBypassSystemProxy(uri: String): Boolean = runCatching {
            val parsed = java.net.URI(uri)
            val path = parsed.rawPath.orEmpty()
            parsed.scheme.equals("http", ignoreCase = true) &&
                parsed.host == "127.0.0.1" &&
                parsed.port in 1..65_535 &&
                parsed.userInfo == null &&
                (path == "/playlist.m3u8" || path.startsWith("/segment/"))
        }.getOrDefault(false)
    }
}

/** A deliberately small HTTP reader for the in-process proxy; it opens with [Proxy.NO_PROXY]. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class NoProxyHttpDataSource : BaseDataSource(true) {
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var openedUri: Uri? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()
    private var transferOpen = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            val http = (URL(dataSpec.uri.toString()).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = dataSpec.getHttpMethodString()
                dataSpec.httpRequestHeaders.forEach(::setRequestProperty)
                if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else dataSpec.position + dataSpec.length - 1
                    setRequestProperty("Range", "bytes=${dataSpec.position}-$end")
                }
                dataSpec.httpBody?.let { body ->
                    doOutput = true
                    outputStream.use { it.write(body) }
                }
            }
            val responseCode = http.responseCode
            responseHeaders = http.headerFields.filterKeys { it != null }
            if (responseCode !in 200..299) {
                val body = http.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                throw HttpDataSource.InvalidResponseCodeException(
                    responseCode,
                    http.responseMessage,
                    null,
                    http.headerFields,
                    dataSpec,
                    body,
                )
            }
            connection = http
            openedUri = dataSpec.uri
            input = http.inputStream
            transferStarted(dataSpec)
            transferOpen = true
            val contentLength = http.contentLengthLong
            return when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                contentLength >= 0 -> contentLength
                else -> C.LENGTH_UNSET.toLong()
            }
        } catch (error: IOException) {
            close()
            throw HttpDataSource.HttpDataSourceException.createForIOException(error, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        val read = requireNotNull(input).read(buffer, offset, length)
        if (read > 0) bytesTransferred(read)
        read
    } catch (error: IOException) {
        throw HttpDataSource.HttpDataSourceException.createForIOException(error, checkNotNull(openedUri).let { DataSpec(it) }, HttpDataSource.HttpDataSourceException.TYPE_READ)
    }

    override fun getUri(): Uri? = openedUri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    override fun close() {
        try { input?.close() } finally {
            input = null
            connection?.disconnect()
            connection = null
            openedUri = null
            responseHeaders = emptyMap()
            if (transferOpen) {
                transferOpen = false
                transferEnded()
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 8_000
    }
}
