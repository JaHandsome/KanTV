package com.kantv.app.playback

/**
 * 进程级的「是否正在播放」标志。
 *
 * 用途：契约 §6 要求「客户端在播放中不得应用新订阅」。刷新发生在后台 Worker，
 * 而播放状态在主线程，因此这里用一个极薄的 volatile 标志跨线程传递，不做任何调度。
 * 只表达布尔状态，不含频道名、URL 或任何内容信息。
 */
object PlaybackActivityFlag {
    @Volatile private var active = false

    fun markActive() {
        active = true
    }

    fun markIdle() {
        active = false
    }

    fun isActive(): Boolean = active
}
