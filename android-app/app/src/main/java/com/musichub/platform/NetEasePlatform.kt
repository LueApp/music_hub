package com.musichub.platform

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.musichub.data.model.ParsedSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Handler for NetEase Cloud Music (网易云音乐).
 */
class NetEasePlatform : PlatformHandler {

    override val platformName = Platforms.NETEASE
    override val displayName = "网易云音乐"
    override val packageName = Platforms.PACKAGE_NAMES[Platforms.NETEASE]!!

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // Regex patterns for parsing NetEase URLs
    private val songIdPatterns = listOf(
        Regex("""music\.163\.com.*[?&]id=(\d+)"""),
        Regex("""music\.163\.com/#/song\?id=(\d+)"""),
        Regex("""y\.music\.163\.com/m/song\?id=(\d+)""")
    )

    private val playlistIdPatterns = listOf(
        Regex("""music\.163\.com.*playlist.*[?&#]id=(\d+)"""),
        Regex("""music\.163\.com.*playlist/(\d+)"""),
        Regex("""music\.163\.com/#/playlist\?id=(\d+)"""),
        Regex("""163cn\.tv.*playlist.*[?&#]id=(\d+)""")
    )

    override fun canHandle(url: String): Boolean {
        return url.contains("music.163.com") || url.contains("163cn.tv")
    }

    override fun parsePlaylistUrl(url: String): ParsedPlaylist? {
        for (pattern in playlistIdPatterns) {
            val match = pattern.find(url)
            if (match != null) {
                val playlistId = match.groupValues[1]
                Log.d(TAG, "Parsed NetEase playlist ID: $playlistId from URL: $url")
                return ParsedPlaylist(
                    platform = platformName,
                    playlistId = playlistId
                )
            }
        }
        return null
    }

    override fun parseSongUrl(url: String): ParsedSong? {
        for (pattern in songIdPatterns) {
            val match = pattern.find(url)
            if (match != null) {
                val songId = match.groupValues[1]
                return ParsedSong(
                    platform = platformName,
                    platformSongId = songId,
                    deepLink = generateDeepLink(songId),
                    fallbackUrl = generateFallbackUrl(songId)
                )
            }
        }
        return null
    }

    override fun generateDeepLink(platformSongId: String): String {
        return "orpheus://song/$platformSongId"
    }

    override fun generateFallbackUrl(platformSongId: String): String {
        return "https://music.163.com/song?id=$platformSongId"
    }

    override suspend fun checkSongAvailability(platformSongId: String): SongAvailability {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://music.163.com/api/v3/song/detail?id=$platformSongId&c=[{%22id%22:$platformSongId}]"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://music.163.com/")
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext SongAvailability(false, "API请求失败 (${response.code})")
                    }

                    val body = response.body?.string()
                    if (body == null) {
                        return@withContext SongAvailability(false, "空响应")
                    }

                    val json = gson.fromJson(body, JsonObject::class.java)
                    val songs = json.getAsJsonArray("songs")

                    if (songs == null || songs.size() == 0) {
                        return@withContext SongAvailability(false, "歌曲不存在")
                    }

                    val song = songs[0].asJsonObject
                    val title = song.get("name")?.asString ?: ""

                    if (title.isEmpty()) {
                        return@withContext SongAvailability(false, "歌曲不存在")
                    }

                    // Check privileges - NetEase uses 'privileges' array to indicate availability
                    // st < 0 means the song is not available (e.g., taken down, region-locked)
                    val privileges = json.getAsJsonArray("privileges")
                    if (privileges != null && privileges.size() > 0) {
                        val priv = privileges[0].asJsonObject
                        val st = priv.get("st")?.asInt ?: 0
                        if (st < 0) {
                            Log.d(TAG, "Song $platformSongId unavailable: st=$st")
                            return@withContext SongAvailability(false, "歌曲已下架或无版权")
                        }
                    }

                    SongAvailability(true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Availability check failed for $platformSongId: ${e.message}")
                // On network error, assume available to avoid blocking playback
                SongAvailability(true)
            }
        }
    }

    override suspend fun fetchMetadata(platformSongId: String): Map<String, String> {
        return withContext(Dispatchers.IO) {
            val result = mutableMapOf<String, String>()

            try {
                // Try v3 API first
                val url = "https://music.163.com/api/v3/song/detail?id=$platformSongId&c=[{%22id%22:$platformSongId}]"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://music.163.com/")
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = gson.fromJson(body, JsonObject::class.java)
                            val songs = json.getAsJsonArray("songs")

                            if (songs != null && songs.size() > 0) {
                                val song = songs[0].asJsonObject

                                result["title"] = song.get("name")?.asString ?: ""

                                // Artists (v3 uses 'ar')
                                val artists = song.getAsJsonArray("ar")
                                if (artists != null && artists.size() > 0) {
                                    result["artist"] = artists.mapNotNull {
                                        it.asJsonObject.get("name")?.asString
                                    }.joinToString("/")
                                }

                                // Album (v3 uses 'al')
                                val album = song.getAsJsonObject("al")
                                if (album != null) {
                                    result["album"] = album.get("name")?.asString ?: ""
                                    val coverUrl = album.get("picUrl")?.asString
                                    if (coverUrl != null) {
                                        result["cover_url"] = coverUrl.replace("http://", "https://")
                                    }
                                }

                                if (result["title"]?.isNotEmpty() == true) {
                                    Log.d(TAG, "Fetched NetEase metadata (v3): ${result["title"]} - ${result["artist"]}")
                                    return@withContext result
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "NetEase v3 API failed for $platformSongId: ${e.message}")
            }

            // Fallback to old API
            try {
                val url = "https://music.163.com/api/song/detail/?ids=[$platformSongId]"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://music.163.com/")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = gson.fromJson(body, JsonObject::class.java)
                            val songs = json.getAsJsonArray("songs")

                            if (songs != null && songs.size() > 0) {
                                val song = songs[0].asJsonObject

                                result["title"] = song.get("name")?.asString ?: ""

                                val artists = song.getAsJsonArray("artists")
                                if (artists != null && artists.size() > 0) {
                                    result["artist"] = artists.mapNotNull {
                                        it.asJsonObject.get("name")?.asString
                                    }.joinToString("/")
                                }

                                val album = song.getAsJsonObject("album")
                                if (album != null) {
                                    result["album"] = album.get("name")?.asString ?: ""
                                    val coverUrl = album.get("picUrl")?.asString
                                    if (coverUrl != null) {
                                        result["cover_url"] = coverUrl.replace("http://", "https://")
                                    }
                                }

                                Log.d(TAG, "Fetched NetEase metadata (fallback): ${result["title"]} - ${result["artist"]}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch NetEase metadata for $platformSongId: ${e.message}")
            }

            result
        }
    }

    override suspend fun fetchPlaylistSongs(playlistId: String): ParsedPlaylist? {
        return withContext(Dispatchers.IO) {
            // Try multiple API endpoints
            fetchPlaylistSongsV3(playlistId)
                ?: fetchPlaylistSongsWeb(playlistId)
                ?: fetchPlaylistSongsLegacy(playlistId)
        }
    }

    private fun fetchPlaylistSongsV3(playlistId: String): ParsedPlaylist? {
        try {
            // v3 API - works without login for public playlists
            val url = "https://music.163.com/api/v3/playlist/detail?id=$playlistId&n=1000"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://music.163.com/")
                .header("Accept", "application/json")
                .header("Cookie", "NMTID=placeholder; MUSIC_U=placeholder")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    Log.d(TAG, "v3 API response (first 500 chars): ${body?.take(500)}")

                    if (body != null) {
                        val json = gson.fromJson(body, JsonObject::class.java)
                        val playlist = json.getAsJsonObject("playlist")

                        if (playlist != null && !playlist.isJsonNull) {
                            return parsePlaylistJson(playlist, playlistId, "v3")
                        } else {
                            Log.w(TAG, "v3 API: playlist is null or missing")
                        }
                    }
                } else {
                    Log.w(TAG, "v3 API failed with status: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "v3 API exception: ${e.message}")
        }
        return null
    }

    private fun fetchPlaylistSongsWeb(playlistId: String): ParsedPlaylist? {
        try {
            // Web API - used by the web player
            val url = "https://music.163.com/weapi/v3/playlist/detail"

            // Simple params without encryption (may work for some requests)
            val params = """{"id":"$playlistId","n":1000}"""
            val encodedParams = java.util.Base64.getEncoder().encodeToString(params.toByteArray())

            val formBody = okhttp3.FormBody.Builder()
                .add("params", encodedParams)
                .build()

            val request = Request.Builder()
                .url(url)
                .post(formBody)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://music.163.com/")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    Log.d(TAG, "Web API response (first 500 chars): ${body?.take(500)}")

                    if (body != null) {
                        val json = gson.fromJson(body, JsonObject::class.java)
                        val playlist = json.getAsJsonObject("playlist")

                        if (playlist != null && !playlist.isJsonNull) {
                            return parsePlaylistJson(playlist, playlistId, "web")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Web API exception: ${e.message}")
        }
        return null
    }

    private fun fetchPlaylistSongsLegacy(playlistId: String): ParsedPlaylist? {
        try {
            // Legacy API - may still work for some playlists
            val url = "https://music.163.com/api/playlist/detail?id=$playlistId"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 14_0 like Mac OS X) AppleWebKit/605.1.15")
                .header("Referer", "https://music.163.com/")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    Log.d(TAG, "Legacy API response (first 500 chars): ${body?.take(500)}")

                    if (body != null) {
                        val json = gson.fromJson(body, JsonObject::class.java)

                        // Legacy API has result.playlist structure
                        val result = json.getAsJsonObject("result")
                        if (result != null && !result.isJsonNull) {
                            return parsePlaylistJsonLegacy(result, playlistId)
                        }

                        // Or direct playlist structure
                        val playlist = json.getAsJsonObject("playlist")
                        if (playlist != null && !playlist.isJsonNull) {
                            return parsePlaylistJson(playlist, playlistId, "legacy")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy API exception: ${e.message}")
        }
        return null
    }

    private fun parsePlaylistJson(playlist: JsonObject, playlistId: String, apiVersion: String): ParsedPlaylist? {
        try {
            val name = playlist.get("name")?.let { if (it.isJsonNull) "" else it.asString } ?: ""
            val description = playlist.get("description")?.let { if (it.isJsonNull) "" else it.asString } ?: ""
            val coverUrl = playlist.get("coverImgUrl")?.let { if (it.isJsonNull) "" else it.asString }?.replace("http://", "https://") ?: ""
            val trackCount = playlist.get("trackCount")?.let { if (it.isJsonNull) 0 else it.asInt } ?: 0

            Log.d(TAG, "Parsing playlist: name=$name, trackCount=$trackCount")

            val songs = mutableListOf<ParsedSong>()

            // First, try to get all song IDs from trackIds array (this is always complete)
            val trackIdsElement = playlist.get("trackIds")
            val trackIds = if (trackIdsElement != null && !trackIdsElement.isJsonNull && trackIdsElement.isJsonArray) {
                trackIdsElement.asJsonArray
            } else {
                null
            }

            // If we have trackIds, use them to fetch all songs (API may limit tracks array)
            if (trackIds != null && trackIds.size() > 0) {
                Log.d(TAG, "Found ${trackIds.size()} trackIds, fetching song details")
                val ids = mutableListOf<Long>()
                // Support up to 500 songs (will be fetched in batches)
                for (i in 0 until minOf(trackIds.size(), 500)) {
                    try {
                        val idObj = trackIds[i].asJsonObject
                        val id = idObj.get("id")?.asLong
                        if (id != null) {
                            ids.add(id)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse trackId: ${e.message}")
                    }
                }
                if (ids.isNotEmpty()) {
                    val fetchedSongs = fetchSongsByIds(ids)
                    songs.addAll(fetchedSongs)
                    Log.d(TAG, "Fetched ${songs.size} songs from trackIds")
                }
            }

            // Fallback: if trackIds didn't work, try tracks array
            if (songs.isEmpty()) {
                val tracksElement = playlist.get("tracks")
                val tracks = if (tracksElement != null && !tracksElement.isJsonNull && tracksElement.isJsonArray) {
                    tracksElement.asJsonArray
                } else {
                    null
                }

                if (tracks != null && tracks.size() > 0) {
                    Log.d(TAG, "Using tracks array fallback (${tracks.size()} tracks)")
                    for (track in tracks) {
                        try {
                            val song = track.asJsonObject
                            val songId = song.get("id")?.asLong?.toString() ?: continue

                            val title = song.get("name")?.asString ?: ""

                            // v3/v6 uses 'ar' for artists
                            val artists = song.getAsJsonArray("ar")
                            val artist = if (artists != null && artists.size() > 0) {
                                artists.mapNotNull { it.asJsonObject.get("name")?.asString }.joinToString("/")
                            } else ""

                            // v3/v6 uses 'al' for album
                            val album = song.getAsJsonObject("al")
                            val albumName = album?.get("name")?.asString ?: ""
                            val songCoverUrl = album?.get("picUrl")?.asString?.replace("http://", "https://") ?: ""

                            songs.add(ParsedSong(
                                platform = platformName,
                                platformSongId = songId,
                                deepLink = generateDeepLink(songId),
                                fallbackUrl = generateFallbackUrl(songId),
                                title = title,
                                artist = artist,
                                album = albumName,
                                coverUrl = songCoverUrl
                            ))
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse track: ${e.message}")
                        }
                    }
                }
            }

            Log.d(TAG, "Parsed NetEase playlist ($apiVersion): $name with ${songs.size} songs (total: $trackCount)")

            return ParsedPlaylist(
                platform = platformName,
                playlistId = playlistId,
                name = name,
                description = description,
                coverUrl = coverUrl,
                songCount = trackCount,
                songs = songs
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse playlist JSON: ${e.message}")
        }
        return null
    }

    private fun parsePlaylistJsonLegacy(result: JsonObject, playlistId: String): ParsedPlaylist? {
        try {
            val name = result.get("name")?.let { if (it.isJsonNull) "" else it.asString } ?: ""
            val description = result.get("description")?.let { if (it.isJsonNull) "" else it.asString } ?: ""
            val coverUrl = result.get("coverImgUrl")?.let { if (it.isJsonNull) "" else it.asString }?.replace("http://", "https://") ?: ""
            val trackCount = result.get("trackCount")?.let { if (it.isJsonNull) 0 else it.asInt } ?: 0

            val songs = mutableListOf<ParsedSong>()

            // Check if tracks is null or JsonNull
            val tracksElement = result.get("tracks")
            val tracks = if (tracksElement != null && !tracksElement.isJsonNull && tracksElement.isJsonArray) {
                tracksElement.asJsonArray
            } else {
                null
            }

            if (tracks != null) {
                for (track in tracks) {
                    try {
                        val song = track.asJsonObject
                        val songId = song.get("id")?.asLong?.toString() ?: continue

                        val title = song.get("name")?.asString ?: ""

                        // Legacy API uses 'artists' array
                        val artists = song.getAsJsonArray("artists")
                        val artist = if (artists != null && artists.size() > 0) {
                            artists.mapNotNull { it.asJsonObject.get("name")?.asString }.joinToString("/")
                        } else ""

                        // Legacy API uses 'album' object
                        val album = song.getAsJsonObject("album")
                        val albumName = album?.get("name")?.asString ?: ""
                        val songCoverUrl = album?.get("picUrl")?.asString?.replace("http://", "https://") ?: ""

                        songs.add(ParsedSong(
                            platform = platformName,
                            platformSongId = songId,
                            deepLink = generateDeepLink(songId),
                            fallbackUrl = generateFallbackUrl(songId),
                            title = title,
                            artist = artist,
                            album = albumName,
                            coverUrl = songCoverUrl
                        ))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse legacy track: ${e.message}")
                    }
                }
            }

            Log.d(TAG, "Parsed NetEase playlist (legacy): $name with ${songs.size} songs")

            return ParsedPlaylist(
                platform = platformName,
                playlistId = playlistId,
                name = name,
                description = description,
                coverUrl = coverUrl,
                songCount = trackCount,
                songs = songs
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse legacy playlist JSON: ${e.message}")
        }
        return null
    }

    private fun fetchSongsByIds(ids: List<Long>): List<ParsedSong> {
        val songMap = mutableMapOf<Long, ParsedSong>()

        // Process in batches of 50 to avoid URL length limits
        val batchSize = 50
        val batches = ids.chunked(batchSize)

        Log.d(TAG, "Fetching ${ids.size} songs in ${batches.size} batches")

        for ((batchIndex, batch) in batches.withIndex()) {
            try {
                // Use POST request to avoid URL length limits
                val url = "https://music.163.com/api/v3/song/detail"

                val idsJson = batch.joinToString(",") { """{"id":$it}""" }
                val formBody = okhttp3.FormBody.Builder()
                    .add("c", "[$idsJson]")
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(formBody)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://music.163.com/")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = gson.fromJson(body, JsonObject::class.java)
                            val songsArray = json.getAsJsonArray("songs")

                            if (songsArray != null) {
                                for (songJson in songsArray) {
                                    try {
                                        val song = songJson.asJsonObject
                                        val songIdLong = song.get("id")?.asLong ?: continue
                                        val songId = songIdLong.toString()
                                        val title = song.get("name")?.asString ?: ""

                                        val artists = song.getAsJsonArray("ar")
                                        val artist = if (artists != null && artists.size() > 0) {
                                            artists.mapNotNull { it.asJsonObject.get("name")?.asString }.joinToString("/")
                                        } else ""

                                        val album = song.getAsJsonObject("al")
                                        val albumName = album?.get("name")?.asString ?: ""
                                        val songCoverUrl = album?.get("picUrl")?.asString?.replace("http://", "https://") ?: ""

                                        songMap[songIdLong] = ParsedSong(
                                            platform = platformName,
                                            platformSongId = songId,
                                            deepLink = generateDeepLink(songId),
                                            fallbackUrl = generateFallbackUrl(songId),
                                            title = title,
                                            artist = artist,
                                            album = albumName,
                                            coverUrl = songCoverUrl
                                        )
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to parse song detail: ${e.message}")
                                    }
                                }
                                Log.d(TAG, "Batch ${batchIndex + 1}/${batches.size}: fetched ${songsArray.size()} songs")
                            }
                        }
                    } else {
                        Log.w(TAG, "Batch ${batchIndex + 1} failed with status: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch batch ${batchIndex + 1}: ${e.message}")
            }
        }

        Log.d(TAG, "Total songs fetched: ${songMap.size} / ${ids.size}")

        // Return songs in the original order of ids
        return ids.mapNotNull { songMap[it] }
    }

    /**
     * Fetch popular playlists by category for browse/discover.
     */
    suspend fun fetchCategoryPlaylists(category: String, limit: Int = 30, offset: Int = 0): List<DiscoverPlaylistInfo> {
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<DiscoverPlaylistInfo>()
            try {
                val encodedCat = URLEncoder.encode(category, "UTF-8")
                val url = "https://music.163.com/api/playlist/list?cat=$encodedCat&order=hot&limit=$limit&offset=$offset"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://music.163.com/")
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = gson.fromJson(body, JsonObject::class.java)
                            val playlists = json.getAsJsonArray("playlists")

                            if (playlists != null) {
                                for (item in playlists) {
                                    try {
                                        val pl = item.asJsonObject
                                        val id = pl.get("id")?.asLong?.toString() ?: continue
                                        val name = pl.get("name")?.asString ?: ""
                                        val coverUrl = pl.get("coverImgUrl")?.asString?.replace("http://", "https://") ?: ""
                                        val playCount = pl.get("playCount")?.asLong ?: 0
                                        val trackCount = pl.get("trackCount")?.asInt ?: 0
                                        val creator = pl.getAsJsonObject("creator")?.get("nickname")?.asString ?: ""

                                        results.add(DiscoverPlaylistInfo(
                                            platform = platformName,
                                            playlistId = id,
                                            name = name,
                                            coverUrl = coverUrl,
                                            playCount = playCount,
                                            songCount = trackCount,
                                            creator = creator
                                        ))
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to parse playlist item: ${e.message}")
                                    }
                                }
                            }
                        }
                    } else {
                        Log.w(TAG, "fetchCategoryPlaylists failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchCategoryPlaylists error: ${e.message}")
            }
            Log.d(TAG, "Fetched ${results.size} playlists for category: $category")
            results
        }
    }

    /**
     * Fetch daily personalized recommendations.
     * Requires MUSIC_U auth cookie.
     */
    suspend fun fetchDailyRecommendations(authCookies: String): List<ParsedSong> {
        return withContext(Dispatchers.IO) {
            val songs = mutableListOf<ParsedSong>()
            try {
                val url = "https://music.163.com/api/v3/discovery/recommend/songs"

                val formBody = okhttp3.FormBody.Builder()
                    .add("limit", "30")
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(formBody)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://music.163.com/")
                    .header("Cookie", authCookies)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = gson.fromJson(body, JsonObject::class.java)
                            val data = json.getAsJsonObject("data")
                            val dailySongs = data?.getAsJsonArray("dailySongs")

                            if (dailySongs != null) {
                                for (item in dailySongs) {
                                    try {
                                        val song = item.asJsonObject
                                        val songId = song.get("id")?.asLong?.toString() ?: continue
                                        val title = song.get("name")?.asString ?: ""

                                        val artists = song.getAsJsonArray("ar")
                                        val artist = if (artists != null && artists.size() > 0) {
                                            artists.mapNotNull { it.asJsonObject.get("name")?.asString }.joinToString("/")
                                        } else ""

                                        val album = song.getAsJsonObject("al")
                                        val albumName = album?.get("name")?.asString ?: ""
                                        val coverUrl = album?.get("picUrl")?.asString?.replace("http://", "https://") ?: ""

                                        songs.add(ParsedSong(
                                            platform = platformName,
                                            platformSongId = songId,
                                            deepLink = generateDeepLink(songId),
                                            fallbackUrl = generateFallbackUrl(songId),
                                            title = title,
                                            artist = artist,
                                            album = albumName,
                                            coverUrl = coverUrl
                                        ))
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to parse recommendation: ${e.message}")
                                    }
                                }
                            }
                        }
                    } else {
                        Log.w(TAG, "fetchDailyRecommendations failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchDailyRecommendations error: ${e.message}")
            }
            Log.d(TAG, "Fetched ${songs.size} daily recommendations")
            songs
        }
    }

    companion object {
        private const val TAG = "NetEasePlatform"
    }
}
