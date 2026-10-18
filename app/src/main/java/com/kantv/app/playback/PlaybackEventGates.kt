package com.kantv.app.playback

// 这两个通用判定器原先随旧播放引擎文件定义；单内核化后原样保留，
// 供帧推进判定与播放会话事件隔离的单测与后续引擎复用。

internal class DisplayedFrameProgressGate {
    private var lastDisplayedPictures = -1
    private var lastDecodedVideo = -1

    fun observe(displayedPictures: Int?, decodedVideo: Int? = null): Boolean {
        if (displayedPictures == null && decodedVideo == null) return false
        // Prefer pictures that actually reached the video output. A decoder may keep accepting
        // frames while the user still sees a frozen image; treating that as progress would hide
        // the exact failure this watchdog is meant to detect.
        val progressed = if (displayedPictures != null) {
            lastDisplayedPictures < 0 || displayedPictures > lastDisplayedPictures
        } else {
            decodedVideo != null && (lastDecodedVideo < 0 || decodedVideo > lastDecodedVideo)
        }
        displayedPictures?.let { lastDisplayedPictures = it }
        decodedVideo?.let { lastDecodedVideo = it }
        return progressed
    }

    fun reset() {
        lastDisplayedPictures = -1
        lastDecodedVideo = -1
    }
}

internal class PlaybackSessionEventGate {
    private var pendingSessionId = 0L
    private var eventSessionId = 0L

    fun begin(sessionId: Long) {
        pendingSessionId = sessionId
        eventSessionId = 0L
    }

    fun activatePending() {
        eventSessionId = pendingSessionId
    }

    fun current(): Long = eventSessionId

    fun reset() {
        pendingSessionId = 0L
        eventSessionId = 0L
    }
}

/**
 * Attributes a rendered-first-frame callback to the MediaPeriod that produced it. Media3 may
 * deliver the callback with an empty Timeline while replacing a source, so the mutable current
 * media item is not a safe fallback. Period mappings are discarded on every external session and on
 * every candidate switch ([forgetPeriods]), because the *media item id is the external session id*
 * and therefore cannot distinguish two candidates of the same session.
 */
internal class RenderedFirstFrameSessionGate {
    private var activeSessionId = 0L
    private var sessionStartedRealtimeMs = Long.MAX_VALUE
    private val sessionByPeriodUid = HashMap<Any, Long>()

    fun begin(sessionId: Long, startedRealtimeMs: Long) {
        require(sessionId > 0L && startedRealtimeMs >= 0L)
        activeSessionId = sessionId
        sessionStartedRealtimeMs = startedRealtimeMs
        sessionByPeriodUid.clear()
    }

    fun end() {
        activeSessionId = 0L
        sessionStartedRealtimeMs = Long.MAX_VALUE
        sessionByPeriodUid.clear()
    }

    /**
     * Drops every MediaPeriod mapping while keeping the active session. Called when a new candidate
     * starts inside the same session: a late callback from the replaced candidate's period is then
     * unrecognisable and is dropped instead of being credited to the new candidate.
     */
    fun forgetPeriods() {
        sessionByPeriodUid.clear()
    }

    fun record(periodUid: Any?, sessionId: Long) {
        if (periodUid != null && sessionId == activeSessionId) {
            sessionByPeriodUid[periodUid] = sessionId
        }
    }

    fun sessionFor(periodUid: Any?): Long? =
        periodUid?.let(sessionByPeriodUid::get)?.takeIf { it == activeSessionId }

    fun resolve(
        directSessionId: Long?,
        periodUid: Any?,
        eventRealtimeMs: Long,
    ): Long? {
        if (eventRealtimeMs < sessionStartedRealtimeMs) return null
        val resolved = directSessionId ?: sessionFor(periodUid) ?: return null
        if (resolved != activeSessionId) return null
        record(periodUid, resolved)
        return resolved
    }
}

/**
 * Pure ownership decision for a Media3 analytics callback.
 *
 * Media3 attributes each callback to a window of its timeline. A callback that names a different
 * MediaItem than the one currently playing belongs to a previous open generation — for example the
 * candidate replaced during a same-session source switch — and must not be able to change the
 * current candidate's state. Deriving the answer from Media3's own timeline (rather than from the
 * mutable "current" item) keeps stale callbacks from being credited to the new one.
 *
 * The first-frame path additionally tolerates the documented Media3 case where a genuine rendered
 * first frame arrives with an empty or already-replaced timeline; only then is the previously
 * recorded MediaPeriod ownership consulted.
 */
internal object Media3EventOwnership {
    fun sessionIdFromMediaItemId(mediaItemId: String?): Long? = mediaItemId
        ?.substringBefore(':')
        ?.toLongOrNull()
        ?.takeIf { it != 0L }

    /**
     * True only for a genuine mismatch. A null on either side means "no information" (for example an
     * empty timeline while a source is being replaced), which callers resolve through the session
     * identity instead of dropping the callback.
     */
    fun isFromAnotherMediaItem(
        eventTimelineMediaId: String?,
        currentMediaItemMediaId: String?,
    ): Boolean {
        val eventId = eventTimelineMediaId ?: return false
        val currentId = currentMediaItemMediaId ?: return false
        return eventId != currentId
    }

    fun owningSessionId(
        eventTimelineMediaId: String?,
        currentMediaItemMediaId: String?,
        activeSessionId: Long,
    ): Long? {
        val eventId = eventTimelineMediaId ?: return null
        val currentId = currentMediaItemMediaId ?: return null
        if (eventId != currentId) return null
        return sessionIdFromMediaItemId(eventId)?.takeIf { it == activeSessionId }
    }

    fun firstFrameSessionId(
        owningSessionId: Long?,
        periodUid: Any?,
        eventRealtimeMs: Long,
        gate: RenderedFirstFrameSessionGate,
    ): Long? = owningSessionId ?: gate.resolve(
        directSessionId = null,
        periodUid = periodUid,
        eventRealtimeMs = eventRealtimeMs,
    )
}
