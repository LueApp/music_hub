package com.musichub.platform

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.musichub.data.model.ParsedSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Handler for Bilibili (哔哩哔哩).
 * Supports both video (BV/av) and audio (au) content.
 */
class BilibiliPlatform : PlatformHandler {

    override val platformName = Platforms.BILIBILI
    override val displayName = "哔哩哔哩"
    override val packageName = Platforms.PACKAGE_NAMES[Platforms.BILIBILI]!!

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    // Regex patterns for parsing Bilibili URLs
    private val bvidPattern = Regex("""bilibili\.com/video/(BV[a-zA-Z0-9]+)""")
    private val avidPattern = Regex("""bilibili\.com/video/av(\d+)""")
    private val audioPattern = Regex("""bilibili\.com/audio/au(\d+)""")

    override fun canHandle(url: String): Boolean {
        return url.contains("bilibili.com") || url.contains("b23.tv")
    }

    override fun parseSongUrl(url: String): ParsedSong? {
        // Try BV format first (most common now)
        val bvidMatch = bvidPattern.find(url)
        if (bvidMatch != null) {
            val bvid = bvidMatch.groupValues[1]
            return ParsedSong(
                platform = platformName,
                platformSongId = "video:$bvid",
                deepLink = generateDeepLink("video:$bvid"),
                fallbackUrl = generateFallbackUrl("video:$bvid")
            )
        }

        // Try av format (legacy)
        val avidMatch = avidPattern.find(url)
        if (avidMatch != null) {
            val avid = avidMatch.groupValues[1]
            return ParsedSong(
                platform = platformName,
                platformSongId = "video:av$avid",
                deepLink = generateDeepLink("video:av$avid"),
                fallbackUrl = generateFallbackUrl("video:av$avid")
            )
        }

        // Try audio format
        val audioMatch = audioPattern.find(url)
        if (audioMatch != null) {
            val auid = audioMatch.groupValues[1]
            return ParsedSong(
                platform = platformName,
                platformSongId = "audio:$auid",
                deepLink = generateDeepLink("audio:$auid"),
                fallbackUrl = generateFallbackUrl("audio:$auid")
            )
        }

        return null
    }

    override fun generateDeepLink(platformSongId: String): String {
        return when {
            platformSongId.startsWith("video:BV") -> {
                val bvid = platformSongId.removePrefix("video:")
                // Bilibili app supports multiple URI formats
                "https://www.bilibili.com/video/$bvid"
            }
            platformSongId.startsWith("video:av") -> {
                val avid = platformSongId.removePrefix("video:av")
                "https://www.bilibili.com/video/av$avid"
            }
            platformSongId.startsWith("audio:") -> {
                val auid = platformSongId.removePrefix("audio:")
                "https://www.bilibili.com/audio/au$auid"
            }
            else -> "https://www.bilibili.com"
        }
    }

    override fun generateFallbackUrl(platformSongId: String): String {
        return when {
            platformSongId.startsWith("video:BV") -> {
                val bvid = platformSongId.removePrefix("video:")
                "https://www.bilibili.com/video/$bvid"
            }
            platformSongId.startsWith("video:av") -> {
                val avid = platformSongId.removePrefix("video:av")
                "https://www.bilibili.com/video/av$avid"
            }
            platformSongId.startsWith("audio:") -> {
                val auid = platformSongId.removePrefix("audio:")
                "https://www.bilibili.com/audio/au$auid"
            }
            else -> "https://www.bilibili.com"
        }
    }

    override suspend fun fetchMetadata(platformSongId: String): Map<String, String> {
        return withContext(Dispatchers.IO) {
            val result = mutableMapOf<String, String>()

            try {
                when {
                    platformSongId.startsWith("video:BV") -> {
                        val bvid = platformSongId.removePrefix("video:")
                        fetchVideoMetadata(bvid, result)
                    }
                    platformSongId.startsWith("video:av") -> {
                        val avid = platformSongId.removePrefix("video:av")
                        fetchVideoMetadataByAid(avid, result)
                    }
                    platformSongId.startsWith("audio:") -> {
                        val auid = platformSongId.removePrefix("audio:")
                        fetchAudioMetadata(auid, result)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch Bilibili metadata for $platformSongId: ${e.message}")
            }

            result
        }
    }

    private fun fetchVideoMetadata(bvid: String, result: MutableMap<String, String>) {
        val url = "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://www.bilibili.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val data = json.getAsJsonObject("data")

                    if (data != null) {
                        result["title"] = data.get("title")?.asString ?: ""

                        // Owner (uploader) as artist
                        val owner = data.getAsJsonObject("owner")
                        if (owner != null) {
                            result["artist"] = owner.get("name")?.asString ?: ""
                        }

                        // Video cover
                        val pic = data.get("pic")?.asString
                        if (pic != null) {
                            result["cover_url"] = pic.replace("http://", "https://")
                        }

                        // Use bvid as album identifier
                        result["album"] = bvid

                        Log.d(TAG, "Fetched Bilibili video metadata: ${result["title"]} - ${result["artist"]}")
                    }
                }
            }
        }
    }

    private fun fetchVideoMetadataByAid(aid: String, result: MutableMap<String, String>) {
        val url = "https://api.bilibili.com/x/web-interface/view?aid=$aid"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://www.bilibili.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val data = json.getAsJsonObject("data")

                    if (data != null) {
                        result["title"] = data.get("title")?.asString ?: ""

                        val owner = data.getAsJsonObject("owner")
                        if (owner != null) {
                            result["artist"] = owner.get("name")?.asString ?: ""
                        }

                        val pic = data.get("pic")?.asString
                        if (pic != null) {
                            result["cover_url"] = pic.replace("http://", "https://")
                        }

                        result["album"] = "av$aid"

                        Log.d(TAG, "Fetched Bilibili video metadata (aid): ${result["title"]} - ${result["artist"]}")
                    }
                }
            }
        }
    }

    private fun fetchAudioMetadata(auid: String, result: MutableMap<String, String>) {
        val url = "https://www.bilibili.com/audio/music-service-c/web/song/info?sid=$auid"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://www.bilibili.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val data = json.getAsJsonObject("data")

                    if (data != null) {
                        result["title"] = data.get("title")?.asString ?: ""
                        result["artist"] = data.get("author")?.asString ?: data.get("uname")?.asString ?: ""

                        val cover = data.get("cover")?.asString
                        if (cover != null) {
                            result["cover_url"] = cover.replace("http://", "https://")
                        }

                        result["album"] = "au$auid"

                        Log.d(TAG, "Fetched Bilibili audio metadata: ${result["title"]} - ${result["artist"]}")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "BilibiliPlatform"
    }
}
