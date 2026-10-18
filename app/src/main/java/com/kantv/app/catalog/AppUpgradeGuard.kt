package com.kantv.app.catalog

import android.content.Context
import com.kantv.app.BuildConfig

/** 记录上次启动时见过的版本号；独立偏好文件，不影响其他配置。 */
interface AppUpgradeStore {
    var lastSeenVersionCode: Int
}

class SharedPreferencesAppUpgradeStore(context: Context) : AppUpgradeStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override var lastSeenVersionCode: Int
        get() = prefs.getInt(KEY_LAST_SEEN_VERSION_CODE, 0)
        set(value) {
            prefs.edit().putInt(KEY_LAST_SEEN_VERSION_CODE, value).apply()
        }

    private companion object {
        const val PREFS = "app_upgrade_guard"
        const val KEY_LAST_SEEN_VERSION_CODE = "last_seen_version_code"
    }
}

/**
 * 升级残留防御：版本号上升时清空枢纽订阅的本地状态并强制一次无条件刷新。
 *
 * v24 时代落盘的订阅 payload 携带限时签名路由 URL；etag/304 的 keep-previous 语义
 * （契约 §6）加上每小时刷新门会让过期 payload 被无限期沿用（升级后全频道黑屏）。
 * 清掉订阅状态后，下一次拉取必然是不带 If-None-Match 的 200，拿到新鲜签名。
 *
 * 幂等：同一版本号重复启动为空操作。降级不做任何破坏性处理，也不回写 lastSeen——
 * 保留较高的水位，避免「降级再升回」时重复清空。
 */
class AppUpgradeGuard(
    private val store: AppUpgradeStore,
    private val hubSubscriptionStore: HubSubscriptionStore,
    private val requestForcedRefresh: () -> Unit,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
) {
    fun run() {
        if (currentVersionCode <= store.lastSeenVersionCode) return
        hubSubscriptionStore.clear()
        store.lastSeenVersionCode = currentVersionCode
        requestForcedRefresh()
    }

    companion object {
        fun create(context: Context): AppUpgradeGuard = AppUpgradeGuard(
            store = SharedPreferencesAppUpgradeStore(context),
            hubSubscriptionStore = HubWiring.store(context),
            requestForcedRefresh = { PlaylistRefreshScheduler.requestRefresh(context, force = true) },
        )
    }
}
