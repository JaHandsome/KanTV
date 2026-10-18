package com.kantv.app.playback

import android.content.Context
import android.content.SharedPreferences
import com.kantv.app.util.Hashing
import java.nio.charset.StandardCharsets

/** The single rendering kernel available to [AdaptivePlaybackEngine]. */
enum class PlaybackKernel {
    MEDIA3,
}

/**
 * Device-local, non-sensitive playback history for a single source fingerprint.
 * The fingerprint is a SHA-256 value; callers must never use a URI as the key.
 */
data class SourcePlaybackProfile(
    val preferredKernel: PlaybackKernel?,
    val media3SuccessCount: Int,
    val media3FailureCount: Int,
)

/**
 * 只保留"这台设备用哪个内核更顺"这一件事。
 *
 * 2026-09-15 移除：按历史给源打分的整套（[SourcePlaybackOutcome]、`recordOutcome`、
 * `outcomesFor`、`sourceHash`、`channelHash` 以及编解码）。原因是那套分数被用来**重排线路顺序**，
 * 实测把一条只会放静止测试卡的源抬到第一位，用户每次打开都先撞上它。
 * 质量判断已改由用户上报（"报错"按钮）驱动，app 不再按历史猜。
 * 已写入的 `candidate.<channelHash>.` 偏好不再被读取，留在设备上不影响任何行为。
 */
interface SourcePlaybackProfileStore {
    fun profileFor(sourceFingerprint: String): SourcePlaybackProfile?
    fun recordSuccess(sourceFingerprint: String, kernel: PlaybackKernel)
    fun recordFailure(sourceFingerprint: String, kernel: PlaybackKernel)

    companion object {
        /**
         * Hash every request field that can affect playback.  The canonical encoding is
         * length-prefixed and headers are sorted, so neither the URI nor header values are
         * retained in preferences or used as preference keys.
         */
        fun fingerprint(request: PlaybackRequest): String {
            val identity = buildString {
                appendPart(request.uri)
                appendPart(request.mimeType.orEmpty())
                request.requestHeaders.entries
                    .sortedWith(compareBy<Map.Entry<String, String>>({ it.key }, { it.value }))
                    .forEach { entry ->
                        appendPart(entry.key)
                        appendPart(entry.value)
                    }
            }
            return Hashing.sha256Hex(identity.toByteArray(StandardCharsets.UTF_8))
        }

        /**
         * Candidate identity used both as the persistence key and as the redacted report value.
         * It is the same full SHA-256 digest [fingerprint] computes, so diagnostics persist only
         * this digest and never the source material itself.
         */
        fun sourceHash(request: PlaybackRequest): String = fingerprint(request)

        private fun StringBuilder.appendPart(value: String) {
            append(value.length).append(':').append(value)
        }
    }
}

/** SharedPreferences-backed store. Values contain only a selected kernel and aggregate counters. */
class SharedPreferencesSourcePlaybackProfileStore(
    context: Context,
    preferenceName: String = PREFERENCE_NAME,
) : SourcePlaybackProfileStore {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    override fun profileFor(sourceFingerprint: String): SourcePlaybackProfile? {
        val key = sourceFingerprint.keyPrefix()
        if (!preferences.contains("$key.preferred") && PlaybackKernel.entries.none { kernel ->
                preferences.contains("$key.${kernel.storageName}.success") ||
                    preferences.contains("$key.${kernel.storageName}.failure")
            }
        ) return null
        return SourcePlaybackProfile(
            preferredKernel = preferences.getString("$key.preferred", null)
                ?.let { runCatching { PlaybackKernel.valueOf(it) }.getOrNull() },
            media3SuccessCount = preferences.getInt("$key.media3.success", 0),
            media3FailureCount = preferences.getInt("$key.media3.failure", 0),
        )
    }

    override fun recordSuccess(sourceFingerprint: String, kernel: PlaybackKernel) {
        update(sourceFingerprint, kernel, succeeded = true)
    }

    override fun recordFailure(sourceFingerprint: String, kernel: PlaybackKernel) {
        update(sourceFingerprint, kernel, succeeded = false)
    }

    private fun update(sourceFingerprint: String, kernel: PlaybackKernel, succeeded: Boolean) {
        val key = sourceFingerprint.keyPrefix()
        val outcome = if (succeeded) "success" else "failure"
        val countKey = "$key.${kernel.storageName}.$outcome"
        preferences.edit().apply {
            if (succeeded) putString("$key.preferred", kernel.name)
        }
            .putInt(countKey, preferences.getInt(countKey, 0).incrementSafely())
            .apply()
    }

    private fun String.keyPrefix(): String = "source.$this"

    private val PlaybackKernel.storageName: String
        get() = name.lowercase()

    private fun Int.incrementSafely(): Int = if (this == Int.MAX_VALUE) this else this + 1

    private companion object {
        // Version the store whenever the success definition changes so stale experiments cannot
        // become a long-lived preference on family devices.
        // Strategy version: v6 adds HLS parallel prefetch to the compatibility kernel.
        // v7 遗留：候选健康分的 `candidate.<channelHash>.` 键前缀已于 2026-09-15 停止读写，
        // 旧数据留在设备上不再被解释；名字保持不变，避免丢弃内核偏好。
        const val PREFERENCE_NAME = "source_playback_profiles_v7"
    }
}
