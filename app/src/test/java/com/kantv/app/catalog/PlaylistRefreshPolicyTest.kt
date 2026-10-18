package com.kantv.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class PlaylistRefreshPolicyTest {
    private val nowMs = 2 * PlaylistRefreshPolicy.REFRESH_INTERVAL_MS

    @Test fun `refresh is due only when both attempt and success are stale`() {
        assertTrue(PlaylistRefreshPolicy.shouldRefresh(subscription(attempt = 0, success = 0), nowMs))
        assertFalse(PlaylistRefreshPolicy.shouldRefresh(subscription(attempt = nowMs - 1, success = 0), nowMs))
        assertFalse(PlaylistRefreshPolicy.shouldRefresh(subscription(attempt = 0, success = nowMs - 1), nowMs))
        assertFalse(PlaylistRefreshPolicy.shouldRefresh(subscription(attempt = nowMs - 1, success = null), nowMs))
        assertTrue(PlaylistRefreshPolicy.shouldRefresh(subscription(attempt = nowMs - PlaylistRefreshPolicy.REFRESH_INTERVAL_MS, success = 0), nowMs))
    }

    @Test fun `catchup runs when any subscription is due`() {
        assertTrue(PlaylistRefreshPolicy.shouldCatchUp(listOf(subscription(0, 0), subscription(nowMs, nowMs)), nowMs))
        assertFalse(PlaylistRefreshPolicy.shouldCatchUp(listOf(subscription(nowMs, nowMs)), nowMs))
    }

    @Test fun `status mapping is fixed and never includes exception text`() {
        assertEquals(PlaylistRefreshStatus.NETWORK_TIMEOUT, PlaylistRefreshStatus.from(SocketTimeoutException("secret")))
        assertEquals("HTTP_503", PlaylistRefreshStatus.from(PlaylistLoadException.HttpStatus(503)))
        assertEquals(PlaylistRefreshStatus.PLAYLIST_TOO_LARGE, PlaylistRefreshStatus.from(PlaylistLoadException.TooLarge))
    }

    private fun subscription(attempt: Long, success: Long?) = PlaylistSubscriptionEntity(
        id = "test",
        encryptedUrl = "encrypted",
        lastAttemptAtMs = attempt,
        lastSuccessAtMs = success,
        status = "OK",
        channelCount = 1,
    )
}
