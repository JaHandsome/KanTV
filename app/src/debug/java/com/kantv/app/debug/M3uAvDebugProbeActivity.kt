package com.kantv.app.debug

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.kantv.app.diagnostics.NativeDiagnosticMapper
import com.kantv.app.playback.DiagnosticType
import com.kantv.app.playback.Media3PlaybackEngine
import com.kantv.app.playback.PlaybackDiagnostics
import com.kantv.app.playback.PlaybackEvent
import com.kantv.app.playback.PlaybackRequest
import org.json.JSONObject

/** Local synthetic HLS only; uses the unchanged production engine/factory/buffer defaults. */
@OptIn(UnstableApi::class)
class M3uAvDebugProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val diagnostics = PlaybackDiagnostics()
    private var engine: Media3PlaybackEngine? = null
    private lateinit var view: PlayerView
    private var startedAtMs = 0L
    private var errors = 0
    private var loading = 0
    private var state = "OPENING"
    private var tick = 0
    private var phase = "normal"
    private var sourceAttempt = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val scenario = intent.getStringExtra("scenario").takeIf { it in listOf("normal", "cycle", "delay", "sync", "open_delay", "read_delay") } ?: "normal"
        val fixtureScenario = if (scenario == "sync") "normal" else scenario
        view = PlayerView(this).apply { useController = false }
        setContentView(view)
        startedAtMs = SystemClock.elapsedRealtime()
        val created = Media3PlaybackEngine(this, view)
        engine = created
        diagnostics.beginSource(SESSION, "synthetic_fixture", 0, 1)
        created.setEventListener { event ->
            when (event) {
                is PlaybackEvent.Technical -> diagnostics.recordTechnical(SESSION, event.update)
                is PlaybackEvent.Loading -> {
                    loading++
                    state = "BUFFERING"
                    emit("loading_boundary")
                }
                is PlaybackEvent.Ready -> state = "READY"
                is PlaybackEvent.FirstFrame -> diagnostics.record(SESSION, null, DiagnosticType.FIRST_FRAME)
                is PlaybackEvent.VideoProgress -> diagnostics.record(SESSION, null, DiagnosticType.VIDEO_PROGRESS)
                is PlaybackEvent.Error -> {
                    errors++
                    state = "ERROR"
                    diagnostics.record(SESSION, null, DiagnosticType.ERROR, event.errorCode)
                    Log.i(TAG, JSONObject().put("kind", "error").put("elapsedMs", elapsed())
                        .put("errorCode", event.errorCode).put("errorKind", event.kind.name).toString())
                }
                is PlaybackEvent.Ended -> state = "ENDED"
            }
        }
        created.play(SESSION, PlaybackRequest(
            channelId = "synthetic_fixture",
            uri = "http://127.0.0.1:18766/$fixtureScenario/master.m3u8",
            mimeType = "application/x-mpegURL",
        ))
        if (scenario == "cycle") {
            handler.postDelayed({
                phase = "seek"
                val player = view.player
                player?.seekTo(((player.currentPosition) - 2_000).coerceAtLeast(0))
                emit("action")
            }, 12_000)
            handler.postDelayed({ phase = "paused"; created.pause(); emit("action") }, 18_000)
            handler.postDelayed({ phase = "resumed"; created.resume(); emit("action") }, 23_000)
            handler.postDelayed({ phase = "reopened"; sourceAttempt++; created.reopen(SESSION); emit("action") }, 31_000)
        } else if (scenario == "sync") {
            handler.postDelayed({ phase = "paused"; created.pause(); emit("action") }, 6_000)
            handler.postDelayed({ phase = "resumed"; created.resume(); emit("action") }, 8_000)
            handler.postDelayed({
                phase = "seek"
                val player = view.player
                if (player != null) player.seekTo((player.currentPosition - 2_000).coerceAtLeast(0))
                emit("action")
            }, 12_000)
        }
        handler.postDelayed(sampler, 1_000)
        handler.postDelayed({
            emit("complete")
            handler.removeCallbacks(sampler)
            created.release()
            engine = null
        }, if (scenario == "sync") 18_000 else 45_000)
    }

    private val sampler = object : Runnable {
        override fun run() {
            tick++
            emit("sample")
            if (engine != null) handler.postDelayed(this, 1_000)
        }
    }

    private fun elapsed() = SystemClock.elapsedRealtime() - startedAtMs

    private fun emit(kind: String) {
        val snapshot = diagnostics.currentSnapshot() ?: return
        val out = JSONObject().put("kind", kind).put("elapsedMs", elapsed()).put("tick", tick)
            .put("phase", phase).put("playerState", state).put("errorCount", errors)
            .put("loadingCount", loading).put("sourceAttempt", sourceAttempt)
            .put("positionMs", view.player?.currentPosition ?: -1)
            .put("bufferedPositionMs", view.player?.bufferedPosition ?: -1)
            .put("totalBufferedDurationMs", view.player?.totalBufferedDuration ?: -1)
            .put("isPlaying", view.player?.isPlaying ?: false)
            .put("lastVideoPtsUs", snapshot.lastVideoPtsUs)
            .put("audioInputPtsUs", snapshot.audioInputPtsUs)
            .put("audioSinkPositionUs", snapshot.audioSinkPositionUs)
            .put("audioSinkPositionAvailability", snapshot.audioSinkPositionAvailability.name)
            .put("audioSampleRate", snapshot.audioSampleRate)
            .put("audioUnderrunCount", snapshot.audioUnderrunCount)
            .put("audioSinkDiscontinuityCount", snapshot.audioSinkDiscontinuityCount)
            .put("droppedVideoFrames", snapshot.droppedVideoFrames)
            .put("frameAdvanceCount", snapshot.frameAdvanceCount)
            .put("videoProgressCount", snapshot.videoProgressCount)
            .put("runtimeGeneration", snapshot.runtimeGeneration)
        val values = NativeDiagnosticMapper.fromSnapshot(snapshot)
        for ((container, fields) in listOf(
            "measurement" to MEASUREMENT_FIELDS,
            "m3uLive" to LIVE_FIELDS,
            "m3uLoad" to LOAD_FIELDS,
        )) {
            val data = getter(values, container) ?: continue
            for (field in fields) {
                when (val value = getter(data, field)) {
                    is Number -> if (value.toDouble().isFinite()) out.put(field, value)
                    is Boolean -> out.put(field, value)
                    is String -> if (value.matches(Regex("[A-Z0-9_]{1,64}"))) out.put(field, value)
                }
            }
        }
        Log.i(TAG, out.toString())
    }

    // The exact same debug source compiles against pre-change and new runtime snapshots.
    private fun getter(target: Any, property: String): Any? = runCatching {
        target.javaClass.getMethod("get" + property.replaceFirstChar(Char::uppercase)).invoke(target)
    }.getOrNull()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        engine?.release()
        engine = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KanTvM3uProbe"
        private const val SESSION = 1L
        private val MEASUREMENT_FIELDS = listOf(
            "nativeAudioClockStatus", "nativeAvGeneration", "nativeAudioStreamOffsetUs",
            "nativeAudioClockUs", "nativeAudioClockAgeMs", "nativePairedVideoPtsUs",
            "nativeAudioMinusVideoUs", "nativeAvPairSpanUs", "nativeVideoReleaseDelayUs",
            "avSyncAssessment",
        )
        private val LOAD_FIELDS = listOf(
            "nativeLoadActiveStatus",
            "nativeLoadActiveTrackType",
            "nativeLoadActiveStartElapsedMs",
            "nativeLoadActiveMediaStartMs",
            "nativeLoadActiveMediaEndMs",
            "nativeLoadActiveRetryCount",
            "nativeLoadLastOutcome",
            "nativeLoadLastTrackType",
            "nativeLoadLastStartElapsedMs",
            "nativeLoadLastEndElapsedMs",
            "nativeLoadLastMediaStartMs",
            "nativeLoadLastMediaEndMs",
            "nativeLoadLastRetryCount",
            "nativeLoadLastBytes",
            "nativeLoadLastHttpStatus",
            "nativeLoadPositionStatus",
            "nativeLoadWindowStartInPeriodMs",
            "nativeTransferActiveCount",
            "nativeTransferSequence",
            "nativeTransferOutcome",
            "nativeTransferTaskMatchStatus",
            "nativeTransferStartElapsedMs",
            "nativeTransferEndElapsedMs",
            "nativeTransferOpenDurationUs",
            "nativeTransferReadDurationUs",
            "nativeTransferCloseDurationUs",
            "nativeTransferReadCallCount",
            "nativeTransferBytes",
            "nativeTransferEofSeen",
            "nativeMediaRequestMatchStatus",
            "nativeMediaRequestCoverage",
            "nativeMediaRequestOutcome",
            "nativeMediaRequestOpenDurationUs",
            "nativeMediaRequestReadDurationUs",
            "nativeMediaRequestCloseDurationUs",
            "nativeMediaRequestReadCallCount",
            "nativeMediaRequestBytes",
            "nativeMediaRequestEofSeen",
        )
        private val LIVE_FIELDS = listOf(
            "nativeLiveContextAgeMs", "nativeLiveOffsetMs", "nativeLiveWindowDurationMs",
            "nativeLiveMediaSequence", "nativeLastMediaLoadDurationMs", "nativeLastMediaLoadBytes",
            "nativeLastLoadErrorCode",
        )
    }
}
