package com.kantv.app.playback

/**
 * "本次启动：页面起点 → 首帧"耗时，只用于本地调试埋点，不上报。
 *
 * 两个时间戳必须属于**同一次启动**：任一时戳未记录、或首帧时戳早于启动起点（上一轮
 * session 的陈旧证据），都返回 -1 表示"本次启动尚未确认出画"，而不是把差值硬算出来。
 */
internal fun startupFirstFrameElapsedMs(startupPageStartedAtMs: Long, startupFirstFrameAtMs: Long): Long =
    if (startupPageStartedAtMs > 0L && startupFirstFrameAtMs >= startupPageStartedAtMs) {
        startupFirstFrameAtMs - startupPageStartedAtMs
    } else {
        -1L
    }
