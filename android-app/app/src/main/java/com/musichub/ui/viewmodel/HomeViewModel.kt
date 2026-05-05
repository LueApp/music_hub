package com.musichub.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musichub.MusicHubApplication
import com.musichub.data.model.Song
import com.musichub.data.repository.MusicRepository
import com.musichub.platform.Platforms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: MusicRepository
) : ViewModel() {

    private val _recentSongs = MutableStateFlow<List<Song>>(emptyList())
    val recentSongs: StateFlow<List<Song>> = _recentSongs.asStateFlow()

    private val _platformCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val platformCounts: StateFlow<Map<String, Int>> = _platformCounts.asStateFlow()

    init {
        loadRecentSongs()
        loadPlatformCounts()
    }

    private fun loadRecentSongs() {
        viewModelScope.launch {
            repository.getRecentSongs(10).collect { songs ->
                _recentSongs.value = songs
            }
        }
    }

    private fun loadPlatformCounts() {
        viewModelScope.launch {
            repository.getSongCountByPlatform(Platforms.NETEASE).collect { neteaseCount ->
                val current = _platformCounts.value.toMutableMap()
                current[Platforms.NETEASE] = neteaseCount
                _platformCounts.value = current
            }
        }
        viewModelScope.launch {
            repository.getSongCountByPlatform(Platforms.QQMUSIC).collect { qqmusicCount ->
                val current = _platformCounts.value.toMutableMap()
                current[Platforms.QQMUSIC] = qqmusicCount
                _platformCounts.value = current
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = MusicHubApplication.getInstance().repository
                return HomeViewModel(repository) as T
            }
        }
    }
}
