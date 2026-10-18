package com.kantv.app.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubConfigTest {
    private val default = "https://hub.example.invalid"

    @Test fun `defaults re-applied when version changed since last apply`() {
        assertTrue(HubConfig.shouldReapplyBundledDefaults(true, 24, 38, default, default))
    }

    @Test fun `defaults not re-applied on same version with valid endpoint`() {
        assertFalse(HubConfig.shouldReapplyBundledDefaults(true, 38, 38, default, default))
    }

    @Test fun `invalid stored endpoint is corrected`() {
        assertTrue(HubConfig.shouldReapplyBundledDefaults(true, 38, 38, "not an endpoint", default))
        assertTrue(HubConfig.shouldReapplyBundledDefaults(true, 38, 38, null, default))
    }

    @Test fun `first launch applies defaults`() {
        assertTrue(HubConfig.shouldReapplyBundledDefaults(false, 0, 38, null, default))
    }

    @Test fun `invalid build default is never written`() {
        assertFalse(HubConfig.shouldReapplyBundledDefaults(false, 0, 38, null, ""))
        assertFalse(HubConfig.shouldReapplyBundledDefaults(true, 38, 38, default, ""))
    }

    @Test fun `changed build endpoint replaces previous endpoint even on same version`() {
        assertTrue(HubConfig.shouldReapplyBundledDefaults(true, 38, 38, "https://old.example.invalid", default))
    }
}
