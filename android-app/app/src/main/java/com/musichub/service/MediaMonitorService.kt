package com.musichub.service

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.musichub.platform.Platforms

/**
 * Service that monitors media playback from other apps (NetEase, QQ Music, Bilibili).
 * When a song finishes, it can trigger auto-advance to next song in our queue.
 *
 * Requires BIND_NOTIFICATION_LISTENER_SERVICE permission and user to enable
 * notification access in Settings.
 */
class MediaMonitorService : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeControllers = mutableMapOf<String, MediaController>()
    private var lastPlaybackState: Int? = null
    private var lastPosition: Long = 0
    private var maxPositionReached: Long = 0  // Track highest position during song playback
    private var lastMetadataDuration: Long = 0
    private val handler = Handler(Looper.getMainLooper())

    // Position polling for apps that don't report position in callbacks (like NetEase)
    private val POSITION_POLL_INTERVAL_MS = 1000L  // Poll every 1 second for faster detection
    private var isPolling = false
    private var songEndTriggered = false  // Prevent duplicate triggers

    // Manual control mode - when Music Hub initiates song switch, suppress auto-advance
    private var manualControlActive = false
    private var manualControlTimestamp: Long = 0
    private val MANUAL_CONTROL_TIMEOUT_MS = 5000L  // 5 second timeout for manual control mode
    private val positionPollRunnable = object : Runnable {
        override fun run() {
            pollCurrentPosition()
            if (isPolling) {
                handler.postDelayed(this, POSITION_POLL_INTERVAL_MS)
            }
        }
    }

    // Threshold to consider song as "near end" - 3 seconds before end
    private val SONG_END_THRESHOLD_MS = 3000L
    // Minimum position to consider as a valid "song finished" (avoid false triggers at start)
    private val MIN_POSITION_FOR_END_MS = 30000L

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onActiveSessionsChanged(controllers)
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handlePlaybackStateChange(state)
        }

        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            metadata?.let {
                val newDuration = it.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
                val newTitle = it.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""

                // Detect song change while playing (common for NetEase)
                // If we were playing and metadata changes to a new song, the old song finished
                // Use maxPositionReached instead of lastPosition because NetEase may reset
                // position to 0 before sending the metadata change event
                if (lastPlaybackState == PlaybackState.STATE_PLAYING &&
                    newDuration != lastMetadataDuration &&
                    newDuration > 0 &&
                    lastMetadataDuration > 0 &&
                    maxPositionReached > MIN_POSITION_FOR_END_MS) {

                    // Check if we were near the end of the previous song
                    // Method 1: Absolute time remaining (45 seconds threshold)
                    val remainingTime = lastMetadataDuration - maxPositionReached
                    val wasNearEndAbsolute = remainingTime <= 45000L

                    // Method 2: Percentage-based (played more than 85% of the song)
                    // This handles cases where position reporting lags behind actual playback
                    val percentPlayed = (maxPositionReached.toDouble() / lastMetadataDuration.toDouble()) * 100
                    val wasNearEndPercent = percentPlayed >= 85.0

                    val wasNearEnd = wasNearEndAbsolute || wasNearEndPercent

                    Log.d(TAG, "Metadata change while playing: maxPos=$maxPositionReached, lastDuration=$lastMetadataDuration, remaining=$remainingTime, percentPlayed=${"%.1f".format(percentPlayed)}%, wasNearEnd=$wasNearEnd, alreadyTriggered=$songEndTriggered")

                    // Only trigger if we haven't already triggered via early detection
                    if (wasNearEnd && !songEndTriggered) {
                        Log.d(TAG, "Song ended (metadata change detection). Triggering song finished.")
                        sendSongFinishedBroadcast()
                    }

                    // Reset tracking for the new song regardless of whether we triggered
                    maxPositionReached = 0
                    lastPosition = 0
                    songEndTriggered = false  // Reset for new song
                }

                lastMetadataDuration = newDuration
                Log.d(TAG, "Metadata changed, duration: $newDuration ms, title: $newTitle")
            }
        }

        override fun onSessionDestroyed() {
            Log.d(TAG, "Media session destroyed")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "MediaMonitorService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopMonitoring()
        Log.d(TAG, "MediaMonitorService destroyed")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener connected")
        startMonitoring()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "NotificationListener disconnected")
        stopMonitoring()
    }

    private fun startMonitoring() {
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(this, MediaMonitorService::class.java)

            mediaSessionManager?.addOnActiveSessionsChangedListener(
                sessionListener,
                componentName
            )

            // Get current active sessions
            val controllers = mediaSessionManager?.getActiveSessions(componentName)
            if (controllers != null) {
                onActiveSessionsChanged(controllers)
            }

            Log.d(TAG, "Started monitoring media sessions")

            // Start position polling
            startPositionPolling()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start monitoring: ${e.message}")
        }
    }

    private fun stopMonitoring() {
        try {
            // Stop position polling
            stopPositionPolling()

            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)

            activeControllers.values.forEach { controller ->
                try {
                    controller.unregisterCallback(callback)
                } catch (e: Exception) {
                    // Ignore
                }
            }
            activeControllers.clear()

            Log.d(TAG, "Stopped monitoring media sessions")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping monitoring: ${e.message}")
        }
    }

    private fun onActiveSessionsChanged(controllers: List<MediaController>?) {
        if (controllers == null) return

        // Track which packages we care about for auto-advance
        // NOTE: Bilibili is intentionally EXCLUDED because:
        // 1. Users watch videos, not listen to sequential audio
        // 2. Bilibili doesn't report proper duration metadata
        // 3. Auto-advance doesn't make sense for video content
        val targetPackages = setOf(
            Platforms.PACKAGE_NAMES[Platforms.NETEASE],
            Platforms.PACKAGE_NAMES[Platforms.QQMUSIC]
            // Bilibili excluded - no auto-advance for video content
        )

        // Remove old controllers
        val currentPackages = controllers.map { it.packageName }.toSet()
        val toRemove = activeControllers.keys - currentPackages
        toRemove.forEach { pkg ->
            activeControllers[pkg]?.unregisterCallback(callback)
            activeControllers.remove(pkg)
            Log.d(TAG, "Removed media controller for: $pkg")
        }

        // Add new controllers
        controllers.forEach { controller ->
            val pkg = controller.packageName
            if (pkg in targetPackages && pkg !in activeControllers) {
                try {
                    controller.registerCallback(callback, handler)
                    activeControllers[pkg] = controller
                    Log.d(TAG, "Added media controller for: $pkg")

                    // Get initial metadata for duration
                    controller.metadata?.let { metadata ->
                        lastMetadataDuration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
                        Log.d(TAG, "Initial duration for $pkg: $lastMetadataDuration ms")
                    }

                    // Check current state
                    handlePlaybackStateChange(controller.playbackState)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register callback for $pkg: ${e.message}")
                }
            }
        }
    }

    private fun handlePlaybackStateChange(state: PlaybackState?) {
        val currentState = state?.state ?: return
        val position = state.position

        Log.d(TAG, "Playback state changed: $currentState (was: $lastPlaybackState), position: $position, duration: $lastMetadataDuration, songEndTriggered: $songEndTriggered")

        // Only detect song end when transitioning from PLAYING to PAUSED/STOPPED/NONE
        if (lastPlaybackState == PlaybackState.STATE_PLAYING &&
            (currentState == PlaybackState.STATE_STOPPED ||
             currentState == PlaybackState.STATE_PAUSED ||
             currentState == PlaybackState.STATE_NONE)) {

            // Skip if we already triggered song end via early detection
            if (songEndTriggered) {
                Log.d(TAG, "Song end already triggered via early detection, skipping state-based detection")
            } else {
                // Determine if this is a song completion vs user pause
                val isSongFinished = isSongFinished(position, lastMetadataDuration, currentState)

                Log.d(TAG, "Playback stopped. Position: $position, Duration: $lastMetadataDuration, isSongFinished: $isSongFinished")

                if (isSongFinished) {
                    // This looks like a natural end of song
                    sendSongFinishedBroadcast()
                    songEndTriggered = true
                } else {
                    Log.d(TAG, "Detected user pause (not song end), skipping auto-advance")
                }
            }

            // Reset tracking for new song when playback stops
            if (currentState == PlaybackState.STATE_STOPPED) {
                maxPositionReached = 0
                lastPosition = 0
                songEndTriggered = false
            }
        }

        lastPlaybackState = currentState
        // Only update lastPosition if it's a meaningful value (> 0)
        // This prevents resetting lastPosition when song transitions happen
        if (position > 0) {
            lastPosition = position
            // Track the maximum position reached during this song
            if (position > maxPositionReached) {
                maxPositionReached = position
            }
        }
    }

    /**
     * Determine if the song has finished playing naturally.
     *
     * Criteria for song finished:
     * 1. Position is within SONG_END_THRESHOLD_MS of the end (near end)
     * 2. Position reset to 0 and last position was near the end or past minimum play time
     * 3. State is STOPPED (not just PAUSED) with position reset
     */
    private fun isSongFinished(position: Long, duration: Long, currentState: Int): Boolean {
        // Case 1: Position is near the end of the song
        if (duration > 0 && position > 0) {
            val remainingTime = duration - position
            if (remainingTime <= SONG_END_THRESHOLD_MS) {
                Log.d(TAG, "Duration check: remaining=$remainingTime ms, nearEnd=true")
                return true
            }
        }

        // Case 2: Position reset to 0 - this is common when song finishes
        // Check if we were playing long enough (lastPosition indicates progress)
        if (position == 0L || position < 1000L) {
            // If last position was near the end of the song
            if (duration > 0 && lastPosition > 0) {
                val wasNearEnd = (duration - lastPosition) <= SONG_END_THRESHOLD_MS
                if (wasNearEnd) {
                    Log.d(TAG, "Position reset detected, last position was near end (lastPosition=$lastPosition, duration=$duration)")
                    return true
                }
            }

            // If last position indicates substantial playback (more than 30 seconds)
            // and current state is STOPPED (not just PAUSED), likely song ended
            if (lastPosition > MIN_POSITION_FOR_END_MS && currentState == PlaybackState.STATE_STOPPED) {
                Log.d(TAG, "Position reset with STOPPED state after substantial playback (lastPosition=$lastPosition)")
                return true
            }
        }

        Log.d(TAG, "Not a song end: position=$position, lastPosition=$lastPosition, duration=$duration, state=$currentState")
        return false
    }

    private fun startPositionPolling() {
        if (!isPolling) {
            isPolling = true
            handler.postDelayed(positionPollRunnable, POSITION_POLL_INTERVAL_MS)
            Log.d(TAG, "Started position polling")
        }
    }

    private fun stopPositionPolling() {
        isPolling = false
        handler.removeCallbacks(positionPollRunnable)
        Log.d(TAG, "Stopped position polling")
    }

    private fun pollCurrentPosition() {
        // Poll position from all active controllers
        activeControllers.values.forEach { controller ->
            try {
                val state = controller.playbackState
                if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                    // Calculate current position based on last update time
                    val lastUpdateTime = state.lastPositionUpdateTime
                    val position = state.position
                    val playbackSpeed = state.playbackSpeed

                    // Estimate current position based on elapsed time since last update
                    val elapsedRealtime = android.os.SystemClock.elapsedRealtime()
                    val timeSinceUpdate = elapsedRealtime - lastUpdateTime
                    val estimatedPosition = position + (timeSinceUpdate * playbackSpeed).toLong()

                    if (estimatedPosition > 0) {
                        Log.d(TAG, "Polled position for ${controller.packageName}: estimated=$estimatedPosition ms (base=$position, elapsed=$timeSinceUpdate)")

                        // Update tracking
                        if (estimatedPosition > lastPosition) {
                            lastPosition = estimatedPosition
                        }
                        if (estimatedPosition > maxPositionReached) {
                            maxPositionReached = estimatedPosition
                        }

                        // Early song end detection: trigger close to end to beat the original app's auto-advance
                        // But not too early to avoid cutting off the song
                        if (lastMetadataDuration > 0 && !songEndTriggered) {
                            val percentPlayed = (estimatedPosition.toDouble() / lastMetadataDuration.toDouble()) * 100
                            val remainingTime = lastMetadataDuration - estimatedPosition

                            // Trigger early if:
                            // 1. We've played at least 99% of the song, OR
                            // 2. Less than 2 seconds remaining
                            // AND we've played enough to not be a false trigger (> 30 seconds)
                            if (estimatedPosition > MIN_POSITION_FOR_END_MS &&
                                (percentPlayed >= 99.0 || remainingTime <= 2000L)) {
                                Log.d(TAG, "Early song end detected! percentPlayed=${String.format("%.1f", percentPlayed)}%, remaining=${remainingTime}ms")
                                songEndTriggered = true
                                sendSongFinishedBroadcast()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error polling position: ${e.message}")
            }
        }
    }

    private fun sendSongFinishedBroadcast() {
        // Check if we're in manual control mode (Music Hub initiated song switch)
        if (manualControlActive) {
            val elapsed = System.currentTimeMillis() - manualControlTimestamp
            if (elapsed < MANUAL_CONTROL_TIMEOUT_MS) {
                Log.d(TAG, "Suppressing song finished broadcast - manual control active (${elapsed}ms ago)")
                return
            } else {
                // Manual control timed out, allow auto-advance again
                Log.d(TAG, "Manual control timed out, allowing song finished broadcast")
                manualControlActive = false
            }
        }

        Log.d(TAG, "Sending song finished broadcast")
        val intent = android.content.Intent(ACTION_SONG_FINISHED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Can use this to detect music app notifications
        super.onNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    /**
     * Open the player page of a music app by sending its notification's contentIntent.
     * This simulates tapping the notification, which reliably opens the player/lyrics page.
     *
     * @param packageName The package name of the music app (e.g., com.tencent.qqmusic)
     * @return true if the contentIntent was sent successfully
     */
    fun openPlayerViaNotification(packageName: String): Boolean {
        try {
            val notifications = activeNotifications ?: return false
            for (sbn in notifications) {
                if (sbn.packageName == packageName) {
                    val contentIntent = sbn.notification?.contentIntent
                    if (contentIntent != null) {
                        Log.d(TAG, "Sending contentIntent for $packageName notification")
                        contentIntent.send()
                        return true
                    }
                }
            }
            Log.w(TAG, "No notification found for $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send notification contentIntent: ${e.message}")
        }
        return false
    }

    // Track package name of the platform we're switching FROM (only for cross-platform switches)
    private var switchingFromPackage: String? = null
    // Whether we're switching between different platforms
    private var isCrossPlatformSwitch: Boolean = false

    /**
     * Pause all active media controllers.
     * This should be called before switching to a new song to prevent
     * the original app from auto-advancing to its own next song.
     *
     * @param targetPlatformPackage The package name of the platform we're switching TO.
     *                              If same as current, won't schedule repeated pauses.
     */
    fun pauseAllMedia(targetPlatformPackage: String? = null) {
        Log.d(TAG, "pauseAllMedia called - pausing all active controllers, target=$targetPlatformPackage")

        // Enter manual control mode to suppress auto-advance detection during song switch
        manualControlActive = true
        manualControlTimestamp = System.currentTimeMillis()

        // Remember which packages we're pausing so we can monitor them
        switchingFromPackage = null
        isCrossPlatformSwitch = false

        activeControllers.values.forEach { controller ->
            try {
                val state = controller.playbackState
                if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                    Log.d(TAG, "Pausing media for ${controller.packageName}")
                    switchingFromPackage = controller.packageName

                    // Check if this is a cross-platform switch
                    // Only schedule repeated pauses for cross-platform switches
                    isCrossPlatformSwitch = targetPlatformPackage != null &&
                            targetPlatformPackage != controller.packageName

                    // Send pause command
                    controller.transportControls.pause()
                    // Send stop command as backup - some apps respond better to stop
                    controller.transportControls.stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing media for ${controller.packageName}: ${e.message}")
            }
        }

        // Schedule repeated pause attempts ONLY for cross-platform switches
        // For same-platform switches, the new song will start in the same app,
        // and we don't want to pause it!
        if (isCrossPlatformSwitch) {
            Log.d(TAG, "Cross-platform switch detected, scheduling repeated pause for $switchingFromPackage")
            scheduleRepeatedPause()
        } else {
            Log.d(TAG, "Same-platform switch, skipping repeated pause")
        }

        // Reset tracking for the new song
        songEndTriggered = false
        maxPositionReached = 0
        lastPosition = 0
        lastMetadataDuration = 0
    }

    /**
     * Schedule repeated pause attempts to catch apps that auto-advance after initial pause.
     * This is needed because NetEase Cloud Music will start playing the next song
     * even after we send pause/stop commands, when switching to a DIFFERENT platform.
     */
    private fun scheduleRepeatedPause() {
        // Pause again after 500ms, 1000ms, 1500ms, and 2000ms to catch auto-advance
        val delays = listOf(500L, 1000L, 1500L, 2000L)
        delays.forEach { delay ->
            handler.postDelayed({
                if (manualControlActive && switchingFromPackage != null && isCrossPlatformSwitch) {
                    pauseSpecificPackage(switchingFromPackage!!)
                }
            }, delay)
        }
    }

    /**
     * Pause a specific package's media controller.
     */
    private fun pauseSpecificPackage(packageName: String) {
        activeControllers[packageName]?.let { controller ->
            try {
                val state = controller.playbackState
                if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                    Log.d(TAG, "Re-pausing media for $packageName (catching auto-advance)")
                    controller.transportControls.pause()
                    controller.transportControls.stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error re-pausing media for $packageName: ${e.message}")
            }
        }
    }

    /**
     * Called when a new song starts playing via Music Hub.
     * This disables manual control mode after a delay to re-enable auto-advance detection.
     */
    fun onNewSongStarted() {
        Log.d(TAG, "onNewSongStarted called - will re-enable auto-advance detection after delay")
        // Wait for the music app to start playing and report metadata
        handler.postDelayed({
            Log.d(TAG, "Re-enabling auto-advance detection (manual control deactivated)")
            manualControlActive = false
            switchingFromPackage = null  // Stop re-pausing the old package
            songEndTriggered = false
        }, 3000L)  // 3 seconds to let the music app settle
    }

    /**
     * Data class to hold current playback info for UI updates.
     */
    data class PlaybackInfo(
        val isPlaying: Boolean,
        val position: Long,     // Current position in milliseconds
        val duration: Long,     // Total duration in milliseconds
        val packageName: String?
    )

    /**
     * Get current playback info from any active media session.
     * Prioritizes controllers that are actively playing.
     * Returns null if no active playback.
     */
    fun getPlaybackInfo(): PlaybackInfo? {
        // First pass: find a controller that is actively playing
        activeControllers.values.forEach { controller ->
            try {
                val state = controller.playbackState
                val metadata = controller.metadata

                if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                    val lastUpdateTime = state.lastPositionUpdateTime
                    val elapsedRealtime = android.os.SystemClock.elapsedRealtime()
                    val timeSinceUpdate = elapsedRealtime - lastUpdateTime
                    val position = state.position + (timeSinceUpdate * state.playbackSpeed).toLong()
                    val duration = metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L

                    if (duration > 0) {
                        return PlaybackInfo(
                            isPlaying = true,
                            position = position,
                            duration = duration,
                            packageName = controller.packageName
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting playback info (playing): ${e.message}")
            }
        }

        // Second pass: find any controller with valid metadata (paused state)
        activeControllers.values.forEach { controller ->
            try {
                val state = controller.playbackState
                val metadata = controller.metadata

                if (state != null && metadata != null) {
                    val duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
                    if (duration > 0) {
                        return PlaybackInfo(
                            isPlaying = false,
                            position = state.position,
                            duration = duration,
                            packageName = controller.packageName
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting playback info (paused): ${e.message}")
            }
        }

        return null
    }

    /**
     * Toggle play/pause on the currently active media session.
     * Prioritizes controllers that are actively playing.
     */
    fun togglePlayPause() {
        // First, try to find a playing controller to pause
        activeControllers.values.forEach { controller ->
            try {
                val state = controller.playbackState
                if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                    Log.d(TAG, "Pausing playback for ${controller.packageName}")
                    controller.transportControls.pause()
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing: ${e.message}")
            }
        }

        // No playing controller found, try to resume any paused controller
        activeControllers.values.forEach { controller ->
            try {
                val state = controller.playbackState
                if (state != null && state.state == PlaybackState.STATE_PAUSED) {
                    Log.d(TAG, "Resuming playback for ${controller.packageName}")
                    controller.transportControls.play()
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming: ${e.message}")
            }
        }

        // Last resort: try the first controller with any state
        activeControllers.values.firstOrNull()?.let { controller ->
            try {
                Log.d(TAG, "Attempting to play ${controller.packageName}")
                controller.transportControls.play()
            } catch (e: Exception) {
                Log.e(TAG, "Error playing: ${e.message}")
            }
        }
    }

    /**
     * Seek to a specific position in the currently playing song.
     * @param positionMs The position to seek to in milliseconds
     */
    fun seekTo(positionMs: Long) {
        // Find the currently active controller (playing or paused)
        activeControllers.values.forEach { controller ->
            try {
                val state = controller.playbackState
                if (state != null && (state.state == PlaybackState.STATE_PLAYING ||
                            state.state == PlaybackState.STATE_PAUSED)) {
                    Log.d(TAG, "Seeking to $positionMs ms for ${controller.packageName}")
                    controller.transportControls.seekTo(positionMs)
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error seeking: ${e.message}")
            }
        }
        Log.d(TAG, "No active controller found for seeking")
    }

    companion object {
        private const val TAG = "MediaMonitorService"
        const val ACTION_SONG_FINISHED = "com.musichub.action.SONG_FINISHED"
        const val ACTION_PAUSE_ALL = "com.musichub.action.PAUSE_ALL"

        // Singleton instance for direct access to pauseAllMedia()
        @Volatile
        private var instance: MediaMonitorService? = null

        fun getInstance(): MediaMonitorService? = instance

        /**
         * Check if notification listener is enabled.
         */
        fun isEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            return flat?.contains(context.packageName) == true
        }

        /**
         * Open settings to enable notification listener.
         */
        fun openSettings(context: Context) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            )
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        /**
         * Check if device is MIUI (Xiaomi)
         */
        fun isMiui(): Boolean {
            return !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()
        }

        private fun getSystemProperty(key: String): String? {
            return try {
                val clazz = Class.forName("android.os.SystemProperties")
                val method = clazz.getMethod("get", String::class.java)
                method.invoke(null, key) as? String
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Open MIUI auto-start settings
         */
        fun openMiuiAutoStartSettings(context: Context) {
            try {
                val intent = android.content.Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open MIUI auto-start settings: ${e.message}")
                // Fallback to app info settings
                try {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    ).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to open app settings: ${e2.message}")
                }
            }
        }
    }
}
