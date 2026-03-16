package com.musichub.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.musichub.databinding.FragmentPlaylistDetailBinding
import com.musichub.remote.RemoteClient
import com.musichub.remote.RemoteMode
import com.musichub.remote.toSong
import com.musichub.service.FloatingWindowService
import com.musichub.service.PlaybackService
import com.musichub.ui.MainActivity
import com.musichub.ui.adapter.SongAdapter
import com.musichub.ui.viewmodel.PlaylistDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistDetailFragment : Fragment() {

    private var _binding: FragmentPlaylistDetailBinding? = null
    private val binding get() = _binding!!

    private val args: PlaylistDetailFragmentArgs by navArgs()
    private val viewModel: PlaylistDetailViewModel by viewModels {
        PlaylistDetailViewModel.Factory(args.playlistId)
    }
    private lateinit var songAdapter: SongAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSwipeToDelete()
        setupClickListeners()
        observeData()
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { song ->
                if (RemoteMode.isController()) {
                    // In controller mode, play the entire playlist remotely starting at this song
                    RemoteClient.playPlaylist(args.playlistId)
                } else {
                    val playbackService = (activity as? MainActivity)?.getPlaybackService()
                    val allSongs = songAdapter.currentList
                    val index = allSongs.indexOfFirst { it.id == song.id }
                    if (index >= 0) {
                        playbackService?.setQueue(allSongs, index)
                        playbackService?.playAtIndex(index)
                        FloatingWindowService.start(requireContext())
                    }
                }
            },
            onPlayClick = { song ->
                if (RemoteMode.isController()) {
                    RemoteClient.playSong(song.id)
                } else {
                    (activity as? MainActivity)?.getPlaybackService()?.playSong(song)
                    FloatingWindowService.start(requireContext())
                }
            },
            onDeleteClick = { song ->
                viewModel.removeSongFromPlaylist(song.id)
                Snackbar.make(binding.root, "已移除: ${song.title}", Snackbar.LENGTH_SHORT).show()
            }
        )

        binding.rvSongs.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = songAdapter
        }
    }

    private fun setupSwipeToDelete() {
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                songAdapter.removeItem(position)
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.rvSongs)
    }

    private fun setupClickListeners() {
        binding.btnPlayAll.setOnClickListener {
            if (RemoteMode.isController()) {
                RemoteClient.playPlaylist(args.playlistId)
                FloatingWindowService.start(requireContext())
            } else {
                val songs = songAdapter.currentList
                if (songs.isNotEmpty()) {
                    val playbackService = (activity as? MainActivity)?.getPlaybackService()
                    playbackService?.setQueue(songs, 0)
                    playbackService?.playAtIndex(0)
                    FloatingWindowService.start(requireContext())
                }
            }
        }

        binding.btnShuffle.setOnClickListener {
            if (RemoteMode.isController()) {
                // Play playlist remotely, then toggle shuffle
                RemoteClient.playPlaylist(args.playlistId)
                RemoteClient.toggleShuffle()
                FloatingWindowService.start(requireContext())
            } else {
                val songs = songAdapter.currentList.shuffled()
                if (songs.isNotEmpty()) {
                    val playbackService = (activity as? MainActivity)?.getPlaybackService()
                    playbackService?.setQueue(songs, 0)
                    playbackService?.playAtIndex(0)
                    FloatingWindowService.start(requireContext())
                }
            }
        }

        binding.fabAddSong.setOnClickListener {
            val action = PlaylistDetailFragmentDirections
                .actionDetailToAddSong(args.playlistId)
            findNavController().navigate(action)
        }

        binding.fabLocate.setOnClickListener {
            locateCurrentSong()
        }
    }

    /**
     * Scroll to the currently playing song in the list.
     */
    private fun locateCurrentSong() {
        val currentSongTitle: String?
        val currentSongId: Long?

        if (RemoteMode.isController()) {
            val remoteSong = RemoteClient.currentState?.currentSong
            currentSongTitle = remoteSong?.title
            currentSongId = remoteSong?.id
        } else {
            val playbackService = PlaybackService.getInstance()
            val currentSong = playbackService?.getCurrentSong()
            currentSongTitle = currentSong?.title
            currentSongId = currentSong?.id
        }

        if (currentSongId == null) {
            Snackbar.make(binding.root, "当前没有正在播放的歌曲", Snackbar.LENGTH_SHORT).show()
            return
        }

        val songs = songAdapter.currentList
        val index = songs.indexOfFirst { it.id == currentSongId }

        if (index >= 0) {
            // Scroll to the position with some offset to center it
            (binding.rvSongs.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                index,
                binding.rvSongs.height / 3
            )
            Snackbar.make(binding.root, "已定位到: ${currentSongTitle ?: ""}", Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(binding.root, "当前歌曲不在此歌单中", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun observeData() {
        if (RemoteMode.isController()) {
            // In controller mode, fetch playlist songs from remote
            viewLifecycleOwner.lifecycleScope.launch {
                val songs = withContext(Dispatchers.IO) {
                    RemoteClient.fetchPlaylistSongs(args.playlistId).map { it.toSong() }
                }
                songAdapter.submitList(songs)
                binding.tvSongCount.text = "${songs.size} 首歌曲"
                binding.tvPlaylistName.text = args.playlistName

                val isEmpty = songs.isEmpty()
                binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                binding.rvSongs.visibility = if (isEmpty) View.GONE else View.VISIBLE
            }
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.playlist.collectLatest { playlist ->
                if (playlist != null) {
                    binding.tvPlaylistName.text = playlist.name
                    binding.tvPlaylistDescription.text = playlist.description
                    binding.tvPlaylistDescription.visibility =
                        if (playlist.description.isNotEmpty()) View.VISIBLE else View.GONE

                    // Set toolbar title
                    (activity as? AppCompatActivity)?.supportActionBar?.title = playlist.name
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.songs.collectLatest { songs ->
                songAdapter.submitList(songs)
                binding.tvSongCount.text = "${songs.size} 首歌曲"

                val isEmpty = songs.isEmpty()
                binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                binding.rvSongs.visibility = if (isEmpty) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
