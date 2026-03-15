package com.musichub.platform

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.musichub.data.model.ParsedSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Handler for QQ Music (QQ音乐).
 */
class QQMusicPlatform : PlatformHandler {

    override val platformName = Platforms.QQMUSIC
    override val displayName = "QQ音乐"
    override val packageName = Platforms.PACKAGE_NAMES[Platforms.QQMUSIC]!!

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    // Regex patterns for parsing QQ Music URLs
    private val songMidPatterns = listOf(
        Regex("""y\.qq\.com.*songDetail/([a-zA-Z0-9]+)"""),
        Regex("""songmid=([a-zA-Z0-9]+)""")
    )

    private val playlistIdPatterns = listOf(
        Regex("""y\.qq\.com.*playlist/(\d+)"""),
        Regex("""y\.qq\.com.*playsquare/(\d+)"""),
        Regex("""y\.qq\.com.*taoge/(\d+)"""),
        Regex("""i\.y\.qq\.com.*details.*[?&#]id=(\d+)"""),
        Regex("""i2\.y\.qq\.com.*[?&#]id=(\d+)"""),
        Regex("""i3\.y\.qq\.com.*[?&#]id=(\d+)"""),
        Regex("""y\.qq\.com.*[?&#]id=(\d+)""")
    )

    override fun canHandle(url: String): Boolean {
        return url.contains("y.qq.com") || url.contains("qq.com/base/fcgi-bin") || url.contains("i.y.qq.com") || url.contains("i2.y.qq.com") || url.contains("i3.y.qq.com")
    }

    override fun parsePlaylistUrl(url: String): ParsedPlaylist? {
        for (pattern in playlistIdPatterns) {
            val match = pattern.find(url)
            if (match != null) {
                val playlistId = match.groupValues[1]
                Log.d(TAG, "Parsed QQ Music playlist ID: $playlistId from URL: $url")
                return ParsedPlaylist(
                    platform = platformName,
                    playlistId = playlistId
                )
            }
        }
        return null
    }

    override fun parseSongUrl(url: String): ParsedSong? {
        for (pattern in songMidPatterns) {
            val match = pattern.find(url)
            if (match != null) {
                val songMid = match.groupValues[1]
                return ParsedSong(
                    platform = platformName,
                    platformSongId = songMid,
                    deepLink = generateDeepLink(songMid),
                    fallbackUrl = generateFallbackUrl(songMid)
                )
            }
        }
        return null
    }

    override fun generateDeepLink(platformSongId: String): String {
        // QQ Music deep link format with JSON payload and play action
        val payload = """{"song":[{"songmid":"$platformSongId"}],"play":1}"""
        val encodedPayload = URLEncoder.encode(payload, "UTF-8")
        return "qqmusic://qq.com/media/playSonglist?p=$encodedPayload"
    }

    override fun generateFallbackUrl(platformSongId: String): String {
        return "https://y.qq.com/n/ryqq/songDetail/$platformSongId"
    }

    override suspend fun checkSongAvailability(platformSongId: String): SongAvailability {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://u.y.qq.com/cgi-bin/musicu.fcg"

                val payload = JsonObject().apply {
                    add("songinfo", JsonObject().apply {
                        addProperty("method", "get_song_detail_yqq")
                        addProperty("module", "music.pf_song_detail_svr")
                        add("param", JsonObject().apply {
                            addProperty("song_mid", platformSongId)
                        })
                    })
                }

                val requestBody = payload.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://y.qq.com/")
                    .header("Content-Type", "application/json")
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
                    val songInfo = json.getAsJsonObject("songinfo")
                    val data = songInfo?.getAsJsonObject("data")
                    val trackInfo = data?.getAsJsonObject("track_info")

                    if (trackInfo == null) {
                        return@withContext SongAvailability(false, "歌曲不存在")
                    }

                    val title = trackInfo.get("name")?.asString ?: ""
                    if (title.isEmpty()) {
                        return@withContext SongAvailability(false, "歌曲不存在")
                    }

                    // Check fnote field - QQ Music uses this to indicate takedown
                    val fnote = trackInfo.get("fnote")?.asInt ?: 0
                    if (fnote == 4001) {
                        Log.d(TAG, "Song $platformSongId unavailable: fnote=$fnote")
                        return@withContext SongAvailability(false, "歌曲已下架")
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
                val url = "https://u.y.qq.com/cgi-bin/musicu.fcg"

                val payload = JsonObject().apply {
                    add("songinfo", JsonObject().apply {
                        addProperty("method", "get_song_detail_yqq")
                        addProperty("module", "music.pf_song_detail_svr")
                        add("param", JsonObject().apply {
                            addProperty("song_mid", platformSongId)
                        })
                    })
                }

                val requestBody = payload.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://y.qq.com/")
                    .header("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = gson.fromJson(body, JsonObject::class.java)
                            val songInfo = json.getAsJsonObject("songinfo")
                            val data = songInfo?.getAsJsonObject("data")
                            val trackInfo = data?.getAsJsonObject("track_info")

                            if (trackInfo != null) {
                                result["title"] = trackInfo.get("name")?.asString ?: ""

                                // Artists from 'singer' array
                                val singers = trackInfo.getAsJsonArray("singer")
                                if (singers != null && singers.size() > 0) {
                                    result["artist"] = singers.mapNotNull {
                                        it.asJsonObject.get("name")?.asString
                                    }.joinToString("/")
                                }

                                // Album info
                                val album = trackInfo.getAsJsonObject("album")
                                if (album != null) {
                                    result["album"] = album.get("name")?.asString ?: ""

                                    // Cover URL from album mid
                                    val albumMid = album.get("mid")?.asString
                                    if (albumMid != null) {
                                        result["cover_url"] = "https://y.qq.com/music/photo_new/T002R300x300M000${albumMid}.jpg"
                                    }
                                }

                                Log.d(TAG, "Fetched QQ Music metadata: ${result["title"]} - ${result["artist"]}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch QQ Music metadata for $platformSongId: ${e.message}")
            }

            result
        }
    }

    override suspend fun fetchPlaylistSongs(playlistId: String): ParsedPlaylist? {
        return withContext(Dispatchers.IO) {
            // Try V1 API with pagination support for large playlists (>500 songs)
            fetchPlaylistSongsV1WithPagination(playlistId)
                ?: fetchPlaylistSongsV2(playlistId)
                ?: fetchPlaylistSongsWeb(playlistId)
        }
    }

    /**
     * Helper data class to hold playlist metadata.
     */
    private data class PlaylistMetadata(
        val name: String,
        val description: String,
        val coverUrl: String,
        val songCount: Int
    )

    /**
     * Fetch playlist songs with pagination support (for playlists > 500 songs).
     */
    private fun fetchPlaylistSongsV1WithPagination(playlistId: String): ParsedPlaylist? {
        val allSongs = mutableListOf<ParsedSong>()
        var playlistInfo: PlaylistMetadata? = null
        var offset = 0
        val pageSize = 300  // Use smaller page size for better compatibility
        var totalSongCount = 0

        while (true) {
            val result = fetchPlaylistPageV1(playlistId, offset, pageSize)
            if (result == null) {
                // If first page fails, return null to try other methods
                if (offset == 0) return null
                // If subsequent pages fail, return what we have
                Log.w(TAG, "Failed to fetch page at offset $offset, returning ${allSongs.size} songs")
                break
            }

            // Store playlist metadata from first page (it has the total count)
            if (playlistInfo == null) {
                playlistInfo = result.first
                totalSongCount = playlistInfo.songCount
                Log.d(TAG, "Playlist metadata: name=${playlistInfo.name}, totalSongs=$totalSongCount")
            }

            val pageSongs = result.second
            allSongs.addAll(pageSongs)
            Log.d(TAG, "Fetched page at offset $offset: ${pageSongs.size} songs, total so far: ${allSongs.size}/$totalSongCount")

            // Exit condition 1: Empty page means no more songs
            if (pageSongs.isEmpty()) {
                Log.d(TAG, "Empty page received, stopping pagination")
                break
            }

            // Exit condition 2: We've reached or exceeded the total song count
            if (totalSongCount > 0 && allSongs.size >= totalSongCount) {
                Log.d(TAG, "Reached total song count ($totalSongCount)")
                break
            }

            // Exit condition 3: Page returned significantly fewer songs than requested
            // Use a threshold to handle API quirks (some songs might be unavailable)
            // Only stop if we got less than 50% of requested and we're past the first page
            if (pageSongs.size < pageSize / 2) {
                Log.d(TAG, "Very small page received (${pageSongs.size} < ${pageSize / 2}), likely end of list")
                break
            }

            offset += pageSize

            // Safety limit to prevent infinite loops
            if (offset > 5000) {
                Log.w(TAG, "Safety limit reached, stopping pagination at offset $offset")
                break
            }
        }

        if (playlistInfo == null || allSongs.isEmpty()) {
            return null
        }

        Log.d(TAG, "Fetched QQ Music playlist with pagination: ${playlistInfo.name} with ${allSongs.size}/${totalSongCount} songs")

        return ParsedPlaylist(
            platform = platformName,
            playlistId = playlistId,
            name = playlistInfo.name,
            description = playlistInfo.description,
            coverUrl = playlistInfo.coverUrl,
            songCount = totalSongCount,
            songs = allSongs
        )
    }

    /**
     * Fetch a single page of playlist songs.
     * @return Pair of (PlaylistMetadata, List<ParsedSong>) or null if failed
     */
    private fun fetchPlaylistPageV1(playlistId: String, offset: Int, limit: Int): Pair<PlaylistMetadata, List<ParsedSong>>? {
        try {
            val url = "https://u.y.qq.com/cgi-bin/musicu.fcg"

            val payload = JsonObject().apply {
                add("req_0", JsonObject().apply {
                    addProperty("module", "srf_diss_info.DissInfoServer")
                    addProperty("method", "CgiGetDiss")
                    add("param", JsonObject().apply {
                        addProperty("disstid", playlistId.toLong())
                        addProperty("onlysonglist", if (offset > 0) 1 else 0)  // Only song list for subsequent pages
                        addProperty("song_begin", offset)
                        addProperty("song_num", limit)
                    })
                })
            }

            Log.d(TAG, "V1 API request for playlist $playlistId, offset=$offset, limit=$limit, payload=${payload}")

            val requestBody = payload.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://y.qq.com/")
                .header("Origin", "https://y.qq.com")
                .header("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val json = gson.fromJson(body, JsonObject::class.java)
                        val req0 = json.getAsJsonObject("req_0")
                        val code = req0?.get("code")?.asInt ?: -1
                        Log.d(TAG, "V1 API response code: $code")

                        val data = req0?.getAsJsonObject("data")
                        val dirinfo = data?.getAsJsonObject("dirinfo")
                        val songlist = data?.getAsJsonArray("songlist")

                        Log.d(TAG, "V1 API response: dirinfo=${dirinfo != null}, songlist size=${songlist?.size() ?: 0}")

                        if (data != null) {
                            // Parse metadata (only needed on first page, but always parse for safety)
                            val metadata = if (dirinfo != null) {
                                val songCount = dirinfo.get("songnum")?.asInt ?: dirinfo.get("total_song_num")?.asInt ?: 0
                                PlaylistMetadata(
                                    name = dirinfo.get("title")?.asString ?: dirinfo.get("dissname")?.asString ?: "",
                                    description = dirinfo.get("desc")?.asString ?: "",
                                    coverUrl = dirinfo.get("pic")?.asString ?: dirinfo.get("picurl")?.asString ?: "",
                                    songCount = songCount
                                )
                            } else {
                                // For onlysonglist=1, dirinfo might not be present
                                PlaylistMetadata("", "", "", 0)
                            }

                            // Parse songs
                            val songs = parseQQSongList(songlist)
                            Log.d(TAG, "V1 page parsed: ${songs.size} songs at offset $offset")

                            return Pair(metadata, songs)
                        }
                    }
                } else {
                    Log.w(TAG, "V1 API page request failed with status: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "V1 API page exception: ${e.message}")
        }
        return null
    }

    /**
     * Parse a JSON array of songs into ParsedSong list.
     */
    private fun parseQQSongList(songlist: JsonArray?): List<ParsedSong> {
        val songs = mutableListOf<ParsedSong>()
        if (songlist == null) return songs

        for (track in songlist) {
            try {
                val song = track.asJsonObject
                val songMid = song.get("songmid")?.asString
                    ?: song.get("mid")?.asString
                    ?: continue

                val title = song.get("songname")?.asString
                    ?: song.get("name")?.asString
                    ?: ""

                val singers = song.getAsJsonArray("singer")
                val artist = if (singers != null && singers.size() > 0) {
                    singers.mapNotNull { it.asJsonObject.get("name")?.asString }.joinToString("/")
                } else ""

                val albumName = song.get("albumname")?.asString
                    ?: song.getAsJsonObject("album")?.get("name")?.asString
                    ?: ""
                val albumMid = song.get("albummid")?.asString
                    ?: song.getAsJsonObject("album")?.get("mid")?.asString
                val songCoverUrl = if (albumMid != null && albumMid.isNotEmpty()) {
                    "https://y.qq.com/music/photo_new/T002R300x300M000${albumMid}.jpg"
                } else ""

                songs.add(ParsedSong(
                    platform = platformName,
                    platformSongId = songMid,
                    deepLink = generateDeepLink(songMid),
                    fallbackUrl = generateFallbackUrl(songMid),
                    title = title,
                    artist = artist,
                    album = albumName,
                    coverUrl = songCoverUrl
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse track: ${e.message}")
            }
        }
        return songs
    }

    private fun fetchPlaylistSongsV1(playlistId: String): ParsedPlaylist? {
        try {
            val url = "https://u.y.qq.com/cgi-bin/musicu.fcg"

            // Use srf_diss_info module - more reliable for public playlists
            val payload = JsonObject().apply {
                add("req_0", JsonObject().apply {
                    addProperty("module", "srf_diss_info.DissInfoServer")
                    addProperty("method", "CgiGetDiss")
                    add("param", JsonObject().apply {
                        addProperty("disstid", playlistId.toLong())
                        addProperty("onlysonglist", 0)
                        addProperty("song_begin", 0)
                        addProperty("song_num", 500)
                    })
                })
            }

            Log.d(TAG, "V1 API request for playlist $playlistId")

            val requestBody = payload.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://y.qq.com/")
                .header("Origin", "https://y.qq.com")
                .header("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    Log.d(TAG, "V1 API response (first 500 chars): ${body?.take(500)}")

                    if (body != null) {
                        val json = gson.fromJson(body, JsonObject::class.java)
                        val req0 = json.getAsJsonObject("req_0")
                        val data = req0?.getAsJsonObject("data")
                        val dirinfo = data?.getAsJsonObject("dirinfo")

                        if (dirinfo != null) {
                            return parseQQPlaylistResponse(data, dirinfo, playlistId, "V1")
                        } else {
                            Log.w(TAG, "V1 API: dirinfo is null")
                        }
                    }
                } else {
                    Log.w(TAG, "V1 API failed with status: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "V1 API exception: ${e.message}")
        }
        return null
    }

    private fun fetchPlaylistSongsV2(playlistId: String): ParsedPlaylist? {
        try {
            val url = "https://u.y.qq.com/cgi-bin/musicu.fcg"

            // Alternative module
            val payload = JsonObject().apply {
                add("playlist", JsonObject().apply {
                    addProperty("method", "GetPlaylistInfos")
                    addProperty("module", "music.srfDissInfo.aiDissInfo")
                    add("param", JsonObject().apply {
                        addProperty("disstid", playlistId.toLong())
                        addProperty("dirid", 0)
                        addProperty("num", 500)
                    })
                })
            }

            Log.d(TAG, "V2 API request for playlist $playlistId")

            val requestBody = payload.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://y.qq.com/")
                .header("Origin", "https://y.qq.com")
                .header("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    Log.d(TAG, "V2 API response (first 500 chars): ${body?.take(500)}")

                    if (body != null) {
                        val json = gson.fromJson(body, JsonObject::class.java)
                        val playlistObj = json.getAsJsonObject("playlist")
                        val data = playlistObj?.getAsJsonObject("data")
                        val dirinfo = data?.getAsJsonObject("dirinfo")

                        if (dirinfo != null) {
                            return parseQQPlaylistResponse(data, dirinfo, playlistId, "V2")
                        } else {
                            Log.w(TAG, "V2 API: dirinfo is null")
                        }
                    }
                } else {
                    Log.w(TAG, "V2 API failed with status: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "V2 API exception: ${e.message}")
        }
        return null
    }

    private fun fetchPlaylistSongsWeb(playlistId: String): ParsedPlaylist? {
        try {
            // Try the web page scraping approach as fallback
            val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&utf8=1&disstid=$playlistId&format=json"

            Log.d(TAG, "Web API request for playlist $playlistId")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://y.qq.com/")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    Log.d(TAG, "Web API response (first 500 chars): ${body?.take(500)}")

                    if (body != null) {
                        val json = gson.fromJson(body, JsonObject::class.java)
                        val cdlist = json.getAsJsonArray("cdlist")

                        if (cdlist != null && cdlist.size() > 0) {
                            val cd = cdlist[0].asJsonObject
                            val name = cd.get("dissname")?.asString ?: ""
                            val description = cd.get("desc")?.asString ?: ""
                            val coverUrl = cd.get("logo")?.asString ?: ""
                            val songCount = cd.get("songnum")?.asInt ?: 0

                            val songs = mutableListOf<ParsedSong>()
                            val songlist = cd.getAsJsonArray("songlist")

                            if (songlist != null) {
                                for (track in songlist) {
                                    try {
                                        val song = track.asJsonObject
                                        val songMid = song.get("songmid")?.asString ?: continue

                                        val title = song.get("songname")?.asString ?: ""

                                        val singers = song.getAsJsonArray("singer")
                                        val artist = if (singers != null && singers.size() > 0) {
                                            singers.mapNotNull { it.asJsonObject.get("name")?.asString }.joinToString("/")
                                        } else ""

                                        val albumName = song.get("albumname")?.asString ?: ""
                                        val albumMid = song.get("albummid")?.asString
                                        val songCoverUrl = if (albumMid != null && albumMid.isNotEmpty()) {
                                            "https://y.qq.com/music/photo_new/T002R300x300M000${albumMid}.jpg"
                                        } else ""

                                        songs.add(ParsedSong(
                                            platform = platformName,
                                            platformSongId = songMid,
                                            deepLink = generateDeepLink(songMid),
                                            fallbackUrl = generateFallbackUrl(songMid),
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

                            Log.d(TAG, "Fetched QQ Music playlist (Web): $name with ${songs.size} songs")

                            return ParsedPlaylist(
                                platform = platformName,
                                playlistId = playlistId,
                                name = name,
                                description = description,
                                coverUrl = coverUrl,
                                songCount = songCount,
                                songs = songs
                            )
                        }
                    }
                } else {
                    Log.w(TAG, "Web API failed with status: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Web API exception: ${e.message}")
        }
        return null
    }

    private fun parseQQPlaylistResponse(data: JsonObject, dirinfo: JsonObject, playlistId: String, apiVersion: String): ParsedPlaylist {
        val name = dirinfo.get("title")?.asString ?: dirinfo.get("dissname")?.asString ?: ""
        val description = dirinfo.get("desc")?.asString ?: ""
        val coverUrl = dirinfo.get("pic")?.asString ?: dirinfo.get("picurl")?.asString ?: dirinfo.get("logo")?.asString ?: ""
        val songCount = dirinfo.get("songnum")?.asInt ?: 0

        val songs = mutableListOf<ParsedSong>()
        val songlist = data.getAsJsonArray("songlist")

        if (songlist != null) {
            for (track in songlist) {
                try {
                    val song = track.asJsonObject
                    val songMid = song.get("songmid")?.asString
                        ?: song.get("mid")?.asString
                        ?: continue

                    val title = song.get("songname")?.asString
                        ?: song.get("name")?.asString
                        ?: ""

                    val singers = song.getAsJsonArray("singer")
                    val artist = if (singers != null && singers.size() > 0) {
                        singers.mapNotNull { it.asJsonObject.get("name")?.asString }.joinToString("/")
                    } else ""

                    val albumName = song.get("albumname")?.asString
                        ?: song.getAsJsonObject("album")?.get("name")?.asString
                        ?: ""
                    val albumMid = song.get("albummid")?.asString
                        ?: song.getAsJsonObject("album")?.get("mid")?.asString
                    val songCoverUrl = if (albumMid != null && albumMid.isNotEmpty()) {
                        "https://y.qq.com/music/photo_new/T002R300x300M000${albumMid}.jpg"
                    } else ""

                    songs.add(ParsedSong(
                        platform = platformName,
                        platformSongId = songMid,
                        deepLink = generateDeepLink(songMid),
                        fallbackUrl = generateFallbackUrl(songMid),
                        title = title,
                        artist = artist,
                        album = albumName,
                        coverUrl = songCoverUrl
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse track in playlist: ${e.message}")
                }
            }
        }

        Log.d(TAG, "Fetched QQ Music playlist ($apiVersion): $name with ${songs.size} songs (total: $songCount)")

        return ParsedPlaylist(
            platform = platformName,
            playlistId = playlistId,
            name = name,
            description = description,
            coverUrl = coverUrl,
            songCount = songCount,
            songs = songs
        )
    }

    companion object {
        private const val TAG = "QQMusicPlatform"
    }
}
