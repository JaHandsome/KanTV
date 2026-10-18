package com.kantv.app.catalog

import com.kantv.app.playback.OfficialWebChannelCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CatalogBootstrapperTest {
    private class Store : CatalogBootstrapStore {
        override var officialRevision: String? = null
        override var bundledChecked = false
    }

    @Test fun secondLaunchDoesNotSeedReadAssetOrRefreshOfficialAgain() = runTest {
        val store = Store()
        var present = false
        var official = 0
        var bundled = 0
        suspend fun run() = CatalogBootstrapper(store).prepare("same", { present },
            { official++; present = true }, { bundled++ })
        assertFalse(run().reusedLocal)
        assertTrue(run().reusedLocal)
        assertEquals(1, official)
        assertEquals(1, bundled)
    }

    @Test fun changedDirectoryOnlyUpdatesOfficialAndPreservesBundledDecision() = runTest {
        val store = Store().apply { officialRevision = "old"; bundledChecked = true }
        var official = 0
        var bundled = 0
        val result = CatalogBootstrapper(store).prepare("new", { true }, { official++ }, { bundled++ })
        assertEquals(1, official)
        assertEquals(0, bundled)
        assertTrue(result.updatedOfficial)
    }

    @Test fun failedPreparationDoesNotPersistSuccessAndNextLaunchRetries() = runTest {
        val store = Store()
        var threw = false
        try { CatalogBootstrapper(store).prepare("v1", { false }, { error("storage") }, {}) }
        catch (_: IllegalStateException) { threw = true }
        assertTrue(threw)
        assertNull(store.officialRevision)
        assertFalse(store.bundledChecked)
        assertFalse(CatalogBootstrapper(store).prepare("v1", { false }, {}, {}).reusedLocal)
    }

    @Test fun missingDatabaseIsRecoveredEvenWithSavedMarkers() = runTest {
        val store = Store().apply { officialRevision = "v1"; bundledChecked = true }
        var writes = 0
        CatalogBootstrapper(store).prepare("v1", { false }, { writes++ }, { writes++ })
        assertEquals(2, writes)
    }

    @Test fun officialRevisionChangesWhenInstallPolicyChanges() {
        val pages = OfficialWebChannelCatalog.pages
        val current = officialCatalogRevisionDefinition(pages, OfficialWebChannelCatalog::isInstalled)
        val noInstalledPages = officialCatalogRevisionDefinition(pages) { false }

        assertNotEquals(current, noInstalledPages)
    }
}
