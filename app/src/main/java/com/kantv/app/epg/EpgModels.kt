package com.kantv.app.epg

import com.kantv.app.catalog.ChannelEntity
import com.kantv.app.catalog.ChannelIdentity
import com.kantv.app.playback.PlaybackRequest

data class EpgChannelRef(
    val channelId: String,
    val tvgId: String?,
    val name: String,
    val aliases: Set<String> = emptySet(),
    val identityKey: String? = null,
)

fun ChannelEntity.toEpgChannelRef(): EpgChannelRef = EpgChannelRef(
    channelId = id,
    tvgId = tvgId,
    name = name,
    identityKey = identityKey ?: ChannelIdentity.knownKey(tvgId, name),
)

enum class EpgProgrammeState { PAST, CURRENT, FUTURE }

data class EpgProgramme(
    val id: String,
    val startAtMs: Long,
    val endAtMs: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val categories: List<String> = emptyList(),
    /** Null unless a provider has supplied an explicitly verified replay media request. */
    val catchUpPlayback: PlaybackRequest? = null,
) {
    init {
        require(endAtMs > startAtMs) { "programme end must be after start" }
    }

    fun stateAt(nowMs: Long): EpgProgrammeState = when {
        nowMs < startAtMs -> EpgProgrammeState.FUTURE
        nowMs >= endAtMs -> EpgProgrammeState.PAST
        else -> EpgProgrammeState.CURRENT
    }

    fun canReplayAt(nowMs: Long): Boolean =
        stateAt(nowMs) == EpgProgrammeState.PAST && catchUpPlayback != null

    override fun toString(): String =
        "EpgProgramme(id=$id, startAtMs=$startAtMs, endAtMs=$endAtMs, title=$title, hasCatchUp=${catchUpPlayback != null})"
}

data class EpgProgrammeWindow(
    val previous: EpgProgramme?,
    val current: EpgProgramme?,
    val next: EpgProgramme?,
)

data class EpgChannelSchedule(
    val channel: EpgChannelRef,
    val feedChannelId: String,
    val programmes: List<EpgProgramme>,
) {
    fun window(atMs: Long): EpgProgrammeWindow {
        if (programmes.isEmpty()) return EpgProgrammeWindow(null, null, null)
        val firstAfter = programmes.binarySearchBy(atMs) { it.startAtMs }
            .let { if (it >= 0) it else -it - 1 }
        val currentIndex = when {
            firstAfter < programmes.size && atMs >= programmes[firstAfter].startAtMs && atMs < programmes[firstAfter].endAtMs -> firstAfter
            firstAfter > 0 && atMs < programmes[firstAfter - 1].endAtMs -> firstAfter - 1
            else -> -1
        }
        val previousIndex = if (currentIndex >= 0) currentIndex - 1 else firstAfter - 1
        val nextIndex = if (currentIndex >= 0) currentIndex + 1 else firstAfter
        return EpgProgrammeWindow(
            previous = programmes.getOrNull(previousIndex),
            current = programmes.getOrNull(currentIndex),
            next = programmes.getOrNull(nextIndex),
        )
    }
}

enum class EpgSourceState { NETWORK, CACHE, STALE_CACHE, UNAVAILABLE }

enum class EpgFailureCode { NETWORK, HTTP_STATUS, TOO_LARGE, INVALID_DATA, CACHE_IO }

data class EpgLoadStatus(
    val state: EpgSourceState,
    val lastSuccessAtMs: Long?,
    val attemptedAtMs: Long,
    val failure: EpgFailureCode? = null,
)

data class EpgMatchStats(
    val requestedChannels: Int,
    val matchedChannels: Int,
    val programmeCount: Int,
)

data class EpgGuide(
    val schedules: Map<String, EpgChannelSchedule>,
    val status: EpgLoadStatus,
    val matchStats: EpgMatchStats,
) {
    fun schedule(channelId: String): EpgChannelSchedule? = schedules[channelId]

    fun window(channelId: String, atMs: Long): EpgProgrammeWindow =
        schedules[channelId]?.window(atMs) ?: EpgProgrammeWindow(null, null, null)
}
