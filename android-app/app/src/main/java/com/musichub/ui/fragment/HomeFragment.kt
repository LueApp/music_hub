package com.musichub.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        observeData()
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
