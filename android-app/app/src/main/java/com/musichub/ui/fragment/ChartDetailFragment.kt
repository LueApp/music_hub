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
import com.musichub.R
import com.musichub.data.model.ParsedSong
import com.musichub.databinding.FragmentChartDetailBinding
import com.musichub.platform.ChartInfo
import com.musichub.service.DeepLinkLauncher
import com.musichub.service.FloatingWindowService
import com.musichub.ui.MainActivity
import com.musichub.ui.adapter.DiscoverSongAdapter
import com.musichub.ui.viewmodel.DiscoverViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChartDetailFragment : Fragment() {

    private var _binding: FragmentChartDetailBinding? = null
    private val binding get() = _binding!!

    private val args: ChartDetailFragmentArgs by navArgs()
    private val viewModel: DiscoverViewModel by activityViewModels { DiscoverViewModel.Factory }
    private lateinit var songAdapter: DiscoverSongAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChartDetailBinding.inflate(inflater, container, false)
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
        binding.toolbar.title = args.chartName
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
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
            viewModel.chartSongs.collectLatest { songs ->
                songAdapter.submitList(songs)
                binding.rvSongs.visibility = if (songs.isNotEmpty()) View.VISIBLE else View.GONE
                binding.btnPlayAll.visibility = if (songs.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.chartSongsLoading.collectLatest { loading ->
                binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                if (loading) {
                    binding.errorState.visibility = View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.chartSongsError.collectLatest { error ->
                if (error != null && viewModel.chartSongs.value.isEmpty()) {
                    binding.errorState.visibility = View.VISIBLE
                    binding.tvError.text = error
                } else {
                    binding.errorState.visibility = View.GONE
                }
            }
        }
    }

    private fun loadData() {
        val chart = ChartInfo(
            platform = args.chartPlatform,
            chartId = args.chartId,
            name = args.chartName
        )
        viewModel.loadChartSongs(chart)

        binding.btnRetry.setOnClickListener {
            viewModel.loadChartSongs(chart)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
