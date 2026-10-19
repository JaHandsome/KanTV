package com.kantv.app.catalog

import com.kantv.app.playback.OfficialWebChannelCatalog
import com.kantv.app.playback.OfficialWebProvider
import com.kantv.app.playback.SupportedWebLivePage
import java.net.URI
import java.net.URLDecoder

/** Import-only directory. Playback continues to receive the existing canonical page URLs. */
object OfficialWebsiteCatalog {
    data class Site(val name: String, val homeUrl: String, val provider: OfficialWebProvider) {
        val pages: List<SupportedWebLivePage>
            get() = OfficialWebChannelCatalog.installedPages.filter { it.provider == provider }
    }

    val sites = listOf(
        Site("央视频", "https://www.yangshipin.cn/", OfficialWebProvider.YANGSHIPIN),
        Site("广东荔枝网", "https://www.gdtv.cn/", OfficialWebProvider.GDTV),
        Site("第一财经官网", "https://www.yicai.com/", OfficialWebProvider.YICAI),
    )

    sealed interface Address {
        data class Directory(val site: Site) : Address
        data class Channel(val page: SupportedWebLivePage) : Address
        data class Rejected(val message: String) : Address
        data object Other : Address
    }

    fun resolve(value: String): Address {
        val uri = runCatching { URI(value) }.getOrNull()
            ?: return Address.Rejected("地址格式不正确，请检查后重试")
        val site = sites.firstOrNull { site ->
            val host = URI(site.homeUrl).host
            uri.host.equals(host, true) || uri.host.equals(host.removePrefix("www."), true)
        }
        if (site == null) {
            val legacy = SupportedWebLivePage.from(value) ?: return Address.Other
            return if (legacy.provider == OfficialWebProvider.REGIONAL) {
                Address.Rejected("该官网频道尚未纳入应用已适配目录")
            } else Address.Channel(legacy)
        }
        if (uri.userInfo != null || uri.port !in setOf(-1, 443)) {
            return Address.Rejected("官网地址不能包含账号或异常端口")
        }
        if (uri.scheme != "https") return Address.Rejected("官网地址必须使用 HTTPS")
        val query = queryParameters(uri) ?: return Address.Rejected("地址参数重复或格式不正确，请使用官网频道链接")
        // Only channel identity and inert sharing tags are understood. Unknown parameters may
        // select a video/member page on the site, so never silently turn those into a channel.
        val sharingKeys = setOf("from", "source", "ptag", "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content")
        if (query.keys.any { it !in sharingKeys && !(site.provider == OfficialWebProvider.YANGSHIPIN && it == "pid") }) {
            return unsupported()
        }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val fragment = uri.fragment.orEmpty()
        val page = when (site.provider) {
            OfficialWebProvider.YANGSHIPIN -> {
                if (path !in setOf("", "/tv/home")) return unsupported()
                val pid = query["pid"]
                val marked = site.pages.firstOrNull { URI(it.url).fragment == fragment }
                if (fragment.isNotEmpty() && marked == null) return unsupported()
                if (pid != null) {
                    val identified = site.pages.firstOrNull { it.channelPid == pid } ?: return unsupported()
                    if (marked != null && marked != identified) return Address.Rejected("频道标记与 pid 冲突，请使用同一频道的官网链接")
                    identified
                } else marked
            }
            OfficialWebProvider.GDTV -> {
                if (fragment.isNotEmpty()) return unsupported()
                if (path.isEmpty()) null else site.pages.firstOrNull { URI(it.url).rawPath == path }
                    ?: return unsupported()
            }
            OfficialWebProvider.YICAI -> {
                when {
                    path.isEmpty() && fragment.isEmpty() -> null
                    path == "/tv" && fragment in setOf("", "yicai", "dfcj") ->
                        site.pages.first { it.tvgId == if (fragment == "dfcj") "official-yicai-dfcj" else "official-yicai-main" }
                    else -> return unsupported()
                }
            }
            else -> return unsupported()
        }
        return page?.let(Address::Channel) ?: Address.Directory(site)
    }

    /** Validate the whole selection before the repository starts a transaction. */
    fun canonicalSelection(pages: List<SupportedWebLivePage>): List<SupportedWebLivePage> {
        require(pages.isNotEmpty()) { "EMPTY_OFFICIAL_SELECTION" }
        val installed = OfficialWebChannelCatalog.installedPages.associateBy { it.tvgId }
        return pages.map { page ->
            requireNotNull(installed[page.tvgId]).also { require(it == page) { "NON_CANONICAL_OFFICIAL_PAGE" } }
        }.distinctBy { it.tvgId }
    }

    private fun queryParameters(uri: URI): Map<String, String>? = runCatching {
        val result = linkedMapOf<String, String>()
        uri.rawQuery?.split('&')?.forEach { pair ->
            val parts = pair.split('=', limit = 2)
            val key = URLDecoder.decode(parts[0], "UTF-8")
            val value = URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            require(key.isNotEmpty() && !result.containsKey(key))
            result[key] = value
        }
        result
    }.getOrNull()

    private fun unsupported() = Address.Rejected("该官网链接不在应用已适配频道目录中，请从“选择官网频道”添加；不支持未知频道或会员专享页")
}
