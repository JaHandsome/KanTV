package com.kantv.app.playback

import androidx.media3.common.C
import com.kantv.app.catalog.HubClearKey

/**
 * Media3 播放 ClearKey 所需的**声明式**配置。
 *
 * 之所以把它做成值对象而不是直接建 DRM 实例：契约要求的两个源码级坑必须能被单测断言，
 * 而真实 DRM 会话无法在 JVM 单测里建立。
 */
data class ClearKeyDrmSpec(
    /** 必须等于 `C.CLEARKEY_UUID`；`DefaultDrmSessionManager.Builder()` 的默认 uuid 是 Widevine。 */
    val uuid: String,
    /** EME ClearKey 的 key response（`{"keys":[{"kty":"oct","k":...,"kid":...}]}`）。 */
    val keyResponseJson: String,
    /** true = 用 `Player.setMediaItem` 让 player 自己的 MediaSourceFactory（含 provider）生效。 */
    val useMediaItem: Boolean,
    /** true = 已挂 `setDrmSessionManagerProvider(...)`。 */
    val attachDrmSessionManagerProvider: Boolean,
    /** true = 已显式 `setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, ...)`。 */
    val explicitUuidProvider: Boolean,
)

/** 配置缺陷。发现任一缺陷都表现为黑屏或 DRM 失败，因此上线前必须为空。 */
enum class ClearKeyDefect {
    /** 没有显式指定 uuid：`DefaultDrmSessionManager.Builder()` 默认是 Widevine → ClearKey 内容黑屏。 */
    IMPLICIT_WIDEVINE_DEFAULT,

    /** 用 `setMediaSource` 绕过了 DrmSessionManagerProvider。 */
    MEDIA_SOURCE_BYPASSES_PROVIDER,

    /** 没有挂 provider。 */
    PROVIDER_NOT_ATTACHED,

    /** 没有任何密钥。 */
    MISSING_KEYS,

    /** 内联密钥不是合法十六进制。 */
    MALFORMED_KEY,
}

/**
 * 契约 §3.1 + 已知源码级坑的落地：把服务端内联 ClearKey 转成 Media3 可用的配置。
 *
 * 密钥来源：`route.clearkey` 内联；MPD 文本注入是降级方案（不在此实现，见 docs/HUB_CLIENT.md 的 P1）。
 */
object ClearKeyPlaybackSupport {
    /**
     * ClearKey 的**回落** uuid，直接取自 Media3 的权威常量 `androidx.media3.common.C.CLEARKEY_UUID`
     * （**不**复制字面量，避免与 Media3 版本漂移）。
     *
     * 注意：会话 uuid **必须与 MPD 的 `ContentProtection@schemeIdUri` 一致**。
     * v1.1 §3 起服务端会通过 `routes[].drm_uuid` 声明它；该字段缺失时才回落到此值。
     */
    val FALLBACK_UUID: String = C.CLEARKEY_UUID.toString()

    /**
     * 为 dash-clearkey 线路构造配置。非该 route、或缺少内联密钥时返回 null。
     * 默认即采用「显式 uuid + 挂 provider + setMediaItem」这条正确路径。
     *
     * uuid 取值优先级：服务端 `drm_uuid` → [FALLBACK_UUID]。
     */
    fun specFor(plan: HubRoutePlan): ClearKeyDrmSpec? {
        if (!plan.requiresClearKey) return null
        val clearKey = plan.clearKey ?: return null
        val keyResponse = keyResponseJson(clearKey) ?: return null
        return ClearKeyDrmSpec(
            uuid = plan.drmUuid?.takeIf { it.isNotBlank() } ?: FALLBACK_UUID,
            keyResponseJson = keyResponse,
            useMediaItem = true,
            attachDrmSessionManagerProvider = true,
            explicitUuidProvider = true,
        )
    }

    /** EME ClearKey 的 key response；密钥非法时返回 null。 */
    fun keyResponseJson(clearKey: HubClearKey): String? {
        val key = hexToBase64Url(clearKey.key) ?: return null
        val kid = hexToBase64Url(clearKey.kid) ?: return null
        return """{"keys":[{"kty":"oct","k":"$key","kid":"$kid"}],"type":"temporary"}"""
    }

    /** 十六进制 → base64url（无填充）。手写实现：不依赖 API 26 的 java.util.Base64。 */
    fun hexToBase64Url(hex: String): String? {
        val bytes = hexToBytes(hex) ?: return null
        if (bytes.isEmpty()) return null
        return base64Url(bytes)
    }

    /** 校验配置是否满足两个源码级坑的要求。 */
    fun defectOf(spec: ClearKeyDrmSpec): ClearKeyDefect? = when {
        spec.keyResponseJson.isBlank() -> ClearKeyDefect.MISSING_KEYS
        spec.uuid.isBlank() || !spec.explicitUuidProvider -> ClearKeyDefect.IMPLICIT_WIDEVINE_DEFAULT
        !spec.attachDrmSessionManagerProvider -> ClearKeyDefect.PROVIDER_NOT_ATTACHED
        !spec.useMediaItem -> ClearKeyDefect.MEDIA_SOURCE_BYPASSES_PROVIDER
        else -> null
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val trimmed = hex.trim()
        if (trimmed.isEmpty() || trimmed.length % 2 != 0) return null
        val output = ByteArray(trimmed.length / 2)
        for (index in output.indices) {
            val high = Character.digit(trimmed[index * 2], 16)
            val low = Character.digit(trimmed[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            output[index] = ((high shl 4) or low).toByte()
        }
        return output
    }

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private fun base64Url(bytes: ByteArray): String {
        val builder = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val remaining = bytes.size - index
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (remaining > 1) bytes[index + 1].toInt() and 0xFF else 0
            val b2 = if (remaining > 2) bytes[index + 2].toInt() and 0xFF else 0
            builder.append(ALPHABET[b0 ushr 2])
            builder.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (remaining > 1) builder.append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            if (remaining > 2) builder.append(ALPHABET[b2 and 0x3F])
            index += 3
        }
        return builder.toString()
    }
}
