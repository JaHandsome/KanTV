package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原生页诊断证据站点门控的行为契约（重建 GeckoMirrorLifecycleWiringTest 的
 * "neverSelected 证据只喂央视频页 / pid 深链页不装点击循环"覆盖）。
 */
class NativePageEvidenceGateTest {
    @Test
    fun neverSelectedEvidenceIsOnlyFedForYangshipinPages() {
        assertTrue(NativePageEvidenceGate.shouldFeedNeverSelectedEvidence(OfficialWebProvider.YANGSHIPIN))
        // 其它官网的 readyState=0 可能是正常等待态（第一财经登录流程），不得喂"从未选中"证据。
        assertFalse(NativePageEvidenceGate.shouldFeedNeverSelectedEvidence(OfficialWebProvider.YICAI))
    }

    @Test
    fun clickLoopIsInstalledOnlyForLabelChannelsWithoutPidDeepLink() {
        // 无 pid 的央视频频道：保留 label 点击兜底。
        assertEquals(
            "CCTV-5",
            NativePageEvidenceGate.clickLoopLabel(channelLabel = "CCTV-5", channelPid = null),
        )
        // pid 深链页面：深链已是权威选择，不装点击循环（模糊点击可能切错频道）。
        assertEquals(
            null,
            NativePageEvidenceGate.clickLoopLabel(channelLabel = "CCTV-5", channelPid = "600001811"),
        )
        assertEquals(
            null,
            NativePageEvidenceGate.clickLoopLabel(channelLabel = null, channelPid = null),
        )
    }
    @Test
    fun sameNodeReadinessFluctuationsCannotRenewStartupButRealReplacementCan() {
        val watchdog = NativePageProgressWatchdogPolicy()
        watchdog.startSession(0)
        watchdog.observeStartupMilestone(0)
        watchdog.observePlaybackClaim(0)
        watchdog.observePictureVerified(0)
        for (now in listOf(3_000L, 6_000L, 9_000L, 12_000L)) {
            if (NativePageEvidenceGate.shouldResetPlayerStartup(true, false, true)) {
                watchdog.observePlayerReplaced(now)
            }
            assertTrue(watchdog.hasPageClaimedPlaying())
        }
        assertEquals(NativePageProgressWatchdogPolicy.Decision.REBUILD, watchdog.decide(15_001))
        val recoveries = watchdog.recoveriesUsed()
        if (NativePageEvidenceGate.shouldResetPlayerStartup(true, true, false)) {
            watchdog.observePlayerReplaced(16_000)
        }
        assertFalse(watchdog.hasPageClaimedPlaying())
        assertEquals(0, watchdog.startupRenewalsUsed())
        assertEquals(recoveries, watchdog.recoveriesUsed())
        assertEquals(NativePageProgressWatchdogPolicy.Decision.NONE, watchdog.decide(50_000))
    }

    @Test
    fun legacyBridgeRetainsOldReplacementRuleAndInitializationDoesNotReset() {
        assertTrue(NativePageEvidenceGate.shouldResetPlayerStartup(false, false, true))
        assertFalse(NativePageEvidenceGate.shouldResetPlayerStartup(false, true, false))
        assertFalse(NativePageEvidenceGate.shouldResetPlayerStartup(true, false, false))
    }
}
