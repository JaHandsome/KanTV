package com.kantv.app.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

/** Debug-only loopback fixture: never routes catalogue playback through this experiment. */
open class GeckoAvDebugProbeActivity : Activity() {
    private var session: GeckoSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val mode = if (intent.getStringExtra("mode") == "probe") "probe" else "baseline"
        val fixture = if (intent.getBooleanExtra("offset", false)) "offset" else "zero"
        val productionBridge = intent.getBooleanExtra("productionBridge", false)
        val scenario = intent.getStringExtra("scenario").takeIf { it in listOf("normal", "late_reaction", "never_settle") }
            ?: if (intent.getBooleanExtra("invalidate", false)) "cycle" else "normal"
        val fixturePage = if (productionBridge) "production-fixture.html" else "fixture.html"
        val extensionDirectory = if (productionBridge) "kantv-av-production-probe" else "kantv-av-debug-probe"
        val extensionId = if (productionBridge) "kantv-av-production-probe@kantv.local" else "kantv-av-debug-probe@kantv.local"
        val runtime = GeckoRuntime.getDefault(this)
        val view = GeckoView(this)
        setContentView(view)
        val created = GeckoSession()
        session = created
        created.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                permission: GeckoSession.PermissionDelegate.ContentPermission,
            ): GeckoResult<Int> = GeckoResult.fromValue(
                if (permission.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                    permission.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                ) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY,
            )
        }
        created.open(runtime)
        view.setSession(created)
        runtime.webExtensionController.ensureBuiltIn(
            "resource://android/assets/$extensionDirectory/",
            extensionId,
        ).accept({ extension ->
            runOnUiThread {
                if (extension == null || session !== created) return@runOnUiThread
                created.webExtensionController.setMessageDelegate(extension, messageDelegate, "kantvAvProbe")
                Log.i(TAG, "extension_ready mode=$mode")
                onFixtureReady()
                created.loadUri("http://127.0.0.1:18765/$fixturePage?mode=$mode&fixture=$fixture&scenario=$scenario")
            }
        }, { error ->
            Log.e(TAG, "extension_failed type=${error?.javaClass?.simpleName}")
        })
    }

    protected open fun onFixtureReady() = Unit

    protected open fun onProbeDestroy() = Unit

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            app: String,
            message: Any,
            sender: WebExtension.MessageSender,
        ): GeckoResult<Any>? {
            if (sender.session !== session) return null
            val source = message as? JSONObject ?: return null
            if (source.optString("kind") == "diagnosticCapability") {
                return GeckoResult.fromValue<Any>(
                    JSONObject().put("diagnosticsEnabled", true).put("audioObservationEnabled", true),
                )
            }
            // Defense in depth: even extension errors cannot emit a URL or a debug dictionary.
            val output = JSONObject()
            for (key in NUMBER_FIELDS) {
                val value = source.opt(key)
                if (value is Number && value.toDouble().isFinite()) output.put(key, value)
            }
            for (key in BOOLEAN_FIELDS) {
                val value = source.opt(key)
                if (value is Boolean) output.put(key, value)
            }
            for (key in ENUM_FIELDS) {
                val value = source.optString(key)
                if (value.matches(Regex("[A-Za-z0-9_]{1,48}"))) output.put(key, value)
            }
            Log.i(TAG, output.toString())
            return null
        }
    }

    override fun onDestroy() {
        onProbeDestroy()
        session?.close()
        session = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KanTvAvProbe"
        private val NUMBER_FIELDS = listOf(
            "geckoStartupResourceScannedCount",
            "geckoStartupResourceFilteredCount",
            "geckoStartupResourceMaxStartMs",
            "geckoStartupResourceMaxEndMs",
            "geckoAudioQueryObservedWorkCount",
            "geckoAudioQueryObservedWorkTotalMs",
            "geckoAudioQueryObservedWorkMaxMs",
            "geckoAudioQueryObservedWorkMaxPhaseMs",
            "geckoAudioQueryObservedWorkMaxScanNodes",
            "geckoBridgeLastTickDurationMs",
            "geckoBridgeLastTickMaxPhaseDurationMs",
            "geckoBridgeLastTickScanNodes",
            "geckoAudioQueryTickDurationMs",
            "geckoAudioQueryTickTailDurationMs",
            "geckoAudioQueryTickMaxPhaseDurationMs",
            "geckoAudioQueryTickScanNodes",
            "geckoStartupObserverTickCount",
            "geckoStartupObserverTotalDurationMs",
            "geckoStartupObserverMaxTickDurationMs",
            "geckoStartupResourceCount",
            "geckoStartupResourceMaxDurationMs",
            "geckoStartupResourceMaxResponseWaitMs",
            "geckoStartupResourceMaxDownloadMs",
            "geckoStartupResourceDetailedCount",
            "elapsedMs", "currentTime", "duration", "readyState", "frames", "mediaTime",
            "callbackAgeMs", "waitingCount", "stalledCount", "debugCallMs", "sample",
            "clockUs", "stateMediaTimeUs", "startTimeUs", "lastGoodPositionUs", "outputRate",
            "tick", "time", "ready", "droppedFrames", "videoPtsUs", "videoCallbackAgeMs",
            "geckoAudioClockUs", "geckoAudioClockAgeMs", "geckoAudioClockCallDurationMs",
            "geckoPairedVideoPtsUs", "geckoAudioMinusVideoUs", "geckoAvPairSpanMs",
            "geckoNodeBoundMs",
            "geckoLoadedMetadataMs",
            "geckoPlayMs",
            "geckoPlayingMs",
            "geckoWaitingMs",
            "geckoCanPlayMs",
            "geckoFirstRvfcMs",
            "geckoFirstRvfcPtsUs",
            "geckoJsTickGapMs",
            "geckoAudioQuerySeq",
            "geckoAudioQueryEpoch",
            "geckoAudioQueryNode",
            "geckoAudioQueryStartMs",
            "geckoAudioSyncCallMs",
            "geckoAudioReactionElapsedMs",
            "geckoAudioTimeoutLateMs",
            "geckoDocumentNowMs",
            "geckoDocumentTimeOriginMs",
            "geckoStateReceivedEpochMs",
            "geckoAudioQueryRvfcCount",
            "geckoAudioQueryMaxRvfcGapMs",
        )
        private val BOOLEAN_FIELDS = listOf(
            "apiAvailable", "rvfcAvailable", "paused", "ended", "hasAudio", "hasVideo",
            "wrapperIsPlaying", "wrapperIsStarted", "wrapperAudioEnded", "audioIsPlaying",
            "audioIsStarted", "audioEnded", "hasErrored",
            "geckoAttachLate",
            "geckoAudioInFlight",
        )
        private val ENUM_FIELDS = listOf(
            "geckoAudioQueryObservedWorkMaxPhaseCode",
            "geckoStartupResourceMaxInitiatorCode",
            "geckoBridgeLastTickMaxPhaseCode",
            "geckoAudioQueryTickMaxPhaseCode",
            "geckoStartupResourceObservationStatus",
            "kind", "mode", "status", "errorType", "state", "phase", "geckoAudioClockStatus",
            "geckoAudioQueryState",
            "geckoAudioQueryDisposition",
        )
    }
}
