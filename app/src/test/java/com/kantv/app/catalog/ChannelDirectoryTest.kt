package com.kantv.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelDirectoryTest {
    @Test fun categoriesFollowChannelNotProvider() {
        assertEquals("卫视", ChannelDirectory.category("广东卫视", "广东荔枝网"))
        assertEquals("卫视", ChannelDirectory.category("广东卫视", "央视频·卫视"))
        assertEquals("央视", ChannelDirectory.category("CCTV-2 财经", "导入"))
        assertEquals("港澳台", ChannelDirectory.category("TVB 财经频道", "无綫新闻"))
        assertEquals("体育频道", ChannelDirectory.category("CCTV-5", "导入"))
        assertEquals("体育频道", ChannelDirectory.category("CCTV-5+", "导入"))
        assertEquals("体育频道", ChannelDirectory.category("CCTV-16 奥林匹克", "导入"))
        assertEquals("体育频道", ChannelDirectory.category("广东体育", "广东荔枝网"))
        assertEquals("新闻频道", ChannelDirectory.category("无线新闻", "无綫新闻"))
        assertEquals("国际", ChannelDirectory.category("CGTN 法语频道", "央视频"))
        assertEquals("广东地方", ChannelDirectory.category("广东珠江", "导入"))
        assertEquals("少儿频道", ChannelDirectory.category("卡酷少儿", "导入"))
        assertEquals("少儿频道", ChannelDirectory.category("嘉佳卡通", "广东荔枝网"))
        assertEquals("少儿频道", ChannelDirectory.category("CCTV-14", "导入"))
        assertEquals("新闻频道", ChannelDirectory.category("CCTV-13", "导入"))
        assertEquals("教育", ChannelDirectory.category("中国教育电视台 1 频道", "央视频·卫视"))
        assertEquals("未分类", ChannelDirectory.category("未识别频道", null))
    }

    @Test fun categoryOrderPrecedesCctvNumberAndSportsKeepsPlusSeparate() {
        val channels = listOf("CCTV-10", "CCTV-5+", "CCTV-2", "CCTV-5").mapIndexed { i, name ->
            ChannelEntity("$i", i, null, name, null, null, "")
        }
        assertEquals(listOf("CCTV-2", "CCTV-10", "CCTV-5", "CCTV-5+"), ChannelDirectory.ordered(channels).map { it.name })
    }

    @Test fun financeChannelsStayWithTheirWebsiteOrBroadcaster() {
        assertEquals("第一财经官网", ChannelDirectory.category("第一财经", "财经直播"))
        assertEquals("第一财经官网", ChannelDirectory.category("东方财经", "导入"))
        assertEquals("央视", ChannelDirectory.category("CCTV-2 财经", "央视频·央视"))
        assertEquals("港澳台", ChannelDirectory.category("TVB 財經頻道", "港澳台"))
    }

    @Test fun websiteGroupReplacesTheSeparateFinanceCategory() {
        assertEquals(
            listOf("央视", "卫视", "第一财经官网", "体育频道", "少儿频道", "新闻频道", "广东地方", "港澳台", "国际", "教育", "其他地方", "未分类"),
            ChannelDirectory.categories,
        )
    }
}
