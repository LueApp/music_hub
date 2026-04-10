package com.musichub.data.local

import androidx.room.*
import com.musichub.data.model.SkipLogEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface SkipLogDao {

    @Query("SELECT * FROM skip_log ORDER BY timestamp DESC")
    fun getAll(): Flow<List<SkipLogEntry>>

    @Insert
    suspend fun insert(entry: SkipLogEntry)

    @Query("DELETE FROM skip_log")
    suspend fun deleteAll()
}
