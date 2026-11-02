package com.kantv.app.util

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
