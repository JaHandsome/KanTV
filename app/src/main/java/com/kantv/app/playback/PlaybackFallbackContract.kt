package com.kantv.app.playback

/**
 * 播放宿主与主界面之间的"失败回退"契约。
 *
 * 背景（无感标准）：换台后新频道若连续自动恢复失败，用户不该被留在黑屏上，
 * 也不该被丢回频道列表。宿主据此把"回到上一个能看的频道"的请求回传给主界面。
 * 只传频道标识，不含地址或任何凭据。
 */
object PlaybackFallbackContract {
    /** 主界面 → 播放宿主：上一个正在看的频道标识（可能为空）。 */
    const val EXTRA_PREVIOUS_CHANNEL_ID = "previousChannelId"

    /** 播放宿主 → 主界面：请求回退到上一个频道。 */
    const val RESULT_CHANNEL_FALLBACK = "channelFallbackRequested"
}
