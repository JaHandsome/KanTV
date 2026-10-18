package com.kantv.app.playback

/** Mutually exclusive top-level playback destinations for one catalogue channel. */
sealed interface PlaybackRoute {
    val channelId: String

    data class Native(
        override val channelId: String,
        val requests: List<PlaybackRequest>,
    ) : PlaybackRoute {
        init {
            require(requests.isNotEmpty() && requests.all { it.channelId == channelId }) {
                "Native route requests must belong to one channel"
            }
            require(requests.none { request ->
                SupportedWebLivePage.from(request.uri)?.let { it.officialPlayer || it.playbackBlockedMessage != null } == true
            }) { "Official or blocked webpages cannot enter the native route" }
        }
    }

    data class OfficialWeb(
        override val channelId: String,
        val page: SupportedWebLivePage,
    ) : PlaybackRoute {
        init {
            require(page.officialPlayer && page.playbackBlockedMessage == null) {
                "Official web route requires a playable official page"
            }
        }
    }

    data class Blocked(
        override val channelId: String,
        val message: String,
    ) : PlaybackRoute {
        init { require(message.isNotBlank()) { "Blocked route requires a user-visible reason" } }
    }
}

data class RouteSession(
    val sessionId: Long,
    val routeGeneration: Long,
    val route: PlaybackRoute,
)

class RouteSelectionToken internal constructor(
    internal val selectionGeneration: Long,
    val channelId: String,
)

fun interface PlaybackEngineFactory {
    fun create(): PlaybackEngine
}

/** Classifies public catalogue requests without opening a player or resolving a media URL. */
object PlaybackRouteClassifier {
    const val EMPTY_ROUTE_MESSAGE = "频道数据不可用，请重新导入播放源"

    fun classify(requests: List<PlaybackRequest>, channelIdWhenEmpty: String = ""): PlaybackRoute {
        if (requests.isEmpty()) return PlaybackRoute.Blocked(channelIdWhenEmpty, EMPTY_ROUTE_MESSAGE)
        val channelId = requests.first().channelId
        require(requests.all { it.channelId == channelId }) {
            "All playback sources must belong to the same channel"
        }
        val firstPage = SupportedWebLivePage.from(requests.first().uri)
        firstPage?.playbackBlockedMessage?.let { return PlaybackRoute.Blocked(channelId, it) }
        if (firstPage?.officialPlayer == true) return PlaybackRoute.OfficialWeb(channelId, firstPage)

        val native = requests.filter { request ->
            val page = SupportedWebLivePage.from(request.uri)
            page?.officialPlayer != true && page?.playbackBlockedMessage == null
        }
        return if (native.isEmpty()) PlaybackRoute.Blocked(channelId, EMPTY_ROUTE_MESSAGE)
        else PlaybackRoute.Native(channelId, native)
    }
}

object PlaybackRouteIntent {
    const val EXTRA_SESSION_ID = "playbackRouteSessionId"
    const val EXTRA_ROUTE_GENERATION = "playbackRouteGeneration"

    /** Tier 1 嗅探换轨：官方页容器验证到可播流后随 RESULT_OK 回传的流地址。 */
    const val EXTRA_SNIFFED_STREAM_URI = "playbackRouteSniffedStreamUri"
    const val EXTRA_SNIFFED_STREAM_MIME = "playbackRouteSniffedStreamMime"

    /** 嗅探流的请求头（JSON 对象，仅 Referer/User-Agent 白名单键）。 */
    const val EXTRA_SNIFFED_STREAM_HEADERS = "playbackRouteSniffedStreamHeaders"

    /** 换轨失败回退时置 true：重开官方页容器但禁用嗅探（防循环）。 */
    const val EXTRA_DISABLE_SNIFF = "playbackRouteDisableSniff"
}
