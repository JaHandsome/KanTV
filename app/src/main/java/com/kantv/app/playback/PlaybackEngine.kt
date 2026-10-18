package com.kantv.app.playback

interface PlaybackEngine {
    fun setEventListener(listener: (PlaybackEvent) -> Unit)
    fun play(sessionId: Long, request: PlaybackRequest)
    fun reopen(sessionId: Long)
    fun recoverToLive(sessionId: Long)
    fun pause()
    fun resume()
    fun stop()

    /**
     * 换台软停止:复位会话身份(迟到回调被丢弃、预检中止),但保持解码器/渲染链热备,
     * 紧随其后的 [play] 通过替换媒体项直接复用。真实停止(后台/退出/释放)仍走 [stop]。
     * 不能复用管线的引擎保持默认实现即可。
     */
    fun stopForSelectionChange() { stop() }

    fun release()
    fun selectTrack(sessionId: Long, kind: PlaybackTrackKind, selectionId: String?): Boolean = false
    fun setPictureMode(mode: PictureMode) = Unit

    /**
     * Gives a multi-kernel engine one chance to retry the same source with another backend.
     * Single-kernel engines keep the default implementation.
     */
    fun tryAlternative(sessionId: Long, kind: PlaybackErrorKind, errorCode: Int): Boolean = false

    /** Called only after the current source has played continuously for the policy window. */
    fun onPlaybackStable(sessionId: Long) = Unit
}
