package com.kantv.app.playback

/**
 * 播放状态文案（用户可见交流）。
 *
 * 用户标准（2026-09-15 定稿）：不得要求用户做任何操作，但要让用户随时知道
 * "正在打开什么 / 卡住了正在自动重试第几次 / 已经恢复了"。
 * 文案平实 + 一句安抚；**只描述用户能理解的事**，不出现内核、镜像、官网等实现细节。
 *
 * 抽成纯函数以便脱离 Android 单测。
 */
internal object PlaybackStatusCopy {

    data class Line(val headline: String, val detail: String)

    /**
     * 等待/恢复中的文案。通道名取自频道列表（用户选的那个名字）。
     *
     * @param channelName 频道名，例如"CCTV-5+ 体育赛事"
     * @param elapsedSeconds 已等待秒数
     * @param recoveryAttempt 第几次自动重试（0 = 尚未重试，仅在首次加载）
     * @param recoveryBudget 总重试预算（仅用于"第 N 次"的展示，不暴露上限给用户）
     */
    fun loading(
        channelName: String,
        elapsedSeconds: Long,
        recoveryAttempt: Int,
        recoveryBudget: Int,
    ): Line {
        val seconds = elapsedSeconds.coerceAtLeast(0L)
        val name = channelName.ifBlank { "频道" }
        return when {
            recoveryAttempt > 0 -> Line(
                headline = "这个频道还没起来，正在自动重试",
                detail = "第 ${recoveryAttempt.coerceAtMost(recoveryBudget.coerceAtLeast(1))} 次尝试" +
                    " · 已等待 ${seconds} 秒",
            )
            seconds < FIRST_HINT_SECONDS -> Line(
                headline = "正在打开 $name",
                detail = "正在加载，请稍候",
            )
            else -> Line(
                headline = "正在打开 $name",
                detail = "已等待 $seconds 秒 · 首次打开会慢一些",
            )
        }
    }

    /** 彻底失败（预算用尽且没有可回退的频道）：只给出口，不给按钮。 */
    fun failed(channelName: String, attempts: Int): Line {
        val name = channelName.ifBlank { "这个频道" }
        return Line(
            headline = "$name 暂时打不开",
            detail = "已自动重试 $attempts 次 · 按返回键换个频道",
        )
    }

    /** 回退到上一个频道时的过渡提示。 */
    fun fallingBack(previousChannelName: String): Line = Line(
        headline = "这个频道暂时不可用",
        detail = "正在回到 ${previousChannelName.ifBlank { "上一个频道" }}",
    )

    /** 网页内核不可用：无兜底内核可退，只说明情况并给出唯一出口。 */
    fun runtimeUnavailable(channelName: String): Line {
        val name = channelName.ifBlank { "这个频道" }
        return Line(
            headline = "$name 暂时无法播放",
            detail = "网页播放组件未能启动 · 按返回键换个频道",
        )
    }

    /** 恢复成功（1.5 秒后自动消失）。 */
    fun recovered(): Line = Line(headline = "已自动恢复", detail = "继续播放")

    /** 超过这个秒数才把"已等待 N 秒"写出来，避免刚进来就报秒数显得焦虑。 */
    const val FIRST_HINT_SECONDS = 10L
}
