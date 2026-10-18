package com.kantv.app.epg

import com.kantv.app.catalog.ChannelIdentity
import com.kantv.app.playback.OfficialWebChannelCatalog
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Optional local evidence test; normal builds do not require a downloaded network snapshot. */
class PublicEpgSnapshotCoverageTest {
    @Test fun reportsCurrentPublicSnapshotCoverage() {
        val path = System.getenv("KANTV_EPG_FIXTURE")
        assumeTrue(!path.isNullOrBlank() && File(path).isFile)
        val channels = OfficialWebChannelCatalog.pages.map { page ->
            EpgChannelRef(
                channelId = page.tvgId,
                tvgId = page.tvgId,
                name = page.name,
                identityKey = ChannelIdentity.knownKey(page.tvgId, page.name),
            )
        }
        val schedules = File(path!!).inputStream().use { XmlTvParser.parse(it, channels) }
        val unmatched = channels.filterNot { schedules.containsKey(it.channelId) }.map { it.name }
        println(
            "EPG_COVERAGE requested=${channels.size} matched=${schedules.size} " +
                "programmes=${schedules.values.sumOf { it.programmes.size }} unmatched=${unmatched.joinToString("|")}",
        )
        assertTrue("public EPG unexpectedly matched fewer than 30 official channels", schedules.size >= 30)
    }
}
