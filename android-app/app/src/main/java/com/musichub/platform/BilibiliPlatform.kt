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
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    // Regex patterns for parsing Bilibili URLs
    private val bvidPattern = Regex("""bilibili\.com/video/(BV[a-zA-Z0-9]+)""")
    private val avidPattern = Regex("""bilibili\.com/video/av(\d+)""")
    private val audioPattern = Regex("""bilibili\.com/audio/au(\d+)""")

    // Regex patterns for parsing Bilibili medialist/favorites URLs
    private val medialistPattern = Regex("""bilibili\.com/medialist/detail/ml(\d+)""")
    private val favlistPattern = Regex("""space\.bilibili\.com/\d+/favlist.*[?&]fid=(\d+)""")

    override fun canHandle(url: String): Boolean {
        return url.contains("bilibili.com") || url.contains("b23.tv")
    }

    override fun parsePlaylistUrl(url: String): ParsedPlaylist? {
        val medialistMatch = medialistPattern.find(url)
        if (medialistMatch != null) {
            val mediaId = medialistMatch.groupValues[1]
            Log.d(TAG, "Parsed Bilibili medialist ID: $mediaId from URL: $url")
            return ParsedPlaylist(platform = platformName, playlistId = mediaId)
        }

        val favlistMatch = favlistPattern.find(url)
        if (favlistMatch != null) {
            val mediaId = favlistMatch.groupValues[1]
            Log.d(TAG, "Parsed Bilibili favlist ID: $mediaId from URL: $url")
            return ParsedPlaylist(platform = platformName, playlistId = mediaId)
        }

        return null
    }

    override suspend fun fetchPlaylistSongs(playlistId: String): ParsedPlaylist? {
        return withContext(Dispatchers.IO) {
            try {
                val songs = mutableListOf<ParsedSong>()
                var playlistName = ""
                var playlistCover = ""
                var totalCount = 0
                var page = 1

                while (true) {
                    val url = "https://api.bilibili.com/x/v3/fav/resource/list?media_id=$playlistId&pn=$page&ps=20&order=mtime&type=0&tid=0&platform=web"

                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Referer", "https://www.bilibili.com/")
                        .build()

                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Favorites API request failed: ${response.code}")
                        response.close()
                        return@withContext null
                    }

                    val body = response.body?.string()
                    response.close()
                    if (body == null) {
                        Log.e(TAG, "Favorites API returned empty body")
                        return@withContext null
                    }

                    val json = gson.fromJson(body, JsonObject::class.java)
                    val code = json.get("code")?.asInt ?: -1

                    if (code != 0) {
                        val message = json.get("message")?.asString ?: "未知错误"
                        Log.e(TAG, "Favorites API error: code=$code, message=$message")
                        return@withContext null
                    }

                    val data = json.getAsJsonObject("data") ?: return@withContext null

                    // Extract playlist metadata from first page
                    if (page == 1) {
                        val info = data.getAsJsonObject("info")
                        if (info != null) {
                            playlistName = info.get("title")?.asString ?: ""
                            playlistCover = info.get("cover")?.asString?.replace("http://", "https://") ?: ""
                            totalCount = info.get("media_count")?.asInt ?: 0
                            Log.d(TAG, "Fetching Bilibili favorites: '$playlistName' ($totalCount items)")
                        }
                    }

                    val medias = data.getAsJsonArray("medias")
                    if (medias == null || medias.size() == 0) break

                    for (element in medias) {
                        val media = element.asJsonObject

                        // Only include video items (type == 2)
                        val type = media.get("type")?.asInt ?: 0
                        if (type != 2) continue

                        // Skip invalidated/deleted items (attr == 9)
                        val attr = media.get("attr")?.asInt ?: 0
                        if (attr == 9) continue

                        val title = media.get("title")?.asString ?: ""
                        val cover = media.get("cover")?.asString?.replace("http://", "https://") ?: ""
                        val bvId = media.get("bv_id")?.asString ?: media.get("bvid")?.asString ?: ""
                        val avId = media.get("id")?.asLong ?: 0L

                        // Determine platformSongId: prefer BV ID, fall back to av ID
                        val platformSongId = if (bvId.isNotEmpty()) {
                            "video:$bvId"
                        } else if (avId > 0) {
                            "video:av$avId"
                        } else {
                            continue // Skip items with no usable ID
                        }

                        // Extract uploader name
                        val upper = media.getAsJsonObject("upper")
                        val artist = upper?.get("name")?.asString ?: ""

                        songs.add(ParsedSong(
                            platform = platformName,
                            platformSongId = platformSongId,
                            deepLink = generateDeepLink(platformSongId),
                            fallbackUrl = generateFallbackUrl(platformSongId),
                            title = title,
                            artist = artist,
                            coverUrl = cover
                        ))
                    }

                    // Check if we've fetched all items
                    if (songs.size >= totalCount || medias.size() < 20) break
                    page++
                }

                Log.d(TAG, "Fetched ${songs.size} videos from Bilibili favorites '$playlistName'")

                ParsedPlaylist(
                    platform = platformName,
                    playlistId = playlistId,
                    name = playlistName,
                    coverUrl = playlistCover,
                    songCount = totalCount,
                    songs = songs
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch Bilibili favorites $playlistId: ${e.message}", e)
                null
            }
        }
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
                "bilibili://video/$bvid?start_progress=0"
            }
            platformSongId.startsWith("video:av") -> {
                val avid = platformSongId.removePrefix("video:av")
                "bilibili://video/av$avid?start_progress=0"
            }
            platformSongId.startsWith("audio:") -> {
                val auid = platformSongId.removePrefix("audio:")
                "bilibili://music/detail/$auid"
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

    override suspend fun checkSongAvailability(platformSongId: String): SongAvailability {
        return withContext(Dispatchers.IO) {
            try {
                when {
                    platformSongId.startsWith("video:BV") -> {
                        val bvid = platformSongId.removePrefix("video:")
                        checkVideoAvailability("bvid=$bvid")
                    }
                    platformSongId.startsWith("video:av") -> {
                        val avid = platformSongId.removePrefix("video:av")
                        checkVideoAvailability("aid=$avid")
                    }
                    platformSongId.startsWith("audio:") -> {
                        val auid = platformSongId.removePrefix("audio:")
                        checkAudioAvailability(auid)
                    }
                    else -> SongAvailability(false, "未知的ID格式")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Availability check failed for $platformSongId: ${e.message}")
                // On network error, assume available to avoid blocking playback
                SongAvailability(true)
            }
        }
    }

    private fun checkVideoAvailability(queryParam: String): SongAvailability {
        val url = "https://api.bilibili.com/x/web-interface/view?$queryParam"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://www.bilibili.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return SongAvailability(false, "API请求失败 (${response.code})")
            }

            val body = response.body?.string() ?: return SongAvailability(false, "空响应")
            val json = gson.fromJson(body, JsonObject::class.java)
            val code = json.get("code")?.asInt ?: -1

            // Bilibili API codes: 0 = success, -404 = not found, -403 = forbidden, 62002 = invisible
            return when (code) {
                0 -> SongAvailability(true)
                -404 -> SongAvailability(false, "视频不存在")
                -403 -> SongAvailability(false, "视频无法访问")
                62002 -> SongAvailability(false, "视频不可见")
                else -> {
                    val message = json.get("message")?.asString ?: "未知错误"
                    SongAvailability(false, message)
                }
            }
        }
    }

    private fun checkAudioAvailability(auid: String): SongAvailability {
        val url = "https://www.bilibili.com/audio/music-service-c/web/song/info?sid=$auid"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://www.bilibili.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return SongAvailability(false, "API请求失败 (${response.code})")
            }

            val body = response.body?.string() ?: return SongAvailability(false, "空响应")
            val json = gson.fromJson(body, JsonObject::class.java)
            val code = json.get("code")?.asInt ?: -1

            return when (code) {
                0 -> SongAvailability(true)
                else -> {
                    val msg = json.get("msg")?.asString ?: "音频不存在"
                    SongAvailability(false, msg)
                }
            }
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

    /**
     * Fetch Bilibili music zone ranking.
     */
    suspend fun fetchMusicRanking(): ParsedPlaylist? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api.bilibili.com/x/web-interface/ranking/v2?rid=3&type=all"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://www.bilibili.com/")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Music ranking API failed: ${response.code}")
                        return@withContext null
                    }

                    val body = response.body?.string() ?: return@withContext null
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val code = json.get("code")?.asInt ?: -1

                    if (code != 0) {
                        Log.e(TAG, "Music ranking API error: $code")
                        return@withContext null
                    }

                    val data = json.getAsJsonObject("data") ?: return@withContext null
                    val list = data.getAsJsonArray("list") ?: return@withContext null

                    val songs = mutableListOf<ParsedSong>()
                    for (item in list) {
                        try {
                            val obj = item.asJsonObject
                            val bvid = obj.get("bvid")?.asString ?: continue
                            val title = obj.get("title")?.asString ?: ""
                            val owner = obj.getAsJsonObject("owner")
                            val artist = owner?.get("name")?.asString ?: ""
                            val pic = obj.get("pic")?.asString?.replace("http://", "https://") ?: ""

                            val platformSongId = "video:$bvid"
                            songs.add(ParsedSong(
                                platform = platformName,
                                platformSongId = platformSongId,
                                deepLink = generateDeepLink(platformSongId),
                                fallbackUrl = generateFallbackUrl(platformSongId),
                                title = title,
                                artist = artist,
                                coverUrl = pic
                            ))
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse ranking item: ${e.message}")
                        }
                    }

                    Log.d(TAG, "Fetched ${songs.size} items from Bilibili music ranking")
                    ParsedPlaylist(
                        platform = platformName,
                        playlistId = "music_ranking",
                        name = "音乐排行",
                        songCount = songs.size,
                        songs = songs
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch music ranking", e)
                null
            }
        }
    }

    companion object {
        private const val TAG = "BilibiliPlatform"
    }
}
