package com.kantv.app.data

import com.kantv.app.catalog.ChannelAliasTable
import com.kantv.app.util.Hashing
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * Parses an M3U playlist without performing network or storage work.  The caller may only
 * replace its current catalogue when [PlaylistImportPreparation.isReplaceSafe] is true.
 *
 * [aliasTableProvider] 提供**服务端下发**的别名表（契约 §1.1）：命中时该条目按服务端
 * `channel_id` 归因，从而与枢纽频道合并；未命中（或 provider 返回 null）时行为与过去完全一致。
 */
class M3uPlaylistImporter(
    private val aliasTableProvider: () -> ChannelAliasTable? = { null },
) {
    fun prepare(source: String): PlaylistImportPreparation {
        val parsed = M3uParser.parse(source, aliasTableProvider())
        return if (parsed.headerFound && parsed.channels.isNotEmpty()) {
            ReadyPlaylistImport(
                PlaylistSnapshot(parsed.epgUrls, parsed.channels),
                ImportSummary(
                    acceptedChannels = parsed.channels.size,
                    rejectedLines = parsed.issues.size,
                    issueCodes = parsed.issues.toSet(),
                ),
            )
        } else {
            RejectedPlaylistImport(
                ImportSummary(
                    acceptedChannels = 0,
                    rejectedLines = parsed.issues.size,
                    issueCodes = (parsed.issues + if (!parsed.headerFound) ImportIssue.INVALID_HEADER else ImportIssue.EMPTY_PLAYLIST).toSet(),
                ),
            )
        }
    }
}

sealed interface PlaylistImportPreparation {
    val summary: ImportSummary
    val isReplaceSafe: Boolean
}

class ReadyPlaylistImport(
    val snapshot: PlaylistSnapshot,
    override val summary: ImportSummary,
) : PlaylistImportPreparation {
    override val isReplaceSafe: Boolean = true
}

class RejectedPlaylistImport(
    override val summary: ImportSummary,
) : PlaylistImportPreparation {
    override val isReplaceSafe: Boolean = false
}

data class PlaylistSnapshot(
    val epgUrls: List<String>,
    val channels: List<PlaylistChannel>,
)

data class ImportSummary(
    val acceptedChannels: Int,
    val rejectedLines: Int,
    val issueCodes: Set<ImportIssue>,
)

/** Deliberately contains codes only, so diagnostics never include stream URLs or request headers. */
enum class ImportIssue {
    INVALID_HEADER,
    EMPTY_PLAYLIST,
    MALFORMED_EXTINF,
    MISSING_URL,
    INVALID_URL,
    ORPHAN_URL,
    UNEXPECTED_CONTENT,
}

class PlaylistChannel(
    val id: String,
    val tvgId: String?,
    val name: String,
    val tvgName: String?,
    val logoUrl: String?,
    val groupTitle: String?,
    val channelNumber: String?,
    val request: StreamRequest,
) {
    override fun toString(): String = "PlaylistChannel(id=$id, name=$name, tvgId=$tvgId)"
}

/**
 * The stream URL and headers are needed by the playback layer, but its string representation is
 * intentionally redacted to prevent accidental exposure through logs or thrown messages.
 */
class StreamRequest(
    val url: String,
    val headers: Map<String, String>,
) {
    override fun toString(): String = "StreamRequest(url=<redacted>, headers=<redacted>)"
}

private data class ParsedPlaylist(
    val headerFound: Boolean,
    val epgUrls: List<String>,
    val channels: List<PlaylistChannel>,
    val issues: List<ImportIssue>,
)

private data class PendingChannel(
    val attributes: Map<String, String>,
    val name: String,
    val headers: MutableMap<String, String> = linkedMapOf(),
)

private object M3uParser {
    fun parse(source: String, aliases: ChannelAliasTable? = null): ParsedPlaylist {
        val lines = source.lineSequence().map { it.trim() }.toList()
        val firstContent = lines.firstOrNull { it.isNotEmpty() }?.removePrefix("\uFEFF")
        if (firstContent?.startsWith("#EXTM3U", ignoreCase = true) != true) {
            return ParsedPlaylist(false, emptyList(), emptyList(), listOf(ImportIssue.INVALID_HEADER))
        }

        val epgUrls = parseHeaderEpgUrls(firstContent)
        val channels = mutableListOf<PlaylistChannel>()
        val issues = mutableListOf<ImportIssue>()
        var pending: PendingChannel? = null

        for (rawLine in lines.dropWhile { it.isEmpty() || it.removePrefix("\uFEFF") == firstContent }) {
            if (rawLine.isEmpty()) continue
            when {
                rawLine.startsWith("#EXTINF:", ignoreCase = true) -> {
                    if (pending != null) issues += ImportIssue.MISSING_URL
                    pending = parseExtInf(rawLine).also { if (it == null) issues += ImportIssue.MALFORMED_EXTINF }
                }
                rawLine.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val item = pending
                    if (item != null) parseVlcHeader(rawLine, item.headers)
                }
                rawLine.startsWith("#") -> Unit
                else -> {
                    val item = pending
                    if (item == null) {
                        issues += if (looksLikeUrl(rawLine)) ImportIssue.ORPHAN_URL else ImportIssue.UNEXPECTED_CONTENT
                    } else {
                        parseStreamRequest(rawLine, item.headers)?.let { request ->
                            val attrs = item.attributes
                            val tvgId = attrs["tvg-id"].orEmpty().ifBlank { null }
                            val displayName = item.name.ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { "Unnamed channel" }
                            // 契约 §1.1：命中服务端别名表时按 channel_id 归因（导入任意第三方
                            // M3U 也能与枢纽频道合并）；未命中时保持过去的稳定 ID 行为。
                            val attributed = listOf(displayName, attrs["tvg-name"].orEmpty(), tvgId.orEmpty())
                                .mapNotNull { candidate ->
                                    candidate.takeIf { it.isNotBlank() }
                                        ?.let { com.kantv.app.catalog.ChannelIdentity.resolveChannelId(it, aliases) }
                                }
                                .firstOrNull()
                            channels += PlaylistChannel(
                                id = attributed?.let { com.kantv.app.catalog.HubChannelIdentity.internalId(it) }
                                    ?: StableChannelId.create(tvgId, attrs["group-title"], displayName),
                                tvgId = attributed ?: tvgId,
                                name = displayName,
                                tvgName = attrs["tvg-name"].orEmpty().ifBlank { null },
                                logoUrl = attrs["tvg-logo"].orEmpty().ifBlank { null },
                                groupTitle = attrs["group-title"].orEmpty().ifBlank { null },
                                channelNumber = attrs["tvg-chno"].orEmpty().ifBlank { null },
                                request = request,
                            )
                        } ?: run { issues += ImportIssue.INVALID_URL }
                        pending = null
                    }
                }
            }
        }
        if (pending != null) issues += ImportIssue.MISSING_URL
        return ParsedPlaylist(true, epgUrls, channels, issues)
    }

    private fun parseHeaderEpgUrls(header: String): List<String> {
        val attributes = parseAttributes(header.removePrefix("#EXTM3U"))
        return listOfNotNull(attributes["x-tvg-url"], attributes["url-tvg"])
            .flatMap { it.split(Regex("[\\s,]+")) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun parseExtInf(line: String): PendingChannel? {
        val body = line.substringAfter(':', missingDelimiterValue = "")
        val comma = findUnquotedComma(body)
        if (comma < 0) return null
        return PendingChannel(parseAttributes(body.substring(0, comma)), body.substring(comma + 1).trim())
    }

    private fun parseVlcHeader(line: String, headers: MutableMap<String, String>) {
        val value = line.substringAfter(':', missingDelimiterValue = "")
        val separator = value.indexOf('=')
        if (separator < 1) return
        when (value.substring(0, separator).trim().lowercase(Locale.ROOT)) {
            "http-user-agent" -> headers["User-Agent"] = value.substring(separator + 1).trim()
            "http-referrer" -> headers["Referer"] = value.substring(separator + 1).trim()
        }
    }

    private fun parseStreamRequest(line: String, precedingHeaders: Map<String, String>): StreamRequest? {
        val baseUrl = line.substringBefore('|').trim()
        if (!looksLikeUrl(baseUrl)) return null
        val headers = linkedMapOf<String, String>()
        headers.putAll(precedingHeaders)
        line.substringAfter('|', missingDelimiterValue = "")
            .split('&')
            .forEach { pair ->
                val split = pair.indexOf('=')
                if (split < 1) return@forEach
                when (pair.substring(0, split).trim().lowercase(Locale.ROOT)) {
                    "user-agent" -> headers["User-Agent"] = decodeSuffixValue(pair.substring(split + 1).trim())
                    "referer" -> headers["Referer"] = decodeSuffixValue(pair.substring(split + 1).trim())
                }
            }
        return StreamRequest(baseUrl, headers.toMap())
    }

    private fun decodeSuffixValue(value: String): String = try {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        value
    }

    private fun looksLikeUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme?.matches(Regex("[A-Za-z][A-Za-z0-9+.-]*")) == true
    } catch (_: Exception) {
        false
    }

    private fun parseAttributes(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val pattern = Regex("""([A-Za-z0-9_-]+)=(?:\"((?:\\.|[^\"])*)\"|'((?:\\.|[^'])*)'|([^\\s,]+))""")
        pattern.findAll(text).forEach { match ->
            val value = match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]?.value ?: ""
            result[match.groups[1]!!.value.lowercase(Locale.ROOT)] = value.replace("\\\"", "\"")
        }
        return result
    }

    private fun findUnquotedComma(value: String): Int {
        var quote: Char? = null
        var escaped = false
        value.forEachIndexed { index, char ->
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if ((char == '\"' || char == '\'') && (quote == null || quote == char)) quote = if (quote == null) char else null
            else if (char == ',' && quote == null) return index
        }
        return -1
    }
}

object StableChannelId {
    fun create(tvgId: String?, groupTitle: String?, name: String): String {
        val key = tvgId?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            ?: listOf(groupTitle, name)
                .joinToString("\u001F") { it.orEmpty().trim().lowercase(Locale.ROOT) }
        return "ch_" + Hashing.sha256Hex(key.toByteArray(Charsets.UTF_8)).take(24)
    }
}
