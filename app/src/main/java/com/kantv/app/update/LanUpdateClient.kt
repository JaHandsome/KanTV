package com.kantv.app.update

import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.kantv.app.BuildConfig
import com.kantv.app.util.Hashing
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class LanUpdateConfiguration(val latestEndpoint: String, val token: String)

object LanUpdateConfig {
    private const val PREFS = "lan_update"
    private const val DEFAULTS_APPLIED = "defaults_applied"

    fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun initializeBundledDefaults(context: Context) {
        if (!LanUpdateEndpointPolicy.validLatestEndpoint(BuildConfig.UPDATE_DEFAULT_ENDPOINT) ||
            !LanUpdateEndpointPolicy.validToken(BuildConfig.UPDATE_DEFAULT_TOKEN)
        ) return
        val prefs = preferences(context)
        if (prefs.getBoolean(DEFAULTS_APPLIED, false)) return
        if (!prefs.contains("endpoint") && !prefs.contains("token")) {
            prefs.edit()
                .putString("endpoint", BuildConfig.UPDATE_DEFAULT_ENDPOINT)
                .putString("token", BuildConfig.UPDATE_DEFAULT_TOKEN)
                .putString("status", "已配置 Home NAS 更新服务，尚未检查")
                .putBoolean(DEFAULTS_APPLIED, true)
                .apply()
        } else {
            prefs.edit().putBoolean(DEFAULTS_APPLIED, true).apply()
        }
    }

    fun read(context: Context): LanUpdateConfiguration? {
        initializeBundledDefaults(context)
        val prefs = preferences(context)
        val endpoint = prefs.getString("endpoint", "").orEmpty()
        val token = prefs.getString("token", "").orEmpty()
        return if (LanUpdateEndpointPolicy.validLatestEndpoint(endpoint) && LanUpdateEndpointPolicy.validToken(token)) {
            LanUpdateConfiguration(endpoint, token)
        } else null
    }
}

object LanUpdateStatusStore {
    fun text(context: Context): String = LanUpdateConfig.preferences(context)
        .getString("status", "未配置 Home NAS 更新服务").orEmpty()

    fun record(context: Context, status: String, checkedAtMs: Long = 0L) {
        LanUpdateConfig.preferences(context).edit().putString("status", status)
            .apply { if (checkedAtMs > 0) putLong("last_check_at_ms", checkedAtMs) }
            .apply()
    }
}

class LanUpdateException(val code: String) : IOException(code)

class LanUpdateClient(
    private val configuration: LanUpdateConfiguration,
) {
    fun fetchManifest(abi: String? = null): LanReleaseManifest {
        if (!LanUpdateEndpointPolicy.validLatestEndpoint(configuration.latestEndpoint) ||
            !LanUpdateEndpointPolicy.validToken(configuration.token)
        ) throw LanUpdateException("INVALID_CONFIG")
        val connection = open(URI(configuration.latestEndpoint), abi)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw LanUpdateException("MANIFEST_HTTP_$code")
            val contentType = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
            if (contentType != "application/json") throw LanUpdateException("MANIFEST_CONTENT_TYPE")
            val length = responseContentLength(connection)
            if (length !in 1..MAX_MANIFEST_BYTES.toLong()) throw LanUpdateException("MANIFEST_LENGTH")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArray(length.toInt())
                var offset = 0
                while (offset < output.size) {
                    val count = input.read(output, offset, output.size - offset)
                    if (count < 0) throw LanUpdateException("MANIFEST_INCOMPLETE")
                    offset += count
                }
                if (input.read() != -1) throw LanUpdateException("MANIFEST_LENGTH_MISMATCH")
                output
            }
            return try {
                LanReleaseManifestParser.parse(bytes.toString(Charsets.UTF_8))
            } catch (_: IllegalArgumentException) {
                throw LanUpdateException("MANIFEST_INVALID")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun download(
        context: Context,
        manifest: LanReleaseManifest,
        onProgress: ((Long, Long) -> Unit)? = null,
        abi: String? = null,
    ): File {
        val artifactUri = LanUpdateEndpointPolicy.artifactUri(configuration.latestEndpoint, manifest.apkPath)
            ?: throw LanUpdateException("ARTIFACT_URL_INVALID")
        val directory = File(context.cacheDir, "lan-updates")
        if (!directory.exists() && !directory.mkdirs()) throw LanUpdateException("CACHE_UNAVAILABLE")
        val destination = File(directory, "update-${manifest.versionCode}-${manifest.sha256.take(12)}.apk")
        if (destination.isFile && destination.length() == manifest.size &&
            LanUpdateArtifactPolicy.sha256(destination) == manifest.sha256
        ) return destination
        val temporary = File(directory, destination.name + ".part")
        if (temporary.exists() && !temporary.delete()) throw LanUpdateException("CACHE_UNAVAILABLE")
        val connection = open(artifactUri, abi)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw LanUpdateException("APK_HTTP_${connection.responseCode}")
            }
            val length = responseContentLength(connection)
            if (length != manifest.size || length !in 1..MAX_APK_BYTES) throw LanUpdateException("APK_LENGTH")
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastReportedPercent = -PROGRESS_STEP_PERCENT
            FileOutputStream(temporary).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > manifest.size || received > MAX_APK_BYTES) throw LanUpdateException("APK_TOO_LARGE")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        if (onProgress != null) {
                            val percent = ((received * 100) / manifest.size).toInt()
                            if (percent - lastReportedPercent >= PROGRESS_STEP_PERCENT) {
                                lastReportedPercent = percent
                                onProgress(received, manifest.size)
                            }
                        }
                    }
                }
                output.fd.sync()
            }
            val actualHash = Hashing.hex(digest.digest())
            if (received != manifest.size) throw LanUpdateException("APK_INCOMPLETE")
            if (actualHash != manifest.sha256) throw LanUpdateException("APK_HASH_MISMATCH")
            if (destination.exists() && !destination.delete()) throw LanUpdateException("CACHE_UNAVAILABLE")
            if (!temporary.renameTo(destination)) throw LanUpdateException("CACHE_FINALIZE_FAILED")
            return destination
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun open(uri: URI, abi: String? = null): HttpURLConnection = (uri.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
        requestMethod = "GET"
        instanceFollowRedirects = false
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("Authorization", "Bearer ${configuration.token}")
        setRequestProperty("Accept-Encoding", "identity")
        // 只发送受控的 ABI 词汇，绝不把设备原始字符串直接透传给 NAS。
        if (abi != null && LanUpdateAbiPolicy.isPublished(abi)) {
            setRequestProperty(LanUpdateAbiPolicy.ABI_HEADER, abi)
        }
        useCaches = false
    }

    private fun responseContentLength(connection: HttpURLConnection): Long {
        val raw = connection.getHeaderField("Content-Length") ?: return -1
        if (raw.isEmpty() || raw.length > 12 || raw.any { it !in '0'..'9' }) return -1
        return raw.toLongOrNull() ?: -1
    }

    companion object {
        const val MAX_MANIFEST_BYTES = 16 * 1024
        const val MAX_APK_BYTES = 256L * 1024 * 1024
        const val PROGRESS_STEP_PERCENT = 5
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 20_000
    }
}

object InstalledPackageInspector {
    fun currentIdentity(context: Context): ApkIdentity? = packageInfo(context.packageManager, context.packageName)?.toIdentity()

    /**
     * 当前已装 APK 内含的 ABI 集合（单 ABI 包只会有一个）。用于选择更新通道，
     * 保证不会把 32 位包更新成 64 位包（或反之）。读取失败返回空集合。
     */
    fun installedAbis(context: Context): Set<String> = runCatching {
        ApkAbiInspector.containedAbis(File(context.applicationInfo.sourceDir))
    }.getOrDefault(emptySet())

    fun archiveIdentity(context: Context, apk: File): ApkIdentity? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        }
        return info?.toIdentity()
    }

    private fun packageInfo(packageManager: PackageManager, packageName: String): PackageInfo? = try {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun PackageInfo.toIdentity(): ApkIdentity? {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            this.signatures
        } ?: return null
        if (signatures.isEmpty()) return null
        val digests = signatures.map { signature ->
            Hashing.sha256Hex(signature.toByteArray())
        }.toSet()
        return ApkIdentity(packageName, if (Build.VERSION.SDK_INT >= 28) longVersionCode else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }, digests)
    }
}

object LanUpdateManager {
    private const val AUTOMATIC_INITIAL_DELAY_MS = 20_000L
    private const val PERMISSION_RETURN_DELAY_MS = 1_500L
    private const val AUTO_PERMISSION_PENDING = "auto_permission_pending"
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kantv-lan-update").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val automaticAttempt = AutomaticUpdateAttempt()
    private var automaticForegroundActivity = WeakReference<Activity>(null)

    fun scheduleStartupCheck(context: Context) {
        if (LanUpdateConfig.read(context) == null) return
        val request = OneTimeWorkRequestBuilder<LanUpdateCheckWorker>()
            .setInitialDelay(20, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("lan-update-startup-check", ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Runs at most once per foreground app process. A return from the one-time unknown-source
     * permission screen is the only exception, so the verified package can continue without
     * making the user find the update button again.
     */
    fun scheduleForegroundAutoUpdate(activity: Activity) {
        if (LanUpdateConfig.read(activity.applicationContext) == null) return
        automaticForegroundActivity = WeakReference(activity)
        val returningFromPermission = LanUpdateConfig.preferences(activity)
            .getBoolean(AUTO_PERMISSION_PENDING, false)
        if (!automaticAttempt.reserve(returningFromPermission)) return
        mainHandler.postDelayed({
            val foreground = automaticForegroundActivity.get()?.takeIf(::isForeground)
            if (foreground == null) {
                if (!returningFromPermission) automaticAttempt.retryWhenForeground()
                return@postDelayed
            }
            runAutomaticUpdate(foreground, returningFromPermission)
        }, if (returningFromPermission) PERMISSION_RETURN_DELAY_MS else AUTOMATIC_INITIAL_DELAY_MS)
    }

    fun downloadAndInstall(activity: Activity, callback: (String) -> Unit = {}, onProgress: ((String) -> Unit)? = null) {
        val app = activity.applicationContext
        LanUpdateStatusStore.record(app, "正在安全下载更新…")
        val progressReporter: ((Long, Long) -> Unit)? = if (onProgress == null) null else { received, total ->
            val percent = ((received * 100) / total).toInt()
            val text = "正在下载更新… $percent%（${received / (1024 * 1024)} MB / ${total / (1024 * 1024)} MB）"
            mainHandler.post {
                LanUpdateStatusStore.record(app, text)
                onProgress.invoke(text)
            }
        }
        executor.execute {
            val prepared = prepareArtifact(app, progressReporter)
            mainHandler.post {
                if (activity.isFinishing || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return@post
                val status = when (prepared) {
                    is PreparedArtifact.Failure -> prepared.message
                    is PreparedArtifact.Ready -> when (val result = LanPackageInstaller.submit(activity, prepared.file, prepared.manifest)) {
                        is InstallSubmission.NeedsUnknownSourcesPermission -> {
                            activity.startActivity(result.intent)
                            "请允许“看电视”安装应用，然后返回再次选择安装更新"
                        }
                        InstallSubmission.Submitted -> "安装包已校验并提交系统安装；部分设备仍会要求确认"
                        InstallSubmission.Failed -> "系统安装会话创建失败，更新包未安装"
                    }
                }
                LanUpdateStatusStore.record(app, status)
                callback(status)
            }
        }
    }

    private fun runAutomaticUpdate(activity: Activity, returningFromPermission: Boolean) {
        if (!automaticAttempt.begin()) return
        val app = activity.applicationContext
        if (returningFromPermission && Build.VERSION.SDK_INT >= 26 &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            automaticAttempt.finish()
            setPermissionPending(app, false)
            LanUpdateStatusStore.record(app, "未获得安装应用权限；可在设置中手动再次安装更新")
            return
        }
        if (returningFromPermission) setPermissionPending(app, false)
        LanUpdateStatusStore.record(app, "正在自动检查 Home NAS 更新…")
        executor.execute {
            // 无感标准：用户正在看直播时不后台下载 APK——几十 MB 的下载会抢直播带宽。
            // 安装避让早已存在（下载完不提交安装），这里把避让前移到下载之前。
            // 两个闸都释放：播放停止后下一次 onStart 会重新发起这次检查。
            if (com.kantv.app.playback.PlaybackActivityFlag.isActive()) {
                LanUpdateStatusStore.record(app, "正在播放，更新下载已推迟；停止播放后回到前台自动继续")
                mainHandler.post {
                    automaticAttempt.finish()
                    automaticAttempt.retryWhenForeground()
                }
                return@execute
            }
            val prepared = prepareArtifact(app) { received, total ->
                val percent = ((received * 100) / total).toInt()
                LanUpdateStatusStore.record(app, "正在后台下载更新… $percent%（${received / (1024 * 1024)} MB / ${total / (1024 * 1024)} MB）")
            }
            mainHandler.post {
                automaticAttempt.finish()
                if (!isForeground(activity)) {
                    automaticAttempt.retryWhenForeground()
                    if (prepared is PreparedArtifact.Ready) {
                        LanUpdateStatusStore.record(app, "发现新版本 ${prepared.manifest.versionName}；应用回到前台后自动安装")
                    }
                    return@post
                }
                val status = when (prepared) {
                    is PreparedArtifact.Failure -> prepared.message
                    is PreparedArtifact.Ready -> {
                        // 无感标准：用户正在看直播时不提交安装（系统安装界面会盖在画面上），
                        // 也不把用户拽去系统授权页。包已下载好，等停止播放后再装。
                        if (com.kantv.app.playback.PlaybackActivityFlag.isActive()) {
                            automaticAttempt.retryWhenForeground()
                            LanUpdateStatusStore.record(
                                app,
                                "新版本 ${prepared.manifest.versionName} 已下载；停止播放后自动安装",
                                System.currentTimeMillis(),
                            )
                            return@post
                        }
                        when (val result = LanPackageInstaller.submit(activity, prepared.file, prepared.manifest)) {
                            is InstallSubmission.NeedsUnknownSourcesPermission -> {
                                if (returningFromPermission) {
                                    setPermissionPending(app, false)
                                    "未获得安装应用权限；将在下次回到前台时自动重试"
                                } else {
                                    setPermissionPending(app, true)
                                    if (runCatching { activity.startActivity(result.intent) }.isSuccess) {
                                        "请允许“看电视”安装应用；返回后将自动继续"
                                    } else {
                                        setPermissionPending(app, false)
                                        "无法打开系统安装授权页；将在下次回到前台时自动重试"
                                    }
                                }
                            }
                            InstallSubmission.Submitted -> {
                                setPermissionPending(app, false)
                                "新版已校验并提交系统安装；部分设备仍会要求确认"
                            }
                            InstallSubmission.Failed -> {
                                setPermissionPending(app, false)
                                "系统安装会话创建失败，当前版本保持不变"
                            }
                        }
                    }
                }
                LanUpdateStatusStore.record(app, status, System.currentTimeMillis())
            }
        }
    }

    private fun setPermissionPending(context: Context, pending: Boolean) {
        LanUpdateConfig.preferences(context).edit().putBoolean(AUTO_PERMISSION_PENDING, pending).apply()
    }

    private fun isForeground(activity: Activity): Boolean =
        !activity.isFinishing &&
            (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed) &&
            activity.window.decorView.hasWindowFocus()

    /** 本次更新应走的 ABI 通道；null 表示退回 legacy 行为（不带 ABI 头、不校验 ABI）。 */
    private fun updateAbi(context: Context): String? =
        LanUpdateAbiPolicy.select(InstalledPackageInspector.installedAbis(context), Build.SUPPORTED_ABIS.toList())

    internal fun checkBlocking(context: Context): String {
        val configuration = LanUpdateConfig.read(context)
        val status = if (configuration == null) {
            "未配置有效的 Home NAS 内网更新地址"
        } else {
            try {
                val manifest = LanUpdateClient(configuration).fetchManifest(updateAbi(context))
                val installed = InstalledPackageInspector.currentIdentity(context)
                if (installed == null) {
                    "无法读取当前安装包身份，已停止更新"
                } else {
                    when (LanUpdateArtifactPolicy.checkOffer(manifest, context.packageName, installed.versionCode, installed.signerCertSha256)) {
                        ArtifactRejection.NONE -> "发现新版本 ${manifest.versionName}（${manifest.versionCode}），将在应用前台自动安装"
                        ArtifactRejection.NOT_NEWER -> "当前已是最新版本（${installed.versionCode}）"
                        else -> "更新清单与当前应用身份不匹配，已拒绝"
                    }
                }
            } catch (_: IOException) {
                "暂时无法连接 Home NAS 更新服务"
            } catch (_: Exception) {
                "更新清单无效，已拒绝"
            }
        }
        LanUpdateStatusStore.record(context, status, System.currentTimeMillis())
        return status
    }

    private fun prepareArtifact(context: Context, onProgress: ((Long, Long) -> Unit)? = null): PreparedArtifact {
        val configuration = LanUpdateConfig.read(context)
            ?: return PreparedArtifact.Failure("未配置有效的 Home NAS 内网更新地址")
        return try {
            val client = LanUpdateClient(configuration)
            val targetAbi = updateAbi(context)
            val manifest = client.fetchManifest(targetAbi)
            val installed = InstalledPackageInspector.currentIdentity(context)
                ?: return PreparedArtifact.Failure("无法读取当前安装包身份，已停止更新")
            val offer = LanUpdateArtifactPolicy.checkOffer(manifest, context.packageName, installed.versionCode, installed.signerCertSha256)
            if (offer != ArtifactRejection.NONE) {
                return PreparedArtifact.Failure(if (offer == ArtifactRejection.NOT_NEWER) "当前已是最新版本，不会安装同版或旧版" else "更新清单与当前应用身份不匹配，已拒绝")
            }
            val file = client.download(context, manifest, onProgress, targetAbi)
            val archive = InstalledPackageInspector.archiveIdentity(context, file)
                ?: return PreparedArtifact.Failure("无法读取下载包身份，已拒绝安装")
            val verified = LanUpdateArtifactPolicy.verifyDownloaded(
                manifest, file.length(), LanUpdateArtifactPolicy.sha256(file), archive,
                targetAbi, ApkAbiInspector.containedAbis(file),
            )
            if (verified == ArtifactRejection.NONE) PreparedArtifact.Ready(manifest, file)
            else PreparedArtifact.Failure(
                if (verified == ArtifactRejection.ABI_MISMATCH) "更新包 ABI 与当前设备不符，已拒绝安装"
                else "更新包校验失败，已拒绝安装"
            )
        } catch (_: IOException) {
            PreparedArtifact.Failure("更新下载失败，请稍后重试")
        } catch (_: Exception) {
            PreparedArtifact.Failure("更新包无效，已拒绝安装")
        }
    }

    private sealed interface PreparedArtifact {
        data class Ready(val manifest: LanReleaseManifest, val file: File) : PreparedArtifact
        data class Failure(val message: String) : PreparedArtifact
    }
}

class LanUpdateCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val status = LanUpdateManager.checkBlocking(applicationContext)
        return if (status == "暂时无法连接 Home NAS 更新服务" && runAttemptCount < 3) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
