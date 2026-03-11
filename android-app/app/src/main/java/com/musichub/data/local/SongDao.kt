package com.musichub.data.local

import androidx.room.*
import com.musichub.data.model.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(song: Song): Long

    @Update
    suspend fun update(song: Song)

    @Delete
    suspend fun delete(song: Song)

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteById(songId: Long): Int

    @Query("SELECT * FROM songs WHERE id = :songId")
    suspend fun getById(songId: Long): Song?

    @Query("SELECT * FROM songs WHERE platform = :platform AND platform_song_id = :platformSongId")
    suspend fun getByPlatformId(platform: String, platformSongId: String): Song?

    @Query("SELECT * FROM songs ORDER BY created_at DESC")
    fun getAllSongs(): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY created_at DESC")
    suspend fun getAllSongsList(): List<Song>

    @Query("SELECT * FROM songs ORDER BY created_at DESC LIMIT :limit")
    fun getRecentSongs(limit: Int): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' ORDER BY created_at DESC")
    fun searchSongs(query: String): Flow<List<Song>>

    @Query("UPDATE songs SET cover_url = :coverUrl WHERE id = :songId")
    suspend fun updateCoverUrl(songId: Long, coverUrl: String): Int

    @Query("SELECT * FROM songs WHERE cover_url = '' OR cover_url IS NULL")
    suspend fun getSongsWithoutCover(): List<Song>

    @Query("SELECT * FROM songs WHERE platform = :platform ORDER BY created_at DESC")
    fun getSongsByPlatform(platform: String): Flow<List<Song>>

    @Query("SELECT COUNT(*) FROM songs WHERE platform = :platform")
    fun getCountByPlatform(platform: String): Flow<Int>
}
