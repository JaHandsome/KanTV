package com.kantv.app.diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kantv.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class OfficialWebDiagnosticsRecorder(
    context: Context,
    private val onReportReady: () -> Unit = {},
) : Closeable {
    private val enabled = BuildConfig.KANTV_DIAGNOSTICS
    private val appContext = context.applicationContext
    private val store = DiagnosticReportStore(
        File(appContext.filesDir, DIAGNOSTICS_DIRECTORY),
        onFailure = { code -> DiagnosticLedgerHealth.recordError(appContext, code) },
    )
    private val executor = if (enabled) ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "kantv-playback-diagnostics").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    } else null
    private val mainHandler = if (enabled) Handler(Looper.getMainLooper()) else null
    private val closed = AtomicBoolean(false)
    private val detector = StallDetector()
    private var current: SessionLog? = null
    private val writerTaskSequence = AtomicLong()
    private val writerTiming = DiagnosticWriterTimingPolicy()
    private val startupDispatcher = DiagnosticStartupDispatcher(
        startExecutor = { task -> executor?.execute(task) },
        recoveryExecutor = { task -> RECOVERY_EXECUTOR.execute(task) },
    )

    init {
        if (enabled) {
            if (ProcessDiagnosticRecoveryGate.claimRecovery()) startupDispatcher.dispatchRecovery { guarded("RECOVERY_TASK_FAILED") {
                val recovered = store.recoverActiveReports { id -> !ProcessDiagnosticRecoveryGate.isActive(id) }
                if (recovered.isNotEmpty()) {
                    updateHealth()
                    notifyReportReady()
                } else {
                    DiagnosticLedgerHealth.recordPendingCount(appContext, store.pendingReports().size)
                }
            } }
            var scheduledAtMs = SystemClock.elapsedRealtime() + CHECKPOINT_INTERVAL_MS
            executor?.scheduleAtFixedRate({
                val enqueuedAtMs = scheduledAtMs
                scheduledAtMs += CHECKPOINT_INTERVAL_MS
                timedAction(DiagnosticWriterTaskKind.CHECKPOINT, "CHECKPOINT_TASK_FAILED", enqueuedAtMs) {
                    val session = current ?: return@timedAction
                    if (session.producerWatermark.hasUncheckpointedEntries &&
                        DiagnosticJournalPolicy.checkpointDue(session.lastCheckpointElapsedMs, SystemClock.elapsedRealtime())) {
                        checkpointSession(session)
                    }
                }.invoke()
            }, CHECKPOINT_INTERVAL_MS, CHECKPOINT_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Forces construction/recovery during app startup without opening a playback session. */
    fun prepare() = Unit

    fun start(
        channelId: String,
        provider: DiagnosticProvider,
        sessionId: Long,
        elapsedMs: Long = SystemClock.elapsedRealtime(),
    ) {
        require(CHANNEL_ID.matches(channelId)) { "channelId must be a fixed catalogue identifier" }
        require(sessionId >= 0 && elapsedMs >= 0)
        if (!enabled || closed.get()) return
        val reportId = UUID.randomUUID().toString()
        ProcessDiagnosticRecoveryGate.register(reportId)
        val accepted = submitStart {
            current?.let { previous ->
                appendEntry(previous, DiagnosticLogEntry(DiagnosticEntryKind.SWITCH, elapsedMs, previous.lastValues))
                detector.event(previous.sessionId, DiagnosticEntryKind.SWITCH, elapsedMs)
                finalizeSession(previous, DiagnosticReportReason.CHANNEL_SWITCH, elapsedMs)
            }
            detector.start(sessionId, elapsedMs)
            current = SessionLog(reportId, channelId, provider, sessionId,
                fragmentStartedElapsedMs = elapsedMs, lastCheckpointElapsedMs = elapsedMs).also {
                appendEntry(it, DiagnosticLogEntry(DiagnosticEntryKind.START, elapsedMs, DiagnosticPlaybackValues.EMPTY))
                // Recovery runs on a separate executor and decoder inventory is deferred.
                if (DiagnosticJournalPolicy.flushImmediately(DiagnosticEntryKind.START)) persistActive(it, elapsedMs, fullIdentity = false)
            }
        }
        if (!accepted) ProcessDiagnosticRecoveryGate.unregister(reportId)
    }

    fun sample(sample: OfficialWebDiagnosticSample) {
        submit(DiagnosticWriterTaskKind.SAMPLE) {
            val session = current?.takeIf { it.sessionId == sample.sessionId } ?: return@submit
            val previous = session.lastValues
            appendEntry(session, DiagnosticLogEntry(DiagnosticEntryKind.SAMPLE, sample.elapsedMs, sample.values))
            var critical = false
            val observedRoute = sample.values.kernelCode > 0 || sample.values.deliveryCode > 0 ||
                sample.values.videoDecoderName != "unknown" || sample.values.audioDecoderName != "unknown"
            if (observedRoute && (!session.hasRoute || routeSignature(previous) != routeSignature(sample.values))) {
                appendEntry(session, DiagnosticLogEntry(DiagnosticEntryKind.ROUTE_CHANGED, sample.elapsedMs, sample.values))
                critical = true
            }
            if (sample.values.selectedAudioTrackId >= 0 &&
                (!session.hasAudioTrack || previous.selectedAudioTrackId != sample.values.selectedAudioTrackId)) {
                appendEntry(session, DiagnosticLogEntry(DiagnosticEntryKind.AUDIO_TRACK_CHANGED, sample.elapsedMs, sample.values))
                critical = true
            }
            if (sample.values.pictureModeCode > 0 &&
                (!session.hasPictureMode || previous.pictureModeCode != sample.values.pictureModeCode)) {
                appendEntry(session, DiagnosticLogEntry(DiagnosticEntryKind.DISPLAY_MODE_CHANGED, sample.elapsedMs, sample.values))
                critical = true
            }
            session.hasRoute = true
            session.hasAudioTrack = true
            session.hasPictureMode = true
            val reason = detector.sample(sample)
            if (reason != null) {
                appendEntry(session, DiagnosticLogEntry(DiagnosticEntryKind.PLAYBACK_FAULT, sample.elapsedMs, sample.values))
                session.faultActive = true
                persistActive(session, sample.elapsedMs)
                writeIncident(session, reason)
                critical = false
            } else if (session.faultActive && madeProgress(
                    previous,
                    sample.values,
                    userPaused = sample.userPaused,
                    menuVisible = sample.menuVisible,
                    appInBackground = sample.appInBackground,
                    observationEnabled = sample.observationEnabled,
                )) {
                appendEntry(session, DiagnosticLogEntry(DiagnosticEntryKind.RECOVERY_SUCCEEDED, sample.elapsedMs, sample.values))
                session.faultActive = false
                critical = true
            }
            if (critical || DiagnosticJournalPolicy.checkpointDue(session.lastCheckpointElapsedMs, sample.elapsedMs)) {
                if (DiagnosticJournalPolicy.checkpointDue(session.lastCheckpointElapsedMs, sample.elapsedMs)) {
                    checkpointSession(session)
                } else persistActive(session, sample.elapsedMs)
            }
        }
    }

    fun event(
        sessionId: Long,
        kind: DiagnosticEntryKind,
        elapsedMs: Long = SystemClock.elapsedRealtime(),
        values: DiagnosticPlaybackValues = DiagnosticPlaybackValues.EMPTY,
    ) {
        require(kind != DiagnosticEntryKind.START && kind != DiagnosticEntryKind.SAMPLE)
        require(sessionId >= 0 && elapsedMs >= 0)
        submit(DiagnosticWriterTaskKind.EVENT) {
            val session = current?.takeIf { it.sessionId == sessionId } ?: return@submit
            val effectiveValues = if (values == DiagnosticPlaybackValues.EMPTY) session.lastValues else values
            appendEntry(session, DiagnosticLogEntry(kind, elapsedMs, effectiveValues))
            detector.event(sessionId, kind, elapsedMs)
            when (kind) {
                DiagnosticEntryKind.STOP -> {
                    finalizeSession(session, DiagnosticReportReason.STOPPED, elapsedMs)
                    current = null
                }
                DiagnosticEntryKind.SWITCH -> {
                    finalizeSession(session, DiagnosticReportReason.CHANNEL_SWITCH, elapsedMs)
                    current = null
                }
                else -> if (DiagnosticJournalPolicy.flushImmediately(kind)) persistActive(session, elapsedMs)
            }
        }
    }

    private fun writeIncident(session: SessionLog, reason: DiagnosticReportReason) {
        if (!session.reportedReasons.add(reason)) return
        val id = UUID.randomUUID().toString()
        val bytes = buildReport(session, id, reason.name, "INCIDENT", System.currentTimeMillis(),
            session.producerWatermark.elapsedMs)
            .toString().toByteArray(Charsets.UTF_8)
        if (store.writeReport(id, reason, bytes) != null) {
            updateHealth()
            notifyReportReady()
        }
    }

    private fun persistActive(session: SessionLog, elapsedMs: Long, fullIdentity: Boolean = true) {
        val bytes = buildReport(session, session.reportId, "ACTIVE_SESSION", "ACTIVE", -1, elapsedMs, fullIdentity)
            .toString().toByteArray(Charsets.UTF_8)
        if (store.writeActive(session.reportId, bytes) != null) {
            session.lastPersistedElapsedMs = elapsedMs
            DiagnosticLedgerHealth.recordPersisted(appContext, System.currentTimeMillis(), store.pendingReports().size)
        }
    }

    private fun finalizeSession(session: SessionLog, reason: DiagnosticReportReason, elapsedMs: Long) {
        val bytes = buildReport(session, session.reportId, reason.name, "COMPLETE", System.currentTimeMillis(), elapsedMs)
            .toString().toByteArray(Charsets.UTF_8)
        if (store.finalizeActive(session.reportId, reason, bytes) != null) {
            session.lastPersistedElapsedMs = elapsedMs
            updateHealth()
            notifyReportReady()
        }
        ProcessDiagnosticRecoveryGate.unregister(session.reportId)
    }

    private fun appendEntry(session: SessionLog, entry: DiagnosticLogEntry) {
        // START remains a minimal durable record; decoder inventory is deferred to this executor.
        val budget = if (session.entries.isEmpty()) DiagnosticJournalPolicy.MAX_SAMPLE_BYTES else {
            val envelopeBytes = buildReport(session, session.reportId, "CHECKPOINT", "CHECKPOINT",
                System.currentTimeMillis(), entry.elapsedMs, includeSamples = false)
                .toString().toByteArray(Charsets.UTF_8).size
            DiagnosticJournalPolicy.sampleBudgetForEnvelope(envelopeBytes)
        }
        val checkpoint = DiagnosticLogEntry(DiagnosticEntryKind.CHECKPOINT,
            session.producerWatermark.elapsedMs, session.lastValues)
        if (DiagnosticJournalPolicy.checkpointBeforeAppend(
                session.entries.size, session.entryBytes, entry.serializedByteCount, checkpoint.serializedByteCount,
                budget,
            )) {
            // If even an empty continuation cannot fit, retain the previous fragment unchanged.
            if (DiagnosticJournalPolicy.checkpointBeforeAppend(
                    1, checkpoint.serializedByteCount + 1L, entry.serializedByteCount,
                    checkpoint.serializedByteCount, budget,
                )) throw FragmentCheckpointFailed()
            // A failed durable rotation retains the existing fragment. Do not evict its records.
            if (!checkpointSession(session)) throw FragmentCheckpointFailed()
        }
        session.add(entry)
        session.producerWatermark.accept(entry.elapsedMs)
    }

    private fun checkpointSession(session: SessionLog): Boolean {
        // A delayed writer must seal at its consumed producer watermark, never its own future clock.
        if (!session.producerWatermark.hasUncheckpointedEntries) return true
        val elapsedMs = session.producerWatermark.elapsedMs
        session.add(DiagnosticLogEntry(DiagnosticEntryKind.CHECKPOINT, elapsedMs, session.lastValues))
        val oldId = session.reportId
        val bytes = buildReport(session, oldId, DiagnosticReportReason.CHECKPOINT.name, "CHECKPOINT",
            System.currentTimeMillis(), elapsedMs).toString().toByteArray(Charsets.UTF_8)
        if (store.finalizeActive(oldId, DiagnosticReportReason.CHECKPOINT, bytes) == null) {
            session.entries.removeLast().also { session.entryBytes -= it.serializedByteCount + 1L }
            return false
        }
        ProcessDiagnosticRecoveryGate.unregister(oldId)
        updateHealth()
        notifyReportReady()
        session.fragmentSequence++
        session.fragmentStartedElapsedMs = elapsedMs
        session.lastCheckpointElapsedMs = elapsedMs
        session.producerWatermark.checkpointed()
        session.reportId = UUID.randomUUID().toString()
        ProcessDiagnosticRecoveryGate.register(session.reportId)
        session.entries.clear()
        session.entryBytes = 0
        session.add(DiagnosticLogEntry(DiagnosticEntryKind.CHECKPOINT, elapsedMs, session.lastValues))
        persistActive(session, elapsedMs)
        return true
    }

    private fun buildReport(
        session: SessionLog,
        reportId: String,
        reason: String,
        lifecycle: String,
        closedAtMs: Long,
        fragmentEndedElapsedMs: Long,
        fullIdentity: Boolean = true,
        includeSamples: Boolean = true,
    ): JSONObject {
        val capturedAtMs = System.currentTimeMillis()
        val device = if (fullIdentity) DeviceDiagnosticIdentity.read(appContext) else DeviceDiagnosticIdentity.readMinimal(appContext)
        val runtimeValues = session.lastValues
        return JSONObject()
            // Version 9 preserves the earlier observation fields and adds Gecko presentation facts.
            .put("schemaVersion", DIAGNOSTIC_SCHEMA_VERSION)
            .put("reportId", reportId)
            .put("reason", reason)
            .put("capturedAtMs", capturedAtMs)
            .put("appVersionName", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE.toLong())
            .put("deviceManufacturer", diagnosticOpaqueDeviceValue(device.manufacturer))
            .put("deviceModel", diagnosticOpaqueDeviceValue(device.model))
            .put("deviceApiLevel", android.os.Build.VERSION.SDK_INT)
            .put("deviceInstallId", device.installId)
            // Never upload the editable local label; retain the legacy field with a stable opaque value.
            .put("deviceLabel", "install-${device.installId.take(8)}")
            .put("deviceCpuAbi", device.cpuAbi)
            .put("deviceTotalRamMb", device.totalRamMb)
            .put("displayWidth", device.displayWidth)
            .put("displayHeight", device.displayHeight)
            .put("channelId", session.channelId)
            .put("provider", session.provider.name)
            .put("sessionId", session.sessionId)
            .put("lifecycle", lifecycle)
            .put("closedAtMs", closedAtMs)
            .put("fragmentSequence", session.fragmentSequence)
            .put("fragmentStartedElapsedMs", session.fragmentStartedElapsedMs)
            .put("fragmentEndedElapsedMs", maxOf(fragmentEndedElapsedMs, session.producerWatermark.elapsedMs))
            .put("app", JSONObject()
                .put("name", "看电视")
                .put("applicationId", BuildConfig.APPLICATION_ID)
                .put("buildType", BuildConfig.BUILD_TYPE)
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE.toLong())
                .put("gitRevision", BuildConfig.GIT_REVISION)
                .put("buildTimeEpochMs", BuildConfig.BUILD_TIME_EPOCH_MS)
                .put("firstInstallTimeMs", device.firstInstallTimeMs)
                .put("lastUpdateTimeMs", device.lastUpdateTimeMs))
            .put("device", JSONObject()
                .put("manufacturer", diagnosticOpaqueDeviceValue(device.manufacturer))
                .put("model", diagnosticOpaqueDeviceValue(device.model))
                .put("product", diagnosticOpaqueDeviceValue(device.product))
                .put("board", diagnosticOpaqueDeviceValue(device.board))
                .put("hardware", diagnosticOpaqueDeviceValue(device.hardware))
                .put("socManufacturer", diagnosticOpaqueDeviceValue(device.socManufacturer))
                .put("socModel", diagnosticOpaqueDeviceValue(device.socModel))
                .put("androidRelease", DeviceDiagnosticIdentity.technical(device.androidRelease))
                .put("apiLevel", android.os.Build.VERSION.SDK_INT)
                .put("securityPatch", DeviceDiagnosticIdentity.technical(device.securityPatch))
                .put("abis", JSONArray(device.cpuAbis.map(DeviceDiagnosticIdentity::technical)))
                .put("cpuCoreCount", device.cpuCoreCount).put("totalRamMb", device.totalRamMb)
                .put("availableRamMb", device.availableRamMb).put("lowRamDevice", device.lowRamDevice ?: JSONObject.NULL)
                .put("lowMemory", device.lowMemory ?: JSONObject.NULL).put("thermalStatus", device.thermalStatus))
            .put("display", JSONObject()
                .put("width", device.displayWidth).put("height", device.displayHeight)
                .put("refreshRate", device.displayRefreshRate).put("modeId", device.displayModeId)
                .put("availableModes", JSONArray(device.displayModes)).put("hdrTypes", JSONArray(device.hdrTypes)))
            .put("runtime", JSONObject()
                .put("networkTransports", JSONArray(device.networkTransports))
                .put("networkMetered", device.networkMetered ?: JSONObject.NULL)
                .put("audioOutputRoute", DeviceDiagnosticIdentity.technical(runtimeValues.audioOutputRoute))
                .put("audioOutputMode", DeviceDiagnosticIdentity.technical(runtimeValues.audioOutputMode))
                .put("audioEncodingMode", if (runtimeValues.audioOutputEncoding >= 0) {
                    "encoding_${runtimeValues.audioOutputEncoding}"
                } else "unknown")
                .put("audioTunneling", runtimeValues.audioOutputTunneling ?: JSONObject.NULL)
                .put("audioTrackPositionFrames", -1)
                .put("audioTimestampAvailable", -1)
                .put("audioOutputSampleRate", runtimeValues.audioOutputSampleRate)
                .put("audioOutputChannelCount", -1))
            .put("libraries", JSONObject()
                .put("media3", BuildConfig.MEDIA3_VERSION)
                .put("ffmpegExtension", BuildConfig.FFMPEG_EXTENSION_VERSION)
                .put("webView", device.webViewVersion))
            .put("decoderCapabilities", JSONArray().also { array ->
                device.decoderCapabilities.forEach { decoder -> array.put(JSONObject()
                    .put("name", decoder.name).put("types", JSONArray(decoder.types))
                    .put("hardwareAccelerated", decoder.hardwareAccelerated ?: JSONObject.NULL)
                    .put("softwareOnly", decoder.softwareOnly ?: JSONObject.NULL)) }
            })
            .put("samples", JSONArray().also { array ->
                if (includeSamples) session.entries.forEach { array.put(it.toJson()) }
            })
    }

    private fun updateHealth() {
        DiagnosticLedgerHealth.recordPersisted(appContext, System.currentTimeMillis(), store.pendingReports().size)
    }

    private fun notifyReportReady() = mainHandler?.post { onReportReady() } ?: Unit

    private fun submit(kind: DiagnosticWriterTaskKind, action: () -> Unit) {
        if (!enabled || closed.get()) return
        try {
            executor?.execute(timedAction(kind, "RECORDER_TASK_FAILED", action = action))
        } catch (_: RejectedExecutionException) {
            DiagnosticLedgerHealth.recordError(appContext, "RECORDER_CLOSED")
        }
    }

    private fun submitStart(action: () -> Unit): Boolean {
        if (!enabled || closed.get()) return false
        return try {
            startupDispatcher.dispatchStart(timedAction(DiagnosticWriterTaskKind.START, "START_TASK_FAILED", action = action))
            true
        } catch (_: RejectedExecutionException) {
            DiagnosticLedgerHealth.recordError(appContext, "START_REJECTED")
            false
        }
    }

    private fun timedAction(
        kind: DiagnosticWriterTaskKind,
        failureCode: String,
        enqueuedAtMs: Long = SystemClock.elapsedRealtime(),
        action: () -> Unit,
    ): () -> Unit {
        val sequence = writerTaskSequence.incrementAndGet()
        return {
            val startedAtMs = SystemClock.elapsedRealtime()
            val queueDepth = executor?.queue?.size ?: 0
            try {
                guarded(failureCode, action)
            } finally {
                val finishedAtMs = SystemClock.elapsedRealtime()
                val queueMs = (startedAtMs - enqueuedAtMs).coerceAtLeast(0L)
                val executeMs = (finishedAtMs - startedAtMs).coerceAtLeast(0L)
                if (writerTiming.shouldLog(finishedAtMs, queueMs, executeMs)) {
                    val waitLabel = if (kind == DiagnosticWriterTaskKind.CHECKPOINT) "dueMs" else "enqueuedMs"
                    val delayLabel = if (kind == DiagnosticWriterTaskKind.CHECKPOINT) "scheduleDelayMs" else "queueMs"
                    android.util.Log.i("KanTvDiag", "DIAGNOSTIC_WRITER task=${kind.name} seq=$sequence " +
                        "$waitLabel=$enqueuedAtMs startedMs=$startedAtMs finishedMs=$finishedAtMs " +
                        "$delayLabel=$queueMs executeMs=$executeMs depth=$queueDepth")
                }
            }
        }
    }

    private fun guarded(code: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            DiagnosticLedgerHealth.recordError(appContext,
                if (error is FragmentCheckpointFailed) "FRAGMENT_CHECKPOINT_FAILED" else code,
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val service = executor ?: return
        try {
            service.execute(timedAction(DiagnosticWriterTaskKind.CLOSE, "CLOSE_FLUSH_FAILED") {
                current?.let { persistActive(it, it.producerWatermark.elapsedMs) }
            })
        } catch (_: RejectedExecutionException) {
            DiagnosticLedgerHealth.recordError(appContext, "CLOSE_REJECTED")
        }
        service.shutdown()
        val waitMs = if (Looper.myLooper() == Looper.getMainLooper()) MAIN_CLOSE_WAIT_MS else BACKGROUND_CLOSE_WAIT_MS
        try {
            if (!service.awaitTermination(waitMs, TimeUnit.MILLISECONDS)) {
                DiagnosticLedgerHealth.recordError(appContext, "CLOSE_FLUSH_TIMEOUT")
                service.shutdownNow()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            DiagnosticLedgerHealth.recordError(appContext, "CLOSE_INTERRUPTED")
            service.shutdownNow()
        }
    }

    private enum class DiagnosticWriterTaskKind { START, SAMPLE, EVENT, CHECKPOINT, CLOSE }

    private class FragmentCheckpointFailed : IllegalStateException()

    private data class SessionLog(
        var reportId: String,
        val channelId: String,
        val provider: DiagnosticProvider,
        val sessionId: Long,
        var fragmentSequence: Int = 0,
        var fragmentStartedElapsedMs: Long,
        var lastCheckpointElapsedMs: Long,
        val producerWatermark: DiagnosticProducerWatermark = DiagnosticProducerWatermark(fragmentStartedElapsedMs),
        val entries: ArrayDeque<DiagnosticLogEntry> = ArrayDeque(),
        val reportedReasons: MutableSet<DiagnosticReportReason> = mutableSetOf(),
        var lastPersistedElapsedMs: Long = -1,
        var lastValues: DiagnosticPlaybackValues = DiagnosticPlaybackValues.EMPTY,
        var entryBytes: Long = 0,
        var hasRoute: Boolean = false,
        var hasAudioTrack: Boolean = false,
        var hasPictureMode: Boolean = false,
        var faultActive: Boolean = false,
    ) {
        fun add(entry: DiagnosticLogEntry) {
            check(entries.size < MAX_ENTRIES) { "FRAGMENT_ENTRY_LIMIT" }
            entries.addLast(entry)
            entryBytes += entry.serializedByteCount + 1L
            lastValues = entry.values
        }
    }

    companion object {
        private const val DIAGNOSTICS_DIRECTORY = "diagnostics"
        // Reserve one slot so crash recovery can append its minimal seal event and remain <=120.
        private const val MAX_ENTRIES = DiagnosticJournalPolicy.MAX_FRAGMENT_ENTRIES
        internal const val CHECKPOINT_INTERVAL_MS = DiagnosticJournalPolicy.CHECKPOINT_INTERVAL_MS
        private const val MAIN_CLOSE_WAIT_MS = 200L
        private const val BACKGROUND_CLOSE_WAIT_MS = 1_500L
        private val CHANNEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")
        private val RECOVERY_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "kantv-diagnostics-recovery").apply { isDaemon = true }
        }

        @JvmStatic fun reportsDir(context: Context): File =
            File(context.applicationContext.filesDir, "$DIAGNOSTICS_DIRECTORY/reports")
        @JvmStatic fun pendingReports(context: Context): List<File> =
            DiagnosticReportStore(File(context.applicationContext.filesDir, DIAGNOSTICS_DIRECTORY)).pendingReports()
        /** Latest bounded JSON journals only; suitable for explicit user-selected local export. */
        @JvmStatic fun exportableReports(context: Context): List<File> =
            DiagnosticReportStore(File(context.applicationContext.filesDir, DIAGNOSTICS_DIRECTORY))
                .exportableReports()
        @JvmStatic fun markUploaded(context: Context, file: File): Boolean =
            DiagnosticReportStore(File(context.applicationContext.filesDir, DIAGNOSTICS_DIRECTORY)).markUploaded(file)
    }
}
