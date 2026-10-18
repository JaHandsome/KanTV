package com.kantv.app.playback

import android.util.Log
import com.kantv.app.BuildConfig
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * A small local-only proxy for ordinary, unencrypted HLS media playlists.
 *
 * Debug logging is limited to generation, sequence, byte, and timing counters; source URLs and
 * request headers may contain credentials and are never logged.
 * [start] returns [StartResult.Fallback] before a player is pointed at localhost when the source is
 * a master playlist, encrypted playlist, or needs an initialization segment.
 */
class HlsPrefetchProxy(
    private val maxCacheBytes: Long = 60L * 1024 * 1024,
    private val maxCachedSegments: Int = 24,
    private val parallelDownloads: Int = 6,
    /** Test-only override. Production calculates a finite reserve from observed downloads. */
    private val initialStreamSegments: Int? = null,
    private val segmentWaitMs: Long = 1_500,
    private val playlistWaitMs: Long = 35_000,
    private val pollFloorMs: Long = 500,
) : Closeable {
    sealed interface StartResult {
        data class Started(
            /** Compatibility endpoint consumed by the continuous TS channel. */
            val localUri: String,
            /** HLS manifest endpoint consumed by Media3. */
            val localPlaylistUri: String,
        ) : StartResult {
            val continuousStreamUri: String get() = localUri
        }
        /** Use the original source directly; no local proxy was started. */
        data class Fallback(val reason: Reason) : StartResult
        /** A transient source failure. The caller may retry the original source directly. */
        data class Failed(val reason: Reason) : StartResult
    }

    /**
     * Counters and the enumerated outcome of the startup preflight. Only counts, an enum, and
     * durations: the proxy never reports a URI, a header, or a segment body.
     */
    data class PrefetchStatus(
        val startupOutcome: PrefetchStartupPolicy.StartupOutcome,
        val startupReason: Reason?,
        /** Wall-clock time spent inside the bounded startup preflight. */
        val startupWaitMs: Long,
        /** Upper bound that preflight was allowed to use. */
        val startupCapMs: Long,
        val queued: Int,
        val ready: Int,
        val failed: Int,
        /** Downloads that may not be attributed to the next session or candidate. */
        val cancelled: Int,
        val targetDurationMs: Long,
        val firstSegmentElapsedMs: Long?,
    ) {
        /** Referenced segment count for the preflight window; zero when no preflight ran. */
        fun segmentCount(): Int = if (startupOutcome == PrefetchStartupPolicy.StartupOutcome.NOT_APPLICABLE) {
            0
        } else {
            queued.coerceAtLeast(0) + ready.coerceAtLeast(0) + failed.coerceAtLeast(0)
        }
    }

    internal fun status(): PrefetchStatus = synchronized(lock) { completedStatus ?: statusLocked() }

    /** A direct, local-only continuous TS response used to feed the compatibility player through a file descriptor. */
    class ContinuousStream internal constructor(
        val input: InputStream,
        private val socket: Socket,
    ) : Closeable {
        override fun close() {
            try { input.close() } catch (_: Exception) { }
            try { socket.close() } catch (_: Exception) { }
        }
    }

    enum class Reason {
        MASTER_PLAYLIST,
        ENCRYPTED,
        INITIALIZATION_SEGMENT,
        INVALID_MEDIA_PLAYLIST,
        NETWORK,
        BYTERANGE,
        GAP,
        LOW_LATENCY_HLS,
        UNSUPPORTED_CODEC,
        UNVERIFIED_BOOTSTRAP,
    }

    private data class Segment(
        val sequence: Long,
        val uri: URI,
        val tags: List<String>,
        val discontinuitySequenceBefore: Long,
    )

    private data class Playlist(
        val targetDurationMs: Long,
        val endList: Boolean,
        val independentSegments: Boolean,
        val segments: List<Segment>,
    )

    private enum class SegmentStatus { QUEUED, READY, FAILED }
    private data class SegmentEntry(
        var status: SegmentStatus,
        var bytes: ByteArray? = null,
        var elapsedMs: Long? = null,
        var bootstrap: MpegTsSegmentInspector.Bootstrap? = null,
    )

    private val lock = Object()
    // 线程池常驻复用:换台靠 generation 递增 + 连接断开让旧任务快速退出,不再每次换台
    // shutdownNow 销毁再新建两组线程。
    private val downloader = Executors.newFixedThreadPool(max(1, parallelDownloads)) as ThreadPoolExecutor
    private var rangeDownloader: ExecutorService = Executors.newFixedThreadPool(max(1, parallelDownloads))
    @Volatile private var shortLiveSegments = false
    private val connections = mutableMapOf<HttpURLConnection, Long>()
    // 本地客户端请求全部 Connection: close 且短促,固定有界池足够;原无界 cachedThreadPool
    // 在异常风暴下会无限创建线程。
    private val clients: ExecutorService = Executors.newFixedThreadPool(CLIENT_POOL_SIZE)
    private val entries = LinkedHashMap<Long, SegmentEntry>()
    private class SegmentDownload(val entry: SegmentEntry) {
        lateinit var future: FutureTask<Unit>
    }
    // Task identity survives cache trimming; all callers share the same bounded admission gate.
    private val downloads = LinkedHashMap<Long, SegmentDownload>()
    private var lastPrefetchAdmission: Long? = null
    /** 已探测为不支持 Range 的 host:本会话内直接整段下载,省掉每个分片一次 bytes=0-0 探测 RTT。 */
    private val rangeUnsupportedHosts = HashSet<String>()
    private val knownSegments = LinkedHashMap<Long, Segment>()
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var pollThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var sourceUri: URI? = null
    @Volatile private var sourceHeaders: Map<String, String> = emptyMap()
    /** Per-open cancellation owned by the caller; independent of proxy generation resets. */
    @Volatile private var sessionCancelled: () -> Boolean = { false }
    private var playlist: Playlist? = null
    private var cacheBytes = 0L
    @Volatile private var generation = 0L
    private var firstSequenceInSession: Long? = null
    private var publishedEndSequence: Long? = null
    @Volatile private var activeStreamSequence: Long? = null
    @Volatile private var activeStreamSocket: Socket? = null
    @Volatile private var pendingContinuousSocket: Socket? = null
    @Volatile private var latestTsObservation: TransportStreamObservation? = null
    private var latestTsObservationSequence = Long.MIN_VALUE
    private val debugTimelineAnchors = if (BuildConfig.DEBUG && BuildConfig.KANTV_DIAGNOSTICS) HlsTsTimelineInspector.Anchors() else null
    /** Startup preflight bookkeeping. Counts only; nothing here can identify a source. */
    private var startupOutcome = PrefetchStartupPolicy.StartupOutcome.NOT_APPLICABLE
    private var startupReason: Reason? = null
    private var startupWaitMs = -1L
    private var startupCapMs = -1L
    private var firstSegmentElapsedMs: Long? = null
    /** Immutable terminal snapshot retained after fallback cleanup for the engine/report. */
    private var completedStatus: PrefetchStatus? = null
    /** Byte progress observed while the startup preflight is active; never persisted. */
    private var startupSessionStartedNs = 0L
    private var startupLastProgressNs = 0L
    private var startupProgressBytes = 0L
    /** Lock-free: cancellations may arrive from a UI thread while the preflight holds the lock. */
    private val cancelledDownloads = java.util.concurrent.atomic.AtomicLong(0L)
    /** Cancellations observed since this session started; a later status never inherits older ones. */
    private var sessionCancelledBase = 0L

    internal fun latestTsObservation(): TransportStreamObservation? = latestTsObservation

    @Synchronized
    fun start(
        originalUri: String,
        requestHeaders: Map<String, String>,
        isCancelled: () -> Boolean = { false },
    ): StartResult {
        stop()
        sessionCancelled = isCancelled
        synchronized(lock) {
            completedStatus = null
            startupSessionStartedNs = System.nanoTime()
            startupLastProgressNs = 0L
            startupProgressBytes = 0L
        }
        if (sessionCancelled()) return cancelledStartResult()
        val uri = try {
            URI(originalUri)
        } catch (_: Exception) {
            rememberUnsupportedStatus(Reason.INVALID_MEDIA_PLAYLIST)
            return StartResult.Fallback(Reason.INVALID_MEDIA_PLAYLIST)
        }
        if ((uri.scheme != "http" && uri.scheme != "https") || uri.host.isNullOrBlank()) {
            rememberUnsupportedStatus(Reason.INVALID_MEDIA_PLAYLIST)
            return StartResult.Fallback(Reason.INVALID_MEDIA_PLAYLIST)
        }
        sourceUri = uri
        sourceHeaders = requestHeaders.filter { (name, value) ->
            name.isNotBlank() && !name.contains('\r') && !name.contains('\n') &&
                !value.contains('\r') && !value.contains('\n')
        }.toMap()
        if (sessionCancelled()) return cancelledStartResult()
        val startupGeneration = generation
        val first = refreshPlaylist()
        if (first != null) {
            if (sessionCancelled()) return cancelledStartResult()
            rememberUnsupportedStatus(first.reason)
            clearSource()
            return first
        }
        val startupFallback = awaitVerifiedStartupFallback()
        if (startupFallback != null) {
            val terminalStatus = synchronized(lock) { statusLocked() }
            stop()
            synchronized(lock) { completedStatus = terminalStatus }
            return StartResult.Fallback(startupFallback)
        }
        if (generation != startupGeneration || sessionCancelled()) {
            val terminalStatus = synchronized(lock) {
                startupOutcome = PrefetchStartupPolicy.StartupOutcome.CANCELLED
                startupReason = Reason.NETWORK
                statusLocked()
            }
            stop()
            synchronized(lock) { completedStatus = terminalStatus }
            return StartResult.Fallback(Reason.NETWORK)
        }
        return try {
            if (sessionCancelled()) return cancelledStartResult()
            val localServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            server = localServer
            running = true
            val epoch = generation
            acceptThread = Thread({ acceptLoop(localServer, epoch) }, "kantv-hls-proxy-accept").apply { isDaemon = true; start() }
            pollThread = Thread({ pollLoop(epoch) }, "kantv-hls-proxy-poll").apply { isDaemon = true; start() }
            if (BuildConfig.DEBUG && BuildConfig.KANTV_DIAGNOSTICS) clients.execute { verifyLocalSocket(localServer.localPort, epoch) }
            if (generation != startupGeneration || sessionCancelled()) return cancelledStartResult()
            val base = "http://127.0.0.1:${localServer.localPort}"
            StartResult.Started(
                localUri = "$base/stream.ts",
                localPlaylistUri = "$base/playlist.m3u8",
            )
        } catch (_: Exception) {
            val terminalStatus = synchronized(lock) {
                startupOutcome = PrefetchStartupPolicy.StartupOutcome.DOWNLOAD_FAILED
                startupReason = Reason.NETWORK
                statusLocked()
            }
            stop()
            synchronized(lock) { completedStatus = terminalStatus }
            StartResult.Failed(Reason.NETWORK)
        }
    }

    private fun cancelledStartResult(): StartResult.Fallback {
        val terminalStatus = synchronized(lock) {
            startupOutcome = PrefetchStartupPolicy.StartupOutcome.CANCELLED
            startupReason = Reason.NETWORK
            startupWaitMs = startupWaitMs.coerceAtLeast(0L)
            startupCapMs = startupCapMs.coerceAtLeast(0L)
            statusLocked()
        }
        stop()
        synchronized(lock) { completedStatus = terminalStatus }
        return StartResult.Fallback(Reason.NETWORK)
    }

    private fun rememberUnsupportedStatus(reason: Reason) {
        synchronized(lock) {
            startupOutcome = when (reason) {
                Reason.NETWORK -> PrefetchStartupPolicy.StartupOutcome.DOWNLOAD_FAILED
                Reason.UNSUPPORTED_CODEC -> PrefetchStartupPolicy.StartupOutcome.UNSUPPORTED_CODEC
                else -> PrefetchStartupPolicy.StartupOutcome.UNSUPPORTED_PLAYLIST
            }
            startupReason = reason
            startupWaitMs = 0L
            startupCapMs = 0L
            completedStatus = statusLocked()
        }
    }

    /** Closes localhost sockets, cancels fetches, and drops all sensitive source state. */
    @Synchronized
    fun stop() {
        sessionCancelled = { true }
        debugLog("stop requested generation=$generation")
        generation++
        running = false
        cancelPendingStream()
        // A cancelled session must never leave its queue ahead of the next channel. Pools persist;
        // in-flight tasks exit via the generation bump and the connection disconnects below.
        val cancelledConnections = synchronized(lock) {
            cancelDownloadsLocked()
            connections.keys.toList().also { connections.clear() }
        }
        cancelledConnections.forEach { runCatching { it.disconnect() } }
        try { server?.close() } catch (_: Exception) { }
        server = null
        acceptThread?.interrupt()
        pollThread?.interrupt()
        acceptThread = null
        pollThread = null
        val discardedBytes = mutableListOf<ByteArray>()
        synchronized(lock) {
            entries.values.forEach { entry -> entry.bytes?.let(discardedBytes::add) }
            entries.clear()
            lastPrefetchAdmission = null
            knownSegments.clear()
            rangeUnsupportedHosts.clear()
            playlist = null
            shortLiveSegments = false
            cacheBytes = 0
            publishedEndSequence = null
            firstSequenceInSession = null
            activeStreamSequence = null
            latestTsObservation = null
            latestTsObservationSequence = Long.MIN_VALUE
            if (BuildConfig.DEBUG && BuildConfig.KANTV_DIAGNOSTICS) debugTimelineAnchors?.reset()
            startupOutcome = PrefetchStartupPolicy.StartupOutcome.NOT_APPLICABLE
            startupReason = null
            startupWaitMs = -1L
            startupCapMs = -1L
            firstSegmentElapsedMs = null
            // start() calls stop() first, so this is where a session's cancellation window begins.
            sessionCancelledBase = cancelledDownloads.get()
            startupSessionStartedNs = 0L
            startupLastProgressNs = 0L
            startupProgressBytes = 0L
            try { activeStreamSocket?.close() } catch (_: Exception) { }
            activeStreamSocket = null
            lock.notifyAll()
        }
        // 清零在锁外做:整窗几十 MB 的 fill(0) 不阻塞播放单服务。
        discardedBytes.forEach { it.fill(0) }
        clearSource()
    }

    override fun close() {
        stop()
        downloader.shutdownNow()
        rangeDownloader.shutdownNow()
        clients.shutdownNow()
        downloader.awaitTermination(1, TimeUnit.SECONDS)
        clients.awaitTermination(1, TimeUnit.SECONDS)
    }

    /** Cancels only a caller currently waiting for the local continuous-stream response headers. */
    fun cancelPendingStream() {
        val socket = pendingContinuousSocket
        pendingContinuousSocket = null
        try { socket?.close() } catch (_: Exception) { }
    }

    /**
     * Fast, non-blocking invalidation used from the UI thread before full cleanup is queued.
     * In particular this disconnects an initial manifest request that may otherwise occupy the
     * single preparation queue for its complete network timeout after the user changes channel.
     */
    fun abortPendingSession() {
        generation++
        running = false
        cancelPendingStream()
        try { server?.close() } catch (_: Exception) { }
        server = null
        acceptThread?.interrupt()
        pollThread?.interrupt()
        try { activeStreamSocket?.close() } catch (_: Exception) { }
        activeStreamSocket = null
        // Downloads that may still be in flight for the superseded generation are cancellations.
        cancelledDownloads.incrementAndGet()
        val cancelledConnections = synchronized(lock) {
            cancelDownloadsLocked()
            connections.keys.toList().also {
                connections.clear()
                lock.notifyAll()
            }
        }
        cancelledConnections.forEach { runCatching { it.disconnect() } }
    }

    /**
     * Opens the same continuous TS endpoint through an explicit Java loopback socket. This bypasses
     * the native HTTP client only; media bytes remain produced by this proxy's normal reserve and
     * cancellation lifecycle.
     */
    fun openContinuousStream(isCancelled: () -> Boolean = { false }): ContinuousStream? {
        if (!running) return null
        val localServer = server ?: return null
        val socket = Socket()
        pendingContinuousSocket = socket
        try {
            if (isCancelled()) {
                socket.close()
                return null
            }
            socket.connect(InetSocketAddress("127.0.0.1", localServer.localPort), LOCAL_SOCKET_CHECK_TIMEOUT_MS)
            socket.soTimeout = (playlistWaitMs + LOCAL_STREAM_RESPONSE_GRACE_MS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (isCancelled()) {
                socket.close()
                return null
            }
            val output = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
            output.write("GET /stream.ts HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
            output.flush()
            val input = BufferedInputStream(socket.getInputStream())
            val status = readHttpStatus(input)
            if (status != 200) {
                debugLog("local pipe source connected=true status=${status ?: -1}")
                input.close()
                socket.close()
                return null
            }
            if (isCancelled()) {
                input.close()
                socket.close()
                return null
            }
            debugLog("local pipe source connected=true status=200")
            pendingContinuousSocket = null
            return ContinuousStream(input, socket)
        } catch (failure: Exception) {
            debugLog("local pipe source connected=false category=${failure.javaClass.simpleName}")
            try { socket.close() } catch (_: Exception) { }
            return null
        } finally {
            if (pendingContinuousSocket === socket) pendingContinuousSocket = null
        }
    }

    /** Reads only the bounded local HTTP envelope, leaving the TS body untouched for the pipe. */
    private fun readHttpStatus(input: InputStream): Int? {
        val status = readAsciiLine(input) ?: return null
        repeat(MAX_LOCAL_RESPONSE_HEADERS) {
            val header = readAsciiLine(input) ?: return null
            if (header.isEmpty()) return status.substringAfter(" ").substringBefore(" ").toIntOrNull()
        }
        return null
    }

    private fun readAsciiLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        repeat(MAX_LOCAL_RESPONSE_LINE_BYTES) {
            when (val value = input.read()) {
                -1 -> return null
                '\n'.code -> return String(bytes.toByteArray(), Charsets.US_ASCII).trimEnd('\r')
                else -> bytes.write(value)
            }
        }
        return null
    }

    private fun clearSource() {
        sourceUri = null
        sourceHeaders = emptyMap()
    }

    private fun pollLoop(epoch: Long) {
        try {
            while (running && generation == epoch) {
                val delay = synchronized(lock) { max(pollFloorMs, playlist?.targetDurationMs?.div(2) ?: pollFloorMs) }
                try { Thread.sleep(delay) } catch (_: InterruptedException) { return }
                if (!running || generation != epoch) return
                refreshPlaylist() // A later unsupported response simply stops proxy refresh; it never changes player state.
            }
        } finally {
            debugLog("poll ended generation=$epoch running=$running currentGeneration=$generation")
        }
    }

    /** Null means the current source is supported; errors intentionally omit source details. */
    private fun refreshPlaylist(): StartResult.Fallback? {
        val observedGeneration = generation
        val currentUri = sourceUri ?: return StartResult.Fallback(Reason.NETWORK)
        val currentHeaders = sourceHeaders
        val body = try { fetch(currentUri, requestHeaders = currentHeaders, observedGeneration = observedGeneration) } catch (failure: Exception) {
            debugLog("playlist failed generation=$observedGeneration category=${failure.javaClass.simpleName}")
            return StartResult.Fallback(Reason.NETWORK)
        }
        unsupportedReason(body)?.let { return StartResult.Fallback(it) }
        val parsed = parseMediaPlaylist(body, currentUri)
            ?: return StartResult.Fallback(Reason.INVALID_MEDIA_PLAYLIST)
        if (parsed.segments.isEmpty()) return StartResult.Fallback(Reason.INVALID_MEDIA_PLAYLIST)
        val debugHasStart = (BuildConfig.DEBUG && BuildConfig.KANTV_DIAGNOSTICS) && body.toString(Charsets.UTF_8).lineSequence()
            .any { it.trim().startsWith("#EXT-X-START:") }
        val evicted = mutableListOf<ByteArray>()
        synchronized(lock) {
            if (generation != observedGeneration) return StartResult.Fallback(Reason.NETWORK)
            if (firstSequenceInSession == null) {
                firstSequenceInSession = parsed.segments.first().sequence
                val durations = parsed.segments.mapNotNull { segment ->
                    segment.tags.firstOrNull { it.startsWith("#EXTINF:") }
                        ?.substringAfter(':')?.substringBefore(',')?.toDoubleOrNull()
                }
                shortLiveSegments = !parsed.endList && durations.size == parsed.segments.size &&
                    durations.all { it > 0 && it <= 3.0 }
                if (shortLiveSegments) {
                    // A short live window cannot tolerate one throttled connection holding a
                    // segment for tens of seconds. Bound both lanes and per-segment fanout.
                    rangeDownloader.shutdownNow()
                    rangeDownloader = Executors.newFixedThreadPool((parallelDownloads * 2).coerceIn(1, 16))
                }
            }
            playlist = parsed
            admitPrefetchLocked(observedGeneration, retryFailed = true)
            trimLocked(evicted)
            if (BuildConfig.DEBUG && BuildConfig.KANTV_DIAGNOSTICS) debugLog(
                "manifest generation=$observedGeneration windowSegments=${parsed.segments.size} " +
                    "ready=${entries.values.count { it.status == SegmentStatus.READY }} " +
                    "queued=${entries.values.count { it.status == SegmentStatus.QUEUED }} " +
                    debugPlaylistFields(parsed.segments, debugHasStart),
            )
            lock.notifyAll()
        }
        evicted.forEach { it.fill(0) }
        return null
    }

    /** A contiguous cache-sized window retains the oldest startup work and published overlap. */
    private fun prefetchWindowLocked(): List<Segment> {
        val segments = playlist?.segments ?: return emptyList()
        val anchor = activeStreamSequence ?: publishedEndSequence ?: segments.firstOrNull()?.sequence
        return segments.dropWhile { anchor != null && it.sequence < anchor }.take(max(1, maxCachedSegments))
    }

    private fun admitPrefetchLocked(epoch: Long, retryFailed: Boolean = false) {
        val origin = sourceUri ?: return
        for (segment in prefetchWindowLocked()) {
            if (downloads.size >= max(1, parallelDownloads)) break
            val entry = entries[segment.sequence]
            // A byte-limit eviction must not make completion callbacks re-download the same prefix.
            if (entry == null && lastPrefetchAdmission?.let { segment.sequence <= it } == true) continue
            if (entry?.status == SegmentStatus.READY || entry?.status == SegmentStatus.QUEUED) continue
            if (entry?.status == SegmentStatus.FAILED && !retryFailed) continue
            if (admitDownloadLocked(segment, epoch, headersForSegment(origin, segment.uri, sourceHeaders))) {
                lastPrefetchAdmission = maxOf(lastPrefetchAdmission ?: segment.sequence, segment.sequence)
            }
        }
    }

    private fun admitDownloadLocked(segment: Segment, epoch: Long, headers: Map<String, String>): Boolean {
        if (generation != epoch || sessionCancelled()) return false
        if (downloads.containsKey(segment.sequence)) return true
        if (downloads.size >= max(1, parallelDownloads)) return false
        if (entries[segment.sequence]?.status == SegmentStatus.READY) return true
        val entry = SegmentEntry(SegmentStatus.QUEUED)
        val task = SegmentDownload(entry)
        task.future = FutureTask<Unit> { download(segment, epoch, headers, task) }
        knownSegments[segment.sequence] = segment
        entries[segment.sequence] = entry
        downloads[segment.sequence] = task
        downloader.execute(task.future)
        return true
    }

    private fun cancelDownloadsLocked() {
        downloads.values.forEach { it.future.cancel(true) }
        downloads.clear()
        downloader.purge()
    }

    /**
     * Production must not publish an arbitrary downloaded prefix when the playlist does not
     * promise independent segments. A bounded, evidence-driven preflight distinguishes H.264 TS,
     * whose decoder bootstrap the inspector can actually verify, from codecs this proxy cannot
     * prove safe. Unknown or slow startup falls back to the original source before Media3 is
     * pointed at localhost.
     *
     * The wait is derived (target duration, missing evidence, observed download time) and capped,
     * so a slow but valid segment completes while a dead source still fails closed. Note that a
     * *count* of ready segments is never accepted as proof of a decodable start: only
     * `#EXT-X-INDEPENDENT-SEGMENTS` or the inspector's `independentlyDecodable` evidence is.
     *
     * [initialStreamSegments] is a test-only compatibility override used by synthetic fixtures.
     */
    private fun awaitVerifiedStartupFallback(): Reason? {
        if (initialStreamSegments != null) {
            startupOutcome = PrefetchStartupPolicy.StartupOutcome.INDEPENDENT_SEGMENTS
            return null
        }
        val observedGeneration = generation
        val startedAtNs = System.nanoTime()
        return synchronized(lock) {
            val current = playlist ?: run {
                startupOutcome = PrefetchStartupPolicy.StartupOutcome.DOWNLOAD_FAILED
                startupReason = Reason.NETWORK
                startupWaitMs = 0L
                return@synchronized Reason.NETWORK
            }
            var allowedCapMs = PrefetchStartupPolicy.planStartup(startupBudgetLocked(current)).capMs
            startupCapMs = allowedCapMs
            try {
                while (generation == observedGeneration && !sessionCancelled()) {
                    val evidence = startupEvidenceLocked(current, observedGeneration)
                    when (val decision = PrefetchStartupPolicy.decideStartup(evidence, deadlineElapsed = false)) {
                        is PrefetchStartupPolicy.StartupStatus.Ready -> {
                            startupOutcome = decision.outcome
                            startupReason = null
                            return@synchronized null
                        }
                        is PrefetchStartupPolicy.StartupStatus.Fallback -> {
                            startupOutcome = decision.outcome
                            startupReason = decision.outcome.toPrefetchReason()
                            return@synchronized startupReason
                        }
                        is PrefetchStartupPolicy.StartupStatus.Waiting -> Unit
                    }
                    // A completed segment or recent byte progress may justify more time, but the
                    // policy's global maximum remains a hard bound for the whole preflight.
                    val refreshedCap = PrefetchStartupPolicy.planStartup(startupBudgetLocked(current)).capMs
                    allowedCapMs = maxOf(allowedCapMs, refreshedCap)
                        .coerceAtMost(PrefetchStartupPolicy.MAX_STARTUP_CAP_MS)
                    startupCapMs = allowedCapMs
                    val deadline = startedAtNs + TimeUnit.MILLISECONDS.toNanos(allowedCapMs)
                    val left = deadline - System.nanoTime()
                    if (left <= 0) break
                    TimeUnit.NANOSECONDS.timedWait(lock, left)
                }
            } finally {
                startupWaitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs).coerceAtLeast(0L)
            }
            if (generation != observedGeneration || sessionCancelled()) {
                // stop()/abortPendingSession() closed the session during the preflight: this is
                // cancellation, never a download failure.
                startupOutcome = PrefetchStartupPolicy.StartupOutcome.CANCELLED
                startupReason = Reason.NETWORK
                return@synchronized Reason.NETWORK
            }
            val finalEvidence = startupEvidenceLocked(current, observedGeneration)
            when (val finalDecision = PrefetchStartupPolicy.decideStartup(finalEvidence, deadlineElapsed = true)) {
                is PrefetchStartupPolicy.StartupStatus.Ready -> {
                    startupOutcome = finalDecision.outcome
                    startupReason = null
                    null
                }
                is PrefetchStartupPolicy.StartupStatus.Fallback -> {
                    startupOutcome = finalDecision.outcome
                    startupReason = finalDecision.outcome.toPrefetchReason()
                    startupReason
                }
                is PrefetchStartupPolicy.StartupStatus.Waiting -> {
                    startupOutcome = PrefetchStartupPolicy.StartupOutcome.VERIFICATION_TIMEOUT
                    startupReason = Reason.NETWORK
                    startupReason
                }
            }
        }
    }

    private fun startupBudgetLocked(current: Playlist): PrefetchStartupPolicy.StartupBudget {
        val samples = entries.values.mapNotNull { entry ->
            val bytes = entry.bytes ?: return@mapNotNull null
            val elapsed = entry.elapsedMs ?: return@mapNotNull null
            PrefetchStartupPolicy.Sample(bytes.size, elapsed)
        }
        val nowNs = System.nanoTime()
        val recentPartialProgress = startupProgressBytes > 0L && startupLastProgressNs > 0L &&
            nowNs - startupLastProgressNs <= TimeUnit.MILLISECONDS.toNanos(
                maxOf(2_000L, averageSegmentDurationMs(current)),
            )
        val partialElapsedMs = if (recentPartialProgress && startupSessionStartedNs > 0L) {
            TimeUnit.NANOSECONDS.toMillis(nowNs - startupSessionStartedNs).coerceAtLeast(1L)
        } else {
            -1L
        }
        return PrefetchStartupPolicy.StartupBudget(
            targetDurationMs = averageSegmentDurationMs(current),
            readySegments = startupReadyRunLocked().size,
            referencedSegments = prefetchWindowLocked().size,
            observedDownloadMs = maxOf(samples.maxOfOrNull { it.elapsedMs } ?: -1L, partialElapsedMs),
            progressObserved = samples.isNotEmpty() || recentPartialProgress,
            independentSegments = current.independentSegments,
        )
    }

    /**
     * Evidence for the startup decision. [decodableStartVerified] comes from the segment inspector,
     * so a ready count can never masquerade as a verified decoder bootstrap.
     */
    private fun startupEvidenceLocked(
        current: Playlist,
        observedGeneration: Long,
    ): PrefetchStartupPolicy.StartupEvidence {
        val run = startupReadyRunLocked()
        val verified = decodableStartLocked(run) != null
        val h264Observed = run.any { entries[it.sequence]?.bootstrap?.h264 == true }
        val window = prefetchWindowLocked()
        val pending = window.count { entries[it.sequence]?.status.let { status -> status == null || status == SegmentStatus.QUEUED } }
        val resolved = pending == 0
        return PrefetchStartupPolicy.StartupEvidence(
            targetDurationMs = averageSegmentDurationMs(current),
            independentSegments = current.independentSegments,
            decodableStartVerified = verified,
            queued = entries.values.count { it.status == SegmentStatus.QUEUED },
            ready = run.size,
            failed = entries.values.count { it.status == SegmentStatus.FAILED },
            unresolvedQueued = pending,
            h264Observed = h264Observed,
            progressObserved = entries.values.any { it.elapsedMs != null },
            observedDownloadMs = entries.values.mapNotNull { it.elapsedMs }.maxOrNull() ?: -1L,
            windowResolved = resolved,
            cancelled = generation != observedGeneration || sessionCancelled(),
        )
    }

    private fun averageSegmentDurationMs(current: Playlist): Long {
        val durations = current.segments.mapNotNull { segment ->
            segment.tags.firstOrNull { it.startsWith("#EXTINF:") }
                ?.substringAfter(':')?.substringBefore(',')?.toDoubleOrNull()?.times(1_000)?.toLong()
        }
        return durations.takeIf { it.isNotEmpty() }?.average()?.toLong() ?: current.targetDurationMs
    }

    private fun PrefetchStartupPolicy.StartupOutcome.toPrefetchReason(): Reason? = when (this) {
        PrefetchStartupPolicy.StartupOutcome.INDEPENDENT_SEGMENTS,
        PrefetchStartupPolicy.StartupOutcome.VERIFIED_BOOTSTRAP,
        -> null
        PrefetchStartupPolicy.StartupOutcome.UNVERIFIED_BOOTSTRAP -> Reason.UNVERIFIED_BOOTSTRAP
        PrefetchStartupPolicy.StartupOutcome.UNSUPPORTED_CODEC -> Reason.UNSUPPORTED_CODEC
        PrefetchStartupPolicy.StartupOutcome.UNSUPPORTED_PLAYLIST -> null
        PrefetchStartupPolicy.StartupOutcome.DOWNLOAD_FAILED -> Reason.NETWORK
        PrefetchStartupPolicy.StartupOutcome.VERIFICATION_TIMEOUT -> Reason.NETWORK
        PrefetchStartupPolicy.StartupOutcome.CANCELLED -> Reason.NETWORK
        PrefetchStartupPolicy.StartupOutcome.NOT_APPLICABLE -> null
    }

    private fun statusLocked(): PrefetchStatus = PrefetchStatus(
        startupOutcome = startupOutcome,
        startupReason = startupReason,
        startupWaitMs = startupWaitMs,
        startupCapMs = startupCapMs,
        queued = entries.values.count { it.status == SegmentStatus.QUEUED },
        ready = entries.values.count { it.status == SegmentStatus.READY },
        failed = entries.values.count { it.status == SegmentStatus.FAILED },
        cancelled = (cancelledDownloads.get() - sessionCancelledBase).coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        targetDurationMs = playlist?.targetDurationMs ?: -1L,
        firstSegmentElapsedMs = firstSegmentElapsedMs,
    )

    private fun download(
        segment: Segment,
        observedGeneration: Long,
        requestHeaders: Map<String, String>,
        task: SegmentDownload,
    ) {
        val startedAt = System.nanoTime()
        var observation: TransportStreamObservation? = null
        var bootstrap: MpegTsSegmentInspector.Bootstrap? = null
        var cancelled = false
        val data = try {
            val original = fetchSegment(segment.uri, requestHeaders, observedGeneration)
            if (BuildConfig.DEBUG && BuildConfig.KANTV_DIAGNOSTICS) debugTsTimeline(original, segment, observedGeneration, startedAt)
            // The production path always caches and serves original bytes. Analysis is bounded and
            // never applies the experimental timestamp correction.
            if (BuildConfig.KANTV_DIAGNOSTICS) {
                observation = MpegTsObservationInspector.inspect(
                    original,
                    hlsDiscontinuity = segment.tags.any { it == "#EXT-X-DISCONTINUITY" },
                    hlsDiscontinuitySequence = segment.discontinuitySequenceBefore,
                )
            }
            // TS bootstrap inspection是 CPU 密集操作,在缓存锁外完成,不阻塞播放单服务。
            bootstrap = MpegTsSegmentInspector.inspect(original)
            original
        } catch (failure: Exception) {
            // A generation change or an interrupt means the session was stopped or superseded.
            // That is cancellation, not a network failure, and it must never be counted as one.
            cancelled = generation != observedGeneration || sessionCancelled() || Thread.currentThread().isInterrupted
            debugLog(
                "segment failed generation=$observedGeneration " +
                    "relativeSequence=${relativeSequenceLocked(segment.sequence)} " +
                    "cancelled=$cancelled category=${failure.javaClass.simpleName}",
            )
            null
        }
        val evicted = mutableListOf<ByteArray>()
        var discardData = false
        synchronized(lock) {
            if (downloads[segment.sequence] === task) downloads.remove(segment.sequence)
            if (generation != observedGeneration || sessionCancelled()) {
                // Bytes fetched by an obsolete generation are discarded, never served to the next
                // session or the next candidate. Zeroing happens outside the lock below.
                discardData = true
                cancelledDownloads.incrementAndGet()
            } else {
                val entry = entries[segment.sequence]
                when {
                    entry == null || entry !== task.entry -> discardData = true
                    data == null -> {
                        entry.status = SegmentStatus.FAILED
                        if (cancelled) cancelledDownloads.incrementAndGet()
                        debugLog(
                            "segment failed generation=$observedGeneration " +
                                "relativeSequence=${relativeSequenceLocked(segment.sequence)} cancelled=$cancelled",
                        )
                    }
                    else -> {
                        entry.bytes = data
                        entry.elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).coerceAtLeast(1)
                        entry.bootstrap = bootstrap
                        if (firstSegmentElapsedMs == null) firstSegmentElapsedMs = entry.elapsedMs
                        observation?.let { timing ->
                            if (segment.sequence >= latestTsObservationSequence) {
                                latestTsObservationSequence = segment.sequence
                                latestTsObservation = timing
                            }
                        }
                        entry.status = SegmentStatus.READY
                        cacheBytes += data.size
                        trimLocked(evicted)
                        debugLog(
                            "segment ready generation=$observedGeneration relativeSequence=${relativeSequenceLocked(segment.sequence)} " +
                                "bytes=${data.size} elapsedMs=${entry.elapsedMs} independent=${entry.bootstrap?.independentlyDecodable}",
                        )
                    }
                }
            }
            if (generation == observedGeneration && !sessionCancelled()) admitPrefetchLocked(observedGeneration)
            lock.notifyAll()
        }
        if (discardData) data?.fill(0)
        evicted.forEach { it.fill(0) }
    }

    /** Range acceleration is used only with a validated length and a stable representation. */
    private fun fetchSegment(uri: URI, headers: Map<String, String>, epoch: Long): ByteArray {
        if (generation != epoch || sessionCancelled()) throw IOException("cancelled")
        val host = uri.host
        // 探测失败过的 host 本会话内不再重复 bytes=0-0 探测(每次节省一个 RTT)。
        val rangeKnownUnsupported = host != null && synchronized(lock) { host in rangeUnsupportedHosts }
        if (!rangeKnownUnsupported) {
            try {
                var total = 0L
                var validator: String? = null
                fetch(uri, 1, requestHeaders = headers + ("Range" to "bytes=0-0"), observedGeneration = epoch,
                    inspectResponse = { response ->
                        if (response.responseCode != 206) throw IOException("range unsupported")
                        val match = Regex("bytes 0-0/(\\d+)").matchEntire(response.getHeaderField("Content-Range").orEmpty())
                            ?: throw IOException("range invalid")
                        total = match.groupValues[1].toLong()
                        validator = response.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") }
                            ?: response.getHeaderField("Last-Modified")
                    })
                val parts = if (shortLiveSegments) 8 else 3
                val minimumBytes = if (shortLiveSegments) 524_288L else 1_048_576L
                if (total in minimumBytes..maxSegmentBytes() && validator != null) {
                    if (generation != epoch || sessionCancelled()) throw IOException("cancelled")
                    val tasks = (0 until parts).map { part ->
                        val from = total * part / parts
                        val to = total * (part + 1) / parts - 1
                        rangeDownloader.submit<ByteArray> {
                            val bytes = fetch(uri, to - from + 1, SEGMENT_READ_TIMEOUT_MS,
                                headers + mapOf("Range" to "bytes=$from-$to", "If-Range" to validator!!), epoch,
                                trackStartupProgress = true,
                                inspectResponse = { response ->
                                    if (response.responseCode != 206 ||
                                        response.getHeaderField("Content-Range") != "bytes $from-$to/$total") {
                                        throw IOException("range changed")
                                    }
                                })
                            if (bytes.size.toLong() != to - from + 1) throw IOException("range truncated")
                            bytes
                        }
                    }
                    try {
                        val output = ByteArrayOutputStream(total.toInt())
                        tasks.forEach { future -> future.get().let { output.write(it); it.fill(0) } }
                        debugLog("range complete generation=$epoch parts=$parts bytes=$total")
                        return output.toByteArray()
                    } finally { tasks.forEach { it.cancel(true) } }
                }
            } catch (_: Exception) {
                if (generation != epoch || sessionCancelled() || Thread.currentThread().isInterrupted) {
                    throw IOException("cancelled")
                }
                if (host != null) synchronized(lock) { rangeUnsupportedHosts += host }
            }
        }
        return fetch(
            uri,
            maxSegmentBytes(),
            SEGMENT_READ_TIMEOUT_MS,
            headers,
            epoch,
            trackStartupProgress = true,
        )
    }

    private fun maxSegmentBytes(): Long = min(maxCacheBytes, 12L * 1024 * 1024)

    private fun trimLocked(evicted: MutableList<ByteArray>) {
        val keepFromPlaylist = playlist?.segments?.takeLast(maxCachedSegments)?.mapTo(HashSet()) { it.sequence } ?: emptySet()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && (entries.size > maxCachedSegments || cacheBytes > maxCacheBytes)) {
            val candidate = iterator.next()
            if (candidate.value.status == SegmentStatus.QUEUED) continue
            if (candidate.key == publishedEndSequence) continue
            // The active segment has already been copied before writing to the client, so it can
            // be evicted. Keep only future segments that the active stream has not consumed yet.
            if (activeStreamSequence?.let { candidate.key > it } == true) continue
            if (candidate.key in keepFromPlaylist && entries.size <= maxCachedSegments) continue
            evictLocked(candidate, iterator, evicted)
        }

        if (entries.size > maxCachedSegments || cacheBytes > maxCacheBytes) {
            // A client that stops reading must not make a live stream cache grow without bound.
            // Closing it lets the player recover while a fresh iterator enforces both limits.
            try { activeStreamSocket?.close() } catch (_: Exception) { }
            activeStreamSocket = null
            activeStreamSequence = null
            val fallbackIterator = entries.entries.iterator()
            while (fallbackIterator.hasNext() && (entries.size > maxCachedSegments || cacheBytes > maxCacheBytes)) {
                val candidate = fallbackIterator.next()
                if (candidate.value.status != SegmentStatus.QUEUED) evictLocked(candidate, fallbackIterator, evicted)
            }
        }
    }

    private fun evictLocked(
        candidate: MutableMap.MutableEntry<Long, SegmentEntry>,
        iterator: MutableIterator<MutableMap.MutableEntry<Long, SegmentEntry>>,
        evicted: MutableList<ByteArray>,
    ) {
        // 清零由调用方在锁外完成:整窗几十 MB 的 fill(0) 不阻塞播放单服务。
        candidate.value.bytes?.let { bytes -> cacheBytes -= bytes.size; evicted += bytes }
        knownSegments.remove(candidate.key)
        iterator.remove()
    }

    private fun headersForSegment(
        playlistUri: URI,
        segmentUri: URI,
        requestHeaders: Map<String, String>,
    ): Map<String, String> {
        if (sameOrigin(playlistUri, segmentUri)) return requestHeaders
        // Authentication and cookies are scoped to the playlist origin. For a CDN on another
        // origin, retain only the two compatibility headers the direct playback path also forwards.
        return requestHeaders.filterKeys { name ->
            name.equals("User-Agent", ignoreCase = true) || name.equals("Referer", ignoreCase = true)
        }
    }

    private fun sameOrigin(left: URI, right: URI): Boolean {
        val leftHost = left.host ?: return false
        val rightHost = right.host ?: return false
        return left.scheme.equals(right.scheme, ignoreCase = true) &&
            leftHost.equals(rightHost, ignoreCase = true) &&
            effectivePort(left) == effectivePort(right)
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private fun fetch(
        uri: URI,
        limit: Long = 2L * 1024 * 1024,
        readTimeoutMs: Int = PLAYLIST_READ_TIMEOUT_MS,
        requestHeaders: Map<String, String> = sourceHeaders,
        observedGeneration: Long = generation,
        trackStartupProgress: Boolean = false,
        inspectResponse: (HttpURLConnection) -> Unit = {},
    ): ByteArray {
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = readTimeoutMs
            requestHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        synchronized(lock) {
            if (generation != observedGeneration || sessionCancelled()) throw IOException("cancelled")
            connections[connection] = observedGeneration
        }
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP failure")
            inspectResponse(connection)
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    if (generation != observedGeneration || sessionCancelled() || Thread.currentThread().isInterrupted) {
                        throw IOException("cancelled")
                    }
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > limit) throw IllegalStateException("response too large")
                    output.write(buffer, 0, count)
                    if (trackStartupProgress) recordStartupProgress(count)
                }
                return output.toByteArray()
            }
        } finally {
            synchronized(lock) { connections.remove(connection) }
            connection.disconnect()
        }
    }

    private fun recordStartupProgress(byteCount: Int) {
        if (byteCount <= 0) return
        synchronized(lock) {
            if (startupSessionStartedNs <= 0L) return
            startupProgressBytes = (startupProgressBytes + byteCount).coerceAtMost(Long.MAX_VALUE)
            startupLastProgressNs = System.nanoTime()
            lock.notifyAll()
        }
    }

    private fun acceptLoop(localServer: ServerSocket, epoch: Long) {
        while (running) {
            val socket = try { localServer.accept() } catch (_: Exception) { return }
            try {
                clients.execute {
                    try {
                        handle(socket, epoch)
                    } catch (failure: IOException) {
                        // stop(), rapid channel switching, or a player retry closes this localhost
                        // socket deliberately. It is normal cancellation, not an app crash.
                        debugLog("local request io failed generation=$epoch category=${failure.javaClass.simpleName}")
                        try { socket.close() } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) {
                try { socket.close() } catch (_: Exception) { }
            }
        }
    }

    /**
     * Debug-only loopback proof. It uses a fixed, non-media path, records only the numeric HTTP
     * status or exception class, and never consumes a stream segment or exposes source state.
     */
    private fun verifyLocalSocket(port: Int, epoch: Long) {
        if (!running || generation != epoch) return
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), LOCAL_SOCKET_CHECK_TIMEOUT_MS)
                socket.soTimeout = LOCAL_SOCKET_CHECK_TIMEOUT_MS
                val output = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                output.write("GET /health-check HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                output.flush()
                val firstLine = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().orEmpty()
                val status = Regex("^HTTP/\\d(?:\\.\\d)? ([1-5]\\d{2}) ").find(firstLine)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: -1
                debugLog("local socket check generation=$epoch connected=true status=$status")
            }
        } catch (failure: Exception) {
            debugLog("local socket check generation=$epoch connected=false category=${failure.javaClass.simpleName}")
        }
    }

    private fun handle(socket: Socket, epoch: Long) {
        socket.use {
            if (generation != epoch) return
            it.soTimeout = 5_000
            val input = it.getInputStream().bufferedReader(Charsets.US_ASCII)
            val request = input.readLine() ?: return
            // Consume headers without retaining them; localhost players may send arbitrary headers.
            while (true) {
                val header = input.readLine() ?: return
                if (header.isEmpty()) break
            }
            if (!running || generation != epoch) return
            val parts = request.split(' ')
            val method = parts.firstOrNull()?.uppercase() ?: "UNKNOWN"
            val path = localRequestPath(parts.getOrNull(1), it.localPort) ?: return respond(
                it,
                404,
                "text/plain",
                ByteArray(0),
            )
            val pathKind = when {
                path == "/playlist.m3u8" -> "playlist"
                path == "/stream.ts" -> "stream"
                path.startsWith("/segment/") -> "segment"
                else -> "other"
            }
            debugLog("local request generation=$epoch method=$method pathKind=$pathKind")
            when (pathKind) {
                "playlist" -> {
                    val rendered = renderPlaylistWaitingForReadySegments(epoch)
                    if (rendered == null) respond(it, 503, "text/plain", ByteArray(0))
                    else respond(it, 200, "application/vnd.apple.mpegurl", rendered.toByteArray())
                }
                "stream" -> serveContinuousTransportStream(it, epoch)
                "segment" -> serveSegment(it, path.removePrefix("/segment/").toLongOrNull(), epoch)
                else -> respond(it, 404, "text/plain", ByteArray(0))
            }
        }
    }

    /** Accept both origin-form and proxy-style absolute-form requests, but never proxy elsewhere. */
    private fun localRequestPath(target: String?, localPort: Int): String? {
        if (target.isNullOrBlank()) return null
        if (target.startsWith('/')) return target.substringBefore('?')
        val uri = runCatching { URI(target) }.getOrNull() ?: return null
        val isLoopback = uri.host.equals("127.0.0.1", ignoreCase = true) ||
            uri.host.equals("localhost", ignoreCase = true) ||
            uri.host == "::1"
        return uri.rawPath.takeIf {
            uri.scheme.equals("http", ignoreCase = true) && isLoopback && uri.port == localPort
        }
    }

    private fun serveSegment(socket: Socket, sequence: Long?, epoch: Long) {
        if (sequence == null) return respond(socket, 404, "text/plain", ByteArray(0))
        val data = synchronized(lock) {
            // Previously a cache wait was followed by a second independent network fetch. Keep
            // that bounded network tolerance while waiting for the shared registered download.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                maxOf(segmentWaitMs, SEGMENT_READ_TIMEOUT_MS.toLong()),
            )
            var requested = false
            while (running && generation == epoch) {
                val entry = entries[sequence]
                if (entry?.status == SegmentStatus.READY) return@synchronized entry.bytes?.copyOf()
                if (!requested && !downloads.containsKey(sequence)) {
                    val segment = knownSegments[sequence] ?: playlist?.segments?.firstOrNull { it.sequence == sequence }
                        ?: return@synchronized null
                    val origin = sourceUri ?: return@synchronized null
                    requested = admitDownloadLocked(segment, epoch, headersForSegment(origin, segment.uri, sourceHeaders))
                } else if (entry?.status == SegmentStatus.FAILED) {
                    return@synchronized null
                }
                val left = deadline - System.nanoTime()
                if (left <= 0) return@synchronized null
                TimeUnit.NANOSECONDS.timedWait(lock, left)
            }
            null
        }
        if (data == null) respond(socket, 503, "text/plain", ByteArray(0))
        else respond(socket, 200, "video/mp2t", data)
    }

    /**
     * Feeds cached MPEG-TS segments as one ordinary HTTP stream. This makes the compatibility
     * player consume from the beginning of the reserve instead of jumping to the HLS live edge.
     */
    private fun serveContinuousTransportStream(socket: Socket, observedGeneration: Long) {
        synchronized(lock) {
            if (generation != observedGeneration) return
            try { activeStreamSocket?.close() } catch (_: IOException) { }
            activeStreamSocket = socket
        }
        try {
            serveContinuousTransportStreamBody(socket, observedGeneration)
        } finally {
            synchronized(lock) {
                if (activeStreamSocket === socket) {
                    activeStreamSocket = null
                    activeStreamSequence = null
                }
            }
        }
    }

    private fun serveContinuousTransportStreamBody(socket: Socket, observedGeneration: Long) {
        val firstSequence = synchronized(lock) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(playlistWaitMs)
            var run = startupReadyRunLocked()
            // Freeze the decision for this opening attempt.  Recomputing it after only one slow
            // segment would turn a two-segment baseline into a much longer wait mid-startup.
            val decision = startupDecisionLocked(run)
            while (running && generation == observedGeneration && !socket.isClosed && run.size < decision.requiredSegments) {
                val left = deadline - System.nanoTime()
                if (left <= 0) break
                TimeUnit.NANOSECONDS.timedWait(lock, left)
                run = startupReadyRunLocked()
            }
            if (!running || generation != observedGeneration || socket.isClosed) return
            val start = decodableStartLocked(run) ?: run.firstOrNull()
            debugLog(
                "startup reserveSegments=${decision.requiredSegments} reserveMs=${decision.requiredCacheMs} " +
                    "p95Ms=${decision.downloadP95Ms} mediaBps=${decision.mediaBitrateBps} sustainable=${decision.deliverySustainable} " +
                    "independentStart=${decodableStartLocked(run) != null}",
            )
            start?.sequence
        }
        if (firstSequence == null) {
            respond(socket, 503, "text/plain", ByteArray(0))
            return
        }
        val output = socket.getOutputStream()
        output.write(
            "HTTP/1.0 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        var sequence = firstSequence
        debugLog("stream start relativeSequence=${relativeSequenceLocked(sequence)}")
        while (running && generation == observedGeneration && !socket.isClosed) {
            activeStreamSequence = sequence
            val data = waitForSegment(sequence, observedGeneration) ?: break
            try {
                output.write(data)
                output.flush()
                debugLog("stream sent relativeSequence=${relativeSequenceLocked(sequence)} bytes=${data.size}")
            } catch (_: Exception) {
                break
            } finally {
                data.fill(0)
            }
            sequence++
        }
        debugLog("stream ended nextRelativeSequence=${relativeSequenceLocked(sequence)} running=$running")
    }

    private fun waitForSegment(sequence: Long, observedGeneration: Long): ByteArray? = synchronized(lock) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SEGMENT_READ_TIMEOUT_MS.toLong())
        while (running && generation == observedGeneration) {
            entries[sequence]?.bytes?.let { return@synchronized it.copyOf() }
            val current = playlist
            if (current?.endList == true && sequence > (current.segments.lastOrNull()?.sequence ?: -1L)) {
                return@synchronized null
            }
            val left = deadline - System.nanoTime()
            if (left <= 0) return@synchronized null
            TimeUnit.NANOSECONDS.timedWait(lock, left)
        }
        null
    }

    private fun renderPlaylistWaitingForReadySegments(epoch: Long): String? = synchronized(lock) {
        if (!running || generation != epoch) return@synchronized null
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(playlistWaitMs)
        var segments = publishedSegmentsForRenderLocked()
        while (running && generation == epoch && segments.isEmpty()) {
            val left = deadline - System.nanoTime()
            if (left <= 0) break
            TimeUnit.NANOSECONDS.timedWait(lock, left)
            if (!running || generation != epoch) return@synchronized null
            segments = publishedSegmentsForRenderLocked()
        }
        if (!running || generation != epoch) return@synchronized null
        val current = playlist ?: return@synchronized null
        if (segments.isEmpty()) return@synchronized null
        segments = segments.takeLast(min(PLAYLIST_WINDOW_SEGMENTS, maxCachedSegments))
        publishedEndSequence = segments.last().sequence
        if (BuildConfig.DEBUG && BuildConfig.KANTV_DIAGNOSTICS) debugLog(
            "render " + debugPlaylistFields(segments, !current.endList && segments.size >= INITIAL_READY_SEGMENTS),
        )
        buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:${max(1, current.targetDurationMs / 1000)}")
            if (current.independentSegments) appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            if (!current.endList && segments.size >= INITIAL_READY_SEGMENTS) {
                appendLine("#EXT-X-START:TIME-OFFSET=-15.0,PRECISE=NO")
            }
            segments.firstOrNull()?.let { appendLine("#EXT-X-MEDIA-SEQUENCE:${it.sequence}") }
            segments.firstOrNull()?.let {
                appendLine("#EXT-X-DISCONTINUITY-SEQUENCE:${it.discontinuitySequenceBefore}")
            }
            segments.forEach { segment ->
                segment.tags.forEach(::appendLine)
                appendLine("/segment/${segment.sequence}")
            }
            if (current.endList && segments.last().sequence == current.segments.last().sequence) appendLine("#EXT-X-ENDLIST")
        }
    }

    /** Returns the longest consecutive run that is already fully cached. */
    private fun readySegmentRunLocked(): List<Segment> {
        val runs = readyRunsLocked()
        val previousEnd = publishedEndSequence
        if (previousEnd != null) {
            // Keep overlap with the last advertised window. Jumping over a missing sequence
            // makes the compatibility player abort inside its adaptive-playlist updater.
            runs.firstOrNull { run -> run.any { it.sequence == previousEnd } }?.let { return it }
            // Once an old window has rolled out, a discontinuity begins a new decode epoch and is
            // the only safe place to advance without overlap. A plain sequence gap never qualifies.
            return runs.firstOrNull { run ->
                run.first().sequence > previousEnd && run.first().tags.any { it == "#EXT-X-DISCONTINUITY" }
            }.orEmpty()
        }
        return startupReadyRunLocked()
    }

    /**
     * The first Media3 playlist must begin with a decoder-safe prefix, rather than an arbitrary
     * number of downloaded fragments. An independent-segments declaration is the producer's
     * explicit guarantee. Otherwise, only publish as soon as the earliest ready run contains a
     * complete H.264 bootstrap. Codecs we cannot identify have already fallen back before the
     * local server starts, and remain fail-closed here.
     */
    private fun publishedSegmentsForRenderLocked(): List<Segment> {
        val run = readySegmentRunLocked()
        if (publishedEndSequence != null) return run
        if (run.isEmpty()) return emptyList()
        if (playlist?.independentSegments == true) return run.take(1)

        var aggregate = MpegTsSegmentInspector.Bootstrap()
        var h264Observed = false
        run.forEachIndexed { index, segment ->
            val bootstrap = entries[segment.sequence]?.bootstrap ?: MpegTsSegmentInspector.Bootstrap()
            aggregate += bootstrap
            h264Observed = h264Observed || bootstrap.h264
            if (aggregate.independentlyDecodable) return run.take(index + 1)
        }
        // We can prove this is H.264 but not that its first IDR is decodable yet: wait for more
        // contiguous data instead of publishing a partial GOP.
        if (h264Observed) return emptyList()
        initialStreamSegments?.let { override ->
            val reserve = min(max(1, override), playlist?.segments?.size ?: max(1, override))
            return run.takeIf { it.size >= reserve }?.take(reserve).orEmpty()
        }
        return emptyList()
    }

    private fun startupReadyRunLocked(): List<Segment> {
        // Preserve the oldest pending segment. Starting on a later fast pair discards the
        // downloads about to finish behind it, leaving no reserve for the next slow segment.
        val first = knownSegments.keys.sorted().firstOrNull {
            entries[it]?.status != SegmentStatus.FAILED
        } ?: return emptyList()
        return readyRunsLocked().firstOrNull { it.first().sequence == first }.orEmpty()
    }

    private fun startupDecisionLocked(run: List<Segment>): PrefetchStartupPolicy.Decision {
        val current = playlist
        val samples = entries.values.mapNotNull { entry ->
            val bytes = entry.bytes ?: return@mapNotNull null
            val elapsed = entry.elapsedMs ?: return@mapNotNull null
            PrefetchStartupPolicy.Sample(bytes.size, elapsed)
        }
        return PrefetchStartupPolicy.decide(
            targetDurationMs = current?.segments?.mapNotNull { segment ->
                segment.tags.firstOrNull { it.startsWith("#EXTINF:") }
                    ?.substringAfter(':')?.substringBefore(',')?.toDoubleOrNull()?.times(1_000)?.toLong()
            }?.takeIf { it.isNotEmpty() }?.average()?.toLong() ?: current?.targetDurationMs ?: 6_000,
            samples = samples,
            availableSegments = maxOf(1, current?.segments?.size ?: run.size),
            overrideSegments = initialStreamSegments,
        )
    }

    /** A session-relative number is sufficient for diagnostics and cannot expose source state. */
    private fun relativeSequenceLocked(sequence: Long): Long =
        synchronized(lock) { sequence - (firstSequenceInSession ?: sequence) }

    /** Finds a suffix whose beginning contains all decoder bootstrap material before its first IDR. */
    private fun decodableStartLocked(run: List<Segment>): Segment? {
        run.indices.forEach { startIndex ->
            var aggregate = MpegTsSegmentInspector.Bootstrap()
            for (index in startIndex until run.size) {
                aggregate += entries[run[index].sequence]?.bootstrap ?: MpegTsSegmentInspector.Bootstrap()
                if (aggregate.independentlyDecodable) return run[startIndex]
            }
        }
        return null
    }

    private fun readyRunsLocked(): List<List<Segment>> {
        val candidates = knownSegments.values.sortedBy(Segment::sequence)
        var current = ArrayList<Segment>()
        val runs = ArrayList<List<Segment>>()
        for (segment in candidates) {
            val ready = entries[segment.sequence]?.status == SegmentStatus.READY
            val startsNewDiscontinuity = segment.tags.any { it == "#EXT-X-DISCONTINUITY" }
            if (ready && (current.isEmpty() ||
                    (segment.sequence == current.last().sequence + 1 && !startsNewDiscontinuity))) {
                current += segment
            } else {
                if (current.isNotEmpty()) runs += current.toList()
                current = ArrayList<Segment>().also { if (ready) it += segment }
            }
        }
        if (current.isNotEmpty()) runs += current.toList()
        return runs
    }

    private fun respond(socket: Socket, code: Int, type: String, body: ByteArray) {
        val status = when (code) {
            200 -> "OK"
            503 -> "Service Unavailable"
            else -> "Not Found"
        }
        socket.getOutputStream().use { output ->
            output.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(body)
        }
    }

    private fun parseMediaPlaylist(bytes: ByteArray, base: URI): Playlist? {
        val lines = bytes.toString(Charsets.UTF_8).replace("\r\n", "\n").lines()
        if (lines.firstOrNull()?.trim() != "#EXTM3U") return null
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") || it.startsWith("#EXT-X-I-FRAME-STREAM-INF") }) return null
        if (lines.any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") }) return null
        if (lines.any { it.startsWith("#EXT-X-MAP") }) return null
        if (lines.any { it.startsWith("#EXT-X-BYTERANGE") }) return null
        var sequence = 0L
        var targetMs = 6_000L
        var endList = false
        var discontinuitySequence = 0L
        var independentSegments = false
        val pending = ArrayList<String>()
        val segments = ArrayList<Segment>()
        for (raw in lines.drop(1)) {
            val line = raw.trim()
            when {
                line.isBlank() -> Unit
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').toLongOrNull() ?: return null
                line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") ->
                    discontinuitySequence = line.substringAfter(':').toLongOrNull() ?: return null
                line.startsWith("#EXT-X-TARGETDURATION:") -> targetMs = (line.substringAfter(':').toDoubleOrNull()?.times(1000))?.toLong() ?: return null
                line == "#EXT-X-ENDLIST" -> endList = true
                line == "#EXT-X-INDEPENDENT-SEGMENTS" -> independentSegments = true
                line.startsWith("#EXTINF:") ||
                    line == "#EXT-X-DISCONTINUITY" ||
                    line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> pending += line
                line.startsWith("#") -> Unit
                else -> {
                    if (pending.none { it.startsWith("#EXTINF:") }) return null
                    val resolved = try { base.resolve(line) } catch (_: Exception) { return null }
                    segments += Segment(sequence++, resolved, pending.toList(), discontinuitySequence)
                    discontinuitySequence += pending.count { it == "#EXT-X-DISCONTINUITY" }
                    pending.clear()
                }
            }
        }
        return Playlist(targetMs, endList, independentSegments, segments)
    }

    private fun unsupportedReason(bytes: ByteArray): Reason? {
        val lines = bytes.toString(Charsets.UTF_8).replace("\r\n", "\n").lines().map(String::trim)
        return when {
            lines.any { it.startsWith("#EXT-X-STREAM-INF") || it.startsWith("#EXT-X-I-FRAME-STREAM-INF") } ->
                Reason.MASTER_PLAYLIST
            lines.any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") } -> Reason.ENCRYPTED
            lines.any { it.startsWith("#EXT-X-MAP") } -> Reason.INITIALIZATION_SEGMENT
            lines.any { it.startsWith("#EXT-X-BYTERANGE") } -> Reason.BYTERANGE
            lines.any { it == "#EXT-X-GAP" } -> Reason.GAP
            lines.any {
                it.startsWith("#EXT-X-PART") ||
                    it.startsWith("#EXT-X-PRELOAD-HINT") ||
                    it.startsWith("#EXT-X-SERVER-CONTROL") ||
                    it.startsWith("#EXT-X-SKIP") ||
                    it.startsWith("#EXT-X-RENDITION-REPORT")
            } -> Reason.LOW_LATENCY_HLS
            else -> null
        }
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG && BuildConfig.KANTV_DIAGNOSTICS) runCatching { Log.d(DEBUG_TAG, message) }
    }

    private fun debugTsTimeline(bytes: ByteArray, segment: Segment, epoch: Long, fetchStartedNs: Long) {
        if (!BuildConfig.DEBUG || !BuildConfig.KANTV_DIAGNOSTICS) return
        // Downloads finish concurrently. An immutable anchor never treats completion order as sequence order.
        runCatching {
            val relativeSequence = synchronized(lock) {
                if (generation != epoch) return
                segment.sequence - (firstSequenceInSession ?: segment.sequence)
            }
            val scanStartedNs = System.nanoTime()
            val fetchElapsedMs = TimeUnit.NANOSECONDS.toMillis(scanStartedNs - fetchStartedNs)
            val timeline = HlsTsTimelineInspector.inspect(bytes)
            val scanElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - scanStartedNs)
            val anchor = synchronized(lock) {
                if (generation != epoch) return
                val anchors = debugTimelineAnchors ?: return
                timeline.measureAnchor(
                    anchors, epoch, relativeSequence,
                    segment.discontinuitySequenceBefore + segment.tags.count { it == "#EXT-X-DISCONTINUITY" },
                )
            }
            Log.d(
                "KanTvTsTimeline",
                "generation=$epoch relativeSequence=$relativeSequence extinfMs=${extinfDurationMs(segment)} " +
                    "fetchElapsedMs=$fetchElapsedMs scanElapsedMs=$scanElapsedMs " + timeline.logFields() +
                    " " + anchor.logFields(),
            )
        }
    }

    private fun extinfDurationMs(segment: Segment): Long = segment.tags
        .firstOrNull { it.startsWith("#EXTINF:") }
        ?.substringAfter(':')?.substringBefore(',')?.toDoubleOrNull()?.times(1_000)
        ?.takeIf { it.isFinite() && it > 0 && it < Long.MAX_VALUE.toDouble() }?.toLong() ?: -1L

    private fun debugPlaylistFields(segments: List<Segment>, hasStart: Boolean): String {
        var totalMs = 0L
        for (segment in segments) {
            val durationMs = extinfDurationMs(segment)
            if (durationMs < 0 || totalMs > Long.MAX_VALUE - durationMs) {
                totalMs = -1L
                break
            }
            totalMs += durationMs
        }
        return "firstRelativeSequence=${segments.firstOrNull()?.let { relativeSequenceLocked(it.sequence) } ?: -1} " +
            "lastRelativeSequence=${segments.lastOrNull()?.let { relativeSequenceLocked(it.sequence) } ?: -1} " +
            "segments=${segments.size} extinfTotalMs=$totalMs hasStart=${if (hasStart) 1 else 0} " +
            "discontinuitySeq=${segments.firstOrNull()?.discontinuitySequenceBefore ?: -1}"
    }

    private companion object {
        const val LOCAL_SOCKET_CHECK_TIMEOUT_MS = 1_000
        const val LOCAL_STREAM_RESPONSE_GRACE_MS = 5_000L
        const val MAX_LOCAL_RESPONSE_HEADERS = 32
        const val MAX_LOCAL_RESPONSE_LINE_BYTES = 8 * 1024
        const val PLAYLIST_READ_TIMEOUT_MS = 8_000
        const val SEGMENT_READ_TIMEOUT_MS = 30_000
        const val PLAYLIST_WINDOW_SEGMENTS = 6
        const val INITIAL_READY_SEGMENTS = 4
        /** 本地客户端服务线程上限;请求全部 Connection: close 且短促。 */
        const val CLIENT_POOL_SIZE = 8
        const val DEBUG_TAG = "KanTvPrefetch"
    }
}

/** Debug diagnostic only: last PTS minus first PTS is not a full segment duration (last frame omitted). */
internal object HlsTsTimelineInspector {
    private const val PACKET_BYTES = 188
    private const val MAX_PACKETS = 65_536
    private const val WRAP = 1L shl 33

    data class TrackSpan(val pid: Int, val ptsCount: Int, val spanUs: Long)

    // Plain class: the private raw timestamp must not leak through a generated data-class toString/copy.
    class Timeline(
        val scannedPackets: Int,
        val bounded: Boolean,
        val trailingBytes: Int,
        val invalidPackets: Int,
        val invalidPesHeaders: Int,
        val splitPesHeaders: Int,
        val ignoredTrackPackets: Int,
        val video: TrackSpan?,
        val audio: List<TrackSpan>,
        private val firstVideoPts: Long?,
    ) {
        fun measureAnchor(anchors: Anchors, generation: Long, relativeSequence: Long, discontinuity: Long): AnchorDelta =
            if (video == null || firstVideoPts == null) AnchorDelta()
            else anchors.observe(generation, relativeSequence, discontinuity, video.pid, firstVideoPts)

        fun logFields(): String =
            "scannedPackets=$scannedPackets maxPackets=$MAX_PACKETS bounded=${if (bounded) 1 else 0} " +
                "trailingBytes=$trailingBytes invalidPackets=$invalidPackets invalidPesHeaders=$invalidPesHeaders " +
                "splitPesHeaders=$splitPesHeaders " +
                "ignoredTrackPackets=$ignoredTrackPackets videoPid=${video?.pid ?: -1} " +
                "videoPtsCount=${video?.ptsCount ?: 0} videoSpanUs=${video?.spanUs ?: 0} " +
                "audio1Pid=${audio.getOrNull(0)?.pid ?: -1} audio1PtsCount=${audio.getOrNull(0)?.ptsCount ?: 0} " +
                "audio1SpanUs=${audio.getOrNull(0)?.spanUs ?: 0} audio2Pid=${audio.getOrNull(1)?.pid ?: -1} " +
                "audio2PtsCount=${audio.getOrNull(1)?.ptsCount ?: 0} audio2SpanUs=${audio.getOrNull(1)?.spanUs ?: 0}"
    }

    data class AnchorDelta(
        val available: Boolean = false,
        val epoch: Int = -1,
        val anchorRelativeSequence: Long = 0,
        val firstRelativeUs: Long = 0,
    ) {
        fun logFields(): String = "anchorAvailable=${if (available) 1 else 0} anchorEpoch=$epoch " +
            "anchorRelativeSequence=$anchorRelativeSequence firstRelativeUs=$firstRelativeUs maxAnchorEpochs=8"
    }

    /** Caller serializes access with the existing generation lock; scanning never holds that lock. */
    class Anchors {
        private data class Key(val videoPid: Int, val discontinuity: Long)
        private class Anchor(val pts: Long, val sequence: Long, val epoch: Int)
        private var generation: Long? = null
        private val anchors = LinkedHashMap<Key, Anchor>()

        fun reset() {
            generation = null
            anchors.clear()
        }

        internal fun observe(generation: Long, sequence: Long, discontinuity: Long, pid: Int, firstPts: Long): AnchorDelta {
            if (this.generation != generation) {
                reset()
                this.generation = generation
            }
            val key = Key(pid, discontinuity)
            val anchor = anchors[key] ?: run {
                // Fixed session bound. Never evict/re-anchor an existing epoch when downloads finish out of order.
                if (anchors.size >= 8) return AnchorDelta()
                Anchor(firstPts, sequence, anchors.size).also { anchors[key] = it }
            }
            return AnchorDelta(true, anchor.epoch, anchor.sequence, deltaUs(firstPts, anchor.pts))
        }
    }

    private class Track(val pid: Int, private val first: Long) {
        private var last = first
        private var count = 1

        fun add(pts: Long) {
            last = pts
            count++
        }

        fun span(): TrackSpan = TrackSpan(
            pid, count, deltaUs(last, first),
        )

        fun firstPts(): Long = first
    }

    fun inspect(bytes: ByteArray): Timeline {
        val availablePackets = bytes.size / PACKET_BYTES
        val scannedPackets = min(availablePackets, MAX_PACKETS)
        var invalidPackets = 0
        var invalidPesHeaders = 0
        var splitPesHeaders = 0
        var ignoredTrackPackets = 0
        var video: Track? = null
        val audio = ArrayList<Track>(2)
        for (packet in 0 until scannedPackets) {
            val offset = packet * PACKET_BYTES
            val end = offset + PACKET_BYTES
            fun byte(index: Int) = bytes[index].toInt() and 255
            val control = (byte(offset + 3) ushr 4) and 3
            if (byte(offset) != 0x47 || byte(offset + 1) and 0x80 != 0 ||
                byte(offset + 3) and 0xc0 != 0 || control == 0
            ) {
                invalidPackets++
                continue
            }
            var payload = offset + 4
            if (control == 2 || control == 3) {
                val length = byte(payload)
                if (payload + 1 + length > end) {
                    invalidPackets++
                    continue
                }
                payload += length + 1
            }
            if (control == 2 || byte(offset + 1) and 0x40 == 0 || payload + 4 > end) continue
            if (byte(payload) != 0 || byte(payload + 1) != 0 || byte(payload + 2) != 1) continue
            val streamId = byte(payload + 3)
            val isVideo = streamId in 0xe0..0xef
            // Same PES candidates as the existing A/V inspector; private_stream_1 may carry audio.
            val isAudio = streamId in 0xc0..0xdf || streamId == 0xbd
            if (!isVideo && !isAudio) continue
            if (payload + 9 > end) {
                splitPesHeaders++
                continue
            }
            val flags = (byte(payload + 7) ushr 6) and 3
            if (flags == 0) continue
            val headerLength = byte(payload + 8)
            val requiredLength = if (flags == 3) 10 else 5
            val packetLength = (byte(payload + 4) shl 8) or byte(payload + 5)
            if (byte(payload + 6) and 0xc0 != 0x80 || flags == 1 || headerLength < requiredLength ||
                (packetLength != 0 && packetLength < 3 + headerLength)
            ) {
                invalidPesHeaders++
                continue
            }
            if (payload + 9 + requiredLength > end) {
                // Do not reassemble PES across packets in this bounded observer; missing evidence stays explicit.
                splitPesHeaders++
                continue
            }
            val stamp = payload + 9
            fun validStamp(position: Int, prefix: Int): Boolean = byte(position) ushr 4 == prefix &&
                byte(position) and 1 == 1 && byte(position + 2) and 1 == 1 && byte(position + 4) and 1 == 1
            if (!validStamp(stamp, flags) || (flags == 3 && !validStamp(stamp + 5, 1))) {
                invalidPesHeaders++
                continue
            }
            val pts = ((byte(stamp).toLong() and 14) shl 29) or
                (byte(stamp + 1).toLong() shl 22) or ((byte(stamp + 2).toLong() and 254) shl 14) or
                (byte(stamp + 3).toLong() shl 7) or (byte(stamp + 4).toLong() ushr 1)
            val pid = ((byte(offset + 1) and 31) shl 8) or byte(offset + 2)
            if (isVideo) {
                if (video == null) video = Track(pid, pts)
                else if (video.pid == pid) video.add(pts)
                else ignoredTrackPackets++
            } else {
                val track = audio.firstOrNull { it.pid == pid }
                if (track != null) track.add(pts)
                else if (audio.size < 2) audio += Track(pid, pts)
                else ignoredTrackPackets++
            }
        }
        return Timeline(
            scannedPackets, availablePackets > MAX_PACKETS, bytes.size % PACKET_BYTES,
            invalidPackets, invalidPesHeaders, splitPesHeaders, ignoredTrackPackets, video?.span(), audio.map { it.span() },
            video?.firstPts(),
        )
    }

    private fun deltaUs(value: Long, reference: Long): Long =
        (((value - reference + WRAP / 2) and (WRAP - 1)) - WRAP / 2) * 1_000_000L / 90_000L
}
