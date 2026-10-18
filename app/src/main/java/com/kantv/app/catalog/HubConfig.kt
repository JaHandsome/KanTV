package com.kantv.app.catalog

import android.content.Context
import com.kantv.app.BuildConfig
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * 枢纽端点与 token 的取值规则。纯函数，便于单测；不含任何 I/O。
 *
 * - 端点来自 `BuildConfig.HUB_DEFAULT_ENDPOINT`（构建期 `KANTV_HUB_ENDPOINT`，缺省为空）。
 * - 回流 token 只来自构建期 `KANTV_HUB_FEEDBACK_TOKEN`，**不得硬编码、不得提交仓库**。
 */
object HubEndpointPolicy {
    const val SUBSCRIPTION_PATH = "/api/hub/v1/subscription"
    const val FEEDBACK_PATH = "/api/hub/v1/feedback"
    const val FEEDBACK_SUMMARY_PATH = "/api/hub/v1/feedback/summary"

    /** 只接受干净的绝对地址：http(s)、有 host、无 userinfo/query/fragment、端口合法。 */
    fun validEndpoint(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return false
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return false
        if (uri.host.isNullOrBlank()) return false
        if (uri.port == 0 || uri.port < -1 || uri.port > 65535) return false
        return uri.path.isNullOrEmpty() || uri.path == "/"
    }

    /** 契约 §4.2：32–128 字符。 */
    fun validToken(value: String?): Boolean = value?.matches(Regex("[A-Za-z0-9_-]{32,128}")) == true

    fun subscriptionUri(endpoint: String, identity: HubClientIdentity, caps: List<String> = HubCaps.declared): String =
        base(endpoint) + SUBSCRIPTION_PATH + "?" + listOf(
            "client" to identity.client,
            "schema" to BuildConfig.HUB_SCHEMA.toString(),
            "caps" to HubCaps.sanitize(caps).joinToString(","),
            "app" to identity.app,
            "device" to identity.device,
        ).joinToString("&") { (key, value) -> "$key=${encode(value)}" }

    fun feedbackUri(endpoint: String): String = base(endpoint) + FEEDBACK_PATH

    private fun base(endpoint: String): String = endpoint.trimEnd('/')

    /** 查询参数只允许契约定义的字符集，统一做一次安全编码。 */
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    /**
     * 设备标识：小写、非字母数字折叠为 '-'，与契约示例 `tcl-q10m-pro` 形状一致。
     * 只使用机型这类非敏感信息，不包含序列号、账号或网络地址。
     */
    fun deviceToken(model: String?): String {
        val normalised = model.orEmpty().lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        return normalised.ifEmpty { "unknown-device" }
    }
}

/**
 * 端点与 token 的运行期配置。两者只由构建期注入；未注入有效端点时枢纽静默关闭，
 * 不沿用历史构建保存在偏好中的端点。
 */
data class HubConfiguration(val endpoint: String, val feedbackToken: String)

object HubConfig {
    private const val PREFS = "hub_config"
    private const val DEFAULTS_APPLIED = "build_defaults_applied"
    private const val DEFAULTS_VERSION_CODE = "build_defaults_version_code"

    fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 把构建期默认端点写进偏好：存储缺失/非法、构建端点或版本号变化时重新应用
     * （端点只有构建期一个来源，没有用户覆盖概念）。token 不落偏好，始终以构建期值为准。
     */
    fun initializeBundledDefaults(context: Context) {
        val prefs = preferences(context)
        val default = BuildConfig.HUB_DEFAULT_ENDPOINT
        if (!shouldReapplyBundledDefaults(
                defaultsApplied = prefs.getBoolean(DEFAULTS_APPLIED, false),
                appliedVersionCode = prefs.getInt(DEFAULTS_VERSION_CODE, 0),
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentEndpoint = prefs.getString("endpoint", null),
                defaultEndpoint = default,
            )) return
        prefs.edit()
            .putString("endpoint", default)
            .putBoolean(DEFAULTS_APPLIED, true)
            .putInt(DEFAULTS_VERSION_CODE, BuildConfig.VERSION_CODE)
            .apply()
    }

    internal fun shouldReapplyBundledDefaults(
        defaultsApplied: Boolean,
        appliedVersionCode: Int,
        currentVersionCode: Int,
        currentEndpoint: String?,
        defaultEndpoint: String,
    ): Boolean {
        if (!HubEndpointPolicy.validEndpoint(defaultEndpoint)) return false
        if (!HubEndpointPolicy.validEndpoint(currentEndpoint)) return true
        return !defaultsApplied || appliedVersionCode != currentVersionCode || currentEndpoint != defaultEndpoint
    }

    fun read(context: Context): HubConfiguration {
        // 通用构建不能因历史偏好而重新访问旧家庭端点；保留偏好，供家庭构建继续使用。
        if (!HubEndpointPolicy.validEndpoint(BuildConfig.HUB_DEFAULT_ENDPOINT)) return HubConfiguration("", "")
        initializeBundledDefaults(context)
        val endpoint = preferences(context).getString("endpoint", null)
            ?.takeIf(HubEndpointPolicy::validEndpoint)
            ?: BuildConfig.HUB_DEFAULT_ENDPOINT.takeIf(HubEndpointPolicy::validEndpoint)
            ?: ""
        val token = BuildConfig.HUB_DEFAULT_TOKEN.takeIf(HubEndpointPolicy::validToken).orEmpty()
        return HubConfiguration(endpoint, token)
    }

    fun hasValidEndpoint(context: Context): Boolean = HubEndpointPolicy.validEndpoint(read(context).endpoint)
}
