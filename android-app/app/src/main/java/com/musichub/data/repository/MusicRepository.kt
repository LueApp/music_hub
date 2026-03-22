package com.musichub.data.repository

import com.musichub.data.local.MusicHubDatabase
import com.musichub.data.model.Playlist
import com.musichub.data.model.PlaylistItem
import com.musichub.data.model.Song
import com.musichub.data.model.SyncSource
import kotlinx.coroutines.flow.Flow

class MusicRepository(private val database: MusicHubDatabase) {

    // Songs
    fun getAllSongs(): Flow<List<Song>> = database.songDao().getAllSongs()

    fun getRecentSongs(limit: Int = 10): Flow<List<Song>> =
        database.songDao().getRecentSongs(limit)

    fun searchSongs(query: String): Flow<List<Song>> =
        database.songDao().searchSongs(query)

    fun getSongsByPlatform(platform: String): Flow<List<Song>> =
        database.songDao().getSongsByPlatform(platform)

    suspend fun getSongByPlatformId(platform: String, platformSongId: String): Song? =
        database.songDao().getByPlatformId(platform, platformSongId)

    suspend fun insertSong(song: Song): Long =
        database.songDao().insert(song)

    suspend fun deleteSong(song: Song) =
        database.songDao().delete(song)

    suspend fun deleteAllSongs(): Int =
        database.songDao().deleteAll()

    fun getSongCountByPlatform(platform: String): Flow<Int> =
        database.songDao().getCountByPlatform(platform)

    // Playlists
    fun getAllPlaylists(): Flow<List<Playlist>> =
        database.playlistDao().getAllPlaylists()

    fun getPlaylistById(id: Long): Flow<Playlist?> =
        database.playlistDao().getPlaylistById(id)

    suspend fun insertPlaylist(playlist: Playlist): Long =
        database.playlistDao().insert(playlist)

    suspend fun updatePlaylist(playlist: Playlist) =
        database.playlistDao().update(playlist)

    suspend fun deletePlaylist(playlist: Playlist) =
        database.playlistDao().delete(playlist)

    // Playlist Items
    fun getSongsInPlaylist(playlistId: Long): Flow<List<Song>> =
        database.playlistItemDao().getSongsInPlaylist(playlistId)

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) =
        database.playlistItemDao().addSongToPlaylist(playlistId, songId)

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) =
        database.playlistItemDao().removeSongFromPlaylist(playlistId, songId)

    suspend fun reorderSong(playlistId: Long, songId: Long, newPosition: Int) =
        database.playlistItemDao().reorderSong(playlistId, songId, newPosition)

    fun getPlaylistSongCount(playlistId: Long): Flow<Int> =
        database.playlistItemDao().getSongCount(playlistId)

    fun searchSongsInPlaylist(query: String, playlistId: Long): Flow<List<Song>> =
        database.playlistItemDao().searchSongsInPlaylist(query, playlistId)

    fun getSongsInPlaylistByPlatform(playlistId: Long, platform: String): Flow<List<Song>> =
        database.playlistItemDao().getSongsInPlaylistByPlatform(playlistId, platform)

    fun searchSongsInPlaylistByPlatform(query: String, playlistId: Long, platform: String): Flow<List<Song>> =
        database.playlistItemDao().searchSongsInPlaylistByPlatform(query, playlistId, platform)

    // Import from library
    fun getSongsNotInPlaylist(playlistId: Long): Flow<List<Song>> =
        database.songDao().getSongsNotInPlaylist(playlistId)

    fun searchSongsNotInPlaylist(query: String, playlistId: Long): Flow<List<Song>> =
        database.songDao().searchSongsNotInPlaylist(query, playlistId)

    fun getSongsByPlatformNotInPlaylist(platform: String, playlistId: Long): Flow<List<Song>> =
        database.songDao().getSongsByPlatformNotInPlaylist(platform, playlistId)

    fun searchSongsByPlatformNotInPlaylist(query: String, platform: String, playlistId: Long): Flow<List<Song>> =
        database.songDao().searchSongsByPlatformNotInPlaylist(query, platform, playlistId)

    suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>, syncSourceId: Long? = null) =
        database.playlistItemDao().addSongsToPlaylist(playlistId, songIds, syncSourceId)

    // Sync Sources
    fun getSyncSourcesForPlaylist(playlistId: Long): Flow<List<SyncSource>> =
        database.syncSourceDao().getByPlaylistId(playlistId)

    suspend fun getSyncSourcesForPlaylistList(playlistId: Long): List<SyncSource> =
        database.syncSourceDao().getByPlaylistIdList(playlistId)

    suspend fun addSyncSource(syncSource: SyncSource): Long =
        database.syncSourceDao().insert(syncSource)

    suspend fun removeSyncSource(id: Long) =
        database.syncSourceDao().deleteById(id)

    suspend fun updateSyncStatus(id: Long, syncAt: Long, status: String, error: String = "") =
        database.syncSourceDao().updateSyncStatus(id, syncAt, status, error)

    suspend fun getAllSyncedPlaylistIds(): List<Long> =
        database.syncSourceDao().getAllSyncedPlaylistIds()

    suspend fun getPlaylistItemsBySyncSource(syncSourceId: Long): List<PlaylistItem> =
        database.playlistItemDao().getItemsBySyncSource(syncSourceId)

    suspend fun getSyncedItemsForPlaylist(playlistId: Long): List<PlaylistItem> =
        database.playlistItemDao().getSyncedItemsForPlaylist(playlistId)

    suspend fun removeSyncSourceAndItems(syncSourceId: Long) {
        database.playlistItemDao().deleteItemsBySyncSource(syncSourceId)
        database.syncSourceDao().deleteById(syncSourceId)
    }

    suspend fun addSongToPlaylistWithSync(playlistId: Long, songId: Long, syncSourceId: Long? = null) =
        database.playlistItemDao().addSongToPlaylist(playlistId, songId, syncSourceId)

    suspend fun getSongsForPlaylistList(playlistId: Long): List<Song> =
        database.playlistItemDao().getSongsForPlaylistList(playlistId)
}
