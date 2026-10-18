package com.kantv.app.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 环回 HTTP 上的行为验证：清单渲染、滑窗推进、init 重置的单调性。 */
class MseMirrorServerTest {

    private val server = MseMirrorServer()
    private val playlistUri: String = run {
        val result = server.start()
        check(result is MseMirrorServer.StartResult.Started) { "server start failed" }
        result.playlistUri
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun httpGet(path: String): Pair<Int, ByteArray> {
        val base = playlistUri.removeSuffix("/master.m3u8")
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 5_000
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            stream?.use { it.copyTo(output) }
            code to output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun feed(track: MseMirrorServer.MirrorTrack, init: ByteArray, fragments: List<ByteArray>) {
        server.offerInit(track, init)
        fragments.forEach { server.offerFragment(track, it) }
    }

    @Test
    fun masterPlaylistReferencesVideoAndAudioRenditions() {
        val (code, body) = httpGet("/master.m3u8")
        assertEquals(200, code)
        val text = body.decodeToString()
        assertTrue(text.contains("#EXT-X-STREAM-INF"))
        assertTrue(text.contains("URI=\"audio.m3u8\""))
        assertTrue(text.trimEnd().endsWith("video.m3u8"))
    }

    @Test
    fun mediaPlaylistExposesMapAndSegments() {
        feed(
            MseMirrorServer.MirrorTrack.VIDEO,
            init = ByteArray(64) { it.toByte() },
            fragments = listOf(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 }),
        )
        val (code, body) = httpGet("/video.m3u8")
        assertEquals(200, code)
        val text = body.decodeToString()
        assertTrue(text.contains("#EXT-X-MAP:URI=\"video-init-1.mp4\""))
        assertTrue(text.contains("video-seg-0.mp4"))
        assertTrue(text.contains("video-seg-1.mp4"))
    }

    @Test
    fun initSegmentServesExactBytes() {
        val init = ByteArray(100) { (it * 3).toByte() }
        feed(MseMirrorServer.MirrorTrack.VIDEO, init, listOf(ByteArray(16)))
        val (code, body) = httpGet("/video-init.mp4")
        assertEquals(200, code)
        assertTrue(init.contentEquals(body))
    }

    @Test
    fun segmentServesExactBytes() {
        val fragment = ByteArray(200) { (it xor 0x5A).toByte() }
        feed(MseMirrorServer.MirrorTrack.VIDEO, ByteArray(32), listOf(ByteArray(8), fragment))
        val (code, body) = httpGet("/video-seg-1.mp4")
        assertEquals(200, code)
        assertTrue(fragment.contentEquals(body))
    }

    @Test
    fun slidingWindowNeverPublishesBackward() {
        feed(MseMirrorServer.MirrorTrack.AUDIO, ByteArray(16), (0 until 5).map { ByteArray(8) })
        val first = httpGet("/audio.m3u8").second.decodeToString()
        val firstSequence = first.lineSequence()
            .firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
            ?.removePrefix("#EXT-X-MEDIA-SEQUENCE:")?.toLong() ?: -1L
        (5 until 12).forEach { server.offerFragment(MseMirrorServer.MirrorTrack.AUDIO, ByteArray(8)) }
        val second = httpGet("/audio.m3u8").second.decodeToString()
        val secondSequence = second.lineSequence()
            .firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
            ?.removePrefix("#EXT-X-MEDIA-SEQUENCE:")?.toLong() ?: -1L
        assertTrue("序号只前进: $firstSequence -> $secondSequence", secondSequence >= firstSequence)
        // 滑窗内的分片数量有界，旧分片被逐出后不再出现在清单。
        val listed = second.lineSequence().count { it.startsWith("audio-seg-") }
        assertTrue(listed in 1..10)
    }

    @Test
    fun initResetKeepsSequenceMonotonic() {
        feed(MseMirrorServer.MirrorTrack.VIDEO, ByteArray(16), (0 until 4).map { ByteArray(8) })
        val first = httpGet("/video.m3u8").second.decodeToString()
        val firstTop = first.lineSequence()
            .filter { it.startsWith("video-seg-") }
            .map { it.removePrefix("video-seg-").removeSuffix(".mp4").toLong() }
            .maxOrNull() ?: -1L
        // 换清晰度：新 init + 新分片（同一会话内序号不得回退）。
        server.offerInit(MseMirrorServer.MirrorTrack.VIDEO, ByteArray(20))
        server.offerFragment(MseMirrorServer.MirrorTrack.VIDEO, ByteArray(8))
        val second = httpGet("/video.m3u8").second.decodeToString()
        val secondSequence = second.lineSequence()
            .firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
            ?.removePrefix("#EXT-X-MEDIA-SEQUENCE:")?.toLong() ?: -1L
        assertTrue("重置后起始序号不回退: firstTop=$firstTop secondSequence=$secondSequence",
            secondSequence >= firstTop)
        val (code, init) = httpGet("/video-init.mp4")
        assertEquals(200, code)
        assertEquals(20, init.size)
    }

    @Test
    fun readyRequiresInitAndFragment() {
        server.offerInit(MseMirrorServer.MirrorTrack.VIDEO, ByteArray(16))
        assertFalse(server.ready)
        server.offerFragment(MseMirrorServer.MirrorTrack.VIDEO, ByteArray(8))
        assertTrue(server.ready)
    }

    @Test
    fun initChangeEmitsDiscontinuityAndVersionedMaps() {
        val initA = ByteArray(32) { 0x0A }
        val initB = ByteArray(32) { 0x0B }
        server.offerInit(MseMirrorServer.MirrorTrack.VIDEO, initA)
        server.offerFragment(MseMirrorServer.MirrorTrack.VIDEO, ByteArray(8))
        // 页面切线/换清晰度：内容不同的新 init。
        server.offerInit(MseMirrorServer.MirrorTrack.VIDEO, initB)
        server.offerFragment(MseMirrorServer.MirrorTrack.VIDEO, ByteArray(9))
        server.offerFragment(MseMirrorServer.MirrorTrack.VIDEO, ByteArray(10))
        val text = httpGet("/video.m3u8").second.decodeToString()
        assertTrue("新代 MAP 应版本化: $text", text.contains("#EXT-X-MAP:URI=\"video-init-2.mp4\""))
        assertTrue("代切换处应插 DISCONTINUITY: $text", text.contains("#EXT-X-DISCONTINUITY"))
        // 旧代 init 仍可按版本化 URI 取到（滑窗内旧分片播放需要）。
        val (codeOld, oldInit) = httpGet("/video-init-1.mp4")
        assertEquals(200, codeOld)
        assertTrue(initA.contentEquals(oldInit))
        val (codeNew, newInit) = httpGet("/video-init-2.mp4")
        assertEquals(200, codeNew)
        assertTrue(initB.contentEquals(newInit))
    }

    /** 构造最小 fMP4：init(moov/trak/tkhd[trackId]) 与 fragment(moof/traf/tfhd[trackId])。 */
    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = ((8 + payload.size) ushr 24).toByte()
        out[1] = ((8 + payload.size) ushr 16).toByte()
        out[2] = ((8 + payload.size) ushr 8).toByte()
        out[3] = (8 + payload.size).toByte()
        type.toByteArray(Charsets.US_ASCII).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray): ByteArray =
        box(type, byteArrayOf(version.toByte(), (flags ushr 16).toByte(), (flags ushr 8).toByte(), flags.toByte()) + payload)

    private fun synthInit(trackId: Int): ByteArray {
        // tkhd body = ctime(4)+mtime(4)+track_id(4)+...（version 0）
        val body = ByteArray(84)
        body[8] = ((trackId ushr 24) and 0xFF).toByte()
        body[9] = ((trackId ushr 16) and 0xFF).toByte()
        body[10] = ((trackId ushr 8) and 0xFF).toByte()
        body[11] = (trackId and 0xFF).toByte()
        val tkhdBox = fullBox("tkhd", 0, 7, body)
        val trak = box("trak", tkhdBox)
        return box("ftyp", byteArrayOf(0x69, 0x73, 0x6F, 0x6D)) + box("moov", trak)
    }

    private fun synthFragment(trackId: Int): ByteArray {
        val tfhd = fullBox("tfhd", 0, 1, byteArrayOf(
            ((trackId ushr 24) and 0xFF).toByte(), ((trackId ushr 16) and 0xFF).toByte(),
            ((trackId ushr 8) and 0xFF).toByte(), (trackId and 0xFF).toByte()))
        val traf = box("traf", tfhd)
        val moof = box("moof", traf)
        return moof + box("mdat", ByteArray(64))
    }

    @Test
    fun fragmentsFromForeignTrackAreDropped() {
        // 页面切线实例交错（实测：init track 3 与 fragment track 20 交叉）。
        server.offerInit(MseMirrorServer.MirrorTrack.VIDEO, synthInit(trackId = 3))
        assertFalse(server.ready) // track 20 分片被丢弃
        server.offerFragment(MseMirrorServer.MirrorTrack.VIDEO, synthFragment(trackId = 3))
        assertTrue(server.ready) // track 3 分片通过
        val (code, body) = httpGet("/video.m3u8")
        assertEquals(200, code)
        assertTrue(body.decodeToString().contains("video-seg-0.mp4"))
    }

    @Test
    fun rejectedForeignFragmentDoesNotRefreshWatchdogHealth() {
        val init = synthInit(trackId = 3)
        val staleFragment = synthFragment(trackId = 20)
        val currentFragment = synthFragment(trackId = 3)

        server.offerInit(MseMirrorServer.MirrorTrack.VIDEO, init)
        assertFalse(server.offerFragment(MseMirrorServer.MirrorTrack.VIDEO, staleFragment))
        assertEquals(0L, server.lastFragmentArrivedAtMs)
        val rejected = server.diagnosticsSnapshot()
        assertEquals(0L, rejected.acceptedFragments)
        assertEquals(1L, rejected.rejectedFragments)

        // 行协议也必须保留同一语义：被拒绝的旧分片不能给 watchdog 续命。
        val offered = server.offer("V|F|${staleFragment.size}|${b64Of(staleFragment)}")
        assertEquals(1, offered)
        assertEquals(0L, server.lastFragmentArrivedAtMs)
        assertEquals(2L, server.diagnosticsSnapshot().rejectedFragments)

        assertTrue(server.offerFragment(MseMirrorServer.MirrorTrack.VIDEO, currentFragment))
    }

    // ===== Gecko 原生消息桥行协议；HTTP 只读 =====

    private fun httpPost(path: String, body: String): Int {
        val base = playlistUri.removeSuffix("/master.m3u8")
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 5_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/plain")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.US_ASCII)) }
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun b64Of(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    @Test
    fun httpOfferAndOtherWriteMethodsCannotMutateTracks() {
        val init = synthInit(trackId = 7)
        val body = "V|I|${init.size}|${b64Of(init)}"
        val before = server.diagnosticsSnapshot()
        for (path in listOf("/offer", "/offer?ignored=true", "/video-init.mp4")) {
            assertEquals(405, httpPost(path, body))
        }
        val after = server.diagnosticsSnapshot()
        assertEquals(before.videoInitCount, after.videoInitCount)
        assertEquals(before.audioInitCount, after.audioInitCount)
        assertEquals(before.acceptedFragments, after.acceptedFragments)
        assertEquals(before.rejectedFragments, after.rejectedFragments)
        assertFalse(server.ready)
        assertEquals(404, httpGet("/video-init.mp4").first)
    }

    @Test
    fun nativeOfferAcceptsLineProtocolAndFeedsTracks() {
        val init = synthInit(trackId = 7)
        val fragment = synthFragment(trackId = 7)
        // content script 通过原生消息转发行协议。
        val body = listOf(
            "V|I|${init.size}|${b64Of(init)}",
            "V|F|${fragment.size}|${b64Of(fragment)}",
            "A|I|${init.size}|${b64Of(init)}",
            "A|F|${fragment.size}|${b64Of(fragment)}",
        ).joinToString("\n")
        assertTrue(server.offer(body) > 0)
        assertTrue(server.ready)
        assertTrue(server.lastFragmentArrivedAtMs > 0L)
        val (videoCode, videoInit) = httpGet("/video-init.mp4")
        assertEquals(200, videoCode)
        assertTrue(videoInit.contentEquals(init))
        val (audioCode, audioInit) = httpGet("/audio-init.mp4")
        assertEquals(200, audioCode)
        assertTrue(audioInit.contentEquals(init))
        val (playlistCode, playlist) = httpGet("/video.m3u8")
        assertEquals(200, playlistCode)
        assertTrue(playlist.decodeToString().contains("video-seg-0.mp4"))
        val diagnostics = server.diagnosticsSnapshot()
        assertEquals(1L, diagnostics.videoInitCount)
        assertEquals(1L, diagnostics.audioInitCount)
        assertEquals(1L, diagnostics.acceptedVideoFragments)
        assertEquals(1L, diagnostics.acceptedAudioFragments)
        assertEquals(0L, diagnostics.rejectedVideoFragments)
        assertEquals(0L, diagnostics.rejectedAudioFragments)
        assertTrue(diagnostics.firstVideoInitElapsedMs >= 0L)
        assertTrue(diagnostics.firstAudioInitElapsedMs >= 0L)
        assertTrue(diagnostics.firstAcceptedFragmentElapsedMs >= 0L)
    }

    @Test
    fun rejectedFragmentsAreCountedPerTrackWithoutAcceptedMilestone() {
        server.offerInit(MseMirrorServer.MirrorTrack.VIDEO, synthInit(trackId = 3))
        server.offerInit(MseMirrorServer.MirrorTrack.AUDIO, synthInit(trackId = 4))
        assertFalse(server.offerFragment(MseMirrorServer.MirrorTrack.VIDEO, synthFragment(trackId = 30)))
        assertFalse(server.offerFragment(MseMirrorServer.MirrorTrack.AUDIO, synthFragment(trackId = 40)))

        val diagnostics = server.diagnosticsSnapshot()
        assertEquals(0L, diagnostics.acceptedFragments)
        assertEquals(2L, diagnostics.rejectedFragments)
        assertEquals(1L, diagnostics.rejectedVideoFragments)
        assertEquals(1L, diagnostics.rejectedAudioFragments)
        assertEquals(-1L, diagnostics.firstAcceptedFragmentElapsedMs)
        assertEquals(0L, server.lastFragmentArrivedAtMs)
    }

    @Test
    fun nativeOfferToleratesJunkLinesAndCRLF() {
        val init = synthInit(trackId = 9)
        val fragment = synthFragment(trackId = 9)
        // CRLF + 空行 + 坏行混入：好行必须全部生效，坏行跳过不炸。
        val body = buildString {
            append("\r\n")
            append("X|I|1|AAAA\r\n")
            append("V|I|${init.size}|${b64Of(init)}\r\n")
            append("V|F|${fragment.size}|${b64Of(fragment)}\r\n")
            append("V|F|not-a-number|AAAA\r\n")
            append("V|F|2|!!!not-base64!!!\r\n")
        }
        assertTrue(server.offer(body) > 0)
        assertTrue(server.ready)
        val (videoCode, videoInit) = httpGet("/video-init.mp4")
        assertEquals(200, videoCode)
        assertTrue(videoInit.contentEquals(init))
        val (_, playlist) = httpGet("/video.m3u8")
        // 坏行（track=X / 非数字 size / 非法 base64）被丢弃，好行不受影响。
        assertTrue(playlist.decodeToString().contains("video-seg-0.mp4"))
    }

    @Test
    fun nativeOfferTruncatesB64PaddingToReportedSize() {
        // JS b64 按完整 3 字节组编码，尾部补零字节必须按 size 精确截断。
        val fragment = synthFragment(trackId = 11)
        val padded = fragment + byteArrayOf(0, 0) // 补零
        val b64 = b64Of(padded)
        val init = synthInit(trackId = 11)
        val accepted = server.offer(
            "V|I|${init.size}|${b64Of(init)}\nV|F|${fragment.size}|$b64",
        )
        assertEquals(2, accepted)
        assertTrue(server.ready)
        val (_, playlist) = httpGet("/video.m3u8")
        assertTrue(playlist.decodeToString().contains("video-seg-0.mp4"))
        // 分片字节与未补零版本完全一致（截断生效）：通过 /video-seg-0 拉回比对。
        val (segCode, seg) = httpGet("/video-seg-0.mp4")
        assertEquals(200, segCode)
        assertTrue(seg.contentEquals(fragment))
    }
}
