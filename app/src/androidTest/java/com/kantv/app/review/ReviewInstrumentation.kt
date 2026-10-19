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
    private var websiteImportOnly = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        seedOnly = arguments?.getString("seed") == "true"
        pauseOnly = arguments?.getString("pause") == "true"
        noNasStartupOnly = arguments?.getString("noNasStartup") == "true"
        websiteImportOnly = arguments?.getString("websiteImport") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            if (websiteImportOnly) {
                officialWebsiteBatchImport()
                result.putString("stream", "PASS official website atomic batch, rollback, idempotence, source and subscription preservation, rejected unknown pages\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (noNasStartupOnly) {
                noNasStartupCompletesWithoutDefaultPlaylist()
                result.putString("stream", "PASS no-NAS startup, retained inactive Hub preference, official catalogue, first menu confirmation and manual M3U import\n")
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
            val bootstrapped = MainActivity::class.java.getDeclaredField("bootstrapped").apply { isAccessible = true }
            val channels = MainActivity::class.java.getDeclaredField("channels").apply { isAccessible = true }
            var ready = false
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                runOnMainSync {
                    ready = prepared.getBoolean(activity) && bootstrapped.getBoolean(activity) &&
                        (channels.get(activity) as List<*>).isNotEmpty()
                }
                if (!ready) Thread.sleep(50)
            }
            check(ready) { "Catalogue preparation did not finish without a default playlist" }
            check(runBlocking { repository.defaultPlaylistMode() } == null) { "Control unexpectedly had a default playlist" }
            check(runBlocking { repository.hasChannels() }) { "Official catalogue was not installed" }
            check(runBlocking { repository.subscriptions().isEmpty() }) { "Unexpected default network subscription" }
            check(!defaults.contains("default_m3u_applied")) { "Unconfigured default subscription was marked applied" }
            val screen = MainActivity::class.java.getDeclaredField("screen").apply { isAccessible = true }
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_MENU)
            waitForIdleSync()
            runOnMainSync {
                check(screen.get(activity).toString() == "MENU") { "Menu key did not open the menu" }
                fun findGuide(view: android.view.View): android.view.View? {
                    if (view is android.widget.Button && view.text.toString() == "频道与节目表") return view
                    if (view is android.view.ViewGroup) {
                        for (index in 0 until view.childCount) findGuide(view.getChildAt(index))?.let { return it }
                    }
                    return null
                }
                val guide = checkNotNull(findGuide(activity.findViewById(android.R.id.content)))
                check(guide.requestFocus()) { "Guide menu action could not take focus" }
            }
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            waitForIdleSync()
            runOnMainSync {
                check(screen.get(activity).toString() in setOf("GROUPS", "CHANNELS")) {
                    "The first menu confirmation was swallowed before opening the catalogue"
                }
            }
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

    private fun officialWebsiteBatchImport() {
        val database = Room.inMemoryDatabaseBuilder(targetContext, CatalogDatabase::class.java).build()
        try {
            val repository = CatalogRepository(database, SecretBox())
            val dao = database.catalogDao()
            val catalog = com.kantv.app.playback.OfficialWebChannelCatalog
            val selected = listOf("official-ysp-cctv1", "official-gdtv-43", "official-yicai-main")
                .map { id -> catalog.installedPages.single { it.tvgId == id } }
            runBlocking {
                val imported = repository.import(
                    "#EXTM3U\n#EXTINF:-1 tvg-id=\"cctv1\",CCTV-1 综合\nhttps://example.invalid/one.m3u8\n" +
                        "#EXTINF:-1,Unrelated review\nhttps://example.invalid/two.m3u8\n",
                    "https://example.invalid/subscription.m3u",
                )
                check(imported is com.kantv.app.catalog.CatalogImportResult.Imported)
            }
            val original = runBlocking { dao.allChannels() }
            val playlist = runBlocking { dao.playlist() }
            val subscriptions = runBlocking { dao.subscriptions() }
            val failureId = com.kantv.app.data.StableChannelId.create(selected.last().tvgId, null, "")
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER website_fail_batch BEFORE INSERT ON channels WHEN NEW.id = '$failureId' " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic batch failure'); END",
            )
            check(runCatching { runBlocking { repository.addOfficialWebPages(selected) } }.isFailure)
            check(runBlocking { dao.allChannels() } == original) { "A failed batch left partial channel writes" }
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER website_fail_batch")
            runBlocking { repository.addOfficialWebPages(selected + selected.first()) }
            val saved = runBlocking { dao.allChannels() }
            check(saved.size == original.size + 2) { "Batch failed to merge the existing CCTV identity" }
            original.forEach { before ->
                val after = saved.single { it.id == before.id }
                check(after.position == before.position)
                check(runBlocking { repository.playbackRequests(after.id) }.any { it.uri.startsWith("https://example.invalid/") })
            }
            val counts = runBlocking { saved.associate { it.id to repository.sourceCount(it.id) } }
            runBlocking { repository.addOfficialWebPages(selected) }
            val repeated = runBlocking { dao.allChannels() }
            check(repeated.map { it.id to it.position } == saved.map { it.id to it.position })
            check(runBlocking { repeated.associate { it.id to repository.sourceCount(it.id) } } == counts)
            val invalid = catalog.pages.first { it.provider == com.kantv.app.playback.OfficialWebProvider.REGIONAL }
            check(runCatching { runBlocking { repository.addOfficialWebPages(selected + invalid) } }.isFailure)
            check(runCatching { runBlocking { repository.addOfficialWebPages(listOf(selected.first().copy(url = "https://example.invalid/forged"))) } }.isFailure)
            check(runBlocking { dao.allChannels() } == repeated)
            check(com.kantv.app.catalog.OfficialWebsiteCatalog.resolve("https://www.yangshipin.cn/tv/home?pid=unknown") is com.kantv.app.catalog.OfficialWebsiteCatalog.Address.Rejected)
            check(runBlocking { dao.playlist() } == playlist)
            check(runBlocking { dao.subscriptions() } == subscriptions)
        } finally {
            database.close()
        }
    }
}
