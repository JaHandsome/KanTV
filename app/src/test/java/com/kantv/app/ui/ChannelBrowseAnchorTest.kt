package com.kantv.app.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChannelBrowseAnchorTest {

    private data class FakeChannel(val id: String, val tvgId: String?, val uris: List<String> = emptyList())

    private fun urisOf(channel: FakeChannel): List<String> = channel.uris

    @Test
    fun channelIdExactMatchWinsEvenWhenLaterThanTvgIdMatch() = runBlocking {
        val byTvg = FakeChannel("a", "official-yicai-main")
        val byId = FakeChannel("b", "other")
        val channels = listOf(byTvg, byId)

        val anchor = ChannelBrowseAnchor.resolve(
            channels = channels,
            currentChannelId = "b",
            currentTvgId = "official-yicai-main",
            idOf = { it.id },
            tvgIdOf = { it.tvgId },
            sourceUrisOf = ::urisOf,
        )

        assertSame(byId, anchor)
    }

    @Test
    fun fallsBackToExactTvgIdMatchWhenIdMisses() = runBlocking {
        val target = FakeChannel("a", "official-yicai-main")
        val channels = listOf(FakeChannel("b", "other"), target)

        val anchor = ChannelBrowseAnchor.resolve(
            channels = channels,
            currentChannelId = "missing",
            currentTvgId = "official-yicai-main",
            idOf = { it.id },
            tvgIdOf = { it.tvgId },
            sourceUrisOf = ::urisOf,
        )

        assertSame(target, anchor)
    }

    @Test
    fun tvgIdMatchIgnoresCase() = runBlocking {
        val target = FakeChannel("a", "Official-Yicai-Main")

        val anchor = ChannelBrowseAnchor.resolve(
            channels = listOf(target),
            currentChannelId = null,
            currentTvgId = "official-yicai-main",
            idOf = { it.id },
            tvgIdOf = { it.tvgId },
            sourceUrisOf = ::urisOf,
        )

        assertSame(target, anchor)
    }

    @Test
    fun resolvesThroughOfficialPageTvgIdOfSourceUri() = runBlocking {
        // 频道行自己的 tvgId 与官网口径不一致，但播放地址是第一财经官网页。
        val target = FakeChannel("a", "legacy-name", listOf("https://www.yicai.com/tv"))
        val other = FakeChannel("b", "other", listOf("https://example.com/live.m3u8"))

        val anchor = ChannelBrowseAnchor.resolve(
            channels = listOf(other, target),
            currentChannelId = null,
            currentTvgId = "official-yicai-main",
            idOf = { it.id },
            tvgIdOf = { it.tvgId },
            sourceUrisOf = ::urisOf,
        )

        assertSame(target, anchor)
    }

    @Test
    fun uriFallbackIsOnlyUsedAfterTvgIdPathsMiss() = runBlocking {
        val viaUri = FakeChannel("a", "x", listOf("https://www.yicai.com/tv"))
        val viaTvg = FakeChannel("b", "official-yicai-main")

        val anchor = ChannelBrowseAnchor.resolve(
            channels = listOf(viaUri, viaTvg),
            currentChannelId = null,
            currentTvgId = "official-yicai-main",
            idOf = { it.id },
            tvgIdOf = { it.tvgId },
            sourceUrisOf = ::urisOf,
        )

        // tvgId 快路优先，不做贵的逐频道地址扫描。
        assertSame(viaTvg, anchor)
    }

    @Test
    fun returnsNullWhenNothingMatchesOrNoTvgIdGiven() = runBlocking {
        val channels = listOf(FakeChannel("a", "x"))

        val noTvgId = ChannelBrowseAnchor.resolve(
            channels = channels,
            currentChannelId = null,
            currentTvgId = null,
            idOf = { it.id },
            tvgIdOf = { it.tvgId },
            sourceUrisOf = ::urisOf,
        )
        val noMatch = ChannelBrowseAnchor.resolve(
            channels = channels,
            currentChannelId = "missing",
            currentTvgId = "unknown-tvg",
            idOf = { it.id },
            tvgIdOf = { it.tvgId },
            sourceUrisOf = ::urisOf,
        )

        assertNull(noTvgId)
        assertNull(noMatch)
    }
}
