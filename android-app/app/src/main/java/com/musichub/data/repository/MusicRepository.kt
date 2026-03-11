package com.musichub.data.repository

import com.musichub.data.local.MusicHubDatabase
import com.musichub.data.model.Playlist
import com.musichub.data.model.Song
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
}
