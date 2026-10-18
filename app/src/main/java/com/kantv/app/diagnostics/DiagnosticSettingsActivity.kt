package com.kantv.app.diagnostics

import android.app.Activity
import android.os.Bundle
import android.widget.*
import com.kantv.app.BuildConfig

/** Read-only diagnostics status for the one ordinary KanTV package. */
class DiagnosticSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.KANTV_DIAGNOSTICS) { finish(); return }
        DiagnosticUploadConfig.initializeBundledDefaults(this)
        DiagnosticUploadConfig.schedule(this)
        com.kantv.app.update.LanUpdateConfig.initializeBundledDefaults(this)
        val prefs = DiagnosticUploadConfig.preferences(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        fun text(value: String) = TextView(this).apply { text = value; textSize = 18f; layout.addView(this) }
        text("电视故障诊断")
        text("诊断由应用自动记录并在已配对版本中自动上传到 Home NAS；上传失败会保留在电视并后台重试，无需手动配置或导出。")
        text("仅记录播放数值和应用状态，不记录播放地址、账号、Cookie、请求头或画面。")
        val identity = DeviceDiagnosticIdentity.read(this)
        text("本次安装标识：${identity.installId.take(8)} · ${identity.cpuAbi} · ${identity.totalRamMb} MB")
        val status = text("正在读取本机诊断记录…")
        val updateStatus = text("应用更新：${com.kantv.app.update.LanUpdateStatusStore.text(this)}\n版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
        layout.addView(Button(this).apply {
            text = "检查并安装更新"
            setOnClickListener {
                com.kantv.app.update.LanUpdateManager.downloadAndInstall(
                    this@DiagnosticSettingsActivity,
                    callback = { result -> updateStatus.text = "应用更新：$result" },
                    onProgress = { text -> updateStatus.text = text },
                )
            }
        })
        Thread {
            val count = OfficialWebDiagnosticsRecorder.pendingReports(applicationContext).size
            val health = DiagnosticLedgerHealth.read(applicationContext, count)
            runOnUiThread { if (!isFinishing) status.text =
                "${DiagnosticLedgerHealth.summary(health)}\n${prefs.getString("status", "尚未配置上传")}" }
        }.start()
        layout.addView(Button(this).apply {
            text = "返回看电视"
            setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }
}
