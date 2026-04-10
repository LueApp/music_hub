package com.musichub.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.musichub.data.model.Playlist
import com.musichub.data.model.PlaylistItem
import com.musichub.data.model.Song
import com.musichub.data.model.SkipLogEntry
import com.musichub.data.model.SyncSource

@Database(
    entities = [Song::class, Playlist::class, PlaylistItem::class, SyncSource::class, SkipLogEntry::class],
    version = 3,
    exportSchema = false
)
abstract class MusicHubDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistItemDao(): PlaylistItemDao
    abstract fun syncSourceDao(): SyncSourceDao
    abstract fun skipLogDao(): SkipLogDao

    companion object {
        @Volatile
        private var INSTANCE: MusicHubDatabase? = null

        fun getDatabase(context: Context): MusicHubDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicHubDatabase::class.java,
                    "musichub.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
