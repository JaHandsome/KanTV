package com.kantv.app.diagnostics

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticLedgerHealthSnapshot(
    val lastPersistedAtMs: Long,
    val pendingUploadCount: Int,
    val lastUploadAtMs: Long,
    val lastUploadResultAtMs: Long,
    val lastUploadResult: String,
    val lastErrorAtMs: Long,
    val lastErrorCode: String,
)

/** Small durable status record. It deliberately never stores exception text or remote data. */
object DiagnosticLedgerHealth {
    private const val LAST_PERSISTED = "ledger_last_persisted_at_ms"
    private const val PENDING = "ledger_pending_upload_count"
    private const val LAST_UPLOAD = "ledger_last_upload_at_ms"
    private const val LAST_UPLOAD_RESULT = "ledger_last_upload_result"
    private const val LAST_UPLOAD_RESULT_AT = "ledger_last_upload_result_at_ms"
    private const val LAST_ERROR = "ledger_last_error_at_ms"
    private const val LAST_ERROR_CODE = "ledger_last_error_code"

    fun read(context: Context, pendingCount: Int? = null): DiagnosticLedgerHealthSnapshot {
        val prefs = DiagnosticUploadConfig.preferences(context)
        return DiagnosticLedgerHealthSnapshot(
            lastPersistedAtMs = prefs.getLong(LAST_PERSISTED, 0),
            pendingUploadCount = pendingCount ?: prefs.getInt(PENDING, 0),
            lastUploadAtMs = prefs.getLong(LAST_UPLOAD, 0),
            lastUploadResultAtMs = prefs.getLong(LAST_UPLOAD_RESULT_AT, 0),
            lastUploadResult = prefs.getString(LAST_UPLOAD_RESULT, "NEVER").orEmpty(),
            lastErrorAtMs = prefs.getLong(LAST_ERROR, 0),
            lastErrorCode = prefs.getString(LAST_ERROR_CODE, "NONE").orEmpty(),
        )
    }

    @Volatile
    private var lastPersistedWriteAtMs = 0L

    @Volatile
    private var lastPersistedPendingCount = -1

    /**
     * 每次 ACTIVE checkpoint（3s 采样、至多每分钟落一次盘）都会调一次；commit 的同步写盘在低端盒上
     * 是 ms 级抖动源。改为异步 apply，且时间戳与待上传数都没变时 5s 内不重复写。
     */
    fun recordPersisted(context: Context, atMs: Long, pendingCount: Int) {
        val pending = pendingCount.coerceAtLeast(0)
        if (pending == lastPersistedPendingCount && atMs - lastPersistedWriteAtMs < 5_000L) return
        lastPersistedWriteAtMs = atMs
        lastPersistedPendingCount = pending
        DiagnosticUploadConfig.preferences(context).edit()
            .putLong(LAST_PERSISTED, atMs)
            .putInt(PENDING, pending)
            .apply()
    }

    fun recordPendingCount(context: Context, pendingCount: Int) {
        DiagnosticUploadConfig.preferences(context).edit()
            .putInt(PENDING, pendingCount.coerceAtLeast(0))
            .apply()
    }

    fun recordError(context: Context, code: String, atMs: Long = System.currentTimeMillis()) {
        DiagnosticUploadConfig.preferences(context).edit()
            .putLong(LAST_ERROR, atMs)
            .putString(LAST_ERROR_CODE, safeCode(code))
            .apply()
    }

    fun recordUpload(context: Context, result: String, pendingCount: Int, atMs: Long = System.currentTimeMillis()) {
        val success = result == "SUCCESS"
        DiagnosticUploadConfig.preferences(context).edit()
            .putString(LAST_UPLOAD_RESULT, safeCode(result))
            .putLong(LAST_UPLOAD_RESULT_AT, atMs)
            .putInt(PENDING, pendingCount.coerceAtLeast(0))
            .apply { if (success) putLong(LAST_UPLOAD, atMs) }
            .apply()
    }

    fun summary(snapshot: DiagnosticLedgerHealthSnapshot): String {
        fun time(value: Long): String = if (value <= 0) "尚无" else
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
        val upload = when (snapshot.lastUploadResult) {
            "SUCCESS" -> "成功"
            "RETRY" -> "等待重试"
            "INVALID_CONFIG" -> "配置无效"
            "HTTP_REJECTED" -> "服务器拒绝"
            "MOVE_FAILED" -> "上传后归档失败"
            else -> "尚无"
        }
        val error = if (snapshot.lastErrorCode == "NONE") "无" else
            "${snapshot.lastErrorCode}（${time(snapshot.lastErrorAtMs)}）"
        return "账本最近落盘：${time(snapshot.lastPersistedAtMs)}\n" +
            "待上传：${snapshot.pendingUploadCount} 份\n" +
            "最近上传结果：$upload（${time(snapshot.lastUploadResultAtMs)}）\n" +
            "最近错误：$error"
    }

    private fun safeCode(value: String): String = value.uppercase(Locale.ROOT)
        .filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' }
        .take(48)
        .ifBlank { "UNKNOWN" }
}
