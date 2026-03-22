package com.musichub.data.local

import androidx.room.*
import com.musichub.data.model.SyncSource
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncSourceDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(syncSource: SyncSource): Long

    @Delete
    suspend fun delete(syncSource: SyncSource)

    @Query("DELETE FROM sync_sources WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT * FROM sync_sources WHERE playlist_id = :playlistId ORDER BY created_at ASC")
    fun getByPlaylistId(playlistId: Long): Flow<List<SyncSource>>

    @Query("SELECT * FROM sync_sources WHERE playlist_id = :playlistId ORDER BY created_at ASC")
    suspend fun getByPlaylistIdList(playlistId: Long): List<SyncSource>

    @Query("SELECT DISTINCT playlist_id FROM sync_sources")
    suspend fun getAllSyncedPlaylistIds(): List<Long>

    @Query("""
        UPDATE sync_sources
        SET last_sync_at = :syncAt, last_sync_status = :status, last_sync_error = :error
        WHERE id = :id
    """)
    suspend fun updateSyncStatus(id: Long, syncAt: Long, status: String, error: String = "")

    @Query("SELECT * FROM sync_sources WHERE id = :id")
    suspend fun getById(id: Long): SyncSource?
}
