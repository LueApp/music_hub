package com.musichub.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import coil.load
import com.musichub.R
import com.musichub.data.model.Playlist
import com.musichub.databinding.FragmentAddSongBinding
import com.musichub.platform.Platforms
import com.musichub.ui.MainActivity
import com.musichub.ui.viewmodel.AddSongViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AddSongFragment : Fragment() {

    private var _binding: FragmentAddSongBinding? = null
    private val binding get() = _binding!!

    private val args: AddSongFragmentArgs by navArgs()
    private val viewModel: AddSongViewModel by viewModels { AddSongViewModel.Factory }

    private var playlistAdapter: ArrayAdapter<String>? = null
    private var currentPlaylists: List<Playlist> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddSongBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupClickListeners()
        observeState()

        // Check for pending shared URL
        (activity as? MainActivity)?.getPendingSharedUrl()?.let { url ->
            binding.etLink.setText(url)
            viewModel.parseLink(url)
        }
    }

    private fun setupClickListeners() {
        binding.btnParse.setOnClickListener {
            val link = binding.etLink.text?.toString()?.trim() ?: ""
            if (link.isNotEmpty()) {
                viewModel.parseLink(link)
            }
        }

        binding.btnAddToLibrary.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.isPlaylist) {
                val keepSynced = binding.cbKeepSynced.isChecked
                viewModel.importPlaylist(keepSynced)
            } else {
                val playlistId = if (args.playlistId > 0) args.playlistId else null
                viewModel.addSongToLibrary(playlistId)
            }
        }

        // Setup playlist spinner
        setupPlaylistSpinner()
    }

    private fun setupPlaylistSpinner() {
        playlistAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            mutableListOf("创建新歌单")
        )
        playlistAdapter?.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerPlaylist.adapter = playlistAdapter

        binding.spinnerPlaylist.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    // "Create new" option selected
                    viewModel.selectTargetPlaylist(null)
                } else {
                    // Existing playlist selected
                    val playlistIndex = position - 1
                    if (playlistIndex < currentPlaylists.size) {
                        viewModel.selectTargetPlaylist(currentPlaylists[playlistIndex].id)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                viewModel.selectTargetPlaylist(null)
            }
        }
    }

    private fun updatePlaylistSpinner(playlists: List<Playlist>) {
        currentPlaylists = playlists
        val options = mutableListOf("创建新歌单")
        options.addAll(playlists.map { it.name })

        playlistAdapter?.clear()
        playlistAdapter?.addAll(options)
        playlistAdapter?.notifyDataSetChanged()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                // Loading state
                binding.parsingProgress.visibility =
                    if (state.isLoading) View.VISIBLE else View.GONE
                binding.btnParse.isEnabled = !state.isLoading

                // Error state
                if (state.error != null) {
                    binding.cardError.visibility = View.VISIBLE
                    binding.tvError.text = state.error
                    binding.cardPreview.visibility = View.GONE
                } else {
                    binding.cardError.visibility = View.GONE
                }

                // Update playlist spinner with existing playlists
                if (state.existingPlaylists != currentPlaylists) {
                    updatePlaylistSpinner(state.existingPlaylists)
                }

                // Playlist preview
                val parsedPlaylist = state.parsedPlaylist
                if (parsedPlaylist != null && state.isPlaylist && state.error == null) {
                    binding.cardPreview.visibility = View.VISIBLE
                    binding.tvPreviewTitle.text = parsedPlaylist.name.ifEmpty { "未命名歌单" }
                    binding.tvPreviewArtist.text = "${parsedPlaylist.songs.size} 首歌曲"
                    binding.tvPreviewAlbum.text = parsedPlaylist.description.take(50)

                    // Show playlist selector for playlist imports
                    binding.layoutPlaylistSelector.visibility = View.VISIBLE

                    // Show sync checkbox for syncable platforms (not Bilibili)
                    binding.cbKeepSynced.visibility =
                        if (parsedPlaylist.platform != Platforms.BILIBILI) View.VISIBLE else View.GONE

                    // Platform badge
                    when (parsedPlaylist.platform) {
                        Platforms.NETEASE -> {
                            binding.tvPreviewPlatform.text = "网易云歌单"
                            binding.tvPreviewPlatform.setBackgroundResource(R.drawable.bg_badge_netease)
                        }
                        Platforms.QQMUSIC -> {
                            binding.tvPreviewPlatform.text = "QQ音乐歌单"
                            binding.tvPreviewPlatform.setBackgroundResource(R.drawable.bg_badge_qqmusic)
                        }
                    }

                    // Playlist cover
                    if (parsedPlaylist.coverUrl.isNotEmpty()) {
                        binding.ivPreviewCover.load(parsedPlaylist.coverUrl) {
                            placeholder(R.drawable.ic_album)
                            error(R.drawable.ic_album)
                        }
                    } else {
                        binding.ivPreviewCover.setImageResource(R.drawable.ic_album)
                    }

                    // Update button text for playlist
                    val targetPlaylistId = state.selectedPlaylistId
                    if (targetPlaylistId != null && targetPlaylistId > 0) {
                        val targetPlaylist = state.existingPlaylists.find { it.id == targetPlaylistId }
                        binding.btnAddToLibrary.text = "导入到 ${targetPlaylist?.name ?: "歌单"}"
                    } else {
                        binding.btnAddToLibrary.text = "创建新歌单并导入"
                    }

                    // Show import progress if importing
                    if (state.importTotal > 0 && state.importProgress < state.importTotal) {
                        binding.btnAddToLibrary.text = "导入中... ${state.importProgress}/${state.importTotal}"
                        binding.btnAddToLibrary.isEnabled = false
                    } else {
                        binding.btnAddToLibrary.isEnabled = true
                    }
                }
                // Parsed song preview
                else {
                    // Hide playlist selector and sync checkbox for single song
                    binding.layoutPlaylistSelector.visibility = View.GONE
                    binding.cbKeepSynced.visibility = View.GONE

                    val parsedSong = state.parsedSong
                    if (parsedSong != null && state.error == null) {
                        binding.cardPreview.visibility = View.VISIBLE
                        binding.tvPreviewTitle.text = parsedSong.title
                        binding.tvPreviewArtist.text = parsedSong.artist
                        binding.tvPreviewAlbum.text = parsedSong.album

                        // Platform badge
                        when (parsedSong.platform) {
                            Platforms.NETEASE -> {
                                binding.tvPreviewPlatform.text = "网易云"
                                binding.tvPreviewPlatform.setBackgroundResource(R.drawable.bg_badge_netease)
                            }
                            Platforms.QQMUSIC -> {
                                binding.tvPreviewPlatform.text = "QQ音乐"
                                binding.tvPreviewPlatform.setBackgroundResource(R.drawable.bg_badge_qqmusic)
                            }
                            Platforms.BILIBILI -> {
                                binding.tvPreviewPlatform.text = "B站"
                                binding.tvPreviewPlatform.setBackgroundResource(R.drawable.bg_badge_bilibili)
                            }
                        }

                        // Album cover
                        if (parsedSong.coverUrl.isNotEmpty()) {
                            binding.ivPreviewCover.load(parsedSong.coverUrl) {
                                placeholder(R.drawable.ic_album)
                                error(R.drawable.ic_album)
                            }
                        } else {
                            binding.ivPreviewCover.setImageResource(R.drawable.ic_album)
                        }

                        // Reset button text for single song
                        binding.btnAddToLibrary.text = getString(R.string.add_to_library_cn)
                    } else if (state.error == null && !state.isPlaylist) {
                        binding.cardPreview.visibility = View.GONE
                    }
                }

                // Success - navigate back
                if (state.addSuccess) {
                    val message = if (state.isPlaylist) {
                        "歌单导入成功"
                    } else {
                        getString(R.string.song_added_cn)
                    }
                    android.widget.Toast.makeText(
                        requireContext(),
                        message,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    findNavController().popBackStack()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
