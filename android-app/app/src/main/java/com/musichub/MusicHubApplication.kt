package com.musichub

import android.app.Application
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.util.Log
import com.musichub.data.local.MusicHubDatabase
import com.musichub.data.repository.MusicRepository
import com.musichub.service.DeepLinkLauncher
import com.musichub.service.MediaMonitorService
import com.musichub.service.ShizukuLauncher
import com.musichub.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MusicHubApplication : Application() {

    val database: MusicHubDatabase by lazy {
        MusicHubDatabase.getDatabase(this)
    }

    val repository: MusicRepository by lazy {
        MusicRepository(database)
    }

    companion object {
        private const val TAG = "MusicHubApplication"
        private lateinit var instance: MusicHubApplication

        fun getInstance(): MusicHubApplication = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        initSyncScheduler()
        rebindMediaMonitorIfNeeded()
        DeepLinkLauncher.recoverOrphanedRotationSnapshot(this)
    }

    /**
     * After an APK reinstall — and after HyperOS kills Music Hub's process
     * (which it does aggressively between playback sessions) — the system
     * leaves the user's allowed NotificationListenerService unbound. The
     * permission still shows as granted in Settings, but the system isn't
     * actually connected to MediaMonitorService, so MediaSession callbacks
     * never reach us and auto-advance falls through to the timeout-skip path.
     *
     * Two-tier rebind:
     *   1. If Shizuku is granted, run a shell-side `cmd notification
     *      disallow_listener / allow_listener` toggle. This always works
     *      (verified manually) but requires shell UID via Shizuku.
     *   2. Otherwise fall back to `NotificationListenerService.requestRebind`
     *      (API 24+), which is the public API but appears unreliable on
     *      HyperOS — included so non-Shizuku users still get *some* attempt.
     */
    private fun rebindMediaMonitorIfNeeded() {
        if (!MediaMonitorService.isEnabled(this)) return
        val component = ComponentName(this, MediaMonitorService::class.java)
        val componentString = component.flattenToString()
        if (ShizukuLauncher.rebindNotificationListener(this, componentString)) {
            Log.d(TAG, "Rebound MediaMonitorService via Shizuku toggle")
            return
        }
        try {
            NotificationListenerService.requestRebind(component)
            Log.d(TAG, "Requested rebind of MediaMonitorService (Java fallback)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request listener rebind: ${e.message}")
        }
    }

    /**
     * Public entry point so MainActivity / PlaybackService can also force a
     * rebind on their respective lifecycle entry points — covers the case
     * where the Application class was created earlier with Shizuku not yet
     * connected, and the user has since granted permission.
     */
    fun rebindMediaMonitor() = rebindMediaMonitorIfNeeded()

    private fun initSyncScheduler() {
        CoroutineScope(Dispatchers.IO).launch {
            val syncedPlaylistIds = repository.getAllSyncedPlaylistIds()
            if (syncedPlaylistIds.isNotEmpty()) {
                SyncScheduler.schedulePeriodicSync(this@MusicHubApplication)
            }
        }
    }
}
