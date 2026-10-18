package com.kantv.app.ui

import com.kantv.app.catalog.ChannelIdentity

/**
 * 频道浏览（GROUPS/CHANNELS/GUIDE）的纯 Kotlin 导航状态机。
 *
 * 只负责"键位判定 / 页回退链 / 打开落点 / 当前频道解析"的决策，
 * 不依赖 Android View/SDK；宿主拿到 [Decision] / [Retreat] / [Landing]
 * 后执行具体的渲染、焦点与播放动作。
 */
object ChannelBrowseNav {

    /**
     * 判定"长按右键"的 repeatCount 阈值（约 0.5 秒）。
     * 取 4 而不是 1：部分电视遥控器首个方向键事件就带 repeatCount=1。
     * （客户端重构 2-4 起由本状态机持有单一事实来源。）
     */
    const val LONG_PRESS_REPEAT_COUNT = 4

    /** 浏览相关页（与 MainActivity.Screen 中的浏览页一一对应）。 */
    enum class Page { GROUPS, CHANNELS, GUIDE }

    /** 方向键/返回键的语义化输入。 */
    enum class Key { UP, DOWN, LEFT, RIGHT, BACK }

    /**
     * 一次按键的判定结果。
     * CONSUME = 按键被浏览层吃掉但无动作（焦点不在可响应行上）；
     * PASS_THROUGH = 浏览层不拦截，交回系统默认处理。
     */
    enum class Decision {
        MOVE_FOCUS,
        PLAY,
        SHOW_ACTIONS,
        OPEN_FOCUSED_GROUP,
        RETREAT,
        DISMISS,
        CONSUME,
        PASS_THROUGH,
    }

    /** 页回退链的落点。 */
    enum class Retreat { BROWSE_GROUPS, BROWSE_CHANNELS, DISMISS }

    /** 打开浏览的落点：当前频道存在 → 其所在组的 CHANNELS；否则 → GROUPS。 */
    sealed interface Landing {
        data object BrowseGroups : Landing

        data class BrowseChannels(val group: String) : Landing
    }

    /** 已解析出的当前频道信息，目前只承载打开落点所需的组名。 */
    class CurrentChannel(val group: String)

    /** 与原路由同口径：repeatCount 达到长按阈值即为长按。 */
    fun isLongPress(repeatCount: Int): Boolean =
        repeatCount >= LONG_PRESS_REPEAT_COUNT

    /**
     * 键位判定。焦点标记只在对应页参与决策：
     * - CHANNELS 右键：焦点是频道行时短按=播放、长按=操作面板，否则仅吞掉按键
     * - GROUPS 右键：焦点是分组行时进入该组，否则仅吞掉按键
     * - 左键/返回：按页回退链后退；GROUPS 再退 = 离开浏览
     * - GUIDE 只响应返回键；方向键交回系统焦点遍历
     */
    fun decideKey(
        page: Page,
        key: Key,
        repeatCount: Int = 0,
        focusIsChannel: Boolean = false,
        focusIsGroup: Boolean = false,
    ): Decision = when (key) {
        Key.UP, Key.DOWN ->
            if (page == Page.GUIDE) Decision.PASS_THROUGH else Decision.MOVE_FOCUS
        Key.RIGHT -> when (page) {
            Page.CHANNELS -> when {
                !focusIsChannel -> Decision.CONSUME
                isLongPress(repeatCount) -> Decision.SHOW_ACTIONS
                else -> Decision.PLAY
            }
            Page.GROUPS -> if (focusIsGroup) Decision.OPEN_FOCUSED_GROUP else Decision.CONSUME
            Page.GUIDE -> Decision.PASS_THROUGH
        }
        Key.LEFT -> when (page) {
            Page.CHANNELS -> Decision.RETREAT
            Page.GROUPS -> Decision.DISMISS
            Page.GUIDE -> Decision.PASS_THROUGH
        }
        Key.BACK -> when (page) {
            Page.CHANNELS, Page.GUIDE -> Decision.RETREAT
            Page.GROUPS -> Decision.DISMISS
        }
    }

    /** 页回退链：CHANNELS→GROUPS；GUIDE→所选组的 CHANNELS；GROUPS→离开浏览。 */
    fun retreat(page: Page): Retreat = when (page) {
        Page.GROUPS -> Retreat.DISMISS
        Page.CHANNELS -> Retreat.BROWSE_GROUPS
        Page.GUIDE -> Retreat.BROWSE_CHANNELS
    }

    /** 打开浏览落点：有当前频道进其组的 CHANNELS，否则从 GROUPS 开始。 */
    fun landing(current: CurrentChannel?): Landing =
        if (current == null) Landing.BrowseGroups else Landing.BrowseChannels(current.group)

    /**
     * 当前频道判定（与原 MainActivity.currentChannel 同一口径）：
     * 先用 activeChannelId 精确命中；失败后用最近播放的频道按
     * [ChannelIdentity.shouldMerge]（身份优先，双方皆无身份时退回行 ID）在频道表中找同一服务。
     *
     * @param playedIdentity 最近播放频道的有效身份（存储的 identityKey，缺失时由调用方
     *   回落到 ChannelIdentity.knownKey(tvgId, name)）。
     */
    fun <T> resolveCurrentChannel(
        channels: List<T>,
        activeChannelId: String?,
        playedId: String?,
        playedIdentity: String?,
        idOf: (T) -> String,
        identityOf: (T) -> String?,
    ): T? {
        if (activeChannelId != null) {
            channels.firstOrNull { idOf(it) == activeChannelId }?.let { return it }
        }
        if (playedId == null) return null
        return channels.firstOrNull { candidate ->
            ChannelIdentity.shouldMerge(playedId, playedIdentity, idOf(candidate), identityOf(candidate))
        }
    }
}
