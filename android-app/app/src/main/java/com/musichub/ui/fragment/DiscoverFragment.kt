package com.musichub.ui.fragment

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayoutMediator
import com.musichub.R
import com.musichub.auth.PlatformAuthManager
import com.musichub.data.model.ParsedSong
import com.musichub.databinding.FragmentDiscoverBinding
import com.musichub.platform.ChartInfo
import com.musichub.platform.DiscoverPlaylistInfo
import com.musichub.platform.Platforms
import com.musichub.service.DeepLinkLauncher
import com.musichub.service.FloatingWindowService
import com.musichub.ui.MainActivity
import com.musichub.ui.adapter.ChartAdapter
import com.musichub.ui.adapter.DiscoverPlaylistAdapter
import com.musichub.ui.adapter.DiscoverSongAdapter
import com.musichub.ui.viewmodel.DiscoverViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DiscoverFragment : Fragment() {

    private var _binding: FragmentDiscoverBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DiscoverViewModel by activityViewModels { DiscoverViewModel.Factory }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDiscoverBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViewPager()
    }

    private fun setupViewPager() {
        val adapter = DiscoverPagerAdapter(this)
        binding.viewPager.adapter = adapter

        val tabTitles = listOf(
            getString(R.string.discover_tab_charts),
            getString(R.string.discover_tab_browse),
            getString(R.string.discover_tab_foryou)
        )

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()
    }

    fun navigateToChartDetail(chart: ChartInfo) {
        val bundle = Bundle().apply {
            putString("chartPlatform", chart.platform)
            putString("chartId", chart.chartId)
            putString("chartName", chart.name)
        }
        findNavController().navigate(R.id.nav_chart_detail, bundle)
    }

    fun navigateToBrowsePlaylistDetail(playlist: DiscoverPlaylistInfo) {
        val bundle = Bundle().apply {
            putString("platform", playlist.platform)
            putString("playlistId", playlist.playlistId)
            putString("playlistName", playlist.name)
        }
        findNavController().navigate(R.id.nav_browse_playlist_detail, bundle)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class DiscoverPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> ChartsTabFragment()
                1 -> BrowseTabFragment()
                2 -> ForYouTabFragment()
                else -> throw IllegalArgumentException("Invalid position: $position")
            }
        }
    }
}

/**
 * Charts section — shows list of charts from all platforms.
 */
class ChartsTabFragment : Fragment() {

    private var chartAdapter: ChartAdapter? = null
    private val viewModel: DiscoverViewModel by activityViewModels { DiscoverViewModel.Factory }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val recyclerView = RecyclerView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(requireContext())
            setPadding(0, 8, 0, 8)
            clipToPadding = false
        }

        chartAdapter = ChartAdapter { chart ->
            (parentFragment as? DiscoverFragment)?.navigateToChartDetail(chart)
        }
        recyclerView.adapter = chartAdapter

        return recyclerView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.charts.collectLatest { charts ->
                chartAdapter?.submitList(charts)
            }
        }

        if (viewModel.charts.value.isEmpty()) {
            viewModel.loadCharts()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        chartAdapter = null
    }
}

/**
 * Browse section — shows curated playlists with category filter chips.
 */
class BrowseTabFragment : Fragment() {

    private var playlistAdapter: DiscoverPlaylistAdapter? = null
    private val viewModel: DiscoverViewModel by activityViewModels { DiscoverViewModel.Factory }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_browse_tab, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val chipGroup = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupCategories)
        val recyclerView = view.findViewById<RecyclerView>(R.id.rvPlaylists)
        val progressBar = view.findViewById<View>(R.id.progressBar)

        playlistAdapter = DiscoverPlaylistAdapter { playlist ->
            (parentFragment as? DiscoverFragment)?.navigateToBrowsePlaylistDetail(playlist)
        }

        recyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = playlistAdapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.categories.collectLatest { categories ->
                chipGroup.removeAllViews()
                for (category in categories) {
                    val chip = Chip(requireContext()).apply {
                        text = category
                        isCheckable = true
                        isChecked = category == viewModel.selectedCategory.value
                        setOnClickListener {
                            viewModel.selectCategory(category)
                        }
                    }
                    chipGroup.addView(chip)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.browsePlaylists.collectLatest { playlists ->
                playlistAdapter?.submitList(playlists)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.browseLoading.collectLatest { loading ->
                progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedCategory.collectLatest { selected ->
                for (i in 0 until chipGroup.childCount) {
                    val chip = chipGroup.getChildAt(i) as? Chip
                    chip?.isChecked = chip?.text == selected
                }
            }
        }

        if (viewModel.categories.value.isEmpty()) {
            viewModel.loadCategories()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        playlistAdapter = null
    }
}

/**
 * For You section — shows personalized recommendations (requires login).
 */
class ForYouTabFragment : Fragment() {

    private val viewModel: DiscoverViewModel by activityViewModels { DiscoverViewModel.Factory }

    private var neteaseAdapter: DiscoverSongAdapter? = null
    private var qqmusicAdapter: DiscoverSongAdapter? = null

    // Track which platform triggered the login
    private var pendingLoginPlatform: String? = null

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val platform = pendingLoginPlatform ?: return@registerForActivityResult
        pendingLoginPlatform = null
        if (viewModel.authManager.handleLoginResult(result.resultCode, result.data)) {
            viewModel.onLoginSuccess(platform)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_foryou_tab, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // NetEase views
        val btnNeteaseLogin = view.findViewById<MaterialButton>(R.id.btnNeteaseLogin)
        val btnNeteaseLogout = view.findViewById<MaterialButton>(R.id.btnNeteaseLogout)
        val progressNetease = view.findViewById<ProgressBar>(R.id.progressNetease)
        val tvNeteaseLoginPrompt = view.findViewById<TextView>(R.id.tvNeteaseLoginPrompt)
        val rvNeteaseRecs = view.findViewById<RecyclerView>(R.id.rvNeteaseRecs)

        val btnNeteasePlayAll = view.findViewById<MaterialButton>(R.id.btnNeteasePlayAll)

        // Combined play buttons
        val combinedPlayButtons = view.findViewById<LinearLayout>(R.id.combinedPlayButtons)
        val btnCombinedPlayAll = view.findViewById<MaterialButton>(R.id.btnCombinedPlayAll)
        val btnCombinedShuffleAll = view.findViewById<MaterialButton>(R.id.btnCombinedShuffleAll)

        // QQ Music views
        val btnQQMusicLogin = view.findViewById<MaterialButton>(R.id.btnQQMusicLogin)
        val btnQQMusicLogout = view.findViewById<MaterialButton>(R.id.btnQQMusicLogout)
        val progressQQMusic = view.findViewById<ProgressBar>(R.id.progressQQMusic)
        val tvQQMusicLoginPrompt = view.findViewById<TextView>(R.id.tvQQMusicLoginPrompt)
        val rvQQMusicRecs = view.findViewById<RecyclerView>(R.id.rvQQMusicRecs)
        val btnQQMusicPlayAll = view.findViewById<MaterialButton>(R.id.btnQQMusicPlayAll)

        // Setup adapters
        neteaseAdapter = DiscoverSongAdapter(
            onPreviewClick = { song ->
                DeepLinkLauncher.launch(requireContext(), song.deepLink, song.fallbackUrl)
            },
            onAddClick = { song -> addSongToLibrary(song) }
        )
        qqmusicAdapter = DiscoverSongAdapter(
            onPreviewClick = { song ->
                DeepLinkLauncher.launch(requireContext(), song.deepLink, song.fallbackUrl)
            },
            onAddClick = { song -> addSongToLibrary(song) }
        )

        rvNeteaseRecs.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = neteaseAdapter
        }
        rvQQMusicRecs.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = qqmusicAdapter
        }

        // Login buttons
        btnNeteaseLogin.setOnClickListener {
            pendingLoginPlatform = Platforms.NETEASE
            loginLauncher.launch(viewModel.authManager.createLoginIntent(requireContext(), Platforms.NETEASE))
        }
        btnQQMusicLogin.setOnClickListener {
            pendingLoginPlatform = Platforms.QQMUSIC
            loginLauncher.launch(viewModel.authManager.createLoginIntent(requireContext(), Platforms.QQMUSIC))
        }

        // Logout buttons
        btnNeteaseLogout.setOnClickListener {
            viewModel.logout(Platforms.NETEASE)
            updatePlatformUI(Platforms.NETEASE, btnNeteaseLogin, btnNeteaseLogout,
                tvNeteaseLoginPrompt, rvNeteaseRecs, progressNetease, btnNeteasePlayAll)
        }
        btnQQMusicLogout.setOnClickListener {
            viewModel.logout(Platforms.QQMUSIC)
            updatePlatformUI(Platforms.QQMUSIC, btnQQMusicLogin, btnQQMusicLogout,
                tvQQMusicLoginPrompt, rvQQMusicRecs, progressQQMusic, btnQQMusicPlayAll)
        }

        // Play all buttons
        btnNeteasePlayAll.setOnClickListener {
            val parsedSongs = neteaseAdapter?.currentList ?: return@setOnClickListener
            if (parsedSongs.isEmpty()) return@setOnClickListener
            playAllSongs(parsedSongs)
        }
        btnQQMusicPlayAll.setOnClickListener {
            val parsedSongs = qqmusicAdapter?.currentList ?: return@setOnClickListener
            if (parsedSongs.isEmpty()) return@setOnClickListener
            playAllSongs(parsedSongs)
        }

        // Combined play all buttons (both platforms)
        btnCombinedPlayAll.setOnClickListener {
            val combined = getCombinedSongs()
            if (combined.isNotEmpty()) playAllSongs(combined)
        }
        btnCombinedShuffleAll.setOnClickListener {
            val combined = getCombinedSongs()
            if (combined.isNotEmpty()) playAllSongs(combined, shuffle = true)
        }

        // Set initial UI state
        updatePlatformUI(Platforms.NETEASE, btnNeteaseLogin, btnNeteaseLogout,
            tvNeteaseLoginPrompt, rvNeteaseRecs, progressNetease, btnNeteasePlayAll)
        updatePlatformUI(Platforms.QQMUSIC, btnQQMusicLogin, btnQQMusicLogout,
            tvQQMusicLoginPrompt, rvQQMusicRecs, progressQQMusic, btnQQMusicPlayAll)

        // Helper to update combined play buttons visibility
        fun updateCombinedButtons() {
            val hasAnySongs = (neteaseAdapter?.currentList?.isNotEmpty() == true) ||
                (qqmusicAdapter?.currentList?.isNotEmpty() == true)
            combinedPlayButtons.visibility = if (hasAnySongs) View.VISIBLE else View.GONE
        }

        // Observe NetEase recommendations
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.neteaseRecs.collectLatest { songs ->
                neteaseAdapter?.submitList(songs)
                if (viewModel.isLoggedIn(Platforms.NETEASE)) {
                    val hasSongs = songs.isNotEmpty()
                    rvNeteaseRecs.visibility = if (hasSongs) View.VISIBLE else View.GONE
                    btnNeteasePlayAll.visibility = if (hasSongs) View.VISIBLE else View.GONE
                }
                updateCombinedButtons()
            }
        }

        // Observe QQ Music recommendations
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.qqmusicRecs.collectLatest { songs ->
                qqmusicAdapter?.submitList(songs)
                if (viewModel.isLoggedIn(Platforms.QQMUSIC)) {
                    val hasSongs = songs.isNotEmpty()
                    rvQQMusicRecs.visibility = if (hasSongs) View.VISIBLE else View.GONE
                    btnQQMusicPlayAll.visibility = if (hasSongs) View.VISIBLE else View.GONE
                }
                updateCombinedButtons()
            }
        }

        // Observe loading states
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.recsLoading.collectLatest { loadingMap ->
                val neteaseLoading = loadingMap[Platforms.NETEASE] == true
                val qqmusicLoading = loadingMap[Platforms.QQMUSIC] == true
                progressNetease.visibility = if (neteaseLoading) View.VISIBLE else View.GONE
                progressQQMusic.visibility = if (qqmusicLoading) View.VISIBLE else View.GONE

                // Update UI after loading finishes to reflect login state changes
                if (!neteaseLoading) {
                    updatePlatformUI(Platforms.NETEASE, btnNeteaseLogin, btnNeteaseLogout,
                        tvNeteaseLoginPrompt, rvNeteaseRecs, progressNetease, btnNeteasePlayAll)
                }
                if (!qqmusicLoading) {
                    updatePlatformUI(Platforms.QQMUSIC, btnQQMusicLogin, btnQQMusicLogout,
                        tvQQMusicLoginPrompt, rvQQMusicRecs, progressQQMusic, btnQQMusicPlayAll)
                }
            }
        }

        // Load recommendations for already-logged-in platforms
        viewModel.loadAllRecommendations()
    }

    private fun updatePlatformUI(
        platform: String,
        btnLogin: MaterialButton,
        btnLogout: MaterialButton,
        tvLoginPrompt: TextView,
        rvRecs: RecyclerView,
        progress: ProgressBar,
        btnPlayAll: MaterialButton
    ) {
        val loggedIn = viewModel.isLoggedIn(platform)
        btnLogin.visibility = if (!loggedIn) View.VISIBLE else View.GONE
        btnLogout.visibility = if (loggedIn) View.VISIBLE else View.GONE
        tvLoginPrompt.visibility = if (!loggedIn) View.VISIBLE else View.GONE

        if (!loggedIn) {
            rvRecs.visibility = View.GONE
            btnPlayAll.visibility = View.GONE
        }
    }

    private fun getCombinedSongs(): List<ParsedSong> {
        val netease = neteaseAdapter?.currentList ?: emptyList()
        val qqmusic = qqmusicAdapter?.currentList ?: emptyList()
        return netease + qqmusic
    }

    private fun playAllSongs(parsedSongs: List<ParsedSong>, shuffle: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            val songs = viewModel.parsedSongsToSongs(parsedSongs)
            val playbackService = (activity as? MainActivity)?.getPlaybackService()
            playbackService?.setShuffleEnabled(shuffle)
            playbackService?.setQueue(songs, 0)
            playbackService?.playAtIndex(0)
            FloatingWindowService.start(requireContext())
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
        neteaseAdapter = null
        qqmusicAdapter = null
    }
}
