package com.musichub.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musichub.MusicHubApplication
import com.musichub.auth.PlatformAuthManager
import com.musichub.data.model.ParsedSong
import com.musichub.data.model.Song
import com.musichub.data.repository.MusicRepository
import com.musichub.platform.ChartInfo
import com.musichub.platform.DiscoverPlaylistInfo
import com.musichub.platform.DiscoveryApi
import com.musichub.platform.Platforms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DiscoverViewModel(
    private val repository: MusicRepository,
    val authManager: PlatformAuthManager
) : ViewModel() {

    // --- Charts ---
    private val _charts = MutableStateFlow<List<ChartInfo>>(emptyList())
    val charts: StateFlow<List<ChartInfo>> = _charts

    private val _chartsLoading = MutableStateFlow(false)
    val chartsLoading: StateFlow<Boolean> = _chartsLoading

    private val _chartsError = MutableStateFlow<String?>(null)
    val chartsError: StateFlow<String?> = _chartsError

    // --- Chart Detail (songs) ---
    private val _chartSongs = MutableStateFlow<List<ParsedSong>>(emptyList())
    val chartSongs: StateFlow<List<ParsedSong>> = _chartSongs

    private val _chartSongsLoading = MutableStateFlow(false)
    val chartSongsLoading: StateFlow<Boolean> = _chartSongsLoading

    private val _chartSongsError = MutableStateFlow<String?>(null)
    val chartSongsError: StateFlow<String?> = _chartSongsError

    // --- Browse Categories ---
    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory

    // --- Browse Playlists ---
    private val _browsePlaylists = MutableStateFlow<List<DiscoverPlaylistInfo>>(emptyList())
    val browsePlaylists: StateFlow<List<DiscoverPlaylistInfo>> = _browsePlaylists

    private val _browseLoading = MutableStateFlow(false)
    val browseLoading: StateFlow<Boolean> = _browseLoading

    private val _browseError = MutableStateFlow<String?>(null)
    val browseError: StateFlow<String?> = _browseError

    // --- Browse Playlist Detail (songs) ---
    private val _browsePlaylistSongs = MutableStateFlow<List<ParsedSong>>(emptyList())
    val browsePlaylistSongs: StateFlow<List<ParsedSong>> = _browsePlaylistSongs

    private val _browsePlaylistSongsLoading = MutableStateFlow(false)
    val browsePlaylistSongsLoading: StateFlow<Boolean> = _browsePlaylistSongsLoading

    private val _browsePlaylistSongsError = MutableStateFlow<String?>(null)
    val browsePlaylistSongsError: StateFlow<String?> = _browsePlaylistSongsError

    fun loadCharts() {
        if (_chartsLoading.value) return
        _chartsLoading.value = true
        _chartsError.value = null

        viewModelScope.launch {
            try {
                val chartList = DiscoveryApi.fetchChartList()
                _charts.value = chartList
                Log.d(TAG, "Loaded ${chartList.size} charts")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load charts", e)
                _chartsError.value = e.message ?: "加载失败"
            } finally {
                _chartsLoading.value = false
            }
        }
    }

    fun loadChartSongs(chart: ChartInfo) {
        if (_chartSongsLoading.value) return
        _chartSongsLoading.value = true
        _chartSongsError.value = null
        _chartSongs.value = emptyList()

        viewModelScope.launch {
            try {
                val songs = DiscoveryApi.fetchChartSongs(chart)
                _chartSongs.value = songs
                Log.d(TAG, "Loaded ${songs.size} songs for chart: ${chart.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load chart songs", e)
                _chartSongsError.value = e.message ?: "加载失败"
            } finally {
                _chartSongsLoading.value = false
            }
        }
    }

    fun loadCategories() {
        _categories.value = DiscoveryApi.fetchCategories()
        if (_selectedCategory.value == null && _categories.value.isNotEmpty()) {
            selectCategory(_categories.value.first())
        }
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
        loadCategoryPlaylists(category)
    }

    private fun loadCategoryPlaylists(category: String) {
        _browseLoading.value = true
        _browseError.value = null

        viewModelScope.launch {
            try {
                val playlists = DiscoveryApi.fetchCategoryPlaylists(category)
                _browsePlaylists.value = playlists
                Log.d(TAG, "Loaded ${playlists.size} playlists for category: $category")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load category playlists", e)
                _browseError.value = e.message ?: "加载失败"
            } finally {
                _browseLoading.value = false
            }
        }
    }

    fun loadBrowsePlaylistSongs(platform: String, playlistId: String) {
        if (_browsePlaylistSongsLoading.value) return
        _browsePlaylistSongsLoading.value = true
        _browsePlaylistSongsError.value = null
        _browsePlaylistSongs.value = emptyList()

        viewModelScope.launch {
            try {
                val playlist = DiscoveryApi.fetchPlaylistSongs(platform, playlistId)
                _browsePlaylistSongs.value = playlist?.songs ?: emptyList()
                Log.d(TAG, "Loaded ${_browsePlaylistSongs.value.size} songs for playlist: $playlistId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load playlist songs", e)
                _browsePlaylistSongsError.value = e.message ?: "加载失败"
            } finally {
                _browsePlaylistSongsLoading.value = false
            }
        }
    }

    // --- Recommendations ---
    private val _neteaseRecs = MutableStateFlow<List<ParsedSong>>(emptyList())
    val neteaseRecs: StateFlow<List<ParsedSong>> = _neteaseRecs

    private val _qqmusicRecs = MutableStateFlow<List<ParsedSong>>(emptyList())
    val qqmusicRecs: StateFlow<List<ParsedSong>> = _qqmusicRecs

    private val _recsLoading = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val recsLoading: StateFlow<Map<String, Boolean>> = _recsLoading

    private val _recsError = MutableStateFlow<Map<String, String?>>(emptyMap())
    val recsError: StateFlow<Map<String, String?>> = _recsError

    fun isLoggedIn(platform: String): Boolean = authManager.isLoggedIn(platform)

    fun onLoginSuccess(platform: String) {
        loadRecommendations(platform)
    }

    fun logout(platform: String) {
        authManager.logout(platform)
        when (platform) {
            Platforms.NETEASE -> _neteaseRecs.value = emptyList()
            Platforms.QQMUSIC -> _qqmusicRecs.value = emptyList()
        }
    }

    fun loadRecommendations(platform: String) {
        val cookies = authManager.getCookies(platform) ?: return

        _recsLoading.value = _recsLoading.value + (platform to true)
        _recsError.value = _recsError.value + (platform to null)

        viewModelScope.launch {
            try {
                val songs = DiscoveryApi.fetchRecommendations(platform, cookies)
                when (platform) {
                    Platforms.NETEASE -> _neteaseRecs.value = songs
                    Platforms.QQMUSIC -> _qqmusicRecs.value = songs
                }
                Log.d(TAG, "Loaded ${songs.size} recommendations for $platform")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load recommendations for $platform", e)
                _recsError.value = _recsError.value + (platform to (e.message ?: "加载失败"))
            } finally {
                _recsLoading.value = _recsLoading.value + (platform to false)
            }
        }
    }

    fun loadAllRecommendations() {
        for (platform in PlatformAuthManager.SUPPORTED_PLATFORMS) {
            if (authManager.isLoggedIn(platform)) {
                loadRecommendations(platform)
            }
        }
    }

    /**
     * Convert a list of ParsedSong to Song entities for the playback queue.
     * Inserts songs that don't exist yet, reuses existing ones.
     */
    suspend fun parsedSongsToSongs(parsedSongs: List<ParsedSong>): List<Song> {
        return parsedSongs.map { parsed ->
            val existing = repository.getSongByPlatformId(parsed.platform, parsed.platformSongId)
            if (existing != null) {
                existing
            } else {
                val song = Song(
                    title = parsed.title.ifEmpty { parsed.platformSongId },
                    artist = parsed.artist,
                    album = parsed.album,
                    platform = parsed.platform,
                    platformSongId = parsed.platformSongId,
                    deepLink = parsed.deepLink,
                    sourceUrl = parsed.sourceUrl,
                    coverUrl = parsed.coverUrl
                )
                val songId = repository.insertSong(song)
                song.copy(id = songId)
            }
        }
    }

    /**
     * Add a discovered song to the user's library.
     * Returns true if added, false if already exists.
     */
    suspend fun addSongToLibrary(parsedSong: ParsedSong): Boolean {
        val existing = repository.getSongByPlatformId(parsedSong.platform, parsedSong.platformSongId)
        if (existing != null) return false

        val song = Song(
            title = parsedSong.title.ifEmpty { parsedSong.platformSongId },
            artist = parsedSong.artist,
            album = parsedSong.album,
            platform = parsedSong.platform,
            platformSongId = parsedSong.platformSongId,
            deepLink = parsedSong.deepLink,
            sourceUrl = parsedSong.sourceUrl,
            coverUrl = parsedSong.coverUrl
        )
        repository.insertSong(song)
        return true
    }

    /**
     * Add a discovered song to a specific playlist.
     * Inserts to library first if needed.
     */
    suspend fun addSongToPlaylist(parsedSong: ParsedSong, playlistId: Long) {
        var existing = repository.getSongByPlatformId(parsedSong.platform, parsedSong.platformSongId)
        if (existing == null) {
            val song = Song(
                title = parsedSong.title.ifEmpty { parsedSong.platformSongId },
                artist = parsedSong.artist,
                album = parsedSong.album,
                platform = parsedSong.platform,
                platformSongId = parsedSong.platformSongId,
                deepLink = parsedSong.deepLink,
                sourceUrl = parsedSong.sourceUrl,
                coverUrl = parsedSong.coverUrl
            )
            val songId = repository.insertSong(song)
            existing = song.copy(id = songId)
        }
        repository.addSongToPlaylist(playlistId, existing.id)
    }

    companion object {
        private const val TAG = "DiscoverViewModel"

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = MusicHubApplication.getInstance()
                val repository = app.repository
                val authManager = PlatformAuthManager(app)
                return DiscoverViewModel(repository, authManager) as T
            }
        }
    }
}
