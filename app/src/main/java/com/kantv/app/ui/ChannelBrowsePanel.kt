package com.kantv.app.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.setPadding
import com.kantv.app.catalog.ChannelDirectory
import com.kantv.app.catalog.ChannelEntity
import com.kantv.app.epg.EpgChannelRef
import com.kantv.app.epg.EpgGuide
import com.kantv.app.epg.EpgProgrammeState
import com.kantv.app.epg.EpgSourceState
import com.kantv.app.epg.toEpgChannelRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 频道浏览三页（GROUPS / CHANNELS / GUIDE）的 View 渲染层（客户端重构 2-3b）。
 *
 * 只负责渲染、焦点记忆（focusRowKey / 垂直焦点链 / 最小滚动）与 EPG 行填充；
 * 键位判定在 [ChannelBrowseNav]，播放、换源、操作面板、离开浏览等动作全部经 [Host]
 * 回调交回宿主。视觉规格与焦点行为逐字保留自原 MainActivity 私有实现，零行为变更。
 */
class ChannelBrowsePanel(
    private val activity: Activity,
    private val root: FrameLayout,
    private val ui: ScreenUi,
    private val host: Host,
) {
    /** 渲染层与宿主的通信面：只回调，不直接触碰播放、订阅或导航状态。 */
    interface Host {
        fun channels(): List<ChannelEntity>
        fun currentChannel(): ChannelEntity?
        fun play(channel: ChannelEntity, sourceIndex: Int? = null)
        fun showChannelSources(channel: ChannelEntity)
        fun showGroupChannels(groupTitle: String?)
        fun showSettings()
        fun notifyProgrammeUnavailable(message: String)
        suspend fun loadEpg(refs: List<EpgChannelRef>): EpgGuide

        /** GROUPS 页再退 = 离开浏览（宿主回全屏）。 */
        fun dismissBrowse()
    }

    /** 分组行的焦点标记（GROUPS 页"确认进入该组"的判定依据）。 */
    data class GroupTarget(val title: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var verticalFocusButtons = emptyList<Button>()
    private var verticalFocusScroll: ScrollView? = null
    private var focusRowKey: String? = null
    private var selectedGroupTitle: String? = null
    private var hasBrowsedGroup = false
    private var guideGeneration = 0L
    private var guideChannelId: String? = null
    /** 宿主切走（clearOverlay）时为 true 的反面：用于作废未完成的 GUIDE EPG 填充。 */
    private var showingGuide = false

    /** 所选组（GUIDE 回退到 CHANNELS 的落点；解冻路径也用）。 */
    val currentGroupTitle: String?
        get() = selectedGroupTitle

    /**
     * GROUPS 页。[resetGroupMemory] 由宿主按"进入本页前的屏幕"传入：
     * 从 CHANNELS/GUIDE 回退进来时保留组记忆，从其他页进来时清零。
     */
    fun showGroups(resetGroupMemory: Boolean) {
        if (resetGroupMemory) hasBrowsedGroup = false
        showingGuide = false
        val channels = host.channels()
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(28))
            background = ui.rounded(0xB31A1E25.toInt(), 0)
        }
        panel.addView(ui.title("选择分类"))
        panel.addView(ui.body("每个频道只列一次 · 确认/右键查看频道"))
        if (channels.isEmpty()) {
            panel.addView(ui.body("还没有频道"))
            panel.addView(ui.tvButton("前往设置", onRowFocused = ::rememberRowFocus) { host.showSettings() })
        } else {
            val groups = channels.groupBy { ChannelDirectory.category(it) }
            val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val allButton = ui.tvButton("全部频道    ${channels.size}", onRowFocused = ::rememberRowFocus) {
                host.showGroupChannels(null)
            }
            val groupButtons = mutableListOf(allButton)
            allButton.tag = GroupTarget(null)
            rows.addView(allButton)
            var focusTarget: View? = allButton
            val current = host.currentChannel()
            groups.forEach { (group, groupChannels) ->
                val button = ui.tvButton("$group    ${groupChannels.size}", onRowFocused = ::rememberRowFocus) {
                    host.showGroupChannels(group)
                }
                button.tag = GroupTarget(group)
                if ((hasBrowsedGroup && selectedGroupTitle == group) ||
                    (!hasBrowsedGroup && groupChannels.any { it.id == current?.id })
                ) {
                    focusTarget = button
                }
                rows.addView(button)
                groupButtons += button
            }
            focusRowKey?.takeIf { it.startsWith("group:") }?.let { remembered ->
                focusTarget = groupButtons.firstOrNull { focusKeyOf(it.tag) == remembered }
            }
            val scroll = ScrollView(activity).apply { addView(rows) }
            linkVerticalFocus(groupButtons, scroll)
            panel.addView(scroll, LinearLayout.LayoutParams(matchSize, 0, 1f))
            panel.post {
                focusTarget?.let {
                    it.requestFocus()
                    it.requestRectangleOnScreen(android.graphics.Rect(0, 0, it.width, it.height), false)
                }
            }
        }
        root.addView(panel, FrameLayout.LayoutParams(ui.dp(520), matchSize, Gravity.START))
    }

    /** CHANNELS 页：所选组的频道行 + EPG 行填充（异步，面板被移除时自动作废）。 */
    fun showChannels(groupTitle: String?) {
        showingGuide = false
        selectedGroupTitle = groupTitle
        hasBrowsedGroup = true
        val channels = host.channels()
        val groupChannels = if (groupTitle == null) channels else channels.filter {
            ChannelDirectory.category(it) == groupTitle
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(28))
            background = ui.rounded(0xB31A1E25.toInt(), 0)
        }
        panel.addView(ui.title("${groupTitle ?: "全部频道"} · ${groupChannels.size} 台"))
        panel.addView(ui.body("确认播放 · 左键返回 · 观看中按右键可切换来源"))
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val channelButtons = mutableListOf<Button>()
        var focusTarget: View? = null
        val current = host.currentChannel()
        groupChannels.forEach { channel ->
            val button = ui.tvButton(
                channelRowText(channel.name, null, channel.id == current?.id),
                onRowFocused = ::rememberRowFocus,
            ) { host.play(channel) }.apply {
                layoutParams.height = ui.dp(84)
                maxLines = 2
                includeFontPadding = false
                setLineSpacing(ui.dp(3).toFloat(), 1f)
            }
            button.tag = channel
            if (channel.id == (guideChannelId ?: current?.id)) focusTarget = button
            rows.addView(button)
            channelButtons += button
        }
        focusRowKey?.takeIf { it.startsWith("channel:") }?.let { remembered ->
            focusTarget = channelButtons.firstOrNull { focusKeyOf(it.tag) == remembered }
        }
        val scroll = ScrollView(activity).apply { addView(rows) }
        linkVerticalFocus(channelButtons, scroll)
        panel.addView(scroll, LinearLayout.LayoutParams(matchSize, 0, 1f))
        root.addView(panel, FrameLayout.LayoutParams(ui.channelPanelWidth(), matchSize, Gravity.START))
        panel.post {
            (focusTarget ?: rows.getChildAt(0))?.let {
                it.requestFocus()
                it.requestRectangleOnScreen(android.graphics.Rect(0, 0, it.width, it.height), false)
            }
        }
        scope.launch {
            val guide = host.loadEpg(channels.map { it.toEpgChannelRef() })
            if (!panel.isAttachedToWindow) return@launch
            val now = System.currentTimeMillis()
            channelButtons.zip(groupChannels).forEach { (button, channel) ->
                val schedule = guide.schedules[channel.id]
                val currentProgramme = schedule?.window(now)?.current
                val next = schedule?.window(now)?.next
                val programme = when {
                    currentProgramme != null -> "正在播：${currentProgramme.title}"
                    next != null -> "接下来：${next.title}"
                    !schedule?.programmes.isNullOrEmpty() -> "查看节目单"
                    else -> "暂无节目单"
                }
                button.text = channelRowText(channel.name, programme, channel.id == host.currentChannel()?.id)
                button.maxLines = 2
                button.ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }
    }

    /** GUIDE 页：底部浮窗节目表（EPG 异步填充，换页/重复进入按代际作废）。 */
    fun showGuide(channel: ChannelEntity) {
        guideChannelId = channel.id
        val generation = ++guideGeneration
        showingGuide = true
        val panel = ui.playbackPanel("${channel.name} · 节目表", root)
        val live = ui.tvButton("观看直播") { host.play(channel) }
        panel.addView(live)
        panel.addView(ui.tvButton("选择播放来源") { host.showChannelSources(channel) })
        val message = ui.body("正在加载节目表…直播不受影响。")
        panel.addView(message)
        val programmes = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(programmes)
        panel.addView(ui.tvButton("返回频道") { host.showGroupChannels(selectedGroupTitle) })
        panel.post { live.requestFocus() }
        scope.launch {
            val guide = host.loadEpg(host.channels().map { it.toEpgChannelRef() })
            if (!showingGuide || generation != guideGeneration || !panel.isAttachedToWindow) return@launch
            val schedule = guide.schedules[channel.id]
            val now = System.currentTimeMillis()
            val date = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            message.text = when {
                schedule?.programmes.isNullOrEmpty() -> "暂未找到这个频道的节目单，可以正常观看直播。"
                guide.status.state == EpgSourceState.STALE_CACHE -> "更新暂未成功，显示上次缓存节目单，请以实际播出为准。"
                else -> "节目时间按电视本地时区显示，请以实际播出为准。"
            }
            var currentProgrammeButton: Button? = null
            val canMoveInitialFocus = activity.currentFocus === live
            schedule?.programmes?.forEach { programme ->
                val state = programme.stateAt(now)
                val label = when (state) {
                    EpgProgrammeState.PAST -> "已播 · 暂无回看"
                    EpgProgrammeState.CURRENT -> "正在播出 · 确认看直播"
                    EpgProgrammeState.FUTURE -> "即将播出"
                }
                val text = "${date.format(java.util.Date(programme.startAtMs))} – ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(programme.endAtMs))}\n${programme.title}\n$label"
                programmes.addView(ui.tvButton(text) {
                    val currentState = programme.stateAt(System.currentTimeMillis())
                    if (currentState == EpgProgrammeState.CURRENT) host.play(channel)
                    else host.notifyProgrammeUnavailable(
                        if (currentState == EpgProgrammeState.PAST) "这个节目暂未接入回看资源" else "这个节目尚未播出",
                    )
                }.apply {
                    layoutParams.height = ui.dp(112)
                    textSize = 19f
                    if (state == EpgProgrammeState.CURRENT) currentProgrammeButton = this
                })
            }
            if (canMoveInitialFocus) panel.post { currentProgrammeButton?.requestFocus() }
        }
    }

    /** 上下键焦点移动：焦点优先 + 按需最小滚动（目标已在可视区时列表完全不动）。 */
    fun moveVerticalFocus(offset: Int): Boolean {
        val buttons = verticalFocusButtons
        val currentIndex = buttons.indexOf(activity.currentFocus)
        if (currentIndex < 0) return false
        val target = buttons.getOrNull(currentIndex + offset) ?: return true
        if (!target.isAttachedToWindow) return false
        val scroll = verticalFocusScroll
        if (scroll == null) {
            target.requestFocus()
            return true
        }
        target.requestFocus()
        scroll.post {
            val top = target.top
            val bottom = top + target.height
            when {
                top - ui.dp(5) < scroll.scrollY -> scroll.smoothScrollTo(0, (top - ui.dp(5)).coerceAtLeast(0))
                bottom + ui.dp(5) > scroll.scrollY + scroll.height ->
                    scroll.smoothScrollTo(0, (bottom + ui.dp(5) - scroll.height).coerceAtLeast(0))
                else -> Unit
            }
        }
        return true
    }

    /** 宿主 clearOverlay 时调用：断开垂直焦点链并作废未完成的 GUIDE EPG 填充。 */
    fun onHidden() {
        showingGuide = false
        verticalFocusButtons = emptyList()
        verticalFocusScroll = null
    }

    fun release() = scope.cancel()

    private fun rememberRowFocus(tag: Any?) {
        focusKeyOf(tag)?.let { focusRowKey = it }
    }

    private fun focusKeyOf(tag: Any?): String? = when (tag) {
        is GroupTarget -> "group:${tag.title}"
        is ChannelEntity -> "channel:${tag.id}"
        else -> null
    }

    private fun linkVerticalFocus(buttons: List<Button>, scroll: ScrollView) {
        verticalFocusButtons = buttons
        verticalFocusScroll = scroll
        buttons.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        buttons.forEachIndexed { index, button ->
            if (index > 0) button.nextFocusUpId = buttons[index - 1].id
            if (index < buttons.lastIndex) button.nextFocusDownId = buttons[index + 1].id
        }
    }

    private companion object {
        const val matchSize = ViewGroup.LayoutParams.MATCH_PARENT
    }
}

/**
 * 频道行双行文案：第一行频道名（观看中加"正在观看"前缀），第二行节目单（80% 字号）。
 * 纯文本组装，无 View 依赖。
 */
internal fun channelRowText(name: String, programme: String?, active: Boolean): CharSequence {
    val firstLine = (if (active) "正在观看  " else "") + name
    if (programme.isNullOrBlank()) return firstLine
    val text = android.text.SpannableString("$firstLine\n$programme")
    text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, firstLine.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text.setSpan(android.text.style.RelativeSizeSpan(1.08f), 0, firstLine.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text.setSpan(android.text.style.RelativeSizeSpan(0.80f), firstLine.length + 1, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return text
}
