package com.musichub.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.musichub.R
import com.musichub.databinding.FragmentLibraryBinding
import com.musichub.platform.Platforms
import com.musichub.service.FloatingWindowService
import com.musichub.service.PlaybackService
import com.musichub.ui.MainActivity
import com.musichub.ui.adapter.SongAdapter
import com.musichub.ui.viewmodel.LibraryViewModel
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LibraryViewModel by viewModels { LibraryViewModel.Factory }
    private lateinit var songAdapter: SongAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSearchAndFilter()
        setupClickListeners()
        observeData()
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { song ->
                // Play this song and set the queue to all filtered songs
                val playbackService = (activity as? MainActivity)?.getPlaybackService()
                val allSongs = songAdapter.currentList
                val index = allSongs.indexOfFirst { it.id == song.id }
                if (index >= 0) {
                    playbackService?.setQueue(allSongs, index)
                    playbackService?.playAtIndex(index)
                    FloatingWindowService.start(requireContext())
                }
            },
            onPlayClick = { song ->
                (activity as? MainActivity)?.getPlaybackService()?.playSong(song)
                FloatingWindowService.start(requireContext())
            }
        )

        binding.rvSongs.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = songAdapter
        }

        FastScrollerBuilder(binding.rvSongs)
            .useMd2Style()
            .build()
    }

    private fun setupSearchAndFilter() {
        binding.etSearch.doAfterTextChanged { text ->
            viewModel.setSearchQuery(text?.toString() ?: "")
        }

        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val platform = when {
                checkedIds.contains(R.id.chipNetease) -> Platforms.NETEASE
                checkedIds.contains(R.id.chipQQMusic) -> Platforms.QQMUSIC
                checkedIds.contains(R.id.chipBilibili) -> Platforms.BILIBILI
                else -> null
            }
            viewModel.setPlatformFilter(platform)
        }
    }

    private fun setupClickListeners() {
        binding.fabPlayAll.setOnClickListener {
            val songs = songAdapter.currentList
            if (songs.isNotEmpty()) {
                val playbackService = (activity as? MainActivity)?.getPlaybackService()
                playbackService?.setQueue(songs, 0)
                playbackService?.playAtIndex(0)
                FloatingWindowService.start(requireContext())
            }
        }

        binding.fabLocate.setOnClickListener {
            locateCurrentSong()
        }

        binding.btnAddFirstSong.setOnClickListener {
            findNavController().navigate(R.id.action_library_to_add_song)
        }
    }

    /**
     * Scroll to the currently playing song in the list.
     */
    private fun locateCurrentSong() {
        val playbackService = PlaybackService.getInstance()
        val currentSong = playbackService?.getCurrentSong()

        if (currentSong == null) {
            Snackbar.make(binding.root, "当前没有正在播放的歌曲", Snackbar.LENGTH_SHORT).show()
            return
        }

        val songs = songAdapter.currentList
        val index = songs.indexOfFirst { it.id == currentSong.id }

        if (index >= 0) {
            // Scroll to the position with some offset to center it
            (binding.rvSongs.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                index,
                binding.rvSongs.height / 3
            )
            Snackbar.make(binding.root, "已定位到: ${currentSong.title}", Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(binding.root, "当前歌曲不在此列表中", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.songs.collectLatest { songs ->
                songAdapter.submitList(songs)

                val isEmpty = songs.isEmpty()
                binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                binding.rvSongs.visibility = if (isEmpty) View.GONE else View.VISIBLE
                binding.tvSongCount.text = "${songs.size} 首歌曲"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
