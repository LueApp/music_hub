package com.musichub.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.musichub.R
import com.musichub.data.model.Song
import com.musichub.databinding.FragmentImportFromLibraryBinding
import com.musichub.platform.Platforms
import com.musichub.remote.RemoteClient
import com.musichub.remote.RemoteMode
import com.musichub.remote.toSong
import com.musichub.ui.adapter.SelectableSongAdapter
import com.musichub.ui.viewmodel.ImportFromLibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportFromLibraryFragment : Fragment() {

    private var _binding: FragmentImportFromLibraryBinding? = null
    private val binding get() = _binding!!

    private val args: ImportFromLibraryFragmentArgs by navArgs()
    private val viewModel: ImportFromLibraryViewModel by viewModels {
        ImportFromLibraryViewModel.Factory(args.playlistId)
    }
    private lateinit var adapter: SelectableSongAdapter

    // For controller mode
    private var remoteSongs: List<Song> = emptyList()
    private var remotePlaylistSongIds: Set<Long> = emptySet()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImportFromLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupAdapter()
        setupSearchAndFilter()
        setupClickListeners()

        if (RemoteMode.isController()) {
            loadRemoteData()
        } else {
            observeLocalData()
        }

        observeSelection()
    }

    private fun setupAdapter() {
        adapter = SelectableSongAdapter { selectedIds ->
            viewModel.setSelectedIds(selectedIds)
        }
        binding.rvSongs.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ImportFromLibraryFragment.adapter
        }
    }

    private fun setupSearchAndFilter() {
        binding.etSearch.doAfterTextChanged { text ->
            val query = text?.toString() ?: ""
            if (RemoteMode.isController()) {
                filterRemoteSongs(query, getCurrentPlatformFilter())
            } else {
                viewModel.setSearchQuery(query)
            }
        }

        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val platform = when {
                checkedIds.contains(R.id.chipNetease) -> Platforms.NETEASE
                checkedIds.contains(R.id.chipQQMusic) -> Platforms.QQMUSIC
                checkedIds.contains(R.id.chipBilibili) -> Platforms.BILIBILI
                else -> null
            }
            if (RemoteMode.isController()) {
                filterRemoteSongs(binding.etSearch.text?.toString() ?: "", platform)
            } else {
                viewModel.setPlatformFilter(platform)
            }
        }
    }

    private fun getCurrentPlatformFilter(): String? {
        val checkedIds = binding.chipGroupFilter.checkedChipIds
        return when {
            checkedIds.contains(R.id.chipNetease) -> Platforms.NETEASE
            checkedIds.contains(R.id.chipQQMusic) -> Platforms.QQMUSIC
            checkedIds.contains(R.id.chipBilibili) -> Platforms.BILIBILI
            else -> null
        }
    }

    private fun setupClickListeners() {
        binding.btnSelectAll.setOnClickListener {
            val currentSongs = adapter.currentList
            val allSelected = viewModel.selectedIds.value.size == currentSongs.size && currentSongs.isNotEmpty()
            if (allSelected) {
                adapter.deselectAll()
                viewModel.deselectAll()
            } else {
                adapter.selectAll(currentSongs)
                viewModel.selectAll(currentSongs)
            }
        }

        binding.btnConfirmImport.setOnClickListener {
            if (RemoteMode.isController()) {
                importRemote()
            } else {
                viewModel.importSelected()
            }
        }
    }

    private fun observeLocalData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.songs.collectLatest { songs ->
                adapter.submitList(songs)
                updateEmptyState(songs)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.importResult.collectLatest { count ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.import_success, count),
                    Toast.LENGTH_SHORT
                ).show()
                findNavController().popBackStack()
            }
        }
    }

    private fun observeSelection() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedIds.collectLatest { ids ->
                val count = ids.size
                binding.tvSelectionCount.text = getString(R.string.import_selected_count, count)
                binding.btnConfirmImport.isEnabled = count > 0

                val currentSongs = adapter.currentList
                val allSelected = count == currentSongs.size && currentSongs.isNotEmpty()
                binding.btnSelectAll.text = if (allSelected) {
                    getString(R.string.import_deselect_all)
                } else {
                    getString(R.string.import_select_all)
                }
            }
        }
    }

    private fun updateEmptyState(songs: List<Song>) {
        val isEmpty = songs.isEmpty()
        binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvSongs.visibility = if (isEmpty) View.GONE else View.VISIBLE

        if (isEmpty) {
            val query = binding.etSearch.text?.toString() ?: ""
            binding.tvEmptyMessage.text = when {
                query.isNotEmpty() -> getString(R.string.import_empty_no_results)
                else -> getString(R.string.import_empty_all_in_playlist)
            }
        }
    }

    // --- Controller mode ---

    private fun loadRemoteData() {
        binding.progressLoading?.visibility = View.VISIBLE
        binding.rvSongs.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val allSongs = withContext(Dispatchers.IO) {
                    RemoteClient.fetchAllSongs().map { it.toSong() }
                }
                val playlistSongs = withContext(Dispatchers.IO) {
                    RemoteClient.fetchPlaylistSongs(args.playlistId).map { it.toSong() }
                }

                if (_binding == null) return@launch

                remoteSongs = allSongs
                remotePlaylistSongIds = playlistSongs.map { it.id }.toSet()

                binding.progressLoading?.visibility = View.GONE
                filterRemoteSongs("", null)
            } catch (e: Exception) {
                if (_binding == null) return@launch
                binding.progressLoading?.visibility = View.GONE
                binding.emptyState.visibility = View.VISIBLE
                binding.tvEmptyMessage.text = getString(R.string.remote_load_songs_failed)
            }
        }
    }

    private fun filterRemoteSongs(query: String, platform: String?) {
        val available = remoteSongs.filter { song ->
            song.id !in remotePlaylistSongIds
        }.filter { song ->
            if (query.isNotEmpty()) {
                song.title.contains(query, ignoreCase = true) ||
                    song.artist.contains(query, ignoreCase = true)
            } else true
        }.filter { song ->
            platform == null || song.platform == platform
        }

        adapter.submitList(available)
        updateEmptyState(available)
    }

    private fun importRemote() {
        val selectedIds = viewModel.selectedIds.value.toList()
        if (selectedIds.isEmpty()) return

        binding.btnConfirmImport.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RemoteClient.addSongsToPlaylist(args.playlistId, selectedIds)
                }
                if (_binding == null) return@launch
                Toast.makeText(
                    requireContext(),
                    getString(R.string.import_success, selectedIds.size),
                    Toast.LENGTH_SHORT
                ).show()
                findNavController().popBackStack()
            } catch (e: Exception) {
                if (_binding == null) return@launch
                binding.btnConfirmImport.isEnabled = true
                Toast.makeText(requireContext(), R.string.error_network_cn, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
