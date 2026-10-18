package com.kantv.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第一财经登录策略。
 *
 * 存在理由：2026-09-15 删除 WebView 宿主后，登录流程必须在 GeckoView 宿主上重建；
 * 其中**登录回跳白名单是安全边界**——只允许 `https://yicai.smgbb.cn/api/setcookie`，
 * 其余跨域顶层导航一律拒绝，防止官网页把用户带出站。
 */
class OfficialWebLoginPolicyTest {
    @Test
    fun sameProviderPageIsAlwaysAllowed() {
        assertTrue(
            OfficialWebLoginPolicy.allowsTopLevelNavigation(
                OfficialWebProvider.YANGSHIPIN, sameProviderPage = true, loginFlowActive = false,
                scheme = "https", host = "www.yangshipin.cn", port = -1, path = "/tv/home",
            ),
        )
    }

    @Test
    fun yicaiCookieHandoffIsAllowedOnlyDuringTheLoginFlow() {
        assertTrue(
            "登录流程中的回跳白名单必须放行，否则登录永远走不完",
            OfficialWebLoginPolicy.allowsTopLevelNavigation(
                OfficialWebProvider.YICAI, sameProviderPage = false, loginFlowActive = true,
                scheme = "https", host = "yicai.smgbb.cn", port = -1, path = "/api/setcookie",
            ),
        )
        assertFalse(
            "非登录流程不得放行该域",
            OfficialWebLoginPolicy.allowsTopLevelNavigation(
                OfficialWebProvider.YICAI, sameProviderPage = false, loginFlowActive = false,
                scheme = "https", host = "yicai.smgbb.cn", port = -1, path = "/api/setcookie",
            ),
        )
    }

    @Test
    fun handoffWhitelistRejectsLookalikesAndWrongShape() {
        val cases = listOf(
            Triple("https", "yicai.smgbb.cn.evil.test", "/api/setcookie"),
            Triple("https", "evil.test", "/api/setcookie"),
            Triple("http", "yicai.smgbb.cn", "/api/setcookie"),
            Triple("https", "yicai.smgbb.cn", "/api/setcookie/extra"),
            Triple("https", "yicai.smgbb.cn", "/api/other"),
        )
        cases.forEach { (scheme, host, path) ->
            assertFalse(
                "不得放行：$scheme://$host$path",
                OfficialWebLoginPolicy.allowsTopLevelNavigation(
                    OfficialWebProvider.YICAI, sameProviderPage = false, loginFlowActive = true,
                    scheme = scheme, host = host, port = -1, path = path,
                ),
            )
        }
        assertFalse(
            "非默认端口不放行",
            OfficialWebLoginPolicy.allowsTopLevelNavigation(
                OfficialWebProvider.YICAI, sameProviderPage = false, loginFlowActive = true,
                scheme = "https", host = "yicai.smgbb.cn", port = 8443, path = "/api/setcookie",
            ),
        )
    }

    @Test
    fun otherProvidersNeverGetTheYicaiHandoff() {
        assertFalse(
            OfficialWebLoginPolicy.allowsTopLevelNavigation(
                OfficialWebProvider.GDTV, sameProviderPage = false, loginFlowActive = true,
                scheme = "https", host = "yicai.smgbb.cn", port = -1, path = "/api/setcookie",
            ),
        )
    }

    @Test
    fun verifiedLoginIsRecordedOnlyOnceAndOnlyWithAuthenticatedUi() {
        assertTrue(
            OfficialWebLoginPolicy.shouldRecordVerifiedLogin(
                OfficialWebProvider.YICAI, lastVerifiedAtMs = 0L, authenticatedUi = true),
        )
        assertFalse(
            "已记录过就不再重复写",
            OfficialWebLoginPolicy.shouldRecordVerifiedLogin(
                OfficialWebProvider.YICAI, lastVerifiedAtMs = 1_700_000_000_000L, authenticatedUi = true),
        )
        assertFalse(
            "没有认证 UI 证据不得记录",
            OfficialWebLoginPolicy.shouldRecordVerifiedLogin(
                OfficialWebProvider.YICAI, lastVerifiedAtMs = 0L, authenticatedUi = false),
        )
        assertFalse(
            "非第一财经不记录",
            OfficialWebLoginPolicy.shouldRecordVerifiedLogin(
                OfficialWebProvider.GDTV, lastVerifiedAtMs = 0L, authenticatedUi = true),
        )
    }
}
