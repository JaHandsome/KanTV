package com.kantv.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSourceLabelsTest {

    @Test
    fun officialPageUriGetsProviderNameWithoutAnyTransportDetail() {
        val uri = "https://www.yicai.com/tv"

        val labels = ChannelSourceLabels.labels(listOf(uri))

        assertEquals(1, labels.size)
        assertTrue(labels[0].contains("第一财经"))
        assertFalse(labels[0].contains(uri))
    }

    @Test
    fun importedAddressesAreNumberedInOrderRegardlessOfPosition() {
        val uris = listOf(
            "https://example.com/a/playlist.m3u8",
            "https://www.yicai.com/tv",
            "http://192.168.0.1:8000/secret-token/stream",
        )

        val labels = ChannelSourceLabels.labels(uris)

        assertEquals(3, labels.size)
        assertEquals("导入来源 1", labels[0])
        assertEquals("导入来源 2", labels[2])
        // 安全口径：任何标签都不得回显原始地址（含内网地址与 token 参数）。
        labels.forEachIndexed { index, label ->
            assertFalse("label $index leaks uri", label.contains(uris[index]))
        }
    }
}
