package com.musichub.remote

import com.musichub.data.model.Playlist
import com.musichub.data.model.Song

/**
 * Data classes for JSON serialization between server and client.
 * Fields use defaults to guard against Gson setting null for missing JSON fields.
 */

data class RemoteSong(
    val id: Long = 0,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val platform: String = "",
    val platformSongId: String = "",
    val deepLink: String = "",
    val coverUrl: String = ""
)

data class RemotePlaylist(
    val id: Long = 0,
    val name: String = "",
    val description: String = "",
    val songCount: Int = 0
)

data class RemoteState(
    val isPlaying: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val repeatMode: String = "OFF",     // "OFF", "ALL", "ONE"
    val shuffleEnabled: Boolean = false,
    val currentSong: RemoteSong? = null,
    val currentIndex: Int = -1,
    val queueSize: Int = 0,
    val shuffleOrder: List<Int>? = null,  // Shuffle index order, null when not shuffling
    val volume: Int = -1,                // STREAM_MUSIC volume, -1 = unknown
    val maxVolume: Int = -1              // Max STREAM_MUSIC volume, -1 = unknown
)

// Extension functions for conversion
// Use orEmpty() to guard against Gson setting null on non-null Kotlin String fields

fun Song.toRemoteSong() = RemoteSong(
    id = id,
    title = title,
    artist = artist,
    album = album,
    platform = platform,
    platformSongId = platformSongId,
    deepLink = deepLink,
    coverUrl = coverUrl
)

fun RemoteSong.toSong() = Song(
    id = id,
    title = (title as String?) ?: "",
    artist = (artist as String?) ?: "",
    album = (album as String?) ?: "",
    platform = (platform as String?) ?: "",
    platformSongId = (platformSongId as String?) ?: "",
    deepLink = (deepLink as String?) ?: "",
    coverUrl = (coverUrl as String?) ?: ""
)

fun Playlist.toRemotePlaylist(songCount: Int = 0) = RemotePlaylist(
    id = id,
    name = name,
    description = description,
    songCount = songCount
)
