package com.kantv.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelNameKeyTest {
    @Test fun `same channel with decoration variants share one key`() {
        val key = ChannelNameKey.canonicalKey("CCTV1")
        assertEquals(key, ChannelNameKey.canonicalKey("CCTV-1"))
        assertEquals(key, ChannelNameKey.canonicalKey("cctv1"))
        assertEquals(key, ChannelNameKey.canonicalKey("CCTV1综合"))
        assertEquals(key, ChannelNameKey.canonicalKey("CCTV1（综合）"))
        assertEquals(key, ChannelNameKey.canonicalKey(" CCTV1 "))
        assertEquals(key, ChannelNameKey.canonicalKey("CCTV1高清"))
    }

    @Test fun `distinct channels never collide`() {
        assertNotEquals(ChannelNameKey.canonicalKey("CCTV5"), ChannelNameKey.canonicalKey("CCTV5+"))
        assertNotEquals(ChannelNameKey.canonicalKey("CCTV1"), ChannelNameKey.canonicalKey("CCTV2"))
        assertNotEquals(ChannelNameKey.canonicalKey("翡翠台"), ChannelNameKey.canonicalKey("明珠台"))
    }

    @Test fun `traditional chinese normalizes to simplified`() {
        assertEquals(ChannelNameKey.canonicalKey("翡翠台"), ChannelNameKey.canonicalKey("翡翠臺"))
        assertEquals(ChannelNameKey.canonicalKey("無線新聞"), ChannelNameKey.canonicalKey("无线新闻"))
    }

    @Test fun `hd variants merge into the base channel`() {
        assertEquals(ChannelNameKey.canonicalKey("翡翠台"), ChannelNameKey.canonicalKey("高清翡翠台"))
        assertEquals(ChannelNameKey.canonicalKey("CCTV5+"), ChannelNameKey.canonicalKey("CCTV5+HD"))
    }

    @Test fun `too short or blank names are excluded from merging`() {
        assertNull(ChannelNameKey.canonicalKey(""))
        assertNull(ChannelNameKey.canonicalKey("   "))
        assertNull(ChannelNameKey.canonicalKey("·"))
    }
}
