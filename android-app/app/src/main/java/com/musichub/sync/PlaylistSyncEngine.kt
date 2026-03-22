package com.musichub.sync

import android.util.Log
import com.musichub.data.model.Song
import com.musichub.data.model.SyncSource
import com.musichub.data.repository.MusicRepository
import com.musichub.platform.LinkParser

/**
 * Result of syncing a single playlist.
 */
data class SyncResult(
    val playlistId: Long,
    val added: Int = 0,
    val removed: Int = 0,
    val errors: List<String> = emptyList()
)

/**
 * Core sync engine that fetches remote playlist songs and diffs against local state.
 */
class PlaylistSyncEngine(private val repository: MusicRepository) {

    companion object {
        private const val TAG = "PlaylistSyncEngine"
    }

    /**
     * Sync a single playlist by fetching all its sync sources and applying diffs.
     */
    suspend fun syncPlaylist(playlistId: Long): SyncResult {
        val sources = repository.getSyncSourcesForPlaylistList(playlistId)
        if (sources.isEmpty()) {
            Log.d(TAG, "No sync sources for playlist $playlistId")
            return SyncResult(playlistId)
        }

        Log.d(TAG, "Syncing playlist $playlistId with ${sources.size} sources")

        var totalAdded = 0
        var totalRemoved = 0
        val errors = mutableListOf<String>()
        val failedSourceIds = mutableSetOf<Long>()

        // Collect all remote songs across all sources, keyed by (platform, platformSongId)
        // Also track which source each song came from
        val remoteSongsBySource = mutableMapOf<Long, List<Song>>()

        for (source in sources) {
            try {
                val remoteSongs = fetchSourceSongs(source)
                remoteSongsBySource[source.id] = remoteSongs

                repository.updateSyncStatus(
                    id = source.id,
                    syncAt = System.currentTimeMillis(),
                    status = "success"
                )
                Log.d(TAG, "Source ${source.id} (${source.platform}): fetched ${remoteSongs.size} songs")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch source ${source.id}: ${e.message}", e)
                val errorMsg = "${source.platform}: ${e.message ?: "Unknown error"}"
                errors.add(errorMsg)
                failedSourceIds.add(source.id)

                repository.updateSyncStatus(
                    id = source.id,
                    syncAt = System.currentTimeMillis(),
                    status = "error",
                    error = e.message ?: "Unknown error"
                )
            }
        }

        // Get current local songs in this playlist
        val localSongs = repository.getSongsForPlaylistList(playlistId)
        val localSongKeys = localSongs.map { "${it.platform}:${it.platformSongId}" }.toSet()

        // Add new songs from successful sources
        for ((sourceId, remoteSongs) in remoteSongsBySource) {
            for (remoteSong in remoteSongs) {
                val key = "${remoteSong.platform}:${remoteSong.platformSongId}"
                if (key !in localSongKeys) {
                    try {
                        val songId = ensureSongExists(remoteSong)
                        repository.addSongToPlaylistWithSync(playlistId, songId, sourceId)
                        totalAdded++
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add song ${remoteSong.title}: ${e.message}")
                    }
                }
            }
        }

        // Remove stale synced songs:
        // A synced item should be removed if its song is no longer in ANY successful source
        val allRemoteSongKeys = remoteSongsBySource.values.flatten()
            .map { "${it.platform}:${it.platformSongId}" }
            .toSet()

        val syncedItems = repository.getSyncedItemsForPlaylist(playlistId)
        for (item in syncedItems) {
            // Skip items from failed sources — don't remove them
            if (item.syncSourceId != null && item.syncSourceId in failedSourceIds) {
                continue
            }

            // Look up the song to check its key
            val song = localSongs.find { it.id == item.songId } ?: continue
            val key = "${song.platform}:${song.platformSongId}"

            if (key !in allRemoteSongKeys) {
                repository.removeSongFromPlaylist(playlistId, item.songId)
                totalRemoved++
            }
        }

        Log.d(TAG, "Sync complete for playlist $playlistId: +$totalAdded -$totalRemoved")
        return SyncResult(playlistId, totalAdded, totalRemoved, errors)
    }

    /**
     * Sync all playlists that have sync sources.
     */
    suspend fun syncAll(): List<SyncResult> {
        val playlistIds = repository.getAllSyncedPlaylistIds()
        Log.d(TAG, "Syncing ${playlistIds.size} playlists")
        return playlistIds.map { syncPlaylist(it) }
    }

    /**
     * Fetch songs from a single sync source via its platform handler.
     */
    private suspend fun fetchSourceSongs(source: SyncSource): List<Song> {
        val handler = LinkParser.getHandler(source.platform)
            ?: throw IllegalArgumentException("No handler for platform: ${source.platform}")

        val parsedPlaylist = handler.fetchPlaylistSongs(source.remotePlaylistId)
            ?: throw IllegalStateException("Failed to fetch playlist ${source.remotePlaylistId}")

        return parsedPlaylist.songs.map { parsed ->
            Song(
                title = parsed.title,
                artist = parsed.artist,
                album = parsed.album,
                platform = parsed.platform,
                platformSongId = parsed.platformSongId,
                deepLink = parsed.deepLink,
                sourceUrl = parsed.fallbackUrl,
                coverUrl = parsed.coverUrl
            )
        }
    }

    /**
     * Ensure a song exists in the songs table, deduplicating by platform + platformSongId.
     * Returns the song's ID.
     */
    private suspend fun ensureSongExists(song: Song): Long {
        val existing = repository.getSongByPlatformId(song.platform, song.platformSongId)
        return existing?.id ?: repository.insertSong(song)
    }
}
