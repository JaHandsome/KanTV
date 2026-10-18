package com.kantv.app.catalog

import com.kantv.app.playback.PlaybackRequest
import com.kantv.app.playback.SourceQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused tests for the ordering the app actually still performs: official playable pages first, then
 * the order the catalogue stored.
 *
 * Reordering from this device's own playback history was removed on purpose (2026-09-15) and its tests
 * went with it: measured against a real channel it ranked a source that only ever showed a frozen test
 * card as the best one and put it first, so the viewer met a dead source on every open. Quality is now
 * whatever the payload says and nothing else.
 *
 * **The upstream's written order is deliberately not preserved anywhere in the client** — the payload is
 * sorted by `score` when it is parsed and by `quality` when the list is read. That is why a source must
 * never be identified to a publisher by its position; see docs/source-audit-2026-09-15.md §6.
 */
class CatalogSourceOrderingTest {
    private fun request(uri: String, quality: Int = SourceQuality.NONE) = PlaybackRequest(
        channelId = "channel",
        uri = uri,
        quality = quality,
    )

    @Test
    fun officialSourcesComeFirstAndKeepTheirRelativeOrder() {
        val sources = listOf(
            CatalogSource("http://a/1", emptyMap(), official = false),
            CatalogSource("http://b/1", emptyMap(), official = true),
            CatalogSource("http://c/1", emptyMap(), official = false),
            CatalogSource("http://d/1", emptyMap(), official = true),
        )

        val ordered = CatalogSourceOrdering.stable(sources)

        assertEquals(
            listOf("http://b/1", "http://d/1", "http://a/1", "http://c/1"),
            ordered.map { it.url },
        )
        // Nothing is dropped and nothing is duplicated.
        assertEquals(sources.size, ordered.size)
        assertEquals(sources.map { it.url }.toSet(), ordered.map { it.url }.toSet())
    }

    @Test
    fun playableOfficialWebPageIsFirstEvenWhenNativeCandidatesPrecedeIt() {
        val native = request("http://a/native")
        val official = request("https://www.yangshipin.cn/tv/home#kantv-cctv1")

        assertEquals(
            listOf(official, native),
            CatalogSourceOrdering.officialWebFirst(listOf(native, official)),
        )
    }

    @Test
    fun officialWebFirstNeverDropsOrDuplicatesAndKeepsTheRestInOrder() {
        // The guarantee the source report relies on: promotion may move an entry, but the list it
        // produces always contains exactly the entries it was given, in a deterministic order.
        val nativeA = request("http://a/one")
        val official = request("https://www.yangshipin.cn/tv/home#kantv-cctv1")
        val nativeB = request("http://a/two")
        val nativeC = request("http://a/three")

        val ordered = CatalogSourceOrdering.officialWebFirst(listOf(nativeA, official, nativeB, nativeC))

        assertEquals(listOf(official, nativeA, nativeB, nativeC), ordered)
        assertEquals(4, ordered.size)
        assertEquals(ordered.toSet().size, ordered.size)
    }

    @Test
    fun qualityIsNeverInferredFromAUrl() {
        // Names that clearly contain a resolution must stay NONE unless the hub said so.
        assertEquals(SourceQuality.NONE, SourceQuality.parse(null))
        assertEquals(SourceQuality.NONE, SourceQuality.parse(""))
        assertEquals(SourceQuality.NONE, SourceQuality.parse("auto"))
        assertEquals(SourceQuality.NONE, SourceQuality.parse("原画"))
        assertTrue(SourceQuality.isKnown(SourceQuality.parse("1080p")))
        assertTrue(SourceQuality.isKnown(SourceQuality.parse("4k")))
        assertNotEquals(SourceQuality.NONE, SourceQuality.parse("720p"))
    }

    @Test
    fun aStrayQualityValueCannotOutrankTheCeiling() {
        val clamped = SourceQuality.qualityOf(Int.MAX_VALUE)
        assertTrue(clamped <= 8_000)
        assertTrue(SourceQuality.qualityOf(SourceQuality.parse("4k")) <= clamped || clamped == 8_000)
        assertEquals(SourceQuality.NONE, SourceQuality.qualityOf(Int.MIN_VALUE))
    }
}
