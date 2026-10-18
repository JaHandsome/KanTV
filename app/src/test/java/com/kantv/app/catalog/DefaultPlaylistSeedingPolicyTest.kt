package com.kantv.app.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPlaylistSeedingPolicyTest {
    @Test fun `only an empty official-only legacy catalogue may seed`() {
        assertTrue(DefaultPlaylistSeedingPolicy.shouldSeed(null, false, false, false))
        assertFalse(DefaultPlaylistSeedingPolicy.shouldSeed(null, true, false, false))
        assertFalse(DefaultPlaylistSeedingPolicy.shouldSeed(null, false, true, false))
        assertFalse(DefaultPlaylistSeedingPolicy.shouldSeed(null, false, false, true))
    }

    @Test fun `bundled custom and unknown modes never reseed`() {
        assertFalse(DefaultPlaylistSeedingPolicy.shouldSeed(DefaultPlaylistMode.BUNDLED, false, false, false))
        assertFalse(DefaultPlaylistSeedingPolicy.shouldSeed(DefaultPlaylistMode.CUSTOM, false, false, false))
        assertFalse(DefaultPlaylistSeedingPolicy.shouldSeed("FUTURE", false, false, false))
        assertTrue(DefaultPlaylistMode.isSafe(DefaultPlaylistMode.BUNDLED))
        assertTrue(DefaultPlaylistMode.isSafe(DefaultPlaylistMode.CUSTOM))
        assertFalse(DefaultPlaylistMode.isSafe(null))
    }
}
