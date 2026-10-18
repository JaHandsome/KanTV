package com.kantv.app.playback

/**
 * 原生页（NATIVE_PAGE）诊断证据的站点门控（从 GeckoMirrorLiveActivity 抽出的纯谓词）。
 *
 * 两条站点纪律：
 * 1. **"频道从未被选中"证据只喂央视频**：央视频 SPA 有"页面加载完但点选没生效"
 *    的失败形态（`readyState==0 && currentTime==0` 持续）。其它官网的 `readyState=0`
 *    可能是正常等待态（如第一财经登录流程，未登录时页面里根本没有播放器节点），
 *    喂进去会把正常等待误判成选台失败。
 * 2. **pid 深链页面不装点击循环**：深链（`channelPid`）已是权威选择，再按 label
 *    模糊点击反而可能切错频道；只有无 pid 的频道才保留 label 点击兜底。
 */
internal object NativePageEvidenceGate {
    /** 是否应把"从未选中"证据喂给进度看门狗。 */
    fun shouldFeedNeverSelectedEvidence(provider: OfficialWebProvider): Boolean =
        provider == OfficialWebProvider.YANGSHIPIN

    /** 页面准备脚本的选台点击 label；pid 深链页返回 null（不装点击循环）。 */
    fun clickLoopLabel(channelLabel: String?, channelPid: String?): String? =
        channelLabel?.takeIf { channelPid == null }
    /** With identity, readiness changes are buffering facts, not new startup windows. */
    fun shouldResetPlayerStartup(
        hasIdentity: Boolean,
        identityChanged: Boolean,
        legacyReplacement: Boolean,
    ): Boolean = if (hasIdentity) identityChanged else legacyReplacement
}
