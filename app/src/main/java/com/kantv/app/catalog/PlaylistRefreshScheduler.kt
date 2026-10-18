package com.kantv.app.catalog

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Registers best-effort background refreshes. WorkManager does not promise an exact wall-clock run.
 */
object PlaylistRefreshScheduler {
    private val registrationMutex = Mutex()
    internal const val PERIODIC_WORK_NAME = "kantv.playlist.refresh.periodic"
    internal const val CATCH_UP_WORK_NAME = "kantv.playlist.refresh.catch-up"
    internal const val MANUAL_WORK_NAME = "kantv.playlist.refresh.manual"
    internal const val FORCE_KEY = "force"

    suspend fun ensureScheduled(context: Context) = withContext(Dispatchers.IO) {
        registrationMutex.withLock { reconcileSchedule(context) }
    }

    private suspend fun reconcileSchedule(context: Context) {
        val appContext = context.applicationContext
        val workManager = WorkManager.getInstance(appContext)
        val repository = HubWiring.repository(appContext)
        val subscriptions = repository.subscriptions()
        // 枢纽是首选来源：即使没有任何用户 M3U 订阅，也要按小时刷新服务端订阅。
        val hubConfigured = HubEndpointPolicy.validEndpoint(HubConfig.read(appContext).endpoint)
        val hubDue = hubConfigured &&
            HubRefreshPolicy.shouldRefresh(HubWiring.store(appContext).state(), System.currentTimeMillis())
        if (subscriptions.isEmpty() && !hubConfigured) {
            // An embedded snapshot/local file is not an online subscription.
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(CATCH_UP_WORK_NAME)
            workManager.cancelUniqueWork(MANUAL_WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<PlaylistRefreshWorker>(
            PlaylistRefreshPolicy.REFRESH_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
            .setConstraints(constraints)
            .setInputData(inputData(force = false))
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )

        if (hubDue || PlaylistRefreshPolicy.shouldCatchUp(subscriptions, System.currentTimeMillis())) {
            workManager.enqueueUniqueWork(
                CATCH_UP_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                oneTimeRequest(force = false, constraints = constraints),
            )
        }
    }

    /** Queue an immediate refresh. The caller chooses force=true for an explicit Refresh action. */
    fun requestRefresh(context: Context, force: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            oneTimeRequest(force, constraints),
        )
    }

    private fun oneTimeRequest(force: Boolean, constraints: Constraints) =
        OneTimeWorkRequestBuilder<PlaylistRefreshWorker>()
            .setConstraints(constraints)
            .setInputData(inputData(force))
            .build()

    private fun inputData(force: Boolean): Data = Data.Builder().putBoolean(FORCE_KEY, force).build()
}
