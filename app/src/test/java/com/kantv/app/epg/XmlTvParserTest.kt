package com.kantv.app.epg

import com.kantv.app.playback.PlaybackRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPOutputStream

class XmlTvParserTest {
    @Test fun matchesOfficialIdentityAndReturnsCompleteOrderedSchedule() {
        val channel = EpgChannelRef(
            channelId = "official-row",
            tvgId = "official-ysp-guangdong",
            name = "广东卫视 高清",
            identityKey = "satellite-广东",
        )
        val result = XmlTvParser.parse(xml().byteInputStream(), listOf(channel))
        val schedule = result.getValue(channel.channelId)

        assertEquals(listOf("早间", "午间", "晚间"), schedule.programmes.map { it.title })
        val windowAt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
            .parse("20260910115000 +0800")!!.time
        val window = schedule.window(windowAt)
        assertEquals("早间", window.previous?.title)
        assertEquals("午间", window.current?.title)
        assertEquals("晚间", window.next?.title)
        assertTrue(schedule.programmes.all { it.catchUpPlayback == null })
        assertFalse(schedule.programmes.first().canReplayAt(Long.MAX_VALUE))
    }

    @Test fun parsesGzipAndDoesNotTreatXmlTvInformationUrlAsReplay() {
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(xml().toByteArray()) }
        }.toByteArray()
        val channel = EpgChannelRef("c1", "广东卫视", "任意显示名")
        val programme = XmlTvParser.parse(ByteArrayInputStream(compressed), listOf(channel))
            .getValue("c1").programmes.first()

        assertNull(programme.catchUpPlayback)
        assertEquals(EpgProgrammeState.PAST, programme.stateAt(Long.MAX_VALUE))
    }

    @Test fun replayRequiresAnExplicitPlaybackRequest() {
        val programme = EpgProgramme(
            id = "p", startAtMs = 100, endAtMs = 200, title = "节目",
            catchUpPlayback = PlaybackRequest("c", "https://media.example/replay.m3u8"),
        )
        assertTrue(programme.canReplayAt(201))
        assertFalse(programme.canReplayAt(150))
    }

    @Test fun rejectsDoctypeInsteadOfLoadingAnExternalEntity() {
        val xml = """<!DOCTYPE tv SYSTEM "https://invalid.example/xmltv.dtd"><tv/>"""
        assertThrows(Exception::class.java) {
            XmlTvParser.parse(xml.byteInputStream(), emptyList())
        }
    }

    @Test fun rejectsDoctypeWithInternalEntities() {
        val xml = """<?xml version="1.0"?><!DOCTYPE tv [<!ENTITY x "nope">]><tv>&x;</tv>"""
        assertThrows(Exception::class.java) {
            XmlTvParser.parse(xml.byteInputStream(), emptyList())
        }
    }

    @Test fun rejectsGzippedDoctype() {
        val xml = """<!DOCTYPE tv [<!ENTITY x "nope">]><tv/>""".toByteArray()
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(xml) }
        }.toByteArray()
        assertThrows(Exception::class.java) {
            XmlTvParser.parse(ByteArrayInputStream(compressed), emptyList())
        }
    }

    @Test fun rejectsUtf16DoctypeInTheByteFilter() {
        val xml = """<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE tv [<!ENTITY x "nope">]><tv>&x;</tv>"""
        for (encoding in listOf(Charsets.UTF_16BE, Charsets.UTF_16LE)) {
            val bom = if (encoding == Charsets.UTF_16BE) {
                byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            } else {
                byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            }
            assertFilterRejected(bom + xml.toByteArray(encoding))
        }
    }

    @Test fun rejectsGzippedUtf16DoctypeInTheByteFilter() {
        val xml = """<!DOCTYPE tv [<!ENTITY x "nope">]><tv/>"""
        val encoded = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + xml.toByteArray(Charsets.UTF_16BE)
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(encoded) }
        }.toByteArray()
        assertFilterRejected(compressed)
    }

    @Test fun parsesUtf16GuideWithoutADoctype() {
        val xml = """<?xml version="1.0" encoding="UTF-16"?><tv>
          <channel id="one"><display-name>One</display-name></channel>
          <programme channel="one" start="20260909060000 +0800" stop="20260909070000 +0800"><title>A</title></programme>
        </tv>"""
        val encoded = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + xml.toByteArray(Charsets.UTF_16BE)
        val result = XmlTvParser.parse(
            ByteArrayInputStream(encoded),
            listOf(EpgChannelRef("1", "one", "One")),
        )
        assertEquals(listOf("A"), result.getValue("1").programmes.map { it.title })
    }

    @Test fun keepsProgrammesThatArriveBeforeTheirChannel() {
        val input = """<tv>
          <programme channel="one" start="20260909060000 +0800" stop="20260909070000 +0800"><title>A</title><category>新闻</category></programme>
          <programme channel="two" start="20260909060000 +0800" stop="20260909070000 +0800"><title>Skip</title></programme>
          <channel id="one"><display-name>One</display-name></channel>
          <channel id="two"><display-name>Unrequested</display-name></channel>
          <programme channel="one" start="20260909070000 +0800" stop="20260909080000 +0800"><title>B</title></programme>
          <programme channel="two" start="20260909080000 +0800" stop="20260909090000 +0800"><title>Still skipped</title></programme>
        </tv>"""
        val result = XmlTvParser.parse(input.byteInputStream(), listOf(EpgChannelRef("1", "one", "One")))
        val schedule = result.getValue("1")
        assertEquals(listOf("A", "B"), schedule.programmes.map { it.title })
        assertEquals(listOf("新闻"), schedule.programmes.first().categories)
        assertEquals(setOf("1"), result.keys)
    }

    @Test fun supportsChannelsInterleavedWithTheirProgrammes() {
        val input = """<tv>
          <channel id="one"><display-name>One</display-name></channel>
          <programme channel="one" start="20260909060000 +0800" stop="20260909070000 +0800"><title>A</title></programme>
          <channel id="two"><display-name>Two</display-name></channel>
          <programme channel="two" start="20260909060000 +0800" stop="20260909070000 +0800"><title>B</title></programme>
        </tv>"""
        val result = XmlTvParser.parse(input.byteInputStream(), listOf(
            EpgChannelRef("1", "one", "One"), EpgChannelRef("2", "two", "Two")))
        assertEquals(setOf("1", "2"), result.keys)
        assertEquals("B", result.getValue("2").programmes.single().title)
    }

    private fun xml() = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="feed-44"><display-name>广东卫视</display-name></channel>
          <programme channel="feed-44" start="20260910100000 +0800" stop="20260910110000 +0800">
            <title>早间</title><url>https://example.invalid/information-only</url>
          </programme>
          <programme channel="feed-44" start="20260910113000 +0800" stop="20260910123000 +0800">
            <title>午间</title><desc>节目简介</desc><category>新闻</category>
          </programme>
          <programme channel="feed-44" start="20260910130000 +0800" stop="20260910140000 +0800">
            <title>晚间</title>
          </programme>
        </tv>
    """.trimIndent()

    private fun assertFilterRejected(xml: ByteArray) {
        val error = assertThrows(Exception::class.java) {
            XmlTvParser.parse(ByteArrayInputStream(xml), emptyList())
        }
        val messages = generateSequence(error as Throwable) { it.cause }
            .map { it.message.orEmpty() }
            .toList()
        assertTrue(
            messages.joinToString(" | "),
            messages.any { "XMLTV doctype is not allowed" in it },
        )
    }
}
