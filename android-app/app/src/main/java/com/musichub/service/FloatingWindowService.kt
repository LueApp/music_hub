package com.musichub.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.Toast
import coil.load
import com.google.android.material.card.MaterialCardView
import com.musichub.R
import com.musichub.data.model.Song
import com.musichub.platform.LinkParser
import com.musichub.remote.RemoteClient
import com.musichub.remote.RemoteMode
import com.musichub.remote.RemoteState
import com.musichub.remote.toSong
import com.musichub.ui.MainActivity
import com.musichub.ui.adapter.QueueAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Service that displays a floating window overlay for playback control.
 * This window stays on top of other apps and allows users to control playback.
 * Supports two modes: full control panel and minimized ball.
 */
class FloatingWindowService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var miniBallView: View? = null
    private var isRunning = false
    private var isMiniMode = false

    private var currentSongTitle = ""
    private var currentArtist = ""
    private var currentCoverUrl = ""
    private var remainingCount = 0
    private var isQueueVisible = false
    private var isVolumeVisible = false
    private var queueAdapter: QueueAdapter? = null
    private var queueRecyclerView: RecyclerView? = null

    // Progress bar update handler
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressUpdateInterval = 500L  // Update every 500ms
    private var isProgressUpdating = false
    private var isUserSeeking = false  // Track if user is dragging seekbar
    private var seekCooldownUntil = 0L  // Ignore remote position updates until this time
    private var modeCooldownUntil = 0L  // Ignore remote mode updates until this time
    private var isUserAdjustingVolume = false  // Track if user is dragging volume slider
    private var volumeCooldownUntil = 0L  // Ignore remote volume updates until this time
    private var audioManager: AudioManager? = null

    // Store window positions for both modes
    private var fullModeParams: WindowManager.LayoutParams? = null
    private var miniModeParams: WindowManager.LayoutParams? = null

    // Mini ball rotation animation
    private var coverRotationAnimator: ObjectAnimator? = null
    private var currentRotatingView: View? = null

    // Coroutine scope for remote operations
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // GestureDetector for double-click detection on mini ball
    private var gestureDetector: GestureDetector? = null

    // Remote state listener for controller mode
    private val remoteStateListener: (RemoteState) -> Unit = { state ->
        // Update current song info from remote state
        val oldTitle = currentSongTitle
        state.currentSong?.let { song ->
            currentSongTitle = (song.title as String?) ?: ""
            currentArtist = (song.artist as String?) ?: ""
            currentCoverUrl = (song.coverUrl as String?) ?: ""
        }
        // When the song changes, refresh the queue display on the main thread
        if (currentSongTitle != oldTitle) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (isMiniMode) {
                    updateMiniBall()
                } else {
                    updateFloatingWindow()
                }
                if (isQueueVisible) {
                    updateQueueData()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "FloatingWindowService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called, action=${intent?.action}")
        when (intent?.action) {
            ACTION_SHOW -> {
                Log.d(TAG, "ACTION_SHOW received")
                currentSongTitle = intent.getStringExtra(EXTRA_SONG_TITLE) ?: ""
                currentArtist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
                currentCoverUrl = intent.getStringExtra(EXTRA_COVER_URL) ?: ""
                remainingCount = intent.getIntExtra(EXTRA_REMAINING_COUNT, 0)
                showFloatingWindow()
            }
            ACTION_UPDATE -> {
                Log.d(TAG, "ACTION_UPDATE received")
                currentSongTitle = intent.getStringExtra(EXTRA_SONG_TITLE) ?: currentSongTitle
                currentArtist = intent.getStringExtra(EXTRA_ARTIST) ?: currentArtist
                currentCoverUrl = intent.getStringExtra(EXTRA_COVER_URL) ?: currentCoverUrl
                remainingCount = intent.getIntExtra(EXTRA_REMAINING_COUNT, remainingCount)
                updateFloatingWindow()
            }
            ACTION_HIDE -> {
                Log.d(TAG, "ACTION_HIDE received")
                hideFloatingWindow()
                stopSelf()
            }
            else -> {
                Log.d(TAG, "Unknown action or null, showing window by default")
                showFloatingWindow()
            }
        }
        return START_STICKY
    }

    private fun showFloatingWindow() {
        Log.d(TAG, "showFloatingWindow called, isRunning=$isRunning")

        if (isRunning) {
            Log.d(TAG, "Already running, updating window")
            updateFloatingWindow()
            return
        }

        // Start as foreground service
        Log.d(TAG, "Starting foreground service...")
        startForegroundService()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Log.d(TAG, "WindowManager obtained: $windowManager")

        // Inflate floating view with themed context
        val themedContext = ContextThemeWrapper(this, R.style.Theme_MusicHub)
        val inflater = LayoutInflater.from(themedContext)
        floatingView = inflater.inflate(R.layout.floating_window, null)
        Log.d(TAG, "Floating view inflated: $floatingView")

        // Set up window parameters
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 100
        }
        Log.d(TAG, "WindowManager.LayoutParams created with type=${params.type}")

        // Add view to window
        try {
            windowManager?.addView(floatingView, params)
            isRunning = true
            Log.d(TAG, "Floating window added to WindowManager successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating window to WindowManager", e)
            return
        }

        // Set up UI
        setupFloatingView()

        // Sync with current playback state if available
        syncWithPlaybackService()

        updateFloatingWindow()

        // Enable dragging
        setupDragListener(params)

        Log.d(TAG, "Floating window shown")
    }

    private fun setupFloatingView() {
        floatingView?.apply {
            // Shuffle button
            findViewById<ImageButton>(R.id.btnShuffle)?.setOnClickListener {
                Log.d(TAG, "Shuffle button clicked")
                if (RemoteMode.isController()) {
                    RemoteClient.toggleShuffle()
                    modeCooldownUntil = System.currentTimeMillis() + 2000
                } else {
                    sendPlaybackCommand(PlaybackService.ACTION_TOGGLE_SHUFFLE)
                    // Immediately toggle icon for visual feedback
                    val btn = it as ImageButton
                    btn.alpha = if (btn.alpha < 1.0f) 1.0f else 0.5f
                }
            }

            // Previous button
            findViewById<ImageButton>(R.id.btnPrevious)?.setOnClickListener {
                Log.d(TAG, "Previous button clicked")
                if (RemoteMode.isController()) {
                    RemoteClient.playPrevious()
                } else {
                    sendPlaybackCommand(PlaybackService.ACTION_PLAY_PREVIOUS)
                }
            }

            // Play/Pause button
            findViewById<ImageButton>(R.id.btnPlayPause)?.setOnClickListener {
                Log.d(TAG, "Play/Pause button clicked")
                if (RemoteMode.isController()) {
                    RemoteClient.togglePlayPause()
                } else {
                    MediaMonitorService.getInstance()?.togglePlayPause()
                }
                // Update icon after a brief delay to let the state change
                progressHandler.postDelayed({ updatePlayPauseIcon() }, 100)
            }

            // Next button
            findViewById<ImageButton>(R.id.btnNext)?.setOnClickListener {
                Log.d(TAG, "Next button clicked")
                if (RemoteMode.isController()) {
                    RemoteClient.playNext()
                } else {
                    sendPlaybackCommand(PlaybackService.ACTION_PLAY_NEXT)
                }
            }

            // Repeat button
            findViewById<ImageButton>(R.id.btnRepeat)?.setOnClickListener {
                Log.d(TAG, "Repeat button clicked")
                if (RemoteMode.isController()) {
                    RemoteClient.toggleRepeat()
                    modeCooldownUntil = System.currentTimeMillis() + 2000
                } else {
                    sendPlaybackCommand(PlaybackService.ACTION_TOGGLE_REPEAT)
                }
            }

            // Volume toggle button
            findViewById<ImageButton>(R.id.btnVolume)?.setOnClickListener {
                Log.d(TAG, "Volume button clicked")
                toggleVolumePanel()
            }

            // Queue button
            findViewById<ImageButton>(R.id.btnQueue)?.setOnClickListener {
                Log.d(TAG, "Queue button clicked")
                toggleQueueView()
            }

            // Setup queue RecyclerView
            setupQueueView()

            // Setup progress bar (SeekBar is display-only, no seeking for external apps)
            setupProgressBar()

            // Setup volume panel
            setupVolumePanel()

            // Close button
            findViewById<ImageButton>(R.id.btnClose)?.setOnClickListener {
                Log.d(TAG, "Close button clicked")
                hideFloatingWindow()
                stopSelf()
            }

            // Minimize button - switch to mini ball mode
            findViewById<ImageButton>(R.id.btnMinimize)?.setOnClickListener {
                Log.d(TAG, "Minimize button clicked")
                switchToMiniMode()
            }
        }
    }

    private fun sendPlaybackCommand(action: String) {
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // Double-tap detection state
    private var lastTapIndex = -1
    private var lastTapTime = 0L
    private val doubleTapTimeout = 300L

    private fun setupQueueView() {
        floatingView?.apply {
            queueRecyclerView = findViewById<RecyclerView>(R.id.rvQueue)
            val recyclerView = queueRecyclerView ?: return@apply
            queueAdapter = QueueAdapter(
                onItemClick = { index ->
                    val now = System.currentTimeMillis()
                    if (index == lastTapIndex && now - lastTapTime < doubleTapTimeout) {
                        // Double-tap detected - play this song
                        Log.d(TAG, "Queue item double-tapped: $index")
                        lastTapIndex = -1
                        lastTapTime = 0L
                        if (RemoteMode.isController()) {
                            RemoteClient.playAtIndex(index)
                        } else {
                            PlaybackService.getInstance()?.playAtIndex(index)
                        }
                    } else {
                        // First tap - show visual feedback
                        lastTapIndex = index
                        lastTapTime = now
                        // Flash highlight on tapped item
                        val vh = recyclerView.findViewHolderForAdapterPosition(
                            queueAdapter?.getDisplayPosition(index) ?: return@QueueAdapter
                        )
                        vh?.itemView?.let { view ->
                            val originalBg = view.background
                            view.setBackgroundColor(getColor(R.color.primary) and 0x40FFFFFF)
                            view.postDelayed({
                                view.background = originalBg
                            }, doubleTapTimeout)
                        }
                    }
                }
            )
            recyclerView.layoutManager = LinearLayoutManager(this@FloatingWindowService)
            recyclerView.adapter = queueAdapter

            // Attach drag-to-reorder
            var dragStartIndex = -1  // Track original position when drag starts
            val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT
            ) {
                override fun isLongPressDragEnabled(): Boolean = true

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val fromPos = viewHolder.bindingAdapterPosition
                    val toPos = target.bindingAdapterPosition
                    val adapter = queueAdapter ?: return false

                    // Only update adapter visually during drag — defer queue update to clearView
                    adapter.moveItem(fromPos, toPos)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val adapterPos = viewHolder.bindingAdapterPosition
                    val adapter = queueAdapter ?: return
                    val queueIndex = adapter.getQueueIndex(adapterPos)

                    Log.d(TAG, "Swiped to remove queue item: adapterPos=$adapterPos, queueIndex=$queueIndex")

                    // Visual feedback first
                    adapter.removeItem(adapterPos)

                    // Then update the actual queue
                    if (RemoteMode.isController()) {
                        RemoteClient.removeFromQueue(queueIndex)
                    } else {
                        PlaybackService.getInstance()?.removeFromQueue(queueIndex)
                    }
                }

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        viewHolder?.itemView?.elevation = 8f
                        val pos = viewHolder?.bindingAdapterPosition ?: -1
                        val inShuffle = queueAdapter?.isShuffleMode() == true
                        // In shuffle mode, record adapter position (= shuffle position)
                        // In normal mode, record the actual queue index
                        dragStartIndex = if (inShuffle) pos else (queueAdapter?.getQueueIndex(pos) ?: -1)
                    }
                }

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.elevation = 0f

                    val endPos = viewHolder.bindingAdapterPosition
                    val inShuffle = queueAdapter?.isShuffleMode() == true
                    if (inShuffle) {
                        // Shuffle mode: reorder the shuffle order
                        if (dragStartIndex >= 0 && endPos >= 0 && dragStartIndex != endPos) {
                            Log.d(TAG, "Shuffle order drag: $dragStartIndex -> $endPos")
                            if (RemoteMode.isController()) {
                                RemoteClient.moveInShuffleOrder(dragStartIndex, endPos)
                            } else {
                                PlaybackService.getInstance()?.moveInShuffleOrder(dragStartIndex, endPos)
                            }
                        }
                    } else {
                        // Normal mode: reorder the actual queue
                        val endIndex = queueAdapter?.getQueueIndex(endPos) ?: -1
                        if (dragStartIndex >= 0 && endIndex >= 0 && dragStartIndex != endIndex) {
                            Log.d(TAG, "Queue drag: $dragStartIndex -> $endIndex")
                            if (RemoteMode.isController()) {
                                RemoteClient.moveInQueue(dragStartIndex, endIndex)
                            } else {
                                PlaybackService.getInstance()?.moveInQueue(dragStartIndex, endIndex)
                            }
                        }
                    }
                    dragStartIndex = -1
                }
            })
            touchHelper.attachToRecyclerView(recyclerView)

            // Initial update
            updateQueueData()
        }
    }

    private fun setupProgressBar() {
        floatingView?.apply {
            val seekBar = findViewById<SeekBar>(R.id.seekBarProgress) ?: return@apply

            // Enable seeking in external apps via MediaController or remote
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                private var seekPosition: Long = 0

                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val duration = if (RemoteMode.isController()) {
                            RemoteClient.currentState?.duration ?: 0
                        } else {
                            MediaMonitorService.getInstance()?.getPlaybackInfo()?.duration ?: 0
                        }
                        if (duration > 0) {
                            seekPosition = (duration * progress / 100)
                            findViewById<TextView>(R.id.tvCurrentTime)?.text = formatTime(seekPosition)
                        }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isUserSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isUserSeeking = false
                    if (seekPosition > 0) {
                        Log.d(TAG, "User seeked to position: $seekPosition ms")
                        if (RemoteMode.isController()) {
                            RemoteClient.seekTo(seekPosition)
                            // Ignore remote position updates for 2s to prevent snap-back
                            seekCooldownUntil = System.currentTimeMillis() + 2000
                        } else {
                            MediaMonitorService.getInstance()?.seekTo(seekPosition)
                        }
                    }
                }
            })
        }

        // Start progress updates
        startProgressUpdates()
    }

    private fun setupVolumePanel() {
        floatingView?.apply {
            val seekBarVolume = findViewById<SeekBar>(R.id.seekBarVolume) ?: return@apply

            // Initialize max and current volume
            if (RemoteMode.isController()) {
                val state = RemoteClient.currentState
                if (state != null && state.maxVolume > 0) {
                    seekBarVolume.max = state.maxVolume
                    seekBarVolume.progress = state.volume.coerceIn(0, state.maxVolume)
                }
            } else {
                val am = audioManager ?: return@apply
                seekBarVolume.max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                seekBarVolume.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            updateVolumeButtonIcon()

            seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        if (RemoteMode.isController()) {
                            RemoteClient.setVolume(progress)
                            volumeCooldownUntil = System.currentTimeMillis() + 2000
                        } else {
                            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                        }
                        updateVolumeButtonIcon()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isUserAdjustingVolume = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isUserAdjustingVolume = false
                }
            })

            // Volume down/up buttons inside the panel
            findViewById<ImageButton>(R.id.btnVolumeDown)?.setOnClickListener {
                adjustVolume(-1)
            }
            findViewById<ImageButton>(R.id.btnVolumeUp)?.setOnClickListener {
                adjustVolume(1)
            }
        }
    }

    private fun toggleVolumePanel() {
        floatingView?.apply {
            val volumeContainer = findViewById<LinearLayout>(R.id.volumeContainer) ?: return@apply
            val volumeButton = findViewById<ImageButton>(R.id.btnVolume)

            isVolumeVisible = !isVolumeVisible
            Log.d(TAG, "Volume panel visibility toggled: $isVolumeVisible")
            if (isVolumeVisible) {
                // Sync slider before showing
                updateVolumeSlider()
                volumeContainer.visibility = View.VISIBLE
                volumeButton?.alpha = 1.0f
            } else {
                volumeContainer.visibility = View.GONE
                volumeButton?.alpha = 0.7f
            }

            // Update window layout
            windowManager?.updateViewLayout(floatingView, floatingView?.layoutParams)
        }
    }

    private fun adjustVolume(delta: Int) {
        if (RemoteMode.isController()) {
            val state = RemoteClient.currentState ?: return
            val newLevel = (state.volume + delta).coerceIn(0, state.maxVolume)
            RemoteClient.setVolume(newLevel)
            volumeCooldownUntil = System.currentTimeMillis() + 2000
            floatingView?.findViewById<SeekBar>(R.id.seekBarVolume)?.progress = newLevel
        } else {
            val am = audioManager ?: return
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val newLevel = (current + delta).coerceIn(0, maxVol)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, newLevel, 0)
            floatingView?.findViewById<SeekBar>(R.id.seekBarVolume)?.progress = newLevel
        }
        updateVolumeButtonIcon()
    }

    private fun updateVolumeButtonIcon() {
        floatingView?.apply {
            val btn = findViewById<ImageButton>(R.id.btnVolume) ?: return@apply
            val volume = findViewById<SeekBar>(R.id.seekBarVolume)?.progress ?: 0
            when {
                volume == 0 -> btn.setImageResource(R.drawable.ic_volume_off)
                volume <= (findViewById<SeekBar>(R.id.seekBarVolume)?.max ?: 15) / 2 ->
                    btn.setImageResource(R.drawable.ic_volume_down)
                else -> btn.setImageResource(R.drawable.ic_volume_up)
            }
        }
    }

    private fun updateVolumeSlider() {
        if (isUserAdjustingVolume) return
        floatingView?.apply {
            val seekBarVolume = findViewById<SeekBar>(R.id.seekBarVolume) ?: return@apply
            if (RemoteMode.isController()) {
                if (System.currentTimeMillis() < volumeCooldownUntil) return@apply
                val state = RemoteClient.currentState ?: return@apply
                if (state.maxVolume > 0) {
                    seekBarVolume.max = state.maxVolume
                    seekBarVolume.progress = state.volume.coerceIn(0, state.maxVolume)
                }
            } else {
                val am = audioManager ?: return@apply
                seekBarVolume.max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                seekBarVolume.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            updateVolumeButtonIcon()
        }
    }

    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (!isMiniMode && isVolumeVisible) updateVolumeSlider()
            if (isProgressUpdating) {
                progressHandler.postDelayed(this, progressUpdateInterval)
            }
        }
    }

    private fun startProgressUpdates() {
        if (!isProgressUpdating) {
            isProgressUpdating = true
            progressHandler.post(progressUpdateRunnable)
            Log.d(TAG, "Started progress updates")
        }
    }

    private fun stopProgressUpdates() {
        isProgressUpdating = false
        progressHandler.removeCallbacks(progressUpdateRunnable)
        Log.d(TAG, "Stopped progress updates")
    }

    private fun updateProgress() {
        // In controller mode, use remote state
        if (RemoteMode.isController()) {
            updateProgressFromRemote()
            return
        }

        val playbackInfo = MediaMonitorService.getInstance()?.getPlaybackInfo()

        // Update mini ball progress if in mini mode
        if (isMiniMode) {
            updateMiniBallProgress()
            // Also update rotation state based on playback
            val isPlaying = playbackInfo?.isPlaying == true
            val coverView = miniBallView?.findViewById<ImageView>(R.id.ivBallCover)
            if (isPlaying) {
                startCoverRotation(coverView)
            } else {
                stopCoverRotation()
            }
            updateMiniBallPlayIndicator()
            return
        }

        floatingView?.apply {
            val seekBar = findViewById<SeekBar>(R.id.seekBarProgress)
            val tvCurrentTime = findViewById<TextView>(R.id.tvCurrentTime)
            val tvTotalTime = findViewById<TextView>(R.id.tvTotalTime)

            if (playbackInfo != null && playbackInfo.duration > 0) {
                // Update time labels
                tvCurrentTime?.text = formatTime(playbackInfo.position)
                tvTotalTime?.text = formatTime(playbackInfo.duration)

                // Update seekbar (only if user is not dragging)
                if (!isUserSeeking) {
                    val progress = ((playbackInfo.position.toFloat() / playbackInfo.duration.toFloat()) * 100).toInt()
                    seekBar?.progress = progress.coerceIn(0, 100)
                }

                // Update play/pause icon
                updatePlayPauseIcon(playbackInfo.isPlaying)
            } else {
                // No playback info - show default state
                tvCurrentTime?.text = "0:00"
                tvTotalTime?.text = "0:00"
                if (!isUserSeeking) {
                    seekBar?.progress = 0
                }
            }
        }
    }

    /**
     * Update progress and UI from remote state (controller mode).
     */
    private fun updateProgressFromRemote() {
        val state = RemoteClient.currentState

        if (isMiniMode) {
            val isPlaying = state?.isPlaying == true
            val coverView = miniBallView?.findViewById<ImageView>(R.id.ivBallCover)
            if (isPlaying) {
                startCoverRotation(coverView)
            } else {
                stopCoverRotation()
            }

            // Update mini ball progress ring
            if (state != null && state.duration > 0) {
                val progress = ((state.position.toFloat() / state.duration) * 100).toInt()
                miniBallView?.findViewById<ProgressBar>(R.id.progressRing)?.progress = progress
            }

            // Update play indicator
            miniBallView?.apply {
                val playIndicator = findViewById<ImageView>(R.id.ivPlayIndicator)
                if (state?.isPlaying == true) {
                    playIndicator?.setImageResource(R.drawable.ic_pause_circle)
                } else {
                    playIndicator?.setImageResource(R.drawable.ic_play_circle)
                }
            }
            return
        }

        floatingView?.apply {
            val seekBar = findViewById<SeekBar>(R.id.seekBarProgress)
            val tvCurrentTime = findViewById<TextView>(R.id.tvCurrentTime)
            val tvTotalTime = findViewById<TextView>(R.id.tvTotalTime)

            if (state != null && state.duration > 0) {
                val inCooldown = System.currentTimeMillis() < seekCooldownUntil

                if (!inCooldown) {
                    tvCurrentTime?.text = formatTime(state.position)
                }
                tvTotalTime?.text = formatTime(state.duration)

                if (!isUserSeeking && !inCooldown) {
                    val progress = ((state.position.toFloat() / state.duration.toFloat()) * 100).toInt()
                    seekBar?.progress = progress.coerceIn(0, 100)
                }

                updatePlayPauseIcon(state.isPlaying)

                // Update mode icons from remote state (skip during cooldown)
                if (System.currentTimeMillis() >= modeCooldownUntil) {
                    val repeatMode = when (state.repeatMode) {
                        "ALL" -> PlaybackService.RepeatMode.ALL
                        "ONE" -> PlaybackService.RepeatMode.ONE
                        else -> PlaybackService.RepeatMode.OFF
                    }
                    updateModeIcons(repeatMode, state.shuffleEnabled)
                }
            } else {
                tvCurrentTime?.text = "0:00"
                tvTotalTime?.text = "0:00"
                if (!isUserSeeking) {
                    seekBar?.progress = 0
                }
            }

            // Update song info if changed
            if (state?.currentSong != null) {
                val song = state.currentSong
                if (song.title != (findViewById<TextView>(R.id.tvSongTitle)?.text ?: "")) {
                    findViewById<TextView>(R.id.tvSongTitle)?.text = song.title
                    findViewById<TextView>(R.id.tvArtist)?.text = song.artist

                    val coverUrl = (song.coverUrl as String?) ?: ""
                    val coverView = findViewById<ImageView>(R.id.ivAlbumCover)
                    if (coverUrl.isNotEmpty()) {
                        coverView?.load(coverUrl) {
                            placeholder(R.drawable.ic_album)
                            error(R.drawable.ic_album)
                            allowHardware(false)
                        }
                    } else {
                        coverView?.setImageResource(R.drawable.ic_album)
                    }
                }
            }
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean? = null) {
        val playing = if (RemoteMode.isController()) {
            isPlaying ?: (RemoteClient.currentState?.isPlaying == true)
        } else {
            isPlaying ?: (MediaMonitorService.getInstance()?.getPlaybackInfo()?.isPlaying == true)
        }

        floatingView?.apply {
            val btnPlayPause = findViewById<ImageButton>(R.id.btnPlayPause)
            if (playing) {
                btnPlayPause?.setImageResource(R.drawable.ic_pause_circle)
            } else {
                btnPlayPause?.setImageResource(R.drawable.ic_play_circle)
            }
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    private fun toggleQueueView() {
        floatingView?.apply {
            val queueContainer = findViewById<LinearLayout>(R.id.queueContainer) ?: return@apply
            val queueButton = findViewById<ImageButton>(R.id.btnQueue)

            isQueueVisible = !isQueueVisible
            Log.d(TAG, "Queue visibility toggled: $isQueueVisible")
            if (isQueueVisible) {
                queueContainer.visibility = View.VISIBLE
                queueButton?.alpha = 1.0f
                updateQueueData()
            } else {
                queueContainer.visibility = View.GONE
                queueButton?.alpha = 0.7f
            }

            // Update window layout
            windowManager?.updateViewLayout(floatingView, floatingView?.layoutParams)
        }
    }

    private fun updateQueueData() {
        if (RemoteMode.isController()) {
            updateQueueDataFromRemote()
            return
        }
        val playbackService = PlaybackService.getInstance()
        if (playbackService == null) {
            Log.w(TAG, "PlaybackService is null, cannot update queue")
            return
        }
        val queue = playbackService.getQueue()
        val currentIndex = playbackService.getCurrentIndex()
        val shuffleOrder = playbackService.getShuffleOrder()

        Log.d(TAG, "Updating queue data: ${queue.size} songs, currentIndex=$currentIndex")

        // Update header text
        floatingView?.findViewById<TextView>(R.id.tvQueueHeader)?.text =
            if (shuffleOrder != null) {
                "播放队列 (随机模式)"
            } else {
                "播放队列 (${queue.size}首)"
            }

        queueAdapter?.updateData(queue, currentIndex, shuffleOrder)

        // Auto-scroll to current song position
        scrollToCurrentSong(currentIndex, shuffleOrder)
    }

    private fun updateQueueDataFromRemote() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val remoteSongs = RemoteClient.fetchQueue()
                val songs = remoteSongs.map { it.toSong() }
                val state = RemoteClient.currentState
                val currentIndex = state?.currentIndex ?: -1
                val shuffleOrder = state?.shuffleOrder

                launch(Dispatchers.Main) {
                    floatingView?.findViewById<TextView>(R.id.tvQueueHeader)?.text =
                        if (shuffleOrder != null) {
                            "播放队列 (随机模式)"
                        } else {
                            "播放队列 (${songs.size}首)"
                        }
                    queueAdapter?.updateData(songs, currentIndex, shuffleOrder)
                    scrollToCurrentSong(currentIndex, shuffleOrder)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch remote queue: ${e.message}")
            }
        }
    }

    /**
     * Scroll the queue RecyclerView to show the current playing song.
     */
    private fun scrollToCurrentSong(currentIndex: Int, shuffleOrder: List<Int>?) {
        if (currentIndex < 0) return

        // Find the display position of the current song
        val displayPosition = if (shuffleOrder != null) {
            shuffleOrder.indexOf(currentIndex)
        } else {
            currentIndex
        }

        if (displayPosition >= 0) {
            queueRecyclerView?.post {
                // Scroll to position with some offset to center it
                (queueRecyclerView?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                    displayPosition,
                    queueRecyclerView?.height?.div(3) ?: 0
                )
            }
        }
    }

    private fun syncWithPlaybackService() {
        // In controller mode, sync via RemoteClient
        if (RemoteMode.isController()) {
            RemoteClient.addStateListener(remoteStateListener)
            val state = RemoteClient.currentState
            if (state?.currentSong != null) {
                currentSongTitle = state.currentSong.title
                currentArtist = state.currentSong.artist
                currentCoverUrl = state.currentSong.coverUrl
            }
            return
        }

        val playbackService = PlaybackService.getInstance()
        if (playbackService != null) {
            val currentSong = playbackService.getCurrentSong()
            if (currentSong != null) {
                Log.d(TAG, "Syncing with current song: ${currentSong.title}")
                currentSongTitle = currentSong.title
                currentArtist = currentSong.artist
                currentCoverUrl = currentSong.coverUrl ?: ""
                remainingCount = playbackService.getRemainingCount()
            } else {
                Log.w(TAG, "PlaybackService has no current song")
            }

            // Sync playback mode states
            updateModeIcons(playbackService.getRepeatMode(), playbackService.isShuffleEnabled())

            // Register for mode changes
            playbackService.setOnPlaybackModeChangeListener { repeatMode, shuffleEnabled ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    updateModeIcons(repeatMode, shuffleEnabled)
                    updateQueueData()
                }
            }

            // Register for queue changes to update queue view
            playbackService.setOnQueueChangeListener { _ ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Log.d(TAG, "Queue changed, updating queue data")
                    updateQueueData()
                }
            }

            // Register for song changes to update queue highlighting and mini ball
            playbackService.setOnSongChangeListener { song ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Log.d(TAG, "Song changed: ${song?.title}")
                    // Update current song info
                    if (song != null) {
                        currentSongTitle = song.title
                        currentArtist = song.artist
                        currentCoverUrl = song.coverUrl ?: ""
                        remainingCount = playbackService.getRemainingCount()
                    }
                    // Update UI based on current mode
                    if (isMiniMode) {
                        updateMiniBall()
                    } else {
                        updateFloatingWindow()
                    }
                    updateQueueData()
                }
            }

            // Initial queue update (in case queue was already set)
            updateQueueData()
        } else {
            Log.w(TAG, "PlaybackService not available for sync")
        }
    }

    private fun updateModeIcons(repeatMode: PlaybackService.RepeatMode, shuffleEnabled: Boolean) {
        floatingView?.apply {
            // Update shuffle button
            val btnShuffle = findViewById<ImageButton>(R.id.btnShuffle)
            btnShuffle?.alpha = if (shuffleEnabled) 1.0f else 0.5f

            // Update repeat button icon and alpha based on mode
            val btnRepeat = findViewById<ImageButton>(R.id.btnRepeat)
            when (repeatMode) {
                PlaybackService.RepeatMode.OFF -> {
                    btnRepeat?.setImageResource(R.drawable.ic_repeat)
                    btnRepeat?.alpha = 0.5f
                }
                PlaybackService.RepeatMode.ALL -> {
                    btnRepeat?.setImageResource(R.drawable.ic_repeat)
                    btnRepeat?.alpha = 1.0f
                }
                PlaybackService.RepeatMode.ONE -> {
                    btnRepeat?.setImageResource(R.drawable.ic_repeat_one)
                    btnRepeat?.alpha = 1.0f
                }
            }
        }
        Log.d(TAG, "Mode icons updated: repeat=$repeatMode, shuffle=$shuffleEnabled")
    }

    private fun setupDragListener(params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingView?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun updateFloatingWindow() {
        floatingView?.apply {
            findViewById<TextView>(R.id.tvSongTitle)?.text =
                if (currentSongTitle.isNotEmpty()) currentSongTitle else "Music Hub"
            findViewById<TextView>(R.id.tvArtist)?.text =
                if (currentArtist.isNotEmpty()) currentArtist else "暂无播放"

            // Update cover image
            val coverView = findViewById<ImageView>(R.id.ivAlbumCover)
            if (currentCoverUrl.isNotEmpty()) {
                coverView?.load(currentCoverUrl) {
                    placeholder(R.drawable.ic_album)
                    error(R.drawable.ic_album)
                    allowHardware(false) // Overlay windows use software rendering
                }
            } else {
                coverView?.setImageResource(R.drawable.ic_album)
            }

            // Update queue if visible
            if (isQueueVisible) {
                updateQueueData()
            }
        }
    }

    private fun hideFloatingWindow() {
        stopProgressUpdates()
        if (floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floating view: ${e.message}")
            }
            floatingView = null
        }
        if (miniBallView != null) {
            try {
                windowManager?.removeView(miniBallView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing mini ball view: ${e.message}")
            }
            miniBallView = null
        }
        isRunning = false
        isMiniMode = false
        Log.d(TAG, "Floating window hidden")
    }

    /**
     * Switch to mini ball mode - show a small floating ball instead of the full control panel.
     */
    private fun switchToMiniMode() {
        if (isMiniMode) return
        Log.d(TAG, "Switching to mini mode")

        // Save current full mode position
        fullModeParams = floatingView?.layoutParams as? WindowManager.LayoutParams

        // Remove full mode view
        if (floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floating view: ${e.message}")
            }
            floatingView = null
        }

        // Create mini ball view
        val themedContext = ContextThemeWrapper(this, R.style.Theme_MusicHub)
        val inflater = LayoutInflater.from(themedContext)
        miniBallView = inflater.inflate(R.layout.floating_ball, null)

        // Set up mini ball window parameters with explicit size (56dp)
        val ballSizePx = (56 * resources.displayMetrics.density).toInt()
        miniModeParams = WindowManager.LayoutParams(
            ballSizePx,
            ballSizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            // Use saved position or default
            x = fullModeParams?.x ?: 20
            y = fullModeParams?.y ?: 100
        }

        // Add mini ball to window
        try {
            windowManager?.addView(miniBallView, miniModeParams)
            isMiniMode = true
            Log.d(TAG, "Mini ball added to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add mini ball to WindowManager", e)
            // Fallback to full mode
            showFloatingWindow()
            return
        }

        // Setup mini ball UI
        setupMiniBallView()

        // Sync with PlaybackService to get current song
        syncWithPlaybackService()

        updateMiniBall()

        // Start progress updates for mini ball
        startProgressUpdates()
    }

    /**
     * Switch back to full control panel mode.
     */
    private fun switchToFullMode() {
        if (!isMiniMode) return
        Log.d(TAG, "Switching to full mode")

        // Save mini mode position
        val savedX = miniModeParams?.x ?: 20
        val savedY = miniModeParams?.y ?: 100

        // Stop and clean up rotation animator
        coverRotationAnimator?.cancel()
        coverRotationAnimator = null
        currentRotatingView = null

        // Remove mini ball view
        if (miniBallView != null) {
            try {
                windowManager?.removeView(miniBallView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing mini ball view: ${e.message}")
            }
            miniBallView = null
        }

        isMiniMode = false

        // Recreate full floating view
        val themedContext = ContextThemeWrapper(this, R.style.Theme_MusicHub)
        val inflater = LayoutInflater.from(themedContext)
        floatingView = inflater.inflate(R.layout.floating_window, null)

        // Set up window parameters with saved position
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = savedX
            y = savedY
        }
        fullModeParams = params

        // Add view to window
        try {
            windowManager?.addView(floatingView, params)
            Log.d(TAG, "Full floating window restored")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore floating window", e)
            return
        }

        // Set up UI
        setupFloatingView()
        syncWithPlaybackService()
        updateFloatingWindow()
        setupDragListener(params)
    }

    private fun setupMiniBallView() {
        // Initialize GestureDetector for double-click
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                Log.d(TAG, "Mini ball double-clicked")
                handleDoubleClick()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                Log.d(TAG, "Mini ball single-clicked, expanding to full mode")
                switchToFullMode()
                return true
            }
        })

        miniBallView?.apply {
            // Long press to toggle play/pause
            setOnLongClickListener {
                Log.d(TAG, "Mini ball long pressed, toggling play/pause")
                if (RemoteMode.isController()) {
                    RemoteClient.togglePlayPause()
                } else {
                    MediaMonitorService.getInstance()?.togglePlayPause()
                }
                // Update play indicator after brief delay
                progressHandler.postDelayed({ updateMiniBallPlayIndicator() }, 100)
                true
            }
        }

        // Setup drag listener for mini ball
        setupMiniBallDragListener()
    }

    private fun setupMiniBallDragListener() {
        val params = miniModeParams ?: return

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val clickThreshold = 10 // pixels

        miniBallView?.setOnTouchListener { view, event ->
            gestureDetector?.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()

                    // Check if this is a drag (moved more than threshold)
                    if (kotlin.math.abs(deltaX) > clickThreshold || kotlin.math.abs(deltaY) > clickThreshold) {
                        isDragging = true
                    }

                    if (isDragging) {
                        params.x = initialX - deltaX
                        params.y = initialY + deltaY
                        windowManager?.updateViewLayout(miniBallView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateMiniBall() {
        miniBallView?.apply {
            // Update cover image
            val coverView = findViewById<ImageView>(R.id.ivBallCover)
            Log.d(TAG, "updateMiniBall: coverUrl='$currentCoverUrl', coverView=$coverView")
            if (currentCoverUrl.isNotEmpty()) {
                coverView?.load(currentCoverUrl) {
                    placeholder(R.drawable.ic_music_note)
                    error(R.drawable.ic_music_note)
                    allowHardware(false) // Overlay windows use software rendering
                    listener(
                        onSuccess = { _, _ -> Log.d(TAG, "Cover image loaded successfully") },
                        onError = { _, result -> Log.e(TAG, "Cover image load failed: ${result.throwable}") }
                    )
                }
            } else {
                coverView?.setImageResource(R.drawable.ic_music_note)
            }

            // Update play indicator and rotation animation
            updateMiniBallPlayIndicator()

            // Start/update rotation animation based on playback state
            val isPlaying = if (RemoteMode.isController()) {
                RemoteClient.currentState?.isPlaying == true
            } else {
                MediaMonitorService.getInstance()?.getPlaybackInfo()?.isPlaying == true
            }
            if (isPlaying) {
                startCoverRotation(coverView)
            } else {
                stopCoverRotation()
            }

            // Update progress ring
            updateMiniBallProgress()
        }
    }

    /**
     * Start the cover rotation animation.
     */
    private fun startCoverRotation(view: View?) {
        if (view == null) {
            Log.d(TAG, "startCoverRotation: view is null")
            return
        }

        // If animating the same view, just resume if paused
        if (currentRotatingView == view && coverRotationAnimator != null) {
            if (coverRotationAnimator?.isPaused == true) {
                Log.d(TAG, "Resuming rotation animation")
                coverRotationAnimator?.resume()
                return
            }
            if (coverRotationAnimator?.isRunning == true) {
                Log.d(TAG, "Rotation already running")
                return
            }
        }

        // Cancel any existing animator
        coverRotationAnimator?.cancel()

        // Create new animator for this view
        currentRotatingView = view
        coverRotationAnimator = ObjectAnimator.ofFloat(view, "rotation", view.rotation, view.rotation + 360f).apply {
            duration = 8000 // 8 seconds per rotation
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            start()
        }
        Log.d(TAG, "Started new rotation animation")
    }

    /**
     * Stop the cover rotation animation (keeps current position).
     */
    private fun stopCoverRotation() {
        if (coverRotationAnimator?.isRunning == true || coverRotationAnimator?.isPaused == true) {
            Log.d(TAG, "Pausing rotation animation")
            coverRotationAnimator?.pause()
        }
    }

    /**
     * Update the progress ring on mini ball.
     */
    private fun updateMiniBallProgress() {
        val position: Long
        val duration: Long
        if (RemoteMode.isController()) {
            val state = RemoteClient.currentState
            position = state?.position ?: 0
            duration = state?.duration ?: 0
        } else {
            val playbackInfo = MediaMonitorService.getInstance()?.getPlaybackInfo()
            position = playbackInfo?.position ?: 0
            duration = playbackInfo?.duration ?: 0
        }
        if (duration > 0) {
            val progress = ((position.toFloat() / duration) * 100).toInt()
            miniBallView?.findViewById<ProgressBar>(R.id.progressRing)?.progress = progress
        }
    }

    private fun updateMiniBallPlayIndicator() {
        val isPlaying = if (RemoteMode.isController()) {
            RemoteClient.currentState?.isPlaying == true
        } else {
            MediaMonitorService.getInstance()?.getPlaybackInfo()?.isPlaying == true
        }
        miniBallView?.apply {
            val playIndicator = findViewById<ImageView>(R.id.ivPlayIndicator)
            if (isPlaying) {
                playIndicator?.setImageResource(R.drawable.ic_pause_circle)
            } else {
                playIndicator?.setImageResource(R.drawable.ic_play_circle)
            }
        }
    }

    private fun startForegroundService() {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Music Hub")
            .setContentText("播放控制已启动")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Window Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows floating playback controls"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        RemoteClient.removeStateListener(remoteStateListener)
        hideFloatingWindow()
        Log.d(TAG, "FloatingWindowService destroyed")
    }

    /**
     * Returns the screen-coordinate Rect of the currently visible overlay
     * (mini ball if active, full panel otherwise) so callers can anchor
     * other windows to the same position. Null if no overlay is showing or
     * its size hasn't been measured yet.
     *
     * Both mini and full modes use `gravity = TOP|END`, so [LayoutParams.x]
     * is an offset from the *right* edge and [LayoutParams.y] is from the
     * top. We translate that to absolute screen coordinates here.
     */
    fun getOverlayScreenBounds(): android.graphics.Rect? {
        val params: WindowManager.LayoutParams
        val viewWidth: Int
        val viewHeight: Int
        if (isMiniMode) {
            params = miniModeParams ?: return null
            val view = miniBallView ?: return null
            viewWidth = if (view.width > 0) view.width else params.width
            viewHeight = if (view.height > 0) view.height else params.height
        } else {
            params = fullModeParams ?: return null
            val view = floatingView ?: return null
            viewWidth = if (view.width > 0) view.width else 0
            viewHeight = if (view.height > 0) view.height else 0
        }
        if (viewWidth <= 0 || viewHeight <= 0) return null

        val screenW = resources.displayMetrics.widthPixels
        val gravity = params.gravity
        val isEndAnchored = (gravity and Gravity.END) != 0
        val left = if (isEndAnchored) screenW - params.x - viewWidth else params.x
        val top = params.y
        return android.graphics.Rect(left, top, left + viewWidth, top + viewHeight)
    }

    private fun getForegroundAppPackage(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val currentTime = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            currentTime - 5000,
            currentTime
        )
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun openUsageStatsSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    private fun handleDoubleClick() {
        // In CONTROLLER mode, playback happens on the remote phone, so launching
        // the local platform app would be meaningless. Open Music Hub instead.
        if (RemoteMode.isController()) {
            launchMusicHub()
            return
        }

        if (!hasUsageStatsPermission()) {
            Log.w(TAG, "Usage stats permission not granted, double-click navigation disabled")
            return
        }

        val foregroundApp = getForegroundAppPackage()
        val currentPlatform = PlaybackService.getInstance()?.getCurrentPlatform()

        Log.d(TAG, "Double-click: foregroundApp=$foregroundApp, currentPlatform=$currentPlatform")

        when {
            foregroundApp == packageName && currentPlatform != null -> {
                launchPlatformApp(currentPlatform)
            }
            foregroundApp == getPlatformPackage(currentPlatform) -> {
                launchMusicHub()
            }
            currentPlatform != null -> {
                launchPlatformApp(currentPlatform)
            }
        }
    }

    private fun launchMusicHub() {
        val intent = Intent(this, com.musichub.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        Log.d(TAG, "Launched Music Hub")
    }

    private fun launchPlatformApp(platform: String?) {
        val targetPackage = getPlatformPackage(platform)
        if (targetPackage == null) {
            Log.w(TAG, "Unknown platform: $platform")
            return
        }

        // Bring the existing platform app to foreground via its launch intent.
        // This avoids re-launching the deep link which would restart the activity
        // and lose the current orientation / state.
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(launchIntent)
            Log.d(TAG, "Brought platform app to foreground: $targetPackage")
        } else {
            Log.w(TAG, "No launch intent for package: $targetPackage")
        }
    }

    private fun getPlatformPackage(platform: String?): String? {
        return when (platform) {
            "netease" -> "com.netease.cloudmusic"
            "qqmusic" -> "com.tencent.qqmusic"
            "bilibili" -> "tv.danmaku.bili"
            else -> null
        }
    }

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val CHANNEL_ID = "floating_window_channel"
        private const val NOTIFICATION_ID = 1002

        @Volatile
        private var instance: FloatingWindowService? = null

        fun getInstance(): FloatingWindowService? = instance

        const val ACTION_SHOW = "com.musichub.action.SHOW_FLOATING"
        const val ACTION_UPDATE = "com.musichub.action.UPDATE_FLOATING"
        const val ACTION_HIDE = "com.musichub.action.HIDE_FLOATING"

        const val EXTRA_SONG_TITLE = "song_title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_COVER_URL = "cover_url"
        const val EXTRA_REMAINING_COUNT = "remaining_count"

        fun show(context: Context, songTitle: String, artist: String, remainingCount: Int) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_SONG_TITLE, songTitle)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_REMAINING_COUNT, remainingCount)
            }
            context.startForegroundService(intent)
        }

        fun update(context: Context, songTitle: String, artist: String, remainingCount: Int) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_SONG_TITLE, songTitle)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_REMAINING_COUNT, remainingCount)
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }

        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_SHOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            hide(context)
        }

        fun updateCurrentSong(context: Context, song: Song) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_SONG_TITLE, song.title)
                putExtra(EXTRA_ARTIST, song.artist)
                putExtra(EXTRA_COVER_URL, song.coverUrl)
            }
            context.startService(intent)
        }
    }
}
