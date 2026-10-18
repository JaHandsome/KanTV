package com.kantv.app.playback

object OfficialWebLoginPolicy {
    /** 登录状态的存储位置：两个 Activity 都要读它，所以常量放在这里，不复制字面量。 */
    const val PREFS = "official_web_login"
    const val KEY_VERIFIED_AT = "yicai_last_verified_at_ms"

    private const val DAY_MS = 24L * 60L * 60L * 1_000L

    internal fun needsFirstYicaiLogin(provider: OfficialWebProvider, lastVerifiedAtMs: Long): Boolean =
        provider == OfficialWebProvider.YICAI && lastVerifiedAtMs <= 0L

    internal fun requiresYicaiLogin(
        provider: OfficialWebProvider,
        hasVideo: Boolean,
        loginRequiredSignal: Boolean,
    ): Boolean = provider == OfficialWebProvider.YICAI && !hasVideo && loginRequiredSignal

    internal fun completedYicaiLogin(
        provider: OfficialWebProvider,
        loginFlowActive: Boolean,
        authenticatedUi: Boolean,
    ): Boolean = provider == OfficialWebProvider.YICAI && loginFlowActive && authenticatedUi

    internal fun allowsYicaiLoginHandoff(
        provider: OfficialWebProvider, loginFlowActive: Boolean,
        scheme: String?, host: String?, port: Int, path: String?,
    ): Boolean = provider == OfficialWebProvider.YICAI && loginFlowActive && scheme == "https" &&
        host == "yicai.smgbb.cn" && (port == -1 || port == 443) && path == "/api/setcookie"

    /**
     * 顶层导航放行判定（GeckoView 用；WebView 宿主已于 2026-09-15 删除）。
     *
     * 规则：同 provider 官网域一律放行；**此外只额外放行第一财经的登录回跳白名单**
     * `https://yicai.smgbb.cn/api/setcookie`（与登录流程绑定，且必须 https/默认端口/精确路径）。
     * 其他跨域顶层导航一律拒绝——这是防止官网页把用户带出站的安全边界。
     */
    fun allowsTopLevelNavigation(
        provider: OfficialWebProvider,
        sameProviderPage: Boolean,
        loginFlowActive: Boolean,
        scheme: String?,
        host: String?,
        port: Int,
        path: String?,
    ): Boolean = sameProviderPage ||
        allowsYicaiLoginHandoff(provider, loginFlowActive, scheme, host, port, path)

    /** 是否应在首帧可用后记录"登录已验证"时间戳（第一财经专用，只记一次）。 */
    fun shouldRecordVerifiedLogin(
        provider: OfficialWebProvider,
        lastVerifiedAtMs: Long,
        authenticatedUi: Boolean,
    ): Boolean = provider == OfficialWebProvider.YICAI &&
        lastVerifiedAtMs <= 0L &&
        authenticatedUi

    fun daysSinceLastVerified(lastVerifiedAtMs: Long, nowMs: Long): Long? {
        if (lastVerifiedAtMs <= 0L || nowMs < lastVerifiedAtMs) return null
        return (nowMs - lastVerifiedAtMs) / DAY_MS
    }
}
