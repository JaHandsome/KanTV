package com.kantv.app.playback

import com.kantv.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HlsPrefetchProxyTest {
    @Test
    fun hugePlaylistAndRepeatedRefreshKeepDownloadAdmissionBounded() {
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:1\n" +
            (1..2_000).joinToString("") { "#EXTINF:1,\n$it.ts\n" }
        LocalHttpServer { path, _ ->
            if (path == "/live.m3u8") Response(manifest) else {
                calls.incrementAndGet()
                release.await(3, TimeUnit.SECONDS)
                Response("segment")
            }
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 2, maxCachedSegments = 4, initialStreamSegments = 1,
                pollFloorMs = 10, segmentWaitMs = 50).use { proxy ->
                try {
                    proxy.start("${upstream.url}/live.m3u8", emptyMap())
                    val refresh = HlsPrefetchProxy::class.java.getDeclaredMethod("refreshPlaylist").apply { isAccessible = true }
                    repeat(5) { refresh.invoke(proxy) }
                    val executor = HlsPrefetchProxy::class.java.getDeclaredField("downloader").apply { isAccessible = true }
                        .get(proxy) as java.util.concurrent.ThreadPoolExecutor
                    assertTrue("manifest must not queue one task per segment", executor.queue.size <= 4)
                    assertTrue(calls.get() <= 2)
                } finally { release.countDown() }
            }
        }
    }

    @Test
    fun hugeManifestKeepsTheOldestSlowBootstrapInsteadOfTrimmingItsTask() {
        val manifest = "#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n" +
            (1..200).joinToString("") { "#EXTINF:2,\n$it.ts\n" }
        LocalHttpServer { path, _ ->
            if (path == "/live.m3u8") Response(manifest) else {
                if (path == "/1.ts") Thread.sleep(200)
                Response("segment")
            }
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 2, maxCachedSegments = 4,
                initialStreamSegments = 1, playlistWaitMs = 700).use { proxy ->
                val local = proxy.start("${upstream.url}/live.m3u8", emptyMap()) as HlsPrefetchProxy.StartResult.Started
                val playlist = get(local.localPlaylistUri).text
                assertTrue("slow first segment must survive admission and trimming", playlist.contains("/segment/1\n"))
            }
        }
    }

    @Test
    fun simultaneousLocalRequestsShareThePrefetchDownload() {
        val calls = AtomicInteger()
        LocalHttpServer { path, headers ->
            if (path == "/live.m3u8") Response(
                "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:2,\none.ts\n#EXT-X-ENDLIST\n",
            ) else {
                if (headers["range"] == null) calls.incrementAndGet()
                Thread.sleep(250)
                Response("original")
            }
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 1, initialStreamSegments = 1, segmentWaitMs = 50).use { proxy ->
                val local = (proxy.start("${upstream.url}/live.m3u8", emptyMap()) as HlsPrefetchProxy.StartResult.Started)
                    .localUri.substringBeforeLast('/')
                val results = Collections.synchronizedList(mutableListOf<ByteArray>())
                val threads = List(4) { Thread { results += get("$local/segment/1").bytes }.apply { start() } }
                threads.forEach { it.join(2_000) }
                assertEquals(4, results.size)
                assertTrue(results.all { it.contentEquals("original".toByteArray()) })
                assertEquals(1, calls.get())
            }
        }
    }

    @Test
    fun demandOutsideThePrefetchWindowSharesTheAdmissionAndConcurrencyLimit() {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val demanded = AtomicInteger()
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:1\n" +
            (1..100).joinToString("") { "#EXTINF:1,\n$it.ts\n" }
        LocalHttpServer { path, headers ->
            when {
                path == "/live.m3u8" -> Response(manifest)
                headers["range"] != null -> Response("range-unsupported")
                else -> {
                    val count = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, count) }
                    if (path == "/50.ts") demanded.incrementAndGet()
                    Thread.sleep(80)
                    active.decrementAndGet()
                    Response(path)
                }
            }
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 2, maxCachedSegments = 4, initialStreamSegments = 1).use { proxy ->
                val started = proxy.start("${upstream.url}/live.m3u8", emptyMap()) as HlsPrefetchProxy.StartResult.Started
                val endpoint = started.localUri.substringBeforeLast('/') + "/segment/50"
                val results = Collections.synchronizedList(mutableListOf<String>())
                val threads = List(2) { Thread { results += get(endpoint).text }.apply { start() } }
                threads.forEach { it.join(2_000) }
                assertEquals(listOf("/50.ts", "/50.ts"), results.toList())
                assertEquals(1, demanded.get())
                assertTrue("HTTP demand must share downloader slots", peak.get() <= 2)
            }
        }
    }

    @Test
    fun boundedVodWindowsOnlyPublishEndListAtTheActualLastSegment() {
        val manifest = "#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:1\n" +
            (1..7).joinToString("") { "#EXTINF:1,\n$it.ts\n" } + "#EXT-X-ENDLIST\n"
        LocalHttpServer { path, _ ->
            if (path == "/live.m3u8") Response(manifest) else Response("segment")
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 2, maxCachedSegments = 4,
                initialStreamSegments = 1, pollFloorMs = 10).use { proxy ->
                val started = proxy.start("${upstream.url}/live.m3u8", emptyMap()) as HlsPrefetchProxy.StartResult.Started
                var body = get(started.localPlaylistUri).text
                assertFalse(body.contains("#EXT-X-ENDLIST"))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (!body.contains("#EXT-X-ENDLIST") && System.nanoTime() < deadline) {
                    Thread.sleep(20)
                    body = get(started.localPlaylistUri).text
                }
                assertTrue(body.contains("/segment/7\n"))
                assertTrue(body.contains("#EXT-X-ENDLIST"))
            }
        }
    }

    @Test
    fun independentProductionPreflightWaitsForTheOldestPublishableRun() {
        val releaseFirst = CountDownLatch(1)
        val tailReady = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = AtomicReference<HlsPrefetchProxy.StartResult>()
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> Response("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-INDEPENDENT-SEGMENTS\n" +
                    "#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:2,\nfirst.ts\n#EXTINF:2,\ntail.ts\n")
                "/first.ts" -> { releaseFirst.await(3, TimeUnit.SECONDS); Response("first") }
                else -> { tailReady.countDown(); Response("tail") }
            }
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 2).use { proxy ->
                val starter = Thread {
                    result.set(proxy.start("${upstream.url}/live.m3u8", emptyMap()))
                    finished.countDown()
                }.apply { start() }
                try {
                    assertTrue(tailReady.await(1, TimeUnit.SECONDS))
                    assertFalse("a fast tail cannot publish before the oldest segment", finished.await(150, TimeUnit.MILLISECONDS))
                    releaseFirst.countDown()
                    assertTrue(finished.await(2, TimeUnit.SECONDS))
                    val started = result.get() as HlsPrefetchProxy.StartResult.Started
                    assertTrue(get(started.localPlaylistUri).text.contains("/segment/1\n"))
                } finally { releaseFirst.countDown(); starter.join(2_000) }
            }
        }
    }

    @Test
    fun incompleteRequestsFromTheOldSessionCannotReadOrPublishTheNewSession() {
        LocalHttpServer { path, _ ->
            if (path.endsWith(".m3u8")) Response("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-INDEPENDENT-SEGMENTS\n" +
                "#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:1,\n${if (path == "/a.m3u8") "a.ts" else "b.ts"}\n")
            else Response(if (path == "/a.ts") "OLD" else "NEW")
        }.use { upstream ->
            HlsPrefetchProxy(initialStreamSegments = 1).use { proxy ->
                val a = proxy.start("${upstream.url}/a.m3u8", emptyMap()) as HlsPrefetchProxy.StartResult.Started
                val url = URL(a.localUri)
                val stale = listOf("/segment/1", "/playlist.m3u8").map { path ->
                    Socket(url.host, url.port).apply {
                        soTimeout = 1_000
                        getOutputStream().write("GET $path HTTP/1.0\r\nHost: localhost\r\n".toByteArray())
                        getOutputStream().flush()
                    }
                }
                try {
                    val clients = HlsPrefetchProxy::class.java.getDeclaredField("clients").apply { isAccessible = true }
                        .get(proxy) as java.util.concurrent.ThreadPoolExecutor
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                    while (clients.activeCount < 2 && System.nanoTime() < deadline) Thread.sleep(10)
                    assertTrue(clients.activeCount >= 2)
                    Thread.sleep(40) // Allow both accepted handlers to reach the incomplete header.
                    proxy.stop()
                    val b = proxy.start("${upstream.url}/b.m3u8", emptyMap()) as HlsPrefetchProxy.StartResult.Started
                    stale.forEach { socket ->
                        val response = try {
                            socket.getOutputStream().write("\r\n".toByteArray())
                            socket.getOutputStream().flush()
                            socket.getInputStream().readBytes()
                        } catch (_: java.io.IOException) { ByteArray(0) }
                        assertTrue("obsolete request must close before rebinding", response.isEmpty())
                    }
                    val published = HlsPrefetchProxy::class.java.getDeclaredField("publishedEndSequence").apply { isAccessible = true }
                    assertNull(published.get(proxy))
                    assertEquals("NEW", get(b.localUri.substringBeforeLast('/') + "/segment/1").text)
                    assertTrue(get(b.localPlaylistUri).text.contains("/segment/1\n"))
                } finally { stale.forEach { it.close() } }
            }
        }
    }

    @Test
    fun productionProxyAlwaysServesOriginalBytesEvenWhenAnalysisWouldRepair() {
        val original = timestampAnomalyStream(120 * 90_000L)
        assertTrue(MpegTsAudioTimestampRepair.analyze(original).wouldRepair)
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> Response(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n" +
                        "#EXTINF:2,\none.ts\n#EXT-X-ENDLIST\n",
                )
                "/one.ts" -> Response(200, original)
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            HlsPrefetchProxy(initialStreamSegments = 1).use { proxy ->
                val started = proxy.start("${upstream.url}/live.m3u8", emptyMap())
                    as HlsPrefetchProxy.StartResult.Started
                assertTrue(original.contentEquals(get("${started.localUri.substringBeforeLast('/')}/segment/1").bytes))
                if (BuildConfig.KANTV_DIAGNOSTICS) {
                    assertEquals(TransportStreamInputSource.ORIGINAL, proxy.latestTsObservation()?.inputSource)
                    assertTrue(proxy.latestTsObservation()?.wouldRepair == true)
                } else {
                    assertNull("Disabled diagnostics must not scan or retain TS observations", proxy.latestTsObservation())
                }
            }
        }
    }

    @Test
    fun acceptsAbsoluteFormLoopbackRequestWithoutBecomingAnOpenProxy() {
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> Response(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:2,\none.ts\n#EXT-X-ENDLIST\n",
                )
                "/one.ts" -> Response("segment")
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            HlsPrefetchProxy(initialStreamSegments = 1).use { proxy ->
                val local = (proxy.start(
                    "${upstream.url}/live.m3u8",
                    emptyMap(),
                ) as HlsPrefetchProxy.StartResult.Started).localUri
                val url = URL(local)
                Socket(url.host, url.port).use { client ->
                    client.getOutputStream().write(
                        "GET $local HTTP/1.1\r\nHost: ${url.host}:${url.port}\r\n\r\n".toByteArray(),
                    )
                    client.getOutputStream().flush()
                    val response = client.getInputStream().readNBytes(512).toString(Charsets.ISO_8859_1)
                    assertTrue(response.startsWith("HTTP/1.0 200"))
                    assertTrue(response.endsWith("segment"))
                }
            }
        }
    }

    @Test
    fun proxiesMediaPlaylistAndDownloadsNewSegmentsInParallel() {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        LocalHttpServer { path, headers ->
            when (path) {
                "/live.m3u8" -> Response.playlist
                "/one.ts", "/two.ts" -> {
                    assertEquals("token-value", headers["x-test-token"])
                    val now = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, now) }
                    Thread.sleep(120)
                    active.decrementAndGet()
                    Response(200, path.removePrefix("/").toByteArray())
                }
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 2, initialStreamSegments = 2, segmentWaitMs = 1_000).use { proxy ->
                val started = proxy.start("${upstream.url}/live.m3u8", mapOf("X-Test-Token" to "token-value"))
                assertTrue(started is HlsPrefetchProxy.StartResult.Started)
                started as HlsPrefetchProxy.StartResult.Started
                val local = started.localUri
                val base = local.substringBeforeLast('/')
                assertEquals("$base/playlist.m3u8", started.localPlaylistUri)
                assertEquals("$base/stream.ts", started.localUri)
                assertEquals(started.localUri, started.continuousStreamUri)
                val playlist = get(started.localPlaylistUri).text
                assertTrue(playlist.contains("#EXT-X-MEDIA-SEQUENCE:41"))
                assertTrue(playlist.contains("/segment/41"))
                assertFalse(playlist.contains("live.m3u8"))
                assertEquals("one.ts", get("$base/segment/41").text)
                assertEquals("two.ts", get("$base/segment/42").text)
                requireNotNull(proxy.openContinuousStream()).use { stream ->
                    assertEquals("one.tstwo.ts", stream.input.readNBytes(12).toString(Charsets.UTF_8))
                }
                assertTrue("expected distinct segments to overlap", peak.get() >= 2)
            }
        }
    }

    @Test
    fun waitsBrieflyForAnInFlightSegment() {
        val served = CountDownLatch(1)
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> Response("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:9\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXTINF:2,\nslow.ts\n")
                "/slow.ts" -> { Thread.sleep(180); served.countDown(); Response(200, "ready".toByteArray()) }
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            HlsPrefetchProxy(segmentWaitMs = 800).use { proxy ->
                val local = (proxy.start("${upstream.url}/live.m3u8", emptyMap()) as HlsPrefetchProxy.StartResult.Started).localUri
                assertEquals("ready", get("${local.substringBeforeLast('/')}/segment/9").text)
                assertTrue(served.await(1, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun cancellingPendingContinuousStreamUnblocksHeaderWait() {
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> Response(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n" +
                        "#EXTINF:2,\none.ts\n#EXTINF:2,\ntwo.ts\n",
                )
                "/one.ts", "/two.ts" -> { Thread.sleep(2_000); Response(200, path.toByteArray()) }
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            HlsPrefetchProxy(initialStreamSegments = 2, playlistWaitMs = 5_000).use { proxy ->
                assertTrue(proxy.start("${upstream.url}/live.m3u8", emptyMap()) is HlsPrefetchProxy.StartResult.Started)
                val finished = CountDownLatch(1)
                val opener = Thread {
                    proxy.openContinuousStream()?.close()
                    finished.countDown()
                }.apply { start() }
                Thread.sleep(100)
                proxy.cancelPendingStream()
                assertTrue("cancellation should close the header wait", finished.await(1, TimeUnit.SECONDS))
                opener.join(1_000)
            }
        }
    }

    @Test
    fun abortingSessionDisconnectsAnInitialManifestWait() {
        val manifestStarted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        LocalHttpServer { path, _ ->
            when (path) {
                "/slow.m3u8" -> {
                    manifestStarted.countDown()
                    releaseServer.await(3, TimeUnit.SECONDS)
                    Response("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\none.ts\n")
                }
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            try {
                HlsPrefetchProxy().use { proxy ->
                    val finished = CountDownLatch(1)
                    Thread {
                        proxy.start("${upstream.url}/slow.m3u8", emptyMap())
                        finished.countDown()
                    }.start()
                    assertTrue(manifestStarted.await(1, TimeUnit.SECONDS))

                    proxy.abortPendingSession()

                    assertTrue("abort should disconnect the blocking manifest", finished.await(1, TimeUnit.SECONDS))
                }
            } finally {
                releaseServer.countDown()
            }
        }
    }

    @Test
    fun stoppingWhileContinuousStreamIsOpeningDoesNotEscapeAsUncaughtException() {
        val uncaught = AtomicReference<Throwable?>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, failure -> uncaught.compareAndSet(null, failure) }
        try {
            LocalHttpServer { path, _ ->
                when (path) {
                    "/live.m3u8" -> Response(
                        "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n" +
                            "#EXTINF:2,\nslow.ts\n",
                    )
                    "/slow.ts" -> { Thread.sleep(300); Response("segment") }
                    else -> Response(404, ByteArray(0))
                }
            }.use { upstream ->
                HlsPrefetchProxy(initialStreamSegments = 1, playlistWaitMs = 1_000).use { proxy ->
                    val local = (proxy.start(
                        "${upstream.url}/live.m3u8",
                        emptyMap(),
                    ) as HlsPrefetchProxy.StartResult.Started).localUri
                    Socket(URL(local).host, URL(local).port).use { client ->
                        client.getOutputStream().write(
                            "GET /stream.ts HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray(),
                        )
                        client.getOutputStream().flush()
                        Thread.sleep(30)
                        proxy.stop()
                    }
                    Thread.sleep(100)
                    assertNull(uncaught.get())
                }
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test
    fun fiftyRapidStreamReopensDoNotLeakCancellationAsUncaughtException() {
        val uncaught = AtomicReference<Throwable?>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, failure -> uncaught.compareAndSet(null, failure) }
        try {
            LocalHttpServer { path, _ ->
                when (path) {
                    "/live.m3u8" -> Response(
                        "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:1,\nsegment.ts\n",
                    )
                    "/segment.ts" -> Response("segment")
                    else -> Response(404, ByteArray(0))
                }
            }.use { upstream ->
                HlsPrefetchProxy(initialStreamSegments = 1, playlistWaitMs = 1_000).use { proxy ->
                    repeat(50) {
                        val local = (proxy.start("${upstream.url}/live.m3u8", emptyMap()) as HlsPrefetchProxy.StartResult.Started).localUri
                        Socket(URL(local).host, URL(local).port).use { client ->
                            client.getOutputStream().write("GET /stream.ts HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
                            client.getOutputStream().flush()
                            proxy.stop()
                        }
                    }
                    Thread.sleep(150)
                    assertNull(uncaught.get())
                }
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test
    fun rangeAccelerationReassemblesExactBytesAndRejectsWrongRanges() {
        val payload = ByteArray(1_200_000) { (it % 251).toByte() }
        for ((duration, invalidRange) in listOf(5 to false, 5 to true, 2 to false, 2 to true)) {
            val parts = AtomicInteger()
            LocalHttpServer { path, headers ->
                if (path == "/live.m3u8") {
                    Response("#EXTM3U\n#EXT-X-TARGETDURATION:$duration\n#EXTINF:$duration,\nsegment.ts\n")
                } else {
                    val range = headers["range"]
                    if (range == null) Response(200, payload)
                    else {
                        val (from, to) = range.removePrefix("bytes=").split('-').map(String::toInt)
                        if (to > 0) { parts.incrementAndGet(); Thread.sleep(30) }
                        Response(206, payload.copyOfRange(from, to + 1), mapOf(
                            "ETag" to "\"stable\"",
                            "Content-Range" to if (invalidRange && to > 0) "bytes 0-0/${payload.size}"
                                else "bytes $from-$to/${payload.size}",
                        ))
                    }
                }
            }.use { upstream ->
                HlsPrefetchProxy(initialStreamSegments = 1).use { proxy ->
                    val local = (proxy.start("${upstream.url}/live.m3u8", emptyMap()) as HlsPrefetchProxy.StartResult.Started).localUri
                    org.junit.Assert.assertArrayEquals(payload, getPrefix(local, payload.size))
                    if (!invalidRange) assertEquals(if (duration == 2) 8 else 3, parts.get())
                    else assertTrue(parts.get() >= 1)
                }
            }
        }
    }

    @Test
    fun startupPreservesSlowOldestSegmentInsteadOfSkippingToFastTail() {
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> Response("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nold.ts\n#EXTINF:1,\nnext.ts\n#EXTINF:1,\ntail.ts\n")
                "/old.ts" -> { Thread.sleep(250); Response("old") }
                "/next.ts" -> Response("next")
                "/tail.ts" -> Response("tail")
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 3, initialStreamSegments = 2).use { proxy ->
                val local = (proxy.start("${upstream.url}/live.m3u8", emptyMap()) as HlsPrefetchProxy.StartResult.Started).localUri
                assertEquals("oldnexttail", String(getPrefix(local, 11)))
            }
        }
    }

    @Test
    fun newSessionDoesNotWaitBehindCancelledDownloads() {
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        LocalHttpServer { path, _ ->
            when (path) {
                "/old.m3u8" -> Response("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nold.ts\n" +
                    (1..500).joinToString("") { "#EXTINF:1,\nqueued.ts\n" })
                "/new.m3u8" -> Response("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nnew.ts\n")
                "/old.ts" -> {
                    oldStarted.countDown()
                    releaseOld.await(3, TimeUnit.SECONDS)
                    Response("old")
                }
                "/queued.ts" -> Response("obsolete")
                "/new.ts" -> Response("new")
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            try {
                HlsPrefetchProxy(parallelDownloads = 1, initialStreamSegments = 1).use { proxy ->
                    proxy.start("${upstream.url}/old.m3u8", emptyMap())
                    assertTrue(oldStarted.await(1, TimeUnit.SECONDS))
                    proxy.abortPendingSession()
                    val local = (proxy.start("${upstream.url}/new.m3u8", emptyMap()) as HlsPrefetchProxy.StartResult.Started).localUri
                    val connection = URL(local).openConnection().apply { readTimeout = 800 }
                    assertEquals("new", connection.getInputStream().use { String(it.readNBytes(3)) })
                }
            } finally { releaseOld.countDown() }
        }
    }

    @Test
    fun refusesMasterEncryptedAndInitializationPlaylistsBeforeStartingLocalServer() {
        val unsupported = listOf(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nvariant.m3u8\n",
            "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-KEY:METHOD=AES-128,URI=key\n#EXTINF:2,\na.ts\n",
            "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MAP:URI=init.mp4\n#EXTINF:2,\na.m4s\n",
            "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\n#EXT-X-BYTERANGE:100@0\na.ts\n",
        )
        unsupported.forEach { body ->
            LocalHttpServer { _, _ -> Response(body) }.use { upstream ->
                HlsPrefetchProxy().use { proxy ->
                    assertTrue(proxy.start("${upstream.url}/live.m3u8", emptyMap()) is HlsPrefetchProxy.StartResult.Fallback)
                }
            }
        }
    }

    @Test
    fun cachedPlaylistPreservesTagsAndCorrectsDiscontinuitySequenceAfterWindowTrim() {
        val manifest = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:2")
            appendLine("#EXT-X-MEDIA-SEQUENCE:100")
            appendLine("#EXT-X-DISCONTINUITY-SEQUENCE:7")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            repeat(8) { index ->
                if (index == 1 || index == 4) appendLine("#EXT-X-DISCONTINUITY")
                appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-09-10T00:00:${index.toString().padStart(2, '0')}Z")
                appendLine("#EXTINF:2.0,")
                appendLine("$index.ts")
            }
        }
        LocalHttpServer { path, _ ->
            if (path == "/live.m3u8") Response(manifest)
            else if (path.endsWith(".ts")) Response(path)
            else Response(404, ByteArray(0))
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 8, initialStreamSegments = 1).use { proxy ->
                val started = proxy.start("${upstream.url}/live.m3u8", emptyMap()) as HlsPrefetchProxy.StartResult.Started
                var rendered = ""
                repeat(20) {
                    Thread.sleep(25)
                    rendered = get(started.localPlaylistUri).text
                    if (rendered.contains("/segment/107")) return@repeat
                }

                assertTrue(rendered.contains("#EXT-X-INDEPENDENT-SEGMENTS"))
                assertTrue(rendered.contains("#EXT-X-MEDIA-SEQUENCE:100"))
                assertTrue(rendered.contains("#EXT-X-DISCONTINUITY-SEQUENCE:7"))
                assertTrue(rendered.contains("#EXT-X-PROGRAM-DATE-TIME:2026-09-10T00:00:00Z"))
                assertTrue(rendered.contains("#EXTINF:2.0,"))
                assertTrue(rendered.contains("/segment/100"))
                assertFalse(rendered.contains("/segment/101"))
            }
        }
    }

    @Test
    fun gapAndLowLatencyPlaylistsExplicitlyFallback() {
        val unsupported = listOf(
            "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-GAP\n#EXTINF:2,\na.ts\n" to HlsPrefetchProxy.Reason.GAP,
            "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES\n#EXT-X-PART:DURATION=0.2,URI=part.ts\n#EXTINF:2,\na.ts\n" to HlsPrefetchProxy.Reason.LOW_LATENCY_HLS,
        )
        unsupported.forEach { (manifest, reason) ->
            LocalHttpServer { _, _ -> Response(manifest) }.use { upstream ->
                HlsPrefetchProxy().use { proxy ->
                    assertEquals(
                        HlsPrefetchProxy.StartResult.Fallback(reason),
                        proxy.start("${upstream.url}/live.m3u8", emptyMap()),
                    )
                    assertEquals(
                        PrefetchStartupPolicy.StartupOutcome.UNSUPPORTED_PLAYLIST,
                        proxy.status().startupOutcome,
                    )
                    assertEquals(reason, proxy.status().startupReason)
                }
            }
        }
    }

    @Test
    fun media3CachedSelectionUsesPlaylistMimeAndNeverContinuousStreamUri() {
        val original = PlaybackRequest("station", "https://media.example.invalid/live.m3u8")
        val selected = cachedHlsPlaybackRequest(original, "http://127.0.0.1:1234/playlist.m3u8")

        assertEquals("http://127.0.0.1:1234/playlist.m3u8", selected.uri)
        assertEquals(androidx.media3.common.MimeTypes.APPLICATION_M3U8, selected.mimeType)
        assertTrue(selected.requestHeaders.isEmpty())
        assertFalse(selected.uri.endsWith("stream.ts"))
    }

    @Test
    fun doesNotForwardAuthenticationOrCookiesToCrossOriginSegments() {
        val received = AtomicReference<Map<String, String>>()
        LocalHttpServer { _, headers ->
            received.set(headers)
            Response("segment")
        }.use { segmentServer ->
            LocalHttpServer { path, _ ->
                if (path == "/live.m3u8") {
                    Response(
                        "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n" +
                            "#EXTINF:2,\n${segmentServer.url}/one.ts\n",
                    )
                } else {
                    Response(404, ByteArray(0))
                }
            }.use { playlistServer ->
                HlsPrefetchProxy(initialStreamSegments = 1).use { proxy ->
                    val result = proxy.start(
                        "${playlistServer.url}/live.m3u8",
                        mapOf(
                            "Authorization" to "secret-token",
                            "Cookie" to "session=secret",
                            "User-Agent" to "kantv-test",
                        ),
                    )
                    val local = (result as HlsPrefetchProxy.StartResult.Started).localUri
                    assertEquals("segment", get("${local.substringBeforeLast('/')}/segment/1").text)
                    val headers = received.get()
                    assertFalse(headers.containsKey("authorization"))
                    assertFalse(headers.containsKey("cookie"))
                    assertEquals("kantv-test", headers["user-agent"])
                }
            }
        }
    }

    @Test
    fun neverAdvertisesANewerReadySegmentAcrossAMissingSequence() {
        val playlistReads = AtomicInteger()
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> if (playlistReads.incrementAndGet() == 1) {
                    Response("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:41\n#EXTINF:1,\n41.ts\n#EXTINF:1,\n42.ts\n")
                } else {
                    Response("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:41\n#EXTINF:1,\n41.ts\n#EXTINF:1,\n42.ts\n#EXTINF:1,\n43.ts\n#EXTINF:1,\n44.ts\n")
                }
                "/43.ts" -> { Thread.sleep(900); Response("43") }
                "/41.ts", "/42.ts", "/44.ts" -> Response(path.removePrefix("/"))
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 2, initialStreamSegments = 2, pollFloorMs = 50).use { proxy ->
                val local = (proxy.start("${upstream.url}/live.m3u8", emptyMap()) as HlsPrefetchProxy.StartResult.Started).localUri
                val playlistUrl = "${local.substringBeforeLast('/')}/playlist.m3u8"
                assertTrue(get(playlistUrl).text.contains("/segment/42"))
                Thread.sleep(250)

                val whileMissing = get(playlistUrl).text
                // 不变量（产品行为）：窗口只发布**连续已就绪**的区间，绝不跳过缺失序号。
                // 所以"44 在窗口里"本身就要求 43 也在；反之断言"44 一定不在"是错的——
                // 那取决于 43 此刻是否已下完（负载低时会更快），属环境竞速而非行为差异。
                assertTrue(whileMissing.contains("/segment/42"))
                if (whileMissing.contains("/segment/44")) {
                    assertTrue(
                        "发布了 44 就必须同时发布 43（窗口必须连续）",
                        whileMissing.contains("/segment/43"),
                    )
                }

                // 等到 43 与 44 **都**出现为止：原先"一看到 43 就停"，随后断言 44 也在，
                // 但 44 可能尚未下完（仅 20ms 的差），属同一类环境竞速。
                var afterGapFilled = ""
                polling@ for (attempt in 0 until 40) {
                    Thread.sleep(100)
                    afterGapFilled = get(playlistUrl).text
                    if (afterGapFilled.contains("/segment/43") &&
                        afterGapFilled.contains("/segment/44")
                    ) {
                        break@polling
                    }
                }
                assertTrue(
                    "缺失序号补齐后 43 必须出现；实际窗口: $afterGapFilled",
                    afterGapFilled.contains("/segment/43"),
                )
                assertTrue(
                    "43 就绪后 44 也必须被发布；实际窗口: $afterGapFilled",
                    afterGapFilled.contains("/segment/44"),
                )
            }
        }
    }

    @Test
    fun cachedPlaylistPublishesOneIndependentSegmentWithoutWaitingForFour() {
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n#EXT-X-INDEPENDENT-SEGMENTS\n" +
            (1..4).joinToString("") { "#EXTINF:2,\n$it.ts\n" }
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> Response(manifest)
                "/1.ts" -> Response("one")
                else -> { Thread.sleep(500); Response(path) }
            }
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 4, playlistWaitMs = 300).use { proxy ->
                val started = proxy.start("${upstream.url}/live.m3u8", emptyMap()) as HlsPrefetchProxy.StartResult.Started
                val playlist = get(started.localPlaylistUri).text
                assertTrue(playlist.contains("/segment/1"))
                assertFalse(playlist.contains("/segment/2"))
            }
        }
    }

    @Test
    fun cachedPlaylistPublishesEarliestCompleteH264BootstrapPrefix() {
        val bootstrap = h264BootstrapSegment()
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n" +
            (1..4).joinToString("") { "#EXTINF:2,\n$it.ts\n" }
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> Response(manifest)
                "/1.ts" -> Response(200, bootstrap)
                else -> { Thread.sleep(500); Response(path) }
            }
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 4, playlistWaitMs = 300).use { proxy ->
                val started = proxy.start("${upstream.url}/live.m3u8", emptyMap()) as HlsPrefetchProxy.StartResult.Started
                val playlist = get(started.localPlaylistUri).text
                assertTrue(playlist.contains("/segment/1"))
                assertFalse(playlist.contains("/segment/2"))
                org.junit.Assert.assertArrayEquals(bootstrap, get("${started.localUri.substringBeforeLast('/')}/segment/1").bytes)
            }
        }
    }

    @Test
    fun cachedPlaylistDoesNotCombineBootstrapAcrossDiscontinuity() {
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n" +
            "#EXTINF:2,\n1.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:2,\n2.ts\n"
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> Response(manifest)
                "/1.ts" -> Response(200, h264BootstrapSegment(includeIdr = false))
                "/2.ts" -> Response(200, h264BootstrapSegment())
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 2, playlistWaitMs = 100).use { proxy ->
                assertEquals(
                    HlsPrefetchProxy.StartResult.Fallback(HlsPrefetchProxy.Reason.UNVERIFIED_BOOTSTRAP),
                    proxy.start("${upstream.url}/live.m3u8", emptyMap()),
                )
            }
        }
    }

    @Test
    fun cachedPlaylistDoesNotPublishFourKnownH264SegmentsWithoutAnIdr() {
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n" +
            (1..4).joinToString("") { "#EXTINF:2,\n$it.ts\n" }
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> Response(manifest)
                "/1.ts", "/2.ts", "/3.ts", "/4.ts" ->
                    Response(200, h264BootstrapSegment(includeIdr = false))
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 4, playlistWaitMs = 100).use { proxy ->
                assertEquals(
                    HlsPrefetchProxy.StartResult.Fallback(HlsPrefetchProxy.Reason.UNVERIFIED_BOOTSTRAP),
                    proxy.start("${upstream.url}/live.m3u8", emptyMap()),
                )
            }
        }
    }

    @Test
    fun unknownCodecWithoutIndependentSegmentGuaranteeFallsBackBeforeLocalPlayback() {
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n" +
            (1..4).joinToString("") { "#EXTINF:2,\n$it.ts\n" }
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> Response(manifest)
                "/1.ts", "/2.ts", "/3.ts", "/4.ts" -> Response("not-h264-$path")
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 4, segmentWaitMs = 500).use { proxy ->
                assertEquals(
                    HlsPrefetchProxy.StartResult.Fallback(HlsPrefetchProxy.Reason.UNSUPPORTED_CODEC),
                    proxy.start("${upstream.url}/live.m3u8", emptyMap()),
                )
            }
        }
    }

    @Test
    fun hevcTransportStreamWithoutIndependentGuaranteeFallsBackBeforeLocalPlayback() {
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n" +
            (1..4).joinToString("") { "#EXTINF:2,\n$it.ts\n" }
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> Response(manifest)
                "/1.ts", "/2.ts", "/3.ts", "/4.ts" ->
                    Response(200, h264BootstrapSegment(streamType = 0x24))
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 4, segmentWaitMs = 500).use { proxy ->
                assertEquals(
                    HlsPrefetchProxy.StartResult.Fallback(HlsPrefetchProxy.Reason.UNSUPPORTED_CODEC),
                    proxy.start("${upstream.url}/live.m3u8", emptyMap()),
                )
            }
        }
    }

    @Test
    fun aValidSegmentSlowerThanTheOldFixedProbeCompletesInsteadOfFallingBack() {
        // 2.5 s for one real H.264 segment: slower than the legacy 1.5 s preflight, yet perfectly
        // decodable. The bounded, derived cap must let it finish instead of leaving for the direct
        // source before localhost is ever used.
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:2,\none.ts\n"
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> Response(manifest)
                "/one.ts" -> { Thread.sleep(2_500); Response(200, h264BootstrapSegment()) }
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            HlsPrefetchProxy(segmentWaitMs = 1_500, pollFloorMs = 200).use { proxy ->
                val started = proxy.start("${upstream.url}/live.m3u8", emptyMap())

                assertTrue("slow but valid must start", started is HlsPrefetchProxy.StartResult.Started)
                val status = proxy.status()
                assertEquals(
                    PrefetchStartupPolicy.StartupOutcome.VERIFIED_BOOTSTRAP,
                    status.startupOutcome,
                )
                assertTrue("must have waited past the old fixed probe", status.startupWaitMs > 1_500L)
                assertTrue("wait must stay inside the bounded cap", status.startupWaitMs <= status.startupCapMs)
                assertTrue(status.ready >= 1)
                assertEquals(0, status.failed)
            }
        }
    }

    @Test
    fun aResolvedWindowWithoutDecodableEvidenceNeverReportsVerifiedBootstrap() {
        // Four "ready" segments that carry no decoder bootstrap at all. The old code treated a ready
        // count as success; the outcome must instead be an honest, fail-closed fallback.
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n" +
            (1..4).joinToString("") { "#EXTINF:2,\n$it.ts\n" }
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> Response(manifest)
                "/1.ts", "/2.ts", "/3.ts", "/4.ts" -> Response(200, h264BootstrapSegment(includeIdr = false))
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 4, segmentWaitMs = 1_500, pollFloorMs = 200).use { proxy ->
                val result = proxy.start("${upstream.url}/live.m3u8", emptyMap())

                assertEquals(
                    HlsPrefetchProxy.StartResult.Fallback(HlsPrefetchProxy.Reason.UNVERIFIED_BOOTSTRAP),
                    result,
                )
                val status = proxy.status()
                assertEquals(
                    PrefetchStartupPolicy.StartupOutcome.UNVERIFIED_BOOTSTRAP,
                    status.startupOutcome,
                )
                assertTrue("segments did download", status.ready >= 1)
                assertTrue("nothing was ever verified", status.startupOutcome != PrefetchStartupPolicy.StartupOutcome.VERIFIED_BOOTSTRAP)
            }
        }
    }

    @Test
    fun stoppingDuringThePreflightIsCountedAsCancelledDownloadsNotFailures() {
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n" +
            (1..4).joinToString("") { "#EXTINF:2,\n$it.ts\n" }
        val firstFetch = CountDownLatch(1)
        val release = CountDownLatch(1)
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> Response(manifest)
                else -> {
                    firstFetch.countDown()
                    release.await(3, TimeUnit.SECONDS)
                    Response(200, h264BootstrapSegment())
                }
            }
        }.use { upstream ->
            HlsPrefetchProxy(parallelDownloads = 2, segmentWaitMs = 5_000, pollFloorMs = 200).use { proxy ->
                val finished = CountDownLatch(1)
                Thread {
                    proxy.start("${upstream.url}/live.m3u8", emptyMap())
                    finished.countDown()
                }.start()
                assertTrue(firstFetch.await(2, TimeUnit.SECONDS))

                proxy.abortPendingSession()
                proxy.stop()
                release.countDown()

                assertTrue("cancellation must end the preflight", finished.await(3, TimeUnit.SECONDS))
                val afterStop = proxy.status()
                // The exact CANCELLED outcome for an interrupted preflight is pinned by
                // PrefetchStartupPolicyTest.aSessionThatStopsDuringThePreflightIsCancelledNotAFailedDownload.
                // What this test proves is the accounting: a stop is cancellation, so no download may
                // be recorded as a network failure and no obsolete bytes may survive as ready.
                assertEquals(0, afterStop.failed)
                assertEquals(0, afterStop.ready)
                assertTrue("cancelled downloads must be counted", afterStop.cancelled >= 1)
            }
        }
    }

    @Test
    fun callerGenerationCancellationCannotRestartAnObsoletePreflight() {
        val manifestRequest = CountDownLatch(1)
        val releaseManifest = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val result = AtomicReference<HlsPrefetchProxy.StartResult>()
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> {
                    manifestRequest.countDown()
                    releaseManifest.await(3, TimeUnit.SECONDS)
                    Response("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\none.ts\n")
                }
                "/one.ts" -> Response(200, h264BootstrapSegment())
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            HlsPrefetchProxy().use { proxy ->
                val finished = CountDownLatch(1)
                Thread {
                    result.set(proxy.start("${upstream.url}/live.m3u8", emptyMap(), cancelled::get))
                    finished.countDown()
                }.start()
                assertTrue(manifestRequest.await(2, TimeUnit.SECONDS))

                cancelled.set(true)
                proxy.abortPendingSession()
                releaseManifest.countDown()

                assertTrue("obsolete open must end promptly", finished.await(2, TimeUnit.SECONDS))
                assertTrue(result.get() is HlsPrefetchProxy.StartResult.Fallback)
                assertEquals(PrefetchStartupPolicy.StartupOutcome.CANCELLED, proxy.status().startupOutcome)
                assertEquals(0, proxy.status().failed)
            }
        }
    }

    @Test
    fun aDecodableSegmentWithoutTheIndependenceDeclarationIsStillVerified() {
        // No `#EXT-X-INDEPENDENT-SEGMENTS`, one segment. Only the inspector's own evidence can make
        // this a success, which is exactly the difference between a proof and a ready count.
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:2,\none.ts\n"
        LocalHttpServer { path, _ ->
            when (path) {
                "/live.m3u8" -> Response(manifest)
                "/one.ts" -> Response(200, h264BootstrapSegment())
                else -> Response(404, ByteArray(0))
            }
        }.use { upstream ->
            HlsPrefetchProxy(segmentWaitMs = 1_500, pollFloorMs = 200).use { proxy ->
                assertTrue(proxy.start("${upstream.url}/live.m3u8", emptyMap()) is HlsPrefetchProxy.StartResult.Started)
                assertEquals(
                    PrefetchStartupPolicy.StartupOutcome.VERIFIED_BOOTSTRAP,
                    proxy.status().startupOutcome,
                )
            }
        }
    }

    private fun h264BootstrapSegment(includeIdr: Boolean = true, streamType: Int = 0x1b): ByteArray {
        fun packet(pid: Int, payload: ByteArray, pcr: Boolean = false): ByteArray {
            val packet = ByteArray(188) { 0xff.toByte() }
            packet[0] = 0x47
            packet[1] = (0x40 or (pid ushr 8)).toByte()
            packet[2] = pid.toByte()
            packet[3] = if (pcr) 0x30 else 0x10
            var offset = 4
            if (pcr) {
                packet[offset++] = 7
                packet[offset++] = 0x10
                repeat(6) { packet[offset++] = 0 }
            }
            payload.copyInto(packet, offset, 0, minOf(payload.size, packet.size - offset))
            return packet
        }
        val pat = packet(0, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xe1.toByte(), 0))
        val pmt = packet(0x100, byteArrayOf(0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, streamType.toByte(), 0xe1.toByte(), 1, 0, 0))
        val pcr = packet(0x101, byteArrayOf(0), pcr = true)
        val videoPayload = if (includeIdr) byteArrayOf(0, 0, 1, 7, 0, 0, 1, 8, 0, 0, 1, 5)
        else byteArrayOf(0, 0, 1, 7, 0, 0, 1, 8)
        val video = packet(0x101, videoPayload)
        return pat + pmt + pcr + video
    }

    private fun get(url: String): Response {
        val connection = URL(url).openConnection()
        return connection.getInputStream().use { Response(connection.getHeaderFieldInt("", 200), it.readBytes()) }
    }

    private fun getPrefix(url: String, count: Int): ByteArray {
        val connection = URL(url).openConnection()
        return connection.getInputStream().use { it.readNBytes(count) }
    }
}

private data class Response(val status: Int = 200, val bytes: ByteArray, val headers: Map<String, String> = emptyMap()) {
    constructor(body: String) : this(200, body.toByteArray())
    val text: String get() = bytes.toString(Charsets.UTF_8)

    companion object {
        val playlist = Response(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:41\n#EXTINF:2,\none.ts\n#EXTINF:2,\ntwo.ts\n",
        )
    }
}

private class LocalHttpServer(private val responder: (String, Map<String, String>) -> Response) : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Collections.synchronizedList(mutableListOf<Thread>())
    @Volatile private var open = true
    private val acceptor = Thread {
        while (open) try {
            val socket = server.accept()
            Thread { serve(socket) }.also { workers += it; it.start() }
        } catch (_: Exception) { return@Thread }
    }.apply { isDaemon = true; start() }

    val url: String = "http://127.0.0.1:${server.localPort}"

    private fun serve(socket: Socket) = socket.use {
        val input = it.getInputStream().bufferedReader(Charsets.US_ASCII)
        val request = input.readLine() ?: return
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            line.substringBefore(':').lowercase().let { headers[it] = line.substringAfter(':').trim() }
        }
        val response = responder(request.split(' ')[1], headers)
        try { it.getOutputStream().use { output ->
            output.write("HTTP/1.1 ${response.status} OK\r\nContent-Length: ${response.bytes.size}\r\nConnection: close\r\n${response.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }}\r\n".toByteArray())
            output.write(response.bytes)
        } } catch (_: java.io.IOException) { /* Cancellation deliberately closes the client. */ }
    }

    override fun close() {
        open = false
        server.close()
        acceptor.join(500)
        workers.forEach { it.join(500) }
    }
}
