package com.kantv.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubConditionalValidatorTest {
    @Test fun controlCharactersNeverBecomeTheNextIfNoneMatchOrStoredEtag() {
        val seen = mutableListOf<String?>()
        val transport = object : HubHttpTransport {
            override fun get(uri: String, ifNoneMatch: String?): HubHttpResponse {
                seen += ifNoneMatch
                return HubHttpResponse(
                    code = 200,
                    etag = "bad\r\nX-Injected: 1",
                    contentType = "application/json",
                    body = """{"hub_schema":2,"channels":[]}""".toByteArray(),
                )
            }
        }
        val client = HubSubscriptionClient(
            endpoint = "http://hub.example",
            identity = HubClientIdentity(app = "0.2.41", device = "test"),
            transport = transport,
        )

        val poisoned = client.fetch("stored\nbad")
        val clean = client.fetch("\"ok\"")

        assertTrue(poisoned is HubFetchOutcome.Fetched)
        assertNull((poisoned as HubFetchOutcome.Fetched).etag)
        assertTrue(clean is HubFetchOutcome.Fetched)
        assertEquals(listOf(null, "\"ok\""), seen)
    }
}
