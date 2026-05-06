package com.musichub.ui.fragment

import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.musichub.R
import com.musichub.databinding.FragmentHomeBinding
import com.musichub.platform.Platforms
import com.musichub.remote.RemoteClient
import com.musichub.remote.RemoteMode
import com.musichub.remote.toSong
import com.musichub.service.FloatingWindowService
import com.musichub.service.MediaMonitorService
import com.musichub.service.PlaybackService
import com.musichub.ui.MainActivity
import com.musichub.ui.adapter.SongAdapter
import com.musichub.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels { HomeViewModel.Factory }
    private lateinit var songAdapter: SongAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupClickListeners()
        setupPermissionButtons()
        observeData()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { song ->
                if (RemoteMode.isController()) {
                    RemoteClient.playSong(song.id)
                } else {
                    (activity as? MainActivity)?.getPlaybackService()?.playSong(song)
                }
            },
            onPlayClick = { song ->
                if (RemoteMode.isController()) {
                    RemoteClient.playSong(song.id)
                } else {
                    (activity as? MainActivity)?.getPlaybackService()?.playSong(song)
                }
            }
        )

        binding.rvRecentPlays.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = songAdapter
        }
    }

    private fun setupClickListeners() {
        binding.fabAddSong.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_add_song)
        }

        binding.cardNetease.setOnClickListener {
            // TODO: Filter library by NetEase
        }

        binding.cardQQMusic.setOnClickListener {
            // TODO: Filter library by QQ Music
        }

        binding.cardBilibili.setOnClickListener {
            // TODO: Filter library by Bilibili
        }
    }

    private fun setupPermissionButtons() {
        binding.btnEnableNotification.setOnClickListener {
            MediaMonitorService.openSettings(requireContext())
        }

        binding.btnEnableOverlay.setOnClickListener {
            requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                R.id.bottom_navigation
            )?.selectedItemId = R.id.nav_settings
        }

        // Auto-start permission for MIUI devices
        binding.btnEnableAutoStart.setOnClickListener {
            MediaMonitorService.openMiuiAutoStartSettings(requireContext())
        }

        binding.btnShowFloatingWindow.setOnClickListener {
            if (Settings.canDrawOverlays(requireContext())) {
                PlaybackService.startService(requireContext())
                FloatingWindowService.start(requireContext())
                Toast.makeText(requireContext(), "悬浮窗已显示", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "请先在设置-权限页面授权悬浮窗", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePermissionStatus() {
        // Notification listener status
        val notificationEnabled = MediaMonitorService.isEnabled(requireContext())
        binding.tvNotificationStatus.text = if (notificationEnabled) {
            "通知监听: ✓ 已授权"
        } else {
            "通知监听: ✗ 未授权"
        }
        binding.tvNotificationStatus.setTextColor(
            resources.getColor(
                if (notificationEnabled) R.color.success else R.color.warning,
                null
            )
        )
        binding.btnEnableNotification.visibility =
            if (notificationEnabled) View.GONE else View.VISIBLE

        // Overlay permission status
        val overlayEnabled = Settings.canDrawOverlays(requireContext())
        binding.tvOverlayStatus.text = if (overlayEnabled) {
            "悬浮窗: ✓ 已授权"
        } else {
            "悬浮窗: ✗ 未授权"
        }
        binding.tvOverlayStatus.setTextColor(
            resources.getColor(
                if (overlayEnabled) R.color.success else R.color.warning,
                null
            )
        )
        binding.btnEnableOverlay.visibility =
            if (overlayEnabled) View.GONE else View.VISIBLE

        // Auto-start permission for MIUI devices
        if (MediaMonitorService.isMiui()) {
            binding.layoutAutoStart.visibility = View.VISIBLE
            // We can't programmatically check auto-start status, so always show it
            binding.tvAutoStartStatus.text = "自启动: 请确保已开启"
        } else {
            binding.layoutAutoStart.visibility = View.GONE
        }

        // Update card stroke color based on permissions
        val allGranted = notificationEnabled && overlayEnabled
        binding.cardPermissions.strokeColor = resources.getColor(
            if (allGranted) R.color.success else R.color.warning,
            null
        )
    }

    private fun observeData() {
        if (RemoteMode.isController()) {
            // In controller mode, fetch songs from remote server
            binding.progressLoading.visibility = View.VISIBLE
            binding.rvRecentPlays.visibility = View.GONE
            binding.tvNoRecentPlays.visibility = View.GONE
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val songs = withContext(Dispatchers.IO) {
                        RemoteClient.fetchAllSongs().map { it.toSong() }
                    }
                    if (_binding == null) return@launch
                    binding.progressLoading.visibility = View.GONE
                    songAdapter.submitList(songs)
                    binding.tvNoRecentPlays.visibility =
                        if (songs.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvRecentPlays.visibility =
                        if (songs.isEmpty()) View.GONE else View.VISIBLE

                    // Update platform counts
                    val neteaseCnt = songs.count { it.platform == Platforms.NETEASE }
                    val qqCnt = songs.count { it.platform == Platforms.QQMUSIC }
                    val biliCnt = songs.count { it.platform == Platforms.BILIBILI }
                    binding.tvNeteaseSongCount.text = "$neteaseCnt 首"
                    binding.tvQQMusicSongCount.text = "$qqCnt 首"
                    binding.tvBilibiliSongCount.text = "$biliCnt 首"
                } catch (e: Exception) {
                    android.util.Log.e("HomeFragment", "Failed to fetch remote songs: ${e.message}", e)
                    if (_binding == null) return@launch
                    binding.progressLoading.visibility = View.GONE
                    binding.tvNoRecentPlays.visibility = View.VISIBLE
                    Toast.makeText(context, R.string.remote_load_songs_failed, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.recentSongs.collectLatest { songs ->
                songAdapter.submitList(songs)
                binding.tvNoRecentPlays.visibility =
                    if (songs.isEmpty()) View.VISIBLE else View.GONE
                binding.rvRecentPlays.visibility =
                    if (songs.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.platformCounts.collectLatest { counts ->
                binding.tvNeteaseSongCount.text = "${counts[Platforms.NETEASE] ?: 0} 首"
                binding.tvQQMusicSongCount.text = "${counts[Platforms.QQMUSIC] ?: 0} 首"
                binding.tvBilibiliSongCount.text = "${counts[Platforms.BILIBILI] ?: 0} 首"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
