package com.kantv.app.update

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.lang.ref.WeakReference

sealed interface InstallSubmission {
    data class NeedsUnknownSourcesPermission(val intent: Intent) : InstallSubmission
    data object Submitted : InstallSubmission
    data object Failed : InstallSubmission
}

object LanPackageInstaller {
    private const val INSTALL_ACTION = "com.kantv.app.action.LAN_UPDATE_INSTALL_RESULT"
    private var initiatingActivity = WeakReference<Activity>(null)

    fun submit(activity: Activity, apk: File, manifest: LanReleaseManifest): InstallSubmission {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            return InstallSubmission.NeedsUnknownSourcesPermission(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
        }
        if (!apk.isFile || apk.length() != manifest.size || manifest.applicationId != activity.packageName) {
            return InstallSubmission.Failed
        }
        val installer = activity.packageManager.packageInstaller
        var sessionId = -1
        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(manifest.applicationId)
                setSize(manifest.size)
                if (Build.VERSION.SDK_INT >= 31) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, manifest.size).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                    session.fsync(output)
                }
                val callback = PendingIntent.getBroadcast(
                    activity,
                    sessionId,
                    Intent(activity, LanUpdateInstallResultReceiver::class.java).setAction(INSTALL_ACTION),
                    PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
                )
                initiatingActivity = WeakReference(activity)
                session.commit(callback.intentSender)
            }
            InstallSubmission.Submitted
        } catch (_: Exception) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            InstallSubmission.Failed
        }
    }

    internal fun isInstallResult(intent: Intent): Boolean = intent.action == INSTALL_ACTION

    internal fun foregroundActivity(): Activity? = initiatingActivity.get()?.takeIf { activity ->
        !activity.isFinishing &&
            (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed) &&
            activity.window.decorView.hasWindowFocus()
    }

    internal fun clearInitiatingActivity() {
        initiatingActivity.clear()
    }
}

class LanUpdateInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!LanPackageInstaller.isInstallResult(intent)) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                val activity = LanPackageInstaller.foregroundActivity()
                if (confirmation != null && activity != null) {
                    LanUpdateStatusStore.record(context, "等待系统安装确认")
                    activity.startActivity(confirmation)
                } else {
                    LanUpdateStatusStore.record(context, "更新需要系统确认；请回到设置页再次选择安装")
                }
                LanPackageInstaller.clearInitiatingActivity()
            }
            PackageInstaller.STATUS_SUCCESS -> {
                LanPackageInstaller.clearInitiatingActivity()
                LanUpdateStatusStore.record(context, "更新安装成功")
            }
            else -> {
                LanPackageInstaller.clearInitiatingActivity()
                LanUpdateStatusStore.record(context, "系统未完成安装，当前版本保持不变")
            }
        }
    }
}
