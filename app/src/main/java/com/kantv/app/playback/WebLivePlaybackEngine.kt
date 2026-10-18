package com.kantv.app.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Resolve again on every open/recovery; stale resolutions and callbacks cannot retune playback. */
class WebLivePlaybackEngine(
    private val delegate: PlaybackEngine,
    private val resolver: WebLiveResolver = TvbLiveResolver(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : PlaybackEngine {
    private var listener: (PlaybackEvent) -> Unit = {}
    private var source: PlaybackRequest? = null
    private var externalId: Long? = null
    private var generation = 0L
    private var job: Job? = null
    private var paused = false
    private var pending: PlaybackRequest? = null

    init {
        delegate.setEventListener { event ->
            val id = externalId
            if (id != null && event.sessionId == generation) listener(event.withSession(id))
        }
    }

    override fun setEventListener(listener: (PlaybackEvent) -> Unit) { this.listener = listener }

    override fun play(sessionId: Long, request: PlaybackRequest) {
        // 软复位:只让旧会话/解析作废,不拆除 delegate 的播放管线;delegate.play() 自身完成复位。
        resetSelectionState()
        source = request
        externalId = sessionId
        paused = false
        val current = generation
        if (!resolver.supports(request.uri)) {
            delegate.play(current, request)
            return
        }
        listener(PlaybackEvent.Loading(sessionId))
        job = scope.launch {
            try {
                val resolved = resolver.resolve(request)
                if (generation != current || externalId != sessionId) return@launch
                if (paused) pending = resolved else delegate.play(current, resolved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == current && externalId == sessionId) {
                    val kind = (error as? WebLiveResolutionException)?.kind ?: PlaybackErrorKind.TEMPORARY_NETWORK
                    listener(PlaybackEvent.Error(sessionId, kind, -10_010))
                }
            }
        }
    }

    override fun reopen(sessionId: Long) {
        if (externalId != sessionId) return
        val request = source ?: return
        if (resolver.supports(request.uri)) play(sessionId, request) else delegate.reopen(generation)
    }

    override fun recoverToLive(sessionId: Long) {
        if (externalId != sessionId) return
        if (source?.let { resolver.supports(it.uri) } == true) reopen(sessionId)
        else delegate.recoverToLive(generation)
    }

    override fun pause() { paused = true; delegate.pause() }
    override fun resume() {
        paused = false
        val resolved = pending
        pending = null
        if (resolved != null && externalId != null) delegate.play(generation, resolved) else delegate.resume()
    }
    override fun stop() {
        resetSelectionState()
        delegate.stop()
    }
    override fun stopForSelectionChange() {
        resetSelectionState()
        delegate.stopForSelectionChange()
    }
    private fun resetSelectionState() {
        generation++
        externalId = null
        source = null
        pending = null
        job?.cancel()
        job = null
    }
    override fun release() { stop(); scope.cancel(); delegate.release() }
    override fun setPictureMode(mode: PictureMode) = delegate.setPictureMode(mode)
    override fun selectTrack(sessionId: Long, kind: PlaybackTrackKind, selectionId: String?): Boolean =
        externalId == sessionId && delegate.selectTrack(generation, kind, selectionId)
    override fun tryAlternative(sessionId: Long, kind: PlaybackErrorKind, errorCode: Int): Boolean =
        externalId == sessionId && delegate.tryAlternative(generation, kind, errorCode)
    override fun onPlaybackStable(sessionId: Long) {
        if (externalId == sessionId) delegate.onPlaybackStable(generation)
    }

    private fun PlaybackEvent.withSession(id: Long): PlaybackEvent = when (this) {
        is PlaybackEvent.Loading -> copy(sessionId = id)
        is PlaybackEvent.Ready -> copy(sessionId = id)
        is PlaybackEvent.FirstFrame -> copy(sessionId = id)
        is PlaybackEvent.VideoProgress -> copy(sessionId = id)
        is PlaybackEvent.Technical -> copy(sessionId = id)
        is PlaybackEvent.Ended -> copy(sessionId = id)
        is PlaybackEvent.Error -> copy(sessionId = id)
    }
}
