package com.kantv.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

internal data class PlaybackMenuAction(val label: String, val action: () -> Unit)
internal enum class PlaybackMenuPlacement { CENTER, BOTTOM }

/**
 * 通用操作菜单浮层（客户端重构 2-4 起为唯一实现，原网页宿主副本已删除）。
 *
 * A 套（MainActivity 操作面板/来源选择）与 Gecko 网页宿主共用这一份实现；
 * 只创建 View-tree 浮层，从不创建 Dialog 窗口。视觉与键行为与原实现逐字一致。
 */
internal class PlaybackMenuOverlay(
    private val activity: AppCompatActivity,
    private val host: FrameLayout,
) {
    private var root: View? = null
    val isShowing: Boolean get() = root?.isAttachedToWindow == true

    fun show(
        heading: String,
        message: String? = null,
        actions: List<PlaybackMenuAction>,
        placement: PlaybackMenuPlacement = PlaybackMenuPlacement.CENTER,
    ) {
        dismiss()
        val container = FrameLayout(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root = container
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = rounded(0xB31A1E25.toInt(), dp(12))
            addView(text(heading, 30f).apply { setTextColor(Color.WHITE) })
            message?.let { addView(text(it, 18f).apply { setTextColor(0xFFD2D7DE.toInt()) }) }
        }
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val buttons = actions.map { item ->
            button(item.label) {
                dismiss()
                item.action()
            }.also(rows::addView)
        }
        panel.addView(ScrollView(activity).apply { addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val metrics = activity.resources.displayMetrics
        val bottom = placement == PlaybackMenuPlacement.BOTTOM
        val width = if (bottom) metrics.widthPixels - dp(32) else minOf(dp(620), metrics.widthPixels - dp(48))
        val height = (metrics.heightPixels * if (bottom) 0.58f else 0.80f).toInt()
        val gravity = if (bottom) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        container.addView(panel, FrameLayout.LayoutParams(width, height, gravity).apply {
            if (bottom) bottomMargin = dp(16)
        })
        host.addView(container, FrameLayout.LayoutParams(-1, -1))
        panel.post { buttons.firstOrNull()?.requestFocus() }
    }

    fun dismiss() {
        root?.let { if (it.parent === host) host.removeView(it) }
        root = null
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (!isShowing || event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT -> { dismiss(); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val buttons = mutableListOf<Button>()
                fun collect(view: View) {
                    if (view is Button && view.isEnabled) buttons += view
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                root?.let(::collect)
                val current = buttons.indexOf(activity.currentFocus)
                val next = buttons.getOrNull(if (current < 0) 0 else current + if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                next?.let {
                    it.requestFocus()
                    it.requestRectangleOnScreen(android.graphics.Rect(0, 0, it.width, it.height), false)
                }
                true
            }
            else -> false
        }
    }

    private fun button(label: String, action: () -> Unit) = Button(activity).apply {
        text = label
        textSize = 21f
        gravity = Gravity.CENTER
        isAllCaps = false
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(24), 0, dp(24), 0)
        layoutParams = LinearLayout.LayoutParams(-1, dp(66)).apply { setMargins(0, dp(5), 0, dp(5)) }
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(0xFF54B7F8.toInt(), dp(10)))
            addState(intArrayOf(), rounded(0x99252B34.toInt(), dp(10)))
        }
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.BLACK, Color.WHITE)))
        setOnClickListener { action() }
    }

    private fun text(value: String, size: Float) = android.widget.TextView(activity).apply {
        text = value
        textSize = size
        setPadding(0, dp(6), 0, dp(10))
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
