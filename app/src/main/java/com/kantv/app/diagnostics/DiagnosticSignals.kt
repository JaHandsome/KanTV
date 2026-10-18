package com.kantv.app.diagnostics

import com.kantv.app.util.Hashing

internal fun routeSignature(value: DiagnosticPlaybackValues): String =
    "${value.kernelCode}:${value.deliveryCode}:${DeviceDiagnosticIdentity.technical(value.videoDecoderName)}:${DeviceDiagnosticIdentity.technical(value.audioDecoderName)}"

/**
 * Recovery evidence for one fault episode: only a real, same-generation advance counts.
 *
 * 与 [StallDetector] 的帧优先口径一致：要求有尺寸、观测有效（非后台/菜单/用户暂停），
 * 且“存在 presentedFrames 就用它、否则用 frames”的计数确实增长。
 * 计数器可用时**绝不**回退用时间（`readyState=1`、frames=0 但时钟在跑不算恢复）；
 * 只有计数器不可用（-1）才回退时间，并要求 ≥[MIN_TIME_ADVANCE_MS] 的真实推进。
 */
internal fun madeProgress(
    before: DiagnosticPlaybackValues,
    after: DiagnosticPlaybackValues,
    userPaused: Boolean = false,
    menuVisible: Boolean = false,
    appInBackground: Boolean = false,
    observationEnabled: Boolean = true,
): Boolean {
    if (after.measurement.probeObservationStatus !in setOf("NOT_APPLICABLE", "VALID")) return false
    if (!observationEnabled || userPaused || menuVisible || appInBackground || after.buffering || after.paused) return false
    if (after.width <= 0 || after.height <= 0) return false
    if (counterGenerationChanged(before, after)) return false
    val frames = after.presentedFrames.takeIf { it >= 0 } ?: after.frames.takeIf { it >= 0 }
    if (frames == null) {
        return after.timeMs > 0 &&
            (before.timeMs < 0 || after.timeMs - before.timeMs >= MIN_TIME_ADVANCE_MS)
    }
    // Counter kinds are not interchangeable (decoded versus actually presented).
    if ((before.presentedFrames >= 0) != (after.presentedFrames >= 0) &&
        (before.presentedFrames >= 0 || before.frames >= 0)) return false
    val previousFrames = if (after.presentedFrames >= 0) before.presentedFrames.takeIf { it >= 0 }
        else before.frames.takeIf { it >= 0 }
    // 首次拿到计数：非零才算进展（与 StallDetector 的 `previous == null -> frameCount > 0` 同口径）。
    return if (previousFrames == null) frames > 0 else frames > previousFrames
}

/** 任一已知世代标记变化，旧的帧计数就不能拿来当新进展（重建/换源/新会话）。 */
internal fun counterGenerationChanged(
    before: DiagnosticPlaybackValues,
    after: DiagnosticPlaybackValues,
): Boolean = listOf(
    before.generation to after.generation,
    before.sourceGeneration to after.sourceGeneration,
    before.media3Generation to after.media3Generation,
    before.webRuntimeGeneration to after.webRuntimeGeneration,
    before.nodeGeneration to after.nodeGeneration,
).any { (older, newer) -> older >= 0 && newer >= 0 && older != newer }

/** 与 [StallDetector] 的时间回退同值：小于 50ms 的抖动不算推进。 */
private const val MIN_TIME_ADVANCE_MS = 50L

/** Device build strings are useful only for correlation; reports never persist their readable values. */
internal fun diagnosticOpaqueDeviceValue(value: String): String {
    if (value.isBlank() || value == "unknown") return "unknown"
    return Hashing.sha256Hex(value.toByteArray(Charsets.UTF_8))
}
