package com.kantv.app.ui

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding

/**
 * 主界面各 Screen 页共用的纯 View 渲染助手（客户端重构 2-3b，唯一实现）。
 *
 * 视觉规格（方角面板、标题/正文字号、按钮字号与焦点反馈、频道面板宽度公式）
 * 逐字保留自原 MainActivity 私有助手；本类只是把实现搬成一份，供宿主与
 * [ChannelBrowsePanel] 共同引用，不做任何视觉调整。
 */
class ScreenUi(private val activity: Activity) {

    fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    fun pagePanel(gravity: Int = Gravity.START): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
        setPadding(dp(72), dp(48), dp(72), dp(48))
        background = rounded(0xB3101216.toInt(), 0)
    }

    /** 底部浮窗面板（TRACKS/STREAM_INFO/GUIDE 共用），自己包一层 ScrollView 并挂到 [root]。 */
    fun playbackPanel(heading: String, root: FrameLayout): LinearLayout {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24))
            background = rounded(0xE61A1E25.toInt(), dp(18))
        }
        panel.addView(title(heading))
        val scroll = ScrollView(activity).apply { addView(panel) }
        val width = activity.resources.displayMetrics.widthPixels - dp(32)
        val height = (activity.resources.displayMetrics.heightPixels * 0.58f).toInt()
        root.addView(scroll, FrameLayout.LayoutParams(width, height, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(16)
        })
        return panel
    }

    fun title(text: String) = TextView(activity).apply {
        this.text = text
        setTextColor(WHITE)
        textSize = 34f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(20))
    }

    fun body(text: String) = TextView(activity).apply {
        this.text = text
        setTextColor(0xFFD5DAE1.toInt())
        textSize = 21f
        setPadding(0, 0, 0, dp(18))
    }

    /**
     * 主界面统一按钮。[onRowFocused] 供浏览层做焦点行记忆（聚焦时回传 view.tag），
     * 其余页面不传即可——与原实现一致，无 tag 的按钮不会触发记忆。
     */
    fun tvButton(
        label: CharSequence,
        onRowFocused: ((tag: Any?) -> Unit)? = null,
        action: () -> Unit,
    ): Button = Button(activity).apply {
        text = label
        textSize = 21f
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(WHITE)
        isFocusable = true
        background = rounded(0x99252B34.toInt(), 12)
        setPadding(dp(22), 0, dp(22), 0)
        setOnClickListener { action() }
        setOnFocusChangeListener { view, focused ->
            if (focused) onRowFocused?.invoke(view.tag)
            view.background = rounded(if (focused) 0xFF54B7F8.toInt() else 0x99252B34.toInt(), 12)
            (view as Button).setTextColor(if (focused) 0xFF000000.toInt() else WHITE)
            view.animate().scaleX(if (focused) 1.025f else 1f).scaleY(if (focused) 1.025f else 1f).setDuration(90).start()
        }
        layoutParams = LinearLayout.LayoutParams(matchSize, dp(66)).apply { setMargins(0, dp(5), 0, dp(5)) }
    }

    /** 频道列表面板宽度公式（屏宽 42%，夹在 540dp 与 min(720dp, 屏宽) 之间）。 */
    fun channelPanelWidth(): Int {
        val available = activity.resources.displayMetrics.widthPixels
        return (available * 0.42f).toInt().coerceIn(dp(540), minOf(dp(720), available))
    }

    private companion object {
        const val matchSize = ViewGroup.LayoutParams.MATCH_PARENT
        const val WHITE = 0xFFF5F7FA.toInt()
    }
}
