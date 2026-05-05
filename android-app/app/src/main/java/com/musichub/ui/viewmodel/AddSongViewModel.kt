package com.musichub.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musichub.MusicHubApplication
import com.musichub.data.model.ParsedSong
import com.musichub.data.model.Playlist
import com.musichub.data.model.Song
import com.musichub.data.repository.MusicRepository
import com.musichub.platform.LinkParser
import com.musichub.platform.ParsedPlaylist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddSongUiState(
    val isLoading: Boolean = false,
    val parsedSong: ParsedSong? = null,
    val parsedPlaylist: ParsedPlaylist? = null,
    val isPlaylist: Boolean = false,
    val error: String? = null,
    val addSuccess: Boolean = false,
    val importProgress: Int = 0,
    val importTotal: Int = 0,
    val existingPlaylists: List<Playlist> = emptyList(),
    val selectedPlaylistId: Long? = null  // null = create new, >0 = import to existing
)

class AddSongViewModel(
    private val repository: MusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddSongUiState())
    val uiState: StateFlow<AddSongUiState> = _uiState.asStateFlow()

    init {
        // Load existing playlists
        loadExistingPlaylists()
    }

    private fun loadExistingPlaylists() {
        viewModelScope.launch {
            try {
                repository.getAllPlaylists().collect { playlists ->
                    _uiState.update { it.copy(existingPlaylists = playlists) }
                }
            } catch (e: Exception) {
                // Ignore error loading playlists
            }
        }
    }

    fun selectTargetPlaylist(playlistId: Long?) {
        _uiState.update { it.copy(selectedPlaylistId = playlistId) }
    }

    fun parseLink(link: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, parsedSong = null, parsedPlaylist = null, isPlaylist = false, addSuccess = false) }

            try {
                // First check if it's a playlist URL
                val isPlaylist = LinkParser.isPlaylistUrl(link)

                if (isPlaylist) {
                    val parsedPlaylist = LinkParser.parsePlaylistUrl(link)
                    if (parsedPlaylist != null) {
                        _uiState.update { it.copy(isLoading = false, parsedPlaylist = parsedPlaylist, isPlaylist = true) }
                    } else {
                        _uiState.update { it.copy(isLoading = false, error = "无法解析歌单") }
                    }
                } else {
                    val parsedSong = LinkParser.parseSharedContent(link)
                    if (parsedSong != null) {
                        _uiState.update { it.copy(isLoading = false, parsedSong = parsedSong, isPlaylist = false) }
                    } else {
                        _uiState.update { it.copy(isLoading = false, error = "无法解析链接") }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "解析失败: ${e.message}") }
            }
        }
    }

    fun addSongToLibrary(playlistId: Long? = null) {
        val parsedSong = _uiState.value.parsedSong ?: return

        viewModelScope.launch {
            try {
                // Check if song already exists
                val existing = repository.getSongByPlatformId(
                    parsedSong.platform,
                    parsedSong.platformSongId
                )

                val songId = if (existing != null) {
                    existing.id
                } else {
                    // Insert new song
                    val song = Song(
                        title = parsedSong.title,
                        artist = parsedSong.artist,
                        album = parsedSong.album,
                        platform = parsedSong.platform,
                        platformSongId = parsedSong.platformSongId,
                        deepLink = parsedSong.deepLink,
                        sourceUrl = parsedSong.sourceUrl,
                        coverUrl = parsedSong.coverUrl
                    )
                    repository.insertSong(song)
                }

                // Add to playlist if specified
                if (playlistId != null && playlistId > 0) {
                    repository.addSongToPlaylist(playlistId, songId)
                }

                _uiState.update { it.copy(addSuccess = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "添加失败: ${e.message}") }
            }
        }
    }

    fun importPlaylist() {
        val parsedPlaylist = _uiState.value.parsedPlaylist ?: return
        val targetPlaylistId = _uiState.value.selectedPlaylistId

        viewModelScope.launch {
            try {
                val total = parsedPlaylist.songs.size
                _uiState.update { it.copy(importProgress = 0, importTotal = total) }

                // Either create new playlist or use existing one
                val playlistId = if (targetPlaylistId != null && targetPlaylistId > 0) {
                    // Import to existing playlist
                    targetPlaylistId
                } else {
                    // Create new playlist
                    val playlist = Playlist(
                        name = parsedPlaylist.name.ifEmpty { "导入的歌单" },
                        description = parsedPlaylist.description,
                        coverPath = parsedPlaylist.coverUrl
                    )
                    repository.insertPlaylist(playlist)
                }

                // Import songs
                var importedCount = 0
                for (parsedSong in parsedPlaylist.songs) {
                    try {
                        // Check if song already exists
                        val existing = repository.getSongByPlatformId(
                            parsedSong.platform,
                            parsedSong.platformSongId
                        )

                        val songId = if (existing != null) {
                            existing.id
                        } else {
                            // Insert new song
                            val song = Song(
                                title = parsedSong.title,
                                artist = parsedSong.artist,
                                album = parsedSong.album,
                                platform = parsedSong.platform,
                                platformSongId = parsedSong.platformSongId,
                                deepLink = parsedSong.deepLink,
                                sourceUrl = parsedSong.fallbackUrl,
                                coverUrl = parsedSong.coverUrl
                            )
                            repository.insertSong(song)
                        }

                        // Add to playlist
                        repository.addSongToPlaylist(playlistId, songId)
                        importedCount++
                        _uiState.update { it.copy(importProgress = importedCount) }
                    } catch (e: Exception) {
                        // Continue with next song on error
                    }
                }

                _uiState.update { it.copy(addSuccess = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "导入失败: ${e.message}") }
            }
        }
    }

    fun reset() {
        _uiState.value = AddSongUiState()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = MusicHubApplication.getInstance().repository
                return AddSongViewModel(repository) as T
            }
        }
    }
}
