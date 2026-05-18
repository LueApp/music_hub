package com.musichub

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.util.Log
import com.musichub.data.local.MusicHubDatabase
import com.musichub.data.repository.MusicRepository
import com.musichub.service.DeepLinkLauncher
import com.musichub.service.MediaMonitorService
import com.musichub.service.PlayerAccessibilityService
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
        private const val A11Y_PREFS = "musichub_a11y"
        private const val PREF_GRANTED = "accessibility_granted"
        private lateinit var instance: MusicHubApplication

        fun getInstance(): MusicHubApplication = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        initSyncScheduler()
        rebindMediaMonitorIfNeeded()
        restoreAccessibilityIfRevoked()
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

    /**
     * AOSP's `am force-stop` handler puts the package into `stopped=true`
     * state. AccessibilityManagerService excludes services from stopped
     * packages on its next sweep, which removes them from the
     * `enabled_accessibility_services` setting — even though the user
     * granted them. Re-launching the app clears `stopped=true`, but the
     * setting is NOT restored automatically — the user has to dive back
     * into Accessibility settings every single time.
     *
     * Common triggers for force-stop in the wild: HyperOS "Clear all"
     * button in Recents, HyperOS background-task killer / battery saver,
     * Recents swipe-up close (on some HyperOS versions), manual force-
     * stop from App info. The user can't realistically avoid all of
     * these.
     *
     * Mitigation: on every app launch, if the user previously granted one
     * of our accessibility services (tracked in SharedPreferences any
     * time we observe them enabled) but the service is now missing, ask
     * Shizuku to re-write the setting. Writing
     * `enabled_accessibility_services` needs WRITE_SECURE_SETTINGS, which
     * is granted to shell UID — exactly what Shizuku provides. Users
     * without Shizuku see no restore but get the same broken-old
     * behavior; users with Shizuku get silent recovery.
     */
    private fun restoreAccessibilityIfRevoked() {
        val prefs = getSharedPreferences(A11Y_PREFS, Context.MODE_PRIVATE)
        val enabled = PlayerAccessibilityService.isEnabled(this)
        val previouslyGranted = prefs.getBoolean(PREF_GRANTED, false)
        Log.d(TAG, "restoreAccessibilityIfRevoked enabled=$enabled previouslyGranted=$previouslyGranted")

        // Remember once we've ever observed the service as enabled. Stays
        // remembered until the user clears app data.
        if (enabled) {
            prefs.edit().putBoolean(PREF_GRANTED, true).apply()
            return
        }

        if (!previouslyGranted) return

        val component = "$packageName/${PlayerAccessibilityService::class.java.canonicalName}"
        if (ShizukuLauncher.restoreAccessibilityServices(this, setOf(component))) {
            Log.i(TAG, "Restored revoked accessibility service via Shizuku: $component")
        } else {
            Log.d(TAG, "Accessibility service revoked but couldn't restore (Shizuku unavailable or denied): $component")
        }
    }

    private fun initSyncScheduler() {
        CoroutineScope(Dispatchers.IO).launch {
            val syncedPlaylistIds = repository.getAllSyncedPlaylistIds()
            if (syncedPlaylistIds.isNotEmpty()) {
                SyncScheduler.schedulePeriodicSync(this@MusicHubApplication)
            }
        }
    }
}
