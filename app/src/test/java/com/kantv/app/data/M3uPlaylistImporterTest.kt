package com.kantv.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uPlaylistImporterTest {
    private val importer = M3uPlaylistImporter()

    @Test fun parsesChineseCommasQuotedAttributesEpgAndHeaders() {
        val prepared = importer.prepare(
            """#EXTM3U x-tvg-url="https://epg.example/a.xml https://epg.example/b.xml" url-tvg="https://epg.example/a.xml"
               |#EXTINF:-1 tvg-id="cctv.1" tvg-name="中央,一台" tvg-logo='https://img.example/logo.png' group-title="央视,精选" tvg-chno="001",CCTV-1, 综合频道
               |#EXTVLCOPT:http-user-agent=Agent/1.0
               |#EXTVLCOPT:http-referrer=https://ref.example/page
               |https://stream.example/live/channel
            """.trimMargin(),
        ) as ReadyPlaylistImport

        val channel = prepared.snapshot.channels.single()
        assertEquals(listOf("https://epg.example/a.xml", "https://epg.example/b.xml"), prepared.snapshot.epgUrls)
        assertEquals("CCTV-1, 综合频道", channel.name)
        assertEquals("中央,一台", channel.tvgName)
        assertEquals("央视,精选", channel.groupTitle)
        assertEquals("001", channel.channelNumber)
        assertEquals("Agent/1.0", channel.request.headers["User-Agent"])
        assertEquals("https://ref.example/page", channel.request.headers["Referer"])
        assertFalse(channel.request.toString().contains("stream.example"))
    }

    @Test fun parsesKodiSuffixHeadersAndAcceptsUrlWithoutExtension() {
        val prepared = importer.prepare(
            """#EXTM3U
               |#EXTINF:-1 tvg-id="news",新闻
               |https://stream.example/watch|User-Agent=Kodi%2F20&Referer=https%3A%2F%2Fsite.example
            """.trimMargin(),
        ) as ReadyPlaylistImport
        val request = prepared.snapshot.channels.single().request
        assertEquals("https://stream.example/watch", request.url)
        assertEquals("Kodi/20", request.headers["User-Agent"])
        assertEquals("https://site.example", request.headers["Referer"])
    }

    @Test fun rejectsEmptyAndCorruptListsWithoutReplacementSnapshot() {
        val empty = importer.prepare("#EXTM3U\n")
        val broken = importer.prepare("#EXTM3U\n#EXTINF:-1 tvg-id=bad,Broken\nnot a stream")
        assertFalse(empty.isReplaceSafe)
        assertFalse(broken.isReplaceSafe)
        assertTrue(broken.summary.issueCodes.contains(ImportIssue.INVALID_URL))
    }

    @Test fun skipsInvalidLinesAndKeepsValidChannel() {
        val prepared = importer.prepare(
            """#EXTM3U
               |#EXTINF:-1 tvg-id="good",有效频道
               |udp://239.1.1.1:1234
               |https://orphan.example/live
               |garbage
            """.trimMargin(),
        ) as ReadyPlaylistImport
        assertEquals(1, prepared.snapshot.channels.size)
        assertTrue(prepared.summary.issueCodes.contains(ImportIssue.ORPHAN_URL))
        assertTrue(prepared.summary.issueCodes.contains(ImportIssue.UNEXPECTED_CONTENT))
    }

    @Test fun sameNameDifferentTvgIdsGetDifferentStableIds() {
        val prepared = importer.prepare(
            """#EXTM3U
               |#EXTINF:-1 tvg-id="first",新闻频道
               |https://a.example/live
               |#EXTINF:-1 tvg-id="second",新闻频道
               |https://b.example/live
            """.trimMargin(),
        ) as ReadyPlaylistImport
        assertNotEquals(prepared.snapshot.channels[0].id, prepared.snapshot.channels[1].id)
        assertEquals(prepared.snapshot.channels[0].id, StableChannelId.create("first", null, "changed name"))
    }
}
