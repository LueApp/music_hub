package com.musichub.platform

import android.util.Log
import com.musichub.data.model.ParsedSong

/**
 * Information about a chart/ranking.
 */
data class ChartInfo(
    val platform: String,
    val chartId: String,
    val name: String,
    val coverUrl: String = "",
    val updateFrequency: String = ""
)

/**
 * Information about a discovered playlist.
 */
data class DiscoverPlaylistInfo(
    val platform: String,
    val playlistId: String,
    val name: String,
    val coverUrl: String = "",
    val playCount: Long = 0,
    val songCount: Int = 0,
    val creator: String = ""
)

/**
 * Coordinator for discovery API calls across platforms.
 */
object DiscoveryApi {

    private const val TAG = "DiscoveryApi"

    private val neteasePlatform = NetEasePlatform()
    private val qqMusicPlatform = QQMusicPlatform()
    private val bilibiliPlatform = BilibiliPlatform()

    // Hardcoded NetEase chart playlist IDs
    private val neteaseCharts = listOf(
        ChartInfo(Platforms.NETEASE, "19723756", "飙升榜", updateFrequency = "每日更新"),
        ChartInfo(Platforms.NETEASE, "3779629", "新歌榜", updateFrequency = "每日更新"),
        ChartInfo(Platforms.NETEASE, "3778678", "热歌榜", updateFrequency = "每日更新"),
        ChartInfo(Platforms.NETEASE, "2884035", "原创榜", updateFrequency = "每周更新")
    )

    private val bilibiliCharts = listOf(
        ChartInfo(Platforms.BILIBILI, "music", "音乐排行", updateFrequency = "每日更新")
    )

    /**
     * Get all available charts across platforms.
     * NetEase and Bilibili charts are hardcoded.
     * QQ Music charts are fetched dynamically.
     */
    suspend fun fetchChartList(): List<ChartInfo> {
        val charts = mutableListOf<ChartInfo>()
        charts.addAll(neteaseCharts)

        // Fetch QQ Music charts dynamically
        try {
            val qqCharts = qqMusicPlatform.fetchToplistAll()
            charts.addAll(qqCharts)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch QQ Music charts", e)
        }

        charts.addAll(bilibiliCharts)
        return charts
    }

    /**
     * Fetch songs for a specific chart.
     */
    suspend fun fetchChartSongs(chart: ChartInfo): List<ParsedSong> {
        return when (chart.platform) {
            Platforms.NETEASE -> {
                // NetEase charts are playlists — reuse fetchPlaylistSongs
                val playlist = neteasePlatform.fetchPlaylistSongs(chart.chartId)
                playlist?.songs ?: emptyList()
            }
            Platforms.QQMUSIC -> {
                val playlist = qqMusicPlatform.fetchToplistDetail(chart.chartId.toIntOrNull() ?: 0, 100)
                playlist?.songs ?: emptyList()
            }
            Platforms.BILIBILI -> {
                val playlist = bilibiliPlatform.fetchMusicRanking()
                playlist?.songs ?: emptyList()
            }
            else -> emptyList()
        }
    }

    // Browse playlist categories (hardcoded for NetEase)
    private val neteaseCategories = listOf("华语", "欧美", "电子", "古风", "轻音乐", "说唱", "摇滚")

    /**
     * Get available browse categories.
     */
    fun fetchCategories(): List<String> {
        return neteaseCategories
    }

    /**
     * Fetch playlists for a category from NetEase.
     */
    suspend fun fetchCategoryPlaylists(category: String, limit: Int = 30, offset: Int = 0): List<DiscoverPlaylistInfo> {
        return try {
            neteasePlatform.fetchCategoryPlaylists(category, limit, offset)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch category playlists", e)
            emptyList()
        }
    }

    /**
     * Fetch popular playlists from QQ Music.
     */
    suspend fun fetchQQMusicPopularPlaylists(): List<DiscoverPlaylistInfo> {
        return try {
            qqMusicPlatform.fetchPlaylistSquare()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch QQ Music playlists", e)
            emptyList()
        }
    }

    /**
     * Fetch songs from a discovered playlist.
     */
    suspend fun fetchPlaylistSongs(platform: String, playlistId: String): ParsedPlaylist? {
        return when (platform) {
            Platforms.NETEASE -> neteasePlatform.fetchPlaylistSongs(playlistId)
            Platforms.QQMUSIC -> qqMusicPlatform.fetchPlaylistSongs(playlistId)
            Platforms.BILIBILI -> bilibiliPlatform.fetchPlaylistSongs(playlistId)
            else -> null
        }
    }

    /**
     * Fetch personalized daily recommendations for a platform.
     * Requires auth cookies.
     */
    suspend fun fetchRecommendations(platform: String, authCookies: String): List<ParsedSong> {
        return try {
            when (platform) {
                Platforms.NETEASE -> neteasePlatform.fetchDailyRecommendations(authCookies)
                Platforms.QQMUSIC -> qqMusicPlatform.fetchDailyRecommendations(authCookies)
                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch recommendations for $platform", e)
            emptyList()
        }
    }
}
