package com.kantv.app.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGuardsTest {
    @Test
    fun suspiciousPartialImportPreservesExistingChannels() {
        assertTrue(CatalogReplacementGuard.shouldPreserveExisting(70, 1, 20))
        assertFalse(CatalogReplacementGuard.shouldPreserveExisting(70, 65, 2))
        assertFalse(CatalogReplacementGuard.shouldPreserveExisting(70, 1, 0))
    }
}
