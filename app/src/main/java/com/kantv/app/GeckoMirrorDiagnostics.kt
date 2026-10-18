package com.kantv.app

import com.kantv.app.diagnostics.DiagnosticEntryKind
import com.kantv.app.diagnostics.DiagnosticPlaybackValues
import com.kantv.app.diagnostics.isMirrorOutputStalled
import com.kantv.app.diagnostics.mirrorServerRelativeElapsedMs
import com.kantv.app.playback.MseMirrorServer

/**
 * Gecko 镜像宿主的镜像诊断计数器/里程碑/阶段状态（从 GeckoMirrorLiveActivity
 * 按内聚块纯搬移，2026-09-26，客户端重构 2-5）。
 *
 * 只持有"诊断事实"：MSE 桥/server 的计数与里程碑闩锁、阶段字符串、呈现全屏计数、
 * 输入/输出进度时间戳。不含任何运行时会话对象，也不做决策——重建/放弃判定在
 * [com.kantv.app.playback.MirrorRecoveryPolicy]，宿主行为留在 Activity。
 *
 * [refreshCounters] 需要宿主侧事实（server 快照、回放策略名、采样基值 probeValues），
 * 以参数注入；返回更新后的采样基值与待发射事件，事件发射仍由宿主完成
 * （recorder 与 BuildConfig 门都在宿主）。
 */
internal class GeckoMirrorDiagnostics {
    var mirrorFirstFrameAtMs = 0L
    /** 仅聚合 MSE bridge 的非敏感诊断计数。 */
    var mirrorWorkerSwitches = 0L
    var mirrorInstanceSwitches = 0L
    var lastBridgeWorkerSwitches = 0L
    var lastBridgeInstanceSwitches = 0L
    var mirrorWatchdogRebuilds = 0L
    var mirrorWatchdogReason = "NONE"
    var mirrorServerStarts = 0L
    var mirrorVideoInitCount = 0L
    var mirrorAudioInitCount = 0L
    var mirrorAcceptedVideoFragments = 0L
    var mirrorAcceptedAudioFragments = 0L
    var mirrorRejectedVideoFragments = 0L
    var mirrorRejectedAudioFragments = 0L
    var lastServerVideoInitCount = 0L
    var lastServerAudioInitCount = 0L
    var lastServerAcceptedVideoFragments = 0L
    var lastServerAcceptedAudioFragments = 0L
    var lastServerRejectedVideoFragments = 0L
    var lastServerRejectedAudioFragments = 0L
    var mirrorFirstVideoInitElapsedMs = -1L
    var mirrorFirstAudioInitElapsedMs = -1L
    var mirrorFirstAcceptedFragmentElapsedMs = -1L
    var mirrorOutputUpdates = 0L
    var mirrorStage = "SESSION_START"
    var mirrorNavigationState = "NOT_STARTED"
    var mirrorExtensionState = "PENDING"
    var mirrorBridgeReady = false
    var mirrorHookRequested = false
    var diagnosticStartedAtMs = 0L
    var navigationStartedGeneration = -1L
    var navigationCommittedGeneration = -1L
    var navigationFinishedGeneration = -1L
    /** 旧 generation 的导航回调次数：用于区分「旧回调污染」与真正的首帧缺失。 */
    var lastPlayerNodeState = "unknown"
    var mirrorNodeGeneration = 0L
    var videoInitMilestoneRecorded = false
    var audioInitMilestoneRecorded = false
    var fragmentMilestoneRecorded = false
    var playerCreatedMilestoneRecorded = false
    var firstFrameMilestoneRecorded = false
    var lastObservedAcceptedFragments = 0L
    var lastObservedOutputUpdates = 0L
    var lastInputProgressAtMs = -1L
    var lastOutputProgressAtMs = -1L
    var outputStallEventActive = false
    var presentationFullscreenActive = false
    var presentationFullscreenEnterCount = 0L
    var presentationFullscreenExitCount = 0L

    /** Content script 上报的是页面内累计数；导航重建时从零开始，按增量汇总到会话。 */
    fun recordBridgeMetrics(workerSwitches: Long, instanceSwitches: Long) {
        mirrorWorkerSwitches += if (workerSwitches >= lastBridgeWorkerSwitches) {
            workerSwitches - lastBridgeWorkerSwitches
        } else {
            workerSwitches
        }
        mirrorInstanceSwitches += if (instanceSwitches >= lastBridgeInstanceSwitches) {
            instanceSwitches - lastBridgeInstanceSwitches
        } else {
            instanceSwitches
        }
        lastBridgeWorkerSwitches = workerSwitches
        lastBridgeInstanceSwitches = instanceSwitches
    }

    /**
     * 将服务器内的接受/拒绝计数与页面桥计数写入诊断采样，不携带 URL 或媒体字节。
     *
     * 与搬移前 [GeckoMirrorLiveActivity] 内的实现逐字等价：先按增量累计 server/桥
     * 计数并消费里程碑闩锁（返回待发射事件），再一次性产出新的采样基值
     * [RefreshResult.values]；事件发射由宿主在赋值后完成（原实现即"先 copy 再逐事件
     * 以 stage 快照发射"）。
     */
    fun refreshCounters(
        /** [MseMirrorServer.diagnosticsSnapshot]；server 不在时为 null（纯桥计数仍累计）。 */
        snapshot: MseMirrorServer.DiagnosticsSnapshot?,
        /** MSE server 实例的固定起点（Gecko watchdog/reload 不得改写），首 init/分片耗时换算用。 */
        mirrorServerStartedAtMs: Long,
        nowMs: Long,
        /** 宿主当前采样基值；返回值是它的 copy，宿主负责写回。 */
        base: DiagnosticPlaybackValues,
        playbackStrategyName: String,
        presentationModeName: String,
        routeGeneration: Long,
    ): RefreshResult {
        fun delta(current: Long, previous: Long): Long =
            if (current >= previous) current - previous else current
        if (snapshot != null) {
            mirrorVideoInitCount += delta(snapshot.videoInitCount, lastServerVideoInitCount)
            mirrorAudioInitCount += delta(snapshot.audioInitCount, lastServerAudioInitCount)
            mirrorAcceptedVideoFragments += delta(
                snapshot.acceptedVideoFragments, lastServerAcceptedVideoFragments)
            mirrorAcceptedAudioFragments += delta(
                snapshot.acceptedAudioFragments, lastServerAcceptedAudioFragments)
            mirrorRejectedVideoFragments += delta(
                snapshot.rejectedVideoFragments, lastServerRejectedVideoFragments)
            mirrorRejectedAudioFragments += delta(
                snapshot.rejectedAudioFragments, lastServerRejectedAudioFragments)
            lastServerVideoInitCount = snapshot.videoInitCount
            lastServerAudioInitCount = snapshot.audioInitCount
            lastServerAcceptedVideoFragments = snapshot.acceptedVideoFragments
            lastServerAcceptedAudioFragments = snapshot.acceptedAudioFragments
            lastServerRejectedVideoFragments = snapshot.rejectedVideoFragments
            lastServerRejectedAudioFragments = snapshot.rejectedAudioFragments
        }
        val events = mutableListOf<Pair<DiagnosticEntryKind, String>>()
        if (!videoInitMilestoneRecorded && mirrorVideoInitCount > 0L) {
            videoInitMilestoneRecorded = true
            mirrorStage = "INIT_RECEIVED"
            events += DiagnosticEntryKind.VIDEO_INIT_ACCEPTED to mirrorStage
        }
        if (!audioInitMilestoneRecorded && mirrorAudioInitCount > 0L) {
            audioInitMilestoneRecorded = true
            mirrorStage = "INIT_RECEIVED"
            events += DiagnosticEntryKind.AUDIO_INIT_ACCEPTED to mirrorStage
        }
        val acceptedFragments = mirrorAcceptedVideoFragments + mirrorAcceptedAudioFragments
        if (!fragmentMilestoneRecorded && acceptedFragments > 0L) {
            fragmentMilestoneRecorded = true
            mirrorStage = "FRAGMENT_ACCEPTED"
            events += DiagnosticEntryKind.FIRST_FRAGMENT_ACCEPTED to mirrorStage
        }
        if (acceptedFragments > lastObservedAcceptedFragments) lastInputProgressAtMs = nowMs
        if (mirrorOutputUpdates > lastObservedOutputUpdates) {
            lastOutputProgressAtMs = nowMs
            outputStallEventActive = false
        }
        lastObservedAcceptedFragments = acceptedFragments
        lastObservedOutputUpdates = mirrorOutputUpdates
        if (isMirrorOutputStalled(
                firstFrameAtMs = mirrorFirstFrameAtMs,
                lastInputProgressAtMs = lastInputProgressAtMs,
                lastOutputProgressAtMs = lastOutputProgressAtMs,
                nowMs = nowMs,
                timeoutMs = OUTPUT_STALL_TIMEOUT_MS,
            ) && !outputStallEventActive) {
            outputStallEventActive = true
            mirrorStage = "INPUT_WITHOUT_OUTPUT_PROGRESS"
            events += DiagnosticEntryKind.MIRROR_OUTPUT_STALLED to mirrorStage
        }
        if (mirrorFirstVideoInitElapsedMs < 0L) {
            mirrorFirstVideoInitElapsedMs = snapshot?.firstVideoInitElapsedMs
                ?.let { mirrorServerRelativeElapsedMs(
                    mirrorServerStartedAtMs, diagnosticStartedAtMs, it) } ?: -1L
        }
        if (mirrorFirstAudioInitElapsedMs < 0L) {
            mirrorFirstAudioInitElapsedMs = snapshot?.firstAudioInitElapsedMs
                ?.let { mirrorServerRelativeElapsedMs(
                    mirrorServerStartedAtMs, diagnosticStartedAtMs, it) } ?: -1L
        }
        if (mirrorFirstAcceptedFragmentElapsedMs < 0L) {
            mirrorFirstAcceptedFragmentElapsedMs = snapshot?.firstAcceptedFragmentElapsedMs
                ?.let { mirrorServerRelativeElapsedMs(
                    mirrorServerStartedAtMs, diagnosticStartedAtMs, it) } ?: -1L
        }
        val values = base.copy(
            mirrorWorkerSwitches = mirrorWorkerSwitches,
            mirrorInstanceSwitches = mirrorInstanceSwitches,
            mirrorAcceptedFragments = acceptedFragments,
            mirrorRejectedFragments = mirrorRejectedVideoFragments + mirrorRejectedAudioFragments,
            mirrorWatchdogRebuilds = mirrorWatchdogRebuilds,
            mirrorWatchdogReason = mirrorWatchdogReason,
            routeGeneration = routeGeneration,
            mirrorStage = mirrorStage,
            mirrorNavigationState = mirrorNavigationState,
            mirrorExtensionState = mirrorExtensionState,
            mirrorBridgeReady = mirrorBridgeReady,
            mirrorHookRequested = mirrorHookRequested,
            mirrorServerStarts = mirrorServerStarts,
            mirrorVideoInitCount = mirrorVideoInitCount,
            mirrorAudioInitCount = mirrorAudioInitCount,
            mirrorAcceptedVideoFragments = mirrorAcceptedVideoFragments,
            mirrorAcceptedAudioFragments = mirrorAcceptedAudioFragments,
            mirrorRejectedVideoFragments = mirrorRejectedVideoFragments,
            mirrorRejectedAudioFragments = mirrorRejectedAudioFragments,
            mirrorFirstVideoInitElapsedMs = mirrorFirstVideoInitElapsedMs,
            mirrorFirstAudioInitElapsedMs = mirrorFirstAudioInitElapsedMs,
            mirrorFirstAcceptedFragmentElapsedMs = mirrorFirstAcceptedFragmentElapsedMs,
            mirrorPlayerCreated = playerCreatedMilestoneRecorded,
            mirrorFirstFrameRendered = firstFrameMilestoneRecorded,
            mirrorOutputUpdates = mirrorOutputUpdates,
            geckoPlaybackStrategy = playbackStrategyName,
            geckoPresentationMode = presentationModeName,
            geckoFullscreenActive = presentationFullscreenActive,
            geckoFullscreenEnterCount = presentationFullscreenEnterCount,
            geckoFullscreenExitCount = presentationFullscreenExitCount,
        )
        return RefreshResult(values, events)
    }

    /** 呈现全屏计数 → 采样基值（[GeckoMirrorLiveActivity.refreshPresentationDiagnostics] 等价搬移）。 */
    fun refreshPresentation(base: DiagnosticPlaybackValues, presentationModeName: String): DiagnosticPlaybackValues =
        base.copy(
            geckoPresentationMode = presentationModeName,
            geckoFullscreenActive = presentationFullscreenActive,
            geckoFullscreenEnterCount = presentationFullscreenEnterCount,
            geckoFullscreenExitCount = presentationFullscreenExitCount,
        )

    /** [refreshCounters] 的结果：宿主应写回的采样基值 + 按序发射的事件。 */
    data class RefreshResult(
        val values: DiagnosticPlaybackValues,
        val events: List<Pair<DiagnosticEntryKind, String>>,
    )

    companion object {
        const val OUTPUT_STALL_TIMEOUT_MS = 15_000L
    }
}
