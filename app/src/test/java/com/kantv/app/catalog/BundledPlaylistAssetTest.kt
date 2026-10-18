package com.kantv.app.catalog

import com.kantv.app.data.M3uPlaylistImporter
import com.kantv.app.data.ReadyPlaylistImport
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Private build inputs are inspected in-process; failures never include addresses or headers. */
class BundledPlaylistAssetTest {
    @Test fun localSnapshotIsFullyParseable() {
        val asset = File("src/main/assets/bundled-default/channels.m3u")
        assumeTrue("Private snapshot is supplied only for local acceptance builds", asset.exists())
        val parsed = M3uPlaylistImporter().prepare(asset.readText())
        assertTrue("Snapshot must have a header and channels", parsed is ReadyPlaylistImport)
        assertTrue("Snapshot must have no rejected entries", parsed.summary.rejectedLines == 0)
        assertTrue("Snapshot must have usable non-suppressed channels", (parsed as ReadyPlaylistImport).snapshot.channels.any {
            !ChannelIdentity.isSuppressed(it.tvgId, it.name, it.tvgName)
        })
    }
}
