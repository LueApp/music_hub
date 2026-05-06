package com.musichub.ui.fragment

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.core.view.MenuProvider
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.musichub.R
import com.musichub.data.model.Song
import com.musichub.databinding.FragmentPlaylistDetailBinding
import com.musichub.platform.Platforms
import com.musichub.remote.RemoteClient
import com.musichub.remote.RemoteMode
import com.musichub.remote.toSong
import com.musichub.service.FloatingWindowService
import com.musichub.service.PlaybackService
import com.musichub.ui.MainActivity
import com.musichub.ui.adapter.SongAdapter
import com.musichub.ui.viewmodel.PlaylistDetailViewModel
import me.zhanghai.android.fastscroll.FastScrollerBuilder
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
    private var hasSyncSources = false

    // For controller mode in-memory filtering
    private var remoteSongs: List<Song> = emptyList()

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
        setupSearchAndFilter()
        setupClickListeners()
        setupMenu()
        observeData()
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { song ->
                if (RemoteMode.isController()) {
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
            },
            onSongLongClick = { song ->
                showCustomDurationDialog(song)
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
        binding.etSearch?.doAfterTextChanged { text ->
            val query = text?.toString() ?: ""
            if (RemoteMode.isController()) {
                filterRemoteSongs(query, getCurrentPlatformFilter())
            } else {
                viewModel.setSearchQuery(query)
            }
        }

        binding.chipGroupFilter?.setOnCheckedStateChangeListener { _, checkedIds ->
            val platform = when {
                checkedIds.contains(R.id.chipNetease) -> Platforms.NETEASE
                checkedIds.contains(R.id.chipQQMusic) -> Platforms.QQMUSIC
                checkedIds.contains(R.id.chipBilibili) -> Platforms.BILIBILI
                else -> null
            }
            if (RemoteMode.isController()) {
                filterRemoteSongs(binding.etSearch?.text?.toString() ?: "", platform)
            } else {
                viewModel.setPlatformFilter(platform)
            }
        }
    }

    private fun getCurrentPlatformFilter(): String? {
        val checkedIds = binding.chipGroupFilter?.checkedChipIds ?: return null
        return when {
            checkedIds.contains(R.id.chipNetease) -> Platforms.NETEASE
            checkedIds.contains(R.id.chipQQMusic) -> Platforms.QQMUSIC
            checkedIds.contains(R.id.chipBilibili) -> Platforms.BILIBILI
            else -> null
        }
    }

    private fun filterRemoteSongs(query: String, platform: String?) {
        val filtered = remoteSongs.filter { song ->
            if (query.isNotEmpty()) {
                song.title.contains(query, ignoreCase = true) ||
                    song.artist.contains(query, ignoreCase = true)
            } else true
        }.filter { song ->
            platform == null || song.platform == platform
        }

        songAdapter.submitList(filtered)
        binding.tvSongCount.text = "${filtered.size} 首歌曲"

        val isEmpty = filtered.isEmpty()
        binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvSongs.visibility = if (isEmpty) View.GONE else View.VISIBLE
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
                RemoteClient.playPlaylist(args.playlistId, shuffle = true)
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

        binding.fabImportFromLibrary?.setOnClickListener {
            val action = PlaylistDetailFragmentDirections
                .actionDetailToImportFromLibrary(args.playlistId)
            findNavController().navigate(action)
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

    private fun setupMenu() {
        binding.toolbar!!.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_playlist_detail, menu)
            }

            override fun onPrepareMenu(menu: Menu) {
                menu.findItem(R.id.action_sync_now)?.isVisible = hasSyncSources
                menu.findItem(R.id.action_manage_sources)?.isVisible = true
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_import_from_library -> {
                        val action = PlaylistDetailFragmentDirections
                            .actionDetailToImportFromLibrary(args.playlistId)
                        findNavController().navigate(action)
                        true
                    }
                    R.id.action_sync_now -> {
                        if (hasSyncSources) {
                            viewModel.syncNow()
                            Snackbar.make(binding.root, R.string.sync_syncing, Snackbar.LENGTH_SHORT).show()
                        } else {
                            Snackbar.make(binding.root, R.string.sync_no_sources, Snackbar.LENGTH_SHORT).show()
                        }
                        true
                    }
                    R.id.action_manage_sources -> {
                        val action = PlaylistDetailFragmentDirections
                            .actionDetailToManageSources(args.playlistId)
                        findNavController().navigate(action)
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

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
            binding.progressLoading?.visibility = View.VISIBLE
            binding.rvSongs.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
            binding.tvPlaylistName.text = args.playlistName
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val songs = withContext(Dispatchers.IO) {
                        RemoteClient.fetchPlaylistSongs(args.playlistId).map { it.toSong() }
                    }
                    if (_binding == null) return@launch
                    binding.progressLoading?.visibility = View.GONE
                    remoteSongs = songs
                    filterRemoteSongs(
                        binding.etSearch?.text?.toString() ?: "",
                        getCurrentPlatformFilter()
                    )
                } catch (e: Exception) {
                    android.util.Log.e("PlaylistDetailFragment", "Failed to fetch remote playlist songs: ${e.message}", e)
                    if (_binding == null) return@launch
                    binding.progressLoading?.visibility = View.GONE
                    binding.emptyState.visibility = View.VISIBLE
                    Toast.makeText(context, R.string.remote_load_songs_failed, Toast.LENGTH_SHORT).show()
                }
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

        // Observe sync sources
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.syncSources.collectLatest { sources ->
                hasSyncSources = sources.isNotEmpty()
                if (sources.isNotEmpty()) {
                    binding.layoutSyncStatus?.visibility = View.VISIBLE
                    binding.tvSyncStatus?.text = viewModel.formatSyncStatus(sources)
                } else {
                    binding.layoutSyncStatus?.visibility = View.GONE
                }
                binding.toolbar?.invalidateMenu()
            }
        }

        // Observe sync results
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.syncResult.collectLatest { result ->
                if (result != null) {
                    val msg = getString(R.string.sync_result, result.added, result.removed)
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                    viewModel.clearSyncResult()
                }
            }
        }

        // Observe syncing state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isSyncing.collectLatest { syncing ->
                if (syncing) {
                    binding.tvSyncStatus?.text = getString(R.string.sync_syncing)
                }
            }
        }
    }

    private fun showCustomDurationDialog(song: Song) {
        val ctx = requireContext()
        val current = song.customDurationMs
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "mm:ss 或 秒数 (例: 1:30 或 90)"
            setText(current?.let { formatMs(it) } ?: "")
            setSelection(text.length)
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val frame = FrameLayout(ctx).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        val currentLabel = current?.let { "当前: ${formatMs(it)}" } ?: "当前: 未设置"
        MaterialAlertDialogBuilder(ctx)
            .setTitle("提前结束播放: ${song.title}")
            .setMessage("$currentLabel\n\n播放到设定时间时自动跳到下一首，用于跳过较长的解说或前奏。")
            .setView(frame)
            .setPositiveButton("保存") { _, _ ->
                val parsed = parseDurationInput(input.text?.toString() ?: "")
                if (parsed == null) {
                    Snackbar.make(binding.root, "时长格式无效", Snackbar.LENGTH_SHORT).show()
                } else {
                    viewModel.setSongCustomDuration(song.id, parsed)
                    Snackbar.make(binding.root, "已设置: ${formatMs(parsed)}", Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("清除") { _, _ ->
                viewModel.setSongCustomDuration(song.id, null)
                Snackbar.make(binding.root, "已清除自定义时长", Snackbar.LENGTH_SHORT).show()
            }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun parseDurationInput(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val totalSecs = if (trimmed.contains(":")) {
                val parts = trimmed.split(":")
                if (parts.size != 2) return null
                val mins = parts[0].toLong()
                val secs = parts[1].toLong()
                if (mins < 0 || secs !in 0..59) return null
                mins * 60 + secs
            } else {
                trimmed.toLong()
            }
            if (totalSecs <= 0) null else totalSecs * 1000L
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSecs = ms / 1000
        return "%d:%02d".format(totalSecs / 60, totalSecs % 60)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
