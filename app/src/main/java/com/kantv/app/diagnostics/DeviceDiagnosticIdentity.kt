package com.kantv.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.webkit.WebViewCompat
import java.util.UUID

data class DeviceDecoderCapability(
    val name: String,
    val types: List<String>,
    /** Capability classification is unavailable before API 29; never infer it from the name. */
    val hardwareAccelerated: Boolean?,
    val softwareOnly: Boolean?,
)

/** Slow full inventory has its own cache lock; minimal START work never enters it. */
internal class DiagnosticIdentityReadLanes<T>(private val fullLoader: () -> T) {
    private val fullLock = Any()
    @Volatile private var cached: T? = null

    fun full(): T = cached ?: synchronized(fullLock) {
        cached ?: fullLoader().also { cached = it }
    }

    fun <R> minimal(block: () -> R): R = block()
}

data class DeviceDiagnosticIdentity(
    val installId: String,
    val label: String,
    val appName: String,
    val firstInstallTimeMs: Long,
    val lastUpdateTimeMs: Long,
    val manufacturer: String,
    val model: String,
    val product: String,
    val board: String,
    val hardware: String,
    val socManufacturer: String,
    val socModel: String,
    val androidRelease: String,
    val securityPatch: String,
    val cpuAbi: String,
    val cpuAbis: List<String>,
    val cpuCoreCount: Int,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val lowRamDevice: Boolean?,
    val lowMemory: Boolean?,
    val displayWidth: Int,
    val displayHeight: Int,
    val displayRefreshRate: Float,
    val displayModeId: Int,
    val displayModes: List<String>,
    val hdrTypes: List<Int>,
    val networkTransports: List<String>,
    val networkMetered: Boolean?,
    val thermalStatus: Int,
    val decoderCapabilities: List<DeviceDecoderCapability>,
    val webViewVersion: String,
) {
    companion object {
        private const val INSTALL_ID = "device_install_id"
        private const val DEVICE_LABEL = "device_label"
        private val installIdLock = Any()
        private val identityReadLanes = DiagnosticIdentityReadLanes(::decoderCapabilities)

        fun read(context: Context, includeDecoderCapabilities: Boolean = true): DeviceDiagnosticIdentity {
            val prefs = DiagnosticUploadConfig.preferences(context)
            val installId = installId(context)
            val model = bounded(Build.MODEL)
            val defaultLabel = "$model · ${installId.take(6)}"
            val label = bounded(prefs.getString(DEVICE_LABEL, null)).takeIf { it != "unknown" }
                ?: defaultLabel.also { prefs.edit().putString(DEVICE_LABEL, it).apply() }
            val memory = ActivityManager.MemoryInfo()
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.getMemoryInfo(memory)
            val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val mode = display?.mode
            val metrics = context.resources.displayMetrics
            val packageInfo = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            val networkManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val capabilities = networkManager?.activeNetwork?.let(networkManager::getNetworkCapabilities)
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return DeviceDiagnosticIdentity(
                installId = installId,
                label = label,
                appName = bounded(runCatching {
                    context.applicationInfo.loadLabel(context.packageManager)?.toString()
                }.getOrNull()),
                firstInstallTimeMs = packageInfo?.firstInstallTime ?: -1,
                lastUpdateTimeMs = packageInfo?.lastUpdateTime ?: -1,
                manufacturer = bounded(Build.MANUFACTURER),
                model = model,
                product = bounded(Build.PRODUCT),
                board = bounded(Build.BOARD),
                hardware = bounded(Build.HARDWARE),
                socManufacturer = if (Build.VERSION.SDK_INT >= 31) bounded(Build.SOC_MANUFACTURER) else "unknown",
                socModel = if (Build.VERSION.SDK_INT >= 31) bounded(Build.SOC_MODEL) else "unknown",
                androidRelease = bounded(Build.VERSION.RELEASE),
                securityPatch = bounded(Build.VERSION.SECURITY_PATCH),
                cpuAbi = bounded(Build.SUPPORTED_ABIS.firstOrNull()),
                cpuAbis = Build.SUPPORTED_ABIS.take(8).map(::bounded),
                cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                totalRamMb = memory.totalMem.toMbOrUnknown(),
                availableRamMb = memory.availMem.toMbOrUnknown(),
                lowRamDevice = activityManager?.isLowRamDevice,
                lowMemory = activityManager?.let { memory.lowMemory },
                displayWidth = (mode?.physicalWidth ?: metrics.widthPixels).coerceAtLeast(0),
                displayHeight = (mode?.physicalHeight ?: metrics.heightPixels).coerceAtLeast(0),
                displayRefreshRate = mode?.refreshRate?.takeIf { it > 0f } ?: -1f,
                displayModeId = mode?.modeId ?: -1,
                displayModes = display?.supportedModes.orEmpty().take(32).map {
                    "${it.modeId}:${it.physicalWidth}x${it.physicalHeight}@${"%.3f".format(java.util.Locale.ROOT, it.refreshRate)}"
                },
                hdrTypes = if (Build.VERSION.SDK_INT >= 24) display?.hdrCapabilities?.supportedHdrTypes?.toList().orEmpty() else emptyList(),
                networkTransports = networkTransports(capabilities),
                networkMetered = networkManager?.isActiveNetworkMetered,
                thermalStatus = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus ?: -1 else -1,
                decoderCapabilities = if (!includeDecoderCapabilities) emptyList() else identityReadLanes.full(),
                webViewVersion = bounded(runCatching {
                    WebViewCompat.getCurrentWebViewPackage(context)?.versionName
                }.getOrNull()),
            )
        }

        /** Fast START identity: complete schema, cheap stable facts, unavailable runtime facts explicit. */
        fun readMinimal(context: Context): DeviceDiagnosticIdentity = identityReadLanes.minimal {
            val installId = installId(context)
            val model = bounded(Build.MODEL)
            DeviceDiagnosticIdentity(
                installId = installId, label = "$model · ${installId.take(6)}", appName = "看电视",
                firstInstallTimeMs = -1, lastUpdateTimeMs = -1,
                manufacturer = bounded(Build.MANUFACTURER), model = model, product = bounded(Build.PRODUCT),
                board = bounded(Build.BOARD), hardware = bounded(Build.HARDWARE),
                socManufacturer = if (Build.VERSION.SDK_INT >= 31) bounded(Build.SOC_MANUFACTURER) else "unknown",
                socModel = if (Build.VERSION.SDK_INT >= 31) bounded(Build.SOC_MODEL) else "unknown",
                androidRelease = bounded(Build.VERSION.RELEASE), securityPatch = bounded(Build.VERSION.SECURITY_PATCH),
                cpuAbi = bounded(Build.SUPPORTED_ABIS.firstOrNull()), cpuAbis = Build.SUPPORTED_ABIS.take(8).map(::bounded),
                cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                totalRamMb = -1, availableRamMb = -1, lowRamDevice = null, lowMemory = null,
                displayWidth = -1, displayHeight = -1, displayRefreshRate = -1f, displayModeId = -1,
                displayModes = emptyList(), hdrTypes = emptyList(), networkTransports = emptyList(),
                networkMetered = null, thermalStatus = -1, decoderCapabilities = emptyList(), webViewVersion = "unknown",
            )
        }

        private fun installId(context: Context): String = synchronized(installIdLock) {
            val prefs = DiagnosticUploadConfig.preferences(context)
            prefs.getString(INSTALL_ID, null)
                ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
                ?: UUID.randomUUID().toString().also { prefs.edit().putString(INSTALL_ID, it).apply() }
        }

        fun saveLabel(context: Context, value: String) {
            val label = bounded(value)
            if (label != "unknown") {
                DiagnosticUploadConfig.preferences(context).edit().putString(DEVICE_LABEL, label).apply()
            }
        }

        private fun decoderCapabilities(): List<DeviceDecoderCapability> = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.asSequence()
                .filterNot(MediaCodecInfo::isEncoder)
                .map { codec ->
                    DeviceDecoderCapability(
                        name = technical(codec.name),
                        types = codec.supportedTypes.asSequence()
                            .filter { it.startsWith("audio/") || it.startsWith("video/") }
                            .take(16).map(::technical).toList(),
                        hardwareAccelerated = if (Build.VERSION.SDK_INT >= 29) codec.isHardwareAccelerated else null,
                        softwareOnly = if (Build.VERSION.SDK_INT >= 29) codec.isSoftwareOnly else null,
                    )
                }
                .filter { it.types.isNotEmpty() }
                .take(96)
                .toList()
        }.getOrDefault(emptyList())

        private fun networkTransports(value: NetworkCapabilities?): List<String> = buildList {
            if (value == null) return@buildList
            if (value.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
            if (value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
            if (value.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
            if (value.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (value.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BLUETOOTH")
            if (Build.VERSION.SDK_INT >= 26 && value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) add("WIFI_AWARE")
            if (Build.VERSION.SDK_INT >= 31 && value.hasTransport(NetworkCapabilities.TRANSPORT_USB)) add("USB")
        }

        fun technical(value: String?): String {
            val raw = value.orEmpty()
            if ("://" in raw || '?' in raw || '@' in raw || '=' in raw) return "unknown"
            return raw.filter { it.isLetterOrDigit() || it in "._/+:-" }
                .take(96).ifBlank { "unknown" }
        }

        private fun bounded(value: String?): String = value.orEmpty()
            .filter { !it.isISOControl() }
            .trim()
            .take(80)
            .ifBlank { "unknown" }

        private fun Long.toMbOrUnknown(): Long = if (this > 0) this / (1024L * 1024L) else -1
    }
}
