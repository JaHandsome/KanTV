package com.kantv.app.epg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpgRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun reusesMemoryAndFreshDiskWithoutAnotherRequest() = runTest {
        val client = FakeClient(mutableListOf(modified()))
        val directory = temporaryFolder.newFolder()
        val channel = EpgChannelRef("c1", "CCTV-1 综合", "CCTV 1")
        val first = repository(directory, client).load(listOf(channel), nowMs = 1_000)
        val secondClient = FakeClient()
        val second = repository(directory, secondClient).load(listOf(channel), nowMs = 2_000)

        assertEquals(EpgSourceState.NETWORK, first.status.state)
        assertEquals(EpgSourceState.CACHE, second.status.state)
        assertEquals(0, secondClient.calls.size)
        assertEquals(1, second.matchStats.matchedChannels)
    }

    @Test fun serialMemoryReuseAvoidsRepeatedFetchAnd304RenewsFreshness() = runTest {
        val client = FakeClient(mutableListOf(modified(), EpgHttpResult.NotModified))
        val repository = repository(temporaryFolder.newFolder(), client, freshForMs = 100)
        val channel = EpgChannelRef("c1", "CCTV-1 综合", "CCTV 1")

        repository.load(listOf(channel), nowMs = 1_000)
        val validated = repository.load(listOf(channel), forceRefresh = true, nowMs = 2_000)
        repository.load(listOf(channel), nowMs = 2_050)

        assertEquals(EpgSourceState.CACHE, validated.status.state)
        assertEquals(2_000L, validated.status.lastSuccessAtMs)
        assertEquals(2, client.calls.size)
    }

    @Test fun fallsBackToSecondSourceAndKeepsStaleScheduleOnLaterFailure() = runTest {
        val client = FakeClient(mutableListOf(
            EpgHttpResult.Failed(EpgFailureCode.NETWORK), modified(),
            EpgHttpResult.Failed(EpgFailureCode.NETWORK), EpgHttpResult.Failed(EpgFailureCode.NETWORK),
        ))
        val repository = repository(temporaryFolder.newFolder(), client)
        val channel = EpgChannelRef("c1", "CCTV-1 综合", "CCTV 1")

        val fallback = repository.load(listOf(channel), nowMs = 1_000)
        val stale = repository.load(listOf(channel), forceRefresh = true, nowMs = 2_000)

        assertEquals(listOf("primary", "fallback", "primary", "fallback"), client.calls.map { it.first })
        assertEquals(EpgSourceState.NETWORK, fallback.status.state)
        assertEquals(EpgSourceState.STALE_CACHE, stale.status.state)
        assertEquals(EpgFailureCode.NETWORK, stale.status.failure)
        assertTrue(stale.schedules.isNotEmpty())
        assertNull(stale.schedules.getValue("c1").programmes.first().catchUpPlayback)
    }

    @Test fun removedSourceCannotKeepServingItsFreshCache() = runTest {
        val directory = temporaryFolder.newFolder()
        val channel = EpgChannelRef("c1", "CCTV-1 综合", "CCTV 1")
        repository(directory, FakeClient(mutableListOf(modified()))).load(listOf(channel), nowMs = 1_000)
        val replacementClient = FakeClient(mutableListOf(modified()))
        val replacement = DefaultEpgRepository(directory, sourceUrls = listOf("replacement"),
            client = replacementClient, ioDispatcher = Dispatchers.Unconfined)
        val result = replacement.load(listOf(channel), nowMs = 2_000)
        assertEquals(EpgSourceState.NETWORK, result.status.state)
        assertEquals(listOf("replacement"), replacementClient.calls.map { it.first })
    }

    @Test fun backupFillsMissingChannelsWithoutOverwritingPrimaryAndKeepsIndependentValidators() = runTest {
        val directory = temporaryFolder.newFolder()
        val cctv1 = EpgChannelRef("c1", "CCTV-1 综合", "CCTV-1 综合")
        val cctv2 = EpgChannelRef("c2", "CCTV-2 财经", "CCTV-2 财经")
        val firstClient = FakeClient(mutableListOf(
            modified(xml("CCTV-2 财经", "主源财经"), "primary-etag"),
            modified(
                xml("CCTV-1 综合", "备用综合", "CCTV-2 财经" to "备用财经"),
                "fallback-etag",
            ),
        ))
        val first = repository(directory, firstClient).load(listOf(cctv1, cctv2), nowMs = 1_000)

        assertEquals("备用综合", first.schedules.getValue("c1").programmes.single().title)
        assertEquals("主源财经", first.schedules.getValue("c2").programmes.single().title)
        assertEquals(listOf("primary", "fallback"), firstClient.calls.map { it.first })

        val validatingClient = FakeClient(mutableListOf(EpgHttpResult.NotModified, EpgHttpResult.NotModified))
        val validated = repository(directory, validatingClient).load(
            listOf(cctv1, cctv2), forceRefresh = true, nowMs = 2_000,
        )
        assertEquals("primary-etag", validatingClient.calls[0].second)
        assertEquals("fallback-etag", validatingClient.calls[1].second)
        assertEquals(2, validated.schedules.size)
        assertEquals(EpgSourceState.CACHE, validated.status.state)
    }

    private fun repository(
        directory: java.io.File,
        client: EpgHttpClient,
        freshForMs: Long = 10_000,
    ) = DefaultEpgRepository(
        cacheDir = directory,
        sourceUrls = listOf("primary", "fallback"),
        client = client,
        ioDispatcher = Dispatchers.Unconfined,
        freshForMs = freshForMs,
    )

    private fun modified(
        content: String = xml(),
        etag: String = "etag",
    ) = EpgHttpResult.Modified(content.toByteArray(), etag, "last-modified")

    private fun xml() = """
        <tv>
          <channel id="CCTV-1 综合"><display-name>CCTV-1 综合</display-name></channel>
          <programme channel="CCTV-1 综合" start="20260910100000 +0800" stop="20260910110000 +0800">
            <title>新闻</title>
          </programme>
        </tv>
    """.trimIndent()

    private fun xml(
        channel: String,
        title: String,
        vararg extraChannels: Pair<String, String>,
    ): String {
        val entries = listOf(channel to title) + extraChannels
        return buildString {
            append("<tv>")
            entries.forEach { (id, _) ->
                append("<channel id=\"").append(id).append("\"><display-name>")
                    .append(id).append("</display-name></channel>")
            }
            entries.forEach { (id, programmeTitle) ->
                append("<programme channel=\"").append(id)
                    .append("\" start=\"20260910100000 +0800\" stop=\"20260910110000 +0800\">")
                    .append("<title>").append(programmeTitle).append("</title></programme>")
            }
            append("</tv>")
        }
    }

    private class FakeClient(
        private val responses: MutableList<EpgHttpResult> = mutableListOf(),
    ) : EpgHttpClient {
        val calls = mutableListOf<Triple<String, String?, String?>>()
        override fun fetch(url: String, etag: String?, lastModified: String?): EpgHttpResult {
            calls += Triple(url, etag, lastModified)
            // Keep JVM 17 unit tests independent of Java 21 List.removeFirst linkage.
            return responses.removeAt(0)
        }
    }
}
