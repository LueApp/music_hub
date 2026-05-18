package com.musichub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.musichub.MusicHubApplication
import com.musichub.R
import com.musichub.data.model.Song
import com.musichub.platform.Platforms
import com.musichub.platform.PlatformHandler
import com.musichub.platform.NetEasePlatform
import com.musichub.platform.QQMusicPlatform
import com.musichub.platform.BilibiliPlatform
import com.musichub.platform.KugouPlatform
import com.musichub.remote.RemoteMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Service that manages the playback queue and coordinates with other services.
 * Acts as the central controller for cross-app playback.
 */
class PlaybackService : Service() {

    private val binder = LocalBinder()
    private var queue: MutableList<Song> = mutableListOf()
    @Volatile private var currentIndex: Int = -1
    private var isPlaying: Boolean = false
    private val queueLock = Any()

    // Coroutine scope for async operations (availability checks)
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // Track skipped songs to prevent infinite loops when all songs are unavailable
    private var consecutiveSkips: Int = 0
    private val MAX_CONSECUTIVE_SKIPS = 10

    // Post-launch playback timeout: detect songs that fail to play at runtime
    // Warm start: app already has an active MediaSession (player initialized)
    // Cold start: no MediaSession, app needs splash screen + initialization
    private val PLAYBACK_TIMEOUT_WARM_MS = 5000L
    private val PLAYBACK_TIMEOUT_COLD_MS = 25000L
    private var playbackTimeoutRunnable: Runnable? = null
    private var lastLaunchedSongId: Long = -1L
    private var lastLaunchedPlatform: String? = null
    private var lastTimedOutSongTitle: String? = null
    private var playbackTimeoutRetried: Boolean = false
    private var desyncCheckRunnable: Runnable? = null
    private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Wake lock to keep CPU running while playing
    private var wakeLock: PowerManager.WakeLock? = null

    // Screen wake lock to keep screen on during playback
    @Suppress("DEPRECATION")
    private var screenWakeLock: PowerManager.WakeLock? = null

    // Playback modes
    enum class RepeatMode {
        OFF,        // No repeat - stop at end of queue
        ALL,        // Repeat entire queue
        ONE         // Repeat current song
    }

    private var repeatMode: RepeatMode = RepeatMode.OFF
    private var shuffleEnabled: Boolean = false
    private var shuffledIndices: MutableList<Int> = mutableListOf()
    private var shufflePosition: Int = -1

    // Support multiple listeners for song/queue changes
    private val songChangeListeners = mutableListOf<(Song?) -> Unit>()
    private val queueChangeListeners = mutableListOf<(List<Song>) -> Unit>()
    private var onPlaybackModeChangeListener: ((RepeatMode, Boolean) -> Unit)? = null

    private val songFinishedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MediaMonitorService.ACTION_SONG_FINISHED) {
                Log.d(TAG, "Received song finished broadcast")
                playNext()
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "PlaybackService created")
        createNotificationChannel()

        // Initialize wake lock for keeping CPU active during playback control
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MusicHub::PlaybackWakeLock"
        )

        // Initialize screen wake lock (keeps screen on during playback)
        @Suppress("DEPRECATION")
        screenWakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "MusicHub::ScreenWakeLock"
        )

        // Register for song finished broadcasts
        val filter = IntentFilter(MediaMonitorService.ACTION_SONG_FINISHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(songFinishedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(songFinishedReceiver, filter)
        }

        registerDisplayListener()

        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(launchModePrefListener)
    }

    // Display / config rotation re-applies HyperOS's default freeform bounds
    // to the music-app task, undoing our off-screen positioning. Re-fire the
    // resize a few times across the rotation animation window — HyperOS
    // keeps adjusting bounds for ~1-2 seconds during rotation. Single
    // triggers don't catch this.
    //
    // We hook BOTH the DisplayListener (catches physical device rotation)
    // and onConfigurationChanged (catches app-driven rotation when another
    // app calls setRequestedOrientation). One of those fires for any kind
    // of orientation change.
    //
    // Gated on launch_mode = background because foreground mode wants the
    // music app to be fullscreen and able to use its own landscape activity
    // — firing `am task resize` against a fullscreen task is at best a
    // no-op, and at worst (if the task is freeform from a prior background
    // launch) it yanks the music app off-screen mid-foreground-launch.
    private fun fireRotationTriggerSequence() {
        val mode = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .getString("launch_mode", DeepLinkLauncher.LAUNCH_MODE_BACKGROUND)
        if (mode != DeepLinkLauncher.LAUNCH_MODE_BACKGROUND) {
            Log.d(TAG, "Rotation trigger skipped: launch_mode=$mode")
            return
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val offsets = longArrayOf(0L, 300L, 800L, 1500L, 2500L, 4000L)
        offsets.forEach { off ->
            handler.postDelayed({ ShizukuLauncher.triggerResizeForCurrentTarget() }, off)
        }
    }

    // Pref-change listener: when the user switches launch_mode away from
    // background, drop any in-flight Shizuku tracking state so a stale
    // currentTargetPkg can't fire resizes against a foreground-mode launch.
    // Kept as a member (not local) so the same instance can be removed in
    // onDestroy — anonymous lambdas don't compare equal across calls.
    private val launchModePrefListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "launch_mode") {
                val mode = prefs.getString(key, DeepLinkLauncher.LAUNCH_MODE_BACKGROUND)
                if (mode != DeepLinkLauncher.LAUNCH_MODE_BACKGROUND) {
                    ShizukuLauncher.clearTargetState()
                    Log.d(TAG, "launch_mode changed to $mode; cleared Shizuku target state")
                }
            }
        }

    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            fireRotationTriggerSequence()
        }
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Fires on ANY device-level configuration change — orientation,
        // density, locale, etc. — including rotations triggered by another
        // app's setRequestedOrientation (which the DisplayListener may miss).
        fireRotationTriggerSequence()
    }

    private fun registerDisplayListener() {
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            dm?.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register display listener: ${e.message}")
        }
    }

    private fun unregisterDisplayListener() {
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            dm?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_NEXT -> playNext()
            ACTION_PLAY_PREVIOUS -> playPrevious()
            ACTION_STOP -> stop()
            ACTION_START_FOREGROUND -> startForegroundService()
            ACTION_TOGGLE_SHUFFLE -> toggleShuffle()
            ACTION_TOGGLE_REPEAT -> toggleRepeatMode()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceJob.cancel()
        releaseWakeLock()
        releaseScreenWakeLock()
        unregisterDisplayListener()
        try {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(launchModePrefListener)
        } catch (_: Exception) { }
        try {
            unregisterReceiver(songFinishedReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        Log.d(TAG, "PlaybackService destroyed")
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) {
            // Acquire for 30 minutes max (will be renewed on each song change)
            wakeLock?.acquire(30 * 60 * 1000L)
            Log.d(TAG, "Wake lock acquired")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "Wake lock released")
        }
    }

    /**
     * Acquire screen wake lock to keep screen on during playback.
     */
    private fun acquireScreenWakeLock() {
        if (screenWakeLock?.isHeld == false) {
            // Acquire for 30 minutes max (renewed on each song change)
            screenWakeLock?.acquire(30 * 60 * 1000L)
            Log.d(TAG, "Screen wake lock acquired")
        }
    }

    private fun releaseScreenWakeLock() {
        if (screenWakeLock?.isHeld == true) {
            screenWakeLock?.release()
            Log.d(TAG, "Screen wake lock released")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tutti playback controls"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val currentSong = getCurrentSong()
        val title = currentSong?.title ?: getString(R.string.app_name)
        val artist = currentSong?.artist ?: "Ready to play"

        // Intent to open the app
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Next action
        val nextIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_PLAY_NEXT
        }
        val nextPendingIntent = PendingIntent.getService(
            this, 1, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Previous action
        val prevIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_PLAY_PREVIOUS
        }
        val prevPendingIntent = PendingIntent.getService(
            this, 2, prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_skip_previous, "Previous", prevPendingIntent)
            .addAction(R.drawable.ic_skip_next, "Next", nextPendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    // Queue management

    fun setQueue(songs: List<Song>, startIndex: Int = 0) {
        synchronized(queueLock) {
            queue.clear()
            queue.addAll(songs)
            currentIndex = startIndex
        }
        Log.i(TAG, "Queue set with ${songs.size} songs, starting at index $startIndex")
        notifyQueueChangeListeners(queue.toList())

        // Reset shuffle indices when queue changes
        if (shuffleEnabled) {
            generateShuffleOrder(startIndex)
        }

        if (songs.isNotEmpty()) {
            startForegroundService()
        }
    }

    fun addToQueue(song: Song) {
        synchronized(queueLock) { queue.add(song) }
        Log.i(TAG, "Added to queue: ${song.title}")
        notifyQueueChangeListeners(queue.toList())
    }

    fun clearQueue() {
        cancelPlaybackTimeout()
        synchronized(queueLock) {
            queue.clear()
            currentIndex = -1
        }
        isPlaying = false
        notifyQueueChangeListeners(emptyList())
        notifySongChangeListeners(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun getQueue(): List<Song> = synchronized(queueLock) { queue.toList() }

    fun getCurrentSong(): Song? = synchronized(queueLock) {
        if (currentIndex in queue.indices) queue[currentIndex] else null
    }

    fun getCurrentPlatform(): String? = synchronized(queueLock) {
        if (currentIndex in queue.indices) queue[currentIndex].platform else null
    }

    fun getCurrentIndex(): Int = currentIndex

    fun getRemainingCount(): Int = synchronized(queueLock) {
        if (currentIndex < 0) queue.size else queue.size - currentIndex - 1
    }

    // Playback control

    fun playSong(song: Song) {
        synchronized(queueLock) {
            // Check if song is already in queue
            val existingIndex = queue.indexOfFirst { it.id == song.id }
            if (existingIndex >= 0) {
                currentIndex = existingIndex
            } else {
                queue.add(song)
                currentIndex = queue.size - 1
            }
        }
        launchCurrentSong()
    }

    fun playAtIndex(index: Int) {
        val valid = synchronized(queueLock) {
            if (index in queue.indices) {
                currentIndex = index
                true
            } else false
        }
        if (valid) launchCurrentSong()
    }

    fun moveInQueue(from: Int, to: Int) {
        synchronized(queueLock) {
            if (from !in queue.indices || to !in queue.indices || from == to) return
            val song = queue.removeAt(from)
            queue.add(to, song)

            // Adjust currentIndex to keep tracking the currently playing song
            currentIndex = when {
                currentIndex == from -> to
                from < currentIndex && to >= currentIndex -> currentIndex - 1
                from > currentIndex && to <= currentIndex -> currentIndex + 1
                else -> currentIndex
            }

            Log.d(TAG, "Moved queue item from $from to $to, currentIndex=$currentIndex")
        }
        notifyQueueChangeListeners(queue.toList())
    }

    fun moveInShuffleOrder(from: Int, to: Int) {
        if (!shuffleEnabled || shuffledIndices.isEmpty()) return
        if (from !in shuffledIndices.indices || to !in shuffledIndices.indices || from == to) return

        val idx = shuffledIndices.removeAt(from)
        shuffledIndices.add(to, idx)

        // Adjust shufflePosition to keep tracking the currently playing song
        shufflePosition = when {
            shufflePosition == from -> to
            from < shufflePosition && to >= shufflePosition -> shufflePosition - 1
            from > shufflePosition && to <= shufflePosition -> shufflePosition + 1
            else -> shufflePosition
        }

        Log.d(TAG, "Moved shuffle order from $from to $to, shufflePosition=$shufflePosition")
    }

    /**
     * Remove a song from the queue by its queue index.
     * Returns true if the currently playing song was removed (caller should launch next).
     */
    fun removeFromQueue(queueIndex: Int): Boolean {
        var removedCurrent = false
        synchronized(queueLock) {
            if (queueIndex !in queue.indices) return false
            val removedSong = queue.removeAt(queueIndex)
            Log.d(TAG, "Removed from queue at $queueIndex: ${removedSong.title}, queue size=${queue.size}")

            if (queue.isEmpty()) {
                currentIndex = -1
                isPlaying = false
                removedCurrent = true
            } else if (queueIndex == currentIndex) {
                // Removed the currently playing song — play the song now at this index
                // (which is the next song), or wrap to last if we removed the tail
                if (currentIndex >= queue.size) {
                    currentIndex = queue.size - 1
                }
                removedCurrent = true
            } else if (queueIndex < currentIndex) {
                currentIndex--
            }

            // Update shuffle indices: remove the queue index and adjust remaining
            if (shuffleEnabled && shuffledIndices.isNotEmpty()) {
                val shufflePos = shuffledIndices.indexOf(queueIndex)
                if (shufflePos >= 0) {
                    shuffledIndices.removeAt(shufflePos)
                    if (shufflePosition > shufflePos) shufflePosition--
                    else if (shufflePosition == shufflePos && shufflePosition >= shuffledIndices.size) {
                        shufflePosition = (shuffledIndices.size - 1).coerceAtLeast(0)
                    }
                }
                // Adjust indices that were above the removed index
                for (i in shuffledIndices.indices) {
                    if (shuffledIndices[i] > queueIndex) {
                        shuffledIndices[i] = shuffledIndices[i] - 1
                    }
                }
            }
        }
        notifyQueueChangeListeners(queue.toList())

        if (removedCurrent) {
            if (queue.isEmpty()) {
                showToast("队列已空")
            } else {
                launchCurrentSong()
            }
        }
        return removedCurrent
    }

    fun playNext(): Boolean {
        // Handle repeat one mode - just replay current song
        if (repeatMode == RepeatMode.ONE) {
            launchCurrentSong()
            return true
        }

        // Handle shuffle mode
        if (shuffleEnabled && shuffledIndices.isNotEmpty()) {
            shufflePosition++
            if (shufflePosition >= shuffledIndices.size) {
                // End of shuffled list
                if (repeatMode == RepeatMode.ALL) {
                    // Reshuffle and start over
                    generateShuffleOrder(-1)
                    shufflePosition = 0
                    synchronized(queueLock) { currentIndex = shuffledIndices[shufflePosition] }
                    launchCurrentSong()
                    return true
                } else {
                    Log.i(TAG, "End of shuffled queue reached")
                    isPlaying = false
                    showToast("播放完毕")
                    return false
                }
            }
            synchronized(queueLock) { currentIndex = shuffledIndices[shufflePosition] }
            launchCurrentSong()
            return true
        }

        // Normal sequential playback
        val hasNext = synchronized(queueLock) {
            if (currentIndex < queue.size - 1) {
                currentIndex++
                true
            } else if (repeatMode == RepeatMode.ALL) {
                currentIndex = 0
                true
            } else {
                false
            }
        }

        return if (hasNext) {
            launchCurrentSong()
            true
        } else {
            Log.i(TAG, "End of queue reached")
            isPlaying = false
            showToast("播放完毕")
            false
        }
    }

    fun playPrevious(): Boolean {
        // Handle shuffle mode
        if (shuffleEnabled && shuffledIndices.isNotEmpty()) {
            if (shufflePosition > 0) {
                shufflePosition--
                synchronized(queueLock) { currentIndex = shuffledIndices[shufflePosition] }
                launchCurrentSong()
                return true
            } else if (repeatMode == RepeatMode.ALL) {
                // Go to end of shuffled list
                shufflePosition = shuffledIndices.size - 1
                synchronized(queueLock) { currentIndex = shuffledIndices[shufflePosition] }
                launchCurrentSong()
                return true
            }
            return false
        }

        // Normal sequential playback
        val hasPrev = synchronized(queueLock) {
            if (currentIndex > 0) {
                currentIndex--
                true
            } else if (repeatMode == RepeatMode.ALL) {
                currentIndex = queue.size - 1
                true
            } else {
                false
            }
        }

        return if (hasPrev) {
            launchCurrentSong()
            true
        } else {
            false
        }
    }

    fun stop() {
        cancelPlaybackTimeout()
        isPlaying = false
        releaseWakeLock()
        releaseScreenWakeLock()
        notifySongChangeListeners(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun launchCurrentSong() {
        cancelPlaybackTimeout()
        val song = getCurrentSong() ?: return
        val handler = getHandlerForPlatform(song.platform)

        // Arm manual control mode IMMEDIATELY to suppress any auto-advance detection
        // that could fire during the async availability check below. Without this,
        // a song-finished broadcast (e.g. from early end-detection or metadata change)
        // could race with playPrevious() and call playNext(), undoing the index change
        // and making the Previous button appear to just replay the current song.
        MediaMonitorService.getInstance()?.armManualControl()

        // Pre-emptive pause: if the next song is on a different platform, pause the old
        // platform immediately BEFORE the availability check. This prevents the old app
        // from auto-advancing to its next song during the HTTP request delay.
        val targetPackage = Platforms.PACKAGE_NAMES[song.platform]
        val monitor = MediaMonitorService.getInstance()
        if (monitor != null && targetPackage != null) {
            val currentPlatform = monitor.getCurrentPlatformPackage()
            if (currentPlatform != null && currentPlatform != targetPackage) {
                Log.d(TAG, "Pre-emptive cross-platform pause: $currentPlatform -> $targetPackage")
                monitor.pauseAllMedia(targetPackage)
            }
        }

        // Check song availability asynchronously before launching
        // Skip when in player mode — phones on local hotspot typically have no internet,
        // and the 10s+10s HTTP timeout would block song advancement for up to 20 seconds
        serviceScope.launch {
            val availability = if (RemoteMode.isPlayer()) {
                null // Skip availability check in player mode
            } else {
                try {
                    handler?.checkSongAvailability(song.platformSongId)
                } catch (e: Exception) {
                    Log.w(TAG, "Availability check exception for ${song.title}: ${e.message}")
                    null // Treat exception as "assume available"
                }
            }

            if (availability != null && !availability.isAvailable) {
                // Song is unavailable - skip it
                consecutiveSkips++
                Log.w(TAG, "Song unavailable: ${song.title} (${song.platform}/${song.platformSongId}) - ${availability.reason} [skip $consecutiveSkips/$MAX_CONSECUTIVE_SKIPS]")
                showToast("跳过: ${song.title} (${availability.reason})")
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        MusicHubApplication.getInstance().repository.logSkip(
                            song.title, song.artist, song.platform, song.platformSongId, availability.reason
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to log skip: ${e.message}")
                    }
                }

                if (consecutiveSkips >= MAX_CONSECUTIVE_SKIPS) {
                    Log.w(TAG, "Too many consecutive skips ($consecutiveSkips), stopping playback")
                    consecutiveSkips = 0
                    isPlaying = false
                    showToast("连续多首歌曲不可用，已停止播放")
                    return@launch
                }

                // Auto-skip to next song
                playNext()
                return@launch
            }

            // Song is available (or check failed/skipped) - proceed with launch
            consecutiveSkips = 0
            doLaunchSong(song, handler)
        }
    }

    private fun getHandlerForPlatform(platform: String): PlatformHandler? {
        return when (platform) {
            Platforms.NETEASE -> NetEasePlatform()
            Platforms.QQMUSIC -> QQMusicPlatform()
            Platforms.BILIBILI -> BilibiliPlatform()
            Platforms.KUGOU -> KugouPlatform()
            else -> null
        }
    }

    private fun doLaunchSong(song: Song, handler: PlatformHandler?) {
        isPlaying = true

        // Acquire wake lock to keep CPU active for playback control
        acquireWakeLock()

        // Keep screen on so user can see lyrics/player
        acquireScreenWakeLock()

        // Determine the target platform's package name
        val targetPackage = Platforms.PACKAGE_NAMES[song.platform]

        // Tell MediaMonitorService which platform is now active so it only
        // triggers song-end detection from the correct controller
        MediaMonitorService.getInstance()?.setCurrentPlatform(targetPackage)

        // Apply per-song custom end-of-song timeout (e.g. to skip long talks).
        MediaMonitorService.getInstance()?.setCurrentSongCustomDuration(song.customDurationMs)

        // Pause all currently playing media before launching the new song
        // Pass the target package so we know if this is a same-platform or cross-platform switch
        // For same-platform switches, we skip repeated pause attempts to avoid pausing the new song
        MediaMonitorService.getInstance()?.pauseAllMedia(targetPackage)

        val fallbackUrl = handler?.generateFallbackUrl(song.platformSongId) ?: ""

        // Determine delay based on platform switching
        // Use lastLaunchedPlatform (the actually played song) instead of queue position,
        // because queue order may not match play order (e.g., shuffle, skips)
        val isPlatformSwitch = lastLaunchedPlatform != null && lastLaunchedPlatform != song.platform
        val launchDelay = if (isPlatformSwitch) 300L else 100L  // Longer delay for platform switch

        Log.d(TAG, "Launching song: ${song.title} (platform=${song.platform}, isPlatformSwitch=$isPlatformSwitch, delay=${launchDelay}ms)")

        // For same-platform NetEase switches, detect if we need a deep link re-send.
        // NetEase auto-advances to its own next song ~1.5s after the current song ends.
        // Early song-end detection fires ~1.5s before the end, so we send the deep link
        // once immediately and once after 2s to override NetEase's auto-advance.
        // Detect same-platform NetEase switch for double-send logic
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val physicalRotation = windowManager?.defaultDisplay?.rotation
        val physicalLandscape = physicalRotation == android.view.Surface.ROTATION_90 || physicalRotation == android.view.Surface.ROTATION_270
        val isLandscapeForDoubleSend = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            physicalLandscape || DeepLinkLauncher.landscapeWorkaroundActive
        val isSamePlatformNetEase = !isPlatformSwitch && song.platform == Platforms.NETEASE
            && lastLaunchedPlatform == Platforms.NETEASE
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        // Check if this is a cold start (no active controller = app not running)
        val isColdStart = targetPackage != null &&
            MediaMonitorService.getInstance()?.hasActiveController(targetPackage) != true

        // Delay to ensure pause takes effect before launching new app
        mainHandler.postDelayed({
            // Launch the song in the native app
            DeepLinkLauncher.launch(this, song.deepLink, fallbackUrl)

            // Notify MediaMonitorService that we've started a new song
            // This re-enables auto-advance detection after a delay
            MediaMonitorService.getInstance()?.onNewSongStarted()

            // Same-platform NetEase: pause the auto-advanced "third song" that
            // NetEase's fresh PlayerActivity audibly plays for ~44ms before
            // processing the deep link's onNewIntent. Empirically the leak
            // persists in landscape too — CLEAR_TASK destroys the activity but
            // NOT NetEase's persisted internal queue (the new PlayerActivity
            // reads it on creation). The scheduled pausePackage() calls are
            // direct pause+stop on NetEase's MediaSession; they don't touch
            // samePlatformNetEasePauseUntil or pendingPlaybackCallback, so they
            // can't short-circuit restoreAutoRotation. Safe to run in both
            // portrait and landscape.
            if (isSamePlatformNetEase) {
                val pauseDelays = listOf(500L, 700L, 900L, 1100L, 1300L, 1500L)
                pauseDelays.forEach { delay ->
                    mainHandler.postDelayed({
                        Log.d(TAG, "Pausing NetEase auto-advance at ${delay}ms (landscape=$isLandscapeForDoubleSend)")
                        MediaMonitorService.getInstance()?.pausePackage("com.netease.cloudmusic")
                    }, delay)
                }
            }

            // Reactive pause window — portrait only.
            // In landscape the 2500ms window can match the target song's first
            // STATE_PLAYING (position ~0) and the early `return` in
            // MediaMonitorService.onPlaybackStateChanged skips
            // firePendingPlaybackCallback → restoreAutoRotation, leaving the
            // device locked in portrait and NetEase loading PlayerActivity
            // instead of PlayerLandscapeActivity.
            if (isSamePlatformNetEase && !isLandscapeForDoubleSend) {
                Log.d(TAG, "Arming reactive pause for NetEase same-platform switch (portrait)")
                MediaMonitorService.getInstance()?.armSamePlatformNetEasePause()
            }

            // Portrait only: re-send deep link after 2s to override NetEase's
            // internal auto-advance. In landscape mode, skip re-send to avoid
            // breaking PlayerLandscapeActivity (CLEAR_TASK handles it instead).
            if (isSamePlatformNetEase && !isLandscapeForDoubleSend) {
                Log.d(TAG, "Scheduling deep link re-send for same-platform NetEase switch (portrait)")
                mainHandler.postDelayed({
                    Log.d(TAG, "Re-sending deep link to override NetEase auto-advance: ${song.title}")
                    DeepLinkLauncher.launch(this, song.deepLink, fallbackUrl, skipAutoRotate = true)
                }, 2000L)
            }

            // Cold start re-send: when QQ Music was not running, the splash screen may
            // consume the deep link without processing it. Re-send after the app has
            // had time to fully initialize. Only re-send if playback hasn't started.
            // Only for QQ Music — NetEase landscape workaround takes ~7-10s and a
            // re-send at 5s would open a portrait PlayerActivity, breaking landscape.
            if (isColdStart && song.platform == Platforms.QQMUSIC) {
                Log.d(TAG, "Cold start detected, scheduling deep link re-send for ${song.title}")
                mainHandler.postDelayed({
                    if (song.id != lastLaunchedSongId) return@postDelayed
                    val info = MediaMonitorService.getInstance()?.getPlaybackInfo()
                    if (info?.isPlaying != true) {
                        Log.d(TAG, "Cold start re-send: playback not started, re-sending deep link for ${song.title}")
                        DeepLinkLauncher.launch(this, song.deepLink, fallbackUrl, skipAutoRotate = true)
                    }
                }, 5000L)
            }
        }, launchDelay)

        // Update notification and notify listeners
        updateNotification()
        notifySongChangeListeners(song)

        // Show toast with remaining count
        val remaining = getRemainingCount()
        val message = if (remaining > 0) {
            "正在播放: ${song.title} (还有${remaining}首)"
        } else {
            "正在播放: ${song.title} (最后一首)"
        }
        showToast(message)

        // Update floating window if active
        FloatingWindowService.updateCurrentSong(this, song)

        // Schedule playback timeout to detect runtime failures
        schedulePlaybackTimeout(song, isPlatformSwitch)

        Log.i(TAG, "Now playing: ${song.title} by ${song.artist} (${song.platform})")
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // Listeners

    fun setOnSongChangeListener(listener: (Song?) -> Unit) {
        songChangeListeners.add(listener)
    }

    fun removeOnSongChangeListener(listener: (Song?) -> Unit) {
        songChangeListeners.remove(listener)
    }

    private fun notifySongChangeListeners(song: Song?) {
        songChangeListeners.forEach { it.invoke(song) }
    }

    fun setOnQueueChangeListener(listener: (List<Song>) -> Unit) {
        queueChangeListeners.add(listener)
    }

    fun removeOnQueueChangeListener(listener: (List<Song>) -> Unit) {
        queueChangeListeners.remove(listener)
    }

    private fun notifyQueueChangeListeners(queue: List<Song>) {
        queueChangeListeners.forEach { it.invoke(queue) }
    }

    fun setOnPlaybackModeChangeListener(listener: (RepeatMode, Boolean) -> Unit) {
        onPlaybackModeChangeListener = listener
    }

    // Playback mode controls

    fun getRepeatMode(): RepeatMode = repeatMode

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        Log.i(TAG, "Repeat mode set to: $mode")
        onPlaybackModeChangeListener?.invoke(repeatMode, shuffleEnabled)

        val message = when (mode) {
            RepeatMode.OFF -> "顺序播放"
            RepeatMode.ALL -> "列表循环"
            RepeatMode.ONE -> "单曲循环"
        }
        showToast(message)
    }

    fun toggleRepeatMode() {
        val newMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        setRepeatMode(newMode)
    }

    fun isShuffleEnabled(): Boolean = shuffleEnabled

    fun getShuffleOrder(): List<Int>? {
        return if (shuffleEnabled && shuffledIndices.isNotEmpty()) {
            shuffledIndices.toList()
        } else {
            null
        }
    }

    fun setShuffleEnabled(enabled: Boolean) {
        shuffleEnabled = enabled
        Log.i(TAG, "Shuffle mode set to: $enabled")

        if (enabled && queue.isNotEmpty()) {
            generateShuffleOrder(currentIndex)
        } else {
            shuffledIndices.clear()
            shufflePosition = -1
        }

        onPlaybackModeChangeListener?.invoke(repeatMode, shuffleEnabled)
        showToast(if (enabled) "随机播放" else "顺序播放")
    }

    fun toggleShuffle() {
        setShuffleEnabled(!shuffleEnabled)
    }

    private fun generateShuffleOrder(currentIdx: Int) {
        shuffledIndices.clear()
        val indices = queue.indices.toMutableList()

        // If we have a current song, put it first
        if (currentIdx >= 0 && currentIdx < queue.size) {
            indices.remove(currentIdx)
            shuffledIndices.add(currentIdx)
            shufflePosition = 0
        } else {
            shufflePosition = -1
        }

        // Shuffle the remaining indices
        indices.shuffle()
        shuffledIndices.addAll(indices)

        Log.d(TAG, "Generated shuffle order: $shuffledIndices")
    }

    // --- Playback timeout detection ---

    private fun cancelPlaybackTimeout() {
        playbackTimeoutRunnable?.let {
            timeoutHandler.removeCallbacks(it)
            playbackTimeoutRunnable = null
        }
        cancelDesyncRecovery()
    }

    private fun schedulePlaybackTimeout(song: Song, isPlatformSwitch: Boolean = false) {
        // Don't schedule for controller mode (MediaMonitorService runs on player phone)
        if (RemoteMode.isController()) {
            return
        }

        cancelPlaybackTimeout()
        lastLaunchedSongId = song.id
        lastLaunchedPlatform = song.platform
        playbackTimeoutRetried = false

        val runnable = Runnable {
            // Verify this timeout is still for the current song
            if (song.id != lastLaunchedSongId) {
                Log.d(TAG, "Playback timeout fired for stale song ${song.title}, ignoring")
                return@Runnable
            }

            val playbackInfo = MediaMonitorService.getInstance()?.getPlaybackInfo()
            // For NetEase and Kugou, skip title verification — both cycle lyric
            // lines and credits through the MediaSession title metadata, making
            // title matching unreliable. For QQ Music, verify song identity to
            // detect desync.
            val titleCyclesLyrics = song.platform == Platforms.NETEASE
                || song.platform == Platforms.KUGOU
            if (playbackInfo?.isPlaying == true) {
                val actualTitle = playbackInfo.title
                val titleMismatch = !titleCyclesLyrics && actualTitle != null && !titleMatches(song.title, actualTitle)

                if (titleMismatch) {
                    Log.w(TAG, "Playback timeout: wrong song playing! Expected '${song.title}', actual '$actualTitle' - treating as timeout")
                } else {
                    // Song is playing (correct or lyric-cycling platform) — reset consecutive skips
                    Log.d(TAG, "Playback timeout check: ${song.title} is playing, all good" +
                        if (titleCyclesLyrics && actualTitle != null) " (${song.platform} title: '$actualTitle', skipping verification)" else "")
                    consecutiveSkips = 0
                    lastTimedOutSongTitle = null
                    return@Runnable
                }
            }

            // If song is paused but has made progress on the CORRECT platform,
            // the user paused it manually — don't skip it.
            // Must verify package matches to avoid reading stale state from a different platform's controller.
            val expectedPackage = Platforms.PACKAGE_NAMES[song.platform]
            if (playbackInfo != null && !playbackInfo.isPlaying && playbackInfo.position > 1000L
                && expectedPackage != null && playbackInfo.packageName == expectedPackage) {
                Log.d(TAG, "Playback timeout check: ${song.title} is paused at ${playbackInfo.position}ms on ${playbackInfo.packageName} — user paused, not a timeout")
                consecutiveSkips = 0
                lastTimedOutSongTitle = null
                return@Runnable
            }

            // If the correct song is loaded on the correct platform but stuck at position=0
            // (e.g., audio focus issue, transient buffering), try a play nudge before giving up.
            if (!playbackTimeoutRetried && expectedPackage != null && playbackInfo != null
                && playbackInfo.packageName == expectedPackage && playbackInfo.position < 1000L) {
                val actualTitle = playbackInfo.title
                val titleOk = titleCyclesLyrics || actualTitle == null || titleMatches(song.title, actualTitle)
                if (titleOk) {
                    Log.d(TAG, "Playback timeout: ${song.title} loaded but not playing (pos=${playbackInfo.position}ms), sending play nudge and retrying")
                    playbackTimeoutRetried = true
                    MediaMonitorService.getInstance()?.playPackage(expectedPackage)
                    // Re-schedule a shorter retry timeout
                    val retryRunnable = playbackTimeoutRunnable
                    if (retryRunnable != null) {
                        timeoutHandler.postDelayed(retryRunnable, 3000L)
                    }
                    return@Runnable
                }
            }

            // Song failed to start playing (or wrong song is playing)
            Log.w(TAG, "Playback timeout: ${song.title} by ${song.artist} (${song.platform}/${song.platformSongId}) - skipping [skip ${consecutiveSkips + 1}/$MAX_CONSECUTIVE_SKIPS]")
            consecutiveSkips++
            lastTimedOutSongTitle = song.title

            if (consecutiveSkips >= MAX_CONSECUTIVE_SKIPS) {
                showToast("连续多首歌曲不可用，已停止播放")
                stop()
                return@Runnable
            }

            showToast("跳过: ${song.title} (播放超时)")
            serviceScope.launch(Dispatchers.IO) {
                try {
                    MusicHubApplication.getInstance().repository.logSkip(
                        song.title, song.artist, song.platform, song.platformSongId, "播放超时"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to log skip: ${e.message}")
                }
            }

            // Schedule desync recovery to catch the timed-out song if it starts playing late
            scheduleDesyncRecovery()

            // For QQ Music, dismiss the error dialog before skipping to next song
            if (song.platform == Platforms.QQMUSIC) {
                PlayerAccessibilityService.dismissQQMusicDialog()
                // Delay to let dialog dismissal + verification complete before launching next song
                // (dismissErrorDialog has a 300ms verification check, so wait 800ms total)
                timeoutHandler.postDelayed({ playNext() }, 800L)
            } else {
                playNext()
            }
        }

        playbackTimeoutRunnable = runnable

        // Determine timeout duration based on context:
        // 1. Landscape workaround + NetEase → cold (CLEAR_TASK restarts app)
        // 2. Cross-platform switch → cold (pause/stop + new deep link needs extra time)
        // 3. Bilibili with an active controller → cold timeout (the app can keep
        //    a MediaSession alive while video-page deep links still load slowly)
        // 4. Has active controller → warm
        // 5. Otherwise → cold
        val targetPackage = Platforms.PACKAGE_NAMES[song.platform]
        val hasController = targetPackage != null &&
            MediaMonitorService.getInstance()?.hasActiveController(targetPackage) == true

        // Check both the flag AND physical orientation — the flag may have been reset
        // by the rotation-restore callback before this timeout is scheduled.
        // Use WindowManager rotation for physical orientation (config orientation may
        // reflect a portrait app's window rather than how the user holds the device).
        val timeoutWindowManager = getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val timeoutRotation = timeoutWindowManager?.defaultDisplay?.rotation
        val isDeviceLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            timeoutRotation == android.view.Surface.ROTATION_90 || timeoutRotation == android.view.Surface.ROTATION_270
        val isLandscapeWorkaround = (DeepLinkLauncher.landscapeWorkaroundActive || isDeviceLandscape) && song.platform == Platforms.NETEASE

        val (timeoutMs, reason) = when {
            isLandscapeWorkaround -> PLAYBACK_TIMEOUT_COLD_MS to "cold start (landscape workaround)"
            isPlatformSwitch -> PLAYBACK_TIMEOUT_COLD_MS to "cold start (cross-platform)"
            song.platform == Platforms.BILIBILI && hasController -> PLAYBACK_TIMEOUT_COLD_MS to "extended start (bilibili)"
            hasController -> PLAYBACK_TIMEOUT_WARM_MS to "warm start"
            else -> PLAYBACK_TIMEOUT_COLD_MS to "cold start"
        }

        timeoutHandler.postDelayed(runnable, timeoutMs)
        Log.d(TAG, "Scheduled playback timeout for ${song.title} (${timeoutMs}ms, $reason)")
    }

    private fun titleMatches(expected: String, actual: String): Boolean {
        return actual.contains(expected, ignoreCase = true) ||
            expected.contains(actual, ignoreCase = true)
    }

    /**
     * Schedule desync recovery checks after a timeout skip.
     * If the timed-out song starts playing late, pause it and re-launch the correct song.
     * Checks at 3s and 6s after the skip to catch late-starting songs.
     */
    private fun scheduleDesyncRecovery() {
        cancelDesyncRecovery()
        val checkDelays = listOf(3000L, 6000L)
        val currentSongAtSkip = getCurrentSong() ?: return

        desyncCheckRunnable = Runnable {
            val timedOutTitle = lastTimedOutSongTitle ?: return@Runnable
            val playbackInfo = MediaMonitorService.getInstance()?.getPlaybackInfo()
            val actualTitle = playbackInfo?.title

            if (playbackInfo?.isPlaying == true && actualTitle != null && titleMatches(timedOutTitle, actualTitle)) {
                Log.w(TAG, "Desync recovery: timed-out song '$timedOutTitle' started playing late, pausing and re-launching '${currentSongAtSkip.title}'")
                // Pause the wrong song
                MediaMonitorService.getInstance()?.pauseAllMedia(
                    Platforms.PACKAGE_NAMES[currentSongAtSkip.platform] ?: ""
                )
                lastTimedOutSongTitle = null
                // Re-launch the correct song's deep link
                val handler = getHandlerForPlatform(currentSongAtSkip.platform)
                val fallbackUrl = handler?.generateFallbackUrl(currentSongAtSkip.platformSongId) ?: ""
                timeoutHandler.postDelayed({
                    DeepLinkLauncher.launch(this, currentSongAtSkip.deepLink, fallbackUrl)
                    MediaMonitorService.getInstance()?.onNewSongStarted()
                }, 500L)
            }
        }

        for (delay in checkDelays) {
            timeoutHandler.postDelayed(desyncCheckRunnable!!, delay)
        }
    }

    private fun cancelDesyncRecovery() {
        desyncCheckRunnable?.let {
            timeoutHandler.removeCallbacks(it)
            desyncCheckRunnable = null
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "playback_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_NEXT = "com.musichub.action.PLAY_NEXT"
        const val ACTION_PLAY_PREVIOUS = "com.musichub.action.PLAY_PREVIOUS"
        const val ACTION_STOP = "com.musichub.action.STOP"
        const val ACTION_START_FOREGROUND = "com.musichub.action.START_FOREGROUND"
        const val ACTION_TOGGLE_SHUFFLE = "com.musichub.action.TOGGLE_SHUFFLE"
        const val ACTION_TOGGLE_REPEAT = "com.musichub.action.TOGGLE_REPEAT"

        @Volatile
        private var instance: PlaybackService? = null

        fun getInstance(): PlaybackService? = instance

        fun startService(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_START_FOREGROUND
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
