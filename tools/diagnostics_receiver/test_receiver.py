from __future__ import annotations

import http.client
import errno
import hashlib
import io
import json
import tempfile
import threading
import unittest
import uuid
import zipfile
from pathlib import Path
from unittest.mock import patch

import receiver

from receiver import ReceiverConfig, _failure_path, create_server
from schema import GECKO_AUDIO_NUMBERS_V15, SchemaError, validate_report


def report(report_id: str | None = None) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "reportId": report_id or str(uuid.uuid4()),
        "reason": "NO_FIRST_FRAME_30S",
        "capturedAtMs": 1,
        "appVersionName": "0.1.0",
        "appVersionCode": 1,
        "deviceManufacturer": "Acme",
        "deviceModel": "TV",
        "deviceApiLevel": 35,
        "channelId": "cctv-1",
        "provider": "CCTV",
        "sessionId": 1,
        "samples": [{"kind": "START", "elapsedMs": 1, "timeMs": -1, "frames": 0, "width": 0, "height": 0, "readyState": 1, "paused": False, "buffering": True, "errorCode": 1001}],
    }


def report_v2(report_id: str | None = None) -> dict[str, object]:
    payload = report(report_id)
    payload.update(
        {
            "schemaVersion": 2,
            "deviceInstallId": str(uuid.uuid4()),
            "deviceLabel": "TCL TV · a1b2c3",
            "deviceCpuAbi": "arm64-v8a",
            "deviceTotalRamMb": 4096,
            "displayWidth": 3840,
            "displayHeight": 2160,
            "provider": "M3U",
        }
    )
    payload["samples"] = [
        {
            "kind": "SAMPLE",
            "elapsedMs": 1,
            "timeMs": 20_000,
            "frames": 21,
            "width": 1920,
            "height": 1080,
            "readyState": 4,
            "paused": False,
            "buffering": False,
            "errorCode": 0,
            "droppedFrames": 2,
            "presentedFrames": 21,
            "jsSampleAgeMs": -1,
            "processPssKb": 123_456,
            "rendererState": -1,
            "kernelCode": 2,
            "deliveryCode": 1,
            "videoProgressCount": 21,
            "decodedFrames": 24,
            "displayedFrames": 21,
            "readBytes": 3_000_000,
            "demuxReadBytes": 2_900_000,
            "inputKbps": 9300,
            "demuxKbps": 9200,
            "demuxCorrupted": 0,
            "demuxDiscontinuity": 0,
            "decodedAudio": 1000,
            "playedAudioBuffers": 998,
            "lostAudioBuffers": 0,
            "selectedAudioTrackId": 147,
            "configuredAudioDelayUs": 0,
            "sourceAudioPid1": 147,
            "sourceAudioMinusVideoMs1": -360,
            "sourceAudioPid2": 148,
            "sourceAudioMinusVideoMs2": -320,
        }
    ]
    return payload


def report_v3(report_id: str | None = None) -> dict[str, object]:
    payload = report_v2(report_id)
    payload.update({
        "schemaVersion": 3,
        "reason": "STOPPED",
        "lifecycle": "COMPLETE",
        "closedAtMs": 2,
        "fragmentSequence": 0,
        "fragmentStartedElapsedMs": 1,
        "fragmentEndedElapsedMs": 2,
        "app": {
            "name": "看电视", "applicationId": "com.kantv.app.diagnostic", "buildType": "diagnostic",
            "versionName": "0.1.6-diagnostic", "versionCode": 7, "gitRevision": "abc123-dirty",
            "buildTimeEpochMs": 1, "firstInstallTimeMs": 1, "lastUpdateTimeMs": 1,
        },
        "device": {
            "manufacturer": "Acme", "model": "TV", "product": "product", "board": "board",
            "hardware": "hardware", "socManufacturer": "unknown", "socModel": "unknown",
            "androidRelease": "15", "apiLevel": 35, "securityPatch": "2026-01-01",
            "abis": ["arm64-v8a"], "cpuCoreCount": 4, "totalRamMb": 4096,
            "availableRamMb": 2048, "lowRamDevice": False, "lowMemory": False, "thermalStatus": -1,
        },
        "display": {"width": 3840, "height": 2160, "refreshRate": 60.0, "modeId": 1,
                    "availableModes": ["1:3840x2160@60.000"], "hdrTypes": [1]},
        "runtime": {"networkTransports": ["ETHERNET"], "networkMetered": False,
                    "audioOutputRoute": "unknown", "audioOutputMode": "unknown",
                    "audioEncodingMode": "unknown", "audioTunneling": None,
                    "audioTrackPositionFrames": -1, "audioTimestampAvailable": -1,
                    "audioOutputSampleRate": -1, "audioOutputChannelCount": -1},
        "libraries": {"media3": "1.11.0", "libvlc": "3.7.0", "ffmpegExtension": "1.11.0", "webView": "unknown"},
        "decoderCapabilities": [{"name": "c2.android.avc.decoder", "types": ["video/avc"],
                                 "hardwareAccelerated": None, "softwareOnly": None}],
    })
    payload["samples"][0].update({
        "videoDecoderName": "c2.android.avc.decoder", "audioDecoderName": "c2.android.mp3.decoder",
        "audioMimeType": "audio/mpeg", "audioCodec": "mp2", "audioChannelCount": 2,
        "audioSampleRate": 48000, "bufferedPositionMs": -1, "bufferDurationMs": -1,
        "videoPtsUs": 20_000_000, "audioPtsUs": -1, "playbackStateCode": 3,
        "generation": 2, "pictureModeCode": 3,
    })
    return payload


def report_v4(report_id: str | None = None) -> dict[str, object]:
    payload = report_v3(report_id)
    payload["schemaVersion"] = 4
    payload["runtime"].update({
        "audioOutputRoute": "unknown", "audioOutputMode": "PCM",
        "audioEncodingMode": "encoding_2", "audioTunneling": False,
        "audioOutputSampleRate": 48_000,
    })
    payload["samples"][0].update({
        "audioInputPtsUs": 20_000_000,
        "audioSinkPositionUs": 19_980_000,
        "audioSinkPositionAvailable": True,
        "audioOutputEncoding": 2,
        "audioOutputSampleRate": 48_000,
        "audioOutputChannelConfig": 12,
        "audioOutputBufferSize": 65_536,
        "audioOutputMode": "PCM",
        "audioOutputRoute": "unknown",
        "audioOutputTunneling": False,
        "audioOutputOffload": False,
        "audioTrackTimestampAvailability": "UNKNOWN",
        "audioTrackPlaybackHeadPosition": -1,
        "underrunBufferSize": 65_536,
        "underrunBufferSizeMs": 341,
        "underrunElapsedSinceLastFeedMs": 22,
        "audioSinkDiscontinuityCount": 1,
        "audioTrackReleased": False,
        "videoPlannedReleaseTimeNs": 123_456_789,
        "videoCallbackNanoTime": 123_450_000,
        "videoTimingSource": "ABOUT_TO_RENDER",
        "videoProcessingOffsetUs": -2_000,
        "videoProcessingOffsetAvailable": True,
        "videoProcessingFrameCount": 10,
        "videoFirstFrameRenderTimeMs": 1_234,
        "videoFirstFrameSource": "FIRST_FRAME",
        "droppedFramesElapsedMs": 1_000,
        "actualPresentedAvailability": "UNKNOWN",
        "tsInputSource": "ORIGINAL",
        "tsPcrAvailable": True,
        "tsPcrPid": 256,
        "tsPcrSpanUs": 2_000_000,
        "tsPcrMaxJumpUs": 40_000,
        "tsPcrBackwardJumpCount": 0,
        "tsAdaptationDiscontinuityCount": 1,
        "tsFirstAudioMinusVideoUs": -320_000,
        "tsFirstAudioMinusVideoAvailable": True,
        "tsHlsDiscontinuity": True,
        "tsHlsDiscontinuitySequence": 8,
        "tsWouldRepair": False,
        "tsRepairCorrectionSeconds": 0,
        "tsRepairHeaderCount": 0,
    })
    return payload


def report_v5(report_id: str | None = None) -> dict[str, object]:
    payload = report_v4(report_id)
    payload["schemaVersion"] = 5
    payload["samples"][0].update({
        "nodeCount": 2,
        "nodeGeneration": 3,
        "webRuntimeGeneration": 2,
        "webState": "NODE_REPLACED",
        "webRecoveryStage": "MONITORING",
    })
    return payload


def report_v10(report_id: str | None = None) -> dict[str, object]:
    payload = report_v5(report_id)
    payload["schemaVersion"] = 10
    payload["appVersionName"] = "0.2.9"
    payload["appVersionCode"] = 22
    payload["app"].update({
        "applicationId": "com.kantv.app", "buildType": "release",
        "versionName": "0.2.9", "versionCode": 22,
    })
    payload["libraries"].pop("libvlc")
    payload["deviceManufacturer"] = "ab" * 32
    payload["deviceModel"] = "cd" * 32
    payload["device"].update({
        "manufacturer": payload["deviceManufacturer"],
        "model": payload["deviceModel"],
        "product": "01" * 32,
        "board": "02" * 32,
        "hardware": "03" * 32,
        "socManufacturer": "04" * 32,
        "socModel": "05" * 32,
    })
    payload["deviceLabel"] = f"install-{payload['deviceInstallId'][:8]}"
    payload["samples"][0].update({
        "mirrorWorkerSwitches": 0,
        "mirrorInstanceSwitches": 0,
        "mirrorAcceptedFragments": 0,
        "mirrorRejectedFragments": 0,
        "mirrorWatchdogRebuilds": 0,
        "mirrorWatchdogReason": "NONE",
        "routeGeneration": 2,
        "mirrorStage": "NATIVE_PAGE_PROGRESS",
        "mirrorNavigationState": "FINISHED",
        "mirrorExtensionState": "READY",
        "mirrorBridgeReady": True,
        "mirrorHookRequested": False,
        "mirrorServerStarts": 0,
        "mirrorVideoInitCount": 0,
        "mirrorAudioInitCount": 0,
        "mirrorAcceptedVideoFragments": 0,
        "mirrorAcceptedAudioFragments": 0,
        "mirrorRejectedVideoFragments": 0,
        "mirrorRejectedAudioFragments": 0,
        "mirrorFirstVideoInitElapsedMs": -1,
        "mirrorFirstAudioInitElapsedMs": -1,
        "mirrorFirstAcceptedFragmentElapsedMs": -1,
        "mirrorPlayerCreated": False,
        "mirrorFirstFrameRendered": True,
        "mirrorOutputUpdates": 1,
        "geckoPlaybackStrategy": "NATIVE_PAGE",
        "geckoPresentationMode": "AUTO_THEN_FIT",
        "geckoFullscreenRequestCount": 1,
        "geckoFullscreenRequestAccepted": True,
        "geckoFullscreenActive": True,
        "geckoFullscreenEnterCount": 1,
        "geckoFullscreenExitCount": 0,
        "geckoCssFitApplied": True,
        "geckoPresentationRebindCount": 1,
        "geckoPresentationTargetWidth": 1920,
        "geckoPresentationTargetHeight": 1080,
        "geckoPresentationViewportWidth": 1920,
        "geckoPresentationViewportHeight": 1080,
        "geckoPresentationCoveragePermille": 1000,
        "sourceOrdinal": 1,
        "sourceCount": 2,
        "sourceHash": "0123456789abcdef",
        "sourceGeneration": 3,
        "media3Generation": 4,
        "deliveryStrategyCode": 1,
        "deliveryReasonCode": 2,
        "prefetchQueued": 5,
        "prefetchReady": 1,
        "prefetchFailed": 0,
        "prefetchCancelled": 0,
        "prefetchWaitMs": 1800,
        "prefetchCapMs": 5000,
        "prefetchSegmentCount": 5,
        "prefetchTargetDurationMs": 6000,
        "prefetchOutcome": "VERIFIED_BOOTSTRAP",
        "rebufferDurationMs": 2500,
        "rebufferCountWindow": 2,
        "rebufferTotalMsWindow": 5000,
        "rebufferReason": "PLAYER_BUFFERING",
        "rebufferUnstableReason": "ACCUMULATED_DURATION",
        "sourceHealthScore": 1000,
        "recoveryReason": "REBUFFER_UNSTABLE",
        "greyScreenReason": "NONE",
        "firstFrameLatencyMs": 2200,
        "videoPtsMs": 5000,
        "audioAdvancedWithoutVideo": False,
        "videoAdvancedWithoutAudio": False,
    })
    return payload


def report_v11(report_id: str | None = None) -> dict[str, object]:
    payload = report_v10(report_id)
    payload["schemaVersion"] = 11
    payload["samples"][0].update({
        "visualSampleOutcome": "unknown",
        "visualSampleCallbackMs": -1,
        "millisSincePictureVerified": -1,
        "wedgeActive": False,
        "watchdogDecision": "NONE",
        "watchdogReason": "NONE",
        "watchdogRecoveries": 0,
        "watchdogRecoveryLimit": 3,
        "sampleSkipReason": "NONE",
        "probeTimedOut": False,
        "networkTransport": "ETHERNET",
        "networkMetered": False,
        "millisSinceHeartbeat": 1000,
        "samplingSuspended": False,
        "samplingSuspendedMs": 0,
    })
    return payload


def report_v12(report_id: str | None = None) -> dict[str, object]:
    payload = report_v11(report_id)
    payload["schemaVersion"] = 12
    payload["samples"][0].update({
        "sourceRoute": "upstream-a",
        "sourceQuality": "1080p",
        "sourceUrlKey": "c1c2c3",
        "sourceScoreUpstream": 100,
        "userVisibleState": "PLAYING",
        "userVisibleStateMs": 5000,
    })
    return payload


def report_v13(report_id: str | None = None) -> dict[str, object]:
    payload = report_v12(report_id)
    payload["schemaVersion"] = 13
    payload["samples"][0].update({"labelFound": True, "labelClicked": False})
    return payload


def report_v14(report_id: str | None = None) -> dict[str, object]:
    payload = report_v13(report_id)
    payload["schemaVersion"] = 14
    payload["samples"][0].update({
        "probeObservationStatus": "VALID",
        "avSyncAssessment": "UNKNOWN_NO_MEDIA_ELEMENT_AUDIO_OUTPUT_TIMESTAMP",
        "videoCallbackAgeMs": 0,
        "videoPresentationAgeMs": 0,
        "videoExpectedDisplayDelayUs": 0,
    })
    return payload


def report_v15(report_id: str | None = None) -> dict[str, object]:
    payload = report_v14(report_id)
    payload["schemaVersion"] = 15
    payload["samples"][0].update({
        "avSyncAssessment": "UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED",
        "geckoAudioClockStatus": "AVAILABLE", "geckoAudioClockUs": 100,
        "geckoAudioClockAgeMs": 0, "geckoAudioClockCallDurationMs": 1,
        "geckoPairedVideoPtsUs": 101, "geckoAudioMinusVideoUs": -1, "geckoAvPairSpanMs": 40,
    })
    return payload


def report_v16(report_id: str | None = None) -> dict[str, object]:
    payload = report_v15(report_id)
    payload["schemaVersion"] = 16
    for sample in payload["samples"]:
        sample.update({
            "nativeLiveContextAgeMs": -1,
            "nativeLiveOffsetMs": -1,
            "nativeLiveWindowDurationMs": -1,
            "nativeLiveMediaSequence": -1,
            "nativeLastMediaLoadDurationMs": -1,
            "nativeLastMediaLoadBytes": -1,
            "nativeLastLoadErrorCode": -1,
            "nativeAudioClockStatus": "NOT_APPLICABLE", "nativeAvGeneration": -1,
            "nativeAudioStreamOffsetUs": -1, "nativeAudioClockUs": -1, "nativeAudioClockAgeMs": -1,
            "nativePairedVideoPtsUs": -1, "nativeAudioMinusVideoUs": -1,
            "nativeAvPairSpanUs": -1, "nativeVideoReleaseDelayUs": -1,
        })
    return payload


def report_v17(report_id: str | None = None) -> dict[str, object]:
    payload = report_v16(report_id)
    payload["schemaVersion"] = 17
    for sample in payload["samples"]:
        sample.update({'nativeLoadActiveStatus': 'NOT_APPLICABLE', 'nativeLoadActiveTrackType': 'UNKNOWN', 'nativeLoadActiveStartElapsedMs': -1, 'nativeLoadActiveMediaStartMs': -1, 'nativeLoadActiveMediaEndMs': -1, 'nativeLoadActiveRetryCount': -1, 'nativeLoadLastOutcome': 'NOT_APPLICABLE', 'nativeLoadLastTrackType': 'UNKNOWN', 'nativeLoadLastStartElapsedMs': -1, 'nativeLoadLastEndElapsedMs': -1, 'nativeLoadLastMediaStartMs': -1, 'nativeLoadLastMediaEndMs': -1, 'nativeLoadLastRetryCount': -1, 'nativeLoadLastBytes': -1, 'nativeLoadLastHttpStatus': -1, 'nativeLoadPositionStatus': 'NOT_APPLICABLE', 'nativeLoadWindowStartInPeriodMs': -1, 'nativeTransferActiveCount': -1, 'nativeTransferSequence': -1, 'nativeTransferOutcome': 'NA', 'nativeTransferTaskMatchStatus': 'NOT_APPLICABLE', 'nativeTransferStartElapsedMs': -1, 'nativeTransferEndElapsedMs': -1, 'nativeTransferOpenDurationUs': -1, 'nativeTransferReadDurationUs': -1, 'nativeTransferCloseDurationUs': -1, 'nativeTransferReadCallCount': -1, 'nativeTransferBytes': -1, 'nativeTransferEofSeen': False, 'geckoNodeBoundMs': -1, 'geckoLoadedMetadataMs': -1, 'geckoPlayMs': -1, 'geckoPlayingMs': -1, 'geckoWaitingMs': -1, 'geckoCanPlayMs': -1, 'geckoFirstRvfcMs': -1, 'geckoFirstRvfcPtsUs': -1, 'geckoJsTickGapMs': -1, 'geckoAudioQuerySeq': -1, 'geckoAudioQueryEpoch': -1, 'geckoAudioQueryNode': -1, 'geckoAudioQueryStartMs': -1, 'geckoAudioSyncCallMs': -1, 'geckoAudioReactionElapsedMs': -1, 'geckoAudioTimeoutLateMs': -1, 'geckoAttachLate': False, 'geckoAudioInFlight': False, 'geckoAudioQueryState': 'NOT_APPLICABLE', 'geckoAudioQueryDisposition': 'NOT_APPLICABLE'})
    return payload



def report_v18(report_id: str | None = None) -> dict[str, object]:
    payload = report_v17(report_id)
    payload["schemaVersion"] = 18
    from schema import EVIDENCE_NUMBERS_V18, EVIDENCE_BOOLEANS_V18
    for sample in payload["samples"]:
        sample.update({k: -1 for k in EVIDENCE_NUMBERS_V18})
        sample.update({k: False for k in EVIDENCE_BOOLEANS_V18})
        sample.update(nativeMediaRequestMatchStatus="NOT_APPLICABLE", nativeMediaRequestCoverage="UNKNOWN", nativeMediaRequestOutcome="NA")
    return payload


def report_v19(report_id: str | None = None) -> dict[str, object]:
    payload = report_v18(report_id)
    payload["schemaVersion"] = 19
    payload["samples"][0].update({'geckoBridgeLastTickDurationMs': -1, 'geckoBridgeLastTickMaxPhaseCode': 'NOT_APPLICABLE', 'geckoBridgeLastTickMaxPhaseDurationMs': -1, 'geckoBridgeLastTickScanNodes': -1, 'geckoAudioQueryTickDurationMs': -1, 'geckoAudioQueryTickTailDurationMs': -1, 'geckoAudioQueryTickMaxPhaseCode': 'NOT_APPLICABLE', 'geckoAudioQueryTickMaxPhaseDurationMs': -1, 'geckoAudioQueryTickScanNodes': -1, 'geckoStartupObserverTickCount': -1, 'geckoStartupObserverTotalDurationMs': -1, 'geckoStartupObserverMaxTickDurationMs': -1, 'geckoStartupResourceObservationStatus': 'NOT_APPLICABLE', 'geckoStartupResourceCount': -1, 'geckoStartupResourceMaxDurationMs': -1, 'geckoStartupResourceMaxResponseWaitMs': -1, 'geckoStartupResourceMaxDownloadMs': -1, 'geckoStartupResourceDetailedCount': -1})
    return payload


def report_v20(report_id: str | None = None) -> dict[str, object]:
    payload=report_v19(report_id)
    payload["schemaVersion"]=20
    payload["samples"][0].update({'geckoAudioQueryObservedWorkCount': -1, 'geckoAudioQueryObservedWorkTotalMs': -1, 'geckoAudioQueryObservedWorkMaxMs': -1, 'geckoAudioQueryObservedWorkMaxPhaseCode': 'NOT_APPLICABLE', 'geckoAudioQueryObservedWorkMaxPhaseMs': -1, 'geckoAudioQueryObservedWorkMaxScanNodes': -1, 'geckoStartupResourceScannedCount': -1, 'geckoStartupResourceFilteredCount': -1, 'geckoStartupResourceMaxStartMs': -1, 'geckoStartupResourceMaxEndMs': -1, 'geckoStartupResourceMaxInitiatorCode': 'NOT_APPLICABLE'})
    return payload


def report_v21(report_id: str | None = None) -> dict[str, object]:
    payload = report_v20(report_id)
    payload["schemaVersion"] = 21
    payload["samples"][0].update({'geckoAudioQueryJsTickCount': -1, 'geckoAudioQueryMaxJsTickGapMs': -1, 'geckoAudioRecoveryCount': -1, 'geckoAudioRetryRemainingMs': -1, 'geckoAudioFirstFaultSeq': -1, 'geckoAudioFirstFaultEpoch': -1, 'geckoAudioFirstFaultNode': -1, 'geckoAudioFirstFaultStartMs': -1, 'geckoAudioFirstFaultSyncCallMs': -1, 'geckoAudioFirstFaultReactionElapsedMs': -1, 'geckoAudioFirstFaultTimeoutLateMs': -1, 'geckoAudioFirstFaultJsTickCount': -1, 'geckoAudioFirstFaultMaxJsTickGapMs': -1, 'geckoCaptureSequence': -1, 'geckoCaptureApiCallMs': -1, 'geckoCaptureIssueToListenerMs': -1, 'geckoCaptureClassifyMs': -1, 'geckoCapturePostLagMs': -1, 'geckoBridgePostLagMs': -1, 'geckoAudioFirstFaultState': 'NOT_APPLICABLE', 'geckoAudioFirstFaultDisposition': 'NOT_APPLICABLE', 'geckoAudioFirstFaultClockStatus': 'NOT_APPLICABLE'})
    return payload


def report_v22(report_id: str | None = None) -> dict[str, object]:
    payload = report_v21(report_id)
    payload["schemaVersion"] = 22
    payload["samples"][0]["visualSampleBlockedReason"] = "unknown"
    return payload


class ReceiverTest(unittest.TestCase):
    def setUp(self) -> None:
        # HTTPServer 仅用它填 server_name；避免 macOS 反向 DNS 阻塞本机边界测试。
        resolver = patch("http.server.socket.getfqdn", return_value="localhost")
        resolver.start()
        self.addCleanup(resolver.stop)
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name) / "reports"
        self.release_directory = Path(self.temporary.name) / "releases"
        self.release_directory.mkdir()
        self.apk = self._apk_bytes("arm64-v8a")
        (self.release_directory / "kantv-release.apk").write_bytes(self.apk)
        self.release_manifest = {
            "applicationId": "com.kantv.app.diagnostic",
            "versionCode": 8,
            "versionName": "0.1.7",
            "apkPath": "kantv-release.apk",
            "size": len(self.apk),
            "sha256": hashlib.sha256(self.apk).hexdigest(),
            "signingCertSha256": "ab" * 32,
            "publishedAt": "2026-09-10T12:00:00Z",
        }
        (self.release_directory / "latest.json").write_text(json.dumps(self.release_manifest), encoding="utf-8")
        self.v7a_apk = self._apk_bytes("armeabi-v7a")
        (self.release_directory / "kantv-release-v7a.apk").write_bytes(self.v7a_apk)
        self.v7a_release_manifest = {
            **self.release_manifest,
            "apkPath": "kantv-release-v7a.apk",
            "size": len(self.v7a_apk),
            "sha256": hashlib.sha256(self.v7a_apk).hexdigest(),
        }
        (self.release_directory / "latest-armeabi-v7a.json").write_text(
            json.dumps(self.v7a_release_manifest),
            encoding="utf-8",
        )
        self.server = create_server(
            "127.0.0.1",
            0,
            ReceiverConfig(
                "diagnostic-token",
                self.directory,
                release_dir=self.release_directory,
                release_token="update-token",
            ),
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @staticmethod
    def _apk_bytes(abi: str) -> bytes:
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr(f"lib/{abi}/libkantv.so", b"\x7fELF")
            archive.writestr("classes.dex", b"dex")
        return output.getvalue()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()
        self.temporary.cleanup()

    def request(self, method: str, path: str, body: bytes | None = None, headers: dict[str, str] | None = None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        content = response.read()
        connection.close()
        try:
            parsed = json.loads(content)
        except (json.JSONDecodeError, UnicodeDecodeError):
            try:
                parsed = content.decode("utf-8")
            except UnicodeDecodeError:
                parsed = content
        return response.status, parsed

    def post(self, payload: dict[str, object], token: str = "diagnostic-token"):
        body = json.dumps(payload).encode()
        return self.request("POST", "/v1/reports", body, {"Authorization": f"Bearer {token}", "Content-Type": "application/json", "Content-Length": str(len(body))})

    def restart(self, **limits) -> None:
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()
        self.server = create_server("127.0.0.1", 0, ReceiverConfig("diagnostic-token", self.directory, **limits))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def test_health_is_only_read_endpoint(self) -> None:
        self.assertEqual((200, "ok\n"), self.request("GET", "/health"))
        self.assertEqual(404, self.request("GET", "/v1/reports")[0])

    def test_release_manifest_and_apk_require_bearer_auth(self) -> None:
        self.assertEqual(401, self.request("GET", "/v1/releases/latest")[0])
        self.assertEqual(401, self.request("GET", "/v1/releases/apk/kantv-release.apk")[0])
        self.assertEqual(401, self.request("GET", "/v1/releases/latest", headers={"Authorization": "Bearer diagnostic-token"})[0])
        headers = {"Authorization": "Bearer update-token"}
        status, manifest = self.request("GET", "/v1/releases/latest", headers=headers)
        self.assertEqual(200, status)
        self.assertEqual(self.release_manifest, manifest)
        status, apk = self.request("GET", "/v1/releases/apk/kantv-release.apk", headers=headers)
        self.assertEqual(200, status)
        self.assertEqual(self.apk, apk)

    def test_release_routes_by_abi_and_keeps_missing_header_on_legacy_arm64(self) -> None:
        headers = {"Authorization": "Bearer update-token"}
        self.assertEqual(
            (200, self.release_manifest),
            self.request("GET", "/v1/releases/latest", headers=headers),
        )
        v7a_headers = {**headers, "X-KanTV-ABI": "armeabi-v7a"}
        self.assertEqual(
            (200, self.v7a_release_manifest),
            self.request("GET", "/v1/releases/latest", headers=v7a_headers),
        )
        self.assertEqual(
            (200, self.v7a_apk),
            self.request("GET", "/v1/releases/apk/kantv-release-v7a.apk", headers=v7a_headers),
        )
        self.assertEqual(
            404,
            self.request("GET", "/v1/releases/apk/kantv-release.apk", headers=v7a_headers)[0],
        )
        self.assertEqual(
            400,
            self.request(
                "GET",
                "/v1/releases/latest",
                headers={**headers, "X-KanTV-ABI": "x86_64"},
            )[0],
        )

    def test_release_rejects_unreadable_empty_dual_or_wrong_abi_apk(self) -> None:
        headers = {"Authorization": "Bearer update-token"}
        apk_path = self.release_directory / "kantv-release.apk"
        for payload in (
            b"not-a-zip",
            self._apk_bytes("armeabi-v7a"),
        ):
            apk_path.write_bytes(payload)
            manifest = {**self.release_manifest, "size": len(payload), "sha256": hashlib.sha256(payload).hexdigest()}
            (self.release_directory / "latest.json").write_text(json.dumps(manifest), encoding="utf-8")
            self.assertEqual(503, self.request("GET", "/v1/releases/latest", headers=headers)[0])

        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr("lib/arm64-v8a/libkantv.so", b"x")
            archive.writestr("lib/armeabi-v7a/libkantv.so", b"x")
        payload = output.getvalue()
        apk_path.write_bytes(payload)
        manifest = {**self.release_manifest, "size": len(payload), "sha256": hashlib.sha256(payload).hexdigest()}
        (self.release_directory / "latest.json").write_text(json.dumps(manifest), encoding="utf-8")
        self.assertEqual(503, self.request("GET", "/v1/releases/latest", headers=headers)[0])

    def test_apk_stream_supports_bounded_single_range(self) -> None:
        headers = {"Authorization": "Bearer update-token", "Range": "bytes=7-31"}
        status, content = self.request("GET", "/v1/releases/apk/kantv-release.apk", headers=headers)
        self.assertEqual(206, status)
        actual = content if isinstance(content, bytes) else content.encode("utf-8")
        self.assertEqual(self.apk[7:32], actual)
        headers["Range"] = f"bytes={len(self.apk)}-"
        self.assertEqual(416, self.request("GET", "/v1/releases/apk/kantv-release.apk", headers=headers)[0])
        headers["Range"] = "bytes=0-1,4-5"
        self.assertEqual(416, self.request("GET", "/v1/releases/apk/kantv-release.apk", headers=headers)[0])

    def test_release_path_is_not_a_directory_or_traversal_surface(self) -> None:
        headers = {"Authorization": "Bearer update-token"}
        for path in (
            "/v1/releases/",
            "/v1/releases/apk/",
            "/v1/releases/apk/../latest.json",
            "/v1/releases/apk/%2e%2e%2flatest.json",
            "/v1/releases/apk/other.apk",
            "/v1/releases/latest?list=1",
        ):
            with self.subTest(path=path):
                self.assertEqual(404, self.request("GET", path, headers=headers)[0])

    def test_invalid_release_manifest_is_not_served(self) -> None:
        invalid = dict(self.release_manifest)
        invalid["apkPath"] = "../kantv-release.apk"
        (self.release_directory / "latest.json").write_text(json.dumps(invalid), encoding="utf-8")
        headers = {"Authorization": "Bearer update-token"}
        self.assertEqual(503, self.request("GET", "/v1/releases/latest", headers=headers)[0])
        self.assertEqual(503, self.request("GET", "/v1/releases/apk/kantv-release.apk", headers=headers)[0])

    def test_stores_idempotently_and_rejects_conflict(self) -> None:
        payload = report_v10()
        report_id = payload["reportId"]
        self.assertEqual(201, self.post(payload)[0])
        self.assertEqual(200, self.post(payload)[0])
        payload["reason"] = "PLAYBACK_STALLED_15S"
        self.assertEqual(409, self.post(payload)[0])
        self.assertEqual([f"{report_id}.json"], [path.name for path in self.directory.glob("*.json")])

    def test_v1_through_v5_are_rejected(self) -> None:
        # 2026-09-15 起接收端仅支持 v10+；v1–v5 生产者已全部退场，必须被拒绝。
        for version in (1, 2, 3, 4, 5):
            with self.subTest(version=version):
                payload = report_v10()
                payload["schemaVersion"] = version
                with self.assertRaises(SchemaError) as caught:
                    validate_report(json.dumps(payload).encode("utf-8"))
                self.assertEqual("report.schemaVersion", str(caught.exception).split(": ", 1)[0])
                self.assertEqual(400, self.post(payload)[0])

    def test_accepts_v10_m3u_report_with_anonymous_device_and_player_metrics(self) -> None:
        payload = report_v10()
        payload["provider"] = "M3U"
        self.assertEqual(201, self.post(payload)[0])
        saved = json.loads((self.directory / f"{payload['reportId']}.json").read_text())
        self.assertEqual("M3U", saved["provider"])
        self.assertEqual(payload["deviceInstallId"], saved["deviceInstallId"])
        self.assertEqual(21, saved["samples"][0]["displayedFrames"])

    def test_address_and_hardware_identifier_keys_are_rejected(self) -> None:
        for field, value in (
            ("url", "https://secret.example/stream"),
            ("macAddress", "00:11:22:33:44:55"),
            ("serialNumber", "secret"),
            ("rawLog", "player output"),
        ):
            with self.subTest(field=field):
                payload = report_v10()
                payload[field] = value
                self.assertEqual(400, self.post(payload)[0])

    def test_accepts_v10_process_recovery_minimal_event(self) -> None:
        payload = report_v10()
        payload["reason"] = "PROCESS_TERMINATED"
        payload["lifecycle"] = "RECOVERED_AFTER_PROCESS_DEATH"
        payload["samples"] = [{"kind": "PROCESS_RECOVERED", "elapsedMs": -1}]
        self.assertEqual(201, self.post(payload)[0])

    def test_accepts_complete_v10_candidate_fragment_without_dropping_fields(self) -> None:
        payload = report_v10()
        self.assertEqual(201, self.post(payload)[0])
        saved = json.loads((self.directory / f"{payload['reportId']}.json").read_text())
        self.assertEqual(set(payload["samples"][0]), set(saved["samples"][0]))
        self.assertEqual(payload["samples"], saved["samples"])
        self.assertEqual("com.kantv.app", saved["app"]["applicationId"])
        self.assertEqual("0123456789abcdef", saved["samples"][0]["sourceHash"])

    def test_v10_rejects_sensitive_unknown_and_invalid_digest_fields(self) -> None:
        unknown = report_v10(); unknown["samples"][0]["pageText"] = "private"
        self.assertEqual(400, self.post(unknown)[0])
        sensitive = report_v10(); sensitive["samples"][0]["requestHeader"] = "private"
        self.assertEqual(400, self.post(sensitive)[0])
        invalid_digest = report_v10(); invalid_digest["samples"][0]["sourceHash"] = "not-a-digest"
        self.assertEqual(400, self.post(invalid_digest)[0])
        disguised_url = report_v10(); disguised_url["device"]["model"] = "https://private.example"
        self.assertEqual(400, self.post(disguised_url)[0])
        # 可读设备值现在会被归一化为摘要；含控制字符的值仍在归一化后被严格校验拒绝。
        control_text = report_v10(); control_text["device"]["product"] = "web\npage text"
        self.assertEqual(400, self.post(control_text)[0])
        disguised_token = report_v10(); disguised_token["runtime"]["audioOutputRoute"] = "A" * 40
        self.assertEqual(400, self.post(disguised_token)[0])
        mismatched_label = report_v10(); mismatched_label["deviceLabel"] = "install-deadbeef"
        self.assertEqual(400, self.post(mismatched_label)[0])

    def test_v10_rejects_oversized_samples(self) -> None:
        payload = report_v10()
        payload["samples"] = [{"kind": "SAMPLE"}] * 121
        self.assertEqual(400, self.post(payload)[0])

    def test_v10_normalizes_legacy_readable_device_identities_before_persisting(self) -> None:
        legacy = {
            "manufacturer": "Google",
            "model": "AOSP on IA Emulator",
            "product": "sdk_google_atv64_arm64",
            "board": "goldfish_x86_64",
            "hardware": "ranchu",
            "socManufacturer": "Google Inc.",
            "socModel": "virtual",
        }
        payload = report_v10()
        payload["deviceManufacturer"] = legacy["manufacturer"]
        payload["deviceModel"] = legacy["model"]
        payload["device"].update(legacy)
        validated = validate_report(json.dumps(payload).encode("utf-8"))
        canonical = validated.canonical_bytes.decode("utf-8")
        digests = {key: hashlib.sha256(value.encode("utf-8")).hexdigest() for key, value in legacy.items()}
        for key, value in legacy.items():
            self.assertNotIn(value, canonical, msg=f"{key} 明文不得进入 canonical_bytes")
        stored = json.loads(canonical)
        self.assertEqual(digests["manufacturer"], stored["deviceManufacturer"])
        self.assertEqual(digests["model"], stored["deviceModel"])
        for key, digest in digests.items():
            self.assertEqual(digest, stored["device"][key])
            self.assertEqual(64, len(digest))
            self.assertTrue(all(char in "0123456789abcdef" for char in digest))
        self.assertEqual(201, self.post(payload)[0])
        saved_text = (self.directory / f"{payload['reportId']}.json").read_text()
        saved = json.loads(saved_text)
        self.assertEqual(digests["manufacturer"], saved["deviceManufacturer"])
        self.assertEqual(digests["model"], saved["deviceModel"])
        for value in legacy.values():
            self.assertNotIn(value, saved_text)

    def test_v10_keeps_unknown_and_existing_device_digests_unchanged(self) -> None:
        payload = report_v10()
        payload["device"]["socManufacturer"] = "unknown"
        validated = validate_report(json.dumps(payload).encode("utf-8"))
        canonical = validated.canonical_bytes.decode("utf-8")
        stored = json.loads(canonical)
        self.assertEqual("ab" * 32, stored["deviceManufacturer"])
        self.assertEqual("cd" * 32, stored["deviceModel"])
        self.assertEqual("ab" * 32, stored["device"]["manufacturer"])
        self.assertEqual("cd" * 32, stored["device"]["model"])
        self.assertEqual("01" * 32, stored["device"]["product"])
        self.assertEqual("02" * 32, stored["device"]["board"])
        self.assertEqual("03" * 32, stored["device"]["hardware"])
        self.assertEqual("05" * 32, stored["device"]["socModel"])
        self.assertEqual("unknown", stored["device"]["socManufacturer"])
        self.assertNotIn(hashlib.sha256(("ab" * 32).encode("utf-8")).hexdigest(), canonical)
        self.assertEqual(201, self.post(payload)[0])

    def test_v10_rejects_sensitive_url_token_and_control_device_identity_values(self) -> None:
        fields = (
            ("root", "deviceManufacturer"),
            ("root", "deviceModel"),
            ("device", "manufacturer"),
            ("device", "model"),
            ("device", "product"),
            ("device", "board"),
            ("device", "hardware"),
            ("device", "socManufacturer"),
            ("device", "socModel"),
        )
        rejected = (
            "https://private.example/device",
            "A" * 40,
            "Bearer secret-device",
            "device\nvalue",
        )
        for scope, key in fields:
            for value in rejected:
                with self.subTest(field=f"{scope}.{key}", value=value):
                    payload = report_v10()
                    target = payload if scope == "root" else payload["device"]
                    target[key] = value
                    with self.assertRaises(SchemaError):
                        validate_report(json.dumps(payload).encode("utf-8"))
                    self.assertEqual(400, self.post(payload)[0])

    def test_v10_rejects_inconsistent_device_identities_after_normalization(self) -> None:
        for top, nested in (("deviceManufacturer", "manufacturer"), ("deviceModel", "model")):
            with self.subTest(field=top):
                payload = report_v10()
                payload[top] = "Google"
                payload["device"][nested] = "samsung"
                with self.assertRaises(SchemaError) as caught:
                    validate_report(json.dumps(payload).encode("utf-8"))
                self.assertEqual("report.device", str(caught.exception).split(": ", 1)[0])
                self.assertIn("设备字段必须与顶层一致", str(caught.exception))
                self.assertEqual(400, self.post(payload)[0])
        digest_vs_readable = report_v10()
        digest_vs_readable["device"]["manufacturer"] = "Google"
        with self.assertRaises(SchemaError):
            validate_report(json.dumps(digest_vs_readable).encode("utf-8"))
        self.assertEqual(400, self.post(digest_vs_readable)[0])

    def test_accepts_v11_evidence_fragment_without_dropping_fields(self) -> None:
        payload = report_v11()
        self.assertEqual(201, self.post(payload)[0])
        saved = json.loads((self.directory / f"{payload['reportId']}.json").read_text())
        self.assertEqual(set(payload["samples"][0]), set(saved["samples"][0]))
        self.assertEqual(payload["samples"], saved["samples"])
        self.assertEqual("NONE", saved["samples"][0]["watchdogDecision"])
        self.assertEqual("ETHERNET", saved["samples"][0]["networkTransport"])

    def test_accepts_v12_source_identity_fragment_without_dropping_fields(self) -> None:
        payload = report_v12()
        self.assertEqual(201, self.post(payload)[0])
        saved = json.loads((self.directory / f"{payload['reportId']}.json").read_text())
        self.assertEqual(payload["samples"], saved["samples"])
        self.assertEqual("PLAYING", saved["samples"][0]["userVisibleState"])
        self.assertEqual("upstream-a", saved["samples"][0]["sourceRoute"])

    def test_v17_trace_shape_preserves_old_versions_and_rejects_fake_join(self) -> None:
        for fixture in (report_v15, report_v16, report_v17):
            validate_report(json.dumps(fixture()).encode())
        payload = report_v16()
        payload["samples"][0]["geckoAudioQuerySeq"] = 1
        with self.assertRaises(SchemaError): validate_report(json.dumps(payload).encode())
        payload = report_v17()
        self.assertEqual(201, self.post(payload)[0])
        sample = payload["samples"][0]
        sample.update(nativeTransferTaskMatchStatus="UNASSIGNED", nativeTransferOutcome="CLOSED_EOF",
                      nativeTransferSequence=1, nativeTransferEofSeen=True,
                      nativeTransferStartElapsedMs=1000, nativeTransferEndElapsedMs=14000,
                      nativeTransferOpenDurationUs=500, nativeTransferReadDurationUs=12900000,
                      nativeTransferCloseDurationUs=100, nativeTransferReadCallCount=50, nativeTransferBytes=5000000)
        validate_report(json.dumps(payload).encode())
        for key, value in (("nativeTransferTaskMatchStatus", "MATCHED"), ("nativeTransferEofSeen", False),
                           ("nativeTransferEndElapsedMs", 999), ("geckoAudioQueryState", "private-token"),
                           ("geckoAudioQuerySeq", True), ("nativeLoadLastHttpStatus", 1002)):
            previous = sample[key]; sample[key] = value
            with self.assertRaises(SchemaError): validate_report(json.dumps(payload).encode())
            sample[key] = previous

    def test_v17_stale_audio_keeps_age_without_a_valid_pair(self) -> None:
        for status, age in (("INSUFFICIENT_STALE_SAMPLE", 5001), ("INSUFFICIENT_PROBE_INVALID", 42)):
            payload = report_v17(); sample = payload["samples"][0]
            sample.update(geckoAudioClockStatus=status, geckoAudioClockAgeMs=age)
            for key in ("geckoAudioClockUs", "geckoPairedVideoPtsUs", "geckoAudioMinusVideoUs", "geckoAvPairSpanMs"):
                sample[key] = -1
            self.assertEqual(201, self.post(payload)[0])

    def test_v17_unknown_evidence_cannot_carry_live_query_or_old_transfer(self) -> None:
        for changes in ({"geckoAudioQueryState": "NOT_STARTED", "geckoAudioInFlight": True},
                        {"geckoAudioQueryState": "PENDING", "geckoAudioQuerySeq": -1},
                        {"nativeTransferOutcome": "INVALIDATED", "nativeTransferBytes": 123},
                        {"nativeTransferOutcome": "NONE", "nativeTransferEofSeen": True}):
            payload = report_v17(); payload["samples"][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(SchemaError):
                validate_report(json.dumps(payload).encode())

    def test_v17_late_settlement_cannot_revalidate_the_audio_clock(self) -> None:
        payload = report_v17(); sample = payload["samples"][0]
        sample.update(geckoAudioClockStatus="INSUFFICIENT_TIMEOUT", geckoAudioClockCallDurationMs=2376,
                      geckoAudioQuerySeq=6, geckoAudioQueryEpoch=2, geckoAudioQueryNode=2,
                      geckoAudioQueryStartMs=1000, geckoAudioSyncCallMs=1,
                      geckoAudioTimeoutLateMs=1376, geckoAudioQueryState="TIMED_OUT",
                      geckoAudioQueryDisposition="TIMEOUT_FUSE", geckoAudioInFlight=True)
        for key in GECKO_AUDIO_NUMBERS_V15 - {"geckoAudioClockCallDurationMs"}: sample[key] = -1
        validate_report(json.dumps(payload).encode())
        sample.update(geckoAudioQueryState="RESOLVED", geckoAudioReactionElapsedMs=2600,
                      geckoAudioInFlight=False, geckoAudioQueryDisposition="IGNORED_TIMEOUT_FUSE")
        validate_report(json.dumps(payload).encode())
        sample["geckoAudioClockUs"] = 100
        with self.assertRaises(SchemaError): validate_report(json.dumps(payload).encode())


    def test_v18_default_and_exact_request_only_are_accepted(self) -> None:
        self.assertEqual(201, self.post(report_v18())[0])
        payload = report_v18(); sample = payload["samples"][0]
        sample.update(nativeLoadLastOutcome="COMPLETED", nativeLoadLastTrackType="DEFAULT",
            nativeLoadLastStartElapsedMs=100, nativeLoadLastEndElapsedMs=800,
            nativeLoadLastMediaStartMs=5000, nativeLoadLastMediaEndMs=10000,
            nativeLoadLastRetryCount=0, nativeLoadLastBytes=4000,
            nativeMediaRequestMatchStatus="MATCHED", nativeMediaRequestCoverage="REQUEST_ONLY",
            nativeMediaRequestOutcome="CLOSED_EOF", nativeMediaRequestOpenDurationUs=16000,
            nativeMediaRequestReadDurationUs=680000, nativeMediaRequestCloseDurationUs=180,
            nativeMediaRequestReadCallCount=4, nativeMediaRequestBytes=4000, nativeMediaRequestEofSeen=True)
        self.assertEqual(201, self.post(payload)[0])
        for changes in ({"nativeLoadLastOutcome":"ERROR_TERMINAL"},
                        {"nativeMediaRequestCoverage":"UNKNOWN"},
                        {"nativeMediaRequestMatchStatus":"UNKNOWN_RETRY"},
                        {"nativeMediaRequestOutcome":"CLOSED_NO_EOF"},
                        {"nativeMediaRequestReadCallCount":-1}):
            with self.subTest(changes=changes):
                import copy
                bad=copy.deepcopy(payload);bad["samples"][0].update(changes)
                with self.assertRaises(SchemaError): validate_report(json.dumps(bad).encode())

    def test_v20_preserves_exact_legacy_and_requires_new_fields(self) -> None:
        self.assertEqual(201,self.post(report_v20())[0])
        for factory in (report_v18,report_v19):
            payload=factory();validate_report(json.dumps(payload).encode())
            payload["samples"][0]["geckoAudioQueryObservedWorkCount"]=-1
            with self.assertRaises(SchemaError):validate_report(json.dumps(payload).encode())
        payload=report_v20();payload["samples"][0].pop("geckoStartupResourceScannedCount")
        with self.assertRaises(SchemaError):validate_report(json.dumps(payload).encode())

    def test_v20_pending_work_totals_and_independent_maxima_are_consistent(self) -> None:
        payload=report_v20();sample=payload["samples"][0]
        sample.update(geckoAudioQuerySeq=1,geckoAudioQueryNode=2,geckoAudioQueryEpoch=3,
            geckoAudioQueryState="PENDING",geckoAudioQueryDisposition="NONE",geckoAudioInFlight=True,
            geckoAudioQueryStartMs=1000,geckoAudioSyncCallMs=1,
            geckoAudioQueryObservedWorkCount=3,geckoAudioQueryObservedWorkTotalMs=2009,
            geckoAudioQueryObservedWorkMaxMs=2000,geckoAudioQueryObservedWorkMaxPhaseCode="LOGIN_SCAN",
            geckoAudioQueryObservedWorkMaxPhaseMs=1999,geckoAudioQueryObservedWorkMaxScanNodes=2800)
        self.assertEqual(201,self.post(payload)[0])
        for changes in ({"geckoAudioQueryObservedWorkTotalMs":1000}, {"geckoAudioQueryObservedWorkMaxPhaseMs":2001},
                {"geckoAudioQueryObservedWorkCount":0}, {"geckoAudioQueryObservedWorkMaxPhaseCode":"UNKNOWN"},
                {"geckoAudioQueryObservedWorkCount":True}, {"geckoAudioQueryObservedWorkMaxScanNodes":2801}):
            import copy
            bad=copy.deepcopy(payload);bad["samples"][0].update(changes)
            with self.subTest(changes=changes),self.assertRaises(SchemaError):validate_report(json.dumps(bad).encode())

    def test_v20_targeted_resource_count_and_longest_entry_match(self) -> None:
        payload=report_v20();sample=payload["samples"][0]
        sample.update(geckoStartupResourceObservationStatus="COMPLETE",geckoStartupResourceCount=4,
            geckoStartupResourceDetailedCount=1,geckoStartupResourceMaxDurationMs=1181,
            geckoStartupResourceMaxResponseWaitMs=247,geckoStartupResourceMaxDownloadMs=137,
            geckoStartupResourceScannedCount=504,geckoStartupResourceFilteredCount=500,
            geckoStartupResourceMaxStartMs=500,geckoStartupResourceMaxEndMs=1681,
            geckoStartupResourceMaxInitiatorCode="XMLHTTPREQUEST")
        self.assertEqual(201,self.post(payload)[0])
        for changes in ({"geckoStartupResourceScannedCount":503}, {"geckoStartupResourceFilteredCount":1025},
                {"geckoStartupResourceMaxEndMs":1683}, {"geckoStartupResourceMaxInitiatorCode":"private_url"},
                {"geckoStartupResourceMaxStartMs":-1}):
            import copy
            bad=copy.deepcopy(payload);bad["samples"][0].update(changes)
            with self.subTest(changes=changes),self.assertRaises(SchemaError):validate_report(json.dumps(bad).encode())
        sample.update(geckoStartupResourceObservationStatus="OVERFLOW",geckoStartupResourceCount=0,
            geckoStartupResourceDetailedCount=0,geckoStartupResourceMaxDurationMs=-1,
            geckoStartupResourceMaxResponseWaitMs=-1,geckoStartupResourceMaxDownloadMs=-1,
            geckoStartupResourceScannedCount=1024,geckoStartupResourceFilteredCount=1024,
            geckoStartupResourceMaxStartMs=-1,geckoStartupResourceMaxEndMs=-1,
            geckoStartupResourceMaxInitiatorCode="NONE")
        validate_report(json.dumps(payload).encode())


    def test_v21_buffering_fact_kinds_do_not_expand_old_kind_sets(self) -> None:
        for kind in ("BUFFERING_STARTED", "BUFFERING_ENDED"):
            p=report_v21();p["samples"][0]["kind"]=kind
            validate_report(json.dumps(p).encode())
            old=report_v20();old["samples"][0]["kind"]=kind
            with self.assertRaises(SchemaError):validate_report(json.dumps(old).encode())
        old=report_v20();old["samples"][0]["kind"]="WATCHDOG_TRIGGERED"
        validate_report(json.dumps(old).encode())

    def test_v22_fixed_block_reasons_and_required_missing_evidence(self) -> None:
        for reason in ("NONE", "DEVICE_GPU_READBACK_UNSAFE", "DEBUG_CONTROL", "unknown"):
            p = report_v22()
            p["samples"][0].update(visualSampleBlockedReason=reason, visualSampleOutcome="NO_SAMPLE",
                visualSampleCallbackMs=-1, millisSincePictureVerified=-1)
            with self.subTest(reason=reason):
                validate_report(json.dumps(p).encode())
        for bad in ("UNREVIEWED_DEVICE", True, None, [], "arbitrary text"):
            p = report_v22(); p["samples"][0]["visualSampleBlockedReason"] = bad
            with self.subTest(bad=bad), self.assertRaises(SchemaError):
                validate_report(json.dumps(p).encode())
        p = report_v22(); p["samples"][0].pop("visualSampleBlockedReason")
        with self.assertRaises(SchemaError): validate_report(json.dumps(p).encode())
        for changes in ({"visualSampleOutcome":"DETAIL"}, {"visualSampleCallbackMs":0}, {"millisSincePictureVerified":100}):
            p = report_v22(); p["samples"][0].update(visualSampleBlockedReason="DEVICE_GPU_READBACK_UNSAFE",
                visualSampleOutcome="NO_SAMPLE", visualSampleCallbackMs=-1, millisSincePictureVerified=-1)
            p["samples"][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(SchemaError):
                validate_report(json.dumps(p).encode())

    def test_v22_does_not_expand_legacy_exact_shapes(self) -> None:
        for factory in (report_v10, report_v11, report_v12, report_v13, report_v14, report_v15,
                        report_v16, report_v17, report_v18, report_v19, report_v20, report_v21):
            p = factory(); validate_report(json.dumps(p).encode())
            p["samples"][0]["visualSampleBlockedReason"] = "unknown"
            with self.subTest(version=p["schemaVersion"]), self.assertRaises(SchemaError):
                validate_report(json.dumps(p).encode())

    def test_v22_actual_kotlin_wire_and_user_report_keep_explicit_block(self) -> None:
        import os
        directory = os.environ.get("KANTV_SCHEMA22_WIRE_FIXTURE_DIR")
        if not directory: self.skipTest("actual Kotlin v22 producer fixture not supplied")
        for name in ("device-blocked-sample.json", "device-blocked-user.json", "debug-blocked-sample.json", "unknown-sample.json"):
            sample = json.loads((Path(directory)/name).read_text())
            p = report_v22(); p["samples"] = [sample]
            validate_report(json.dumps(p).encode())
            self.assertEqual(201, self.post(p)[0])
            if name != "unknown-sample.json":
                self.assertEqual("NO_SAMPLE", sample["visualSampleOutcome"])
                self.assertEqual(-1, sample["visualSampleCallbackMs"])
                self.assertEqual(-1, sample["millisSincePictureVerified"])
                self.assertEqual("DEBUG_CONTROL" if name.startswith("debug") else "DEVICE_GPU_READBACK_UNSAFE",
                    sample["visualSampleBlockedReason"])
            if "user" in name: self.assertEqual("USER_SOURCE_REPORT", sample["kind"])
            p["schemaVersion"] = 21
            with self.assertRaises(SchemaError): validate_report(json.dumps(p).encode())
            if name != "unknown-sample.json":
                legacy = dict(sample); legacy.pop("visualSampleBlockedReason")
                p["samples"] = [legacy]; validate_report(json.dumps(p).encode())

    def test_v21_unknown_fields_and_strict_v20_compatibility(self) -> None:
        self.assertEqual(201, self.post(report_v21())[0])
        validate_report(json.dumps(report_v20()).encode())
        old = report_v20(); old["samples"][0]["geckoAudioRecoveryCount"] = -1
        with self.assertRaises(SchemaError): validate_report(json.dumps(old).encode())
        new = report_v21(); new["samples"][0].pop("geckoCaptureSequence")
        with self.assertRaises(SchemaError): validate_report(json.dumps(new).encode())

    def test_v21_tick_fault_capture_and_recovery_reject_invalid_shapes(self) -> None:
        for changes in ({"geckoAudioRecoveryCount":True}, {"geckoAudioRecoveryCount":3},
                {"geckoAudioQueryJsTickCount":0,"geckoAudioQueryMaxJsTickGapMs":0},
                {"geckoCaptureApiCallMs":1}, {"geckoCaptureSequence":0},
                {"geckoAudioRecoveryCount":2,"geckoAudioRetryRemainingMs":0},
                {"geckoAudioFirstFaultState":"private"}, {"geckoAudioFirstFaultReactionElapsedMs":1137}):
            p=report_v21();p["samples"][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(SchemaError): validate_report(json.dumps(p).encode())

    def test_v21_actual_kotlin_wire_preserves_first_fault_and_host_domains(self) -> None:
        import os
        directory=os.environ.get("KANTV_SCHEMA21_WIRE_FIXTURE_DIR")
        if not directory: self.skipTest("actual Kotlin producer fixture not supplied")
        for name in ("gecko-observer-fault-and-host.json", "gecko-observer-unknown.json"):
            sample=json.loads((Path(directory)/name).read_text())
            payload=report_v21();payload["samples"]=[sample]
            validate_report(json.dumps(payload).encode())
            if "fault-and-host" in name:
                self.assertEqual(179,sample["geckoAudioFirstFaultSeq"])
                self.assertEqual(180,sample["geckoAudioQuerySeq"])
                self.assertEqual(0,sample["geckoAudioFirstFaultJsTickCount"])
                self.assertEqual(-1,sample["geckoAudioFirstFaultMaxJsTickGapMs"])
                self.assertEqual(77,sample["geckoCaptureIssueToListenerMs"])

        legacy=Path(directory).parent/"schema20-wire-fixtures"
        for name in ("gecko-window-and-candidate-entry.json", "gecko-window-unknown.json"):
            payload=report_v20();payload["samples"]=[json.loads((legacy/name).read_text())]
            validate_report(json.dumps(payload).encode())

    def test_v20_unicode_envelope_and_actual_sample_budget_fit_upload_limit(self) -> None:
        import copy
        payload=report_v20()
        payload["decoderCapabilities"]=[{"name":"codec."+"c"*90,"types":["audio/"+"中"*90]*16,
            "hardwareAccelerated":None,"softwareOnly":None} for _ in range(96)]
        encode=lambda value:json.dumps(value,ensure_ascii=False,separators=(",", ":")).encode()
        sample=payload["samples"][0];envelope=len(encode(dict(payload,samples=[])))
        budget=min(300*1024,512*1024-envelope-8*1024)
        payload["samples"]=[copy.deepcopy(sample) for _ in range(min(119,(budget-128)//(len(encode(sample))+1)))]
        encoded=encode(payload);validate_report(encoded);self.assertLessEqual(len(encoded),512*1024)
        self.assertEqual(201,self.request("POST","/v1/reports",encoded,
            {"Authorization":"Bearer diagnostic-token","Content-Type":"application/json"})[0])

    def test_v19_phase_metrics_preserve_exact_legacy_shapes(self) -> None:
        self.assertEqual(201, self.post(report_v19())[0])
        for factory in (report_v17, report_v18):
            payload = factory(); validate_report(json.dumps(payload).encode())
            payload["samples"][0]["geckoBridgeLastTickDurationMs"] = -1
            with self.assertRaises(SchemaError): validate_report(json.dumps(payload).encode())
        payload = report_v19(); payload["samples"][0].pop("geckoStartupResourceCount")
        with self.assertRaises(SchemaError): validate_report(json.dumps(payload).encode())

    def test_v19_phase_and_startup_metrics_require_consistent_groups(self) -> None:
        payload = report_v19(); sample = payload["samples"][0]
        sample.update(geckoBridgeLastTickDurationMs=2260, geckoBridgeLastTickMaxPhaseCode="LOGIN_SCAN",
            geckoBridgeLastTickMaxPhaseDurationMs=2259, geckoBridgeLastTickScanNodes=2800,
            geckoStartupObserverTickCount=2, geckoStartupObserverTotalDurationMs=2260,
            geckoStartupObserverMaxTickDurationMs=2260)
        self.assertEqual(201, self.post(payload)[0])
        for changes in ({"geckoBridgeLastTickMaxPhaseDurationMs":2261}, {"geckoBridgeLastTickMaxPhaseCode":"UNKNOWN"},
                {"geckoBridgeLastTickMaxPhaseCode":"https://private.invalid"}, {"geckoBridgeLastTickScanNodes":2801},
                {"geckoBridgeLastTickDurationMs":True}, {"geckoStartupObserverTotalDurationMs":-1},
                {"geckoStartupObserverTickCount":0}):
            import copy
            bad=copy.deepcopy(payload);bad["samples"][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(SchemaError):validate_report(json.dumps(bad).encode())

    def test_v19_resource_timings_keep_restricted_or_overflow_evidence_explicit(self) -> None:
        payload=report_v19();sample=payload["samples"][0]
        sample.update(geckoStartupResourceObservationStatus="COMPLETE", geckoStartupResourceCount=4,
            geckoStartupResourceMaxDurationMs=9000, geckoStartupResourceMaxResponseWaitMs=-1,
            geckoStartupResourceMaxDownloadMs=-1, geckoStartupResourceDetailedCount=0)
        self.assertEqual(201,self.post(payload)[0])
        for changes in ({"geckoStartupResourceMaxResponseWaitMs":0}, {"geckoStartupResourceDetailedCount":5},
                {"geckoStartupResourceCount":129}, {"geckoStartupResourceObservationStatus":"EMPTY"},
                {"geckoStartupResourceObservationStatus":"UNSUPPORTED"}):
            import copy
            bad=copy.deepcopy(payload);bad["samples"][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(SchemaError):validate_report(json.dumps(bad).encode())
        sample.update(geckoStartupResourceObservationStatus="OVERFLOW",geckoStartupResourceCount=0,
            geckoStartupResourceMaxDurationMs=-1)
        validate_report(json.dumps(payload).encode())

    def test_v19_query_metrics_cannot_outlive_its_query_or_exceed_task(self) -> None:
        payload=report_v19();sample=payload["samples"][0]
        sample.update(geckoAudioQuerySeq=1,geckoAudioQueryNode=2,geckoAudioQueryEpoch=3,
            geckoAudioQueryState="RESOLVED",geckoAudioQueryDisposition="APPLIED",
            geckoAudioQueryStartMs=1000,geckoAudioSyncCallMs=1,
            geckoAudioQueryTickDurationMs=2260,geckoAudioQueryTickTailDurationMs=2259,
            geckoAudioQueryTickMaxPhaseCode="LOGIN_SCAN",geckoAudioQueryTickMaxPhaseDurationMs=2259,
            geckoAudioQueryTickScanNodes=2800)
        self.assertEqual(201,self.post(payload)[0])
        for changes in ({"geckoAudioQueryTickTailDurationMs":2261}, {"geckoAudioQueryState":"NOT_STARTED"}):
            import copy
            bad=copy.deepcopy(payload);bad["samples"][0].update(changes)
            with self.assertRaises(SchemaError):validate_report(json.dumps(bad).encode())

    def test_v19_unicode_envelope_and_actual_sample_budget_fit_upload_limit(self) -> None:
        import copy
        payload=report_v19()
        payload["decoderCapabilities"]=[{"name":"codec."+"c"*90,"types":["audio/"+"中"*90]*16,
            "hardwareAccelerated":None,"softwareOnly":None} for _ in range(96)]
        encode=lambda value:json.dumps(value,ensure_ascii=False,separators=(",", ":")).encode()
        sample=payload["samples"][0];envelope=len(encode(dict(payload,samples=[])))
        budget=min(300*1024,512*1024-envelope-8*1024)
        payload["samples"]=[copy.deepcopy(sample) for _ in range(min(119,(budget-128)//(len(encode(sample))+1)))]
        encoded=encode(payload);validate_report(encoded);self.assertLessEqual(len(encoded),512*1024)
        self.assertEqual(201,self.request("POST","/v1/reports",encoded,
            {"Authorization":"Bearer diagnostic-token","Content-Type":"application/json"})[0])

    def test_v18_unknown_requests_reject_old_stats_and_free_text(self) -> None:
        for changes in ({"nativeMediaRequestMatchStatus":"UNASSIGNED", "nativeMediaRequestReadDurationUs":0},
                        {"nativeMediaRequestCoverage":"REQUEST_ONLY"},
                        {"nativeMediaRequestMatchStatus":"http://private.invalid"},
                        {"nativeMediaRequestEofSeen":True},
                        {"nativeMediaRequestBytes":True}, {"nativeMediaRequestReadDurationUs":-2}):
            payload=report_v18();payload["samples"][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(SchemaError): validate_report(json.dumps(payload).encode())

    def test_v18_query_callback_observation_has_identity_and_bounds(self) -> None:
        payload=report_v18();sample=payload["samples"][0]
        sample.update(geckoAudioQuerySeq=1,geckoAudioQueryEpoch=2,geckoAudioQueryNode=3,
            geckoAudioQueryStartMs=1000,geckoAudioQueryState="PENDING",geckoAudioQueryDisposition="NONE",
            geckoAudioInFlight=True,geckoAudioSyncCallMs=0,
            geckoAudioQueryRvfcCount=4,geckoAudioQueryMaxRvfcGapMs=39,
            geckoDocumentNowMs=1200,geckoDocumentTimeOriginMs=1_729_000_000_000,
            geckoStateReceivedEpochMs=1_729_000_001_222)
        self.assertEqual(201,self.post(payload)[0])
        for changes in ({"geckoAudioQueryRvfcCount":-1}, {"geckoAudioQueryRvfcCount":1_000_001},
                        {"geckoAudioQueryMaxRvfcGapMs":86_400_001},
                        {"geckoDocumentNowMs":-2}, {"geckoDocumentTimeOriginMs":True},
                        {"geckoStateReceivedEpochMs":9_007_199_254_740_992}):
            import copy
            bad=copy.deepcopy(payload);bad["samples"][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(SchemaError): validate_report(json.dumps(bad).encode())
        sample["geckoAudioQueryRvfcCount"]=-1;sample["geckoAudioQueryMaxRvfcGapMs"]=-1
        validate_report(json.dumps(payload).encode())

    def test_v18_extensions_do_not_change_legacy_exact_shapes(self) -> None:
        for factory in (report_v10,report_v11,report_v12,report_v13,report_v14,report_v15,report_v16,report_v17):
            payload=factory();validate_report(json.dumps(payload).encode())
            payload["samples"][0]["nativeMediaRequestMatchStatus"]="NOT_APPLICABLE"
            with self.subTest(version=payload["schemaVersion"]),self.assertRaises(SchemaError):validate_report(json.dumps(payload).encode())
        payload=report_v18();payload["samples"][0].pop("geckoDocumentNowMs")
        with self.assertRaises(SchemaError):validate_report(json.dumps(payload).encode())

    def test_v18_utf8_envelope_keeps_maximum_metadata_under_upload_limit(self) -> None:
        import copy
        payload=report_v18()
        payload["decoderCapabilities"]=[{"name":"codec."+"c"*90,"types":["audio/"+"中"*90]*16,
            "hardwareAccelerated":None,"softwareOnly":None} for _ in range(96)]
        encode=lambda value:json.dumps(value,ensure_ascii=False,separators=(",", ":")).encode()
        sample=payload["samples"][0];envelope=len(encode(dict(payload,samples=[])))
        budget=min(300*1024,512*1024-envelope-8*1024)
        payload["samples"]=[copy.deepcopy(sample) for _ in range(min(119,(budget-128)//(len(encode(sample))+1)))]
        encoded=encode(payload);validate_report(encoded);self.assertLessEqual(len(encoded),512*1024)
        self.assertEqual(201,self.request("POST","/v1/reports",encoded,
            {"Authorization":"Bearer diagnostic-token","Content-Type":"application/json"})[0])

    def test_v17_maximum_identity_envelope_and_byte_bounded_fragment_fit_http_limit(self) -> None:
        import copy
        payload = report_v17()
        payload["decoderCapabilities"] = [{"name": "codec." + "c" * 90,
            "types": ["audio/" + "a" * 90] * 16,
            "hardwareAccelerated": None, "softwareOnly": None} for _ in range(96)]
        sample = payload["samples"][0]
        size = len(json.dumps(sample, ensure_ascii=False, separators=(",", ":")).encode())
        envelope = dict(payload, samples=[])
        envelope_size = len(json.dumps(envelope, ensure_ascii=False, separators=(",", ":")).encode())
        budget = min(300 * 1024, 512 * 1024 - envelope_size - 8 * 1024)
        count = (budget - 128) // (size + 1)
        payload["samples"] = [copy.deepcopy(sample) for _ in range(min(count, 119))]
        encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
        validate_report(encoded)
        self.assertLessEqual(len(encoded), 512 * 1024)
        self.assertEqual(201, self.post(payload)[0])

    def test_v17_unicode_identity_uses_actual_utf8_budget_and_uploads(self) -> None:
        import copy
        payload = report_v17()
        payload["decoderCapabilities"] = [{"name": "codec." + "c" * 90,
            "types": ["audio/" + "中" * 90] * 16,
            "hardwareAccelerated": None, "softwareOnly": None} for _ in range(96)]
        sample = payload["samples"][0]
        encode = lambda value: json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()
        envelope_size = len(encode(dict(payload, samples=[])))
        budget = min(300 * 1024, 512 * 1024 - envelope_size - 8 * 1024)
        self.assertGreater(envelope_size, 400 * 1024)
        self.assertLess(budget, 300 * 1024)
        count = min(119, (budget - 128) // (len(encode(sample)) + 1))
        self.assertGreater(count, 0)
        payload["samples"] = [copy.deepcopy(sample) for _ in range(count)]
        encoded = encode(payload)
        validate_report(encoded)
        self.assertLessEqual(len(encoded), 512 * 1024)
        status, _ = self.request("POST", "/v1/reports", encoded,
            {"Authorization": "Bearer diagnostic-token", "Content-Type": "application/json"})
        self.assertEqual(201, status)

    def test_v17_known_window_is_retained_when_playback_position_is_unknown(self) -> None:
        payload = report_v17()
        sample = payload["samples"][0]
        sample.update(nativeLoadPositionStatus="WINDOW_ONLY", nativeLoadWindowStartInPeriodMs=26_000,
                      nativeLiveWindowDurationMs=20_000, timeMs=-1)
        self.assertEqual(201, self.post(payload)[0])
        for changes in ({"nativeLiveWindowDurationMs": -1}, {"timeMs": 0},
                        {"nativeLoadPositionStatus": "UNKNOWN_TIME"}):
            with self.subTest(changes=changes):
                invalid = report_v17()
                invalid["samples"][0].update(sample)
                invalid["samples"][0].update(changes)
                with self.assertRaises(SchemaError): validate_report(json.dumps(invalid).encode())

    def test_v16_native_fixed_pair_and_unknown_sentinels(self) -> None:
        payload = report_v16()
        validate_report(json.dumps(payload).encode("utf-8"))
        sample = payload["samples"][0]
        sample.update({
            "nativeAudioClockStatus": "AVAILABLE", "nativeAvGeneration": 9,
            "nativeAudioStreamOffsetUs": 1_000_000_000_000, "nativeAudioClockUs": 79_999,
            "nativeAudioClockAgeMs": 500, "nativePairedVideoPtsUs": 80_000,
            "nativeAudioMinusVideoUs": -1, "nativeAvPairSpanUs": 40_000,
            "nativeVideoReleaseDelayUs": 20_000, "geckoAudioClockStatus": "NOT_APPLICABLE",
            "probeObservationStatus": "NOT_APPLICABLE", "avSyncAssessment": "UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED",
        })
        for key in ("geckoAudioClockUs", "geckoAudioClockAgeMs", "geckoAudioClockCallDurationMs",
                    "geckoPairedVideoPtsUs", "geckoAudioMinusVideoUs", "geckoAvPairSpanMs"):
            sample[key] = -1
        validate_report(json.dumps(payload).encode("utf-8"))
        sample["nativeAudioStreamOffsetUs"] = -40_000
        validate_report(json.dumps(payload).encode("utf-8"))
        for key, bad in (("nativeAudioClockStatus", "PASS"), ("nativeAudioClockAgeMs", 1501),
                         ("nativeAvPairSpanUs", 100001), ("nativeAudioMinusVideoUs", 0),
                         ("avSyncAssessment", "UNKNOWN_NOT_EVALUATED"), ("nativeAvGeneration", -1)):
            before = sample[key]; sample[key] = bad
            with self.assertRaises(SchemaError): validate_report(json.dumps(payload).encode("utf-8"))
            sample[key] = before
        sample["nativeAudioClockStatus"] = "INSUFFICIENT_STALE_SAMPLE"
        with self.assertRaises(SchemaError): validate_report(json.dumps(payload).encode("utf-8"))

    def test_v16_live_context_is_bounded_and_error_code_is_only_http_number(self) -> None:
        payload = report_v16()
        sample = payload["samples"][0]
        sample.update({"nativeLiveContextAgeMs": 500, "nativeLiveOffsetMs": 12000,
            "nativeLiveWindowDurationMs": 80000, "nativeLiveMediaSequence": 123,
            "nativeLastMediaLoadDurationMs": 400, "nativeLastMediaLoadBytes": 65536,
            "nativeLastLoadErrorCode": 503})
        validate_report(json.dumps(payload).encode("utf-8"))
        for key, bad in (("nativeLastLoadErrorCode", 0), ("nativeLastLoadErrorCode", 600),
                         ("nativeLiveContextAgeMs", -2), ("nativeLastMediaLoadBytes", 9007199254740992)):
            before = sample[key]; sample[key] = bad
            with self.assertRaises(SchemaError): validate_report(json.dumps(payload).encode("utf-8"))
            sample[key] = before

    def test_v16_exact_shape_and_older_versions_reject_native_fields(self) -> None:
        from schema import NATIVE_AUDIO_NUMBERS_V16, NATIVE_LIVE_NUMBERS_V16
        for key in NATIVE_AUDIO_NUMBERS_V16 | NATIVE_LIVE_NUMBERS_V16 | {"nativeAudioClockStatus"}:
            payload = report_v16(); del payload["samples"][0][key]
            with self.assertRaises(SchemaError): validate_report(json.dumps(payload).encode("utf-8"))
        for factory in (report_v10, report_v11, report_v12, report_v13, report_v14, report_v15):
            payload = factory(); validate_report(json.dumps(payload).encode("utf-8"))
            payload["samples"][0]["nativeAudioClockStatus"] = "NOT_APPLICABLE"
            with self.assertRaises(SchemaError): validate_report(json.dumps(payload).encode("utf-8"))

    def test_v15_exact_signed_pair_and_unknown_physical_assessment(self) -> None:
        payload = report_v15()
        self.assertEqual(201, self.post(payload)[0])
        saved = json.loads((self.directory / f"{payload['reportId']}.json").read_text())
        self.assertEqual(payload["samples"], saved["samples"])
        self.assertEqual(-1, saved["samples"][0]["geckoAudioMinusVideoUs"])
        for status in ("INSUFFICIENT_UNSUPPORTED", "INSUFFICIENT_TIMEOUT", "INSUFFICIENT_PROBE_INVALID", "NOT_APPLICABLE"):
            payload = report_v15()
            payload["samples"][0].update({"geckoAudioClockStatus": status,
                "geckoAudioClockUs": -1, "geckoPairedVideoPtsUs": -1,
                "geckoAudioClockAgeMs": -1, "geckoAudioClockCallDurationMs": -1,
                "geckoAudioMinusVideoUs": -1, "geckoAvPairSpanMs": -1,
                "avSyncAssessment": "UNKNOWN_NO_MEDIA_ELEMENT_AUDIO_OUTPUT_TIMESTAMP"})
            self.assertEqual(201, self.post(payload)[0])

    def test_v15_requires_exact_shape_bounded_pair_and_fixed_enums(self) -> None:
        fields = ("geckoAudioClockStatus", "geckoAudioClockUs", "geckoAudioClockAgeMs",
            "geckoAudioClockCallDurationMs", "geckoPairedVideoPtsUs", "geckoAudioMinusVideoUs", "geckoAvPairSpanMs")
        for field in fields:
            payload = report_v15(); del payload["samples"][0][field]
            self.assertEqual(400, self.post(payload)[0])
        for field, value in (("geckoAudioClockStatus", "HEALTHY"), ("geckoAudioClockUs", 0),
            ("geckoAudioClockUs", 9_007_199_254_740_992), ("geckoAudioClockAgeMs", 5001),
            ("geckoAudioClockAgeMs", True), ("geckoAudioClockCallDurationMs", 101),
            ("geckoPairedVideoPtsUs", -1), ("geckoAudioMinusVideoUs", 0),
            ("geckoAudioMinusVideoUs", -9_007_199_254_740_992), ("geckoAudioMinusVideoUs", -1.2),
            ("geckoAvPairSpanMs", 101), ("avSyncAssessment", "SYNC_NORMAL"),
            ("avSyncAssessment", "UNKNOWN_NOT_EVALUATED"), ("probeObservationStatus", "INSUFFICIENT_STALE_SAMPLE"),
            ("decoderInstance", "secret"), ("url", "https://private.invalid/token")):
            with self.subTest(field=field, value=value):
                payload = report_v15(); payload["samples"][0][field] = value
                self.assertEqual(400, self.post(payload)[0])

    def test_v15_suspension_is_explicit_and_cannot_claim_available(self) -> None:
        payload = report_v15()
        payload["samples"][0].update({"probeObservationStatus": "INSUFFICIENT_SUSPENDED",
            "geckoAudioClockStatus": "INSUFFICIENT_PROBE_INVALID", "geckoAudioClockUs": -1,
            "geckoPairedVideoPtsUs": -1, "geckoAudioMinusVideoUs": -1, "geckoAvPairSpanMs": -1,
            "avSyncAssessment": "UNKNOWN_NO_MEDIA_ELEMENT_AUDIO_OUTPUT_TIMESTAMP"})
        self.assertEqual(201, self.post(payload)[0])
        payload["reportId"] = str(uuid.uuid4())
        payload["samples"][0]["geckoAudioClockStatus"] = "AVAILABLE"
        self.assertEqual(400, self.post(payload)[0])
        payload = report_v14()
        payload["samples"][0]["probeObservationStatus"] = "INSUFFICIENT_SUSPENDED"
        self.assertEqual(400, self.post(payload)[0])

    def test_v10_to_v14_keep_exact_legacy_shapes(self) -> None:
        for factory in (report_v10, report_v11, report_v12, report_v13, report_v14):
            with self.subTest(version=factory):
                self.assertEqual(201, self.post(factory())[0])
                payload = factory(); payload["samples"][0]["geckoAudioClockStatus"] = "NOT_APPLICABLE"
                self.assertEqual(400, self.post(payload)[0])
        payload = report_v14()
        payload["samples"][0]["avSyncAssessment"] = "UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED"
        self.assertEqual(400, self.post(payload)[0])

    def test_v14_accepts_zero_relative_timing_without_claiming_sync(self) -> None:
        payload = report_v14()
        self.assertEqual(201, self.post(payload)[0])
        payload = report_v14()
        payload["samples"][0].update({
            "probeObservationStatus": "INSUFFICIENT_STALE_SAMPLE",
            "videoCallbackAgeMs": -1, "videoPresentationAgeMs": -1,
            "videoExpectedDisplayDelayUs": -1,
        })
        self.assertEqual(201, self.post(payload)[0])

    def test_v14_requires_all_fields_and_rejects_unbounded_or_free_text(self) -> None:
        keys = ("probeObservationStatus", "avSyncAssessment", "videoCallbackAgeMs",
                "videoPresentationAgeMs", "videoExpectedDisplayDelayUs")
        for key in keys:
            with self.subTest(missing=key):
                payload = report_v14()
                del payload["samples"][0][key]
                self.assertEqual(400, self.post(payload)[0])
        for key, value in (("probeObservationStatus", "HEALTHY"),
                           ("avSyncAssessment", "SYNC_NORMAL"),
                           ("videoCallbackAgeMs", -2),
                           ("videoPresentationAgeMs", 9_007_199_254_740_992),
                           ("videoExpectedDisplayDelayUs", 1_000_001),
                           ("videoExpectedDisplayDelayUs", True)):
            with self.subTest(key=key, value=value):
                payload = report_v14()
                payload["samples"][0][key] = value
                self.assertEqual(400, self.post(payload)[0])

    def test_accepts_v13_label_click_fragment_without_dropping_fields(self) -> None:
        payload = report_v13()
        self.assertEqual(201, self.post(payload)[0])
        saved = json.loads((self.directory / f"{payload['reportId']}.json").read_text())
        self.assertEqual(payload["samples"], saved["samples"])
        self.assertIs(True, saved["samples"][0]["labelFound"])
        self.assertIs(False, saved["samples"][0]["labelClicked"])

    def test_v13_requires_label_found_and_label_clicked(self) -> None:
        for missing in ("labelFound", "labelClicked"):
            with self.subTest(missing=missing):
                payload = report_v13()
                del payload["samples"][0][missing]
                with self.assertRaises(SchemaError) as caught:
                    validate_report(json.dumps(payload).encode("utf-8"))
                self.assertIn("字段必须严格为", str(caught.exception))
                self.assertEqual(400, self.post(payload)[0])
        wrong_type = report_v13()
        wrong_type["samples"][0]["labelFound"] = "yes"
        with self.assertRaises(SchemaError):
            validate_report(json.dumps(wrong_type).encode("utf-8"))
        self.assertEqual(400, self.post(wrong_type)[0])

    def test_rejects_auth_schema_and_length_edges(self) -> None:
        self.assertEqual(401, self.post(report_v10(), "wrong-token")[0])
        invalid = report_v10()
        invalid["unexpectedLog"] = "https://secret.example/stream"
        self.assertEqual(400, self.post(invalid)[0])
        malformed_reason = report_v10()
        malformed_reason["reason"] = ["NO_FIRST_FRAME_30S"]
        self.assertEqual(400, self.post(malformed_reason)[0])
        malformed_provider = report_v10()
        malformed_provider["provider"] = {"CCTV": True}
        self.assertEqual(400, self.post(malformed_provider)[0])
        body = b"{}"
        self.assertEqual(415, self.request("POST", "/v1/reports", body, {"Authorization": "Bearer diagnostic-token"})[0])
        self.assertEqual(413, self.request("POST", "/v1/reports", body, {"Authorization": "Bearer diagnostic-token", "Content-Type": "application/json", "Content-Length": str(512 * 1024 + 1)})[0])

    def test_count_limit_archives_whole_batch_preserving_bytes_and_checksums(self) -> None:
        self.restart(max_reports=2)
        old = [report_v10(), report_v13()]
        canonical = {f"{payload['reportId']}.json": validate_report(json.dumps(payload).encode()).canonical_bytes for payload in old}
        for payload in old:
            self.assertEqual(201, self.post(payload)[0])
        self.assertEqual([], list(self.directory.glob("archive-*")))
        with self.assertLogs("receiver", level="INFO") as logs:
            self.assertEqual(201, self.post(report_v12())[0])
        self.assertRegex(" ".join(logs.output), r"reports_archived count=2 bytes=\d+ duration_ms=\d+")
        self.assertEqual(1, len(list(self.directory.glob("*.json"))))
        archive, = self.directory.glob("archive-*")
        manifest = json.loads((archive / "manifest.json").read_bytes())
        self.assertEqual(set(canonical), {entry["name"] for entry in manifest["reports"]})
        for entry in manifest["reports"]:
            saved = (archive / entry["name"]).read_bytes()
            self.assertEqual(canonical[entry["name"]], saved)
            self.assertEqual(len(saved), entry["size"])
            self.assertEqual(hashlib.sha256(saved).hexdigest(), entry["sha256"])
        self.assertEqual(0o700, archive.stat().st_mode & 0o777)
        self.assertEqual(0o600, (archive / "manifest.json").stat().st_mode & 0o777)

    def test_byte_limit_archives_before_incoming_would_exceed_limit(self) -> None:
        first, incoming = report_v10(), report_v11()
        first_bytes = validate_report(json.dumps(first).encode()).canonical_bytes
        incoming_bytes = validate_report(json.dumps(incoming).encode()).canonical_bytes
        self.restart(max_storage_bytes=max(len(first_bytes), len(incoming_bytes)))
        self.assertEqual(201, self.post(first)[0])
        self.assertEqual(201, self.post(incoming)[0])
        archive, = self.directory.glob("archive-*")
        self.assertEqual(first_bytes, (archive / f"{first['reportId']}.json").read_bytes())
        self.assertEqual(incoming_bytes, (self.directory / f"{incoming['reportId']}.json").read_bytes())

    def test_archived_retransmission_and_conflict_survive_restart(self) -> None:
        self.restart(max_reports=1)
        old = report_v10()
        self.assertEqual(201, self.post(old)[0])
        self.assertEqual(201, self.post(report_v13())[0])
        for restart in (False, True):
            if restart:
                self.restart(max_reports=1)
            self.assertEqual((200, {"reportId": old["reportId"], "stored": False}), self.post(old))
            conflicting = {**old, "reason": "PLAYBACK_STALLED_15S"}
            self.assertEqual(409, self.post(conflicting)[0])
        self.assertEqual(1, len(list(self.directory.glob("archive-*"))))
        self.assertFalse((self.directory / f"{old['reportId']}.json").exists())

    def test_manual_archive_metadata_is_ignored_and_duplicate_ids_never_overwritten(self) -> None:
        payload = report_v10()
        canonical = validate_report(json.dumps(payload).encode()).canonical_bytes
        for name in ("archive-20261012T112345+0800", "archive-other"):
            archive = self.directory / name
            archive.mkdir()
            (archive / f"{payload['reportId']}.json").write_bytes(canonical)
            for metadata in ("manifest.json", "recovery-result.json", "verification.json"):
                (archive / metadata).write_bytes(b"not a report or valid JSON")
        self.restart()
        self.assertEqual(200, self.post(payload)[0])
        other = self.directory / "archive-other" / f"{payload['reportId']}.json"
        conflicting = {**payload, "reason": "PLAYBACK_STALLED_15S"}
        other.write_bytes(validate_report(json.dumps(conflicting).encode()).canonical_bytes)
        self.restart()
        self.assertEqual(409, self.post(payload)[0])
        self.assertEqual(409, self.post(conflicting)[0])
        self.assertFalse((self.directory / other.name).exists())
        self.assertEqual(201, self.post(report_v12())[0])

    def test_partial_archive_failure_retains_all_reports_and_idempotency(self) -> None:
        self.restart(max_reports=2)
        old = [report_v10(), report_v11()]
        for payload in old:
            self.assertEqual(201, self.post(payload)[0])
        incoming = report_v13()
        original_rename = receiver.os.rename
        calls = 0

        def fail_second_move(source, destination):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError(errno.EIO, "secret error text must never enter logs")
            original_rename(source, destination)

        with patch("receiver.os.rename", side_effect=fail_second_move), patch("receiver._sync_directory", wraps=receiver._sync_directory) as sync, self.assertLogs("receiver", level="WARNING") as logs:
            self.assertEqual((507, {"error": "storage_unavailable"}), self.post(incoming))
        archive, = self.directory.glob("archive-*")
        self.assertEqual([archive, self.directory], [call.args[0] for call in sync.call_args_list[-2:]])
        self.assertIn("errno=5", " ".join(logs.output))
        self.assertNotIn("secret", " ".join(logs.output))
        for payload in old:
            paths = list(self.directory.rglob(f"{payload['reportId']}.json"))
            self.assertEqual(1, len(paths))
            self.assertEqual(validate_report(json.dumps(payload).encode()).canonical_bytes, paths[0].read_bytes())
            self.assertEqual(200, self.post(payload)[0])
        self.restart(max_reports=2)
        for payload in old:
            self.assertEqual(200, self.post(payload)[0])
        self.assertEqual(201, self.post(incoming)[0])

    def test_archive_creation_failure_preserves_active_report_and_does_not_accept_new(self) -> None:
        self.restart(max_reports=1)
        old, incoming = report_v10(), report_v12()
        self.assertEqual(201, self.post(old)[0])
        with patch("receiver.tempfile.mkdtemp", side_effect=OSError(errno.ENOSPC, "disk full")):
            self.assertEqual(507, self.post(incoming)[0])
        self.assertTrue((self.directory / f"{old['reportId']}.json").is_file())
        self.assertFalse((self.directory / f"{incoming['reportId']}.json").exists())
        self.assertEqual(200, self.post(old)[0])
        self.assertEqual(201, self.post(incoming)[0])

    def test_disk_full_during_new_report_write_returns_507_and_retry_can_succeed(self) -> None:
        payload = report_v10()
        with patch("receiver.os.fsync", side_effect=OSError(errno.ENOSPC, "disk full")):
            self.assertEqual(507, self.post(payload)[0])
        self.assertEqual([], list(self.directory.iterdir()))
        self.assertEqual(201, self.post(payload)[0])

    def test_committed_report_retry_cannot_succeed_until_directory_sync_recovers(self) -> None:
        payload = report_v10()
        canonical = validate_report(json.dumps(payload).encode()).canonical_bytes
        with patch("receiver._sync_directory", side_effect=OSError(errno.EIO, "directory sync failed")) as sync:
            self.assertEqual(507, self.post(payload)[0])
            self.assertEqual(507, self.post(payload)[0])
            self.assertEqual(2, sync.call_count)
        saved = self.directory / f"{payload['reportId']}.json"
        self.assertEqual(canonical, saved.read_bytes())
        self.assertEqual(200, self.post(payload)[0])
        self.restart()
        self.assertEqual(200, self.post(payload)[0])
        self.assertEqual(canonical, saved.read_bytes())

    def test_existing_report_conflict_remains_409_when_directory_sync_fails(self) -> None:
        self.restart(max_reports=1)
        payload = report_v10()
        self.assertEqual(201, self.post(payload)[0])
        conflicting = {**payload, "reason": "PLAYBACK_STALLED_15S"}
        for archived in (False, True):
            if archived:
                self.assertEqual(201, self.post(report_v13())[0])
            with patch("receiver._sync_directory", side_effect=OSError(errno.EIO, "directory sync failed")) as sync:
                self.assertEqual(409, self.post(conflicting)[0])
                sync.assert_not_called()

    def test_archive_sync_failure_retains_reports_and_retries_sync_before_200(self) -> None:
        self.restart(max_reports=1)
        old, incoming = report_v10(), report_v13()
        self.assertEqual(201, self.post(old)[0])
        original_sync = receiver._sync_directory
        calls = 0

        def fail_after_manifest_persisted(directory):
            nonlocal calls
            calls += 1
            if calls > 2:
                raise OSError(errno.EIO, "directory sync failed")
            original_sync(directory)

        with patch("receiver._sync_directory", side_effect=fail_after_manifest_persisted):
            self.assertEqual(507, self.post(incoming)[0])
            self.assertEqual(507, self.post(old)[0])
        archive, = self.directory.glob("archive-*")
        archived = archive / f"{old['reportId']}.json"
        self.assertEqual(validate_report(json.dumps(old).encode()).canonical_bytes, archived.read_bytes())
        with patch("receiver._sync_directory", wraps=original_sync) as sync:
            self.assertEqual(200, self.post(old)[0])
        self.assertEqual({archive, self.directory}, {call.args[0] for call in sync.call_args_list})
        self.assertEqual(201, self.post(incoming)[0])
        self.restart(max_reports=1)
        self.assertEqual(200, self.post(old)[0])

    def test_single_report_larger_than_active_byte_limit_does_not_archive(self) -> None:
        old, incoming = report_v10(), report_v13()
        old_bytes = validate_report(json.dumps(old).encode()).canonical_bytes
        self.restart(max_storage_bytes=len(old_bytes))
        self.assertEqual(201, self.post(old)[0])
        self.assertEqual(507, self.post(incoming)[0])
        self.assertEqual([], list(self.directory.glob("archive-*")))
        self.assertEqual(old_bytes, (self.directory / f"{old['reportId']}.json").read_bytes())

    def test_archives_never_follow_directory_or_report_symlinks(self) -> None:
        outside = Path(self.temporary.name) / "outside"
        outside.mkdir()
        unsafe_archive = self.directory / "archive-unsafe"
        unsafe_archive.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(OSError):
            create_server("127.0.0.1", 0, ReceiverConfig("diagnostic-token", self.directory))
        unsafe_archive.unlink()
        unsafe_archive.mkdir()
        payload = report_v10()
        target = outside / "private.json"
        target.write_bytes(b"sensitive evidence")
        unsafe_report = unsafe_archive / f"{payload['reportId']}.json"
        unsafe_report.symlink_to(target)
        with self.assertRaises(OSError):
            create_server("127.0.0.1", 0, ReceiverConfig("diagnostic-token", self.directory))
        unsafe_report.unlink()
        # 非报告名的 metadata 符号链接也不能被读取或归档。
        (unsafe_archive / "verification.json").symlink_to(target)
        self.restart(max_reports=1)
        self.assertEqual(201, self.post(payload)[0])
        self.assertEqual(201, self.post(report_v13())[0])
        self.assertEqual(b"sensitive evidence", target.read_bytes())

    def test_replaced_archive_directory_and_active_report_symlink_are_rejected(self) -> None:
        self.restart(max_reports=1)
        old = report_v10()
        self.assertEqual(201, self.post(old)[0])
        self.assertEqual(201, self.post(report_v12())[0])
        archive, = self.directory.glob("archive-*")
        moved = self.directory / "saved-evidence"
        archive.rename(moved)
        archive.symlink_to(moved, target_is_directory=True)
        self.assertEqual(507, self.post(old)[0])
        payload = report_v13()
        (self.directory / f"{payload['reportId']}.json").symlink_to(moved / f"{old['reportId']}.json")
        self.assertEqual(409, self.post(payload)[0])

    def test_archive_collision_never_overwrites_existing_evidence(self) -> None:
        self.restart(max_reports=1)
        old = report_v10()
        self.assertEqual(201, self.post(old)[0])
        archive = self.directory / "archive-existing"
        archive.mkdir()
        (archive / "manifest.json").write_bytes(b"previous manifest")
        with patch("receiver.tempfile.mkdtemp", return_value=str(archive)):
            self.assertEqual(507, self.post(report_v13())[0])
        self.assertEqual(b"previous manifest", (archive / "manifest.json").read_bytes())
        self.assertEqual(200, self.post(old)[0])

    def test_failure_path_accepts_legal_structured_paths(self) -> None:
        for message, expected in (
            ("report.schemaVersion: 必须为已支持版本", "report.schemaVersion"),
            ("report.samples[0].kind: 必须是以下枚举之一", "report.samples[0].kind"),
            ("report.decoderCapabilities[12].name: 必须是受限技术标识", "report.decoderCapabilities[12].name"),
            ("body: 必须是 UTF-8 JSON", "body"),
            ("report: 无法规范化", "report"),
        ):
            with self.subTest(message=message):
                self.assertEqual(expected, _failure_path(SchemaError(message)))

    def test_failure_path_degrades_to_unknown_for_sensitive_or_unstructured_text(self) -> None:
        for message in (
            "report.https://secret.example/live: Bearer super-secret-token",
            "Authorization: Bearer super-secret-token",
            "report.channel-secret-marker: 保留字段",
            "report.CCTV5: 频道泄露",
            "report." + ("a" * 400) + ": 过长路径",
            "https://secret.example/stream",
            "",
        ):
            with self.subTest(message=message):
                self.assertEqual("unknown", _failure_path(SchemaError(message)))

    def test_invalid_report_logs_only_structured_path_and_returns_400(self) -> None:
        payload = report_v10()
        payload["channelId"] = "channel-secret-marker"
        payload["deviceModel"] = "device-secret-marker"
        payload["schemaVersion"] = "schema-secret-value"
        with self.assertLogs("receiver", level="WARNING") as captured:
            status, body = self.post(payload)
        self.assertEqual(400, status)
        self.assertEqual({"error": "invalid_report"}, body)
        messages = [record.getMessage() for record in captured.records]
        self.assertEqual(["invalid_report path=report.schemaVersion"], messages)
        logged = "\n".join(messages)
        for sensitive in (
            "schema-secret-value",
            "channel-secret-marker",
            "device-secret-marker",
            "diagnostic-token",
            json.dumps(payload),
            "必须是",
        ):
            self.assertNotIn(sensitive, logged)

    def test_invalid_sample_field_logs_indexed_structured_path(self) -> None:
        payload = report_v10()
        payload["samples"][0]["kind"] = "NOT_A_REAL_KIND"
        with self.assertLogs("receiver", level="WARNING") as captured:
            status, body = self.post(payload)
        self.assertEqual(400, status)
        self.assertEqual({"error": "invalid_report"}, body)
        self.assertEqual(
            ["invalid_report path=report.samples[0].kind"],
            [record.getMessage() for record in captured.records],
        )

    def test_sensitive_forbidden_key_logs_unknown_instead_of_key(self) -> None:
        payload = report_v10()
        payload["samples"][0]["url-secret-marker"] = "https://secret.example/live"
        with self.assertLogs("receiver", level="WARNING") as captured:
            status, body = self.post(payload)
        self.assertEqual(400, status)
        self.assertEqual({"error": "invalid_report"}, body)
        self.assertEqual(
            ["invalid_report path=unknown"],
            [record.getMessage() for record in captured.records],
        )
        logged = "\n".join(record.getMessage() for record in captured.records)
        self.assertNotIn("url-secret-marker", logged)
        self.assertNotIn("secret.example", logged)


if __name__ == "__main__":
    unittest.main()
