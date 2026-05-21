package com.musichub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.musichub.data.local.MusicHubDatabase
import com.musichub.data.repository.MusicRepository
import com.musichub.service.AccessibilityGrantStore
import com.musichub.service.AutoGrantResult
import com.musichub.service.DeepLinkLauncher
import com.musichub.service.MediaMonitorService
import com.musichub.service.PlayerAccessibilityService
import com.musichub.service.ShizukuLauncher
import com.musichub.sync.SyncScheduler
import com.musichub.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

class MusicHubApplication : Application() {

    val database: MusicHubDatabase by lazy {
        MusicHubDatabase.getDatabase(this)
    }

    val repository: MusicRepository by lazy {
        MusicRepository(database)
    }

    // Background scope for restore retries triggered from the main thread (e.g.,
    // Shizuku binder-received listener) — we always dispatch to IO before
    // touching SharedPreferences / Settings.Secure / Shizuku binder IPC.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "MusicHubApplication"
        const val PREF_AUTO_GRANT = "auto_grant_via_shizuku"
        private const val PERMISSIONS_CHANNEL = "permissions_channel"
        private const val NOTIFICATION_ID_AUTO_GRANT = 1102
        private lateinit var instance: MusicHubApplication

        fun getInstance(): MusicHubApplication = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        initSyncScheduler()
        rebindMediaMonitorIfNeeded()
        runRestoreCheck("onCreate")
        registerShizukuBinderListener()
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
        // SPEC: current-platform-playback-isolation
        // Skip the rebind when the service is already bound (instance != null
        // means onListenerConnected ran). The Shizuku toggle below tears down
        // the service and bounces its in-memory state — including
        // currentPlatformPackage — every time. With MainActivity.onResume
        // calling us on every UI return, that wipes the platform binding
        // every 5-10s during normal use, making the strict-filter
        // getPlaybackInfo return null and the timeout watchdog spuriously
        // skip QQ Music / Bilibili songs whose metadata takes >5s to load.
        if (MediaMonitorService.getInstance() != null) {
            Log.d(TAG, "rebindMediaMonitorIfNeeded: already bound, skipping toggle")
            return
        }
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
     * Public, idempotent restore-check entry point. Callers fire this from
     * MainActivity.onResume, the PlaybackService ContentObserver, the
     * Settings diagnostic action, and the Shizuku binder-received listener.
     * Always dispatches to a background dispatcher because the restore path
     * touches Shizuku binder IPC.
     */
    fun restoreAccessibilityIfNeeded(reason: String) {
        appScope.launch {
            runRestoreCheck(reason)
        }
    }

    /**
     * Public, idempotent bulk-grant entry point. Reads the
     * [PREF_AUTO_GRANT] preference (default `true`); when enabled and
     * Shizuku is `READY`, attempts to grant every special permission Tutti
     * needs and posts a one-shot notification listing what was newly
     * granted. Called from the Shizuku binder-received listener at app
     * startup and from the Settings diagnostic action.
     *
     * @return the [AutoGrantResult] (empty/no-op when the pref is OFF or
     *   Shizuku is not READY).
     */
    fun runAutoGrantIfEnabled(reason: String): AutoGrantResult {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(PREF_AUTO_GRANT, true)) {
            Log.d(TAG, "autoGrant skipped: pref disabled (reason=$reason)")
            return AutoGrantResult(emptyList(), ShizukuLauncher.status(this))
        }
        val result = ShizukuLauncher.autoGrantAllPermissions(this)
        Log.i(
            TAG,
            "runAutoGrantIfEnabled reason=$reason status=${result.shizukuStatus} " +
                "newly=${result.newlyGranted.map { it.key }} failed=${result.failed.map { it.key }}"
        )
        if (result.newlyGranted.isNotEmpty()) {
            showAutoGrantNotification(result.newlyGranted)
        }
        return result
    }

    /**
     * Same as [runAutoGrantIfEnabled] but dispatched to a background
     * coroutine. Use this from main-thread callers (e.g., the Shizuku
     * binder-received listener) that should not block on Shizuku IPC.
     *
     * The returned Job is stashed as [lastAutoGrantJob] so playback code can
     * `awaitAutoGrantIfRunning` before launching a deep link — that prevents
     * the HyperOS "想要打开 网易云音乐" dialog from appearing when the user
     * taps play before the asynchronous grant finishes.
     */
    fun runAutoGrantInBackground(reason: String): Job {
        val job = appScope.launch {
            runAutoGrantIfEnabled(reason)
        }
        lastAutoGrantJob = job
        return job
    }

    /**
     * Suspend until any in-flight `runAutoGrantInBackground` job completes,
     * up to [timeoutMs] milliseconds. Safe to call when no job is running
     * (returns immediately). Used by `PlaybackService.launchCurrentSong` to
     * ensure SYSTEM_ALERT_WINDOW / WRITE_SETTINGS / accessibility / listener
     * grants are in place before the cross-app launch fires.
     */
    suspend fun awaitAutoGrantIfRunning(timeoutMs: Long = 2000L) {
        val job = lastAutoGrantJob ?: return
        if (job.isCompleted) return
        withTimeoutOrNull(timeoutMs) { job.join() }
    }

    @Volatile
    private var lastAutoGrantJob: Job? = null

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
     * Mitigation: on every reasonable trigger (process start, Shizuku
     * binder connected, MainActivity.onResume, accessibility settings
     * ContentObserver, diagnostic action), if the user previously granted
     * the service (tracked in [AccessibilityGrantStore]) but it is now
     * missing from the setting, ask Shizuku to re-write it. Writing
     * `enabled_accessibility_services` needs WRITE_SECURE_SETTINGS, which
     * is granted to shell UID — exactly what Shizuku provides.
     */
    private fun runRestoreCheck(reason: String) {
        val enabled = PlayerAccessibilityService.isEnabled(this)
        val previouslyGranted = AccessibilityGrantStore.wasGranted(this)
        Log.d(
            TAG,
            "restoreAccessibilityIfNeeded reason=$reason enabled=$enabled previouslyGranted=$previouslyGranted"
        )

        // Idempotent short-circuit: when the service is already enabled, refresh
        // the pref and return without contacting Shizuku. Lets every trigger
        // point call this freely without redundant work.
        if (enabled) {
            AccessibilityGrantStore.setGranted(this)
            return
        }

        if (!previouslyGranted) return

        val component = "$packageName/${PlayerAccessibilityService::class.java.canonicalName}"
        val status = ShizukuLauncher.status(this)
        if (ShizukuLauncher.restoreAccessibilityServices(this, setOf(component))) {
            Log.i(
                TAG,
                "Restored revoked accessibility service via Shizuku: $component (reason=$reason)"
            )
        } else {
            Log.w(
                TAG,
                "Accessibility service revoked but Shizuku restore failed: $component (status=$status, reason=$reason)"
            )
        }
    }

    /**
     * Shizuku's binder connects asynchronously through `ShizukuProvider`. If
     * the binder isn't bound yet when [onCreate] runs, the synchronous restore
     * attempt at process start cannot reach shell UID and silently fails. The
     * sticky listener fires immediately if the binder is already bound, and
     * otherwise fires on the next bind — covering the timing race without
     * polling. Wrapped in try/catch so a missing-symbol or transient-throwable
     * scenario can't block app startup.
     */
    private fun registerShizukuBinderListener() {
        try {
            Shizuku.addBinderReceivedListenerSticky {
                // Both the targeted accessibility restore and the broader bulk
                // auto-grant run from this single trigger. Dispatch each to IO
                // to keep the binder-receive callback (main thread) snappy.
                restoreAccessibilityIfNeeded("shizuku-binder-received")
                runAutoGrantInBackground("shizuku-binder-received")
            }
        } catch (e: Throwable) {
            Log.w(
                TAG,
                "Failed to register Shizuku binder listener: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /**
     * Lazily create the "permissions" notification channel and post the
     * auto-grant notification. The channel is `IMPORTANCE_LOW` so it never
     * peeks or makes a sound. The notification is dismissable (`setAutoCancel`
     * + no `setOngoing`); tapping it opens [MainActivity].
     */
    private fun showAutoGrantNotification(newlyGranted: List<com.musichub.service.PermissionState>) {
        if (newlyGranted.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PERMISSIONS_CHANNEL,
                getString(R.string.auto_grant_notification_channel_cn),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.auto_grant_notification_channel_desc_cn)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val names = newlyGranted.joinToString("、") { it.displayName }
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, PERMISSIONS_CHANNEL)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(getString(R.string.auto_grant_notification_title_cn))
            .setContentText(
                getString(R.string.auto_grant_notification_short_cn, newlyGranted.size, names)
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(getString(R.string.auto_grant_notification_long_cn, names))
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tapIntent)
            .build()
        try {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID_AUTO_GRANT, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not yet propagated on this process — silently
            // best-effort. The next launch will re-check and find everything
            // already granted, including POST_NOTIFICATIONS.
            Log.w(TAG, "Auto-grant notify suppressed: ${e.message}")
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
