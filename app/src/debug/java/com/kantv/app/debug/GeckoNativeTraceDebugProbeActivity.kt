package com.kantv.app.debug

/** Separate debug process: its global profiler cannot touch the application's playback runtime. */
class GeckoNativeTraceDebugProbeActivity : GeckoAvDebugProbeActivity() {
    private var trace: GeckoNativeTraceCapture? = null

    override fun onFixtureReady() {
        if (!intent.getBooleanExtra("nativeTrace", false)) return
        // No URI, features, thread filters, marker payloads or duration are accepted from intents.
        trace = GeckoNativeTraceCapture(applicationContext).also { it.start() }
    }

    override fun onProbeDestroy() {
        trace?.stop()
        trace = null
    }
}
