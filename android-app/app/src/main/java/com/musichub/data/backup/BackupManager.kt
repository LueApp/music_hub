package com.musichub.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.musichub.data.local.MusicHubDatabase
import com.musichub.data.model.Playlist
import com.musichub.data.model.PlaylistItem
import com.musichub.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

private const val BACKUP_VERSION = 1

data class BackupSong(
    val title: String,
    val artist: String,
    val album: String,
    val platform: String,
    val platformSongId: String,
    val deepLink: String,
    val sourceUrl: String,
    val coverUrl: String,
    val createdAt: Long,
    val customDurationMs: Long? = null
)

data class BackupPlaylistItem(
    val platform: String,
    val platformSongId: String,
    val position: Int,
    val addedAt: Long
)

data class BackupPlaylist(
    val name: String,
    val description: String,
    val coverPath: String,
    val createdAt: Long,
    val updatedAt: Long,
    val syncIntervalMinutes: Long,
    val items: List<BackupPlaylistItem>
)

data class BackupFile(
    val version: Int,
    val exportedAt: Long,
    val songs: List<BackupSong>,
    val playlists: List<BackupPlaylist>
)

data class ExportResult(val songCount: Int, val playlistCount: Int)

data class ImportResult(
    val songsAdded: Int,
    val songsExisting: Int,
    val playlistsAdded: Int,
    val itemsAdded: Int,
    val itemsSkipped: Int
)

class BackupManager(private val database: MusicHubDatabase) {

    private val gson = Gson()

    suspend fun export(context: Context, uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        val songs = database.songDao().getAllSongsList()
        val playlists = database.playlistDao().getAllPlaylistsList()

        val songIdToKey = songs.associate { it.id to (it.platform to it.platformSongId) }

        val backupSongs = songs.map { it.toBackup() }
        val backupPlaylists = playlists.map { playlist ->
            val items = database.playlistItemDao()
                .getItemsForPlaylistList(playlist.id)
                .mapNotNull { item ->
                    val key = songIdToKey[item.songId] ?: return@mapNotNull null
                    BackupPlaylistItem(
                        platform = key.first,
                        platformSongId = key.second,
                        position = item.position,
                        addedAt = item.addedAt
                    )
                }
            playlist.toBackup(items)
        }

        val backup = BackupFile(
            version = BACKUP_VERSION,
            exportedAt = System.currentTimeMillis(),
            songs = backupSongs,
            playlists = backupPlaylists
        )

        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.bufferedWriter().use { writer ->
                gson.toJson(backup, writer)
            }
        } ?: throw IllegalStateException("Cannot open output stream")

        ExportResult(songCount = backupSongs.size, playlistCount = backupPlaylists.size)
    }

    suspend fun import(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val backup: BackupFile = context.contentResolver.openInputStream(uri)?.use { stream ->
            try {
                gson.fromJson(InputStreamReader(stream), BackupFile::class.java)
            } catch (e: JsonSyntaxException) {
                throw IllegalArgumentException("无效的备份文件格式", e)
            }
        } ?: throw IllegalArgumentException("无效的备份文件格式")

        if (backup.version > BACKUP_VERSION) {
            throw IllegalArgumentException("备份文件版本过新，请升级应用")
        }

        var songsAdded = 0
        var songsExisting = 0
        var playlistsAdded = 0
        var itemsAdded = 0
        var itemsSkipped = 0

        database.withTransaction {
            val keyToLocalId = mutableMapOf<Pair<String, String>, Long>()

            for (backupSong in backup.songs) {
                val key = backupSong.platform to backupSong.platformSongId
                val existing = database.songDao().getByPlatformId(backupSong.platform, backupSong.platformSongId)
                if (existing != null) {
                    keyToLocalId[key] = existing.id
                    songsExisting++
                } else {
                    val newId = database.songDao().insert(backupSong.toEntity())
                    keyToLocalId[key] = newId
                    songsAdded++
                }
            }

            for (backupPlaylist in backup.playlists) {
                val newPlaylistId = database.playlistDao().insert(backupPlaylist.toEntity())
                playlistsAdded++

                for (item in backupPlaylist.items.sortedBy { it.position }) {
                    val songId = keyToLocalId[item.platform to item.platformSongId]
                    if (songId == null) {
                        itemsSkipped++
                        continue
                    }
                    database.playlistItemDao().insert(
                        PlaylistItem(
                            playlistId = newPlaylistId,
                            songId = songId,
                            position = item.position,
                            addedAt = item.addedAt
                        )
                    )
                    itemsAdded++
                }
            }
        }

        ImportResult(songsAdded, songsExisting, playlistsAdded, itemsAdded, itemsSkipped)
    }

    private fun Song.toBackup() = BackupSong(
        title = title,
        artist = artist,
        album = album,
        platform = platform,
        platformSongId = platformSongId,
        deepLink = deepLink,
        sourceUrl = sourceUrl,
        coverUrl = coverUrl,
        createdAt = createdAt,
        customDurationMs = customDurationMs
    )

    private fun BackupSong.toEntity() = Song(
        title = title,
        artist = artist,
        album = album,
        platform = platform,
        platformSongId = platformSongId,
        deepLink = deepLink,
        sourceUrl = sourceUrl,
        coverUrl = coverUrl,
        createdAt = createdAt,
        customDurationMs = customDurationMs
    )

    private fun Playlist.toBackup(items: List<BackupPlaylistItem>) = BackupPlaylist(
        name = name,
        description = description,
        coverPath = coverPath,
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncIntervalMinutes = syncIntervalMinutes,
        items = items
    )

    private fun BackupPlaylist.toEntity() = Playlist(
        name = name,
        description = description,
        coverPath = coverPath,
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncIntervalMinutes = syncIntervalMinutes
    )
}
