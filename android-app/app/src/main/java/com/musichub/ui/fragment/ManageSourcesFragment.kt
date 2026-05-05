package com.musichub.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.musichub.R
import com.musichub.databinding.FragmentManageSourcesBinding
import com.musichub.ui.adapter.SyncSourceAdapter
import com.musichub.ui.viewmodel.AddSourceResult
import com.musichub.ui.viewmodel.ManageSourcesViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ManageSourcesFragment : Fragment() {

    private var _binding: FragmentManageSourcesBinding? = null
    private val binding get() = _binding!!

    private val args: ManageSourcesFragmentArgs by navArgs()
    private val viewModel: ManageSourcesViewModel by viewModels {
        ManageSourcesViewModel.Factory(args.playlistId)
    }
    private lateinit var sourceAdapter: SyncSourceAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentManageSourcesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupClickListeners()
        observeData()
    }

    private fun setupRecyclerView() {
        sourceAdapter = SyncSourceAdapter(
            onDeleteClick = { source ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.sync_remove_source)
                    .setMessage(R.string.sync_remove_confirm)
                    .setPositiveButton(R.string.confirm_cn) { _, _ ->
                        viewModel.removeSource(source)
                    }
                    .setNegativeButton(R.string.cancel_cn, null)
                    .show()
            }
        )

        binding.rvSources.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = sourceAdapter
        }
    }

    private fun setupClickListeners() {
        binding.fabAddSource.setOnClickListener {
            showAddSourceDialog()
        }
    }

    private fun showAddSourceDialog() {
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.sync_add_source_hint)
            setPadding(48, 32, 48, 16)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.sync_add_source)
            .setView(editText)
            .setPositiveButton(R.string.confirm_cn) { _, _ ->
                val url = editText.text.toString().trim()
                if (url.isNotEmpty()) {
                    viewModel.addSourceFromUrl(url)
                }
            }
            .setNegativeButton(R.string.cancel_cn, null)
            .show()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.syncSources.collectLatest { sources ->
                sourceAdapter.submitList(sources)
                val isEmpty = sources.isEmpty()
                binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                binding.rvSources.visibility = if (isEmpty) View.GONE else View.VISIBLE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.addResult.collectLatest { result ->
                when (result) {
                    is AddSourceResult.Success -> {
                        Snackbar.make(binding.root, R.string.sync_source_added, Snackbar.LENGTH_SHORT).show()
                        viewModel.clearAddResult()
                    }
                    is AddSourceResult.Error -> {
                        Snackbar.make(binding.root, result.message, Snackbar.LENGTH_LONG).show()
                        viewModel.clearAddResult()
                    }
                    null -> { /* no-op */ }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
