package com.kantv.app.review

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.room.Room
import com.kantv.app.catalog.CatalogDatabase
import com.kantv.app.catalog.CatalogRepository
import com.kantv.app.catalog.ChannelEntity
import com.kantv.app.catalog.SecretBox
import com.kantv.app.BuildConfig
import com.kantv.app.MainActivity
import com.kantv.app.catalog.HubConfig
import com.kantv.app.catalog.HubRefreshOutcome
import com.kantv.app.catalog.HubRefreshWorkerSupport
import com.kantv.app.playback.Media3PlaybackEngine
import com.kantv.app.playback.PlaybackEvent
import com.kantv.app.playback.PlaybackRequest
import com.kantv.app.playback.PlaybackTechnicalUpdate
import com.kantv.app.playback.StreamDeliveryStrategy
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Emulator-only regression checks; uses an in-memory database and synthetic addresses. */
class ReviewInstrumentation : Instrumentation() {
    private var seedOnly = false
    private var pauseOnly = false
    private var noNasStartupOnly = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        seedOnly = arguments?.getString("seed") == "true"
        pauseOnly = arguments?.getString("pause") == "true"
        noNasStartupOnly = arguments?.getString("noNasStartup") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            if (noNasStartupOnly) {
                noNasStartupCompletesWithoutDefaultPlaylist()
                result.putString("stream", "PASS no-NAS startup, retained inactive Hub preference, official catalogue and manual M3U import\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (seedOnly) {
                seedSyntheticChannels()
                result.putString("stream", "PASS synthetic channels seeded\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (pauseOnly) {
                delayedPreflightPreservesPause()
                result.putString("stream", "PASS delayed preflight preserves pause and explicit resume\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            catalogueMergeRollsBackFailedInsert()
            result.putString("stream", "PASS catalogue rollback and successful merge\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL regression: ${error.javaClass.simpleName}: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun noNasStartupCompletesWithoutDefaultPlaylist() {
        check(targetContext.packageName != "com.kantv.app") { "Requires an isolated review application ID" }
        check(BuildConfig.HUB_DEFAULT_ENDPOINT.isBlank()) { "Requires a build without a Hub endpoint" }
        val repository = CatalogRepository(CatalogDatabase.get(targetContext), SecretBox())
        check(runBlocking { !repository.hasChannels() && repository.subscriptions().isEmpty() }) {
            "Requires fresh isolated review data; existing data will not be cleared"
        }
        val defaults = targetContext.getSharedPreferences("kantv_catalog_defaults", Context.MODE_PRIVATE)
        check(!defaults.contains("default_m3u_applied")) { "Requires fresh default-subscription state" }
        val hubPreferences = HubConfig.preferences(targetContext)
        val previousEndpoint = "https://previous.example.invalid"
        check(hubPreferences.edit().putString("endpoint", previousEndpoint).commit())
        check(!HubConfig.hasValidEndpoint(targetContext)) { "Historical preference enabled an unconfigured Hub" }
        check(hubPreferences.getString("endpoint", null) == previousEndpoint) { "Historical preference was changed" }
        check(runBlocking {
            HubRefreshWorkerSupport.refresh(targetContext, repository, force = true, playbackActive = false)
        } == HubRefreshOutcome.Skipped("ENDPOINT_INVALID")) { "Unconfigured Hub refresh did not skip" }

        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val prepared = MainActivity::class.java.getDeclaredField("catalogPrepared").apply { isAccessible = true }
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            while (!prepared.getBoolean(activity) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            check(prepared.getBoolean(activity)) { "Catalogue preparation did not finish without a default playlist" }
            check(runBlocking { repository.defaultPlaylistMode() } == null) { "Control unexpectedly had a default playlist" }
            check(runBlocking { repository.hasChannels() }) { "Official catalogue was not installed" }
            check(runBlocking { repository.subscriptions().isEmpty() }) { "Unexpected default network subscription" }
            check(!defaults.contains("default_m3u_applied")) { "Unconfigured default subscription was marked applied" }
            runBlocking {
                repository.import(
                    "#EXTM3U\n#EXTINF:-1,Manual review\nhttps://example.invalid/manual.m3u8\n",
                    "content://review/manual.m3u",
                )
                check(repository.defaultPlaylistMode() == "CUSTOM") { "Manual M3U import was not retained" }
            }
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

    private fun fixture(path: String): String {
        val connection = URL("http://10.0.2.2:18420$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        return try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun delayedPreflightPreservesPause() {
        fixture("/control/reset")
        val prepared = CountDownLatch(1)
        lateinit var engine: Media3PlaybackEngine
        runOnMainSync {
            engine = Media3PlaybackEngine(targetContext, PlayerView(targetContext))
            engine.setEventListener { event ->
                val update = (event as? PlaybackEvent.Technical)?.update
                if (update is PlaybackTechnicalUpdate.StreamDeliverySelected &&
                    update.strategy == StreamDeliveryStrategy.HLS_CACHED_PLAYLIST
                ) prepared.countDown()
            }
            engine.play(123L, PlaybackRequest("synthetic-pause", "http://10.0.2.2:18420/slow/live.m3u8"))
            engine.pause()
        }
        try {
            fixture("/control/release")
            check(prepared.await(30, TimeUnit.SECONDS)) { "Preflight did not complete" }
            // Delivery event precedes prepare in the same main-loop callback; this read runs after it.
            var paused = false
            var pausedAfterReopen = false
            var resumed = false
            runOnMainSync {
                val field = Media3PlaybackEngine::class.java.getDeclaredField("player").apply { isAccessible = true }
                val player = field.get(engine) as Player
                paused = !player.playWhenReady
                engine.reopen(123L)
                pausedAfterReopen = !player.playWhenReady
                engine.resume()
                resumed = player.playWhenReady
            }
            check(paused) { "Delayed preflight resumed a paused player" }
            check(pausedAfterReopen) { "Reopen lost the paused intention" }
            check(resumed) { "Explicit resume did not restore play intent" }
        } finally {
            runOnMainSync { engine.release() }
        }
    }

    private fun seedSyntheticChannels() = runBlocking {
        val secretBox = SecretBox()
        val dao = CatalogDatabase.get(targetContext).catalogDao()
        dao.insertChannels((1..2).map { number ->
            ChannelEntity(
                id = "synthetic-$number", position = number - 4, tvgId = null,
                name = "Synthetic $number", groupTitle = "Synthetic review", logoUrl = null,
                encryptedRequest = secretBox.encrypt(JSONArray().put(JSONObject().put(
                    "url", "http://10.0.2.2:18420/live.m3u8?channel=$number",
                )).toString()), identityKey = "id:synthetic-$number",
            )
        })
    }

    private fun catalogueMergeRollsBackFailedInsert() {
        val database = Room.inMemoryDatabaseBuilder(targetContext, CatalogDatabase::class.java).build()
        try {
            val secretBox = SecretBox()
            fun channel(id: String, position: Int): ChannelEntity = ChannelEntity(
                id = id,
                position = position,
                tvgId = null,
                name = "Review channel",
                groupTitle = "Synthetic",
                logoUrl = null,
                encryptedRequest = secretBox.encrypt(
                    JSONArray().put(JSONObject().put("url", "https://example.invalid/$id.m3u8")).toString(),
                ),
                identityKey = "hub:review-channel",
            )
            val original = listOf(channel("one", 0), channel("two", 1))
            val dao = database.catalogDao()
            val repository = CatalogRepository(database, secretBox)
            runBlocking { dao.insertChannels(original) }
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER review_fail_merge BEFORE INSERT ON channels " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic insert failure'); END",
            )
            val failure = runCatching { runBlocking { repository.dedupeNameVariants() } }.exceptionOrNull()
            check(failure != null) { "The failed-insert control did not execute" }
            check(runBlocking { dao.allChannels() } == original) { "Merge failure lost original rows" }
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER review_fail_merge")
            check(runBlocking { repository.dedupeNameVariants() }) { "Expected a merge" }
            val merged = runBlocking { dao.allChannels() }
            check(merged.size == 1)
            check(runBlocking { repository.playbackRequests(merged.single().id) }.size == 2)
        } finally {
            database.close()
        }
    }
}
