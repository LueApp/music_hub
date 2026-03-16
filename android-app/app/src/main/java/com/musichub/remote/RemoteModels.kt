package com.musichub.remote

import com.musichub.data.model.Playlist
import com.musichub.data.model.Song

/**
 * Data classes for JSON serialization between server and client.
 */

data class RemoteSong(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val platform: String,
    val platformSongId: String,
    val deepLink: String,
    val coverUrl: String
)

data class RemotePlaylist(
    val id: Long,
    val name: String,
    val description: String,
    val songCount: Int
)

data class RemoteState(
    val isPlaying: Boolean,
    val position: Long,
    val duration: Long,
    val repeatMode: String,     // "OFF", "ALL", "ONE"
    val shuffleEnabled: Boolean,
    val currentSong: RemoteSong?,
    val currentIndex: Int,
    val queueSize: Int
)

// Extension functions for conversion

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
    title = title,
    artist = artist,
    album = album,
    platform = platform,
    platformSongId = platformSongId,
    deepLink = deepLink,
    coverUrl = coverUrl
)

fun Playlist.toRemotePlaylist(songCount: Int = 0) = RemotePlaylist(
    id = id,
    name = name,
    description = description,
    songCount = songCount
)
