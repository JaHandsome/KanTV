"""Strict, privacy-preserving schema for playback diagnostic fragments."""
from __future__ import annotations

import hashlib
import json
import re
import uuid
from dataclasses import dataclass
from typing import Any

MAX_STRING_LENGTH = 96
MAX_SAMPLES = 120
# 2026-09-15：用户决定弃用 v1–v5（不会有生产者再产出）。仅保留 v10（老电视在用）与 v11。
# 2026-09-22：v13 新增央视频选台点击布尔事实 labelFound/labelClicked（每个样本必填）。
SUPPORTED_SCHEMA_VERSIONS = frozenset({10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22})
REASONS_V1_V2 = frozenset({"NO_FIRST_FRAME_30S", "PLAYBACK_STALLED_15S", "CHANNEL_SWITCH"})
REASONS_V3 = REASONS_V1_V2 | {"STOPPED", "PROCESS_TERMINATED", "CHECKPOINT"}
PROVIDERS_V1 = frozenset({"CCTV", "YANGSHIPIN", "YICAI", "GDTV", "TVB_NEWS", "TVB_RESOLVED", "REGIONAL"})
PROVIDERS_V2 = PROVIDERS_V1 | {"M3U"}
SAMPLE_KINDS_V1_V2 = frozenset(
    {"START", "SAMPLE", "STOP", "SWITCH", "MENU_OPEN", "MENU_CLOSE", "BACKGROUND", "FOREGROUND", "USER_PAUSE", "USER_RESUME", "RENDERER_ERROR"}
)
SAMPLE_KINDS_V3 = SAMPLE_KINDS_V1_V2 | {
    "ROUTE_CHANGED", "AUDIO_TRACK_CHANGED", "DISPLAY_MODE_CHANGED", "PLAYBACK_FAULT",
    "RECOVERY_STARTED", "RECOVERY_SUCCEEDED", "CHECKPOINT", "PROCESS_RECOVERED", "GENERIC_EVENT",
}
SAMPLE_KINDS_V10 = SAMPLE_KINDS_V3 | {
    "MIRROR_SERVER_STARTED", "MIRROR_SERVER_FAILED", "WEB_SESSION_CREATED",
    "NAVIGATION_STARTED", "NAVIGATION_COMMITTED", "NAVIGATION_FINISHED", "NAVIGATION_FAILED",
    "EXTENSION_READY", "EXTENSION_FAILED", "BRIDGE_READY", "HOOK_INJECTION_REQUESTED",
    "PLAYER_NODE_MISSING", "PLAYER_NODE_READY0", "VIDEO_INIT_ACCEPTED", "AUDIO_INIT_ACCEPTED",
    "FIRST_FRAGMENT_ACCEPTED", "MIRROR_PLAYER_CREATED", "MIRROR_FIRST_FRAME",
    "MIRROR_OUTPUT_STALLED", "NATIVE_PAGE_PREPARED", "NATIVE_PAGE_PROGRESS",
    "SOURCE_START", "SOURCE_FIRST_FRAME", "DELIVERY_SELECTED", "REBUFFER_INTERVAL",
    "SOURCE_AUTO_SWITCH", "SOURCE_PLAYBACK_STABLE", "RECOVERY_REOPENED", "WATCHDOG_TRIGGERED",
    "GREY_SCREEN_EVIDENCE", "MEDIA_PIPELINE_STATE",
}
# v11（2026-09-15）：诊断存活心跳与看门狗判定事件。
SAMPLE_KINDS_V11 = SAMPLE_KINDS_V10 | {"HEARTBEAT", "WATCHDOG_DECISION"}
CHANNEL_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,95}\Z")

SAMPLE_BASE_NUMBERS = frozenset({
    "elapsedMs", "timeMs", "frames", "width", "height", "readyState", "errorCode",
    "droppedFrames", "presentedFrames", "jsSampleAgeMs", "processPssKb", "rendererState",
})
PLAYER_NUMBERS_V2 = frozenset({
    "kernelCode", "deliveryCode", "videoProgressCount", "decodedFrames", "displayedFrames",
    "readBytes", "demuxReadBytes", "inputKbps", "demuxKbps", "demuxCorrupted",
    "demuxDiscontinuity", "decodedAudio", "playedAudioBuffers", "lostAudioBuffers",
    "selectedAudioTrackId", "configuredAudioDelayUs", "sourceAudioPid1",
    "sourceAudioMinusVideoMs1", "sourceAudioPid2", "sourceAudioMinusVideoMs2",
})
PLAYER_NUMBERS_V3 = PLAYER_NUMBERS_V2 | {
    "audioChannelCount", "audioSampleRate", "bufferedPositionMs", "bufferDurationMs",
    "videoPtsUs", "audioPtsUs", "playbackStateCode", "generation", "pictureModeCode",
}
PLAYER_NUMBERS_V4 = PLAYER_NUMBERS_V3 | {
    "audioInputPtsUs", "audioSinkPositionUs", "audioOutputEncoding", "audioOutputSampleRate",
    "audioOutputChannelConfig", "audioOutputBufferSize", "audioTrackPlaybackHeadPosition",
    "underrunBufferSize", "underrunBufferSizeMs", "underrunElapsedSinceLastFeedMs",
    "audioSinkDiscontinuityCount", "videoPlannedReleaseTimeNs", "videoCallbackNanoTime",
    "videoProcessingOffsetUs", "videoProcessingFrameCount", "videoFirstFrameRenderTimeMs",
    "droppedFramesElapsedMs", "tsPcrPid", "tsPcrSpanUs", "tsPcrMaxJumpUs", "tsPcrBackwardJumpCount",
    "tsAdaptationDiscontinuityCount", "tsFirstAudioMinusVideoUs",
    "tsHlsDiscontinuitySequence", "tsRepairCorrectionSeconds", "tsRepairHeaderCount",
}
PLAYER_NUMBERS_V5 = PLAYER_NUMBERS_V4 | {"nodeCount", "nodeGeneration", "webRuntimeGeneration"}
PLAYER_NUMBERS_V10 = PLAYER_NUMBERS_V5 | {
    "mirrorWorkerSwitches", "mirrorInstanceSwitches", "mirrorAcceptedFragments",
    "mirrorRejectedFragments", "mirrorWatchdogRebuilds", "routeGeneration", "mirrorServerStarts",
    "mirrorVideoInitCount", "mirrorAudioInitCount", "mirrorAcceptedVideoFragments",
    "mirrorAcceptedAudioFragments", "mirrorRejectedVideoFragments", "mirrorRejectedAudioFragments",
    "mirrorFirstVideoInitElapsedMs", "mirrorFirstAudioInitElapsedMs",
    "mirrorFirstAcceptedFragmentElapsedMs", "mirrorOutputUpdates", "geckoFullscreenRequestCount",
    "geckoFullscreenEnterCount", "geckoFullscreenExitCount", "geckoPresentationRebindCount",
    "geckoPresentationTargetWidth", "geckoPresentationTargetHeight", "geckoPresentationViewportWidth",
    "geckoPresentationViewportHeight", "geckoPresentationCoveragePermille", "sourceOrdinal",
    "sourceCount", "sourceGeneration", "media3Generation", "deliveryStrategyCode",
    "deliveryReasonCode", "prefetchQueued", "prefetchReady", "prefetchFailed", "prefetchCancelled",
    "prefetchWaitMs", "prefetchCapMs", "prefetchSegmentCount", "prefetchTargetDurationMs",
    "rebufferDurationMs", "rebufferCountWindow", "rebufferTotalMsWindow", "sourceHealthScore",
    "firstFrameLatencyMs", "videoPtsMs",
}
SAMPLE_BOOLEANS = frozenset({"paused", "buffering"})
SAMPLE_STRINGS_V3 = frozenset({"videoDecoderName", "audioDecoderName", "audioMimeType", "audioCodec"})
SAMPLE_BOOLEANS_V4 = SAMPLE_BOOLEANS | {
    "audioSinkPositionAvailable", "audioTrackReleased", "videoProcessingOffsetAvailable",
    "tsPcrAvailable", "tsFirstAudioMinusVideoAvailable", "tsHlsDiscontinuity", "tsWouldRepair",
}
SAMPLE_NULLABLE_BOOLEANS_V4 = frozenset({"audioOutputTunneling", "audioOutputOffload"})
SAMPLE_STRINGS_V4 = SAMPLE_STRINGS_V3 | {
    "audioOutputMode", "audioOutputRoute", "audioTrackTimestampAvailability",
    "videoTimingSource", "videoFirstFrameSource", "actualPresentedAvailability", "tsInputSource",
}
SAMPLE_STRINGS_V5 = SAMPLE_STRINGS_V4 | {"webState", "webRecoveryStage"}
SAMPLE_BOOLEANS_V10 = SAMPLE_BOOLEANS_V4 | {
    "mirrorBridgeReady", "mirrorHookRequested", "mirrorPlayerCreated", "mirrorFirstFrameRendered",
    "geckoFullscreenRequestAccepted", "geckoFullscreenActive", "geckoCssFitApplied",
    "audioAdvancedWithoutVideo", "videoAdvancedWithoutAudio",
}
SAMPLE_STRINGS_V10 = SAMPLE_STRINGS_V5 | {
    "mirrorWatchdogReason", "mirrorStage", "mirrorNavigationState", "mirrorExtensionState",
    "geckoPlaybackStrategy", "geckoPresentationMode", "sourceHash", "prefetchOutcome",
    "rebufferReason", "rebufferUnstableReason", "recoveryReason", "greyScreenReason",
}
# ===== v11（2026-09-15）：合成层 / 决策层 / 上下文层证据 =====
SAMPLE_STRINGS_V11 = SAMPLE_STRINGS_V10 | {
    "visualSampleOutcome", "watchdogDecision", "watchdogReason",
    "sampleSkipReason", "networkTransport",
}
SAMPLE_BOOLEANS_V11 = SAMPLE_BOOLEANS_V10 | {
    "wedgeActive", "probeTimedOut", "networkMetered", "samplingSuspended",
}
PLAYER_NUMBERS_V11 = PLAYER_NUMBERS_V10 | {
    "visualSampleCallbackMs", "millisSincePictureVerified",
    "watchdogRecoveries", "watchdogRecoveryLimit",
    "millisSinceHeartbeat", "samplingSuspendedMs",
}
VISUAL_SAMPLE_OUTCOMES_V11 = frozenset({
    "unknown", "DETAIL", "FLAT_NEUTRAL", "FLAT_OTHER", "NO_SAMPLE",
})
WATCHDOG_DECISIONS_V11 = frozenset({"unknown", "NONE", "REBUILD", "FALLBACK"})
WATCHDOG_REASONS_V11 = frozenset({
    "unknown", "NONE", "FIRST_FRAME_TIMEOUT", "FRAME_PROGRESS_TIMEOUT", "CLOCK_STALLED",
    "PICTURE_UNVERIFIED", "PROBE_TIMED_OUT", "RENDERER_GONE", "BUDGET_EXHAUSTED", "MANUAL",
})
SAMPLE_SKIP_REASONS_V11 = frozenset({
    "unknown", "NONE", "PROBE_IN_FLIGHT", "NOT_PLAYING", "NO_SESSION", "STOPPED",
})
NETWORK_TRANSPORTS_V11 = frozenset({
    "unknown", "NONE", "WIFI", "CELLULAR", "ETHERNET", "VPN", "OTHER",
})
WEB_STATES_V5 = frozenset({
    "unknown", "NO_PLAYER_NODE", "INITIALIZING", "NODE_REPLACED", "LOGIN_REQUIRED", "AD",
    "POLICY_OR_REGION_OR_DRM_BLOCKED", "PLAYER_NO_FRAME_PROGRESS",
    "RENDERER_UNRESPONSIVE", "RENDERER_GONE", "PLAYING",
})
WEB_RECOVERY_STAGES_V5 = frozenset({
    "unknown", "IDLE", "MONITORING", "SOFT_RELOAD", "HARD_RECREATE", "BLOCKED", "FAILED",
})
MIRROR_WATCHDOG_REASONS_V10 = frozenset({
    "unknown", "NONE", "FIRST_FRAGMENT_TIMEOUT", "ACCEPTED_FRAGMENT_STALL",
})
MIRROR_STAGES_V10 = frozenset({
    "unknown", "SESSION_START", "SERVER_READY", "NAVIGATING", "PAGE_COMMITTED",
    "PAGE_FINISHED", "EXTENSION_READY", "BRIDGE_READY", "HOOK_REQUESTED", "NO_PLAYER_NODE",
    "PLAYER_READY0", "INIT_RECEIVED", "FRAGMENT_ACCEPTED", "PLAYER_CREATED", "FIRST_FRAME",
    "INPUT_WITHOUT_OUTPUT_PROGRESS", "WATCHDOG_REBUILD", "PLAYER_ERROR", "FAILED",
    "NATIVE_PAGE_PREPARED", "NATIVE_PAGE_PROGRESS",
})
MIRROR_NAVIGATION_STATES_V10 = frozenset({
    "unknown", "NOT_STARTED", "STARTED", "COMMITTED", "FINISHED", "FAILED",
})
MIRROR_EXTENSION_STATES_V10 = frozenset({"unknown", "PENDING", "READY", "FAILED"})
GECKO_PLAYBACK_STRATEGIES_V10 = frozenset({"unknown", "MIRROR", "NATIVE_PAGE"})
GECKO_PRESENTATION_MODES_V10 = frozenset({
    "unknown", "AUTO_FULLSCREEN_ONLY", "CSS_FIT_ONLY", "AUTO_THEN_FIT",
})
PREFETCH_OUTCOMES_V10 = frozenset({
    "unknown", "NOT_APPLICABLE", "INDEPENDENT_SEGMENTS", "VERIFIED_BOOTSTRAP",
    "UNVERIFIED_BOOTSTRAP", "UNSUPPORTED_CODEC", "UNSUPPORTED_PLAYLIST", "DOWNLOAD_FAILED",
    "VERIFICATION_TIMEOUT", "CANCELLED",
})
REBUFFER_REASONS_V10 = frozenset({"unknown", "PLAYER_BUFFERING", "FRAME_STALL"})
REBUFFER_UNSTABLE_REASONS_V10 = frozenset({
    "unknown", "SINGLE_LONG_REBUFFER", "ACCUMULATED_DURATION", "REPEATED_REBUFFERS",
})
RECOVERY_REASONS_V10 = frozenset({
    "unknown", "NONE", "FIRST_FRAME_TIMEOUT", "FRAME_PROGRESS_TIMEOUT", "BUFFERING_STALL",
    "BEHIND_LIVE_WINDOW", "REBUFFER_UNSTABLE", "AUTO_REOPEN", "COOLDOWN_RETRY",
    "BACKEND_UNSUPPORTED", "ACTIVITY_STOP", "USER_EXIT", "PROCESS_TERMINATED",
})
GREY_SCREEN_REASONS_V10 = frozenset({
    "unknown", "NONE", "NO_FIRST_FRAME", "FRAME_FROZEN", "SOUND_ONLY", "STALE_SESSION",
})
SOURCE_HASH_RE_V10 = re.compile(r"(?:[0-9a-f]{8,64})?\Z")
TOKEN_LIKE_RE_V10 = re.compile(r"[A-Za-z0-9_-]{32,128}\Z")
INSTALL_LABEL_RE_V10 = re.compile(r"install-[0-9a-f]{8}\Z")
GIT_REVISION_RE_V10 = re.compile(r"[A-Za-z0-9._-]{1,80}\Z")
OPAQUE_DEVICE_RE_V10 = re.compile(r"(?:unknown|[0-9a-f]{64})\Z")
SENSITIVE_VALUE_MARKERS_V10 = ("authorization", "bearer", "cookie", "token")
LIFECYCLES_V3 = frozenset({"COMPLETE", "CHECKPOINT", "INCIDENT", "RECOVERED_AFTER_PROCESS_DEATH"})

ROOT_V1 = {
    "schemaVersion", "reportId", "reason", "capturedAtMs", "appVersionName", "appVersionCode",
    "deviceManufacturer", "deviceModel", "deviceApiLevel", "channelId", "provider", "sessionId", "samples",
}
ROOT_V2 = ROOT_V1 | {"deviceInstallId", "deviceLabel", "deviceCpuAbi", "deviceTotalRamMb", "displayWidth", "displayHeight"}
ROOT_V3 = ROOT_V2 | {
    "lifecycle", "closedAtMs", "fragmentSequence", "fragmentStartedElapsedMs", "fragmentEndedElapsedMs",
    "app", "device", "display", "runtime", "libraries", "decoderCapabilities",
}

FORBIDDEN_KEY_PARTS = ("url", "uri", "token", "cookie", "header", "ssid", "bssid", "mac", "rawlog")

class SchemaError(ValueError):
    pass

@dataclass(frozen=True)
class ValidatedReport:
    report_id: str
    canonical_bytes: bytes

def _fail(path: str, message: str) -> None:
    raise SchemaError(f"{path}: {message}")

def _object(value: Any, path: str) -> dict[str, Any]:
    if not isinstance(value, dict): _fail(path, "必须是对象")
    return value

def _exact(value: Any, path: str, fields: set[str]) -> dict[str, Any]:
    item = _object(value, path)
    if set(item) != fields: _fail(path, f"字段必须严格为 {sorted(fields)!r}")
    return item

def _string(value: Any, path: str, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or (not allow_empty and not value) or len(value) > MAX_STRING_LENGTH:
        _fail(path, f"必须是至多 {MAX_STRING_LENGTH} 个字符的字符串")
    if any(ord(char) < 32 for char in value): _fail(path, "不能包含控制字符")
    return value

def _int(value: Any, path: str) -> None:
    if isinstance(value, bool) or not isinstance(value, int): _fail(path, "必须是整数")

def _technical(value: Any, path: str) -> None:
    text = _string(value, path)
    if "://" in text or any(char.isspace() for char in text): _fail(path, "必须是受限技术标识")

def _v10_technical(value: Any, path: str) -> str:
    text = _string(value, path)
    lowered = text.lower()
    if ("://" in text or any(char.isspace() for char in text) or TOKEN_LIKE_RE_V10.fullmatch(text) or
            any(marker in lowered for marker in SENSITIVE_VALUE_MARKERS_V10)):
        _fail(path, "必须是非敏感受限技术标识")
    return text

def _v10_device_digest(value: Any, path: str) -> str:
    text = _string(value, path)
    if not OPAQUE_DEVICE_RE_V10.fullmatch(text): _fail(path, "必须是不可逆设备摘要或 unknown")
    return text

def _normalized_v10_device_identity(value: Any) -> Any:
    """将旧版可读设备身份值即时归一化为不可逆摘要，严格校验器保持最终裁决。

    ``unknown`` 与既有的 64 位小写 SHA-256 摘要原样保留；非字符串、超长、含控制
    字符，或形似 URL/令牌/敏感标记的值也原样返回，交由 ``_v10_device_digest``
    用原有错误拒绝。其余合法、有限且无敏感特征的可读值在此处立即哈希，绝不保留
    或记录明文。
    """
    if not isinstance(value, str) or not 0 < len(value) <= MAX_STRING_LENGTH: return value
    if any(ord(char) < 32 for char in value): return value
    if OPAQUE_DEVICE_RE_V10.fullmatch(value): return value
    lowered = value.lower()
    if ("://" in value or TOKEN_LIKE_RE_V10.fullmatch(value)
            or any(marker in lowered for marker in SENSITIVE_VALUE_MARKERS_V10)):
        return value
    return hashlib.sha256(value.encode("utf-8")).hexdigest()

def _normalize_v10_device_identities(root: dict[str, Any]) -> None:
    for key in ("deviceManufacturer", "deviceModel"):
        root[key] = _normalized_v10_device_identity(root[key])
    device = root.get("device")
    if not isinstance(device, dict): return
    for key in ("manufacturer", "model", "product", "board", "hardware", "socManufacturer", "socModel"):
        if key in device: device[key] = _normalized_v10_device_identity(device[key])

def _number(value: Any, path: str) -> None:
    if isinstance(value, bool) or not isinstance(value, (int, float)): _fail(path, "必须是数字")

def _bool_or_null(value: Any, path: str) -> None:
    if value is not None and not isinstance(value, bool): _fail(path, "必须是布尔值或 null")

def _list(value: Any, path: str, limit: int) -> list[Any]:
    if not isinstance(value, list) or len(value) > limit: _fail(path, f"必须是至多 {limit} 条的列表")
    return value

def _uuid(value: Any, path: str) -> str:
    if not isinstance(value, str): _fail(path, "必须是标准 UUID")
    try: normalized = str(uuid.UUID(value))
    except (ValueError, AttributeError) as exc: raise SchemaError(f"{path}: 必须是标准 UUID") from exc
    if value.lower() != normalized: _fail(path, "必须使用标准 UUID 文本")
    return normalized

def _reject_forbidden_keys(value: Any, path: str = "report") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            lowered = key.lower()
            if any(lowered == part or lowered.startswith(part) or lowered.endswith(part)
                   for part in FORBIDDEN_KEY_PARTS):
                _fail(f"{path}.{key}", "字段可能携带敏感数据")
            _reject_forbidden_keys(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value): _reject_forbidden_keys(child, f"{path}[{index}]")

# v12：源的上游身份 + 用户可见状态。回答"当时在哪条线路上、用户屏幕上看到的是什么"。
SAMPLE_KINDS_V12 = SAMPLE_KINDS_V11 | {"USER_SOURCE_REPORT"}
SAMPLE_KINDS_V21 = SAMPLE_KINDS_V12 | {"BUFFERING_STARTED", "BUFFERING_ENDED"}
SAMPLE_STRINGS_V12 = SAMPLE_STRINGS_V11 | {
    "sourceRoute", "sourceQuality", "sourceUrlKey", "userVisibleState",
}
PLAYER_NUMBERS_V12 = PLAYER_NUMBERS_V11 | {
    "sourceScoreUpstream", "userVisibleStateMs",
}
USER_VISIBLE_STATES_V12 = frozenset({
    "unknown", "PLAYING", "CONNECTING", "FIRST_OPEN", "BUFFERING", "RECOVERING",
    "RECOVERED_NOTICE", "SWITCHING_SOURCE", "FAILED_NOTICE", "MENU_OPEN", "BACKGROUND",
})

# v13（2026-09-22）：央视频选台点击的两个布尔事实。v12 未新增布尔字段，故基类仍为 V11。
SAMPLE_BOOLEANS_V13 = SAMPLE_BOOLEANS_V11 | {"labelFound", "labelClicked"}


# v14: browser-clock relative timings and explicit insufficient/unknown assessments.
PLAYER_NUMBERS_V14 = PLAYER_NUMBERS_V12 | {
    "videoCallbackAgeMs", "videoPresentationAgeMs", "videoExpectedDisplayDelayUs",
}
SAMPLE_STRINGS_V14 = SAMPLE_STRINGS_V12 | {"probeObservationStatus", "avSyncAssessment"}
PROBE_OBSERVATION_STATUSES_V14 = frozenset({
    "NOT_APPLICABLE", "VALID", "INSUFFICIENT_NO_SAMPLE", "INSUFFICIENT_STALE_SAMPLE",
    "INSUFFICIENT_TICK_NOT_ADVANCING", "INSUFFICIENT_RUNTIME_CHANGED", "INSUFFICIENT_NO_IDENTITY",
})
AV_SYNC_ASSESSMENTS_V14 = frozenset({
    "UNKNOWN_NOT_EVALUATED", "UNKNOWN_NO_MEDIA_ELEMENT_AUDIO_OUTPUT_TIMESTAMP",
})

# v15: Gecko engine clock and exact request-time frame pair. Never physical output proof.
GECKO_AUDIO_NUMBERS_V15 = frozenset({
    "geckoAudioClockUs", "geckoAudioClockAgeMs", "geckoAudioClockCallDurationMs",
    "geckoPairedVideoPtsUs", "geckoAudioMinusVideoUs", "geckoAvPairSpanMs",
})
PLAYER_NUMBERS_V15 = PLAYER_NUMBERS_V14 | GECKO_AUDIO_NUMBERS_V15
SAMPLE_STRINGS_V15 = SAMPLE_STRINGS_V14 | {"geckoAudioClockStatus"}
GECKO_AUDIO_CLOCK_STATUSES_V15 = frozenset({
    "NOT_APPLICABLE", "AVAILABLE", "INSUFFICIENT_NO_SAMPLE", "INSUFFICIENT_NOT_PLAYING",
    "INSUFFICIENT_PENDING", "INSUFFICIENT_UNSUPPORTED", "INSUFFICIENT_REJECTED",
    "INSUFFICIENT_TIMEOUT", "INSUFFICIENT_SLOW_CALL", "INSUFFICIENT_INVALID_CLOCK",
    "INSUFFICIENT_CLOCK_MISMATCH", "INSUFFICIENT_PAIR_STALE", "INSUFFICIENT_STALE_SAMPLE",
    "INSUFFICIENT_PROBE_INVALID",
})
PROBE_OBSERVATION_STATUSES_V15 = PROBE_OBSERVATION_STATUSES_V14 | {"INSUFFICIENT_SUSPENDED"}
AV_SYNC_ASSESSMENTS_V15 = AV_SYNC_ASSESSMENTS_V14 | {"UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED"}

# v16: native Media3 sink estimate, normalized with its observed output stream offset.
NATIVE_AUDIO_NUMBERS_V16 = frozenset({
    "nativeAvGeneration", "nativeAudioStreamOffsetUs", "nativeAudioClockUs", "nativeAudioClockAgeMs",
    "nativePairedVideoPtsUs", "nativeAudioMinusVideoUs", "nativeAvPairSpanUs", "nativeVideoReleaseDelayUs",
})
NATIVE_LIVE_NUMBERS_V16 = frozenset({"nativeLiveContextAgeMs", "nativeLiveOffsetMs", "nativeLiveWindowDurationMs", "nativeLiveMediaSequence", "nativeLastMediaLoadDurationMs", "nativeLastMediaLoadBytes", "nativeLastLoadErrorCode"})
PLAYER_NUMBERS_V16 = PLAYER_NUMBERS_V15 | NATIVE_AUDIO_NUMBERS_V16 | NATIVE_LIVE_NUMBERS_V16
SAMPLE_STRINGS_V16 = SAMPLE_STRINGS_V15 | {"nativeAudioClockStatus"}
NATIVE_AUDIO_CLOCK_STATUSES_V16 = frozenset({
    "NOT_APPLICABLE", "AVAILABLE", "INSUFFICIENT_NO_SAMPLE", "INSUFFICIENT_NOT_PLAYING",
    "INSUFFICIENT_NO_OFFSET", "INSUFFICIENT_INVALID_CLOCK", "INSUFFICIENT_STREAM_MISMATCH",
    "INSUFFICIENT_PAIR_STALE", "INSUFFICIENT_STALE_SAMPLE", "INSUFFICIENT_INVALIDATED",
})

# v17: bounded task/read timing and Gecko request/first-frame trace. No URI or task IDs.
EVIDENCE_NUMBERS_V17 = frozenset({'nativeTransferStartElapsedMs', 'geckoNodeBoundMs', 'nativeTransferSequence', 'geckoAudioReactionElapsedMs', 'geckoLoadedMetadataMs', 'geckoFirstRvfcMs', 'nativeLoadActiveStartElapsedMs', 'nativeLoadLastMediaStartMs', 'geckoAudioQuerySeq', 'geckoAudioQueryNode', 'nativeTransferActiveCount', 'nativeTransferBytes', 'nativeTransferEndElapsedMs', 'nativeLoadLastMediaEndMs', 'geckoWaitingMs', 'nativeLoadLastBytes', 'geckoFirstRvfcPtsUs', 'nativeTransferReadDurationUs', 'nativeTransferCloseDurationUs', 'nativeLoadActiveMediaStartMs', 'nativeLoadLastStartElapsedMs', 'geckoAudioSyncCallMs', 'nativeLoadLastRetryCount', 'nativeLoadLastHttpStatus', 'geckoPlayMs', 'geckoAudioQueryStartMs', 'nativeTransferReadCallCount', 'nativeLoadLastEndElapsedMs', 'nativeTransferOpenDurationUs', 'geckoJsTickGapMs', 'geckoPlayingMs', 'nativeLoadActiveMediaEndMs', 'nativeLoadWindowStartInPeriodMs', 'geckoAudioTimeoutLateMs', 'nativeLoadActiveRetryCount', 'geckoCanPlayMs', 'geckoAudioQueryEpoch'})
EVIDENCE_BOOLEANS_V17 = frozenset({'geckoAudioInFlight', 'geckoAttachLate', 'nativeTransferEofSeen'})
EVIDENCE_ENUMS_V17 = {
    'nativeLoadActiveStatus': frozenset(['NOT_APPLICABLE', 'NONE', 'LOADING', 'RETRYING', 'UNKNOWN_CAPACITY', 'UNKNOWN_OWNERSHIP']),
    'nativeLoadActiveTrackType': frozenset(['UNKNOWN', 'DEFAULT', 'AUDIO', 'VIDEO', 'TEXT']),
    'nativeLoadLastOutcome': frozenset(['NOT_APPLICABLE', 'NONE', 'COMPLETED', 'ERROR_RETRYING', 'ERROR_TERMINAL', 'CANCELED', 'INVALIDATED']),
    'nativeLoadLastTrackType': frozenset(['UNKNOWN', 'DEFAULT', 'AUDIO', 'VIDEO', 'TEXT']),
    'nativeLoadPositionStatus': frozenset(['NOT_APPLICABLE', 'AVAILABLE', 'WINDOW_ONLY', 'UNKNOWN_NO_PERIOD', 'UNKNOWN_CHANGED_PERIOD', 'UNKNOWN_MULTI_PERIOD', 'UNKNOWN_TIME']),
    'nativeTransferOutcome': frozenset(['NA', 'NONE', 'CLOSED_EOF', 'CLOSED_NO_EOF', 'OPEN_ERROR', 'READ_ERROR', 'CLOSE_ERROR', 'INVALIDATED']),
    'nativeTransferTaskMatchStatus': frozenset(['NOT_APPLICABLE', 'UNASSIGNED']),
    'geckoAudioQueryState': frozenset(['NOT_APPLICABLE', 'NOT_STARTED', 'CALLING', 'PENDING', 'TIMED_OUT', 'RESOLVED', 'REJECTED']),
    'geckoAudioQueryDisposition': frozenset(['NOT_APPLICABLE', 'NONE', 'APPLIED', 'TIMEOUT_FUSE', 'SYNC_THROW', 'REJECTED_FUSE', 'SLOW_CALL', 'INVALID_CLOCK', 'CLOCK_MISMATCH', 'PAIR_STALE', 'IGNORED_TIMEOUT_FUSE', 'IGNORED_NODE', 'IGNORED_EPOCH', 'IGNORED_NOT_PLAYING']),
}
PLAYER_NUMBERS_V17 = PLAYER_NUMBERS_V16 | EVIDENCE_NUMBERS_V17
SAMPLE_STRINGS_V17 = SAMPLE_STRINGS_V16 | EVIDENCE_ENUMS_V17.keys()
SAMPLE_BOOLEANS_V17 = SAMPLE_BOOLEANS_V13 | EVIDENCE_BOOLEANS_V17

EVIDENCE_NUMBERS_V18 = frozenset(['geckoAudioQueryMaxRvfcGapMs', 'geckoAudioQueryRvfcCount', 'geckoDocumentNowMs', 'geckoDocumentTimeOriginMs', 'geckoStateReceivedEpochMs', 'nativeMediaRequestBytes', 'nativeMediaRequestCloseDurationUs', 'nativeMediaRequestOpenDurationUs', 'nativeMediaRequestReadCallCount', 'nativeMediaRequestReadDurationUs'])
EVIDENCE_BOOLEANS_V18 = frozenset(['nativeMediaRequestEofSeen'])
EVIDENCE_ENUMS_V18 = {'nativeMediaRequestMatchStatus': frozenset({'UNASSIGNED', 'NONE', 'UNKNOWN_RETRY', 'NOT_APPLICABLE', 'UNKNOWN_AMBIGUOUS', 'MATCHED', 'UNKNOWN_CAPACITY', 'PENDING', 'INVALIDATED'}), 'nativeMediaRequestCoverage': frozenset({'UNKNOWN', 'REQUEST_ONLY'}), 'nativeMediaRequestOutcome': frozenset({'CLOSED_EOF', 'NONE', 'OPEN_ERROR', 'CLOSE_ERROR', 'READ_ERROR', 'NA', 'CLOSED_NO_EOF', 'INVALIDATED'})}
PLAYER_NUMBERS_V18 = PLAYER_NUMBERS_V17 | EVIDENCE_NUMBERS_V18
SAMPLE_STRINGS_V18 = SAMPLE_STRINGS_V17 | EVIDENCE_ENUMS_V18.keys()
SAMPLE_BOOLEANS_V18 = SAMPLE_BOOLEANS_V17 | EVIDENCE_BOOLEANS_V18

EVIDENCE_LIMITS_V19 = {'geckoBridgeLastTickDurationMs': 86400000, 'geckoBridgeLastTickMaxPhaseDurationMs': 86400000, 'geckoBridgeLastTickScanNodes': 2800, 'geckoAudioQueryTickDurationMs': 86400000, 'geckoAudioQueryTickTailDurationMs': 86400000, 'geckoAudioQueryTickMaxPhaseDurationMs': 86400000, 'geckoAudioQueryTickScanNodes': 2800, 'geckoStartupObserverTickCount': 1000000, 'geckoStartupObserverTotalDurationMs': 86400000, 'geckoStartupObserverMaxTickDurationMs': 86400000, 'geckoStartupResourceCount': 128, 'geckoStartupResourceMaxDurationMs': 86400000, 'geckoStartupResourceMaxResponseWaitMs': 86400000, 'geckoStartupResourceMaxDownloadMs': 86400000, 'geckoStartupResourceDetailedCount': 128}
EVIDENCE_NUMBERS_V19 = frozenset(EVIDENCE_LIMITS_V19)
EVIDENCE_ENUMS_V19 = {'geckoBridgeLastTickMaxPhaseCode': frozenset({'QR_LAYOUT', 'NATIVE_SEND', 'STATE_BUILD', 'LOGIN_SCAN', 'UNKNOWN', 'NOT_APPLICABLE', 'AUDIO', 'NONE', 'BIND', 'UI_ACTIONS'}), 'geckoAudioQueryTickMaxPhaseCode': frozenset({'QR_LAYOUT', 'NATIVE_SEND', 'STATE_BUILD', 'LOGIN_SCAN', 'UNKNOWN', 'NOT_APPLICABLE', 'AUDIO', 'NONE', 'BIND', 'UI_ACTIONS'}), 'geckoStartupResourceObservationStatus': frozenset({'EMPTY', 'OVERFLOW', 'NOT_APPLICABLE', 'UNSUPPORTED', 'OBSERVING', 'BOUNDED', 'COMPLETE', 'INVALIDATED'})}
PLAYER_NUMBERS_V19 = PLAYER_NUMBERS_V18 | EVIDENCE_NUMBERS_V19
SAMPLE_STRINGS_V19 = SAMPLE_STRINGS_V18 | EVIDENCE_ENUMS_V19.keys()

EVIDENCE_LIMITS_V20 = {'geckoAudioQueryObservedWorkCount': 1000000, 'geckoAudioQueryObservedWorkTotalMs': 86400000, 'geckoAudioQueryObservedWorkMaxMs': 86400000, 'geckoAudioQueryObservedWorkMaxPhaseMs': 86400000, 'geckoAudioQueryObservedWorkMaxScanNodes': 2800, 'geckoStartupResourceScannedCount': 1024, 'geckoStartupResourceFilteredCount': 1024, 'geckoStartupResourceMaxStartMs': 86400000, 'geckoStartupResourceMaxEndMs': 86400000}
EVIDENCE_NUMBERS_V20 = frozenset(EVIDENCE_LIMITS_V20)
EVIDENCE_ENUMS_V20 = {'geckoAudioQueryObservedWorkMaxPhaseCode': frozenset({'UI_ACTIONS', 'BIND', 'QR_LAYOUT', 'AUDIO', 'STATE_BUILD', 'NOT_APPLICABLE', 'LOGIN_SCAN', 'NONE', 'NATIVE_SEND', 'UNKNOWN'}), 'geckoStartupResourceMaxInitiatorCode': frozenset({'FETCH', 'XMLHTTPREQUEST', 'AUDIO', 'NOT_APPLICABLE', 'NONE', 'VIDEO', 'UNKNOWN'})}
PLAYER_NUMBERS_V20 = PLAYER_NUMBERS_V19 | EVIDENCE_NUMBERS_V20
SAMPLE_STRINGS_V20 = SAMPLE_STRINGS_V19 | EVIDENCE_ENUMS_V20.keys()


# v21 keeps DOM heartbeat/fault identity separate from Android host durations.
EVIDENCE_LIMITS_V21 = {'geckoAudioQueryJsTickCount': 1000000, 'geckoAudioQueryMaxJsTickGapMs': 86400000, 'geckoAudioRecoveryCount': 2, 'geckoAudioRetryRemainingMs': 30000, 'geckoAudioFirstFaultSeq': 9007199254740991, 'geckoAudioFirstFaultEpoch': 9007199254740991, 'geckoAudioFirstFaultNode': 9007199254740991, 'geckoAudioFirstFaultStartMs': 9007199254740991, 'geckoAudioFirstFaultSyncCallMs': 9007199254740991, 'geckoAudioFirstFaultReactionElapsedMs': 9007199254740991, 'geckoAudioFirstFaultTimeoutLateMs': 9007199254740991, 'geckoAudioFirstFaultJsTickCount': 1000000, 'geckoAudioFirstFaultMaxJsTickGapMs': 86400000, 'geckoCaptureSequence': 9007199254740991, 'geckoCaptureApiCallMs': 86400000, 'geckoCaptureIssueToListenerMs': 86400000, 'geckoCaptureClassifyMs': 86400000, 'geckoCapturePostLagMs': 86400000, 'geckoBridgePostLagMs': 86400000}
EVIDENCE_NUMBERS_V21 = frozenset(EVIDENCE_LIMITS_V21)
EVIDENCE_ENUMS_V21 = {key: frozenset(values) for key, values in {'geckoAudioFirstFaultState': ['NOT_APPLICABLE', 'CALLING', 'PENDING', 'TIMED_OUT', 'RESOLVED', 'REJECTED'], 'geckoAudioFirstFaultDisposition': ['NOT_APPLICABLE', 'TIMEOUT_FUSE', 'SLOW_CALL', 'SYNC_THROW', 'REJECTED_FUSE', 'INVALID_CLOCK', 'CLOCK_MISMATCH'], 'geckoAudioFirstFaultClockStatus': ['NOT_APPLICABLE', 'INSUFFICIENT_TIMEOUT', 'INSUFFICIENT_SLOW_CALL', 'INSUFFICIENT_REJECTED', 'INSUFFICIENT_INVALID_CLOCK', 'INSUFFICIENT_CLOCK_MISMATCH']}.items()}
PLAYER_NUMBERS_V21 = PLAYER_NUMBERS_V20 | EVIDENCE_NUMBERS_V21
SAMPLE_STRINGS_V21 = SAMPLE_STRINGS_V20 | EVIDENCE_ENUMS_V21.keys()
# v22: explain deliberately missing pixel evidence without claiming decoder output is a picture.
VISUAL_SAMPLE_BLOCKED_REASONS_V22 = frozenset({"unknown", "NONE", "DEVICE_GPU_READBACK_UNSAFE", "DEBUG_CONTROL"})
SAMPLE_STRINGS_V22 = SAMPLE_STRINGS_V21 | {"visualSampleBlockedReason"}

def _validate_v21_observations(item: dict, path: str) -> None:
    for key, limit in EVIDENCE_LIMITS_V21.items():
        if not -1 <= item[key] <= limit: _fail(path, "诊断恢复计数或时长超出范围")
    for key, values in EVIDENCE_ENUMS_V21.items():
        if item[key] not in values: _fail(path, "未知首故障枚举")
    for prefix in ("geckoAudioQuery", "geckoAudioFirstFault"):
        count, gap = item[prefix+"JsTickCount"], item[prefix+"MaxJsTickGapMs"]
        if (count <= 0 and gap != -1) or (count > 0 and gap < 0): _fail(path, "tick 缺失不能伪造事件循环间隔")
    if item["geckoAudioQueryState"] in {"NOT_APPLICABLE", "NOT_STARTED"} and item["geckoAudioQueryJsTickCount"] != -1:
        _fail(path, "无查询不能携带 tick 证据")
    prefix="geckoAudioFirstFault"
    if item[prefix+"Seq"] == -1:
        if any(v != (-1 if k in EVIDENCE_NUMBERS_V21 else "NOT_APPLICABLE") for k,v in item.items() if k.startswith(prefix)):
            _fail(path, "首故障缺失不能携带旧证据")
    elif item[prefix+"Seq"] <= 0 or any(item[prefix+k] < 0 for k in ("Epoch","Node","StartMs")) or             any(item[k] == "NOT_APPLICABLE" for k in EVIDENCE_ENUMS_V21):
        _fail(path, "首故障身份或固定归因缺失")
    if item["geckoCaptureSequence"] == 0 or (item["geckoCaptureSequence"] == -1 and
            any(v != -1 for k,v in item.items() if k.startswith("geckoCapture"))): _fail(path, "capture 身份与时序不一致")
    if item["geckoAudioRetryRemainingMs"] >= 0 and item["geckoAudioRecoveryCount"] not in {0,1}:
        _fail(path, "预算耗尽不能宣称等待恢复")

def _validate_v19_observations(item: dict, path: str) -> None:
    for key, limit in EVIDENCE_LIMITS_V19.items():
        if not -1 <= item[key] <= limit: _fail(path, "阶段计时或计数超出范围")
    for key, values in EVIDENCE_ENUMS_V19.items():
        if item[key] not in values: _fail(path, "未知阶段状态")
    for prefix, query in (("geckoBridgeLastTick", False), ("geckoAudioQueryTick", True)):
        total, code, maximum, scan = (item[prefix+k] for k in ("DurationMs", "MaxPhaseCode", "MaxPhaseDurationMs", "ScanNodes"))
        values = [total, maximum, scan] + ([item[prefix+"TailDurationMs"]] if query else [])
        if code in {"UNKNOWN", "NOT_APPLICABLE"}:
            if any(v != -1 for v in values): _fail(path, "未知阶段不能携带旧计时")
        elif min(values) < 0 or maximum > total or ((code == "NONE") != (maximum == 0)):
            _fail(path, "阶段计时与最大阶段不一致")
        if query and (item[prefix+"TailDurationMs"] > total or
                (item["geckoAudioQueryState"] in {"NOT_APPLICABLE", "NOT_STARTED"} and (code != "NOT_APPLICABLE" or any(v != -1 for v in values)))):
            _fail(path, "查询任务计时不一致")
    count, total, maximum = (item["geckoStartupObserver"+k] for k in ("TickCount", "TotalDurationMs", "MaxTickDurationMs"))
    if min(count,total,maximum)<0:
        if (count,total,maximum) != (-1,-1,-1): _fail(path, "启动计时必须整体未知")
    elif maximum>total or (count==0 and (total!=0 or maximum!=0)): _fail(path, "启动累计计时不一致")
    status=item["geckoStartupResourceObservationStatus"]
    count, maximum, wait, download, detailed=(item["geckoStartupResource"+k] for k in ("Count","MaxDurationMs","MaxResponseWaitMs","MaxDownloadMs","DetailedCount"))
    if status in {"NOT_APPLICABLE","UNSUPPORTED","INVALIDATED"}:
        if (count,maximum,wait,download,detailed)!=(-1,-1,-1,-1,-1): _fail(path, "不可用资源不能携带旧计时")
    elif count<0 or detailed<0 or detailed>count or ((count==0)!=(maximum==-1)) or \
            (detailed==0 and (wait!=-1 or download!=-1)) or (detailed>0 and min(wait,download)<0) or \
            (status=="EMPTY" and count!=0) or (status in {"COMPLETE","BOUNDED"} and count==0):
        _fail(path, "资源数量、细分时序或状态不一致")


def _validate_v20_observations(item: dict, path: str) -> None:
    for key, limit in EVIDENCE_LIMITS_V20.items():
        if not -1 <= item[key] <= limit: _fail(path, "窗口或资源计数超出范围")
    for key, values in EVIDENCE_ENUMS_V20.items():
        if item[key] not in values: _fail(path, "未知窗口或资源枚举")
    prefix="geckoAudioQueryObservedWork"
    count,total,maximum,phase,scan=(item[prefix+k] for k in ("Count","TotalMs","MaxMs","MaxPhaseMs","MaxScanNodes"))
    code=item[prefix+"MaxPhaseCode"]
    if code in {"UNKNOWN","NOT_APPLICABLE"}:
        if (count,total,maximum,phase,scan)!=(-1,-1,-1,-1,-1): _fail(path, "未知窗口不能携带旧累计")
    elif min(count,total,maximum,phase,scan)<0 or not phase<=maximum<=total or ((code=="NONE")!=(phase==0)) or \
            (count==0 and ((total,maximum,phase,scan)!=(0,0,0,0) or code!="NONE")):
        _fail(path, "窗口累计与独立最大值不一致")
    if item["geckoAudioQueryState"] in {"NOT_APPLICABLE","NOT_STARTED"} and code!="NOT_APPLICABLE":
        _fail(path, "无查询不能携带窗口累计")
    prefix="geckoStartupResource"
    scanned,filtered,start,end=(item[prefix+k] for k in ("ScannedCount","FilteredCount","MaxStartMs","MaxEndMs"))
    code=item[prefix+"MaxInitiatorCode"];count=item[prefix+"Count"]
    if code in {"UNKNOWN","NOT_APPLICABLE"}:
        if (scanned,filtered,start,end)!=(-1,-1,-1,-1): _fail(path, "未知资源关联不能携带旧时序")
    elif scanned<0 or filtered<0 or scanned!=count+filtered:
        _fail(path, "扫描、接纳与过滤数量不一致")
    elif count==0 and ((start,end)!=(-1,-1) or code!="NONE"):
        _fail(path, "零候选资源不能携带最长请求")
    elif count>0 and (code=="NONE" or start<0 or end<start or abs(end-start-item[prefix+"MaxDurationMs"])>1):
        _fail(path, "最长候选时序必须对应同一个条目")
    if item[prefix+"ObservationStatus"] in {"NOT_APPLICABLE","UNSUPPORTED","INVALIDATED"} and code!="NOT_APPLICABLE":
        _fail(path, "不可用资源不能携带候选归类")


def _validate_samples(samples: Any, version: int) -> None:
    items = _list(samples, "report.samples", MAX_SAMPLES)
    base_required = {"kind", "elapsedMs", "timeMs", "frames", "width", "height", "readyState", "paused", "buffering", "errorCode"}
    v2_required = base_required | PLAYER_NUMBERS_V2
    v3_required = {"kind"} | SAMPLE_BASE_NUMBERS | PLAYER_NUMBERS_V3 | SAMPLE_BOOLEANS | SAMPLE_STRINGS_V3
    v4_required = {"kind"} | SAMPLE_BASE_NUMBERS | PLAYER_NUMBERS_V4 | SAMPLE_BOOLEANS_V4 | SAMPLE_NULLABLE_BOOLEANS_V4 | SAMPLE_STRINGS_V4
    v5_required = {"kind"} | SAMPLE_BASE_NUMBERS | PLAYER_NUMBERS_V5 | SAMPLE_BOOLEANS_V4 | SAMPLE_NULLABLE_BOOLEANS_V4 | SAMPLE_STRINGS_V5
    v10_required = {"kind"} | SAMPLE_BASE_NUMBERS | PLAYER_NUMBERS_V10 | SAMPLE_BOOLEANS_V10 | SAMPLE_NULLABLE_BOOLEANS_V4 | SAMPLE_STRINGS_V10
    # v11：在 v10 基础上追加合成层/决策层/上下文层三组证据（这些字段在 v11 为必填）。
    v11_required = v10_required | PLAYER_NUMBERS_V11 | SAMPLE_BOOLEANS_V11 | SAMPLE_STRINGS_V11
    v12_required = v11_required | PLAYER_NUMBERS_V12 | SAMPLE_STRINGS_V12
    # v13：在 v12 基础上追加央视频选台点击布尔事实（这两个字段在 v13 为必填）。
    v13_required = v12_required | SAMPLE_BOOLEANS_V13
    v14_required = v13_required | PLAYER_NUMBERS_V14 | SAMPLE_STRINGS_V14
    v15_required = v14_required | PLAYER_NUMBERS_V15 | SAMPLE_STRINGS_V15
    v16_required = v15_required | PLAYER_NUMBERS_V16 | SAMPLE_STRINGS_V16
    v17_required = v16_required | EVIDENCE_NUMBERS_V17 | EVIDENCE_BOOLEANS_V17 | EVIDENCE_ENUMS_V17.keys()
    v18_required = v17_required | EVIDENCE_NUMBERS_V18 | EVIDENCE_BOOLEANS_V18 | EVIDENCE_ENUMS_V18.keys()
    v19_required = v18_required | EVIDENCE_NUMBERS_V19 | EVIDENCE_ENUMS_V19.keys()
    v20_required = v19_required | EVIDENCE_NUMBERS_V20 | EVIDENCE_ENUMS_V20.keys()
    v21_required = v20_required | EVIDENCE_NUMBERS_V21 | EVIDENCE_ENUMS_V21.keys()
    v22_required = v21_required | {"visualSampleBlockedReason"}
    allowed_numbers = SAMPLE_BASE_NUMBERS if version == 1 else SAMPLE_BASE_NUMBERS | (
        PLAYER_NUMBERS_V2 if version == 2 else PLAYER_NUMBERS_V3 if version == 3 else
        PLAYER_NUMBERS_V4 if version == 4 else PLAYER_NUMBERS_V21 if version >= 21 else PLAYER_NUMBERS_V20 if version >= 20 else PLAYER_NUMBERS_V19 if version >= 19 else PLAYER_NUMBERS_V18 if version >= 18 else PLAYER_NUMBERS_V17 if version >= 17 else PLAYER_NUMBERS_V16 if version >= 16 else PLAYER_NUMBERS_V15 if version >= 15 else PLAYER_NUMBERS_V14 if version >= 14 else PLAYER_NUMBERS_V12 if version >= 12 else PLAYER_NUMBERS_V11 if version >= 11 else PLAYER_NUMBERS_V10 if version >= 10 else PLAYER_NUMBERS_V5
    )
    kinds = SAMPLE_KINDS_V1_V2 if version < 3 else SAMPLE_KINDS_V21 if version >= 21 else SAMPLE_KINDS_V12 if version >= 12 else SAMPLE_KINDS_V11 if version >= 11 else SAMPLE_KINDS_V10 if version >= 10 else SAMPLE_KINDS_V3
    for index, raw in enumerate(items):
        path = f"report.samples[{index}]"
        item = _object(raw, path)
        kind = item.get("kind")
        if kind not in kinds: _fail(f"{path}.kind", f"必须是以下枚举之一: {sorted(kinds)!r}")
        if version >= 3 and kind == "PROCESS_RECOVERED":
            _exact(item, path, {"kind", "elapsedMs"}); _int(item["elapsedMs"], f"{path}.elapsedMs"); continue
        required = base_required if version == 1 else v2_required if version == 2 else \
            v3_required if version == 3 else v4_required if version == 4 else \
            v22_required if version >= 22 else v21_required if version >= 21 else v20_required if version >= 20 else v19_required if version >= 19 else v18_required if version >= 18 else v17_required if version >= 17 else v16_required if version >= 16 else v15_required if version >= 15 else v14_required if version >= 14 else v13_required if version >= 13 else v12_required if version >= 12 else v11_required if version >= 11 else v10_required if version >= 10 else v5_required
        _exact(item, path, required)
        for key in allowed_numbers:
            if key in item: _int(item[key], f"{path}.{key}")
        for key in SAMPLE_BOOLEANS:
            if key in item and not isinstance(item[key], bool): _fail(f"{path}.{key}", "必须是布尔值")
        if version >= 4:
            for key in SAMPLE_BOOLEANS_V18 if version >= 18 else SAMPLE_BOOLEANS_V17 if version >= 17 else SAMPLE_BOOLEANS_V13 if version >= 13 else SAMPLE_BOOLEANS_V11 if version >= 11 else SAMPLE_BOOLEANS_V10 if version >= 10 else SAMPLE_BOOLEANS_V4:
                if not isinstance(item[key], bool): _fail(f"{path}.{key}", "必须是布尔值")
            for key in SAMPLE_NULLABLE_BOOLEANS_V4:
                _bool_or_null(item[key], f"{path}.{key}")
        string_fields = SAMPLE_STRINGS_V22 if version >= 22 else SAMPLE_STRINGS_V21 if version >= 21 else SAMPLE_STRINGS_V20 if version >= 20 else SAMPLE_STRINGS_V19 if version >= 19 else SAMPLE_STRINGS_V18 if version >= 18 else SAMPLE_STRINGS_V17 if version >= 17 else SAMPLE_STRINGS_V16 if version >= 16 else SAMPLE_STRINGS_V15 if version >= 15 else SAMPLE_STRINGS_V14 if version >= 14 else SAMPLE_STRINGS_V12 if version >= 12 else SAMPLE_STRINGS_V11 if version >= 11 else SAMPLE_STRINGS_V10 if version >= 10 else SAMPLE_STRINGS_V5 if version == 5 else SAMPLE_STRINGS_V4 if version == 4 else SAMPLE_STRINGS_V3
        for key in string_fields:
            if key not in item: continue
            if key == "sourceHash":
                value = _string(item[key], f"{path}.{key}", allow_empty=True)
                if not SOURCE_HASH_RE_V10.fullmatch(value): _fail(f"{path}.{key}", "必须为空或十六进制摘要")
            elif version >= 14 and key in {"probeObservationStatus", "avSyncAssessment", "geckoAudioClockStatus", "nativeAudioClockStatus"}:
                # Fixed enums are checked below, not treated as arbitrary token-shaped text.
                _string(item[key], f"{path}.{key}")
            elif version >= 10:
                _v10_technical(item[key], f"{path}.{key}")
            else:
                _technical(item[key], f"{path}.{key}")
        if version in {5, 10}:
            if item["webState"] not in WEB_STATES_V5: _fail(f"{path}.webState", "必须是已定义网页状态")
            if item["webRecoveryStage"] not in WEB_RECOVERY_STAGES_V5: _fail(f"{path}.webRecoveryStage", "必须是已定义恢复阶段")
        if version >= 10:
            enum_fields = {
                "probeObservationStatus": PROBE_OBSERVATION_STATUSES_V15 if version >= 15 else PROBE_OBSERVATION_STATUSES_V14,
                "avSyncAssessment": AV_SYNC_ASSESSMENTS_V15 if version >= 15 else AV_SYNC_ASSESSMENTS_V14,
                "geckoAudioClockStatus": GECKO_AUDIO_CLOCK_STATUSES_V15,
                "nativeAudioClockStatus": NATIVE_AUDIO_CLOCK_STATUSES_V16,
                "mirrorWatchdogReason": MIRROR_WATCHDOG_REASONS_V10,
                "mirrorStage": MIRROR_STAGES_V10,
                "mirrorNavigationState": MIRROR_NAVIGATION_STATES_V10,
                "mirrorExtensionState": MIRROR_EXTENSION_STATES_V10,
                "geckoPlaybackStrategy": GECKO_PLAYBACK_STRATEGIES_V10,
                "geckoPresentationMode": GECKO_PRESENTATION_MODES_V10,
                "prefetchOutcome": PREFETCH_OUTCOMES_V10,
                "rebufferReason": REBUFFER_REASONS_V10,
                "rebufferUnstableReason": REBUFFER_UNSTABLE_REASONS_V10,
                "recoveryReason": RECOVERY_REASONS_V10,
                "visualSampleOutcome": VISUAL_SAMPLE_OUTCOMES_V11,
                "watchdogDecision": WATCHDOG_DECISIONS_V11,
                "sampleSkipReason": SAMPLE_SKIP_REASONS_V11,
                "networkTransport": NETWORK_TRANSPORTS_V11,
                "userVisibleState": USER_VISIBLE_STATES_V12,
                "greyScreenReason": GREY_SCREEN_REASONS_V10,
            }
            for key, allowed in enum_fields.items():
                if key not in item: continue
                if item[key] not in allowed: _fail(f"{path}.{key}", "必须是已定义枚举")
        if version >= 22:
            reason = item["visualSampleBlockedReason"]
            if reason not in VISUAL_SAMPLE_BLOCKED_REASONS_V22:
                _fail(f"{path}.visualSampleBlockedReason", "必须是已定义阻断原因")
            if reason in {"DEVICE_GPU_READBACK_UNSAFE", "DEBUG_CONTROL"} and (
                item["visualSampleOutcome"] != "NO_SAMPLE" or item["visualSampleCallbackMs"] != -1 or
                item["millisSincePictureVerified"] != -1
            ):
                _fail(path, "阻断像素观测必须是未采样且没有历史画面时长")
        if version >= 14:
            for key in ("videoCallbackAgeMs", "videoPresentationAgeMs"):
                if not -1 <= item[key] <= 9_007_199_254_740_991:
                    _fail(f"{path}.{key}", "必须是 -1 或有界网页时长")
            if not -1_000_000 <= item["videoExpectedDisplayDelayUs"] <= 1_000_000:
                _fail(f"{path}.videoExpectedDisplayDelayUs", "必须是有界相对网页时长")
        if version >= 15:
            for key in GECKO_AUDIO_NUMBERS_V15:
                minimum = -9_007_199_254_740_991 if key == "geckoAudioMinusVideoUs" else -1
                if not minimum <= item[key] <= 9_007_199_254_740_991:
                    _fail(f"{path}.{key}", "必须是有界整数测量或未知哨兵")
            if item["geckoAudioClockStatus"] == "AVAILABLE":
                if item["geckoAudioClockUs"] <= 0 or item["geckoPairedVideoPtsUs"] < 0:
                    _fail(path, "可用引擎时钟必须有非零时钟及配对帧")
                if not 0 <= item["geckoAudioClockAgeMs"] <= 5000:
                    _fail(path, "可用引擎时钟必须新鲜")
                if not 0 <= item["geckoAudioClockCallDurationMs"] <= 100 or not 0 <= item["geckoAvPairSpanMs"] <= 100:
                    _fail(path, "可用引擎时钟必须有有界采集和配对窗口")
                if item["geckoAudioMinusVideoUs"] != item["geckoAudioClockUs"] - item["geckoPairedVideoPtsUs"]:
                    _fail(path, "差值必须对应同次配对时钟和帧")
                if item["probeObservationStatus"] != "VALID" or item["avSyncAssessment"] != "UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED":
                    _fail(path, "可用引擎时钟不能声明物理音画同步")

        if version >= 16:
            for key in NATIVE_LIVE_NUMBERS_V16:
                if not -1 <= item[key] <= 9_007_199_254_740_991:
                    _fail(f"{path}.{key}", "必须是有界直播上下文或未知哨兵")
            if item["nativeLastLoadErrorCode"] != -1 and not 100 <= item["nativeLastLoadErrorCode"] <= 599:
                _fail(path, "加载错误只保留 HTTP 数字或未知哨兵")
            for key in NATIVE_AUDIO_NUMBERS_V16:
                minimum = -9_007_199_254_740_991 if key in {"nativeAudioStreamOffsetUs", "nativeAudioMinusVideoUs"} else -1_000_000 if key == "nativeVideoReleaseDelayUs" else -1
                maximum = 100_000 if key == "nativeAvPairSpanUs" else 1_000_000 if key == "nativeVideoReleaseDelayUs" else 9_007_199_254_740_991
                if not minimum <= item[key] <= maximum:
                    _fail(f"{path}.{key}", "必须是有界原生测量或未知哨兵")
            if item["nativeAudioClockStatus"] == "AVAILABLE":
                if min(item["nativeAvGeneration"], item["nativeAudioClockUs"], item["nativePairedVideoPtsUs"], item["nativeAvPairSpanUs"]) < 0:
                    _fail(path, "可用原生时钟必须有同代的配对帧")
                if not 0 <= item["nativeAudioClockAgeMs"] <= 1500:
                    _fail(path, "可用原生时钟必须新鲜")
                if item["nativeAudioMinusVideoUs"] != item["nativeAudioClockUs"] - item["nativePairedVideoPtsUs"]:
                    _fail(path, "差值必须对应固定原生配对快照")
                if item["avSyncAssessment"] != "UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED" or item["geckoAudioClockStatus"] != "NOT_APPLICABLE" or item["probeObservationStatus"] != "NOT_APPLICABLE":
                    _fail(path, "原生游标和待渲染帧不能声明物理音画同步")
            else:
                for key in NATIVE_AUDIO_NUMBERS_V16 - {"nativeAvGeneration"}:
                    if item[key] != -1:
                        _fail(f"{path}.{key}", "原生测量未知时必须使用未知哨兵")


        if version >= 17:
            for key in EVIDENCE_NUMBERS_V17:
                minimum = -9_007_199_254_740_991 if key == "nativeLoadWindowStartInPeriodMs" else -1
                if not minimum <= item[key] <= 9_007_199_254_740_991:
                    _fail(f"{path}.{key}", "必须是有界计时或未知哨兵")
            for key, allowed in EVIDENCE_ENUMS_V17.items():
                if item[key] not in allowed: _fail(f"{path}.{key}", "必须是已定义枚举")
            if item["geckoAudioClockStatus"] != "AVAILABLE":
                for key in ("geckoAudioClockUs", "geckoPairedVideoPtsUs", "geckoAudioMinusVideoUs", "geckoAvPairSpanMs"):
                    if item[key] != -1: _fail(path, "未知 Gecko 音画配对必须使用未知哨兵")
            if item["nativeLoadActiveStatus"] not in {"LOADING", "RETRYING"}:
                for key in ("nativeLoadActiveStartElapsedMs", "nativeLoadActiveMediaStartMs",
                            "nativeLoadActiveMediaEndMs", "nativeLoadActiveRetryCount"):
                    if item[key] != -1: _fail(path, "活动任务未知时不能复用旧任务数值")
                if item["nativeLoadActiveTrackType"] != "UNKNOWN": _fail(path, "未知任务不能携带轨道")
            if item["nativeLoadLastOutcome"] in {"NOT_APPLICABLE", "NONE", "INVALIDATED"}:
                for key in ("nativeLoadLastStartElapsedMs", "nativeLoadLastEndElapsedMs",
                            "nativeLoadLastMediaStartMs", "nativeLoadLastMediaEndMs",
                            "nativeLoadLastRetryCount", "nativeLoadLastBytes", "nativeLoadLastHttpStatus"):
                    if item[key] != -1: _fail(path, "失效任务不能复用旧终态数值")
                if item["nativeLoadLastTrackType"] != "UNKNOWN": _fail(path, "失效任务不能携带轨道")
            if item["nativeLoadLastHttpStatus"] != -1 and not 100 <= item["nativeLoadLastHttpStatus"] <= 599:
                _fail(path, "任务错误只保留 HTTP 数字或未知哨兵")
            if item["nativeLoadPositionStatus"] == "AVAILABLE":
                if item["timeMs"] < 0 or item["nativeLiveWindowDurationMs"] < 0:
                    _fail(path, "窗口相对位置必须有同次播放位置与窗口")
            elif item["nativeLoadPositionStatus"] == "WINDOW_ONLY":
                if item["timeMs"] != -1 or item["nativeLiveWindowDurationMs"] < 0:
                    _fail(path, "仅窗口可知时必须保留未知播放位置与有效窗口")
            elif item["nativeLoadWindowStartInPeriodMs"] != -1:
                _fail(path, "窗口位置未知时必须使用未知哨兵")
            for start, end in (("nativeLoadLastStartElapsedMs", "nativeLoadLastEndElapsedMs"),
                               ("nativeTransferStartElapsedMs", "nativeTransferEndElapsedMs")):
                if min(item[start], item[end]) >= 0 and item[end] < item[start]:
                    _fail(path, "同一任务或传输的结束时间不能早于开始")
            if item["nativeTransferOutcome"] == "CLOSED_EOF" and not item["nativeTransferEofSeen"]:
                _fail(path, "EOF 结束必须实际观察 EOF")
            if item["nativeTransferOutcome"] == "CLOSED_NO_EOF" and item["nativeTransferEofSeen"]:
                _fail(path, "未观察 EOF 的结束不能声明 EOF")
            transfer_numbers = ("nativeTransferSequence", "nativeTransferStartElapsedMs",
                "nativeTransferEndElapsedMs", "nativeTransferOpenDurationUs", "nativeTransferReadDurationUs",
                "nativeTransferCloseDurationUs", "nativeTransferReadCallCount", "nativeTransferBytes")
            if item["nativeTransferOutcome"] in {"NA", "NONE", "INVALIDATED"}:
                if any(item[key] != -1 for key in transfer_numbers) or item["nativeTransferEofSeen"]:
                    _fail(path, "失效传输不能复用旧摘要")
            else:
                if item["nativeTransferTaskMatchStatus"] != "UNASSIGNED":
                    _fail(path, "独立读取摘要不能冒充任务配对")
                if min(item["nativeTransferSequence"], item["nativeTransferReadCallCount"],
                       item["nativeTransferBytes"]) < 0:
                    _fail(path, "实际传输必须有序号与字节计数")
            query_numbers = ("geckoAudioQuerySeq", "geckoAudioQueryEpoch", "geckoAudioQueryNode",
                "geckoAudioQueryStartMs", "geckoAudioSyncCallMs", "geckoAudioReactionElapsedMs",
                "geckoAudioTimeoutLateMs")
            if item["geckoAudioQueryState"] in {"NOT_APPLICABLE", "NOT_STARTED"}:
                if any(item[key] != -1 for key in query_numbers) or item["geckoAudioInFlight"]:
                    _fail(path, "无查询状态不能携带活动请求")
            elif item["geckoAudioQuerySeq"] <= 0 or min(item["geckoAudioQueryEpoch"],
                    item["geckoAudioQueryNode"], item["geckoAudioQueryStartMs"]) < 0:
                _fail(path, "实际查询必须有自己的序号及节点代际")

        if version >= 18:
            for key in EVIDENCE_NUMBERS_V18:
                limit = 1_000_000 if key == "geckoAudioQueryRvfcCount" else 86_400_000 if key == "geckoAudioQueryMaxRvfcGapMs" else 9_007_199_254_740_991
                if not -1 <= item[key] <= limit: _fail(f"{path}.{key}", "必须是有界请求事实或未知哨兵")
            for key, allowed in EVIDENCE_ENUMS_V18.items():
                if item[key] not in allowed: _fail(f"{path}.{key}", "必须是已定义枚举")
            request_numbers = ("nativeMediaRequestOpenDurationUs", "nativeMediaRequestReadDurationUs",
                "nativeMediaRequestCloseDurationUs", "nativeMediaRequestReadCallCount", "nativeMediaRequestBytes")
            if item["nativeMediaRequestMatchStatus"] == "MATCHED":
                if item["nativeLoadLastOutcome"] != "COMPLETED" or item["nativeMediaRequestCoverage"] != "REQUEST_ONLY":
                    _fail(path, "关联请求只能附着最近已完成媒体任务且不表示全任务覆盖")
                if item["nativeMediaRequestOutcome"] not in {"CLOSED_EOF", "CLOSED_NO_EOF", "OPEN_ERROR", "READ_ERROR", "CLOSE_ERROR"}:
                    _fail(path, "关联请求必须具有终态")
                if min(item["nativeMediaRequestReadCallCount"], item["nativeMediaRequestBytes"]) < 0:
                    _fail(path, "关联请求必须保留实测读取事实")
                if item["nativeMediaRequestOutcome"] == "CLOSED_EOF" and not item["nativeMediaRequestEofSeen"]:
                    _fail(path, "EOF终态必须实际观测EOF")
                if item["nativeMediaRequestOutcome"] == "CLOSED_NO_EOF" and item["nativeMediaRequestEofSeen"]:
                    _fail(path, "未EOF终态不能声称EOF")
            elif item["nativeMediaRequestCoverage"] != "UNKNOWN" or item["nativeMediaRequestOutcome"] != "NA" or any(item[k] != -1 for k in request_numbers) or item["nativeMediaRequestEofSeen"]:
                _fail(path, "未关联请求不能复用已关联统计")
            if item["geckoAudioQueryState"] in {"NOT_APPLICABLE", "NOT_STARTED"}:
                if item["geckoAudioQueryRvfcCount"] != -1 or item["geckoAudioQueryMaxRvfcGapMs"] != -1:
                    _fail(path, "未发起查询不能携带回调事实")
            if item["geckoAudioQueryRvfcCount"] == -1 and item["geckoAudioQueryMaxRvfcGapMs"] != -1:
                _fail(path, "未知回调身份不能携带间隔")

        if version >= 19: _validate_v19_observations(item, path)
        if version >= 20: _validate_v20_observations(item, path)
        if version >= 21: _validate_v21_observations(item, path)


def _validate_v3_nested(root: dict[str, Any], version: int) -> None:
    app = _exact(root["app"], "report.app", {"name", "applicationId", "buildType", "versionName", "versionCode", "gitRevision", "buildTimeEpochMs", "firstInstallTimeMs", "lastUpdateTimeMs"})
    if version >= 10:
        if app["name"] != "看电视": _fail("report.app.name", "必须是固定应用标识")
        for key in {"applicationId", "buildType", "versionName"}: _v10_technical(app[key], f"report.app.{key}")
        git_revision = _string(app["gitRevision"], "report.app.gitRevision")
        if not GIT_REVISION_RE_V10.fullmatch(git_revision): _fail("report.app.gitRevision", "必须是受限版本标识")
    else:
        for key in {"name", "applicationId", "buildType", "versionName", "gitRevision"}: _string(app[key], f"report.app.{key}")
    for key in {"versionCode", "buildTimeEpochMs", "firstInstallTimeMs", "lastUpdateTimeMs"}: _int(app[key], f"report.app.{key}")
    device = _exact(root["device"], "report.device", {"manufacturer", "model", "product", "board", "hardware", "socManufacturer", "socModel", "androidRelease", "apiLevel", "securityPatch", "abis", "cpuCoreCount", "totalRamMb", "availableRamMb", "lowRamDevice", "lowMemory", "thermalStatus"})
    opaque_device_fields = {"manufacturer", "model", "product", "board", "hardware", "socManufacturer", "socModel"}
    for key in opaque_device_fields:
        (_v10_device_digest if version >= 10 else _string)(device[key], f"report.device.{key}")
    for key in {"androidRelease", "securityPatch"}:
        (_v10_technical if version >= 10 else _string)(device[key], f"report.device.{key}")
    for value in _list(device["abis"], "report.device.abis", 8):
        (_v10_technical if version >= 10 else _string)(value, "report.device.abis[]")
    for key in {"apiLevel", "cpuCoreCount", "totalRamMb", "availableRamMb", "thermalStatus"}: _int(device[key], f"report.device.{key}")
    _bool_or_null(device["lowRamDevice"], "report.device.lowRamDevice")
    _bool_or_null(device["lowMemory"], "report.device.lowMemory")
    display = _exact(root["display"], "report.display", {"width", "height", "refreshRate", "modeId", "availableModes", "hdrTypes"})
    for key in {"width", "height", "modeId"}: _int(display[key], f"report.display.{key}")
    _number(display["refreshRate"], "report.display.refreshRate")
    for value in _list(display["availableModes"], "report.display.availableModes", 32):
        (_v10_technical if version >= 10 else _string)(value, "report.display.availableModes[]")
    for value in _list(display["hdrTypes"], "report.display.hdrTypes", 16): _int(value, "report.display.hdrTypes[]")
    runtime = _exact(root["runtime"], "report.runtime", {"networkTransports", "networkMetered", "audioOutputRoute", "audioOutputMode", "audioEncodingMode", "audioTunneling", "audioTrackPositionFrames", "audioTimestampAvailable", "audioOutputSampleRate", "audioOutputChannelCount"})
    for value in _list(runtime["networkTransports"], "report.runtime.networkTransports", 8):
        (_v10_technical if version >= 10 else _string)(value, "report.runtime.networkTransports[]")
    _bool_or_null(runtime["networkMetered"], "report.runtime.networkMetered")
    for key in {"audioOutputRoute", "audioOutputMode", "audioEncodingMode"}:
        (_v10_technical if version >= 10 else _string)(runtime[key], f"report.runtime.{key}")
    _bool_or_null(runtime["audioTunneling"], "report.runtime.audioTunneling")
    for key in {"audioTrackPositionFrames", "audioTimestampAvailable", "audioOutputSampleRate", "audioOutputChannelCount"}: _int(runtime[key], f"report.runtime.{key}")
    library_fields = {"media3", "ffmpegExtension", "webView"} if version >= 10 else {"media3", "libvlc", "ffmpegExtension", "webView"}
    libraries = _exact(root["libraries"], "report.libraries", library_fields)
    for key, value in libraries.items():
        (_v10_technical if version >= 10 else _string)(value, f"report.libraries.{key}")
    for index, raw in enumerate(_list(root["decoderCapabilities"], "report.decoderCapabilities", 96)):
        path = f"report.decoderCapabilities[{index}]"
        decoder = _exact(raw, path, {"name", "types", "hardwareAccelerated", "softwareOnly"})
        (_v10_technical if version >= 10 else _technical)(decoder["name"], f"{path}.name")
        for value in _list(decoder["types"], f"{path}.types", 16):
            (_v10_technical if version >= 10 else _technical)(value, f"{path}.types[]")
        _bool_or_null(decoder["hardwareAccelerated"], f"{path}.hardwareAccelerated")
        _bool_or_null(decoder["softwareOnly"], f"{path}.softwareOnly")

def validate_report(payload: bytes) -> ValidatedReport:
    try: document = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc: raise SchemaError("body: 必须是 UTF-8 JSON") from exc
    root = _object(document, "report")
    version = root.get("schemaVersion")
    if type(version) is not int or version not in SUPPORTED_SCHEMA_VERSIONS: _fail("report.schemaVersion", "必须为已支持版本")
    required = ROOT_V3
    _exact(root, "report", required)
    _reject_forbidden_keys(root)
    report_id = _uuid(root["reportId"], "report.reportId")
    reasons = REASONS_V3
    if not isinstance(root["reason"], str) or root["reason"] not in reasons:
        _fail("report.reason", f"必须是以下枚举之一: {sorted(reasons)!r}")
    for key in {"capturedAtMs", "appVersionCode", "deviceApiLevel", "sessionId"}: _int(root[key], f"report.{key}")
    if version >= 10:
        _v10_technical(root["appVersionName"], "report.appVersionName")
        _normalize_v10_device_identities(root)
        _v10_device_digest(root["deviceManufacturer"], "report.deviceManufacturer")
        _v10_device_digest(root["deviceModel"], "report.deviceModel")
    else:
        for key in {"appVersionName", "deviceManufacturer", "deviceModel"}: _string(root[key], f"report.{key}")
    if not isinstance(root["channelId"], str) or not CHANNEL_ID_RE.fullmatch(root["channelId"]): _fail("report.channelId", "必须是受限频道 ID")
    providers = PROVIDERS_V2
    if not isinstance(root["provider"], str) or root["provider"] not in providers:
        _fail("report.provider", f"必须是以下枚举之一: {sorted(providers)!r}")
    if version >= 2:
        install_id = _uuid(root["deviceInstallId"], "report.deviceInstallId")
        if version >= 10:
            device_label = _string(root["deviceLabel"], "report.deviceLabel")
            if not INSTALL_LABEL_RE_V10.fullmatch(device_label) or device_label != f"install-{install_id[:8]}":
                _fail("report.deviceLabel", "必须是安装标识的固定短形式")
            _v10_technical(root["deviceCpuAbi"], "report.deviceCpuAbi")
        else:
            for key in {"deviceLabel", "deviceCpuAbi"}: _string(root[key], f"report.{key}")
        for key in {"deviceTotalRamMb", "displayWidth", "displayHeight"}: _int(root[key], f"report.{key}")
    if version >= 3:
        for key in {"closedAtMs", "fragmentSequence", "fragmentStartedElapsedMs", "fragmentEndedElapsedMs"}: _int(root[key], f"report.{key}")
        if not isinstance(root["lifecycle"], str) or root["lifecycle"] not in LIFECYCLES_V3:
            _fail("report.lifecycle", f"必须是以下枚举之一: {sorted(LIFECYCLES_V3)!r}")
        _validate_v3_nested(root, version)
        if version >= 10:
            if root["appVersionName"] != root["app"]["versionName"] or root["appVersionCode"] != root["app"]["versionCode"]:
                _fail("report.app", "版本字段必须与顶层一致")
            if root["deviceManufacturer"] != root["device"]["manufacturer"] or root["deviceModel"] != root["device"]["model"]:
                _fail("report.device", "设备字段必须与顶层一致")
    _validate_samples(root["samples"], version)
    try: canonical = json.dumps(root, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")
    except (TypeError, ValueError) as exc: raise SchemaError("report: 无法规范化") from exc
    return ValidatedReport(report_id, canonical)
