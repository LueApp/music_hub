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
    private var controllerCallbacks = mutableMapOf<String, MediaController.Callback>()
    // Lock for thread-safe access to activeControllers and controllerCallbacks.
    // The RemoteServer broadcast loop reads these from Dispatchers.IO while the main thread mutates them.
    private val controllersLock = Any()
    private var lastPlaybackState: Int? = null
    private var lastPlaybackStatePackage: String? = null  // Which package set lastPlaybackState
    private var lastPosition: Long = 0
    private var maxPositionReached: Long = 0  // Track highest position during song playback
    private var lastMetadataDuration: Long = 0
    private val handler = Handler(Looper.getMainLooper())

    // The package name of the platform currently playing via Music Hub.
    // Only song-end events from this package trigger auto-advance.
    private var currentPlatformPackage: String? = null

    // Position polling for apps that don't report position in callbacks (like NetEase)
    private val POSITION_POLL_INTERVAL_MS = 1000L  // Poll every 1 second for faster detection
    private var isPolling = false
    private var songEndTriggered = false  // Prevent duplicate triggers

    // Manual control mode - when Music Hub initiates song switch, suppress auto-advance
    private var manualControlActive = false
    private var manualControlTimestamp: Long = 0
    private val MANUAL_CONTROL_TIMEOUT_MS = 5000L  // 5 second timeout for manual control mode

    // Tracks same-platform NetEase switch for reactive auto-advance suppression.
    // Set by PlaybackService when scheduling pauses, expires after a short window
    // (auto-advance happens within ~2s, target song takes 3-8s).
    private var samePlatformNetEasePauseUntil: Long = 0


    // One-shot callback for detecting when a platform starts playing.
    // Used by DeepLinkLauncher to restore auto-rotation after NetEase loads.
    private var pendingPlaybackCallback: (() -> Unit)? = null
    private var pendingPlaybackPackage: String? = null
    private var pendingPlaybackTimeoutRunnable: Runnable? = null
    private var pendingPlaybackArmedTime: Long = 0  // SystemClock.elapsedRealtime when callback becomes armed
    private val PLAYBACK_CALLBACK_MIN_DELAY_MS = 3000L  // Ignore STATE_PLAYING for 3s to skip stale events
    private val positionPollRunnable = object : Runnable {
        override fun run() {
            pollCurrentPosition()
            if (isPolling) {
                handler.postDelayed(this, POSITION_POLL_INTERVAL_MS)
            }
        }
    }

    // Threshold to consider song as "near end" - 3 seconds before end.
    // Bilibili gets tighter thresholds because its MediaSession can report
    // noisy video metadata; switching early cuts visible content.
    private val SONG_END_THRESHOLD_MS = 3000L
    private val BILIBILI_SONG_END_THRESHOLD_MS = 1000L
    private val EARLY_SONG_END_THRESHOLD_MS = 2000L
    private val BILIBILI_EARLY_SONG_END_THRESHOLD_MS = 500L
    private val BILIBILI_METADATA_END_PERCENT = 99.5
    private val BILIBILI_EARLY_END_PERCENT = 99.8
    private val BILIBILI_PERCENT_END_MAX_REMAINING_MS = 3000L
    private val BILIBILI_POSITION_OVERRUN_TOLERANCE_MS = 1000L
    // Minimum position to consider as a valid "song finished" (avoid false triggers at start)
    private val MIN_POSITION_FOR_END_MS = 30000L

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onActiveSessionsChanged(controllers)
    }

    /**
     * Create a per-controller callback that knows which package it belongs to.
     * This allows us to only trigger song-end detection for the current platform.
     */
    private fun createCallbackForPackage(packageName: String) = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handlePlaybackStateChange(state, packageName)
        }

        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            metadata?.let {
                val newDuration = it.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
                val newTitle = it.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""

                // Only process song-end detection for the current platform
                if (currentPlatformPackage != null && packageName != currentPlatformPackage) {
                    Log.d(TAG, "Ignoring metadata change from $packageName (current platform: $currentPlatformPackage), title: $newTitle")
                    return
                }

                // Detect song change while playing (common for NetEase)
                // If we were playing and metadata changes to a new song, the old song finished
                // Use maxPositionReached instead of lastPosition because NetEase may reset
                // position to 0 before sending the metadata change event
                if (lastPlaybackState == PlaybackState.STATE_PLAYING &&
                    newDuration != lastMetadataDuration &&
                    newDuration > 0 &&
                    lastMetadataDuration > 0 &&
                    maxPositionReached > MIN_POSITION_FOR_END_MS) {

                    // Sanity check: if maxPositionReached exceeds lastMetadataDuration by >10%,
                    // it's stale data from a different song's playback state — ignore it
                    if (maxPositionReached > lastMetadataDuration * 1.1) {
                        Log.d(TAG, "Metadata change: maxPos=$maxPositionReached exceeds lastDuration=$lastMetadataDuration by >10%, stale position data — skipping song-end detection")
                        maxPositionReached = 0
                        lastPosition = 0
                        songEndTriggered = false
                    } else {
                        val remainingTime = lastMetadataDuration - maxPositionReached
                        val percentPlayed = (maxPositionReached.toDouble() / lastMetadataDuration.toDouble()) * 100
                        val wasNearEnd = wasNearEndForMetadataChange(packageName, maxPositionReached, lastMetadataDuration)

                        Log.d(TAG, "Metadata change while playing: maxPos=$maxPositionReached, lastDuration=$lastMetadataDuration, remaining=$remainingTime, percentPlayed=${"%.1f".format(percentPlayed)}%, wasNearEnd=$wasNearEnd, alreadyTriggered=$songEndTriggered")

                        // Only trigger if we haven't already triggered via early detection
                        if (wasNearEnd && !songEndTriggered) {
                            Log.d(TAG, "Song ended (metadata change detection). Triggering song finished.")
                            sendSongFinishedBroadcast()
                        }
                    }

                    // Reset tracking for the new song regardless of whether we triggered
                    maxPositionReached = 0
                    lastPosition = 0
                    songEndTriggered = false  // Reset for new song
                }

                lastMetadataDuration = newDuration
                Log.d(TAG, "Metadata changed from $packageName, duration: $newDuration ms, title: $newTitle")
            }
        }

        override fun onSessionDestroyed() {
            Log.d(TAG, "Media session destroyed for $packageName")
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

            synchronized(controllersLock) {
                activeControllers.forEach { (pkg, controller) ->
                    try {
                        controllerCallbacks[pkg]?.let { cb -> controller.unregisterCallback(cb) }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
                activeControllers.clear()
                controllerCallbacks.clear()
            }

            Log.d(TAG, "Stopped monitoring media sessions")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping monitoring: ${e.message}")
        }
    }

    private fun onActiveSessionsChanged(controllers: List<MediaController>?) {
        if (controllers == null) return

        // Track which packages we care about for auto-advance
        val targetPackages = setOf(
            Platforms.PACKAGE_NAMES[Platforms.NETEASE],
            Platforms.PACKAGE_NAMES[Platforms.QQMUSIC],
            Platforms.PACKAGE_NAMES[Platforms.BILIBILI]
        )

        synchronized(controllersLock) {
            // Remove old controllers
            val currentPackages = controllers.map { it.packageName }.toSet()
            val toRemove = activeControllers.keys - currentPackages
            toRemove.forEach { pkg ->
                controllerCallbacks[pkg]?.let { cb ->
                    activeControllers[pkg]?.unregisterCallback(cb)
                }
                activeControllers.remove(pkg)
                controllerCallbacks.remove(pkg)
                Log.d(TAG, "Removed media controller for: $pkg")
            }

            // Add or update controllers
            controllers.forEach { controller ->
                val pkg = controller.packageName
                if (pkg !in targetPackages) return@forEach

                // Check if we already have a controller for this package
                val existingController = activeControllers[pkg]
                val isNewSession = existingController != null &&
                    existingController.sessionToken != controller.sessionToken

                if (isNewSession) {
                    // Session token changed — the app created a new MediaSession.
                    // The old controller is stale and its callback will never fire.
                    Log.d(TAG, "Session token changed for $pkg, replacing stale controller")
                    controllerCallbacks[pkg]?.let { cb ->
                        try { existingController?.unregisterCallback(cb) } catch (_: Exception) {}
                    }
                    activeControllers.remove(pkg)
                    controllerCallbacks.remove(pkg)
                }

                if (pkg !in activeControllers) {
                    try {
                        val cb = createCallbackForPackage(pkg)
                        controller.registerCallback(cb, handler)
                        activeControllers[pkg] = controller
                        controllerCallbacks[pkg] = cb
                        Log.d(TAG, "Added media controller for: $pkg")

                        // Get initial metadata for duration (only for current platform)
                        if (currentPlatformPackage == null || pkg == currentPlatformPackage) {
                            controller.metadata?.let { metadata ->
                                lastMetadataDuration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
                                Log.d(TAG, "Initial duration for $pkg: $lastMetadataDuration ms")
                            }
                        }

                        // Check current state
                        handlePlaybackStateChange(controller.playbackState, pkg)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to register callback for $pkg: ${e.message}")
                    }
                }
            }
        }
    }

    private fun handlePlaybackStateChange(state: PlaybackState?, fromPackage: String? = null) {
        val currentState = state?.state ?: return
        val position = state.position

        Log.d(TAG, "Playback state changed from $fromPackage: $currentState (was: $lastPlaybackState), position: $position, duration: $lastMetadataDuration, songEndTriggered: $songEndTriggered")

        // Reactive re-pause: if the old platform starts playing during a cross-platform switch,
        // immediately pause it to prevent audible bleed of the old platform's next song
        if (fromPackage != null && isCrossPlatformSwitch && fromPackage == switchingFromPackage &&
            (currentState == PlaybackState.STATE_PLAYING || currentState == PlaybackState.STATE_BUFFERING)) {
            Log.d(TAG, "Reactive re-pause: old platform $fromPackage entered state $currentState during cross-platform switch")
            activeControllers[fromPackage]?.let { controller ->
                try {
                    controller.transportControls.pause()
                    controller.transportControls.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in reactive re-pause for $fromPackage: ${e.message}")
                }
            }
            return
        }

        // Reactive pause for same-platform NetEase: during the pause window
        // (set by PlaybackService), if NetEase starts playing a new song (position near 0),
        // this is NetEase's auto-advance to its own queue — pause it immediately.
        // The scheduled pauses cover 500-1500ms, but NetEase may auto-advance later
        // (~1900ms observed). This reactive catch handles any timing within the window.
        // Uses a dedicated short window (2500ms) to avoid pausing the target song
        // which loads after 3-8s.
        // Uses pause()+stop() because NetEase fights back against pause() alone,
        // resuming playback immediately and letting audio leak for hundreds of ms.
        if (android.os.SystemClock.elapsedRealtime() < samePlatformNetEasePauseUntil &&
            fromPackage == "com.netease.cloudmusic" &&
            (currentState == PlaybackState.STATE_PLAYING || currentState == PlaybackState.STATE_BUFFERING) &&
            position <= 100) {
            Log.d(TAG, "Reactive pause: NetEase auto-advanced during pause window (position=$position), pausing+stopping")
            activeControllers[fromPackage]?.let { controller ->
                try {
                    controller.transportControls.pause()
                    controller.transportControls.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in reactive same-platform pause: ${e.message}")
                }
            }
            return
        }

        // Only process song-end detection for the current platform
        if (fromPackage != null && currentPlatformPackage != null && fromPackage != currentPlatformPackage) {
            Log.d(TAG, "Ignoring state change from $fromPackage (current platform: $currentPlatformPackage)")
            return
        }

        // Only detect song end when transitioning from PLAYING to PAUSED/STOPPED/NONE
        // AND the transition is from the same package that set PLAYING — after a service
        // restart, multiple packages fire initial state callbacks and a cross-package
        // PLAYING(QQ)→PAUSED(NetEase) transition would falsely trigger song end.
        val isSamePackageTransition = fromPackage == null || lastPlaybackStatePackage == null || fromPackage == lastPlaybackStatePackage
        if (lastPlaybackState == PlaybackState.STATE_PLAYING &&
            isSamePackageTransition &&
            (currentState == PlaybackState.STATE_STOPPED ||
             currentState == PlaybackState.STATE_PAUSED ||
             currentState == PlaybackState.STATE_NONE)) {

            // Skip if we already triggered song end via early detection
            if (songEndTriggered) {
                Log.d(TAG, "Song end already triggered via early detection, skipping state-based detection")
            } else {
                // Determine if this is a song completion vs user pause
                val isSongFinished = isSongFinished(position, lastMetadataDuration, currentState, fromPackage)

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

        // Fire pending playback callback when the target platform starts playing.
        // Ignore events before the armed time to skip stale STATE_PLAYING from the
        // previous song's MediaSession (which may still be active after CLEAR_TASK).
        if (currentState == PlaybackState.STATE_PLAYING &&
            pendingPlaybackCallback != null &&
            (fromPackage == null || fromPackage == pendingPlaybackPackage)) {
            if (android.os.SystemClock.elapsedRealtime() >= pendingPlaybackArmedTime) {
                Log.d(TAG, "Playback started for $fromPackage, firing pending callback")
                firePendingPlaybackCallback()
            } else {
                Log.d(TAG, "Playback event for $fromPackage ignored (callback not yet armed)")
            }
        }

        lastPlaybackState = currentState
        lastPlaybackStatePackage = fromPackage
        // Only update positions if not during manual control (song switching).
        // Stale state callbacks from the old song can re-set these after pauseAllMedia resets them,
        // causing false song-end detection when the old position exceeds the new song's duration.
        if (position > 0 && !manualControlActive) {
            lastPosition = position
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
    private fun isSongFinished(position: Long, duration: Long, currentState: Int, packageName: String?): Boolean {
        if (isBilibiliPackage(packageName)) {
            return isBilibiliSongFinished(position, duration, currentState)
        }

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

    private fun isBilibiliSongFinished(position: Long, duration: Long, currentState: Int): Boolean {
        if (duration <= 0) {
            Log.d(TAG, "Bilibili not a song end: missing duration, position=$position, state=$currentState")
            return false
        }

        val progressPosition = when {
            position > 0 -> position
            lastPosition > 0 -> lastPosition
            else -> 0L
        }
        if (progressPosition <= MIN_POSITION_FOR_END_MS) {
            Log.d(TAG, "Bilibili not a song end: insufficient progress position=$progressPosition, duration=$duration, state=$currentState")
            return false
        }

        val remainingTime = duration - progressPosition
        val percentPlayed = (progressPosition.toDouble() / duration.toDouble()) * 100
        val isNearEnd = isBilibiliNearEnd(
            progressPosition,
            duration,
            BILIBILI_SONG_END_THRESHOLD_MS,
            BILIBILI_METADATA_END_PERCENT
        )

        if (isNearEnd) {
            Log.d(TAG, "Bilibili song end: remaining=$remainingTime ms, percentPlayed=${"%.1f".format(percentPlayed)}%, state=$currentState")
            return true
        }

        Log.d(TAG, "Bilibili not a song end: position=$position, lastPosition=$lastPosition, duration=$duration, remaining=$remainingTime, percentPlayed=${"%.1f".format(percentPlayed)}%, state=$currentState")
        return false
    }

    private fun wasNearEndForMetadataChange(packageName: String?, position: Long, duration: Long): Boolean {
        if (duration <= 0 || position <= 0) return false

        val remainingTime = duration - position
        val percentPlayed = (position.toDouble() / duration.toDouble()) * 100

        if (isBilibiliPackage(packageName)) {
            return isBilibiliNearEnd(
                position,
                duration,
                BILIBILI_SONG_END_THRESHOLD_MS,
                BILIBILI_METADATA_END_PERCENT
            )
        }

        // The broader rule is intentionally kept for music apps. NetEase in
        // particular can change metadata after internally advancing its queue,
        // and Music Hub needs to catch that handoff.
        return remainingTime <= 45000L || percentPlayed >= 85.0
    }

    private fun isBilibiliNearEnd(
        position: Long,
        duration: Long,
        remainingThresholdMs: Long,
        percentThreshold: Double
    ): Boolean {
        val remainingTime = duration - position
        val percentPlayed = (position.toDouble() / duration.toDouble()) * 100
        val withinDurationTolerance = position <= duration + BILIBILI_POSITION_OVERRUN_TOLERANCE_MS
        val nearByTime = remainingTime <= remainingThresholdMs
        val nearByPercent = percentPlayed >= percentThreshold &&
            remainingTime <= BILIBILI_PERCENT_END_MAX_REMAINING_MS

        return withinDurationTolerance && (nearByTime || nearByPercent)
    }

    private fun isBilibiliPackage(packageName: String?): Boolean {
        return packageName == Platforms.PACKAGE_NAMES[Platforms.BILIBILI]
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
        // Poll position from all active controllers, but only trigger song-end
        // detection for the current platform's controller
        val controllerSnapshot = synchronized(controllersLock) {
            activeControllers.entries.map { (pkg, controller) -> pkg to controller }
        }
        controllerSnapshot.forEach { (pkg, controller) ->
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
                        Log.d(TAG, "Polled position for $pkg: estimated=$estimatedPosition ms (base=$position, elapsed=$timeSinceUpdate)")

                        // Only track position and detect song end for the current platform
                        val isCurrentPlatform = currentPlatformPackage == null || pkg == currentPlatformPackage
                        if (!isCurrentPlatform) return@forEach

                        // Update tracking (skip during manual control to prevent stale data)
                        if (!manualControlActive) {
                            if (estimatedPosition > lastPosition) {
                                lastPosition = estimatedPosition
                            }
                            if (estimatedPosition > maxPositionReached) {
                                maxPositionReached = estimatedPosition
                            }
                        }

                        // Early song end detection: trigger close to end to beat the original app's auto-advance
                        // But not too early to avoid cutting off the song
                        if (lastMetadataDuration > 0 && !songEndTriggered) {
                            val percentPlayed = (estimatedPosition.toDouble() / lastMetadataDuration.toDouble()) * 100
                            val remainingTime = lastMetadataDuration - estimatedPosition

                            // If position exceeds duration, the duration is likely stale/wrong
                            // (e.g. after service restart, or NetEase reporting incorrect duration).
                            // Don't treat this as song end — wait for metadata change detection instead.
                            if (estimatedPosition > lastMetadataDuration) {
                                Log.d(TAG, "Position ($estimatedPosition) exceeds duration ($lastMetadataDuration), stale duration — skipping early end detection")
                            } else {
                                val isBilibili = isBilibiliPackage(pkg)
                                val isNearEnd = if (isBilibili) {
                                    isBilibiliNearEnd(
                                        estimatedPosition,
                                        lastMetadataDuration,
                                        BILIBILI_EARLY_SONG_END_THRESHOLD_MS,
                                        BILIBILI_EARLY_END_PERCENT
                                    )
                                } else {
                                    percentPlayed >= 99.0 || remainingTime <= EARLY_SONG_END_THRESHOLD_MS
                                }

                                if (estimatedPosition > MIN_POSITION_FOR_END_MS && isNearEnd) {
                                    // Trigger early only after enough playback to avoid false positives.
                                    // NetEase keeps the previous 99% / 2s behavior; Bilibili uses a
                                    // stricter 99.8% / 0.5s window to avoid cutting off video endings.
                                    Log.d(TAG, "Early song end detected! percentPlayed=${String.format("%.1f", percentPlayed)}%, remaining=${remainingTime}ms, package=$pkg")
                                    // Pause NetEase immediately to prevent it from finishing the song
                                    // and auto-advancing to its own next track. This must happen BEFORE
                                    // the broadcast so there's no window for auto-advance.
                                    if (currentPlatformPackage == "com.netease.cloudmusic") {
                                        activeControllers["com.netease.cloudmusic"]?.let { controller ->
                                            try {
                                                controller.transportControls.pause()
                                                Log.d(TAG, "Pre-emptive pause sent to NetEase on early song end")
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Error pre-emptively pausing NetEase: ${e.message}")
                                            }
                                        }
                                    }
                                    songEndTriggered = true
                                    sendSongFinishedBroadcast()
                                }
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

        // Detect cross-platform switch: if target is specified, check if any OTHER
        // platform's controller exists (regardless of current playback state).
        // This fixes a race condition where the controller may have already transitioned
        // to PAUSED by the time we check, but the app can still auto-advance to a new song.
        val controllerSnapshot = synchronized(controllersLock) {
            activeControllers.entries.map { (pkg, controller) -> pkg to controller }
        }
        controllerSnapshot.forEach { (pkg, controller) ->
            try {
                if (targetPlatformPackage != null && targetPlatformPackage != pkg) {
                    // This controller belongs to a different platform than the target
                    switchingFromPackage = pkg
                    isCrossPlatformSwitch = true
                }

                val state = controller.playbackState
                val isPlayingOrRecentlyPlaying = state != null && (
                    state.state == PlaybackState.STATE_PLAYING ||
                    state.state == PlaybackState.STATE_PAUSED ||
                    state.state == PlaybackState.STATE_BUFFERING
                )

                if (isPlayingOrRecentlyPlaying) {
                    Log.d(TAG, "Pausing media for ${controller.packageName} (state=${state?.state})")
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
     * Extended to 4 seconds with frequent checks because auto-advance can happen
     * up to ~2 seconds after the initial pause.
     */
    private fun scheduleRepeatedPause() {
        // Pause at frequent intervals up to 4 seconds to catch auto-advance
        val delays = listOf(500L, 1000L, 1500L, 2000L, 2500L, 3000L, 3500L, 4000L)
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
        val controller = synchronized(controllersLock) {
            activeControllers[packageName]
        }
        controller?.let {
            try {
                val state = it.playbackState
                if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                    Log.d(TAG, "Re-pausing media for $packageName (catching auto-advance)")
                    it.transportControls.pause()
                    it.transportControls.stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error re-pausing media for $packageName: ${e.message}")
            }
        }
    }

    /**
     * Set which platform package is currently playing via Music Hub.
     * Only events from this package will trigger song-end detection.
     * This prevents false auto-advance from stale controllers of other platforms.
     */
    fun setCurrentPlatform(packageName: String?) {
        Log.d(TAG, "Current platform set to: $packageName (was: $currentPlatformPackage)")
        currentPlatformPackage = packageName
    }

    fun getCurrentPlatformPackage(): String? = currentPlatformPackage

    /**
     * Register a one-shot callback that fires when the specified platform's
     * MediaController transitions to STATE_PLAYING. If playback isn't detected
     * within timeoutMs, the callback fires anyway as a safety fallback.
     */
    fun onNextPlaybackStart(packageName: String, timeoutMs: Long, callback: () -> Unit) {
        // Cancel any previous pending callback
        cancelPendingPlaybackCallback()

        pendingPlaybackCallback = callback
        pendingPlaybackPackage = packageName
        pendingPlaybackArmedTime = android.os.SystemClock.elapsedRealtime() + PLAYBACK_CALLBACK_MIN_DELAY_MS
        Log.d(TAG, "Registered playback start callback for $packageName (timeout=${timeoutMs}ms, armed after ${PLAYBACK_CALLBACK_MIN_DELAY_MS}ms)")

        // Schedule timeout fallback
        val timeoutRunnable = Runnable {
            Log.d(TAG, "Playback start callback timed out for $packageName, firing anyway")
            firePendingPlaybackCallback()
        }
        pendingPlaybackTimeoutRunnable = timeoutRunnable
        handler.postDelayed(timeoutRunnable, timeoutMs)
    }

    private fun firePendingPlaybackCallback() {
        val callback = pendingPlaybackCallback ?: return
        pendingPlaybackCallback = null
        pendingPlaybackPackage = null
        pendingPlaybackTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingPlaybackTimeoutRunnable = null
        callback()
    }

    private fun cancelPendingPlaybackCallback() {
        pendingPlaybackCallback = null
        pendingPlaybackPackage = null
        pendingPlaybackTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingPlaybackTimeoutRunnable = null
    }

    /**
     * Arm manual control mode immediately, without pausing anything.
     * Called at the very start of a Music Hub-initiated song transition so that
     * any auto-advance detection (early song-end, metadata change, etc.) that fires
     * during the async availability check is suppressed. Without this, pressing
     * Previous could race with a song-finished broadcast that immediately calls
     * playNext() and undoes the index change.
     */
    fun armManualControl() {
        Log.d(TAG, "armManualControl called - suppressing auto-advance detection during transition")
        manualControlActive = true
        manualControlTimestamp = System.currentTimeMillis()
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
        val packageName: String?,
        val title: String? = null
    )

    /**
     * Arm reactive pause window for same-platform NetEase switches.
     * During this window, any new playback (position ~0) from NetEase will be
     * immediately paused as it's NetEase's auto-advance to its own queue.
     * Also mutes the music stream as a guaranteed fallback since NetEase ignores
     * pause+stop commands. Unmute happens when the target song's playback callback fires.
     * Window is 2500ms — auto-advance happens within ~2s, target song takes 3-8s.
     */
    fun armSamePlatformNetEasePause() {
        samePlatformNetEasePauseUntil = android.os.SystemClock.elapsedRealtime() + 2500L
        Log.d(TAG, "Armed same-platform NetEase reactive pause window (2500ms)")
    }

    /**
     * Lightweight pause for a specific package. Does NOT reset tracking state
     * or set manualControlActive. Used to silence auto-advanced songs during
     * same-platform switches without interfering with pending deep links.
     */
    fun pausePackage(packageName: String) {
        val controller = synchronized(controllersLock) {
            activeControllers[packageName]
        }
        if (controller != null) {
            try {
                controller.transportControls.pause()
                controller.transportControls.stop()
                Log.d(TAG, "Lightweight pause+stop sent to $packageName")
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing $packageName: ${e.message}")
            }
        }
    }

    fun playPackage(packageName: String) {
        val controller = synchronized(controllersLock) {
            activeControllers[packageName]
        }
        if (controller != null) {
            try {
                controller.transportControls.play()
                Log.d(TAG, "Lightweight play sent to $packageName")
            } catch (e: Exception) {
                Log.e(TAG, "Error playing $packageName: ${e.message}")
            }
        }
    }

    /**
     * Check whether an active MediaController exists for the given package.
     * Used to determine if the app is in a warm-start state (player initialized)
     * or cold-start state (needs splash screen).
     */
    fun hasActiveController(packageName: String): Boolean {
        return synchronized(controllersLock) {
            activeControllers.containsKey(packageName)
        }
    }

    /**
     * Get current playback info from any active media session.
     * Prioritizes controllers that are actively playing.
     * Returns null if no active playback.
     */
    fun getPlaybackInfo(): PlaybackInfo? {
        // Snapshot controllers under lock, then iterate outside lock to avoid
        // holding the lock during potentially slow MediaController IPC calls.
        // This prevents ConcurrentModificationException when the broadcast thread
        // calls this while the main thread modifies activeControllers.
        val controllerSnapshot = synchronized(controllersLock) {
            activeControllers.values.toList()
        }

        // Prefer the platform Music Hub last launched so a stale paused controller
        // from another app can't shadow the song the user actually expects to see.
        val target = currentPlatformPackage
        val orderedControllers = if (target != null) {
            controllerSnapshot.sortedByDescending { it.packageName == target }
        } else {
            controllerSnapshot
        }

        // First pass: find a controller that is actively playing
        orderedControllers.forEach { controller ->
            try {
                val state = controller.playbackState
                val metadata = controller.metadata

                if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                    val lastUpdateTime = state.lastPositionUpdateTime
                    val elapsedRealtime = android.os.SystemClock.elapsedRealtime()
                    val timeSinceUpdate = elapsedRealtime - lastUpdateTime
                    val position = state.position + (timeSinceUpdate * state.playbackSpeed).toLong()
                    val duration = metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                    val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)

                    if (duration > 0) {
                        return PlaybackInfo(
                            isPlaying = true,
                            position = position,
                            duration = duration,
                            packageName = controller.packageName,
                            title = title
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting playback info (playing): ${e.message}")
            }
        }

        // Second pass: find any controller with valid metadata (paused state)
        orderedControllers.forEach { controller ->
            try {
                val state = controller.playbackState
                val metadata = controller.metadata

                if (state != null && metadata != null) {
                    val duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
                    if (duration > 0) {
                        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                        return PlaybackInfo(
                            isPlaying = false,
                            position = state.position,
                            duration = duration,
                            packageName = controller.packageName,
                            title = title
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
        val controllerSnapshot = synchronized(controllersLock) {
            activeControllers.values.toList()
        }

        // Prefer the platform Music Hub last launched so resume can't accidentally
        // start a stale paused song in a different music app the user touched manually.
        val target = currentPlatformPackage
        val orderedControllers = if (target != null) {
            controllerSnapshot.sortedByDescending { it.packageName == target }
        } else {
            controllerSnapshot
        }

        // First, try to find a playing controller to pause
        orderedControllers.forEach { controller ->
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
        orderedControllers.forEach { controller ->
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
        orderedControllers.firstOrNull()?.let { controller ->
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
        val controllerSnapshot = synchronized(controllersLock) {
            activeControllers.values.toList()
        }

        // Find the currently active controller (playing or paused)
        controllerSnapshot.forEach { controller ->
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
