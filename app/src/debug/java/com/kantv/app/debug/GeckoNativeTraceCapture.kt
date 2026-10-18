package com.kantv.app.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.ProfilerController
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed opt-in marker-only debug capture. Raw profiles remain private and never enter upload/logs. */
internal class GeckoNativeTraceCapture(
    context: Context,
    target: GeckoNativeTraceTarget = GeckoNativeTraceTarget.SYNTHETIC,
) {
    private val file = File(context.cacheDir, target.fileName)
    private val handler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private var attempted = false
    private var started = false
    private var stopping = false
    private var ownsCapture = false
    private var startedAt = 0L
    private var startCallMs = -1L
    private var stopCallMs = -1L
    private val deadline = Runnable { stop() }

    fun start() {
        if (attempted) return
        attempted = true
        startedAt = SystemClock.elapsedRealtime()
        if (!ACTIVE_CAPTURE.compareAndSet(false, true)) {
            emit("capture_busy")
            writer.shutdown()
            return
        }
        ownsCapture = true
        try {
            if (file.exists() && !file.delete()) {
                emit("cleanup_failed")
                finish()
                return
            }
            // Gecko 146 accepts exactly these features, replacing its defaults. No Java sampler.
            // JNI uses a fixed large entry capacity; this short window is NOT a memory cap.
            val calledAt = SystemClock.elapsedRealtime()
            ProfilerController.startProfiler(
                arrayOf("GeckoMain", "MediaDecoderStateMachine", "MediaSupervisor", "MediaPDecoder"),
                arrayOf("nostacksampling", "nomarkerstacks"),
            )
            startCallMs = SystemClock.elapsedRealtime() - calledAt
            started = true
            handler.postDelayed(deadline, WINDOW_MS)
            emit("start_returned")
        } catch (_: Throwable) {
            // JNI might have started before a later failure: make one best-effort cleanup call.
            started = true
            emit("start_failed")
            stop()
        }
    }

    fun stop() {
        // A rejected instance must never stop another instance's process-global profiler.
        if (!ownsCapture || !started || stopping) return
        stopping = true
        handler.removeCallbacks(deadline)
        emit("stop_requested")
        try {
            val calledAt = SystemClock.elapsedRealtime()
            val result = ProfilerController.stopProfiler()
            stopCallMs = SystemClock.elapsedRealtime() - calledAt
            emit("stop_returned")
            result.accept({ profile ->
                if (profile == null || profile.isEmpty() || profile.size > MAX_SAVED_BYTES) {
                    emit("profile_discarded", profile?.size ?: 0)
                    finish()
                } else {
                    writer.execute {
                        try {
                            FileOutputStream(file).use { output ->
                                Os.chmod(file.absolutePath, 0b110000000)
                                output.write(profile)
                            }
                            emit("profile_saved", profile.size)
                        } catch (_: Throwable) {
                            file.delete()
                            emit("save_failed")
                        } finally {
                            finish()
                        }
                    }
                }
            }, {
                emit("stop_failed")
                finish(releaseClaim = false)
            })
        } catch (_: Throwable) {
            emit("stop_failed")
            finish(releaseClaim = false)
        }
    }

    private fun finish(releaseClaim: Boolean = true) {
        // Failed stop leaves native state unknown: block another capture until process cleanup.
        if (releaseClaim && ownsCapture) {
            ownsCapture = false
            ACTIVE_CAPTURE.set(false)
        }
        writer.shutdown()
    }

    private fun emit(state: String, bytes: Int = 0) {
        // Fixed enum + numbers only; exceptions/profile contents never enter logcat.
        Log.i("KanTvNativeTrace", JSONObject()
            .put("state", state)
            .put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
            .put("compressedBytes", bytes)
            .put("windowMs", WINDOW_MS)
            .put("startCallMs", startCallMs)
            .put("stopCallMs", stopCallMs)
            .toString())
    }

    companion object {
        private val ACTIVE_CAPTURE = AtomicBoolean()
        private const val WINDOW_MS = 8_000L
        // Disk export guard only. The public API's native buffer and returned byte[] are not bounded here.
        private const val MAX_SAVED_BYTES = 16 * 1024 * 1024
    }
}

internal enum class GeckoNativeTraceTarget(val fileName: String) {
    SYNTHETIC("native-trace-profile.json.gz"),
    LIVE_STARTUP("native-trace-live-startup.json.gz"),
    LIVE_STABLE("native-trace-live-stable.json.gz"),
}
