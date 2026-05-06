package com.musichub.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.musichub.data.model.Playlist
import com.musichub.data.model.PlaylistItem
import com.musichub.data.model.Song
import com.musichub.data.model.SkipLogEntry
import com.musichub.data.model.SyncSource

@Database(
    entities = [Song::class, Playlist::class, PlaylistItem::class, SyncSource::class, SkipLogEntry::class],
    version = 4,
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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN custom_duration_ms INTEGER")
            }
        }

        fun getDatabase(context: Context): MusicHubDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicHubDatabase::class.java,
                    "musichub.db"
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
