package com.kantv.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 播放状态的**用户可见交流**层。
 *
 * 设计来由（2026-09-15 用户反馈）：原先只有"黑屏 + 底部一行小字"，用户看到黑屏只会认为坏了。
 * 用户的要求是"无感"：**不要求用户做任何操作**，但必须让用户知道 app 正在做什么、进展到哪一步。
 *
 * 因此本视图：
 * - 等待期显示**转圈 + 分级文案**（正在打开 X / 正在自动重试 第 N 次 / 已等待 N 秒）；
 * - 恢复成功显示一次"已自动恢复"，1.5 秒后自动消失（仅当真的发生过恢复，正常起播不打扰）；
 * - **不提供任何按钮**：出口只有遥控器的返回键（用户已确认不要"重试"按钮）；
 * - 画面一就绪立刻隐藏，不遮挡观看。
 *
 * 文案只说用户能理解的事，不出现内核/镜像/官网等实现细节。
 */
internal class PlaybackStatusView(context: Context) {

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        visibility = View.GONE
        setPadding(dp(context, 28), dp(context, 22), dp(context, 28), dp(context, 22))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xCC101216.toInt())
            cornerRadius = dp(context, 14).toFloat()
        }
    }

    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyle).apply {
        indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
    }

    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 24f
        gravity = Gravity.CENTER
        maxLines = 2
        setPadding(0, dp(context, 10), 0, 0)
    }

    private val subtitle = TextView(context).apply {
        setTextColor(0xFFB9C2CC.toInt())
        textSize = 17f
        gravity = Gravity.CENTER
        maxLines = 2
        setPadding(0, dp(context, 6), 0, 0)
    }

    private val hideRunnable = Runnable { hide() }

    init {
        root.addView(spinner, LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)))
        root.addView(title)
        root.addView(subtitle)
    }

    /** 挂到宿主容器（居中偏上，避免遮住底部字幕）。 */
    fun attachTo(container: FrameLayout) {
        if (root.parent === container) return
        (root.parent as? FrameLayout)?.removeView(root)
        val width = (container.resources.displayMetrics.widthPixels * CARD_WIDTH_RATIO).toInt()
        container.addView(
            root,
            FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                .apply { bottomMargin = dp(container.context, 40); topMargin = dp(container.context, 40) },
        )
    }

    /** 正在加载/正在自动重试。 */
    fun showLoading(headline: String, detail: String) {
        root.removeCallbacks(hideRunnable)
        spinner.visibility = View.VISIBLE
        title.text = headline
        subtitle.text = detail
        root.visibility = View.VISIBLE
        root.bringToFront()
    }

    /** 恢复成功：只给一次确认，1.5 秒后自动消失。 */
    fun showRecovered(headline: String = "已自动恢复", detail: String = "继续播放") {
        root.removeCallbacks(hideRunnable)
        spinner.visibility = View.INVISIBLE
        title.text = headline
        subtitle.text = detail
        root.visibility = View.VISIBLE
        root.bringToFront()
        root.postDelayed(hideRunnable, RECOVERED_HIDE_DELAY_MS)
    }

    /** 画面就绪 / 用户离开：立即隐藏，不打扰观看。 */
    fun hide() {
        root.removeCallbacks(hideRunnable)
        root.visibility = View.GONE
    }



    private companion object {
        const val RECOVERED_HIDE_DELAY_MS = 1_500L
        /** 卡片占屏宽比例：太窄会把中文挤成一列（实测踩过）。 */
        const val CARD_WIDTH_RATIO = 0.72f
        fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }
}
