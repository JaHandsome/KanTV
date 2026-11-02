package com.kantv.app.util

import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.Locale

/**
 * Conditional-request validators are copied into later request headers and onto disk.
 * A CR, LF, or other control character makes [java.net.HttpURLConnection.setRequestProperty]
 * throw, so one poisoned ETag sticks and every later refresh fails closed.
 */
fun cacheValidator(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (trimmed.any { it <= '\u001F' || it == '\u007F' }) return null
    return trimmed
}

/**
 * Absolute http(s) URL safe to hand to [URL.openConnection].
 * [URL] accepts CR/LF in the path or query and will put them on the request line.
 * file/content/javascript are rejected here so a cast to HttpURLConnection is not
 * the only thing standing in front of a local open.
 */
fun openableHttpUrl(value: String): URL? {
    if (value.any { it <= '\u001F' || it == '\u007F' }) return null
    val uri = try {
        URI(value)
    } catch (_: URISyntaxException) {
        return null
    }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank()) return null
    return try {
        uri.toURL()
    } catch (_: MalformedURLException) {
        null
    }
}
