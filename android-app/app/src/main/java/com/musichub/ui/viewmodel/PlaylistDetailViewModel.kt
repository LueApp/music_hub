package com.musichub.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musichub.MusicHubApplication
import com.musichub.data.model.Playlist
import com.musichub.data.model.Song
import com.musichub.data.model.SyncSource
import com.musichub.data.repository.MusicRepository
import com.musichub.sync.PlaylistSyncEngine
import com.musichub.sync.SyncResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistDetailViewModel(
    private val playlistId: Long,
    private val repository: MusicRepository
) : ViewModel() {

    val playlist: StateFlow<Playlist?> = repository.getPlaylistById(playlistId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _searchQuery = MutableStateFlow("")
    private val _platformFilter = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val songs: StateFlow<List<Song>> = combine(
        _searchQuery,
        _platformFilter
    ) { query, platform ->
        Pair(query, platform)
    }.flatMapLatest { (query, platform) ->
        when {
            query.isNotEmpty() && platform != null ->
                repository.searchSongsInPlaylistByPlatform(query, playlistId, platform)
            query.isNotEmpty() ->
                repository.searchSongsInPlaylist(query, playlistId)
            platform != null ->
                repository.getSongsInPlaylistByPlatform(playlistId, platform)
            else ->
                repository.getSongsInPlaylist(playlistId)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val syncSources: StateFlow<List<SyncSource>> = repository.getSyncSourcesForPlaylist(playlistId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncResult = MutableStateFlow<SyncResult?>(null)
    val syncResult: StateFlow<SyncResult?> = _syncResult.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setPlatformFilter(platform: String?) {
        _platformFilter.value = platform
    }

    fun removeSongFromPlaylist(songId: Long) {
        viewModelScope.launch {
            repository.removeSongFromPlaylist(playlistId, songId)
        }
    }

    fun reorderSong(songId: Long, newPosition: Int) {
        viewModelScope.launch {
            repository.reorderSong(playlistId, songId, newPosition)
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncResult.value = null
            try {
                val engine = PlaylistSyncEngine(repository)
                val result = engine.syncPlaylist(playlistId)
                _syncResult.value = result
            } catch (e: Exception) {
                _syncResult.value = SyncResult(playlistId, errors = listOf(e.message ?: "Unknown error"))
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun clearSyncResult() {
        _syncResult.value = null
    }

    /**
     * Format the sync status text for display.
     */
    fun formatSyncStatus(sources: List<SyncSource>): String {
        if (sources.isEmpty()) return ""

        val latestSync = sources.maxByOrNull { it.lastSyncAt }
        val timeText = if (latestSync == null || latestSync.lastSyncAt == 0L) {
            "从未同步"
        } else {
            formatRelativeTime(latestSync.lastSyncAt)
        }

        val sourceCount = sources.size
        return "$timeText · ${sourceCount}个同步源"
    }

    private fun formatRelativeTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val minutes = diff / (1000 * 60)
        val hours = minutes / 60
        val days = hours / 24

        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            else -> "${days}天前"
        }
    }

    class Factory(private val playlistId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repository = MusicHubApplication.getInstance().repository
            return PlaylistDetailViewModel(playlistId, repository) as T
        }
    }
}
