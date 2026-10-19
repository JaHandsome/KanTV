package com.kantv.app.playback

import java.net.URI

enum class OfficialWebProvider {
    CCTV,
    YANGSHIPIN,
    YICAI,
    GDTV,
    TVB_RESOLVED,
    REGIONAL,
}

data class SupportedWebLivePage(
    val tvgId: String,
    val name: String,
    val url: String,
    val officialPlayer: Boolean,
    val groupTitle: String = "网页直播",
    val provider: OfficialWebProvider = OfficialWebProvider.TVB_RESOLVED,
    val channelLabel: String? = null,
    val channelSelector: String? = null,
    val playerSelector: String = "video",
    val videoSelector: String = "video",
    val playbackBlockedMessage: String? = null,
    /**
     * 央视频 `?pid=` 深链 id：存在时页面直达目标频道，不再依赖标签点击
     * （[channelLabel] 点击只是无 pid 频道的兜底路径）。
     */
    val channelPid: String? = null,
) {
    /**
     * 荔枝网（GDTV）播放页会保留 UA 原生 video controls 与常驻 `:focus` 焦点框，
     * 正常播放时遮挡画面底部。仅该 provider 需要在 Gecko 呈现脚本里做最小隐藏；
     * 其他官网（央视频、第一财经、TVB 等）页面结构不同，保持原样以免误伤。
     */
    val suppressesNativePlayerChrome: Boolean
        get() = provider == OfficialWebProvider.GDTV

    companion object {
        fun from(value: String): SupportedWebLivePage? {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.userInfo != null || uri.port !in setOf(-1, 443)) return null

            OfficialWebChannelCatalog.from(uri)?.let { return it }

            // Compatibility for older, manually imported TVB page IDs. New built-in
            // entries use the official webpage itself and never persist a resolved URL.
            TvbLiveResolver.channelId(value)?.let { id ->
                return SupportedWebLivePage(
                    tvgId = "tvb-web-$id",
                    name = TvbLiveResolver.channelName(id),
                    url = "https://news.tvb.com/tc/live/$id",
                    officialPlayer = false,
                )
            }
            return null
        }
    }
}

/** Stable public page identities only. No media URLs, cookies, tokens, or request headers. */
object OfficialWebChannelCatalog {
    private data class GdtvEntry(val id: Int, val name: String)

    private data class YangshipinEntry(
        val key: String,
        val name: String,
        val group: String,
        val label: String,
        val pid: String?,
    )

    /**
     * 央视频条目（央视 + 高清国际/卫视）的单一声明：key、名称、分组、页面频道标签与
     * `?pid=` 深链 id 合一，取代原先按位置对齐的平行数组/map（插删一项即错位）。
     *
     * pid 深链 id（页面直达频道，不再依赖标签点击）：CCTV/CGTN 取自官方频道列表接口
     * capi.yangshipin.cn/api/oms/m/tvchannel/list；卫视与 4K/8K 频道 id 逐一经验证
     * （深链落地页的 `<title>` 即频道名）。桌面页 /tv/home 的频道栏只列 CCTV/CGTN——
     * 卫视与 4K/8K 在页面上根本没有可点标签，深链是这些频道的唯一通路，所以表必须
     * 覆盖全部央视频条目。缺 pid 的条目退回无 pid 的 `#kantv-` 落地 URL。
     */
    private val yangshipin = listOf(
        YangshipinEntry("cctv1", "CCTV-1 综合", "央视频·央视", "CCTV1", "600001859"),
        YangshipinEntry("cctv2", "CCTV-2 财经", "央视频·央视", "CCTV2", "600001800"),
        YangshipinEntry("cctv3", "CCTV-3 综艺", "央视频·央视", "CCTV3", "600001801"),
        YangshipinEntry("cctv4", "CCTV-4 中文国际", "央视频·央视", "CCTV4", "600001814"),
        YangshipinEntry("cctv5", "CCTV-5 体育", "央视频·央视", "CCTV5", "600001818"),
        YangshipinEntry("cctv5plus", "CCTV-5+ 体育赛事", "央视频·央视", "CCTV5+", "600001817"),
        YangshipinEntry("cctv6", "CCTV-6 电影", "央视频·央视", "CCTV6", "600108442"),
        YangshipinEntry("cctv7", "CCTV-7 国防军事", "央视频·央视", "CCTV7", "600004092"),
        YangshipinEntry("cctv8", "CCTV-8 电视剧", "央视频·央视", "CCTV8", "600001803"),
        YangshipinEntry("cctv9", "CCTV-9 纪录", "央视频·央视", "CCTV9", "600004078"),
        YangshipinEntry("cctv10", "CCTV-10 科教", "央视频·央视", "CCTV10", "600001805"),
        YangshipinEntry("cctv11", "CCTV-11 戏曲", "央视频·央视", "CCTV11", "600001806"),
        YangshipinEntry("cctv12", "CCTV-12 社会与法", "央视频·央视", "CCTV12", "600001807"),
        YangshipinEntry("cctv13", "CCTV-13 新闻", "央视频·央视", "CCTV13", "600001811"),
        YangshipinEntry("cctv14", "CCTV-14 少儿", "央视频·央视", "CCTV14", "600001809"),
        YangshipinEntry("cctv15", "CCTV-15 音乐", "央视频·央视", "CCTV15", "600001815"),
        YangshipinEntry("cctv16", "CCTV-16 奥林匹克", "央视频·央视", "CCTV16", "600098637"),
        YangshipinEntry("cctv17", "CCTV-17 农业农村", "央视频·央视", "CCTV17", "600001810"),
        YangshipinEntry("cctv16-4k", "CCTV-16 4K", "央视频·高清国际", "CCTV16(4K）", "600099502"),
        YangshipinEntry("cctv4k", "CCTV 4K", "央视频·高清国际", "CCTV4K", "600002264"),
        YangshipinEntry("cctv8k", "CCTV 8K", "央视频·高清国际", "CCTV8K", "600156816"),
        YangshipinEntry("cgtn", "CGTN", "央视频·高清国际", "CGTN", "600014550"),
        YangshipinEntry("cgtn-fr", "CGTN 法语频道", "央视频·高清国际", "CGTN法语频道", "600084704"),
        YangshipinEntry("cgtn-ru", "CGTN 俄语频道", "央视频·高清国际", "CGTN俄语频道", "600084758"),
        YangshipinEntry("cgtn-ar", "CGTN 阿拉伯语频道", "央视频·高清国际", "CGTN阿拉伯语频道", "600084782"),
        YangshipinEntry("cgtn-es", "CGTN 西班牙语频道", "央视频·高清国际", "CGTN西班牙语频道", "600084744"),
        YangshipinEntry("cgtn-doc", "CGTN 外语纪录频道", "央视频·高清国际", "CGTN外语纪录频道", "600084781"),
        YangshipinEntry("beijing", "北京卫视", "央视频·卫视", "北京卫视", "600002309"),
        YangshipinEntry("jiangsu", "江苏卫视", "央视频·卫视", "江苏卫视", "600002521"),
        YangshipinEntry("dongfang", "东方卫视", "央视频·卫视", "东方卫视", "600002483"),
        YangshipinEntry("zhejiang", "浙江卫视", "央视频·卫视", "浙江卫视", "600002520"),
        YangshipinEntry("hunan", "湖南卫视", "央视频·卫视", "湖南卫视", "600002475"),
        YangshipinEntry("hubei", "湖北卫视", "央视频·卫视", "湖北卫视", "600002508"),
        YangshipinEntry("guangdong", "广东卫视", "央视频·卫视", "广东卫视", "600002485"),
        YangshipinEntry("guangxi", "广西卫视", "央视频·卫视", "广西卫视", "600002509"),
        YangshipinEntry("heilongjiang", "黑龙江卫视", "央视频·卫视", "黑龙江卫视", "600002498"),
        YangshipinEntry("hainan", "海南卫视", "央视频·卫视", "海南卫视", "600002506"),
        YangshipinEntry("chongqing", "重庆卫视", "央视频·卫视", "重庆卫视", "600002531"),
        YangshipinEntry("shenzhen", "深圳卫视", "央视频·卫视", "深圳卫视", "600002481"),
        YangshipinEntry("sichuan", "四川卫视", "央视频·卫视", "四川卫视", "600002516"),
        YangshipinEntry("henan", "河南卫视", "央视频·卫视", "河南卫视", "600002525"),
        YangshipinEntry("fujian", "福建东南卫视", "央视频·卫视", "福建东南卫视", "600002484"),
        YangshipinEntry("guizhou", "贵州卫视", "央视频·卫视", "贵州卫视", "600002490"),
        YangshipinEntry("jiangxi", "江西卫视", "央视频·卫视", "江西卫视", "600002503"),
        YangshipinEntry("liaoning", "辽宁卫视", "央视频·卫视", "辽宁卫视", "600002505"),
        YangshipinEntry("anhui", "安徽卫视", "央视频·卫视", "安徽卫视", "600002532"),
        YangshipinEntry("hebei", "河北卫视", "央视频·卫视", "河北卫视", "600002493"),
        YangshipinEntry("shandong", "山东卫视", "央视频·卫视", "山东卫视", "600002513"),
        YangshipinEntry("tianjin", "天津卫视", "央视频·卫视", "天津卫视", "600152137"),
        YangshipinEntry("jilin", "吉林卫视", "央视频·卫视", "吉林卫视", "600190405"),
        YangshipinEntry("shaanxi", "陕西卫视", "央视频·卫视", "陕西卫视", "600190400"),
        YangshipinEntry("gansu", "甘肃卫视", "央视频·卫视", "甘肃卫视", "600190408"),
        YangshipinEntry("ningxia", "宁夏卫视", "央视频·卫视", "宁夏卫视", "600190737"),
        YangshipinEntry("neimenggu", "内蒙古卫视", "央视频·卫视", "内蒙古卫视", "600190401"),
        YangshipinEntry("yunnan", "云南卫视", "央视频·卫视", "云南卫视", "600190402"),
        YangshipinEntry("shanxi", "山西卫视", "央视频·卫视", "山西卫视", "600190407"),
        YangshipinEntry("qinghai", "青海卫视", "央视频·卫视", "青海卫视", "600190406"),
        YangshipinEntry("xizang", "西藏卫视", "央视频·卫视", "西藏卫视", "600190403"),
        YangshipinEntry("cetv1", "中国教育电视台 1 频道", "央视频·卫视", "中国教育电视台1频道", "600171827"),
        YangshipinEntry("xinjiang", "新疆卫视", "央视频·卫视", "新疆卫视", "600152138"),
    )

    /**
     * 仅用于升级时剔除旧官方源，不纳入 [pages]，也不会接受新的央视网页面导入。
     * 不含媒体地址、Cookie、Token 或请求头。
     */
    val retiredOfficialUrls: Set<String> = setOf(
        "https://tv.cctv.com/live/cctv1/", "https://tv.cctv.com/live/cctv2/",
        "https://tv.cctv.com/live/cctv3/", "https://tv.cctv.com/live/cctv4/",
        "https://tv.cctv.com/live/cctv5/", "https://tv.cctv.com/live/cctv5plus/",
        "https://tv.cctv.com/live/cctv6/", "https://tv.cctv.com/live/cctv7/",
        "https://tv.cctv.com/live/cctv8/", "https://tv.cctv.com/live/cctvjilu/",
        "https://tv.cctv.com/live/cctv10/", "https://tv.cctv.com/live/cctv11/",
        "https://tv.cctv.com/live/cctv12/", "https://tv.cctv.com/live/cctv13/",
        "https://tv.cctv.com/live/cctvchild/", "https://tv.cctv.com/live/cctv15/",
        "https://tv.cctv.com/live/cctv16/", "https://tv.cctv.com/live/cctv17/",
        "https://tv.cctv.com/live/cctveurope/index.shtml", "https://tv.cctv.com/live/cctvamerica/",
    )

    private val gdtv = listOf(
        GdtvEntry(43, "广东卫视"), GdtvEntry(44, "广东珠江"), GdtvEntry(45, "广东新闻"),
        GdtvEntry(48, "广东民生"), GdtvEntry(47, "广东体育"), GdtvEntry(51, "大湾区卫视"),
        GdtvEntry(46, "大湾区卫视（海外版）"), GdtvEntry(53, "广东影视"), GdtvEntry(16, "4K 超高清"),
        GdtvEntry(54, "广东少儿"), GdtvEntry(66, "嘉佳卡通"),
        GdtvEntry(15, "岭南戏曲"), GdtvEntry(74, "广东移动"), GdtvEntry(100, "广东台经典剧"),
        GdtvEntry(94, "纪录片"), GdtvEntry(99, "健康"), GdtvEntry(102, "GRTN 生活"),
    )

    val pages: List<SupportedWebLivePage> = buildList {
        addAll(RegionalOfficialWebCatalog.pages)
        yangshipin.forEach { entry ->
            val pid = entry.pid
            add(SupportedWebLivePage(
                tvgId = "official-ysp-${entry.key}", name = entry.name,
                url = if (pid != null) {
                    "https://www.yangshipin.cn/tv/home?pid=$pid#kantv-${entry.key}"
                } else {
                    "https://www.yangshipin.cn/tv/home#kantv-${entry.key}"
                },
                officialPlayer = true,
                groupTitle = entry.group, provider = OfficialWebProvider.YANGSHIPIN, channelLabel = entry.label,
                playerSelector = ".video-con", videoSelector = "#myvideo2024078201, .video-con video",
                channelPid = pid,
            ))
        }
        add(SupportedWebLivePage(
            tvgId = "official-yicai-main", name = "第一财经", url = "https://www.yicai.com/tv",
            officialPlayer = true, groupTitle = "第一财经官网", provider = OfficialWebProvider.YICAI,
            channelLabel = "第一财经", playerSelector = ".videobox", videoSelector = "#video",
        ))
        add(SupportedWebLivePage(
            tvgId = "official-yicai-dfcj", name = "东方财经", url = "https://www.yicai.com/tv#dfcj",
            officialPlayer = true, groupTitle = "第一财经官网", provider = OfficialWebProvider.YICAI,
            channelLabel = "东方财经", playerSelector = ".videobox", videoSelector = "#video",
        ))
        gdtv.forEach { entry ->
            add(SupportedWebLivePage(
                tvgId = "official-gdtv-${entry.id}", name = entry.name,
                url = "https://www.gdtv.cn/tvChannelDetail/${entry.id}", officialPlayer = true,
                groupTitle = "广东荔枝网", provider = OfficialWebProvider.GDTV,
                playerSelector = "#J_prismPlayer", videoSelector = "#J_prismPlayer video",
            ))
        }
    }

    /**
     * The normal catalogue uses Yangshipin for the CCTV series and no longer installs CCTV.com
     * pages. Newly collected regional candidates remain parseable through [pages]
     * but stay outside the installed catalogue until they receive separate acceptance.
     * TVB official live pages are retired from installation (2026-09-12): the resolver API
     * only serves video variants to overseas egress; mainland clients get an audio-only
     * master playlist, so the installed channel can never show picture. Pages stay parsed
     * in [pages] so stored URLs still classify as official webpages and never reach Media3.
     */
    val installedPages: List<SupportedWebLivePage> = pages.filter {
        it.provider != OfficialWebProvider.REGIONAL
    }

    fun isInstalled(page: SupportedWebLivePage): Boolean =
        page.provider != OfficialWebProvider.REGIONAL

    private val byYangshipinFragment = pages.filter { it.provider == OfficialWebProvider.YANGSHIPIN }
        .associateBy { URI(it.url).fragment }
    private val byGdtvPath = pages.filter { it.provider == OfficialWebProvider.GDTV }
        .associateBy { URI(it.url).path.trimEnd('/') }

    fun from(uri: URI): SupportedWebLivePage? = when {
        uri.host.equals("www.yangshipin.cn", true) && uri.path.trimEnd('/') == "/tv/home" ->
            byYangshipinFragment[uri.fragment]
        uri.host.equals("www.yicai.com", true) && uri.path.trimEnd('/') == "/tv" -> when (uri.fragment.orEmpty()) {
            "", "yicai" -> pages.first { it.tvgId == "official-yicai-main" }
            "dfcj" -> pages.first { it.tvgId == "official-yicai-dfcj" }
            else -> null
        }
        uri.host.equals("www.gdtv.cn", true) -> byGdtvPath[uri.path.trimEnd('/')]
        else -> RegionalOfficialWebCatalog.from(uri)
    }

    fun containsTvgId(tvgId: String?): Boolean = tvgId != null && pages.any { it.tvgId == tvgId }

    /** 按内置频道的 tvgId 找页面（探针与历史数据用它把页面身份映射成 URL）。 */
    fun pageByTvgId(tvgId: String?): SupportedWebLivePage? =
        tvgId?.let { id -> installedPages.firstOrNull { it.tvgId == id } }

    /**
     * 按「频道表里真正存储的主键」找内置官网页。
     *
     * 频道表的主键是 [com.kantv.app.data.StableChannelId]（`ch_` 前缀，由 CatalogRepository
     * 用 `StableChannelId.create(tvgId, null, "")` 生成），**不**等于 tvgId。预热与恢复只拿得到
     * 这个主键，所以这里两种口径都接受：
     * 1. 先按 tvgId 精确匹配（旧值与 debug 探针写入的就是 tvgId）；
     * 2. 再按权威的 StableChannelId 映射匹配——复用同一个哈希实现，不新造第二份算法。
     *
     * 只在 [installedPages] 内查找：非内置页面不会被识别。
     */
    fun pageForStoredChannelId(storedId: String?): SupportedWebLivePage? {
        val key = storedId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        pageByTvgId(key)?.let { return it }
        return installedPages.firstOrNull {
            com.kantv.app.data.StableChannelId.create(it.tvgId, null, "") == key
        }
    }
}
