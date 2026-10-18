package com.kantv.app.playback

import androidx.media3.common.C
import com.kantv.app.catalog.HubCaps
import com.kantv.app.catalog.HubChannel
import com.kantv.app.catalog.HubClearKey
import com.kantv.app.catalog.HubCoverage
import com.kantv.app.catalog.HubFallback
import com.kantv.app.catalog.HubRoute
import com.kantv.app.catalog.HubRouteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约 §3.1 的 route → 引擎映射、§3 的兜底顺序，以及 ClearKey 两个源码级坑的回归。
 *
 * 全部为纯函数断言：不需要 Android、不需要真实 DRM 会话。
 */
class HubRouteSelectorTest {
    @Test fun `hls ts and flv go to media3`() {
        val plans = HubRouteSelector.plans(channel(routes = listOf(route(HubRouteType.HLS_TS), route(HubRouteType.FLV))))
        assertEquals(2, plans.size)
        plans.forEach {
            assertEquals(HubEngineTarget.MEDIA3, it.target)
        }
    }

    @Test fun `hls aes128 goes to media3`() {
        val plan = HubRouteSelector.plans(channel(routes = listOf(route(HubRouteType.HLS_AES128)))).single()
        assertEquals(HubEngineTarget.MEDIA3, plan.target)
    }

    @Test fun `dash clearkey goes to media3 with an explicit keystore plan`() {
        val plan = HubRouteSelector.plans(channel(routes = listOf(route(HubRouteType.DASH_CLEARKEY)))).single()
        assertEquals(HubEngineTarget.MEDIA3, plan.target)
        assertTrue(plan.requiresClearKey)
        assertNotNull(plan.clearKey)
        assertNotNull(ClearKeyPlaybackSupport.specFor(plan))
    }

    @Test fun `web embed goes to the webview container`() {
        val plan = HubRouteSelector.plans(channel(routes = listOf(route(HubRouteType.WEB_EMBED)))).single()
        assertEquals(HubEngineTarget.WEB_EMBED, plan.target)
        assertNull(plan.mimeType)
    }

    @Test fun `unknown route from the server is never used`() {
        val unknown = route(HubRouteType.HLS_TS).copy(route = HubRouteType.UNKNOWN, rawRoute = "dash-mpegts")
        assertTrue(HubRouteSelector.plans(channel(routes = listOf(unknown))).isEmpty())
    }

    @Test fun `routes outside the declared caps are not delivered to an engine`() {
        val all = channel(routes = HubRouteType.entries.filter { it != HubRouteType.UNKNOWN }.map(::route))
        val hlsOnly = HubRouteSelector.plans(all, caps = listOf(HubCaps.HLS_TS))
        assertEquals(1, hlsOnly.size)
        assertEquals(HubRouteType.HLS_TS, hlsOnly.single().route)
    }

    @Test fun `mime type comes from the declared route not from the url`() {
        // URL 看起来像 HLS，但服务端声明的是 dash-clearkey：必须以服务端 route 为准。
        val mismatched = HubRoute(
            route = HubRouteType.DASH_CLEARKEY,
            rawRoute = "dash-clearkey",
            url = "https://example.invalid/looks-like.m3u8",
            urlKey = KEY,
            container = "dash",
            encryption = "clearkey",
            kid = KID,
            clearkey = HubClearKey(K, KID),
            drmUuid = null,
            quality = "1080p",
            score = 90,
            verifiedAt = null,
        )
        val plan = HubRouteSelector.plans(channel(routes = listOf(mismatched))).single()
        assertEquals(HubRouteType.DASH_CLEARKEY, plan.route)
        assertEquals("application/dash+xml", plan.mimeType)
    }

    @Test fun `routes keep the server score order`() {
        val ordered = HubRouteSelector.plans(
            channel(
                routes = listOf(
                    route(HubRouteType.HLS_TS).copy(score = 10),
                    route(HubRouteType.FLV).copy(score = 99),
                    route(HubRouteType.HLS_AES128).copy(score = 50),
                ),
            ),
        )
        assertEquals(listOf(99, 50, 10), ordered.map { it.score })
    }

    @Test fun `fallback order is server fallback then the local official directory`() {
        val target = channel(
            routes = emptyList(),
            fallback = HubFallback("web-embed", "https://news.tvb.com/tc/live/83"),
        )
        val steps = HubRouteSelector.fallbackSteps(
            channel = target,
            localOfficialPages = listOf("https://tv.cctv.com/live/cctv1/"),
        )
        assertEquals(2, steps.size)
        assertTrue(steps[0] is HubFallbackStep.ServerFallback)
        assertTrue(steps[1] is HubFallbackStep.LocalOfficialWeb)
    }

    @Test fun `fallback is skipped when the server declares no fallback`() {
        val steps = HubRouteSelector.fallbackSteps(channel(routes = emptyList()), emptyList())
        assertTrue(steps.isEmpty())
    }

    @Test fun `fallback route outside caps is skipped`() {
        val target = channel(routes = emptyList(), fallback = HubFallback("hls-ts", "https://example.invalid/live.m3u8"))
        val steps = HubRouteSelector.fallbackSteps(target, caps = listOf(HubCaps.WEB_EMBED))
        assertTrue(steps.isEmpty())
    }

    // ---------- ClearKey：两个源码级坑的回归 ----------

    @Test fun `clearkey uuid matches the media3 constant`() {
        assertEquals(C.CLEARKEY_UUID.toString(), ClearKeyPlaybackSupport.FALLBACK_UUID)
    }

    @Test fun `clearkey uuid is never the widevine default`() {
        assertNotEquals(C.WIDEVINE_UUID.toString(), ClearKeyPlaybackSupport.FALLBACK_UUID)
    }

    @Test fun `server drm uuid wins and falls back to clearkey uuid when absent`() {
        val declared = "1077efec-c0b2-4d02-ace3-3c1e52e2fb4b"
        val withUuid = HubRouteSelector.plans(
            channel(routes = listOf(route(HubRouteType.DASH_CLEARKEY).copy(drmUuid = declared))),
        ).single()
        assertEquals(declared, requireNotNull(ClearKeyPlaybackSupport.specFor(withUuid)).uuid)
        assertNull(ClearKeyPlaybackSupport.defectOf(requireNotNull(ClearKeyPlaybackSupport.specFor(withUuid))))

        val withoutUuid = HubRouteSelector.plans(channel(routes = listOf(route(HubRouteType.DASH_CLEARKEY)))).single()
        assertEquals(
            C.CLEARKEY_UUID.toString(),
            requireNotNull(ClearKeyPlaybackSupport.specFor(withoutUuid)).uuid,
        )
    }

    @Test fun `routes without a server url key are playable but not attributable`() {
        val attributed = HubRouteSelector.plans(channel(routes = listOf(route(HubRouteType.HLS_TS)))).single()
        assertTrue(attributed.attributable)
        assertEquals(KEY, attributed.urlKey)

        val unattributed = HubRouteSelector.plans(
            channel(routes = listOf(route(HubRouteType.HLS_TS).copy(urlKey = null))),
        ).single()
        assertFalse(unattributed.attributable)
        assertNull(unattributed.urlKey)
        // 不可归因不影响可播放性
        assertTrue(unattributed.uri.isNotBlank())
    }

    @Test fun `clearkey spec uses the explicit uuid, provider and setMediaItem`() {
        val plan = HubRouteSelector.plans(channel(routes = listOf(route(HubRouteType.DASH_CLEARKEY)))).single()
        val spec = requireNotNull(ClearKeyPlaybackSupport.specFor(plan))
        assertEquals(ClearKeyPlaybackSupport.FALLBACK_UUID, spec.uuid)
        assertTrue("必须显式 setUuidAndExoMediaDrmProvider", spec.explicitUuidProvider)
        assertTrue("必须挂 setDrmSessionManagerProvider", spec.attachDrmSessionManagerProvider)
        assertTrue("必须用 setMediaItem 而不是 setMediaSource", spec.useMediaItem)
        assertNull(ClearKeyPlaybackSupport.defectOf(spec))
    }

    @Test fun `regression - setMediaSource bypasses the drm provider and is detected`() {
        val plan = HubRouteSelector.plans(channel(routes = listOf(route(HubRouteType.DASH_CLEARKEY)))).single()
        val spec = requireNotNull(ClearKeyPlaybackSupport.specFor(plan))
        assertEquals(
            ClearKeyDefect.MEDIA_SOURCE_BYPASSES_PROVIDER,
            ClearKeyPlaybackSupport.defectOf(spec.copy(useMediaItem = false)),
        )
    }

    @Test fun `regression - implicit widevine uuid is detected`() {
        val plan = HubRouteSelector.plans(channel(routes = listOf(route(HubRouteType.DASH_CLEARKEY)))).single()
        val spec = requireNotNull(ClearKeyPlaybackSupport.specFor(plan))
        assertEquals(
            ClearKeyDefect.IMPLICIT_WIDEVINE_DEFAULT,
            ClearKeyPlaybackSupport.defectOf(spec.copy(explicitUuidProvider = false)),
        )
        assertEquals(
            ClearKeyDefect.IMPLICIT_WIDEVINE_DEFAULT,
            ClearKeyPlaybackSupport.defectOf(spec.copy(uuid = "")),
        )
    }

    @Test fun `regression - missing provider and missing keys are detected`() {
        val plan = HubRouteSelector.plans(channel(routes = listOf(route(HubRouteType.DASH_CLEARKEY)))).single()
        val spec = requireNotNull(ClearKeyPlaybackSupport.specFor(plan))
        assertEquals(
            ClearKeyDefect.PROVIDER_NOT_ATTACHED,
            ClearKeyPlaybackSupport.defectOf(spec.copy(attachDrmSessionManagerProvider = false)),
        )
        assertEquals(ClearKeyDefect.MISSING_KEYS, ClearKeyPlaybackSupport.defectOf(spec.copy(keyResponseJson = "")))
    }

    @Test fun `dash clearkey without inline keys is fail closed and produces no plan`() {
        val keyless = route(HubRouteType.DASH_CLEARKEY).copy(clearkey = null)
        // 客户端最后一道 fail-closed：无内联密钥不产出计划（投递了也只会黑屏）
        assertTrue(HubRouteSelector.plans(channel(routes = listOf(keyless))).isEmpty())
        assertFalse(HubRouteSelector.supports(HubRouteType.DASH_CLEARKEY, emptyList()))
    }

    @Test fun `hex keys are converted to the eme clearkey response`() {
        assertEquals("ABEiM0RVZneImaq7zN3u_w", ClearKeyPlaybackSupport.hexToBase64Url("00112233445566778899aabbccddeeff"))
        assertEquals("qrvM3e7_ABEiM0RVZneImQ", ClearKeyPlaybackSupport.hexToBase64Url("aabbccddeeff00112233445566778899"))
        assertNull(ClearKeyPlaybackSupport.hexToBase64Url("xyz"))
        assertNull(ClearKeyPlaybackSupport.hexToBase64Url("abc"))
    }

    @Test fun `key response follows the eme octet key format`() {
        val json = requireNotNull(ClearKeyPlaybackSupport.keyResponseJson(HubClearKey(K, KID)))
        assertEquals(
            """{"keys":[{"kty":"oct","k":"ABEiM0RVZneImaq7zN3u_w","kid":"qrvM3e7_ABEiM0RVZneImQ"}],"type":"temporary"}""",
            json,
        )
    }

    private fun route(type: HubRouteType): HubRoute = HubRoute(
        route = type,
        rawRoute = type.wireName,
        url = "https://example.invalid/${type.wireName}/index",
        urlKey = KEY,
        container = null,
        encryption = if (type == HubRouteType.DASH_CLEARKEY) "clearkey" else null,
        kid = if (type == HubRouteType.DASH_CLEARKEY) KID else null,
        clearkey = if (type == HubRouteType.DASH_CLEARKEY) HubClearKey(K, KID) else null,
        drmUuid = null,
        quality = "1080p",
        score = 80,
        verifiedAt = null,
    )

    private fun channel(
        routes: List<HubRoute>,
        fallback: HubFallback? = null,
    ): HubChannel = HubChannel(
        channelId = "無線新聞台",
        display = "无线新闻台",
        groupId = "hk_tw",
        logo = null,
        epgId = null,
        coverage = HubCoverage.OK,
        routes = routes,
        fallback = fallback,
    )

    private companion object {
        const val KEY = "0123456789abcdef0123456789abcdef01234567"
        const val K = "00112233445566778899aabbccddeeff"
        const val KID = "aabbccddeeff00112233445566778899"
    }
}
