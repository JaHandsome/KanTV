package com.kantv.app.epg

import com.kantv.app.util.Hashing
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

internal object XmlTvParser {
    fun parse(input: InputStream, requested: List<EpgChannelRef>): Map<String, EpgChannelSchedule> {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        buffered.mark(2)
        val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val decoded = DoctypeRejectingInputStream(
            BoundedInputStream(if (gzip) GZIPInputStream(buffered) else buffered, MAX_EXPANDED_BYTES),
        )

        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            setFeatureSafely(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
            setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureSafely("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeatureSafely("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val handler = Handler(requested)
        factory.newSAXParser().parse(InputSource(decoded), handler)
        return handler.result()
    }

    private const val MAX_EXPANDED_BYTES = 64L * 1024 * 1024

    private fun SAXParserFactory.setFeatureSafely(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private class Handler(private val requested: List<EpgChannelRef>) : DefaultHandler() {
        private val programmes = linkedMapOf<String, MutableList<EpgProgramme>>()
        private val matches = linkedMapOf<String, EpgChannelRef>()
        private val heldProgrammes = linkedMapOf<String, MutableList<HeldProgramme>>()
        private val seenChannelIds = HashSet<String>()
        private val chooseChannel = EpgChannelMatcher.prepare(requested)
        private val matchedIds = mutableSetOf<String>()
        private var channelId: String? = null
        private var channelNames = linkedSetOf<String>()
        private var programmeChannelId: String? = null
        private var programmeStartMs: Long? = null
        private var programmeEndMs: Long? = null
        private var title: String? = null
        private var subtitle: String? = null
        private var description: String? = null
        private val categories = mutableListOf<String>()
        private var captureProgrammeText = false
        private var textElement: String? = null
        private val text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (qName.lowercase(Locale.ROOT)) {
                "channel" -> {
                    channelId = attributes.getValue("id")?.trim()
                    channelNames = linkedSetOf()
                }
                "programme" -> {
                    programmeChannelId = attributes.getValue("channel")?.trim()
                    val feedId = programmeChannelId
                    // A channel element that already failed to match will not match later.
                    // Programmes that arrive before their channel still have to be kept.
                    val knownMiss = feedId != null && feedId in seenChannelIds && feedId !in matches
                    captureProgrammeText = feedId != null && !knownMiss
                    textElement = null
                    programmeStartMs = if (captureProgrammeText) parseXmlTvTime(attributes.getValue("start")) else null
                    programmeEndMs = if (captureProgrammeText) parseXmlTvTime(attributes.getValue("stop")) else null
                    title = null
                    subtitle = null
                    description = null
                    categories.clear()
                }
                "display-name" -> {
                    textElement = "display-name"
                    text.setLength(0)
                }
                "title", "sub-title", "desc", "category" -> if (captureProgrammeText) {
                    textElement = qName.lowercase(Locale.ROOT)
                    text.setLength(0)
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (textElement != null && text.length < MAX_TEXT_CHARS) {
                text.append(ch, start, minOf(length, MAX_TEXT_CHARS - text.length))
            }
        }

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            InputSource(StringReader(""))

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val value = text.toString().trim()
            when (qName.lowercase(Locale.ROOT)) {
                "display-name" -> if (channelId != null && value.isNotEmpty()) channelNames += value
                "channel" -> channelId?.let { id ->
                    seenChannelIds += id
                    val feed = XmlTvChannel(id, channelNames.toSet())
                    val chosen = chooseChannel(feed)?.takeIf { matchedIds.add(it.channelId) }
                    if (chosen != null) {
                        matches[id] = chosen
                        flushHeld(id, chosen)
                    } else {
                        heldProgrammes.remove(id)
                    }
                    channelId = null
                }
                "title" -> if (value.isNotEmpty() && title == null) title = value
                "sub-title" -> if (value.isNotEmpty() && subtitle == null) subtitle = value
                "desc" -> if (value.isNotEmpty() && description == null) description = value
                "category" -> if (value.isNotEmpty()) categories += value
                "programme" -> appendProgramme()
            }
            if (qName.equals(textElement, ignoreCase = true)) textElement = null
        }

        private fun appendProgramme() {
            val feedId = programmeChannelId ?: return
            if (feedId in seenChannelIds && feedId !in matches) return
            val start = programmeStartMs ?: return
            val end = programmeEndMs ?: return
            val programmeTitle = title?.takeIf { it.isNotBlank() } ?: return
            if (end <= start) return
            val categoriesSnapshot = categories.distinct()
            val channel = matches[feedId]
            if (channel == null) {
                heldProgrammes.getOrPut(feedId) { mutableListOf() } += HeldProgramme(
                    startMs = start,
                    endMs = end,
                    title = programmeTitle,
                    subtitle = subtitle,
                    description = description,
                    categories = categoriesSnapshot,
                )
                return
            }
            addProgramme(channel, start, end, programmeTitle, subtitle, description, categoriesSnapshot)
        }

        private fun flushHeld(feedId: String, channel: EpgChannelRef) {
            heldProgrammes.remove(feedId)?.forEach { item ->
                addProgramme(
                    channel,
                    item.startMs,
                    item.endMs,
                    item.title,
                    item.subtitle,
                    item.description,
                    item.categories,
                )
            }
        }

        private fun addProgramme(
            channel: EpgChannelRef,
            start: Long,
            end: Long,
            programmeTitle: String,
            programmeSubtitle: String?,
            programmeDescription: String?,
            programmeCategories: List<String>,
        ) {
            programmes.getOrPut(channel.channelId) { mutableListOf() } += EpgProgramme(
                id = stableProgrammeId(channel.channelId, start, end, programmeTitle),
                startAtMs = start,
                endAtMs = end,
                title = programmeTitle,
                subtitle = programmeSubtitle,
                description = programmeDescription,
                categories = programmeCategories,
                // XMLTV <url> is informational metadata, not a replay stream.
                catchUpPlayback = null,
            )
        }

        fun result(): Map<String, EpgChannelSchedule> {
            return matches.entries.mapNotNull { (feedId, channel) ->
                val items = programmes[channel.channelId].orEmpty()
                    .distinctBy { it.startAtMs to it.endAtMs }
                    .sortedBy { it.startAtMs }
                if (items.isEmpty()) null else channel.channelId to EpgChannelSchedule(channel, feedId, items)
            }.toMap(linkedMapOf())
        }
    }

    private data class HeldProgramme(
        val startMs: Long,
        val endMs: Long,
        val title: String,
        val subtitle: String?,
        val description: String?,
        val categories: List<String>,
    )

    private const val MAX_TEXT_CHARS = 32 * 1024

    /**
     * [setFeatureSafely] swallows a parser that rejects `disallow-doctype-decl`.
     * Internal entity expansion never calls [Handler.resolveEntity], so a DOCTYPE
     * that gets through can still expand. XMLTV has no DTD; reject the declaration
     * itself while the bytes are read.
     */
    private class DoctypeRejectingInputStream(
        private val delegate: InputStream,
    ) : InputStream() {
        private var matched = 0

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) observe(value)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = delegate.read(buffer, offset, length)
            if (count > 0) {
                for (index in offset until offset + count) observe(buffer[index].toInt() and 0xFF)
            }
            return count
        }

        override fun close() = delegate.close()

        private fun observe(value: Int) {
            if (matched == 0) {
                if (value == '<'.code) matched = 1
                return
            }
            if (matched == 1) {
                matched = when (value) {
                    '!'.code -> 2
                    '<'.code -> 1
                    else -> 0
                }
                return
            }
            val expected = DOCTYPE[matched - 2]
            val folded = if (value in 'A'.code..'Z'.code) value + 32 else value
            if (folded == expected.code) {
                matched++
                if (matched == 2 + DOCTYPE.length) throw IOException("XMLTV doctype is not allowed")
                return
            }
            matched = if (value == '<'.code) 1 else 0
        }
    }

    private const val DOCTYPE = "doctype"

    private class BoundedInputStream(
        private val delegate: InputStream,
        private val maxBytes: Long,
    ) : InputStream() {
        private var total = 0L

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = delegate.read(buffer, offset, length)
            if (count > 0) account(count)
            return count
        }

        override fun close() = delegate.close()

        private fun account(count: Int) {
            total += count
            if (total > maxBytes) throw IOException("expanded XMLTV exceeds limit")
        }
    }

    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("GMT+08:00")
        }
    }

    private val timestampPattern = Regex("^(\\d{14})(?:\\s*([+-]\\d{4}|Z))?")
    private fun parseXmlTvTime(value: String?): Long? {
        val match = value?.let {
            timestampPattern.find(it.trim())
        } ?: return null
        val datePart = match.groupValues[1]
        val zonePart = match.groupValues[2].ifBlank { "+0800" }.replace("Z", "+0000")
        return dateFormat.get()!!.parse("$datePart $zonePart", ParsePosition(0))?.time
    }

    private fun stableProgrammeId(channelId: String, startMs: Long, endMs: Long, title: String): String =
        "epg_" + Hashing.sha256Hex("$channelId\u001F$startMs\u001F$endMs\u001F$title".toByteArray(Charsets.UTF_8)).take(24)
}
