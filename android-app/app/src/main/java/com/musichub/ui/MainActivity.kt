package com.musichub.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import coil.load
import com.musichub.R
import com.musichub.data.model.Song
import com.musichub.databinding.ActivityMainBinding
import com.musichub.platform.LinkParser
import com.musichub.remote.RemoteClient
import com.musichub.remote.RemoteMode
import com.musichub.remote.RemoteState
import com.musichub.service.FloatingWindowService
import com.musichub.service.PlaybackService
import com.musichub.service.ShareReceiver

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    private var playbackService: PlaybackService? = null
    private var serviceBound = false

    // Remote state listener for controller mode
    private val remoteStateListener: (RemoteState) -> Unit = { state ->
        runOnUiThread {
            val song = state.currentSong
            if (song != null) {
                binding.nowPlayingBar.visibility = View.VISIBLE
                binding.tvNowPlayingTitle.text = song.title
                binding.tvNowPlayingArtist.text = song.artist

                val coverUrl = (song.coverUrl as String?) ?: ""
                if (coverUrl.isNotEmpty()) {
                    binding.ivNowPlayingCover.load(coverUrl) {
                        placeholder(R.drawable.ic_album)
                        error(R.drawable.ic_album)
                    }
                } else {
                    binding.ivNowPlayingCover.setImageResource(R.drawable.ic_album)
                }
            } else {
                binding.nowPlayingBar.visibility = View.GONE
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            serviceBound = true

            // Set up song change listener
            playbackService?.setOnSongChangeListener { song ->
                runOnUiThread {
                    updateNowPlayingBar(song)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        setupNowPlayingBar()

        if (RemoteMode.isController()) {
            // In controller mode, listen to remote state updates instead of binding local service
            RemoteClient.addStateListener(remoteStateListener)
        } else {
            bindPlaybackService()
        }

        // Handle share intent
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        binding.bottomNavigation.setupWithNavController(navController)
    }

    private fun setupNowPlayingBar() {
        binding.btnNowPlayingPrev.setOnClickListener {
            if (RemoteMode.isController()) {
                RemoteClient.playPrevious()
            } else {
                playbackService?.playPrevious()
            }
        }

        binding.btnNowPlayingNext.setOnClickListener {
            if (RemoteMode.isController()) {
                RemoteClient.playNext()
            } else {
                playbackService?.playNext()
            }
        }

        binding.nowPlayingBar.setOnClickListener {
            // TODO: Open full now playing screen or queue
        }
    }

    private fun updateNowPlayingBar(song: Song?) {
        if (song != null) {
            binding.nowPlayingBar.visibility = View.VISIBLE
            binding.tvNowPlayingTitle.text = song.title
            binding.tvNowPlayingArtist.text = song.artist

            if (song.coverUrl.isNotEmpty()) {
                binding.ivNowPlayingCover.load(song.coverUrl) {
                    placeholder(R.drawable.ic_album)
                    error(R.drawable.ic_album)
                }
            } else {
                binding.ivNowPlayingCover.setImageResource(R.drawable.ic_album)
            }
        } else {
            binding.nowPlayingBar.visibility = View.GONE
        }
    }

    private fun bindPlaybackService() {
        Intent(this, PlaybackService::class.java).also { intent ->
            // Start the service to keep it alive independently of binding
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (ShareReceiver.isShareIntent(intent)) {
            val sharedText = ShareReceiver.getSharedText(intent)
            if (sharedText != null) {
                // Navigate to add song screen with the shared URL
                val url = LinkParser.extractUrlFromText(sharedText)
                if (url != null) {
                    // Navigate to add song fragment
                    navController.navigate(R.id.nav_add_song)
                    // The fragment will need to receive this URL somehow
                    // We'll store it temporarily
                    pendingSharedUrl = url
                }
            }
            ShareReceiver.clearIntent(intent)
        }
    }

    fun getPlaybackService(): PlaybackService? = playbackService

    fun getPendingSharedUrl(): String? {
        val url = pendingSharedUrl
        pendingSharedUrl = null
        return url
    }

    override fun onDestroy() {
        super.onDestroy()
        RemoteClient.removeStateListener(remoteStateListener)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    companion object {
        private var pendingSharedUrl: String? = null
    }
}
