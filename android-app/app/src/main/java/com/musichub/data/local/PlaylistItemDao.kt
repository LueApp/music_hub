package com.musichub.data.local

import androidx.room.*
import com.musichub.data.model.PlaylistItem
import com.musichub.data.model.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PlaylistItem): Long

    @Delete
    suspend fun delete(item: PlaylistItem)

    @Query("DELETE FROM playlist_items WHERE playlist_id = :playlistId AND song_id = :songId")
    suspend fun deleteByIds(playlistId: Long, songId: Long): Int

    @Query("DELETE FROM playlist_items WHERE playlist_id = :playlistId")
    suspend fun deleteAllForPlaylist(playlistId: Long)

    @Query("SELECT * FROM playlist_items WHERE playlist_id = :playlistId ORDER BY position ASC")
    fun getItemsForPlaylist(playlistId: Long): Flow<List<PlaylistItem>>

    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN playlist_items ON songs.id = playlist_items.song_id
        WHERE playlist_items.playlist_id = :playlistId
        ORDER BY playlist_items.position ASC
    """)
    fun getSongsForPlaylist(playlistId: Long): Flow<List<Song>>

    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN playlist_items ON songs.id = playlist_items.song_id
        WHERE playlist_items.playlist_id = :playlistId
        ORDER BY playlist_items.position ASC
    """)
    suspend fun getSongsForPlaylistList(playlistId: Long): List<Song>

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlist_id = :playlistId")
    fun getSongCount(playlistId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlist_id = :playlistId")
    suspend fun getSongCountValue(playlistId: Long): Int

    @Query("SELECT MAX(position) FROM playlist_items WHERE playlist_id = :playlistId")
    suspend fun getMaxPosition(playlistId: Long): Int?

    @Query("UPDATE playlist_items SET position = :newPosition WHERE playlist_id = :playlistId AND song_id = :songId")
    suspend fun updatePosition(playlistId: Long, songId: Long, newPosition: Int)

    @Query("""
        UPDATE playlist_items
        SET position = position + 1
        WHERE playlist_id = :playlistId AND position >= :startPosition AND position < :endPosition
    """)
    suspend fun shiftPositionsUp(playlistId: Long, startPosition: Int, endPosition: Int)

    @Query("""
        UPDATE playlist_items
        SET position = position - 1
        WHERE playlist_id = :playlistId AND position > :startPosition AND position <= :endPosition
    """)
    suspend fun shiftPositionsDown(playlistId: Long, startPosition: Int, endPosition: Int)

    @Query("SELECT * FROM playlist_items WHERE playlist_id = :playlistId AND song_id = :songId")
    suspend fun getItem(playlistId: Long, songId: Long): PlaylistItem?

    @Transaction
    suspend fun addSongToPlaylist(playlistId: Long, songId: Long, syncSourceId: Long? = null): Long {
        val maxPosition = getMaxPosition(playlistId) ?: -1
        val item = PlaylistItem(
            playlistId = playlistId,
            songId = songId,
            position = maxPosition + 1,
            syncSourceId = syncSourceId
        )
        return insert(item)
    }

    fun getSongsInPlaylist(playlistId: Long): Flow<List<Song>> = getSongsForPlaylist(playlistId)

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long): Int = deleteByIds(playlistId, songId)

    @Transaction
    suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>, syncSourceId: Long? = null) {
        var position = (getMaxPosition(playlistId) ?: -1) + 1
        for (songId in songIds) {
            val item = PlaylistItem(
                playlistId = playlistId,
                songId = songId,
                position = position,
                syncSourceId = syncSourceId
            )
            insert(item)
            position++
        }
    }

    @Query("SELECT * FROM playlist_items WHERE sync_source_id = :syncSourceId")
    suspend fun getItemsBySyncSource(syncSourceId: Long): List<PlaylistItem>

    @Query("DELETE FROM playlist_items WHERE sync_source_id = :syncSourceId")
    suspend fun deleteItemsBySyncSource(syncSourceId: Long): Int

    @Query("""
        SELECT * FROM playlist_items
        WHERE playlist_id = :playlistId AND sync_source_id IS NOT NULL
    """)
    suspend fun getSyncedItemsForPlaylist(playlistId: Long): List<PlaylistItem>

    @Transaction
    suspend fun reorderSong(playlistId: Long, songId: Long, newPosition: Int) {
        val item = getItem(playlistId, songId) ?: return
        val oldPosition = item.position

        if (oldPosition == newPosition) return

        if (oldPosition < newPosition) {
            shiftPositionsDown(playlistId, oldPosition, newPosition)
        } else {
            shiftPositionsUp(playlistId, newPosition, oldPosition)
        }

        updatePosition(playlistId, songId, newPosition)
    }
}
