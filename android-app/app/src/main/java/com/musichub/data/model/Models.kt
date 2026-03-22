package com.musichub.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Song entity representing a song in the user's library.
 */
@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["platform"]),
        Index(value = ["title"]),
        Index(value = ["platform", "platform_song_id"], unique = true)
    ]
)
data class Song(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String = "",
    val platform: String, // 'netease' or 'qqmusic'
    @ColumnInfo(name = "platform_song_id")
    val platformSongId: String,
    @ColumnInfo(name = "deep_link")
    val deepLink: String,
    @ColumnInfo(name = "source_url")
    val sourceUrl: String = "",
    @ColumnInfo(name = "cover_url")
    val coverUrl: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Playlist entity.
 */
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    @ColumnInfo(name = "cover_path")
    val coverPath: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "sync_interval_minutes")
    val syncIntervalMinutes: Long = 360
)

/**
 * Junction table for playlist-song relationship.
 */
@Entity(
    tableName = "playlist_items",
    indices = [
        Index(value = ["playlist_id", "position"]),
        Index(value = ["playlist_id", "song_id"], unique = true)
    ]
)
data class PlaylistItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    @ColumnInfo(name = "song_id")
    val songId: Long,
    val position: Int,
    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "sync_source_id")
    val syncSourceId: Long? = null
)

/**
 * Parsed song data from URL parsing.
 */
data class ParsedSong(
    val platform: String,
    val platformSongId: String,
    val deepLink: String,
    val fallbackUrl: String,
    val sourceUrl: String = "",
    var title: String = "",
    var artist: String = "",
    var album: String = "",
    var coverUrl: String = ""
)

/**
 * Playlist with song count (for UI display).
 */
data class PlaylistWithCount(
    val playlist: Playlist,
    val songCount: Int
)

/**
 * Sync source entity linking a playlist to a remote source playlist.
 * A playlist can have multiple sync sources from different platforms.
 */
@Entity(
    tableName = "sync_sources",
    indices = [
        Index(value = ["playlist_id"]),
        Index(value = ["playlist_id", "platform", "remote_playlist_id"], unique = true)
    ]
)
data class SyncSource(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    val platform: String,
    @ColumnInfo(name = "remote_playlist_id")
    val remotePlaylistId: String,
    @ColumnInfo(name = "source_url")
    val sourceUrl: String,
    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Long = 0,
    @ColumnInfo(name = "last_sync_status")
    val lastSyncStatus: String = "never",  // "never", "success", "error"
    @ColumnInfo(name = "last_sync_error")
    val lastSyncError: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
