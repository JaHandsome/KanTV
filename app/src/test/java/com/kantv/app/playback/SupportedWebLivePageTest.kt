package com.kantv.app.playback

import com.kantv.app.data.StableChannelId
import org.junit.Assert.*
import org.junit.Test

class SupportedWebLivePageTest {
    @Test fun yangshipinCctvPageUsesOfficialPlayer() {
        val page = requireNotNull(SupportedWebLivePage.from("https://www.yangshipin.cn/tv/home?pid=600001817#kantv-cctv5plus"))
        assertTrue(page.officialPlayer)
        assertEquals("https://www.yangshipin.cn/tv/home?pid=600001817#kantv-cctv5plus", page.url)
    }

    @Test fun yangshipinPidDeepLinkSelectsChannelDirectly() {
        // 央视频支持 ?pid= 深链：带 pid 的页面直达目标频道，不再依赖标签点击。
        val cctv11 = requireNotNull(OfficialWebChannelCatalog.pageByTvgId("official-ysp-cctv11"))
        assertEquals("600001806", cctv11.channelPid)
        assertEquals("https://www.yangshipin.cn/tv/home?pid=600001806#kantv-cctv11", cctv11.url)
        // 深链形状与旧形状都解析回同一个页面（页面身份由 fragment 决定，query 不参与）。
        assertSame(cctv11, SupportedWebLivePage.from(cctv11.url))
        assertSame(cctv11, SupportedWebLivePage.from("https://www.yangshipin.cn/tv/home#kantv-cctv11"))
    }

    @Test fun everyYangshipinChannelCarriesAPidDeepLink() {
        // 桌面页频道栏只列 CCTV/CGTN：卫视与 4K/8K 在页面上没有可点标签，
        // 深链是它们的唯一通路——全部央视频条目都必须有 pid。
        val yangshipin = OfficialWebChannelCatalog.pages.filter { it.provider == OfficialWebProvider.YANGSHIPIN }
        assertEquals(60, yangshipin.size)
        assertTrue(yangshipin.all { it.channelPid != null })
        yangshipin.forEach { page ->
            val key = page.tvgId.removePrefix("official-ysp-")
            assertEquals("https://www.yangshipin.cn/tv/home?pid=${page.channelPid}#kantv-$key", page.url)
        }
        // 深链形状与旧 fragment-only 形状都解析回同一个页面（身份由 fragment 决定）。
        val beijing = requireNotNull(OfficialWebChannelCatalog.pageByTvgId("official-ysp-beijing"))
        assertSame(beijing, SupportedWebLivePage.from(beijing.url))
        assertSame(beijing, SupportedWebLivePage.from("https://www.yangshipin.cn/tv/home#kantv-beijing"))
        val xizang = requireNotNull(OfficialWebChannelCatalog.pageByTvgId("official-ysp-xizang"))
        assertEquals("600190403", xizang.channelPid)
        assertSame(xizang, SupportedWebLivePage.from("https://www.yangshipin.cn/tv/home#kantv-xizang"))
    }

    @Test fun pageByTvgIdMapsOfficialChannelIdsToPagesAndRejectsUnknown() {
        // 页面身份 → URL 的映射：内置官网页必须有解，非内置/未知 id 必须拒绝
        // （否则会把预热目标指到不存在的地方）。频道表主键走
        // [OfficialWebChannelCatalog.pageForStoredChannelId]。
        val cctv5 = requireNotNull(OfficialWebChannelCatalog.pageByTvgId("official-ysp-cctv5"))
        assertEquals("https://www.yangshipin.cn/tv/home?pid=600001818#kantv-cctv5", cctv5.url)
        assertEquals(OfficialWebProvider.YANGSHIPIN, cctv5.provider)
        val cctv5plus = requireNotNull(OfficialWebChannelCatalog.pageByTvgId("official-ysp-cctv5plus"))
        assertEquals("https://www.yangshipin.cn/tv/home?pid=600001817#kantv-cctv5plus", cctv5plus.url)
        val gdtv = requireNotNull(OfficialWebChannelCatalog.pageByTvgId("official-gdtv-45"))
        assertEquals(OfficialWebProvider.GDTV, gdtv.provider)
        val yicai = requireNotNull(OfficialWebChannelCatalog.pageByTvgId("official-yicai-main"))
        assertEquals(OfficialWebProvider.YICAI, yicai.provider)
        assertNull(OfficialWebChannelCatalog.pageByTvgId("unknown-channel"))
        assertNull(OfficialWebChannelCatalog.pageByTvgId(null))
        assertNull(OfficialWebChannelCatalog.pageByTvgId("ch_4f16b2b535e99af874b9dd")) // M3U 频道不是官网页
    }

    @Test fun storedStableChannelIdResolvesToTheSameBuiltInPage() {
        // 频道表的主键是 StableChannelId（ch_ 前缀），预热/恢复只拿得到它：
        // 解析复用同一个权威映射，而不是把 tvgId 当作主键。
        val cctv5 = requireNotNull(OfficialWebChannelCatalog.pageByTvgId("official-ysp-cctv5"))
        val storedCctv5 = StableChannelId.create(cctv5.tvgId, null, "")
        assertTrue(storedCctv5.startsWith("ch_"))
        assertSame(cctv5, OfficialWebChannelCatalog.pageForStoredChannelId(storedCctv5))
        // 旧值与 debug 探针写入的是 tvgId，继续可用（含首尾空白）。
        assertSame(cctv5, OfficialWebChannelCatalog.pageForStoredChannelId("official-ysp-cctv5"))
        assertSame(cctv5, OfficialWebChannelCatalog.pageForStoredChannelId("  official-ysp-cctv5 "))
        // 非内置频道的 ch_ 主键不解析为官网页。
        assertNull(OfficialWebChannelCatalog.pageForStoredChannelId(
            StableChannelId.create("custom-m3u-channel", null, "")))
        assertNull(OfficialWebChannelCatalog.pageForStoredChannelId(null))
        assertNull(OfficialWebChannelCatalog.pageForStoredChannelId(""))
        // 不是只对央视系列生效：广东荔枝网等其它内置页同样可解。
        val gdtv = requireNotNull(OfficialWebChannelCatalog.pageByTvgId("official-gdtv-43"))
        assertSame(gdtv, OfficialWebChannelCatalog.pageForStoredChannelId(
            StableChannelId.create(gdtv.tvgId, null, "")))
    }

    @Test fun builtInCatalogContainsEveryApprovedPublicPageOnce() {
        val pages = OfficialWebChannelCatalog.pages
        // 2026-09-15：删除 news.tvb.com 两个已失效页面后为 79。
        assertEquals(79 + RegionalOfficialWebCatalog.pages.size, pages.size)
        assertEquals(pages.size, pages.map { it.tvgId }.toSet().size)
        assertEquals(pages.size, pages.map { it.url }.toSet().size)
        assertEquals(0, pages.count { it.provider == OfficialWebProvider.CCTV })
        assertEquals(18, pages.count { it.groupTitle == "央视频·央视" })
        assertEquals(33, pages.count { it.groupTitle == "央视频·卫视" })
        assertEquals(17, pages.count { it.groupTitle == "广东荔枝网" })
        assertTrue(pages.all { SupportedWebLivePage.from(it.url) == it })
    }

    @Test fun publicPageVariantsSelectTheExpectedFixedChannel() {
        assertEquals("CCTV-1 综合", SupportedWebLivePage.from("https://www.yangshipin.cn/tv/home#kantv-cctv1")?.name)
        assertEquals("北京卫视", SupportedWebLivePage.from("https://www.yangshipin.cn/tv/home#kantv-beijing")?.name)
        assertEquals("东方财经", SupportedWebLivePage.from("https://www.yicai.com/tv#dfcj")?.name)
        assertEquals("广东卫视", SupportedWebLivePage.from("https://www.gdtv.cn/tvChannelDetail/43")?.name)
    }

    @Test fun unsupportedChannelsAndLookalikeHostsAreNotEnabled() {
        listOf("https://tv.cctv.com/live/cctv1/", "https://tv.cctv.com/live/cctv18/", "https://tv.cctv.com.evil.test/live/cctv5plus/",
            "https://tv.cctv.com@evil.test/live/cctv5plus/", "https://tv.cctv.com:444/live/cctv5plus/",
            "https://tv.cctv.com/live/cctv5plus/../../other", "https://www.yangshipin.cn/tv/home#unknown",
            "https://www.gdtv.cn/tvChannelDetail/1016529").forEach { assertNull(SupportedWebLivePage.from(it)) }
        assertFalse(requireNotNull(SupportedWebLivePage.from("https://news.tvb.com/tc/live/T")).officialPlayer)
        assertNull(SupportedWebLivePage.from("https://www.gdtv.cn/tvChannelDetail/42"))
    }

    @Test fun gdtvPagesTargetTheProviderPlayerInsteadOfAnArbitraryVideo() {
        val pages = OfficialWebChannelCatalog.pages.filter { it.provider == OfficialWebProvider.GDTV }

        assertTrue(pages.isNotEmpty())
        pages.forEach { page ->
            assertEquals("#J_prismPlayer", page.playerSelector)
            assertEquals("#J_prismPlayer video", page.videoSelector)
        }
    }

    @Test fun onlyLizhiGdtvPagesRequestNativePlayerChromeSuppression() {
        assertTrue(OfficialWebChannelCatalog.pages
            .filter { it.provider == OfficialWebProvider.GDTV }
            .all { it.suppressesNativePlayerChrome })
        assertTrue(OfficialWebChannelCatalog.pages
            .filter { it.provider != OfficialWebProvider.GDTV }
            .none { it.suppressesNativePlayerChrome })
        // 解析出来的存储页面与目录条目保持同一适配决策。
        assertTrue(requireNotNull(
            SupportedWebLivePage.from("https://www.gdtv.cn/tvChannelDetail/53")).suppressesNativePlayerChrome)
        assertFalse(requireNotNull(
            SupportedWebLivePage.from("https://www.yangshipin.cn/tv/home#kantv-cctv1")).suppressesNativePlayerChrome)
    }
}
