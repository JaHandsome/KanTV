package com.kantv.app.review

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.kantv.app.playback.AudioOnlyExtensionRenderersFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Bounded, synthetic native-decoder check. Never part of production playback or monitoring. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FfmpegDecodeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.packageName != "com.kantv.app") { "Requires an isolated application ID" }
            check(FfmpegLibrary.isAvailable()) { "Native library could not load" }
            check(!FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_FLAC)) { "Unexpected decoder enabled" }
            val fixtures = JSONObject(context.assets.open("ffmpeg/fixtures.json").bufferedReader().use { it.readText() })
                .getJSONArray("fixtures")
            val observations = JSONArray()
            for (index in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(index)
                val mime = fixture.getString("mime")
                check(FfmpegLibrary.supportsFormat(mime)) { "Unsupported fixture codec ${fixture.getString("name")}" }
                val initialization = fixture.getJSONArray("initializationData")
                val format = Format.Builder().setSampleMimeType(mime)
                    .setSampleRate(fixture.getInt("sampleRate")).setChannelCount(fixture.getInt("channels"))
                    .setInitializationData((0 until initialization.length()).map {
                        Base64.decode(initialization.getString(it), Base64.DEFAULT)
                    }).build()
                val frames = fixture.getJSONArray("packets").let { packets ->
                    (0 until packets.length()).map { Base64.decode(packets.getString(it), Base64.DEFAULT) }
                }
                val decoder = FfmpegAudioDecoder(format, 8, 8, frames.maxOf { it.size }, false)
                try {
                    val first = decode(decoder, frames)
                    decoder.flush()
                    val second = decode(decoder, frames)
                    check(first.getLong("bytes") == second.getLong("bytes")) { "Flush changed output length" }
                    check(decoder.sampleRate == format.sampleRate && decoder.channelCount == format.channelCount) {
                        "Unexpected PCM format"
                    }
                    observations.put(JSONObject().put("codec", fixture.getString("name"))
                        .put("sampleRate", decoder.sampleRate).put("channels", decoder.channelCount)
                        .put("pcm", first).put("afterFlush", second))
                } finally {
                    decoder.release()
                }
            }
            result.putString("stream", JSONObject().put("status", "PASS")
                .put("version", FfmpegLibrary.getVersion()).put("codecs", observations)
                .put("renderer", renderSyntheticPlaylist()).toString() + "\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL ${error.javaClass.simpleName}: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun decode(decoder: FfmpegAudioDecoder, frames: List<ByteArray>): JSONObject {
        val digest = MessageDigest.getInstance("SHA-256")
        var next = 0
        var eosQueued = false
        var eosReceived = false
        var bytes = 0L
        var samples = 0L
        var squares = 0.0
        var crossings = 0
        var previous = 0
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (!eosReceived && SystemClock.elapsedRealtime() < deadline) {
            if (!eosQueued) {
                decoder.dequeueInputBuffer()?.let { input ->
                    input.clear()
                    if (next == frames.size) {
                        input.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    } else {
                        val frame = frames[next++]
                        input.ensureSpaceForWrite(frame.size)
                        input.data!!.put(frame)
                        input.timeUs = next * 20_000L
                        input.flip()
                    }
                    decoder.queueInputBuffer(input)
                }
            }
            decoder.dequeueOutputBuffer()?.let { output ->
                try {
                    if (output.isEndOfStream) {
                        eosReceived = true
                    } else if (!output.shouldBeSkipped) {
                        output.data?.let { pcm ->
                            bytes += pcm.remaining()
                            val stereo = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            while (stereo.remaining() >= 4) {
                                val left = stereo.short.toInt()
                                stereo.short
                                squares += left.toDouble() * left
                                if (samples > 4_800 && previous >= 0 && left < 0) crossings++
                                previous = left
                                samples++
                            }
                            digest.update(pcm.duplicate())
                        }
                    }
                } finally {
                    output.release()
                }
            }
            Thread.sleep(1)
        }
        check(eosReceived) { "Decoder did not reach EOS" }
        // Stereo 48kHz PCM16 tone is two seconds, allowing codec priming/padding.
        check(bytes in 360_000L..410_000L) { "Incomplete or excessive PCM output: $bytes" }
        val rms = kotlin.math.sqrt(squares / samples)
        check(rms in 100.0..10_000.0 && crossings in 1_500..2_300) {
            "Decoded PCM does not contain the synthetic tone: rms=$rms crossings=$crossings"
        }
        return JSONObject().put("bytes", bytes)
            .put("rms", rms).put("negativeZeroCrossingsAfterWarmup", crossings)
            .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun renderSyntheticPlaylist(): JSONObject {
        val items = listOf("tone.m4a", "tone.mp3").map { name ->
            val file = File(targetContext.cacheDir, "ffmpeg-review-$name")
            context.assets.open("ffmpeg/$name").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            MediaItem.fromUri(android.net.Uri.fromFile(file))
        }
        val initialized = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val error = AtomicReference<PlaybackException?>()
        val decoders = CopyOnWriteArrayList<String>()
        lateinit var player: ExoPlayer
        runOnMainSync {
            val factory = AudioOnlyExtensionRenderersFactory(targetContext)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            player = ExoPlayer.Builder(targetContext, factory).build()
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    decoders.add(decoderName)
                    initialized.countDown()
                }
            })
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) ended.countDown()
                }
                override fun onPlayerError(failure: PlaybackException) {
                    error.set(failure)
                    initialized.countDown()
                    ended.countDown()
                }
            })
            player.setMediaItems(items)
            player.prepare()
            player.play()
        }
        try {
            check(initialized.await(20, TimeUnit.SECONDS)) { "Audio renderer did not initialize" }
            runOnMainSync { player.pause() }
            Thread.sleep(150)
            runOnMainSync {
                check(!player.playWhenReady) { "Pause was not retained" }
                player.play()
            }
            check(ended.await(20, TimeUnit.SECONDS)) { "Audio playlist did not reach EOS" }
            check(error.get() == null) { "Audio renderer failed: ${error.get()?.errorCodeName}" }
            check(decoders.any { it.startsWith("ffmpeg") && it.endsWith("-aac") } &&
                decoders.any { it.startsWith("ffmpeg") && it.endsWith("-mp3") }) {
                "FFmpeg renderer was not exercised for both formats: $decoders"
            }
            return JSONObject().put("decoders", JSONArray(decoders)).put("ended", true).put("pauseResume", true)
        } finally {
            runOnMainSync { player.release() }
        }
    }
}
