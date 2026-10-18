package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRouteTest {
    @Test
    fun nativeDiagnosticGateRejectsOldWebStoppedAndSelectingRoutes() {
        val coordinator = coordinator(factory = PlaybackEngineFactory { RouteRecordingEngine() })
        val first = coordinator.selectRoute(PlaybackRoute.Native("one", listOf(request("one"))))
        assertTrue(coordinator.acceptsNativeDiagnostics(first.sessionId))
        assertFalse(coordinator.acceptsNativeDiagnostics(first.sessionId + 999))
        val selecting = coordinator.beginSelection("two")
        assertFalse(coordinator.acceptsNativeDiagnostics(first.sessionId))
        val second = coordinator.selectRequests(selecting, listOf(request("two")))!!
        assertTrue(coordinator.acceptsNativeDiagnostics(second.sessionId))
        assertFalse(coordinator.acceptsNativeDiagnostics(first.sessionId))
        val web = coordinator.selectRoute(officialRoute("web"))
        assertFalse(coordinator.acceptsNativeDiagnostics(second.sessionId))
        assertFalse(coordinator.acceptsNativeDiagnostics(web.sessionId))
        val third = coordinator.selectRoute(PlaybackRoute.Native("three", listOf(request("three"))))
        assertTrue(coordinator.acceptsNativeDiagnostics(third.sessionId))
        coordinator.stop()
        assertFalse(coordinator.acceptsNativeDiagnostics(third.sessionId))
        assertFalse(coordinator.acceptsNativeDiagnostics(0))
    }

    @Test
    fun classifierProducesOneRouteAndNeverPassesOfficialPagesToNative() {
        val native = request("one", "https://media.example.invalid/live.m3u8")
        val official = request("one", "https://www.yangshipin.cn/tv/home#kantv-cctv1")

        val nativeFirst = PlaybackRouteClassifier.classify(listOf(native, official))
        assertTrue(nativeFirst is PlaybackRoute.Native)
        assertEquals(listOf(native), (nativeFirst as PlaybackRoute.Native).requests)

        val webFirst = PlaybackRouteClassifier.classify(listOf(official, native))
        assertTrue(webFirst is PlaybackRoute.OfficialWeb)
        assertEquals("official-ysp-cctv1", (webFirst as PlaybackRoute.OfficialWeb).page.tvgId)

        val empty = PlaybackRouteClassifier.classify(emptyList(), "missing")
        assertEquals(
            PlaybackRoute.Blocked("missing", PlaybackRouteClassifier.EMPTY_ROUTE_MESSAGE),
            empty,
        )
    }

    @Test
    fun blockedRouteCreatesNoRuntimeAndLaunchesNothing() {
        var factories = 0
        var launches = 0
        var blocked = 0
        val coordinator = coordinator(
            factory = PlaybackEngineFactory { factories++; RouteRecordingEngine() },
            onWeb = { _, _ -> launches++ },
            onBlocked = { _, _ -> blocked++ },
        )

        coordinator.selectRoute(PlaybackRoute.Blocked("one", "不可播放"))

        assertEquals(0, factories)
        assertEquals(0, launches)
        assertEquals(1, blocked)
    }

    @Test
    fun nativeToWebStopsRuntimeBeforeLaunchAndKeepsItForReuse() {
        val order = mutableListOf<String>()
        val runtime = RouteRecordingEngine(order)
        val coordinator = coordinator(
            factory = PlaybackEngineFactory { order += "create"; runtime },
            onWeb = { _, _ -> order += "launch" },
        )
        coordinator.selectRoute(PlaybackRoute.Native("one", listOf(request("one"))))

        coordinator.selectRoute(officialRoute("web"))

        // 换台先软停止(假引擎经默认实现记为 stop),官网分支再补一次硬停止;
        // 引擎本身被保留供网页解析回流后复用,不再 release。
        assertEquals(listOf("create", "stop", "stop", "launch"), order)
    }

    @Test
    fun webToNativeRejectsOldActivityResult() {
        val runtimes = mutableListOf<RouteRecordingEngine>()
        val coordinator = coordinator(factory = PlaybackEngineFactory {
            RouteRecordingEngine().also(runtimes::add)
        })
        val web = coordinator.selectRoute(officialRoute("web"))

        val native = coordinator.selectRoute(PlaybackRoute.Native("native", listOf(request("native"))))

        assertFalse(coordinator.acceptsOfficialWebResult(web.sessionId, web.routeGeneration))
        assertFalse(coordinator.acceptsOfficialWebResult(native.sessionId, native.routeGeneration))
        assertEquals(1, runtimes.size)
    }

    @Test
    fun newerNativeSelectionRejectsOldCoroutineAndEngineCallback() {
        val runtimes = mutableListOf<RouteRecordingEngine>()
        val saved = mutableListOf<String>()
        val states = mutableListOf<PlaybackUiState>()
        val coordinator = coordinator(
            factory = PlaybackEngineFactory { RouteRecordingEngine().also(runtimes::add) },
            states = states,
            onFirstFrame = saved::add,
        )
        val firstSelection = coordinator.beginSelection("a")
        val first = requireNotNull(coordinator.selectRequests(firstSelection, listOf(request("a"))))
        val oldRuntime = runtimes.single()
        val secondSelection = coordinator.beginSelection("b")
        requireNotNull(coordinator.selectRequests(secondSelection, listOf(request("b"))))
        val stateCount = states.size

        oldRuntime.emit(PlaybackEvent.FirstFrame(first.sessionId))

        assertTrue(saved.isEmpty())
        assertEquals(stateCount, states.size)
        assertNull(coordinator.selectRequests(firstSelection, listOf(request("a"))))
        // 引擎跨换台保留:第二次选择复用同一实例,靠会话号拒绝旧回调。
        assertEquals(1, runtimes.size)
    }

    @Test
    fun repeatedReleaseReleasesOneRuntimeOnce() {
        val runtime = RouteRecordingEngine()
        val coordinator = coordinator(factory = PlaybackEngineFactory { runtime })
        coordinator.selectRoute(PlaybackRoute.Native("one", listOf(request("one"))))

        coordinator.release()
        coordinator.release()

        assertEquals(1, runtime.releaseCount)
        assertEquals(1, runtime.stopCount)
    }

    private fun coordinator(
        factory: PlaybackEngineFactory,
        states: MutableList<PlaybackUiState> = mutableListOf(),
        onFirstFrame: (String) -> Unit = {},
        onWeb: (RouteSession, PlaybackRoute.OfficialWeb) -> Unit = { _, _ -> },
        onBlocked: (RouteSession, PlaybackRoute.Blocked) -> Unit = { _, _ -> },
    ) = PlaybackCoordinator(
        engineFactory = factory,
        scheduler = RouteScheduler(),
        diagnostics = PlaybackDiagnostics(),
        elapsedRealtimeMs = { 0L },
        onStateChanged = states::add,
        onFirstFrame = onFirstFrame,
        onOfficialWeb = onWeb,
        onBlocked = onBlocked,
    )

    private fun officialRoute(channelId: String): PlaybackRoute.OfficialWeb {
        val page = requireNotNull(SupportedWebLivePage.from("https://www.yangshipin.cn/tv/home#kantv-cctv1"))
        return PlaybackRoute.OfficialWeb(channelId, page)
    }

    private fun request(channelId: String, uri: String = "https://media.example.invalid/live.m3u8") =
        PlaybackRequest(channelId, uri)
}

private class RouteRecordingEngine(private val order: MutableList<String>? = null) : PlaybackEngine {
    private var listener: (PlaybackEvent) -> Unit = {}
    var stopCount = 0
    var releaseCount = 0
    override fun setEventListener(listener: (PlaybackEvent) -> Unit) { this.listener = listener }
    override fun play(sessionId: Long, request: PlaybackRequest) = Unit
    override fun reopen(sessionId: Long) = Unit
    override fun recoverToLive(sessionId: Long) = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun stop() { stopCount++; order?.add("stop") }
    override fun release() { releaseCount++; order?.add("release") }
    fun emit(event: PlaybackEvent) = listener(event)
}

private class RouteScheduler : RetryScheduler {
    override fun schedule(delayMs: Long, action: () -> Unit) = Unit
    override fun cancelAll() = Unit
}
