package com.kantv.app.catalog

import android.content.Context
import com.kantv.app.util.Hashing
import com.kantv.app.playback.OfficialWebChannelCatalog
import com.kantv.app.playback.SupportedWebLivePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface CatalogBootstrapStore {
    var officialRevision: String?
    var bundledChecked: Boolean
}

data class CatalogBootstrapResult(val updatedOfficial: Boolean, val checkedBundled: Boolean) {
    val reusedLocal: Boolean get() = !updatedOfficial && !checkedBundled
}

/** No network work. Persist completion only after the associated database operation succeeds. */
class CatalogBootstrapper(
    private val store: CatalogBootstrapStore,
    private val upgradeGuard: AppUpgradeGuard? = null,
) {
    suspend fun prepare(
        revision: String,
        hasChannels: suspend () -> Boolean,
        ensureOfficial: suspend () -> Unit,
        ensureBundled: suspend () -> Unit,
        onPreparing: suspend (firstInstall: Boolean) -> Unit = {},
    ): CatalogBootstrapResult = mutex.withLock {
        upgradeGuard?.run()
        val existing = hasChannels()
        val officialNeeded = !existing || store.officialRevision != revision
        val bundledNeeded = !existing || !store.bundledChecked
        if (officialNeeded || bundledNeeded) onPreparing(!existing)
        if (officialNeeded) {
            ensureOfficial()
            store.officialRevision = revision
        }
        if (bundledNeeded) {
            ensureBundled()
            store.bundledChecked = true
        }
        CatalogBootstrapResult(officialNeeded, bundledNeeded)
    }

    private companion object { val mutex = Mutex() }
}

class SharedPreferencesBootstrapStore(context: Context) : CatalogBootstrapStore {
    private val prefs = context.getSharedPreferences("catalog_bootstrap", Context.MODE_PRIVATE)
    override var officialRevision: String?
        get() = prefs.getString("official_revision", null)
        set(value) { check(prefs.edit().putString("official_revision", value).commit()) }
    override var bundledChecked: Boolean
        get() = prefs.getBoolean("bundled_checked", false)
        set(value) { check(prefs.edit().putBoolean("bundled_checked", value).commit()) }
}

object OfficialCatalogRevision {
    // The install decision is part of the revision so a whitelist correction also runs against
    // an existing database instead of being skipped as an unchanged directory.
    val current: String by lazy {
        val definition = officialCatalogRevisionDefinition(
            OfficialWebChannelCatalog.pages,
            OfficialWebChannelCatalog::isInstalled,
        )
        Hashing.sha256Hex(definition.toByteArray(Charsets.UTF_8))
    }
}

internal fun officialCatalogRevisionDefinition(
    pages: List<SupportedWebLivePage>,
    isInstalled: (SupportedWebLivePage) -> Boolean,
): String = "identity-policy-3\n" + pages.joinToString("\n") {
    listOf(it.tvgId, it.name, it.url, it.groupTitle, isInstalled(it).toString()).joinToString("\u001F")
}
