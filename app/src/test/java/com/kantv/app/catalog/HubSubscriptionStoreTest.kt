package com.kantv.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HubSubscriptionStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test fun `legacy inline payload is migrated to the payload file on first read`() {
        // v24 时代的落盘形状：payload 以内联键放在 prefs 里，尚无 payload 文件。
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putString("status", HubSubscriptionStatus.SUCCESS)
            .putString("etag", "\"v24\"")
            .putLong("seq", 9L)
            .putLong("last_attempt_at_ms", 100L)
            .putLong("last_success_at_ms", 100L)
            .putLong("last_applied_seq", 9L)
            .putString("payload", LEGACY_PAYLOAD)
            .commit()
        val payloadFile = File(temporaryFolder.root, "hub_subscription_payload.json")

        val state = SharedPreferencesHubSubscriptionPersistence(prefs, payloadFile).read()!!

        assertEquals(LEGACY_PAYLOAD, state.payload)
        assertEquals("\"v24\"", state.etag)
        assertEquals(9L, state.seq)
        assertTrue(state.hasSuccessfulSubscription)
        assertTrue(payloadFile.isFile)
        assertEquals(LEGACY_PAYLOAD, payloadFile.readText(Charsets.UTF_8))
        assertFalse("legacy inline key must be removed after migration", prefs.contains("payload"))
    }

    @Test fun `clear removes the prefs keys and deletes the payload file`() {
        val prefs = FakeSharedPreferences()
        val payloadFile = File(temporaryFolder.root, "hub_subscription_payload.json")
        val store = HubSubscriptionStore(SharedPreferencesHubSubscriptionPersistence(prefs, payloadFile))
        store.recordSuccess(LEGACY_PAYLOAD, "\"v24\"", seq = 9L, nowMs = 1_000L, applied = true)
        assertTrue(payloadFile.isFile)
        assertTrue(prefs.contains("status"))

        store.clear()

        assertTrue(prefs.all.isEmpty())
        assertFalse(payloadFile.exists())
        val state = store.state()
        assertFalse(state.hasSuccessfulSubscription)
        assertNull(state.etag)
        assertEquals(HubSubscriptionStatus.NEVER_FETCHED, state.status)
    }

    @Test fun `payload file takes precedence over a leftover legacy inline key`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("status", HubSubscriptionStatus.SUCCESS).putString("payload", LEGACY_PAYLOAD).commit()
        val payloadFile = File(temporaryFolder.root, "hub_subscription_payload.json")
        payloadFile.writeText("""{"hub_schema":1,"seq":10}""", Charsets.UTF_8)

        val state = SharedPreferencesHubSubscriptionPersistence(prefs, payloadFile).read()!!

        assertEquals("""{"hub_schema":1,"seq":10}""", state.payload)
        assertEquals("""{"hub_schema":1,"seq":10}""", payloadFile.readText(Charsets.UTF_8))
        assertFalse(prefs.contains("payload"))
    }

    @Test fun `reader instance sees payload written by another instance of the same persistence`() {
        val prefs = FakeSharedPreferences()
        val payloadFile = File(temporaryFolder.root, "hub_subscription_payload.json")
        val reader = HubSubscriptionStore(SharedPreferencesHubSubscriptionPersistence(prefs, payloadFile))
        val writer = HubSubscriptionStore(SharedPreferencesHubSubscriptionPersistence(prefs, payloadFile))

        reader.recordSuccess(LEGACY_PAYLOAD, "\"v24\"", seq = 9L, nowMs = 1_000L, applied = true)
        assertEquals(LEGACY_PAYLOAD, reader.state().payload)

        val refreshedPayload = """
            {"hub_schema":1,"seq":10,"generated_at":"2026-08-01T01:00:00+08:00","channels":[]}
        """.trimIndent()
        writer.recordSuccess(refreshedPayload, "\"v25\"", seq = 10L, nowMs = 2_000L, applied = true)

        val reread = reader.state()
        assertEquals("reader must observe the payload written by the writer instance", refreshedPayload, reread.payload)
        assertEquals(10L, reread.seq)
        assertEquals(refreshedPayload, payloadFile.readText(Charsets.UTF_8))
    }

    @Test fun `signed params fingerprint persists across store instances`() {
        val prefs = FakeSharedPreferences()
        val payloadFile = File(temporaryFolder.root, "hub_subscription_payload.json")
        val writer = HubSubscriptionStore(SharedPreferencesHubSubscriptionPersistence(prefs, payloadFile))
        val reader = HubSubscriptionStore(SharedPreferencesHubSubscriptionPersistence(prefs, payloadFile))

        // 旧版服务端未投递指纹：recordSuccess 不得清掉既有锚点
        writer.recordSuccess(LEGACY_PAYLOAD, "\"v24\"", seq = 9L, nowMs = 1_000L, applied = true)
        assertNull(reader.state().signedParamsFingerprint)
        writer.recordSuccess(LEGACY_PAYLOAD, "\"v24\"", seq = 9L, nowMs = 2_000L, applied = true,
            signedParamsFingerprint = "aaa111")
        writer.recordSuccess(LEGACY_PAYLOAD, "\"v24\"", seq = 9L, nowMs = 3_000L, applied = true)

        val reread = reader.state()
        assertEquals("aaa111", reread.signedParamsFingerprint)
        assertTrue("prefs 必须持久化指纹锚点", prefs.contains("signed_params_fingerprint"))

        writer.clear()
        assertNull(reader.state().signedParamsFingerprint)
    }

    private companion object {
        /** 脱敏 fixture：形状与 v24 payload 一致，不含任何真实流地址。 */
        val LEGACY_PAYLOAD = """
            {"hub_schema":1,"seq":9,"generated_at":"2026-08-01T00:00:00+08:00","channels":[]}
        """.trimIndent()
    }
}
