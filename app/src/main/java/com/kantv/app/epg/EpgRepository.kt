package com.kantv.app.epg

import com.kantv.app.util.Hashing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties

object DefaultEpgSources {
    /** Direct public XMLTV service, used first because it does not depend on GitHub reachability. */
    const val CHINESE_XMLTV = "https://epg.112114.xyz/pp.xml.gz"
    /** Public compressed fallback with wider regional coverage. */
    const val CHINESE_XMLTV_FALLBACK = "https://raw.githubusercontent.com/litiande03/epg/refs/heads/master/pl.xml.gz"

    val defaults = listOf(CHINESE_XMLTV, CHINESE_XMLTV_FALLBACK)
}

interface EpgRepository {
    suspend fun load(
        channels: List<EpgChannelRef>,
        forceRefresh: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): EpgGuide
}

class DefaultEpgRepository(
    cacheDir: File,
    private val sourceUrls: List<String> = DefaultEpgSources.defaults,
    private val client: EpgHttpClient = UrlConnectionEpgHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val freshForMs: Long = 6 * 60 * 60 * 1000L,
) : EpgRepository {
    private val cacheRoot = File(cacheDir, "epg")
    private val loadMutex = Mutex()
    private var memoryEntry: MemoryGuide? = null

    companion object {
        @Volatile private var shared: DefaultEpgRepository? = null

        /** 进程级共享实例：XMLTV 解析结果只缓存一份（宿主与频道浮层不再各持一份大缓存）。 */
        fun shared(context: android.content.Context): DefaultEpgRepository =
            shared ?: synchronized(this) {
                shared ?: DefaultEpgRepository(
                    File(context.applicationContext.filesDir, "epg"),
                ).also { shared = it }
            }
    }

    override suspend fun load(
        channels: List<EpgChannelRef>,
        forceRefresh: Boolean,
        nowMs: Long,
    ): EpgGuide = withContext(ioDispatcher) {
        loadMutex.withLock {
            val uniqueChannels = channels.distinctBy { it.channelId }
            val signature = channelSignature(uniqueChannels)
            memoryEntry?.takeIf { memory ->
                !forceRefresh && memory.channelSignature == signature &&
                    nowMs - memory.guide.status.attemptedAtMs in 0..when (memory.guide.status.state) {
                        EpgSourceState.NETWORK, EpgSourceState.CACHE -> freshForMs
                        EpgSourceState.STALE_CACHE, EpgSourceState.UNAVAILABLE -> RETRY_COOLDOWN_MS
                    }
            }?.let { return@withLock it.guide }
            val guide = loadSources(uniqueChannels, forceRefresh, nowMs)
            memoryEntry = MemoryGuide(signature, guide)
            guide
        }
    }

    private fun loadSources(
        channels: List<EpgChannelRef>,
        forceRefresh: Boolean,
        nowMs: Long,
    ): EpgGuide {
        val schedules = linkedMapOf<String, EpgChannelSchedule>()
        val sourceResults = mutableListOf<SourceGuide>()
        sourceUrls.distinct().forEach { sourceUrl ->
            val remaining = channels.filterNot { schedules.containsKey(it.channelId) }
            if (remaining.isEmpty()) return@forEach
            val result = loadSource(
                sourceUrl = sourceUrl,
                channels = remaining,
                forceRefresh = forceRefresh,
                nowMs = nowMs,
            )
            sourceResults += result
            result.schedules.forEach { (channelId, schedule) ->
                if (!schedules.containsKey(channelId)) schedules[channelId] = schedule
            }
        }
        if (schedules.isEmpty()) {
            return unavailable(
                channels.size,
                nowMs,
                sourceResults.mapNotNull { it.failure }.lastOrNull() ?: EpgFailureCode.INVALID_DATA,
            )
        }
        val incomplete = schedules.size < channels.size
        val usedStale = sourceResults.any { result ->
            result.state == EpgSourceState.STALE_CACHE && result.schedules.isNotEmpty()
        }
        val relevantFailure = (usedStale || incomplete)
            .takeIf { it }
            ?.let { sourceResults.mapNotNull { result -> result.failure }.lastOrNull() }
        val state = when {
            usedStale || relevantFailure != null -> EpgSourceState.STALE_CACHE
            sourceResults.any { it.state == EpgSourceState.NETWORK } -> EpgSourceState.NETWORK
            else -> EpgSourceState.CACHE
        }
        return EpgGuide(
            schedules = schedules,
            status = EpgLoadStatus(
                state = state,
                lastSuccessAtMs = sourceResults.mapNotNull { it.lastSuccessAtMs }.minOrNull(),
                attemptedAtMs = nowMs,
                failure = relevantFailure,
            ),
            matchStats = EpgMatchStats(
                requestedChannels = channels.size,
                matchedChannels = schedules.size,
                programmeCount = schedules.values.sumOf { it.programmes.size },
            ),
        )
    }

    private fun loadSource(
        sourceUrl: String,
        channels: List<EpgChannelRef>,
        forceRefresh: Boolean,
        nowMs: Long,
    ): SourceGuide {
        val cache = cacheFor(sourceUrl)
        val cached = cache.read()?.takeIf { it.sourceUrl == sourceUrl }
        if (!forceRefresh && cached != null && nowMs - cached.lastSuccessAtMs in 0..freshForMs) {
            parseSchedules(cached.payload, channels)?.let { schedules ->
                return SourceGuide(schedules, EpgSourceState.CACHE, cached.lastSuccessAtMs, null)
            }
        }
        val response = runCatching {
            client.fetch(sourceUrl, cached?.etag, cached?.lastModified)
        }.getOrElse { EpgHttpResult.Failed(EpgFailureCode.NETWORK) }
        return when (response) {
            is EpgHttpResult.Modified -> {
                val schedules = parseSchedules(response.payload, channels)
                if (schedules == null) {
                    staleSource(cached, channels, EpgFailureCode.INVALID_DATA)
                } else {
                    runCatching { cache.write(response.payload, nowMs, response.etag, response.lastModified, sourceUrl) }
                    SourceGuide(schedules, EpgSourceState.NETWORK, nowMs, null)
                }
            }
            EpgHttpResult.NotModified -> if (cached == null) {
                SourceGuide(emptyMap(), EpgSourceState.UNAVAILABLE, null, EpgFailureCode.CACHE_IO)
            } else {
                val schedules = parseSchedules(cached.payload, channels)
                if (schedules == null) {
                    SourceGuide(emptyMap(), EpgSourceState.UNAVAILABLE, null, EpgFailureCode.INVALID_DATA)
                } else {
                    // A 304 validates only this source and starts its own freshness window.
                    runCatching {
                        cache.write(cached.payload, nowMs, cached.etag, cached.lastModified, sourceUrl)
                    }
                    SourceGuide(schedules, EpgSourceState.CACHE, nowMs, null)
                }
            }
            is EpgHttpResult.Failed -> staleSource(cached, channels, response.code)
        }
    }

    private fun staleSource(
        cached: EpgCacheEntry?,
        channels: List<EpgChannelRef>,
        failure: EpgFailureCode,
    ): SourceGuide {
        val schedules = cached?.let { parseSchedules(it.payload, channels) }
        return if (cached != null && schedules != null) {
            SourceGuide(schedules, EpgSourceState.STALE_CACHE, cached.lastSuccessAtMs, failure)
        } else {
            SourceGuide(emptyMap(), EpgSourceState.UNAVAILABLE, null, failure)
        }
    }

    private fun parseSchedules(
        payload: ByteArray,
        channels: List<EpgChannelRef>,
    ): Map<String, EpgChannelSchedule>? = runCatching {
        XmlTvParser.parse(ByteArrayInputStream(payload), channels)
    }.getOrNull()

    private fun cacheFor(sourceUrl: String): EpgDiskCache =
        EpgDiskCache(File(cacheRoot, "source-${stableSourceKey(sourceUrl)}"))

    private fun unavailable(channelCount: Int, nowMs: Long, failure: EpgFailureCode): EpgGuide = EpgGuide(
        schedules = emptyMap(),
        status = EpgLoadStatus(EpgSourceState.UNAVAILABLE, null, nowMs, failure),
        matchStats = EpgMatchStats(channelCount, 0, 0),
    )
}

interface EpgHttpClient {
    fun fetch(url: String, etag: String?, lastModified: String?): EpgHttpResult
}

sealed interface EpgHttpResult {
    data class Modified(
        val payload: ByteArray,
        val etag: String?,
        val lastModified: String?,
    ) : EpgHttpResult
    data object NotModified : EpgHttpResult
    data class Failed(val code: EpgFailureCode) : EpgHttpResult
}

class UrlConnectionEpgHttpClient(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 20_000,
    private val maxBytes: Int = 12 * 1024 * 1024,
) : EpgHttpClient {
    override fun fetch(url: String, etag: String?, lastModified: String?): EpgHttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/xml, text/xml, application/gzip, */*")
            // XmlTvParser 按 magic bytes 识别 gzip 并自带解压上限；允许 gzip 可把 EPG 传输量压低一个量级。
            setRequestProperty("Accept-Encoding", "gzip")
            etag?.let { setRequestProperty("If-None-Match", it) }
            lastModified?.let { setRequestProperty("If-Modified-Since", it) }
        }
        return try {
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> EpgHttpResult.NotModified
                in 200..299 -> {
                    val declaredSize = (connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L)
                    if (declaredSize > maxBytes) return EpgHttpResult.Failed(EpgFailureCode.TOO_LARGE)
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > maxBytes) return EpgHttpResult.Failed(EpgFailureCode.TOO_LARGE)
                            output.write(buffer, 0, count)
                        }
                    }
                    EpgHttpResult.Modified(
                        output.toByteArray(),
                        connection.getHeaderField("ETag"),
                        connection.getHeaderField("Last-Modified"),
                    )
                }
                else -> EpgHttpResult.Failed(EpgFailureCode.HTTP_STATUS)
            }
        } catch (_: IOException) {
            EpgHttpResult.Failed(EpgFailureCode.NETWORK)
        } finally {
            connection.disconnect()
        }
    }
}

private data class EpgCacheEntry(
    val payload: ByteArray,
    val lastSuccessAtMs: Long,
    val etag: String?,
    val lastModified: String?,
    val sourceUrl: String,
)

private data class MemoryGuide(val channelSignature: String, val guide: EpgGuide)

private data class SourceGuide(
    val schedules: Map<String, EpgChannelSchedule>,
    val state: EpgSourceState,
    val lastSuccessAtMs: Long?,
    val failure: EpgFailureCode?,
)

private fun channelSignature(channels: List<EpgChannelRef>): String = channels
    .sortedBy { it.channelId }
    .joinToString("\u001E") { channel ->
        listOf(channel.channelId, channel.tvgId.orEmpty(), channel.name, channel.identityKey.orEmpty())
            .plus(channel.aliases.sorted())
            .joinToString("\u001F")
    }

private const val RETRY_COOLDOWN_MS = 5 * 60 * 1000L

private fun stableSourceKey(sourceUrl: String): String =
    Hashing.sha256Hex(sourceUrl.toByteArray(Charsets.UTF_8)).take(24)

private class EpgDiskCache(private val directory: File) {
    private val payloadFile get() = File(directory, "guide.xml.bin")
    private val metadataFile get() = File(directory, "guide.properties")

    fun read(): EpgCacheEntry? = runCatching {
        if (!payloadFile.isFile || !metadataFile.isFile) return null
        val properties = Properties().apply { metadataFile.inputStream().use(::load) }
        EpgCacheEntry(
            payload = payloadFile.readBytes(),
            lastSuccessAtMs = properties.getProperty("lastSuccessAtMs")?.toLongOrNull() ?: return null,
            etag = properties.getProperty("etag")?.takeIf { it.isNotEmpty() },
            lastModified = properties.getProperty("lastModified")?.takeIf { it.isNotEmpty() },
            sourceUrl = properties.getProperty("sourceUrl") ?: return null,
        )
    }.getOrNull()

    fun write(payload: ByteArray, lastSuccessAtMs: Long, etag: String?, lastModified: String?, sourceUrl: String) {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("cannot create EPG cache")
        val payloadTemp = File(directory, "guide.xml.bin.tmp")
        val metadataTemp = File(directory, "guide.properties.tmp")
        payloadTemp.outputStream().use { it.write(payload) }
        Properties().apply {
            setProperty("lastSuccessAtMs", lastSuccessAtMs.toString())
            setProperty("etag", etag.orEmpty())
            setProperty("lastModified", lastModified.orEmpty())
            setProperty("sourceUrl", sourceUrl)
        }.let { properties -> metadataTemp.outputStream().use { properties.store(it, null) } }
        if (!payloadTemp.renameTo(payloadFile)) throw IOException("cannot replace EPG payload cache")
        if (!metadataTemp.renameTo(metadataFile)) throw IOException("cannot replace EPG metadata cache")
    }
}
