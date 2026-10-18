package com.kantv.app.playback

/**
 * Single-kernel playback engine: every external session runs through Media3 while retaining one
 * stable session identifier for the coordinator. Each open receives a fresh private session id,
 * which prevents late callbacks from a stopped session from changing the visible session.
 */
class AdaptivePlaybackEngine(
    private val media3Factory: PlaybackEngineFactory,
    private val profileStore: SourcePlaybackProfileStore,
    private val onKernelChanged: (PlaybackKernel) -> Unit = {},
) : PlaybackEngine {
    private var listener: (PlaybackEvent) -> Unit = {}
    private var active: ActiveSession? = null
    private var nextInternalSessionId = 1L
    private val engines = mutableMapOf<PlaybackKernel, PlaybackEngine>()
    private var pictureMode = PictureMode.FILL
    private var released = false

    override fun setEventListener(listener: (PlaybackEvent) -> Unit) {
        this.listener = listener
    }

    override fun play(sessionId: Long, request: PlaybackRequest) {
        check(!released) { "Playback engine has been released" }
        val fingerprint = SourcePlaybackProfileStore.fingerprint(request)
        val learned = profileStore.profileFor(fingerprint)?.preferredKernel
        // Media3 is the only kernel; the learned preference is retained as historical evidence.
        val preferred = PlaybackKernel.MEDIA3
        active = null
        val retainedKernel = engines.keys.singleOrNull()
        // 保留的引擎不再先 stop():新 play() 自身完成会话状态复位,省去管线拆除/重建。
        if (retainedKernel != preferred) releaseAllEngines()
        start(
            sessionId,
            request,
            fingerprint,
            preferred,
            reason = when {
                request.preferredKernel == PlaybackKernel.MEDIA3 -> KernelSelectionReason.SOURCE_COMPATIBILITY
                learned == PlaybackKernel.MEDIA3 -> KernelSelectionReason.LEARNED_PREFERENCE
                else -> KernelSelectionReason.DEFAULT
            },
        )
    }

    override fun reopen(sessionId: Long) {
        active?.takeIf { it.externalSessionId == sessionId }?.let(::restart)
    }

    override fun recoverToLive(sessionId: Long) {
        active?.takeIf { it.externalSessionId == sessionId }?.let(::restart)
    }

    override fun tryAlternative(sessionId: Long, kind: PlaybackErrorKind, errorCode: Int): Boolean {
        // Single-kernel engine: no alternative backend exists.
        return false
    }

    override fun onPlaybackStable(sessionId: Long) {
        active?.takeIf { it.externalSessionId == sessionId }?.let { session ->
            profileStore.recordSuccess(session.fingerprint, session.kernel)
        }
    }

    override fun selectTrack(sessionId: Long, kind: PlaybackTrackKind, selectionId: String?): Boolean {
        val session = active?.takeIf { it.externalSessionId == sessionId } ?: return false
        return engineFor(session.kernel).selectTrack(session.internalSessionId, kind, selectionId)
    }

    override fun setPictureMode(mode: PictureMode) {
        pictureMode = mode
        engines.values.forEach { it.setPictureMode(mode) }
    }

    override fun pause() {
        active?.let { engineFor(it.kernel).pause() }
    }

    override fun resume() {
        active?.let { engineFor(it.kernel).resume() }
    }

    override fun stop() {
        active = null
        engines.values.singleOrNull()?.stop()
    }

    override fun stopForSelectionChange() {
        // 会话作废即可;保留下来的引擎不在换台时拆除管线。
        active = null
        engines.values.singleOrNull()?.stopForSelectionChange()
    }

    override fun release() {
        if (released) return
        released = true
        active = null
        releaseAllEngines()
    }

    private fun start(
        sessionId: Long,
        request: PlaybackRequest,
        fingerprint: String,
        kernel: PlaybackKernel,
        reason: KernelSelectionReason,
    ) {
        val internalSessionId = nextInternalSessionId++
        active = ActiveSession(sessionId, internalSessionId, request, fingerprint, kernel)
        onKernelChanged(kernel)
        listener(
            PlaybackEvent.Technical(
                sessionId,
                PlaybackTechnicalUpdate.KernelSelected(kernel, reason),
            ),
        )
        listener(PlaybackEvent.Technical(
            sessionId,
            PlaybackTechnicalUpdate.RuntimeGeneration(internalSessionId),
        ))
        listener(PlaybackEvent.Loading(sessionId))
        engineFor(kernel).play(internalSessionId, request)
    }

    private fun handleKernelEvent(kernel: PlaybackKernel, event: PlaybackEvent) {
        val session = active ?: return
        if (kernel != session.kernel || event.sessionId != session.internalSessionId) return
        listener(event.forExternalSession(session.externalSessionId))
    }

    private fun restart(session: ActiveSession) {
        active = null
        // 不再先 stop():同一引擎紧跟着收到 play(),由它完成会话状态复位。
        start(
            sessionId = session.externalSessionId,
            request = session.request,
            fingerprint = session.fingerprint,
            kernel = session.kernel,
            reason = KernelSelectionReason.MANUAL_REOPEN,
        )
    }

    private fun PlaybackEvent.forExternalSession(externalSessionId: Long): PlaybackEvent = when (this) {
        is PlaybackEvent.Loading -> PlaybackEvent.Loading(externalSessionId)
        is PlaybackEvent.Ready -> PlaybackEvent.Ready(externalSessionId)
        is PlaybackEvent.FirstFrame -> PlaybackEvent.FirstFrame(externalSessionId)
        is PlaybackEvent.VideoProgress -> PlaybackEvent.VideoProgress(externalSessionId, presentationTimeUs)
        is PlaybackEvent.Technical -> PlaybackEvent.Technical(externalSessionId, update)
        is PlaybackEvent.Ended -> PlaybackEvent.Ended(externalSessionId)
        is PlaybackEvent.Error -> PlaybackEvent.Error(externalSessionId, kind, errorCode)
    }

    private fun engineFor(kernel: PlaybackKernel): PlaybackEngine = engines.getOrPut(kernel) {
        check(engines.isEmpty()) { "Only one playback kernel may be retained" }
        val engine = when (kernel) {
            PlaybackKernel.MEDIA3 -> media3Factory.create()
        }
        engine.setPictureMode(pictureMode)
        engine.setEventListener { event -> handleKernelEvent(kernel, event) }
        engine
    }

    private fun releaseAllEngines() {
        val retained = engines.toMap()
        engines.clear()
        retained.values.forEach { engine ->
            try {
                engine.stop()
            } finally {
                engine.release()
            }
        }
    }

    internal fun retainedEngineCountForTest(): Int = engines.size

    private data class ActiveSession(
        val externalSessionId: Long,
        val internalSessionId: Long,
        val request: PlaybackRequest,
        val fingerprint: String,
        val kernel: PlaybackKernel,
    )
}
