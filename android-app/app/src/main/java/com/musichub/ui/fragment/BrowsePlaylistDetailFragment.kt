package com.musichub.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.musichub.MusicHubApplication
import com.musichub.R
import com.musichub.data.model.ParsedSong
import com.musichub.data.model.Playlist
import com.musichub.data.model.SyncSource
import com.musichub.databinding.FragmentBrowsePlaylistDetailBinding
import com.musichub.service.DeepLinkLauncher
import com.musichub.service.FloatingWindowService
import com.musichub.sync.PlaylistSyncEngine
import com.musichub.ui.MainActivity
import com.musichub.ui.adapter.DiscoverSongAdapter
import com.musichub.ui.viewmodel.DiscoverViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BrowsePlaylistDetailFragment : Fragment() {

    private var _binding: FragmentBrowsePlaylistDetailBinding? = null
    private val binding get() = _binding!!

    private val args: BrowsePlaylistDetailFragmentArgs by navArgs()
    private val viewModel: DiscoverViewModel by activityViewModels { DiscoverViewModel.Factory }
    private lateinit var songAdapter: DiscoverSongAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowsePlaylistDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupPlayAll()
        observeData()
        loadData()
    }

    private fun setupToolbar() {
        binding.toolbar.title = args.playlistName
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_sync_playlist -> {
                    syncThisPlaylist()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        songAdapter = DiscoverSongAdapter(
            onPreviewClick = { song ->
                DeepLinkLauncher.launch(requireContext(), song.deepLink, song.fallbackUrl)
            },
            onAddClick = { song ->
                addSongToLibrary(song)
            }
        )

        binding.rvSongs.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = songAdapter
        }
    }

    private fun setupPlayAll() {
        binding.btnPlayAll.setOnClickListener {
            val parsedSongs = songAdapter.currentList
            if (parsedSongs.isEmpty()) return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                val songs = viewModel.parsedSongsToSongs(parsedSongs)
                val playbackService = (activity as? MainActivity)?.getPlaybackService()
                playbackService?.setQueue(songs, 0)
                playbackService?.playAtIndex(0)
                FloatingWindowService.start(requireContext())
            }
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.browsePlaylistSongs.collectLatest { songs ->
                songAdapter.submitList(songs)
                binding.rvSongs.visibility = if (songs.isNotEmpty()) View.VISIBLE else View.GONE
                binding.btnPlayAll.visibility = if (songs.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.browsePlaylistSongsLoading.collectLatest { loading ->
                binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                if (loading) {
                    binding.errorState.visibility = View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.browsePlaylistSongsError.collectLatest { error ->
                if (error != null && viewModel.browsePlaylistSongs.value.isEmpty()) {
                    binding.errorState.visibility = View.VISIBLE
                    binding.tvError.text = error
                } else {
                    binding.errorState.visibility = View.GONE
                }
            }
        }
    }

    private fun loadData() {
        viewModel.loadBrowsePlaylistSongs(args.platform, args.playlistId)

        binding.btnRetry.setOnClickListener {
            viewModel.loadBrowsePlaylistSongs(args.platform, args.playlistId)
        }
    }

    private fun addSongToLibrary(song: ParsedSong) {
        viewLifecycleOwner.lifecycleScope.launch {
            val added = viewModel.addSongToLibrary(song)
            if (added) {
                Toast.makeText(requireContext(), R.string.discover_song_added, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.discover_song_exists, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun syncThisPlaylist() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val repository = MusicHubApplication.getInstance().repository

                val playlist = Playlist(name = args.playlistName)
                val playlistId = repository.insertPlaylist(playlist)

                val sourceUrl = when (args.platform) {
                    "netease" -> "https://music.163.com/playlist?id=${args.playlistId}"
                    "qqmusic" -> "https://y.qq.com/n/ryqq/playlist/${args.playlistId}"
                    else -> ""
                }
                val syncSource = SyncSource(
                    playlistId = playlistId,
                    platform = args.platform,
                    remotePlaylistId = args.playlistId,
                    sourceUrl = sourceUrl
                )
                repository.addSyncSource(syncSource)

                val engine = PlaylistSyncEngine(repository)
                engine.syncPlaylist(playlistId)

                Toast.makeText(requireContext(), R.string.discover_playlist_synced, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "同步失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
