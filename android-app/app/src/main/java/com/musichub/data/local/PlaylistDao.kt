package com.musichub.data.local

import androidx.room.*
import com.musichub.data.model.Playlist
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: Playlist): Long

    @Update
    suspend fun update(playlist: Playlist)

    @Delete
    suspend fun delete(playlist: Playlist)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deleteById(playlistId: Long): Int

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getById(playlistId: Long): Playlist?

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    fun getPlaylistById(playlistId: Long): Flow<Playlist?>

    @Query("SELECT * FROM playlists ORDER BY updated_at DESC")
    fun getAllPlaylists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists ORDER BY updated_at DESC")
    suspend fun getAllPlaylistsList(): List<Playlist>

    @Query("UPDATE playlists SET updated_at = :timestamp WHERE id = :playlistId")
    suspend fun updateTimestamp(playlistId: Long, timestamp: Long = System.currentTimeMillis())
}
