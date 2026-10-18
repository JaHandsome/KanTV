package com.kantv.app.debug

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kantv.app.GeckoMirrorLiveActivity
import com.kantv.app.data.StableChannelId
import com.kantv.app.playback.OfficialWebChannelCatalog
import com.kantv.app.playback.PlaybackRouteIntent
import org.json.JSONObject

/** Fixed catalogue CCTV5+ entry; the inherited activity owns every playback decision. */
class GeckoNativeTraceLiveStartupActivity : GeckoMirrorLiveActivity() {
    private var traceEnabled = false
    private var traceTarget: GeckoNativeTraceTarget? = null
    private var trace: GeckoNativeTraceCapture? = null
    private val handler = Handler(Looper.getMainLooper())
    private val createdAt = SystemClock.elapsedRealtime()
    private var initialNavigationHandled = false
    private var stableScheduled = false
    private var lifecycleEnded = false
    private val stableStart = Runnable { startCapture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        val requestedPhase = if (intent.hasExtra("nativeTracePhase")) {
            intent.getStringExtra("nativeTracePhase")
        } else {
            "startup"
        }
        traceTarget = when (requestedPhase) {
            "startup" -> GeckoNativeTraceTarget.LIVE_STARTUP
            "stable" -> GeckoNativeTraceTarget.LIVE_STABLE
            else -> null
        }
        traceEnabled = intent.getBooleanExtra("nativeTrace", false) && traceTarget != null
        emit(if (traceTarget == null) TraceState.PHASE_REJECTED else TraceState.PHASE_SELECTED)
        val page = requireNotNull(OfficialWebChannelCatalog.pageByTvgId("official-ysp-cctv5plus"))
        val localRouteId = SystemClock.elapsedRealtime().coerceAtLeast(1L)
        // Rebuild the existing official-player intent; accept no external page/stream URL or tuning.
        intent = Intent(this, GeckoNativeTraceLiveStartupActivity::class.java)
            .putExtra("pageUrl", page.url)
            .putExtra("channelId", StableChannelId.create(page.tvgId, null, ""))
            .putExtra("channelTvgId", page.tvgId)
            .putExtra(PlaybackRouteIntent.EXTRA_SESSION_ID, localRouteId)
            .putExtra(PlaybackRouteIntent.EXTRA_ROUTE_GENERATION, localRouteId)
        super.onCreate(savedInstanceState)
    }

    override fun onBeforeInitialOfficialNavigation() {
        if (initialNavigationHandled) return
        initialNavigationHandled = true
        if (!traceEnabled || lifecycleEnded) return
        if (traceTarget == GeckoNativeTraceTarget.LIVE_STABLE) {
            stableScheduled = handler.postDelayed(stableStart, STABLE_DELAY_MS)
            emit(if (stableScheduled) TraceState.STABLE_SCHEDULED else TraceState.SCHEDULE_FAILED)
        } else {
            startCapture()
        }
    }

    private fun startCapture() {
        stableScheduled = false
        if (!traceEnabled || lifecycleEnded || trace != null) return
        val target = traceTarget ?: return
        emit(TraceState.CAPTURE_REQUESTED)
        trace = GeckoNativeTraceCapture(applicationContext, target)
            .also { it.start() }
    }

    override fun onStop() {
        endCaptureLifecycle()
        super.onStop()
    }

    override fun onDestroy() {
        endCaptureLifecycle()
        super.onDestroy()
    }

    private fun endCaptureLifecycle() {
        lifecycleEnded = true
        handler.removeCallbacks(stableStart)
        if (stableScheduled) {
            stableScheduled = false
            emit(TraceState.STABLE_CANCELLED)
        }
        trace?.stop()
    }

    private fun emit(state: TraceState) {
        // Fixed enum + numbers only; no page, route identifiers, URLs, or headers.
        Log.i("KanTvNativeTrace", JSONObject()
            .put("state", state.name.lowercase())
            .put("elapsedMs", SystemClock.elapsedRealtime() - createdAt)
            .put("phase", when (traceTarget) {
                GeckoNativeTraceTarget.LIVE_STARTUP -> 0
                GeckoNativeTraceTarget.LIVE_STABLE -> 1
                else -> -1
            })
            .put("enabled", if (traceEnabled) 1 else 0)
            .put("delayMs", if (traceTarget == GeckoNativeTraceTarget.LIVE_STABLE) STABLE_DELAY_MS else 0L)
            .toString())
    }

    private enum class TraceState {
        PHASE_SELECTED,
        PHASE_REJECTED,
        STABLE_SCHEDULED,
        SCHEDULE_FAILED,
        CAPTURE_REQUESTED,
        STABLE_CANCELLED,
    }

    companion object {
        private const val STABLE_DELAY_MS = 240_000L
    }
}
