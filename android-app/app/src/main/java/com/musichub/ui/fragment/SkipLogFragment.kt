package com.musichub.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.musichub.R
import com.musichub.databinding.FragmentSkipLogBinding
import com.musichub.ui.adapter.SkipLogAdapter
import com.musichub.ui.viewmodel.SkipLogViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SkipLogFragment : Fragment() {

    private var _binding: FragmentSkipLogBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SkipLogViewModel by viewModels { SkipLogViewModel.Factory }
    private lateinit var adapter: SkipLogAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSkipLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SkipLogAdapter()
        binding.rvSkipLog.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@SkipLogFragment.adapter
        }

        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menu.add(Menu.NONE, 1, Menu.NONE, R.string.skip_log_clear)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem.itemId == 1) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.skip_log_clear)
                        .setMessage(R.string.skip_log_clear_confirm)
                        .setPositiveButton(R.string.confirm_cn) { _, _ -> viewModel.clearAll() }
                        .setNegativeButton(R.string.cancel_cn, null)
                        .show()
                    return true
                }
                return false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.skipLogs.collectLatest { logs ->
                adapter.submitList(logs)
                binding.tvEmpty.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
                binding.rvSkipLog.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
