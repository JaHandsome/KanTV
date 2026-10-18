package com.kantv.app.ui

import com.kantv.app.playback.OfficialWebProvider
import com.kantv.app.playback.SupportedWebLivePage

/**
 * 播放来源选择菜单的显示名（客户端重构 2-4：MainActivity 与 Gecko 宿主共用一份）。
 *
 * 安全约束（与原两处内联实现同口径）：只渲染 provider 中文名或导入来源序号，
 * 绝不渲染地址、token、请求头等传输层细节。导入来源按出现顺序从 1 编号。
 */
internal object ChannelSourceLabels {

    fun labels(uris: List<String>): List<String> {
        var imported = 0
        return uris.map { uri ->
            val page = SupportedWebLivePage.from(uri)
            if (page == null) "导入来源 ${++imported}" else when (page.provider) {
                OfficialWebProvider.CCTV -> "央视官网"
                OfficialWebProvider.YANGSHIPIN -> "央视频"
                OfficialWebProvider.YICAI -> "第一财经官网（可能需要登录）"
                OfficialWebProvider.GDTV -> "广东荔枝网"
                OfficialWebProvider.REGIONAL -> "${page.name}官网"
                else -> "TVB 官网"
            }
        }
    }
}
