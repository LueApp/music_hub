package com.musichub.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musichub.MusicHubApplication
import com.musichub.data.model.SyncSource
import com.musichub.data.repository.MusicRepository
import com.musichub.platform.LinkParser
import com.musichub.platform.Platforms
import com.musichub.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class AddSourceResult {
    data object Success : AddSourceResult()
    data class Error(val message: String) : AddSourceResult()
}

class ManageSourcesViewModel(
    private val playlistId: Long,
    private val repository: MusicRepository
) : ViewModel() {

    companion object {
        private const val TAG = "ManageSourcesVM"
    }

    val syncSources: StateFlow<List<SyncSource>> = repository.getSyncSourcesForPlaylist(playlistId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _addResult = MutableStateFlow<AddSourceResult?>(null)
    val addResult: StateFlow<AddSourceResult?> = _addResult.asStateFlow()

    private val _isAdding = MutableStateFlow(false)
    val isAdding: StateFlow<Boolean> = _isAdding.asStateFlow()

    fun addSourceFromUrl(url: String) {
        viewModelScope.launch {
            _isAdding.value = true
            _addResult.value = null

            try {
                val resolvedUrl = LinkParser.resolveShortUrl(url.trim())

                // Find matching platform handler
                var platform: String? = null
                var remotePlaylistId: String? = null
                var sourceUrl = resolvedUrl

                val handler = LinkParser.getHandler(Platforms.NETEASE)
                val qqHandler = LinkParser.getHandler(Platforms.QQMUSIC)

                if (handler != null && handler.canHandle(resolvedUrl)) {
                    val parsed = handler.parsePlaylistUrl(resolvedUrl)
                    if (parsed != null) {
                        platform = Platforms.NETEASE
                        remotePlaylistId = parsed.playlistId
                    }
                }

                if (platform == null && qqHandler != null && qqHandler.canHandle(resolvedUrl)) {
                    val parsed = qqHandler.parsePlaylistUrl(resolvedUrl)
                    if (parsed != null) {
                        platform = Platforms.QQMUSIC
                        remotePlaylistId = parsed.playlistId
                    }
                }

                // Check for Bilibili
                if (platform == null) {
                    val biliHandler = LinkParser.getHandler(Platforms.BILIBILI)
                    if (biliHandler != null && biliHandler.canHandle(resolvedUrl)) {
                        val parsed = biliHandler.parsePlaylistUrl(resolvedUrl)
                        if (parsed != null) {
                            platform = Platforms.BILIBILI
                            remotePlaylistId = parsed.playlistId
                        }
                    }
                }

                if (platform == null || remotePlaylistId == null) {
                    _addResult.value = AddSourceResult.Error("不支持此链接作为同步源")
                    _isAdding.value = false
                    return@launch
                }

                val syncSource = SyncSource(
                    playlistId = playlistId,
                    platform = platform,
                    remotePlaylistId = remotePlaylistId,
                    sourceUrl = sourceUrl
                )

                val id = repository.addSyncSource(syncSource)
                if (id == -1L) {
                    _addResult.value = AddSourceResult.Error("此同步源已存在")
                } else {
                    // Schedule sync now that we have sources
                    val app = MusicHubApplication.getInstance()
                    SyncScheduler.schedulePeriodicSync(app)
                    _addResult.value = AddSourceResult.Success
                }

                Log.d(TAG, "Added sync source: $platform/$remotePlaylistId -> id=$id")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add source: ${e.message}", e)
                _addResult.value = AddSourceResult.Error("添加失败: ${e.message}")
            } finally {
                _isAdding.value = false
            }
        }
    }

    fun removeSource(source: SyncSource) {
        viewModelScope.launch {
            try {
                repository.removeSyncSourceAndItems(source.id)
                Log.d(TAG, "Removed sync source ${source.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove source: ${e.message}", e)
            }
        }
    }

    fun clearAddResult() {
        _addResult.value = null
    }

    class Factory(private val playlistId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repository = MusicHubApplication.getInstance().repository
            return ManageSourcesViewModel(playlistId, repository) as T
        }
    }
}
