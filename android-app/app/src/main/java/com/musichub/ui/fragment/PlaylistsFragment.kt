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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.musichub.R
import com.musichub.data.model.Playlist
import com.musichub.databinding.FragmentPlaylistsBinding
import com.musichub.remote.RemoteClient
import com.musichub.remote.RemoteMode
import com.musichub.ui.adapter.PlaylistAdapter
import com.musichub.ui.viewmodel.PlaylistsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistsFragment : Fragment() {

    private var _binding: FragmentPlaylistsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlaylistsViewModel by viewModels { PlaylistsViewModel.Factory }
    private lateinit var playlistAdapter: PlaylistAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupClickListeners()
        observeData()
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            onPlaylistClick = { playlist ->
                val action = PlaylistsFragmentDirections
                    .actionPlaylistsToDetail(playlist.id, playlist.name)
                findNavController().navigate(action)
            },
            onPlaylistLongClick = { playlist ->
                showDeletePlaylistDialog(playlist)
            }
        )

        binding.rvPlaylists.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = playlistAdapter
        }
    }

    private fun showDeletePlaylistDialog(playlist: com.musichub.data.model.Playlist) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除歌单")
            .setMessage("确定要删除「${playlist.name}」吗？")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deletePlaylist(playlist)
            }
            .setNegativeButton(R.string.cancel_cn, null)
            .show()
    }

    private fun setupClickListeners() {
        binding.fabCreatePlaylist.setOnClickListener {
            showCreatePlaylistDialog()
        }

        binding.btnCreateFirst.setOnClickListener {
            showCreatePlaylistDialog()
        }
    }

    private fun showCreatePlaylistDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_create_playlist, null)

        val etName = dialogView.findViewById<TextInputEditText>(R.id.etPlaylistName)
        val etDescription = dialogView.findViewById<TextInputEditText>(R.id.etPlaylistDescription)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_playlist_cn)
            .setView(dialogView)
            .setPositiveButton(R.string.save_cn) { _, _ ->
                val name = etName.text?.toString()?.trim() ?: ""
                val description = etDescription.text?.toString()?.trim() ?: ""
                if (name.isNotEmpty()) {
                    viewModel.createPlaylist(name, description)
                }
            }
            .setNegativeButton(R.string.cancel_cn, null)
            .show()
    }

    private fun observeData() {
        if (RemoteMode.isController()) {
            // In controller mode, fetch playlists from remote server
            viewLifecycleOwner.lifecycleScope.launch {
                val remotePlaylists = withContext(Dispatchers.IO) {
                    RemoteClient.fetchPlaylists()
                }
                val playlists = remotePlaylists.map { remote ->
                    Playlist(
                        id = remote.id,
                        name = remote.name,
                        description = remote.description
                    )
                }
                playlistAdapter.submitList(playlists)

                val isEmpty = playlists.isEmpty()
                binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                binding.rvPlaylists.visibility = if (isEmpty) View.GONE else View.VISIBLE
            }
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.playlists.collectLatest { playlists ->
                playlistAdapter.submitList(playlists)

                val isEmpty = playlists.isEmpty()
                binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                binding.rvPlaylists.visibility = if (isEmpty) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
