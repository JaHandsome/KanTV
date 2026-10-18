package com.kantv.app.catalog

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Errors which can safely be persisted as refresh state without retaining a URL or exception text. */
sealed class PlaylistLoadException : IOException() {
    data class HttpStatus(val status: Int) : PlaylistLoadException()
    data object TooLarge : PlaylistLoadException()
}

class PlaylistLoader {
    fun fromUrl(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "audio/x-mpegurl, application/x-mpegurl, text/plain, */*")
            // 公开清单常带 gzip；解压后的体积仍受 MAX_BYTES 上限约束。
            setRequestProperty("Accept-Encoding", "gzip")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw PlaylistLoadException.HttpStatus(status)
            val output = ByteArrayOutputStream()
            val body = if (connection.getHeaderField("Content-Encoding")?.equals("gzip", ignoreCase = true) == true) {
                java.util.zip.GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }
            body.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) throw PlaylistLoadException.TooLarge
                    output.write(buffer, 0, read)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
    }
}
