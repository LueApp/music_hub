package com.musichub.platform

import android.util.Log
import com.musichub.data.model.ParsedSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Link parser service for extracting song information from shared URLs.
 */
object LinkParser {

    private const val TAG = "LinkParser"

    private val handlers = listOf(
        NetEasePlatform(),
        QQMusicPlatform(),
        BilibiliPlatform()
    )

    // Short URL domains that need redirect resolution
    private val shortUrlDomains = listOf("163cn.tv", ".y.qq.com/base/fcgi-bin", "b23.tv")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Get the appropriate platform handler for a URL.
     */
    fun getHandler(platformName: String): PlatformHandler? {
        return handlers.find { it.platformName == platformName }
    }

    /**
     * Resolve short URLs by following redirects.
     */
    suspend fun resolveShortUrl(url: String): String {
        val isShortUrl = shortUrlDomains.any { url.contains(it) }
        if (!isShortUrl) return url

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Resolving short URL: $url")

                val request = Request.Builder()
                    .url(url)
                    .build()

                client.newCall(request).execute().use { response ->
                    val resolvedUrl = response.request.url.toString()
                    Log.d(TAG, "Resolved to: $resolvedUrl")
                    resolvedUrl
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve short URL: ${e.message}")
                url
            }
        }
    }

    /**
     * Parse a shared URL and extract song information.
     */
    suspend fun parseSharedUrl(url: String): ParsedSong? {
        val resolvedUrl = resolveShortUrl(url.trim())

        for (handler in handlers) {
            if (handler.canHandle(resolvedUrl)) {
                Log.d(TAG, "URL matched platform: ${handler.displayName}")
                val parsed = handler.parseSongUrl(resolvedUrl)

                if (parsed != null) {
                    Log.d(TAG, "Parsed song ID: ${parsed.platformSongId}")

                    // Fetch metadata from platform API
                    val metadata = handler.fetchMetadata(parsed.platformSongId)
                    parsed.title = metadata["title"] ?: ""
                    parsed.artist = metadata["artist"] ?: ""
                    parsed.album = metadata["album"] ?: ""
                    parsed.coverUrl = metadata["cover_url"] ?: ""

                    return parsed
                }
            }
        }

        Log.w(TAG, "No platform handler found for URL: ${url.take(100)}")
        return null
    }

    /**
     * Extract URL from shared text content.
     */
    fun extractUrlFromText(text: String): String? {
        val urlPattern = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""", RegexOption.IGNORE_CASE)
        val matches = urlPattern.findAll(text)

        for (match in matches) {
            val url = match.value
            for (handler in handlers) {
                if (handler.canHandle(url)) {
                    return url
                }
            }
        }

        // Return first URL if no platform match
        return matches.firstOrNull()?.value
    }

    /**
     * Extract song title and artist from shared text.
     * Chinese music apps often include this info in shared text.
     */
    fun extractMetadataFromText(text: String): Pair<String, String> {
        var title = ""
        var artist = ""

        // Pattern: 《title》
        val titleMatch = Regex("""《([^》]+)》""").find(text)
        if (titleMatch != null) {
            title = titleMatch.groupValues[1].trim()
        }

        // Pattern: artist《title》 (artist before title)
        val artistBeforeTitle = Regex("""^([^《\s@]+)《""").find(text.trim())
        if (artistBeforeTitle != null) {
            val candidate = artistBeforeTitle.groupValues[1].trim()
            if (candidate !in listOf("分享", "推荐", "听")) {
                artist = candidate
            }
        }

        // Pattern: 分享XXX的
        if (artist.isEmpty()) {
            val shareMatch = Regex("""分享\s*([^的《]+?)的""").find(text)
            if (shareMatch != null) {
                artist = shareMatch.groupValues[1].trim()
            }
        }

        // Pattern: 》- artist
        if (artist.isEmpty()) {
            val dashMatch = Regex("""》\s*[-–—]\s*([^\s\n（(]+)""").find(text)
            if (dashMatch != null) {
                artist = dashMatch.groupValues[1].trim()
            }
        }

        // Pattern: 》(artist) or 》（artist）
        if (artist.isEmpty()) {
            val parenMatch = Regex("""》\s*[（(]([^）)]+)[）)]""").find(text)
            if (parenMatch != null) {
                artist = parenMatch.groupValues[1].trim()
            }
        }

        Log.d(TAG, "Extracted metadata - title: '$title', artist: '$artist'")
        return Pair(title, artist)
    }

    /**
     * Parse shared content which may contain a URL among other text.
     */
    suspend fun parseSharedContent(text: String): ParsedSong? {
        val url = extractUrlFromText(text)
        if (url != null) {
            val result = parseSharedUrl(url)
            if (result != null) {
                // Fall back to text extraction if API didn't return metadata
                if (result.title.isEmpty() || result.artist.isEmpty()) {
                    val (title, artist) = extractMetadataFromText(text)
                    if (result.title.isEmpty() && title.isNotEmpty()) {
                        result.title = title
                    }
                    if (result.artist.isEmpty() && artist.isNotEmpty()) {
                        result.artist = artist
                    }
                }
                return result
            }
        }
        return null
    }

    /**
     * Check if the URL is a playlist URL.
     */
    suspend fun isPlaylistUrl(text: String): Boolean {
        val url = extractUrlFromText(text)
        if (url == null) return false

        val resolvedUrl = resolveShortUrl(url.trim())

        for (handler in handlers) {
            if (handler.canHandle(resolvedUrl)) {
                if (handler.parsePlaylistUrl(resolvedUrl) != null) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Parse a playlist URL and fetch all songs.
     */
    suspend fun parsePlaylistUrl(text: String): ParsedPlaylist? {
        val url = extractUrlFromText(text)
        if (url == null) return null

        val resolvedUrl = resolveShortUrl(url.trim())

        for (handler in handlers) {
            if (handler.canHandle(resolvedUrl)) {
                val playlistInfo = handler.parsePlaylistUrl(resolvedUrl)
                if (playlistInfo != null) {
                    Log.d(TAG, "Playlist URL matched platform: ${handler.displayName}")
                    // Fetch full playlist details
                    return handler.fetchPlaylistSongs(playlistInfo.playlistId)
                }
            }
        }

        Log.w(TAG, "No platform handler found for playlist URL: ${url.take(100)}")
        return null
    }
}
