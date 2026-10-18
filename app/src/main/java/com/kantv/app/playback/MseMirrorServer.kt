package com.kantv.app.playback

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地 MSE 镜像服务器（央视系加扰流的「恢复播放」通道）。
 *
 * 数据流：隐藏 WebView 内官方播放器（自带 WASM 解密）-> SourceBuffer.appendBuffer 钩子
 * 桥回传干净 fMP4 -> [offer] 写入本服务器 -> 本地合成双轨 fMP4-HLS -> Media3 原生渲染。
 *
 * 线程纪律：[offer] 可从任意线程调用（内部加锁）；HTTP 处理在专用线程池。
 * 隐私纪律：日志只记计数与状态，不记来源 URL 与媒体字节。
 */
class MseMirrorServer(
    /** 诊断日志（只记路径与计数，不记 URL/媒体字节）；测试可注入。 */
    private val debugLog: (String) -> Unit = {},
) : Closeable {

    private class TrackState {
        var initSegment: ByteArray? = null
        /** init 内容变更即递增（页面切线/换清晰度）；分片记录所属代。 */
        var initGeneration = 0
        val initHistory = HashMap<Int, ByteArray>()
        /** init 声明的 track id 集合（tkhd）；用于丢弃跨流分片（tfhd 不匹配）。 */
        var initTrackIds: Set<Int> = emptySet()

        class Fragment(val bytes: ByteArray, val generation: Int)

        val fragments = ArrayDeque<Fragment>()
        var fragmentBytes = 0L
        var publishedSequence = 0L
        var servedSequence = 0L
        /** init 内容变更后待插入的 DISCONTINUITY（渲染首个新代分片时消费）。 */
        var pendingDiscontinuity = false
    }

    private val lock = Object()
    private val video = TrackState()
    private val audio = TrackState()
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients: ExecutorService = Executors.newFixedThreadPool(8)
    private val requestCounter = AtomicLong()
    @Volatile private var running = false
    private var generation = 0L
    private var acceptedFragmentCount = 0L
    private var rejectedFragmentCount = 0L
    private var videoInitCount = 0L
    private var audioInitCount = 0L
    private var acceptedVideoFragmentCount = 0L
    private var acceptedAudioFragmentCount = 0L
    private var rejectedVideoFragmentCount = 0L
    private var rejectedAudioFragmentCount = 0L
    private val diagnosticsStartedAtNs = System.nanoTime()
    private var firstVideoInitElapsedMs = -1L
    private var firstAudioInitElapsedMs = -1L
    private var firstAcceptedFragmentElapsedMs = -1L

    /** 仅含计数的镜像泵诊断快照，不含媒体地址、内容或页面凭据。 */
    data class DiagnosticsSnapshot(
        val acceptedFragments: Long,
        val rejectedFragments: Long,
        val videoInitCount: Long,
        val audioInitCount: Long,
        val acceptedVideoFragments: Long,
        val acceptedAudioFragments: Long,
        val rejectedVideoFragments: Long,
        val rejectedAudioFragments: Long,
        val firstVideoInitElapsedMs: Long,
        val firstAudioInitElapsedMs: Long,
        val firstAcceptedFragmentElapsedMs: Long,
    )

    /** 首个 fragment 就绪前清单返回 503（Media3 重试）。 */
    val ready: Boolean
        get() = synchronized(lock) {
            video.initSegment != null && video.fragments.isNotEmpty()
        }

    fun start(): StartResult {
        stopServerOnly()
        return try {
            val localServer = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            server = localServer
            running = true
            val epoch = generation
            acceptThread = Thread({ acceptLoop(localServer, epoch) }, "kantv-mse-mirror-accept").apply {
                isDaemon = true
                start()
            }
            StartResult.Started("http://127.0.0.1:${localServer.localPort}/master.m3u8")
        } catch (_: Exception) {
            stopServerOnly()
            StartResult.Failed
        }
    }

    sealed interface StartResult {
        data class Started(val playlistUri: String) : StartResult
        data object Failed : StartResult
    }

    /** 新 init 段视为轨道重置（换清晰度/页面切线）。序号单调前进不回退，
     *  旧分片丢弃；绝不能动 generation——那是 accept 循环的存活纪元。
     *  init 内容变更时递增 initGeneration 并入历史：清单用版本化 EXT-X-MAP
     *  + #EXT-X-DISCONTINUITY 告知播放器重建解码器（实测：页面从 bootstrap
     *  线切到 vdn 授权线时 init 变化，静态 init URI 会让 ExoPlayer 拿旧 avcC
     *  解新流 → 持续花屏）。
     *  [bytes] 的所有权随调用转移给本服务器（调用方不得再改写）；存入后永不变异，
     *  因此历史与当前 init 共享同一数组、服务时零拷贝。 */
    fun offerInit(track: MirrorTrack, bytes: ByteArray) {
        synchronized(lock) {
            val elapsedMs = diagnosticsElapsedMs()
            when (track) {
                MirrorTrack.VIDEO -> {
                    videoInitCount++
                    if (firstVideoInitElapsedMs < 0) firstVideoInitElapsedMs = elapsedMs
                }
                MirrorTrack.AUDIO -> {
                    audioInitCount++
                    if (firstAudioInitElapsedMs < 0) firstAudioInitElapsedMs = elapsedMs
                }
            }
            val state = stateFor(track)
            if (state.initSegment == null || !state.initSegment.contentEquals(bytes)) {
                state.initGeneration += 1
                state.initHistory[state.initGeneration] = bytes
                while (state.initHistory.size > MAX_INIT_HISTORY) {
                    state.initHistory.remove(state.initHistory.keys.minOrNull() ?: break)
                }
                // 只有已经发布过分片才需要 DISCONTINUITY（首 init 无需）。
                state.pendingDiscontinuity = state.servedSequence > 0 || state.fragments.isNotEmpty()
            }
            state.initSegment = bytes
            state.initTrackIds = parseInitTrackIds(bytes)
            state.servedSequence += state.fragments.size
            state.fragments.clear()
            state.fragmentBytes = 0
            state.publishedSequence = state.servedSequence
            lock.notifyAll()
        }
    }

    /**
     * 入队一个媒体分片。[bytes] 的所有权随调用转移给本服务器（调用方不得再改写）；
     * 存入后永不变异,服务时零拷贝。
     *
     * 返回值是唯一能证明该分片进入当前镜像流的信号；调用方不得把被拒绝的旧流
     * 分片当作活跃数据来续命 watchdog。
     */
    fun offerFragment(track: MirrorTrack, bytes: ByteArray): Boolean {
        synchronized(lock) {
            val state = stateFor(track)
            // 跨流分片防护（2026-09-13 实测）：页面切线后新旧 MediaSource 交错 append，
            // fragment 的 tfhd track_id 与当前 init 的 tkhd 不一致时必须丢弃，
            // 否则解码器拿 track 20 的分片配 track 3 的 init → 持续花屏。
            val fragmentTrackId = parseFragmentTrackId(bytes)
            if (state.initTrackIds.isNotEmpty() && fragmentTrackId != null &&
                fragmentTrackId !in state.initTrackIds
            ) {
                rejectedFragmentCount++
                when (track) {
                    MirrorTrack.VIDEO -> rejectedVideoFragmentCount++
                    MirrorTrack.AUDIO -> rejectedAudioFragmentCount++
                }
                lock.notifyAll()
                return false
            }
            state.fragments.addLast(TrackState.Fragment(bytes, state.initGeneration))
            acceptedFragmentCount++
            when (track) {
                MirrorTrack.VIDEO -> acceptedVideoFragmentCount++
                MirrorTrack.AUDIO -> acceptedAudioFragmentCount++
            }
            if (firstAcceptedFragmentElapsedMs < 0) firstAcceptedFragmentElapsedMs = diagnosticsElapsedMs()
            state.fragmentBytes += bytes.size
            while (state.fragments.size > MAX_FRAGMENTS || state.fragmentBytes > MAX_TRACK_BYTES) {
                if (state.fragments.isEmpty()) break
                val evicted = state.fragments.removeFirst()
                state.fragmentBytes -= evicted.bytes.size
                state.servedSequence++
            }
            lock.notifyAll()
            return true
        }
    }

    /** 解析 init moov 内所有 tkhd 的 track_id（多 trak 时全收）。解析失败返回空集。 */
    private fun parseInitTrackIds(bytes: ByteArray): Set<Int> {
        val ids = HashSet<Int>()
        fun walk(start: Int, end: Int, depth: Int) {
            var pos = start
            while (pos + 8 <= end) {
                var size = ((bytes[pos].toInt() and 0xFF) shl 24) or ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
                val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
                var header = 8
                if (size == 1 && pos + 16 <= end) {
                    size = java.lang.Long.parseLong(
                        bytes.sliceArray(pos + 8 until pos + 16).joinToString("") { "%02x".format(it) }, 16,
                    ).toInt()
                    header = 16
                }
                if (size < 8 || pos + size > end) return
                when {
                    type == "tkhd" && pos + header + 20 <= end -> {
                        val version = bytes[pos + header].toInt()
                        val base = pos + header + 4
                        val id = if (version == 0) {
                            ((bytes[base + 8].toInt() and 0xFF) shl 24) or ((bytes[base + 9].toInt() and 0xFF) shl 16) or
                                ((bytes[base + 10].toInt() and 0xFF) shl 8) or (bytes[base + 11].toInt() and 0xFF)
                        } else {
                            ((bytes[base + 16].toInt() and 0xFF) shl 24) or ((bytes[base + 17].toInt() and 0xFF) shl 16) or
                                ((bytes[base + 18].toInt() and 0xFF) shl 8) or (bytes[base + 19].toInt() and 0xFF)
                        }
                        ids.add(id)
                    }
                    type in CONTAINER_BOXES && depth < 4 -> walk(pos + header, pos + size, depth + 1)
                }
                pos += size
            }
        }
        walk(0, bytes.size, 0)
        return ids
    }

    /** 解析 moof/traf/tfhd 的 track_id（flags bit0=track_id 存在）。解析失败返回 null。 */
    private fun parseFragmentTrackId(bytes: ByteArray): Int? {
        var pos = 0
        val end = bytes.size
        while (pos + 8 <= end) {
            var size = ((bytes[pos].toInt() and 0xFF) shl 24) or ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            var header = 8
            if (size == 1 && pos + 16 <= end) {
                size = java.lang.Long.parseLong(
                    bytes.sliceArray(pos + 8 until pos + 16).joinToString("") { "%02x".format(it) }, 16,
                ).toInt()
                header = 16
            }
            if (size < 8 || pos + size > end) return null
            if (type == "moof") {
                // 在 moof 内找 traf → tfhd
                var inner = pos + header
                val moofEnd = pos + size
                while (inner + 8 <= moofEnd) {
                    val iSize = ((bytes[inner].toInt() and 0xFF) shl 24) or ((bytes[inner + 1].toInt() and 0xFF) shl 16) or
                        ((bytes[inner + 2].toInt() and 0xFF) shl 8) or (bytes[inner + 3].toInt() and 0xFF)
                    val iType = String(bytes, inner + 4, 4, Charsets.US_ASCII)
                    if (iSize < 8 || inner + iSize > moofEnd) return null
                    if (iType == "traf") {
                        var j = inner + 8
                        val trafEnd = inner + iSize
                        while (j + 8 <= trafEnd) {
                            val jSize = ((bytes[j].toInt() and 0xFF) shl 24) or ((bytes[j + 1].toInt() and 0xFF) shl 16) or
                                ((bytes[j + 2].toInt() and 0xFF) shl 8) or (bytes[j + 3].toInt() and 0xFF)
                            val jType = String(bytes, j + 4, 4, Charsets.US_ASCII)
                            if (jSize < 8 || j + jSize > trafEnd) return null
                            if (jType == "tfhd" && j + 16 <= trafEnd) {
                                // fullbox: j+8=version, j+9..11=flags, j+12..15=track_id
                                val flags = ((bytes[j + 9].toInt() and 0xFF) shl 16) or
                                    ((bytes[j + 10].toInt() and 0xFF) shl 8) or (bytes[j + 11].toInt() and 0xFF)
                                if (flags and 0x1 == 0) return null
                                return ((bytes[j + 12].toInt() and 0xFF) shl 24) or ((bytes[j + 13].toInt() and 0xFF) shl 16) or
                                    ((bytes[j + 14].toInt() and 0xFF) shl 8) or (bytes[j + 15].toInt() and 0xFF)
                            }
                            j += jSize
                        }
                    }
                    inner += iSize
                }
                return null
            }
            pos += size
        }
        return null
    }

    private fun stateFor(track: MirrorTrack): TrackState = when (track) {
        MirrorTrack.VIDEO -> video
        MirrorTrack.AUDIO -> audio
    }

    /** 最近一次视频分片到达时间戳，供桥侧健康监测。 */
    @Volatile var lastFragmentArrivedAtMs: Long = 0L
        private set

    fun noteFragmentArrived() {
        lastFragmentArrivedAtMs = System.currentTimeMillis()
    }

    fun diagnosticsSnapshot(): DiagnosticsSnapshot = synchronized(lock) {
        DiagnosticsSnapshot(
            acceptedFragments = acceptedFragmentCount,
            rejectedFragments = rejectedFragmentCount,
            videoInitCount = videoInitCount,
            audioInitCount = audioInitCount,
            acceptedVideoFragments = acceptedVideoFragmentCount,
            acceptedAudioFragments = acceptedAudioFragmentCount,
            rejectedVideoFragments = rejectedVideoFragmentCount,
            rejectedAudioFragments = rejectedAudioFragmentCount,
            firstVideoInitElapsedMs = firstVideoInitElapsedMs,
            firstAudioInitElapsedMs = firstAudioInitElapsedMs,
            firstAcceptedFragmentElapsedMs = firstAcceptedFragmentElapsedMs,
        )
    }

    private fun diagnosticsElapsedMs(): Long =
        ((System.nanoTime() - diagnosticsStartedAtNs) / 1_000_000L).coerceAtLeast(0L)

    private fun acceptLoop(localServer: ServerSocket, epoch: Long) {
        while (running && generation == epoch) {
            val socket = try { localServer.accept() } catch (_: Exception) { return }
            clients.execute {
                try {
                    handle(socket)
                } catch (_: IOException) {
                    // 播放器停止/换台的正常取消，不算异常。
                } catch (_: Exception) {
                } finally {
                    try { socket.close() } catch (_: Exception) { }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream()
        val header = readRequestHeader(input) ?: return
        val request = header.firstOrNull() ?: return
        val method = request.split(' ').getOrNull(0)
        val path = request.split(' ').getOrNull(1)?.substringBefore('?') ?: return
        if (method != "GET") {
            respond(socket, 405, "text/plain", ByteArray(0))
            return
        }
        requestCounter.incrementAndGet()
        debugLog("mirror request method=$method path=$path")
        when {
            path == "/master.m3u8" -> respondPlaylist(socket, ::renderMaster)
            path == "/video.m3u8" -> respondPlaylist(socket, ::renderVideoPlaylist)
            path == "/audio.m3u8" -> respondPlaylist(socket, ::renderAudioPlaylist)
            path == "/video-init.mp4" -> respondInit(socket, video, null)
            path == "/audio-init.mp4" -> respondInit(socket, audio, null)
            path.startsWith("/video-init-") ->
                respondInit(socket, video, path.removePrefix("/video-init-").removeSuffix(".mp4").toIntOrNull())
            path.startsWith("/audio-init-") ->
                respondInit(socket, audio, path.removePrefix("/audio-init-").removeSuffix(".mp4").toIntOrNull())
            path.startsWith("/video-seg-") ->
                respondSegment(socket, video, path.removePrefix("/video-seg-").removeSuffix(".mp4").toLongOrNull())
            path.startsWith("/audio-seg-") ->
                respondSegment(socket, audio, path.removePrefix("/audio-seg-").removeSuffix(".mp4").toLongOrNull())
            else -> respond(socket, 404, "text/plain", ByteArray(0))
        }
    }

    /** 逐行读取请求头（UTF-8 行读取，body 字节不被预缓冲吞掉）。 */
    private fun readRequestHeader(input: java.io.InputStream): List<String>? {
        val lines = ArrayList<String>()
        val current = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (lines.isNotEmpty()) lines else null
            if (byte == '\n'.code) {
                var line = current.toString()
                if (line.endsWith('\r')) line = line.dropLast(1)
                current.setLength(0)
                if (line.isEmpty()) return lines
                lines.add(line)
                if (lines.size > 64) return null
            } else {
                current.append(byte.toChar())
            }
        }
    }

    /**
     * Gecko WebExtension native messages deliver the ASCII batch protocol:
     * `V|I|<size>|<base64>` / `V|F|<size>|<base64>` / `A|I|…` / `A|F|…`.
     * Padding is truncated to the reported size. Returns handled valid lines; actual fragment
     * admission is decided by [offerFragment]. HTTP serves playback data through GET only.
     */
    fun offer(body: String): Int {
        if (body.isEmpty()) return 0
        var accepted = 0
        for (line in body.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val fields = trimmed.split('|')
            if (fields.size != 4) continue
            val track = when (fields[0]) {
                "V" -> MirrorTrack.VIDEO
                "A" -> MirrorTrack.AUDIO
                else -> continue
            }
            val isInit = fields[1] == "I"
            if (fields[1] != "I" && fields[1] != "F") continue
            val size = fields[2].toIntOrNull() ?: continue
            val decoded = try {
                java.util.Base64.getDecoder().decode(fields[3])
            } catch (_: IllegalArgumentException) {
                continue
            }
            // base64 解码产出是新建数组,仅在补零截断时才需要拷贝。
            val bytes = if (size in 1 until decoded.size) decoded.copyOf(size) else decoded
            if (isInit) {
                offerInit(track, bytes)
            } else if (offerFragment(track, bytes)) {
                noteFragmentArrived()
            }
            accepted++
        }
        return accepted
    }

    private fun respondPlaylist(socket: Socket, render: () -> String?) {
        val deadline = System.nanoTime() + PLAYLIST_WAIT_NS
        val body = synchronized(lock) {
            var rendered: String? = render()
            while (running && rendered == null) {
                val left = deadline - System.nanoTime()
                if (left <= 0) break
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(lock, left)
                rendered = render()
            }
            rendered
        }
        if (body == null) {
            respond(socket, 503, "text/plain", ByteArray(0))
        } else {
            respond(socket, 200, "application/vnd.apple.mpegurl", body.toByteArray())
        }
    }

    private fun renderMaster(): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:7")
        // 音频轨缺失时仍可单视频播放（Media3 忽略缺失 audio rendition）。
        appendLine("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"audio\",DEFAULT=YES,URI=\"audio.m3u8\"")
        appendLine("#EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO=\"aud\"")
        appendLine("video.m3u8")
    }

    private fun renderVideoPlaylist(): String? = synchronizedRender(video, "video")

    private fun renderAudioPlaylist(): String? = synchronizedRender(audio, "audio")

    private fun synchronizedRender(state: TrackState, prefix: String): String? = synchronized(lock) {
        if (state.initSegment == null || state.fragments.isEmpty()) return null
        val available = state.servedSequence + state.fragments.size
        // 直播滑窗：起始序号只前进（target 随新分片推进），published 记忆防回退。
        val target = maxOf(state.servedSequence, available - PUBLISHED_WINDOW)
        val start = maxOf(state.publishedSequence, target)
        state.publishedSequence = start
        buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:$TARGET_DURATION_SECONDS")
            appendLine("#EXT-X-MEDIA-SEQUENCE:$start")
            var lastGen = -1
            for (sequence in start until available) {
                val index = (sequence - state.servedSequence).toInt()
                val fragment = state.fragments.getOrNull(index) ?: continue
                if (fragment.generation != lastGen) {
                    if (lastGen != -1 || state.pendingDiscontinuity) {
                        appendLine("#EXT-X-DISCONTINUITY")
                        state.pendingDiscontinuity = false
                    }
                    appendLine("#EXT-X-MAP:URI=\"$prefix-init-${fragment.generation}.mp4\"")
                    lastGen = fragment.generation
                }
                appendLine("#EXTINF:$TARGET_DURATION_SECONDS.000,")
                appendLine("$prefix-seg-$sequence.mp4")
            }
        }
    }

    /** gen=null 服务当前 init（兼容别名）；否则按代取历史 init。存入数组永不变异,直接零拷贝服务。 */
    private fun respondInit(socket: Socket, state: TrackState, generation: Int?) {
        val bytes = synchronized(lock) {
            when (generation) {
                null -> state.initSegment
                else -> generation?.let { state.initHistory[it] }
            }
        }
        if (bytes == null) respond(socket, 404, "text/plain", ByteArray(0))
        else respond(socket, 200, "video/mp4", bytes)
    }

    private fun respondSegment(socket: Socket, state: TrackState, sequence: Long?) {
        if (sequence == null) return respond(socket, 404, "text/plain", ByteArray(0))
        // 播放器请求的分片可能刚被逐出：等到超时仍无则 404（触发 Media3 重新拉清单）。
        val deadline = System.nanoTime() + SEGMENT_WAIT_NS
        val bytes = synchronized(lock) {
            while (running) {
                val index = (sequence - state.servedSequence).toInt()
                if (index >= 0 && index < state.fragments.size) {
                    return@synchronized state.fragments[index].bytes
                }
                val left = deadline - System.nanoTime()
                if (left <= 0) return@synchronized null
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(lock, left)
            }
            null
        }
        if (bytes == null) respond(socket, 404, "text/plain", ByteArray(0))
        else respond(socket, 200, "video/mp4", bytes)
    }

    private fun respond(socket: Socket, code: Int, type: String, body: ByteArray) {
        socket.getOutputStream().use { output ->
            output.write("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(body)
            output.flush()
        }
    }

    private fun stopServerOnly() {
        generation++
        running = false
        try { server?.close() } catch (_: Exception) { }
        server = null
        acceptThread?.interrupt()
        acceptThread = null
        synchronized(lock) {
            listOf(video, audio).forEach { state ->
                state.initSegment = null
                state.initGeneration = 0
                state.initHistory.clear()
                state.initTrackIds = emptySet()
                state.pendingDiscontinuity = false
                state.fragments.clear()
                state.fragmentBytes = 0
                state.publishedSequence = 0
                state.servedSequence = 0
            }
            lock.notifyAll()
        }
    }

    override fun close() {
        stopServerOnly()
        clients.shutdownNow()
    }

    enum class MirrorTrack { VIDEO, AUDIO }

    private companion object {
        const val TARGET_DURATION_SECONDS = 4
        const val MAX_FRAGMENTS = 24
        const val MAX_TRACK_BYTES = 48L * 1024 * 1024
        const val PUBLISHED_WINDOW = 8
        const val MAX_INIT_HISTORY = 4
        /** 递归遍历 init 时允许进入的容器 box。 */
        val CONTAINER_BOXES = setOf("moov", "trak", "mdia", "minf", "stbl")
        // 服务端挂起必须显著短于客户端读超时(Media3 DefaultHttpDataSource 默认 8s):
        // 让 404/503 先到,由客户端重试,而不是客户端先超时把整次起播判死。
        val PLAYLIST_WAIT_NS = 5_000_000_000L
        val SEGMENT_WAIT_NS = 5_000_000_000L
    }
}
