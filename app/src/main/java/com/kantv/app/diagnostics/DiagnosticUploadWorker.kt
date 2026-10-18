package com.kantv.app.diagnostics

import android.content.Context
import androidx.work.*
import com.kantv.app.BuildConfig
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

object DiagnosticUploadConfig {
    private const val PREFS = "diagnostic_upload"
    private const val BUNDLED_DEFAULTS_APPLIED = "bundled_defaults_applied"
    fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun validToken(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{32,128}"))

    fun initializeBundledDefaults(context: Context) {
        if (!BuildConfig.KANTV_DIAGNOSTICS ||
            !validEndpoint(BuildConfig.DIAGNOSTIC_DEFAULT_ENDPOINT) ||
            !validToken(BuildConfig.DIAGNOSTIC_DEFAULT_TOKEN)) return
        val prefs = preferences(context)
        if (!shouldRefreshBundledDefaults(
                defaultsApplied = prefs.getBoolean(BUNDLED_DEFAULTS_APPLIED, false),
                enabled = prefs.getBoolean("enabled", false),
                currentEndpoint = prefs.getString("endpoint", "").orEmpty(),
                currentToken = prefs.getString("token", "").orEmpty(),
            )) return
        // v21 had no bundled upload pair. The first paired ordinary-package upgrade must replace
        // that unconfigured state so retained reports are delivered without ADB or a file picker.
        prefs.edit()
            .putString("endpoint", BuildConfig.DIAGNOSTIC_DEFAULT_ENDPOINT)
            .putString("token", BuildConfig.DIAGNOSTIC_DEFAULT_TOKEN)
            .putBoolean("enabled", true)
            .putBoolean(BUNDLED_DEFAULTS_APPLIED, true)
            .putString("status", "已配对 Home NAS，等待故障报告")
            .apply()
    }

    internal fun shouldRefreshBundledDefaults(
        defaultsApplied: Boolean,
        enabled: Boolean,
        currentEndpoint: String,
        currentToken: String,
    ): Boolean = !defaultsApplied || !enabled || !validEndpoint(currentEndpoint) || !validToken(currentToken)

    /** Only a literal LAN address. No redirects, DNS aliases, credentials, or query strings. */
    fun validEndpoint(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("http", "https") || uri.userInfo != null || uri.query != null || uri.fragment != null ||
            uri.path != "/v1/reports" || uri.port !in -1..65535 || uri.port == 0) return false
        val octets = uri.host.orEmpty().split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        // Match the canonical dotted-decimal spelling so URL parsers cannot reinterpret it.
        if (octets.joinToString(".") != uri.host) return false
        return octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31)
    }

    fun schedule(context: Context) {
        if (!BuildConfig.KANTV_DIAGNOSTICS || !preferences(context).getBoolean("enabled", false)) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniqueWork("diagnostic-upload-now", oneTimeUploadEnqueuePolicy(),
            OneTimeWorkRequestBuilder<DiagnosticUploadWorker>().setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "diagnostic-upload-periodic", periodicUploadEnqueuePolicy(),
            PeriodicWorkRequestBuilder<DiagnosticUploadWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build(),
        )
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("diagnostic-upload-now")
        WorkManager.getInstance(context).cancelUniqueWork("diagnostic-upload-periodic")
    }
}

/**
 * 进入应用（含打开诊断页）时重新排上传任务的策略（2026-09-17 二次复盘）。
 *
 * 这里曾用 `KEEP`：只要还有一个旧任务赖在系统退避队列里，本次注册就被**静默忽略**。
 * 实测 0.2.19（已修好"失败处置"）装到电视上、也打开了应用，上传依旧一动不动——
 * 因为下次尝试仍由那个旧任务的 5 小时退避决定。故一次性任务改为 `REPLACE`、
 * 周期任务改为 `CANCEL_AND_REENQUEUE`：每次进入应用都得到一份全新的、立刻可跑的排期。
 */
internal fun oneTimeUploadEnqueuePolicy(): ExistingWorkPolicy = ExistingWorkPolicy.REPLACE

internal fun periodicUploadEnqueuePolicy(): ExistingPeriodicWorkPolicy =
    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE

/**
 * 一次上传尝试结束后的处置（2026-09-17 复盘）。
 *
 * 这里曾返回 `Result.retry()` 或 `Result.failure()`，两个方向都会让上传通道长时间失效：
 * - `retry()` 把"下次什么时候再试"交给系统调度器做指数退避，连续故障时涨到上限 **5 小时**：
 *   服务端修好后应用仍长时间不上传，而且"打开应用"也催不动——唯一任务还赖在重试队列里，
 *   反复入队被 KEEP 忽略；
 * - `failure()` 会让 15 分钟的周期任务直接终止，通道就此永久静默。
 *
 * 现在一律交回 `success()`：一次运行只负责"尝试一次并如实记录结果"，
 * 节奏交给周期任务与应用启动时的一次性任务；报告保留在本机，下次尝试自动重发。
 */
internal fun uploadAttemptDisposition(): ListenableWorker.Result = ListenableWorker.Result.success()

class DiagnosticUploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    @Volatile private var activeConnection: HttpURLConnection? = null

    override fun onStopped() {
        activeConnection?.disconnect()
        super.onStopped()
    }
    override fun doWork(): Result {
        // 同一时刻只跑一轮上传：应用启动时一次性任务与周期任务会同时触发，
        // 两轮争抢同一批报告文件会产生 MOVE_FAILED / RETENTION_DELETE_FAILED 这类并发噪音
        // （2026-09-17 现场：22:43:39 与 22:43:48 各一条）。被挡下的那一轮直接放行，等下一轮。
        if (!runLock.compareAndSet(false, true)) return Result.success()
        return try {
            performUpload()
        } finally {
            runLock.set(false)
        }
    }

    private companion object {
        /** 进程内单轮锁；Worker 与一次性任务共用同一进程，足以消除并发抢文件。 */
        val runLock = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private fun performUpload(): Result {
        if (!BuildConfig.KANTV_DIAGNOSTICS) return Result.success()
        val prefs = DiagnosticUploadConfig.preferences(applicationContext)
        if (!prefs.getBoolean("enabled", false)) return Result.success()
        val endpoint = prefs.getString("endpoint", "").orEmpty()
        val token = prefs.getString("token", "").orEmpty()
        if (!DiagnosticUploadConfig.validEndpoint(endpoint) || !DiagnosticUploadConfig.validToken(token)) {
            prefs.edit().putString("status", "上传地址或上传码未配置").apply()
            DiagnosticLedgerHealth.recordUpload(applicationContext, "INVALID_CONFIG",
                OfficialWebDiagnosticsRecorder.pendingReports(applicationContext).size)
            return uploadAttemptDisposition()
        }
        for (report in OfficialWebDiagnosticsRecorder.pendingReports(applicationContext)) {
            if (isStopped || !prefs.getBoolean("enabled", false)) return Result.success()
            if (report.length() > 512 * 1024) {
                prefs.edit().putString("status", "报告超出上传大小限制，已保留本机记录").apply()
                DiagnosticLedgerHealth.recordError(applicationContext, "UPLOAD_REPORT_TOO_LARGE")
                DiagnosticLedgerHealth.recordUpload(applicationContext, "HTTP_REJECTED",
                    OfficialWebDiagnosticsRecorder.pendingReports(applicationContext).size)
                return uploadAttemptDisposition()
            }
            var connection: HttpURLConnection? = null
            try {
                // 连接配置共享于 PostJsonBearer；本侧历史不发 Accept、不设 useCaches，
                // 固定长度按文件字节数（File.length()）——逐处保留，不顺手统一。
                connection = openPostJsonBearerConnection(
                    uri = endpoint,
                    token = token,
                    connectTimeoutMs = 5_000,
                    readTimeoutMs = 10_000,
                    sendAcceptJson = false,
                    disableCaches = false,
                    bodyLength = report.length(),
                )
                activeConnection = connection
                if (isStopped || !prefs.getBoolean("enabled", false)) return Result.success()
                connection.outputStream.use { output -> report.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (isStopped || !prefs.getBoolean("enabled", false)) return Result.success()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                } }
                if (isStopped || !prefs.getBoolean("enabled", false)) return Result.success()
                val code = connection.responseCode
                if (isStopped || !prefs.getBoolean("enabled", false)) return Result.success()
                if (code in 200..299) {
                    if (!OfficialWebDiagnosticsRecorder.markUploaded(applicationContext, report)) {
                        DiagnosticLedgerHealth.recordError(applicationContext, "UPLOAD_MOVE_FAILED")
                        DiagnosticLedgerHealth.recordUpload(applicationContext, "MOVE_FAILED",
                            OfficialWebDiagnosticsRecorder.pendingReports(applicationContext).size)
                        return uploadAttemptDisposition()
                    }
                    prefs.edit().putString("status", "已上传到 Home NAS").putLong("lastUploadAtMs", System.currentTimeMillis()).apply()
                    DiagnosticLedgerHealth.recordUpload(applicationContext, "SUCCESS",
                        OfficialWebDiagnosticsRecorder.pendingReports(applicationContext).size)
                } else {
                    prefs.edit().putString("status", "NAS 返回 $code，报告已保留").apply()
                    DiagnosticLedgerHealth.recordError(applicationContext, "UPLOAD_HTTP_$code")
                    DiagnosticLedgerHealth.recordUpload(applicationContext, "HTTP_REJECTED",
                        OfficialWebDiagnosticsRecorder.pendingReports(applicationContext).size)
                    return uploadAttemptDisposition()
                }
            } catch (_: java.io.IOException) {
                if (isStopped || !prefs.getBoolean("enabled", false)) return Result.success()
                prefs.edit().putString("status", "暂时连不上 NAS，联网后重试").apply()
                DiagnosticLedgerHealth.recordError(applicationContext, "UPLOAD_IO_FAILED")
                DiagnosticLedgerHealth.recordUpload(applicationContext, "RETRY",
                    OfficialWebDiagnosticsRecorder.pendingReports(applicationContext).size)
                return uploadAttemptDisposition()
            } catch (_: Exception) {
                DiagnosticLedgerHealth.recordError(applicationContext, "UPLOAD_UNEXPECTED_FAILED")
                DiagnosticLedgerHealth.recordUpload(applicationContext, "RETRY",
                    OfficialWebDiagnosticsRecorder.pendingReports(applicationContext).size)
                return uploadAttemptDisposition()
            } finally {
                activeConnection = null
                connection?.disconnect()
            }
        }
        return Result.success()
    }
}
