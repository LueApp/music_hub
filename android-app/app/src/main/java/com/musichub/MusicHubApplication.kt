package com.musichub

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
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
     * After an APK reinstall — and after HyperOS kills Tutti's process
     * (which it does aggressively between playback sessions) — the system
     * leaves the user's allowed NotificationListenerService unbound. The
     * permission still shows as granted in Settings, but the system isn't
     * actually connected to MediaMonitorService, so MediaSession callbacks
     * never reach us — getPlaybackInfo() returns null and the floating
     * window's progress bar stays at 0% even though the song is playing.
     *
     * Two-tier rebind:
     *   1. If Shizuku is granted, run a shell-side `cmd notification
     *      disallow_listener / allow_listener` toggle. This always works
     *      (verified manually) but requires shell UID via Shizuku.
     *   2. Otherwise toggle the service component's enabled state via
     *      PackageManager (disable then re-enable with DONT_KILL_APP),
     *      then call requestRebind. The component toggle forces the
     *      system to drop the stale binding and re-establish it on
     *      re-enable — this is the standard non-Shizuku workaround for
     *      HyperOS leaving listeners unbound. requestRebind alone is
     *      unreliable.
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
            val pm = packageManager
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            NotificationListenerService.requestRebind(component)
            Log.d(TAG, "Toggled component state and requested rebind (Java fallback)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to toggle component / request rebind: ${e.message}")
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
