package com.musichub.platform

import com.musichub.data.model.ParsedSong

/**
 * Parsed playlist data from URL parsing.
 */
data class ParsedPlaylist(
    val platform: String,
    val playlistId: String,
    val name: String = "",
    val description: String = "",
    val coverUrl: String = "",
    val songCount: Int = 0,
    val songs: List<ParsedSong> = emptyList()
)

/**
 * Base interface for music platform handlers.
 */
interface PlatformHandler {
    val platformName: String
    val displayName: String
    val packageName: String

    /**
     * Check if this handler can parse the given URL.
     */
    fun canHandle(url: String): Boolean

    /**
     * Parse a song URL and extract song information.
     */
    fun parseSongUrl(url: String): ParsedSong?

    /**
     * Parse a playlist URL and extract playlist ID.
     */
    fun parsePlaylistUrl(url: String): ParsedPlaylist? = null

    /**
     * Generate a deep link URI to open the song in the platform's app.
     */
    fun generateDeepLink(platformSongId: String): String

    /**
     * Generate a web fallback URL.
     */
    fun generateFallbackUrl(platformSongId: String): String

    /**
     * Fetch metadata from the platform's API.
     * Returns a map with keys: title, artist, album, cover_url
     */
    suspend fun fetchMetadata(platformSongId: String): Map<String, String>

    /**
     * Fetch playlist details and songs from the platform's API.
     */
    suspend fun fetchPlaylistSongs(playlistId: String): ParsedPlaylist? = null
}

/**
 * Platform constants.
 */
object Platforms {
    const val NETEASE = "netease"
    const val QQMUSIC = "qqmusic"
    const val BILIBILI = "bilibili"

    val DISPLAY_NAMES = mapOf(
        NETEASE to "网易云音乐",
        QQMUSIC to "QQ音乐",
        BILIBILI to "哔哩哔哩"
    )

    val PACKAGE_NAMES = mapOf(
        NETEASE to "com.netease.cloudmusic",
        QQMUSIC to "com.tencent.qqmusic",
        BILIBILI to "tv.danmaku.bili"
    )
}
