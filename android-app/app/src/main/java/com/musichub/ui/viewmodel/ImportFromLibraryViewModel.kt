package com.musichub.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musichub.MusicHubApplication
import com.musichub.data.model.Song
import com.musichub.data.repository.MusicRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ImportFromLibraryViewModel(
    private val playlistId: Long,
    private val repository: MusicRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _platformFilter = MutableStateFlow<String?>(null)
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _importResult = MutableSharedFlow<Int>()

    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()
    val importResult: SharedFlow<Int> = _importResult.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val songs: StateFlow<List<Song>> = combine(
        _searchQuery,
        _platformFilter
    ) { query, platform ->
        Pair(query, platform)
    }.flatMapLatest { (query, platform) ->
        when {
            query.isNotEmpty() && platform != null ->
                repository.searchSongsByPlatformNotInPlaylist(query, platform, playlistId)
            query.isNotEmpty() ->
                repository.searchSongsNotInPlaylist(query, playlistId)
            platform != null ->
                repository.getSongsByPlatformNotInPlaylist(platform, playlistId)
            else ->
                repository.getSongsNotInPlaylist(playlistId)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setPlatformFilter(platform: String?) {
        _platformFilter.value = platform
    }

    fun setSelectedIds(ids: Set<Long>) {
        _selectedIds.value = ids
    }

    fun selectAll(songs: List<Song>) {
        _selectedIds.value = songs.map { it.id }.toSet()
    }

    fun deselectAll() {
        _selectedIds.value = emptySet()
    }

    fun importSelected() {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return

        viewModelScope.launch {
            repository.addSongsToPlaylist(playlistId, ids)
            _importResult.emit(ids.size)
        }
    }

    class Factory(private val playlistId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repository = MusicHubApplication.getInstance().repository
            return ImportFromLibraryViewModel(playlistId, repository) as T
        }
    }
}
