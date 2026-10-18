package com.kantv.app.catalog

import com.kantv.app.playback.PlaybackRequest
import com.kantv.app.playback.SupportedWebLivePage

/**
 * Deterministic source/candidate ordering shared by the catalogue merge and the playback
 * coordinator, so the stored order and the play order can never disagree.
 *
 * Priority (highest first):
 * 1. **official playable web page** — the source a channel should open on when it has one;
 * 2. **recent stability** — a candidate the health store remembers as healthy beats an unmeasured or
 *    recently unstable one;
 * 3. **trusted quality** — the bounded rank parsed from hub metadata
 *    ([com.kantv.app.playback.HubRoutePlan.qualityRank]); a resolution is never inferred from a URL;
 * 4. **original order** — the stable tie-break. Sources without quality metadata compare equal on
 *    every tier above, so they keep their original relative order.
 *
 * A manual source pick bypasses all of this: every entry point returns the list untouched when the
 * caller pinned one candidate.
 */
object CatalogSourceOrdering {
    /** Official playable sources first, everything else in its original relative order. */
    fun stable(sources: List<CatalogSource>): List<CatalogSource> = sources.withIndex()
        .sortedWith(
            compareBy<IndexedValue<CatalogSource>> { if (it.value.official) 0 else 1 }
                .thenBy { it.index },
        )
        .map { it.value }

    /**
     * Playable official pages are a top-level route choice, ahead of every native candidate.
     */
    fun officialWebFirst(requests: List<PlaybackRequest>): List<PlaybackRequest> = requests.withIndex()
        .sortedWith(
            compareBy<IndexedValue<PlaybackRequest>> {
                val page = SupportedWebLivePage.from(it.value.uri)
                if (page?.officialPlayer == true && page.playbackBlockedMessage == null) 0 else 1
            }.thenBy { it.index },
        )
        .map { it.value }
}
