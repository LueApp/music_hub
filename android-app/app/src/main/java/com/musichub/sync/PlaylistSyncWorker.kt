package com.musichub.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.musichub.MusicHubApplication

/**
 * WorkManager worker that syncs all playlists with their remote sources.
 */
class PlaylistSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "PlaylistSyncWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting playlist sync work")

        return try {
            val app = applicationContext as MusicHubApplication
            val syncEngine = PlaylistSyncEngine(app.repository)
            val results = syncEngine.syncAll()

            val totalAdded = results.sumOf { it.added }
            val totalRemoved = results.sumOf { it.removed }
            val totalErrors = results.sumOf { it.errors.size }

            Log.d(TAG, "Sync complete: ${results.size} playlists, +$totalAdded -$totalRemoved, $totalErrors errors")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync work failed: ${e.message}", e)
            Result.retry()
        }
    }
}
