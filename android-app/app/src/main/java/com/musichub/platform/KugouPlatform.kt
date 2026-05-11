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
 * Handler for Kugou Music (酷狗音乐). Songs are identified by 32-char hex hash;
 * playlists ("songlists") are identified by the global-collection id (gcid).
 */
class KugouPlatform : PlatformHandler {

    override val platformName = Platforms.KUGOU
    override val displayName = "酷狗音乐"
    override val packageName = Platforms.PACKAGE_NAMES[Platforms.KUGOU]!!

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    private val queryHashPattern = Regex("""[?&]hash=([a-fA-F0-9]{32})""")
    private val pathHashPattern = Regex("""kugou\.com/(?:song|mixsong)/([a-fA-F0-9]{32})""")
    private val songlistGcidPattern = Regex("""kugou\.com/songlist/gcid_([a-zA-Z0-9]+)""")

    override fun canHandle(url: String): Boolean {
        return url.contains("kugou.com")
    }

    override fun parseSongUrl(url: String): ParsedSong? {
        val hash = (queryHashPattern.find(url) ?: pathHashPattern.find(url))
            ?.groupValues?.get(1)?.lowercase() ?: return null

        return ParsedSong(
            platform = platformName,
            platformSongId = hash,
            deepLink = generateDeepLink(hash),
            fallbackUrl = generateFallbackUrl(hash)
        )
    }

    override fun parsePlaylistUrl(url: String): ParsedPlaylist? {
        val match = songlistGcidPattern.find(url) ?: return null
        val gcid = match.groupValues[1]
        Log.d(TAG, "Parsed Kugou songlist gcid: $gcid from URL: $url")
        return ParsedPlaylist(platform = platformName, playlistId = gcid)
    }

    override fun generateDeepLink(platformSongId: String): String {
        // Kugou's custom `kugou://start.weixin?...` scheme expects a URL-encoded JSON
        // payload (not key=value query params), and the app silently falls back to a
        // wrong song when the payload doesn't parse. The Kugou app also registers an
        // HTTPS app-link intent filter for `m.kugou.com/song`, which resolves the
        // hash server-side and shows the correct track. We use that as the primary
        // deep link and let `setPackage("com.kugou.android")` route it to the app.
        return "https://m.kugou.com/song/?hash=$platformSongId"
    }

    override fun generateFallbackUrl(platformSongId: String): String {
        return "https://m.kugou.com/song/?hash=$platformSongId"
    }

    override suspend fun fetchMetadata(platformSongId: String): Map<String, String> {
        return withContext(Dispatchers.IO) {
            val result = mutableMapOf<String, String>()
            try {
                val url = "https://mobilecdnbj.kugou.com/api/v3/song/info?hash=$platformSongId"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36")
                    .header("Referer", "https://m.kugou.com/")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Metadata fetch HTTP ${response.code} for $platformSongId")
                        return@withContext result
                    }
                    val body = response.body?.string() ?: return@withContext result
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val data = json.getAsJsonObject("data") ?: return@withContext result

                    result["title"] = data.get("songname")?.asString
                        ?: data.get("filename")?.asString.orEmpty()
                    result["artist"] = data.get("singername")?.asString
                        ?: data.get("author_name")?.asString.orEmpty()
                    result["album"] = data.get("album_name")?.asString.orEmpty()

                    val image = data.get("image")?.asString
                        ?: data.get("img")?.asString
                        ?: data.get("imgurl")?.asString
                    if (!image.isNullOrEmpty()) {
                        result["cover_url"] = image
                            .replace("http://", "https://")
                            .replace("{size}", "120")
                    }

                    Log.d(TAG, "Fetched Kugou metadata: ${result["title"]} - ${result["artist"]}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch Kugou metadata for $platformSongId: ${e.message}")
            }
            result
        }
    }

    override suspend fun checkSongAvailability(platformSongId: String): SongAvailability {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://mobilecdnbj.kugou.com/api/v3/song/info?hash=$platformSongId"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36")
                    .header("Referer", "https://m.kugou.com/")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext SongAvailability(false, "API请求失败 (${response.code})")
                    }
                    val body = response.body?.string()
                        ?: return@withContext SongAvailability(false, "空响应")
                    val json = gson.fromJson(body, JsonObject::class.java)

                    // Kugou v3 uses status: 1 = success, 0 = error.
                    val status = json.get("status")?.asInt ?: 0
                    if (status != 1) {
                        val msg = json.get("error")?.asString
                            ?: json.get("error_msg")?.asString
                            ?: "歌曲不存在"
                        return@withContext SongAvailability(false, msg)
                    }
                    val data = json.getAsJsonObject("data")
                        ?: return@withContext SongAvailability(false, "数据为空")
                    val title = data.get("songname")?.asString
                        ?: data.get("filename")?.asString.orEmpty()
                    if (title.isEmpty()) {
                        return@withContext SongAvailability(false, "无法获取歌曲信息")
                    }
                    SongAvailability(true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Availability check failed for $platformSongId: ${e.message}")
                // Network error → fail-open so playback isn't blocked.
                SongAvailability(true)
            }
        }
    }

    override suspend fun fetchPlaylistSongs(playlistId: String): ParsedPlaylist? {
        return withContext(Dispatchers.IO) {
            try {
                // Kugou's public songlist APIs (m3ws.kugou.com/share/list,
                // m.kugou.com/plist/list, wwwapi.kugou.com/v1/playlist/list_info)
                // either return "No Action Found" or a 404 page without auth.
                // The share page itself server-side-renders the full song list as
                // `window.$output = {info: {listinfo:{...}, songs:[...]}}` — so we
                // fetch that page and scrape the embedded JSON.
                val url = "https://m.kugou.com/songlist/gcid_$playlistId/"
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Mobile Safari/537.36"
                    )
                    .header("Referer", "https://m.kugou.com/")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Songlist page failed: HTTP ${response.code}")
                        return@withContext null
                    }
                    val html = response.body?.string()
                    if (html.isNullOrEmpty()) {
                        Log.e(TAG, "Songlist page returned empty body")
                        return@withContext null
                    }

                    val output = extractWindowOutput(html)
                    if (output == null) {
                        Log.e(TAG, "Could not find window.\$output JSON in songlist page")
                        return@withContext null
                    }

                    val info = output.getAsJsonObject("info")
                        ?: return@withContext null.also {
                            Log.e(TAG, "Songlist page missing info{}")
                        }
                    val listInfo = info.getAsJsonObject("listinfo")
                    val songsArray = info.getAsJsonArray("songs")
                    if (songsArray == null) {
                        Log.e(TAG, "Songlist page missing info.songs[]")
                        return@withContext null
                    }

                    val playlistName = listInfo?.get("name")?.asString.orEmpty()
                    val playlistCover = (listInfo?.get("pic")?.asString.orEmpty())
                        .replace("http://", "https://")
                        .replace("{size}", "400")

                    val songs = mutableListOf<ParsedSong>()
                    for (element in songsArray) {
                        try {
                            val item = element.asJsonObject
                            val hash = (item.get("hash")?.asString
                                ?: item.get("filehash")?.asString
                                ?: continue).lowercase()

                            val rawName = item.get("name")?.asString
                                ?: item.get("filename")?.asString.orEmpty()
                            val title: String
                            val artistFromName: String
                            if (rawName.contains(" - ")) {
                                val parts = rawName.split(" - ", limit = 2)
                                artistFromName = parts[0].trim()
                                title = parts[1].trim()
                            } else {
                                title = rawName
                                artistFromName = ""
                            }
                            val artist = artistFromName.ifEmpty {
                                // Fall back to the singerinfo[].name array.
                                item.getAsJsonArray("singerinfo")
                                    ?.mapNotNull { it.asJsonObject.get("name")?.asString }
                                    ?.joinToString("、")
                                    ?: item.get("singername")?.asString
                                    ?: item.get("author_name")?.asString.orEmpty()
                            }

                            val album = item.getAsJsonObject("albuminfo")?.get("name")?.asString
                                ?: item.get("album_name")?.asString.orEmpty()

                            val cover = (item.get("cover")?.asString
                                ?: item.get("img")?.asString
                                ?: item.get("imgurl")?.asString.orEmpty())
                                .replace("http://", "https://")
                                .replace("{size}", "120")

                            // Songlist data embeds a per-song share URL like
                            // `https://m.kugou.com/mixsong/<short-id>.html` — that's
                            // the only URL Kugou's CDN serves without 403, and the
                            // Kugou app's intent-filter resolves it to the correct
                            // song. The hash-based `/song/?hash=` URL is rejected
                            // server-side ("No Action Found!"), so prefer _song_url
                            // when present.
                            val songUrl = item.get("_song_url")?.asString
                                ?.replace("http://", "https://")
                            val deepLink = songUrl ?: generateDeepLink(hash)
                            val fallbackUrl = songUrl ?: generateFallbackUrl(hash)

                            songs.add(
                                ParsedSong(
                                    platform = platformName,
                                    platformSongId = hash,
                                    deepLink = deepLink,
                                    fallbackUrl = fallbackUrl,
                                    title = title,
                                    artist = artist,
                                    album = album,
                                    coverUrl = cover
                                )
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse Kugou songlist item: ${e.message}")
                        }
                    }

                    Log.d(TAG, "Fetched ${songs.size} songs from Kugou songlist '$playlistName'")
                    ParsedPlaylist(
                        platform = platformName,
                        playlistId = playlistId,
                        name = playlistName,
                        coverUrl = playlistCover,
                        songCount = songs.size,
                        songs = songs
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch Kugou songlist $playlistId: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Locate `window.$output = { ... };` in the songlist HTML and return the
     * parsed object. We bracket-balance the JSON literal because regex isn't
     * sufficient for nested braces inside string values.
     */
    private fun extractWindowOutput(html: String): JsonObject? {
        val marker = "window.\$output"
        var i = html.indexOf(marker)
        if (i < 0) return null
        i = html.indexOf('{', i)
        if (i < 0) return null
        val start = i
        var depth = 0
        var inStr = false
        var esc = false
        while (i < html.length) {
            val c = html[i]
            if (esc) {
                esc = false
            } else if (inStr) {
                when (c) {
                    '\\' -> esc = true
                    '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val literal = html.substring(start, i + 1)
                            return try {
                                gson.fromJson(literal, JsonObject::class.java)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse window.\$output JSON: ${e.message}")
                                null
                            }
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    companion object {
        private const val TAG = "KugouPlatform"
    }
}
