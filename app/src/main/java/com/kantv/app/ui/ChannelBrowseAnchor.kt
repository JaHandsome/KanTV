package com.kantv.app.ui

import com.kantv.app.playback.SupportedWebLivePage

/**
 * 网页播放宿主打开频道浏览时的"当前频道"锚点解析（客户端重构 2-4，纯逻辑）。
 *
 * 替代原网页宿主浏览浮层的内联解析，供 GeckoMirrorLiveActivity 在 IO 协程里调用：
 * 1. 频道 ID 精确命中（ intent 里的 channelId）；
 * 2. tvgId 精确命中（ intent 里的 channelTvgId，缺省回退到官网页自身 tvgId）；
 * 3. tvgId 忽略大小写命中（订阅清单与官网口径大小写不一致的零成本快路）；
 * 4. 逐频道看播放地址对应的官网页 tvgId（命名口径真的不同的兜底，调用方注入取地址函数，
 *    数据库查询由调用方在 IO 调度器上执行）。
 *
 * 解析结果只决定打开落点与"正在观看"高亮，拿不到锚点时浏览从分类页开始。
 */
object ChannelBrowseAnchor {

    suspend fun <T> resolve(
        channels: List<T>,
        currentChannelId: String?,
        currentTvgId: String?,
        idOf: (T) -> String,
        tvgIdOf: (T) -> String?,
        sourceUrisOf: suspend (T) -> List<String>,
    ): T? {
        if (currentChannelId != null) {
            channels.firstOrNull { idOf(it) == currentChannelId }?.let { return it }
        }
        val target = currentTvgId ?: return null
        channels.firstOrNull { tvgIdOf(it) == target }?.let { return it }
        channels.firstOrNull { it -> tvgIdOf(it)?.equals(target, ignoreCase = true) == true }?.let { return it }
        return channels.firstOrNull { channel ->
            sourceUrisOf(channel).any { uri -> SupportedWebLivePage.from(uri)?.tvgId == target }
        }
    }
}
