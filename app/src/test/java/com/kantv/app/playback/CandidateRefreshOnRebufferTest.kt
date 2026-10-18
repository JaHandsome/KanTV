package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放中、未失败的会话也要能用上最新阵容。
 *
 * 2026-09-24 的故障是"每 15~20 秒自愈一次的短缓冲"循环：自愈成功意味着永远不进
 * `PlaybackUiState.Recovering`，于是开播时的单源阵容整场锁死，订阅在播放期间刷新出
 * 新阵容也学不进去，`switchToNextSource()` 形同虚设。
 *
 * 这里按宿主 `MainActivity` 的接线形状组装：计入的缓冲区间 → 节流闸门 → 读最新阵容 →
 * `updateCandidates`。正在播放的线路始终不动（由 `updateCandidates` 语义保证）。
 */
class CandidateRefreshOnRebufferTest {
    private var nowMs = 0L
    private val throttle = CandidateRefreshThrottle()
    private val catalogueReads = mutableListOf<List<PlaybackRequest>>()
    private val mergeResults = mutableListOf<Boolean>()
    private val states = mutableListOf<PlaybackUiState>()
    private var lineup: List<PlaybackRequest> = emptyList()

    private lateinit var coordinator: PlaybackCoordinator

    private fun start(engine: FakeEngine, scheduler: FakeScheduler) {
        coordinator = PlaybackCoordinator(
            engineFactory = PlaybackEngineFactory { engine },
            scheduler = scheduler,
            diagnostics = PlaybackDiagnostics(elapsedRealtimeMs = { nowMs }),
            elapsedRealtimeMs = { nowMs },
            onStateChanged = states::add,
            onFirstFrame = {},
            onRebufferCounted = { refreshFromCatalogue() },
        )
    }

    /** 与 `MainActivity.refreshPlaybackCandidates` 同形：先过节流，再读阵容，再合并候选。 */
    private fun refreshFromCatalogue() {
        if (!throttle.shouldRefresh(nowMs)) return
        catalogueReads += lineup
        mergeResults += coordinator.updateCandidates(lineup)
    }

    private fun request(source: String) = PlaybackRequest("one", "https://example.invalid/$source")

    private fun advance(ms: Long) {
        nowMs += ms
    }

    /** 一次被计入的缓冲区间：首帧之后加载 2 秒再恢复（够长于 600ms 下限，且在首帧宽限之外）。 */
    private fun countedRebuffer(engine: FakeEngine, session: Long) {
        engine.emit(PlaybackEvent.Loading(session))
        advance(2_000)
        engine.emit(PlaybackEvent.Ready(session))
    }

    @Test
    fun suspendedRefreshCannotApplyAfterSwitchingAwayAndBackToTheSameChannel() {
        val engine = FakeEngine()
        start(engine, FakeScheduler())
        coordinator.select(listOf(request("a")))
        val refreshToken = coordinator.currentSelectionToken()!!
        coordinator.select(PlaybackRequest("two", "https://example.invalid/two"))
        coordinator.select(listOf(request("new-a")))
        assertFalse(coordinator.updateCandidates(refreshToken, listOf(request("stale"))))
        assertEquals(1, coordinator.sourceCount)
        assertFalse(coordinator.switchToNextSource())
        val current = coordinator.currentSelectionToken()!!
        assertTrue(coordinator.updateCandidates(current, listOf(request("fresh"))))
        assertTrue(coordinator.switchToNextSource())
        assertEquals("https://example.invalid/fresh", engine.playedRequests.last().uri)
    }

    @Test
    fun candidatesForAnotherChannelCannotEnterTheActiveRoute() {
        val engine = FakeEngine()
        start(engine, FakeScheduler())
        coordinator.select(listOf(request("a")))
        assertFalse(coordinator.updateCandidates(listOf(PlaybackRequest("two", "https://example.invalid/two"))))
        assertFalse(coordinator.updateCandidates(listOf(request("b"), PlaybackRequest("two", "https://example.invalid/two"))))
        assertEquals(1, coordinator.sourceCount)
        assertFalse(coordinator.switchToNextSource())
    }

    @Test
    fun aCountedRebufferRefreshesTheLineupSoTheNextSwitchUsesIt() {
        val engine = FakeEngine()
        start(engine, FakeScheduler())
        lineup = listOf(request("a"))
        coordinator.select(lineup)
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        advance(20_000)

        // 枢纽在播放期间上新了第二条线路：单源会话必须学到它，否则整场停在 a 上。
        lineup = listOf(request("a"), request("b"))
        countedRebuffer(engine, session)

        assertEquals(1, catalogueReads.size)
        assertEquals(2, coordinator.sourceCount)
        assertTrue(coordinator.switchToNextSource())
        assertEquals("https://example.invalid/b", engine.playedRequests.last().uri)
    }

    @Test
    fun aRebufferInsideTheThrottleWindowDoesNotReadTheCatalogueAgain() {
        val engine = FakeEngine()
        start(engine, FakeScheduler())
        lineup = listOf(request("a"))
        coordinator.select(lineup)
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        advance(20_000)

        // 第一次计入的缓冲放行（t=22s），也是这个循环里唯一需要读库的一次。
        countedRebuffer(engine, session)
        assertEquals(1, catalogueReads.size)

        // 短缓冲循环继续：窗口内的事件仍然到达回调，但不再读库。
        advance(5_000)
        countedRebuffer(engine, session)
        advance(5_000)
        countedRebuffer(engine, session)
        assertEquals(1, catalogueReads.size)

        // 距上一次放行满 30 秒，重新放行一次。
        advance(25_000)
        countedRebuffer(engine, session)
        assertEquals(2, catalogueReads.size)
    }

    @Test
    fun anUnchangedLineupNeverDisturbsThePlayingSource() {
        val engine = FakeEngine()
        start(engine, FakeScheduler())
        lineup = listOf(request("a"), request("b"))
        coordinator.select(lineup)
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        advance(20_000)

        countedRebuffer(engine, session)

        // 阵容没变 → updateCandidates 返回 false，正在播的那条线既不重起也不换线。
        assertEquals(1, catalogueReads.size)
        assertFalse(mergeResults.single())
        assertEquals(1, engine.playedRequests.size)
        assertEquals(session, engine.lastSession)
        assertEquals(1, coordinator.sourceIndex)
        assertEquals(2, coordinator.sourceCount)
        assertEquals("只有开播那一次 Loading，没有为刷新重起会话", 1, states.count { it is PlaybackUiState.Loading })
    }

    @Test
    fun aFreshLineupKeepsThePlayingLineFirstAndOnlyAppendsNewOnes() {
        val engine = FakeEngine()
        start(engine, FakeScheduler())
        lineup = listOf(request("a"), request("b"))
        coordinator.select(lineup)
        val session = engine.lastSession
        engine.emit(PlaybackEvent.FirstFrame(session))
        advance(20_000)

        // 自动换到第二条线：会话身份换了，重新等首帧。
        coordinator.switchToNextSource()
        assertEquals("https://example.invalid/b", engine.playedRequests.last().uri)
        engine.emit(PlaybackEvent.FirstFrame(engine.lastSession))
        advance(20_000)

        // 新阵容把正在播的 b 排到了前面，并新增 c：播放中的那条线必须留在原位（不重起），
        // 但下一次切线按新阵容走。
        lineup = listOf(request("b"), request("c"))
        countedRebuffer(engine, engine.lastSession)

        assertTrue(mergeResults.single())
        assertEquals(2, coordinator.sourceCount)
        assertEquals(1, coordinator.sourceIndex)
        assertEquals("刷新不得重起正在播的线路", 1, engine.playedRequests.count { it.uri.endsWith("/b") })
        assertTrue(coordinator.switchToNextSource())
        assertEquals("https://example.invalid/c", engine.playedRequests.last().uri)
    }
}
