package com.kantv.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppUpgradeGuardTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class FakeUpgradeStore(override var lastSeenVersionCode: Int = 0) : AppUpgradeStore

    @Test fun `version increase clears hub prefs and payload file`() {
        val prefs = FakeSharedPreferences()
        val payloadFile = File(temporaryFolder.root, "hub_subscription_payload.json")
        val store = HubSubscriptionStore(SharedPreferencesHubSubscriptionPersistence(prefs, payloadFile))
        store.recordSuccess(PAYLOAD, "\"v24\"", seq = 9L, nowMs = 1_000L, applied = true)
        assertTrue(payloadFile.isFile)
        val upgradeStore = FakeUpgradeStore(lastSeenVersionCode = 24)
        var refreshes = 0

        AppUpgradeGuard(upgradeStore, store, { refreshes++ }, currentVersionCode = 38).run()

        assertTrue(prefs.all.isEmpty())
        assertFalse(payloadFile.exists())
        assertFalse(store.state().hasSuccessfulSubscription)
        assertEquals(38, upgradeStore.lastSeenVersionCode)
        assertEquals(1, refreshes)
    }

    @Test fun `same version is idempotent and forces the refresh exactly once per upgrade`() {
        val persistence = InMemoryHubSubscriptionPersistence()
        val store = HubSubscriptionStore(persistence)
        store.recordSuccess(PAYLOAD, "\"v24\"", seq = 9L, nowMs = 1_000L, applied = true)
        val upgradeStore = FakeUpgradeStore(lastSeenVersionCode = 24)
        var refreshes = 0
        AppUpgradeGuard(upgradeStore, store, { refreshes++ }, currentVersionCode = 38).run()

        // 模拟进程重启后的第二次启动：共享同一个持久化水位。
        AppUpgradeGuard(upgradeStore, store, { refreshes++ }, currentVersionCode = 38).run()

        assertEquals(1, refreshes)
        assertEquals(38, upgradeStore.lastSeenVersionCode)
        assertNull(persistence.read())
    }

    @Test fun `downgrade neither clears state nor refreshes and keeps the higher watermark`() {
        val persistence = InMemoryHubSubscriptionPersistence()
        val store = HubSubscriptionStore(persistence)
        store.recordSuccess(PAYLOAD, "\"v38\"", seq = 10L, nowMs = 1_000L, applied = true)
        val upgradeStore = FakeUpgradeStore(lastSeenVersionCode = 38)
        var refreshes = 0

        AppUpgradeGuard(upgradeStore, store, { refreshes++ }, currentVersionCode = 24).run()

        assertTrue(store.state().hasSuccessfulSubscription)
        assertEquals("\"v38\"", store.state().etag)
        assertEquals(0, refreshes)
        assertEquals(38, upgradeStore.lastSeenVersionCode)
    }

    private companion object {
        val PAYLOAD = """{"hub_schema":1,"seq":9,"channels":[]}"""
    }
}
