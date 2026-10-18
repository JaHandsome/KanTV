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
import javax.xml.parsers.SAXParserFactory

internal object XmlTvParser {
    fun parse(input: InputStream, requested: List<EpgChannelRef>): Map<String, EpgChannelSchedule> {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        buffered.mark(2)
        val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val decoded = BoundedInputStream(if (gzip) GZIPInputStream(buffered) else buffered, MAX_EXPANDED_BYTES)

        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
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
        private val feedChannels = mutableListOf<XmlTvChannel>()
        private val programmes = linkedMapOf<String, MutableList<EpgProgramme>>()
        private val matches = linkedMapOf<String, EpgChannelRef>()
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
                    val relevant = matches.orEmpty().containsKey(programmeChannelId)
                    programmeStartMs = if (relevant) parseXmlTvTime(attributes.getValue("start")) else null
                    programmeEndMs = if (relevant) parseXmlTvTime(attributes.getValue("stop")) else null
                    title = null
                    subtitle = null
                    description = null
                    categories.clear()
                }
                "display-name", "title", "sub-title", "desc", "category" -> {
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
                    val feed = XmlTvChannel(id, channelNames.toSet())
                    chooseChannel(feed)?.takeIf { matchedIds.add(it.channelId) }?.let { matches[id] = it }
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
            val channel = matches.orEmpty()[feedId] ?: return
            val start = programmeStartMs ?: return
            val end = programmeEndMs ?: return
            val programmeTitle = title?.takeIf { it.isNotBlank() } ?: return
            if (end <= start) return
            programmes.getOrPut(channel.channelId) { mutableListOf() } += EpgProgramme(
                id = stableProgrammeId(channel.channelId, start, end, programmeTitle),
                startAtMs = start,
                endAtMs = end,
                title = programmeTitle,
                subtitle = subtitle,
                description = description,
                categories = categories.distinct(),
                // XMLTV <url> is informational metadata, not a replay stream.
                catchUpPlayback = null,
            )
        }

        fun result(): Map<String, EpgChannelSchedule> {
            val finalMatches = matches ?: EpgChannelMatcher.match(requested, feedChannels)
            return finalMatches.entries.mapNotNull { (feedId, channel) ->
                val items = programmes[channel.channelId].orEmpty()
                    .distinctBy { it.startAtMs to it.endAtMs }
                    .sortedBy { it.startAtMs }
                if (items.isEmpty()) null else channel.channelId to EpgChannelSchedule(channel, feedId, items)
            }.toMap(linkedMapOf())
        }
    }

    private const val MAX_TEXT_CHARS = 32 * 1024

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
