package com.musichub.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Utility for scheduling and cancelling periodic playlist sync jobs via WorkManager.
 */
object SyncScheduler {

    private const val TAG = "SyncScheduler"
    private const val SYNC_WORK_NAME = "playlist_sync"
    private const val MIN_INTERVAL_MINUTES = 15L
    private const val DEFAULT_INTERVAL_MINUTES = 360L  // 6 hours

    /**
     * Schedule periodic sync for all synced playlists.
     * Uses a single periodic work request for simplicity.
     */
    fun schedulePeriodicSync(context: Context, intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES) {
        val interval = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)

        Log.d(TAG, "Scheduling periodic sync every $interval minutes")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<PlaylistSyncWorker>(
            interval, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    /**
     * Cancel all periodic sync work.
     */
    fun cancelPeriodicSync(context: Context) {
        Log.d(TAG, "Cancelling periodic sync")
        WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
    }

    /**
     * Trigger an immediate one-time sync.
     */
    fun triggerImmediateSync(context: Context) {
        Log.d(TAG, "Triggering immediate sync")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<PlaylistSyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueue(syncRequest)
    }
}
