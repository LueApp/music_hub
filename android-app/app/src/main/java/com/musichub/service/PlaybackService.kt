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
import com.musichub.R
import com.musichub.data.model.Song
import com.musichub.platform.Platforms
import com.musichub.platform.PlatformHandler
import com.musichub.platform.NetEasePlatform
import com.musichub.platform.QQMusicPlatform
import com.musichub.platform.BilibiliPlatform
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
    private val PLAYBACK_TIMEOUT_MS = 15000L
    private var playbackTimeoutRunnable: Runnable? = null
    private var lastLaunchedSongId: Long = -1L
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
                description = "Music Hub playback controls"
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
        val title = currentSong?.title ?: "Music Hub"
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

        // Pause all currently playing media before launching the new song
        // Pass the target package so we know if this is a same-platform or cross-platform switch
        // For same-platform switches, we skip repeated pause attempts to avoid pausing the new song
        MediaMonitorService.getInstance()?.pauseAllMedia(targetPackage)

        val fallbackUrl = handler?.generateFallbackUrl(song.platformSongId) ?: ""

        // Determine delay based on platform switching
        // Longer delay when switching between different platforms to ensure pause takes effect
        val previousSong = synchronized(queueLock) {
            if (currentIndex > 0 && currentIndex - 1 < queue.size) {
                queue.getOrNull(currentIndex - 1)
            } else null
        }

        val isPlatformSwitch = previousSong != null && previousSong.platform != song.platform
        val launchDelay = if (isPlatformSwitch) 300L else 100L  // Longer delay for platform switch

        Log.d(TAG, "Launching song: ${song.title} (platform=${song.platform}, isPlatformSwitch=$isPlatformSwitch, delay=${launchDelay}ms)")

        // Delay to ensure pause takes effect before launching new app
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // Launch the song in the native app
            DeepLinkLauncher.launch(this, song.deepLink, fallbackUrl)

            // Notify MediaMonitorService that we've started a new song
            // This re-enables auto-advance detection after a delay
            MediaMonitorService.getInstance()?.onNewSongStarted()
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
        schedulePlaybackTimeout(song)

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
    }

    private fun schedulePlaybackTimeout(song: Song) {
        // Don't schedule for controller mode (MediaMonitorService runs on player phone)
        if (RemoteMode.isController()) {
            return
        }

        cancelPlaybackTimeout()
        lastLaunchedSongId = song.id

        val runnable = Runnable {
            // Verify this timeout is still for the current song
            if (song.id != lastLaunchedSongId) {
                Log.d(TAG, "Playback timeout fired for stale song ${song.title}, ignoring")
                return@Runnable
            }

            val playbackInfo = MediaMonitorService.getInstance()?.getPlaybackInfo()
            if (playbackInfo?.isPlaying == true) {
                // Song is playing fine — reset consecutive skips
                Log.d(TAG, "Playback timeout check: ${song.title} is playing, all good")
                consecutiveSkips = 0
                return@Runnable
            }

            // Song failed to start playing
            Log.w(TAG, "Playback timeout: ${song.title} by ${song.artist} (${song.platform}/${song.platformSongId}) - skipping [skip ${consecutiveSkips + 1}/$MAX_CONSECUTIVE_SKIPS]")
            consecutiveSkips++

            if (consecutiveSkips >= MAX_CONSECUTIVE_SKIPS) {
                showToast("连续多首歌曲不可用，已停止播放")
                stop()
                return@Runnable
            }

            showToast("跳过: ${song.title} (播放超时)")

            // For QQ Music, dismiss the error dialog before skipping to next song
            if (song.platform == Platforms.QQMUSIC) {
                PlayerAccessibilityService.dismissQQMusicDialog()
                // Delay to let dialog close animation complete before launching next song
                timeoutHandler.postDelayed({ playNext() }, 500L)
            } else {
                playNext()
            }
        }

        playbackTimeoutRunnable = runnable
        timeoutHandler.postDelayed(runnable, PLAYBACK_TIMEOUT_MS)
        Log.d(TAG, "Scheduled playback timeout for ${song.title} (${PLAYBACK_TIMEOUT_MS}ms)")
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
