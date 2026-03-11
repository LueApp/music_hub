package com.musichub.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musichub.MusicHubApplication
import com.musichub.data.model.Playlist
import com.musichub.data.model.Song
import com.musichub.data.repository.MusicRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    val songs: StateFlow<List<Song>> = repository.getSongsInPlaylist(playlistId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

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

    class Factory(private val playlistId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repository = MusicHubApplication.getInstance().repository
            return PlaylistDetailViewModel(playlistId, repository) as T
        }
    }
}
