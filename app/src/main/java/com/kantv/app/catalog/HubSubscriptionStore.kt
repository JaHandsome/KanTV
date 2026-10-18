package com.kantv.app.catalog

import android.content.Context
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 枢纽订阅的本地状态。字段全部是**安全的运行元数据**：etag/seq/时间戳/状态码，
 * 以及最近一次成功解析到的服务端 payload 原文（用于离线兜底与别名表重建）。
 *
 * payload 只包含频道清单与线路地址，属于项目既有的「不得提交仓库」范畴，
 * 因此只落本机私有存储，绝不写入日志、报告或仓库。
 */
data class HubSubscriptionState(
    val etag: String? = null,
    val seq: Long = 0L,
    val payload: String? = null,
    val lastAttemptAtMs: Long = 0L,
    val lastSuccessAtMs: Long = 0L,
    /** 最近一次真正应用到目录的 seq；用于「从未成功过」判定。 */
    val lastAppliedSeq: Long = 0L,
    /** 固定枚举状态码，永不包含异常文本或 URL。 */
    val status: String = HubSubscriptionStatus.NEVER_FETCHED,
    /**
     * 最近一次成功订阅里 `signed_params.fingerprint`（P0-2 方案 C）；
     * 服务端未投递 / 旧版服务端时为 null。用于检测服务端 url_key 语义切换。
     */
    val signedParamsFingerprint: String? = null,
) {
    val hasSuccessfulSubscription: Boolean get() = lastSuccessAtMs > 0L && !payload.isNullOrBlank()

    val hasAppliedSubscription: Boolean get() = lastAppliedSeq > 0L
}

object HubSubscriptionStatus {
    const val NEVER_FETCHED = "NEVER_FETCHED"
    const val UPDATING = "UPDATING"
    const val SUCCESS = "SUCCESS"
    const val NOT_MODIFIED = "NOT_MODIFIED"
    const val SCHEMA_UNSUPPORTED = "SCHEMA_UNSUPPORTED"
    const val INVALID_PAYLOAD = "INVALID_PAYLOAD"
    const val TOO_LARGE = "TOO_LARGE"
    const val CONTENT_TYPE_INVALID = "CONTENT_TYPE_INVALID"
    const val NETWORK_TIMEOUT = "NETWORK_TIMEOUT"
    const val NETWORK_UNAVAILABLE = "NETWORK_UNAVAILABLE"
    const val NETWORK_IO = "NETWORK_IO"
    const val NETWORK_BLOCKED = "NETWORK_BLOCKED"
    const val ENDPOINT_INVALID = "ENDPOINT_INVALID"

    /** HTTP 状态码统一格式化为 HTTP_<code>，与 PlaylistRefreshStatus 习惯保持一致。 */
    fun http(status: Int): String = "HTTP_${status.coerceIn(100, 599)}"

    /** 异常 → 固定状态码；绝不透出异常文本、URL 或请求头。 */
    fun from(failure: Throwable): String = when (failure) {
        is SocketTimeoutException -> NETWORK_TIMEOUT
        is UnknownHostException, is ConnectException, is SSLException -> NETWORK_UNAVAILABLE
        is SecurityException -> NETWORK_BLOCKED
        is IOException -> NETWORK_IO
        else -> NETWORK_IO
    }
}

interface HubSubscriptionPersistence {
    fun read(): HubSubscriptionState?
    fun write(state: HubSubscriptionState)
    fun clear()
}

class InMemoryHubSubscriptionPersistence(private var state: HubSubscriptionState? = null) : HubSubscriptionPersistence {
    override fun read(): HubSubscriptionState? = state

    override fun write(state: HubSubscriptionState) {
        this.state = state
    }

    override fun clear() {
        state = null
    }
}

class SharedPreferencesHubSubscriptionPersistence internal constructor(
    private val prefs: android.content.SharedPreferences,
    private val payloadFile: java.io.File,
) : HubSubscriptionPersistence {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        java.io.File(context.applicationContext.filesDir, PAYLOAD_FILE_NAME),
    )

    override fun read(): HubSubscriptionState? {
        if (!prefs.contains(KEY_STATUS)) return null
        return HubSubscriptionState(
            etag = prefs.getString(KEY_ETAG, null),
            seq = prefs.getLong(KEY_SEQ, 0L),
            payload = readPayload(),
            lastAttemptAtMs = prefs.getLong(KEY_ATTEMPT, 0L),
            lastSuccessAtMs = prefs.getLong(KEY_SUCCESS, 0L),
            lastAppliedSeq = prefs.getLong(KEY_APPLIED_SEQ, 0L),
            status = prefs.getString(KEY_STATUS, HubSubscriptionStatus.NEVER_FETCHED)
                ?: HubSubscriptionStatus.NEVER_FETCHED,
            signedParamsFingerprint = prefs.getString(KEY_SIGNED_PARAMS_FP, null),
        )
    }

    override fun write(state: HubSubscriptionState) {
        prefs.edit()
            .putString(KEY_STATUS, state.status)
            .putLong(KEY_SEQ, state.seq)
            .putLong(KEY_ATTEMPT, state.lastAttemptAtMs)
            .putLong(KEY_SUCCESS, state.lastSuccessAtMs)
            .putLong(KEY_APPLIED_SEQ, state.lastAppliedSeq)
            .apply {
                if (state.etag == null) remove(KEY_ETAG) else putString(KEY_ETAG, state.etag)
                // 迁移期遗留：老版本曾把 payload 写进 prefs，读到/写到即清除。
                remove(KEY_PAYLOAD)
                if (state.signedParamsFingerprint == null) {
                    remove(KEY_SIGNED_PARAMS_FP)
                } else {
                    putString(KEY_SIGNED_PARAMS_FP, state.signedParamsFingerprint)
                }
            }
            .apply()
        synchronized(this) {
            writePayload(state.payload)
        }
    }

    private fun readPayload(): String? {
        synchronized(this) {
            val fromFile = runCatching {
                if (payloadFile.isFile) payloadFile.readText(Charsets.UTF_8) else null
            }.getOrNull()
            val legacy = prefs.getString(KEY_PAYLOAD, null)
            if (fromFile == null && legacy != null) writePayload(legacy)
            if (legacy != null) prefs.edit().remove(KEY_PAYLOAD).apply()
            return fromFile ?: legacy
        }
    }

    /** 临时文件 + rename，避免读到写了一半的 payload。 */
    private fun writePayload(payload: String?) {
        runCatching {
            if (payload == null) {
                payloadFile.delete()
                return
            }
            val tmp = java.io.File(payloadFile.parentFile, payloadFile.name + ".tmp")
            tmp.writeText(payload, Charsets.UTF_8)
            if (!tmp.renameTo(payloadFile)) {
                payloadFile.delete()
                tmp.renameTo(payloadFile)
            }
        }
    }

    override fun clear() {
        prefs.edit().clear().apply()
        synchronized(this) {
            payloadFile.delete()
        }
    }

    private companion object {
        const val PREFS_NAME = "hub_subscription"
        const val PAYLOAD_FILE_NAME = "hub_subscription_payload.json"
        const val KEY_ETAG = "etag"
        const val KEY_SEQ = "seq"
        const val KEY_PAYLOAD = "payload"
        const val KEY_ATTEMPT = "last_attempt_at_ms"
        const val KEY_SUCCESS = "last_success_at_ms"
        const val KEY_APPLIED_SEQ = "last_applied_seq"
        const val KEY_STATUS = "status"
        const val KEY_SIGNED_PARAMS_FP = "signed_params_fingerprint"
    }
}

/**
 * 订阅状态读写。所有成功写入都要求「时间不倒退」，避免过期响应覆盖新结果
 * （与 [CatalogRepository] 对 M3U 刷新的既有语义保持一致）。
 */
class HubSubscriptionStore(
    private val persistence: HubSubscriptionPersistence = InMemoryHubSubscriptionPersistence(),
) {
    fun state(): HubSubscriptionState = persistence.read() ?: HubSubscriptionState()

    /** 记录一次拉取尝试；新尝试时间戳更旧时忽略（过期响应不覆盖新结果）。 */
    fun recordAttempt(nowMs: Long): HubSubscriptionState {
        val previous = state()
        if (nowMs < previous.lastAttemptAtMs) return previous
        val updated = previous.copy(lastAttemptAtMs = nowMs, status = HubSubscriptionStatus.UPDATING)
        persistence.write(updated)
        return updated
    }

    /** 记录一次失败；只保存固定状态码，保留上一次成功结果。 */
    fun recordFailure(status: String, nowMs: Long): HubSubscriptionState {
        val previous = state()
        if (nowMs < previous.lastAttemptAtMs) return previous
        val updated = previous.copy(lastAttemptAtMs = nowMs, status = status)
        persistence.write(updated)
        return updated
    }

    /** 记录一次 304：不重建目录，也不改变 etag/payload。 */
    fun recordNotModified(nowMs: Long): HubSubscriptionState {
        val previous = state()
        if (nowMs < previous.lastAttemptAtMs) return previous
        val updated = previous.copy(lastAttemptAtMs = nowMs, status = HubSubscriptionStatus.NOT_MODIFIED)
        persistence.write(updated)
        return updated
    }

    /** 记录一次成功解析。 */
    fun recordSuccess(
        payload: String,
        etag: String?,
        seq: Long,
        nowMs: Long,
        applied: Boolean,
        signedParamsFingerprint: String? = null,
    ): HubSubscriptionState {
        val previous = state()
        if (nowMs < previous.lastAttemptAtMs) return previous
        val updated = previous.copy(
            etag = etag ?: previous.etag,
            seq = seq,
            payload = payload,
            lastAttemptAtMs = nowMs,
            lastSuccessAtMs = nowMs,
            lastAppliedSeq = if (applied) seq else previous.lastAppliedSeq,
            status = HubSubscriptionStatus.SUCCESS,
            // 服务端投递了指纹才更新锚点；未投递（旧版服务端）保留旧值
            signedParamsFingerprint = signedParamsFingerprint ?: previous.signedParamsFingerprint,
        )
        persistence.write(updated)
        return updated
    }

    /** 清空订阅元数据与 payload 文件（升级残留防御）；不碰 Room 目录、EPG 与用户订阅。 */
    fun clear() = persistence.clear()

    /** 保留成功结果、仅推进「已应用」水位（用于播放结束后补应用）。 */
    fun markApplied(seq: Long): HubSubscriptionState {
        val previous = state()
        val updated = previous.copy(lastAppliedSeq = seq)
        persistence.write(updated)
        return updated
    }
}
