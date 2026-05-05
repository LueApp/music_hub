package com.musichub.remote

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.musichub.MusicHubApplication
import com.musichub.data.model.Song
import com.musichub.service.MediaMonitorService
import com.musichub.service.PlaybackService
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.*
import java.io.IOException

/**
 * Embedded HTTP + WebSocket server for remote control.
 * Runs on the player phone, receives commands from the controller phone.
 */
class RemoteServer(port: Int = RemoteMode.DEFAULT_PORT) : NanoWSD(port) {

    private val TAG = "RemoteServer"
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectedClients = mutableListOf<RemoteWebSocket>()
    private var broadcastJob: Job? = null
    private val broadcastScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return RemoteWebSocket(handshake)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // Let NanoWSD handle WebSocket upgrade requests
        if (isWebsocketRequested(session)) {
            return super.serve(session)
        }

        Log.d(TAG, "$method $uri")

        return try {
            when {
                // Playback state
                method == Method.GET && uri == "/api/state" -> handleGetState()

                // Playback controls
                method == Method.POST && uri == "/api/play/next" -> handlePlayNext()
                method == Method.POST && uri == "/api/play/previous" -> handlePlayPrevious()
                method == Method.POST && uri == "/api/play/pause" -> handleTogglePlayPause()
                method == Method.POST && uri.startsWith("/api/play/index/") -> handlePlayAtIndex(uri)
                method == Method.POST && uri.startsWith("/api/seek/") -> handleSeek(uri)
                method == Method.POST && uri == "/api/shuffle" -> handleToggleShuffle()
                method == Method.POST && uri == "/api/repeat" -> handleToggleRepeat()

                // Queue
                method == Method.GET && uri == "/api/queue" -> handleGetQueue()

                // Library
                method == Method.GET && uri == "/api/playlists" -> handleGetPlaylists()
                method == Method.GET && uri.matches(Regex("/api/playlists/\\d+/songs")) -> handleGetPlaylistSongs(uri)
                method == Method.GET && uri == "/api/songs" -> handleGetAllSongs()

                // Playlist management
                method == Method.POST && uri.matches(Regex("/api/playlists/\\d+/import")) -> handleImportSongsToPlaylist(session, uri)

                // Play from library
                method == Method.POST && uri.matches(Regex("/api/play/song/\\d+")) -> handlePlaySong(uri)
                method == Method.POST && uri.matches(Regex("/api/play/playlist/\\d+")) -> handlePlayPlaylist(session, uri)

                else -> jsonResponse(Response.Status.NOT_FOUND, mapOf("error" to "Not found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $method $uri: ${e.message}")
            jsonResponse(Response.Status.INTERNAL_ERROR, mapOf("error" to e.message))
        }
    }

    // --- Playback State ---

    private fun handleGetState(): Response {
        val state = buildCurrentState()
        return jsonResponse(Response.Status.OK, state)
    }

    private fun buildCurrentState(): RemoteState {
        val playback = PlaybackService.getInstance()
        val monitor = MediaMonitorService.getInstance()
        val playbackInfo = monitor?.getPlaybackInfo()
        val currentSong = playback?.getCurrentSong()

        return RemoteState(
            isPlaying = playbackInfo?.isPlaying ?: false,
            position = playbackInfo?.position ?: 0,
            duration = playbackInfo?.duration ?: 0,
            repeatMode = playback?.getRepeatMode()?.name ?: "OFF",
            shuffleEnabled = playback?.isShuffleEnabled() ?: false,
            currentSong = currentSong?.toRemoteSong(),
            currentIndex = playback?.getCurrentIndex() ?: -1,
            queueSize = playback?.getQueue()?.size ?: 0,
            shuffleOrder = playback?.getShuffleOrder()
        )
    }

    // --- Playback Controls ---

    private fun handlePlayNext(): Response {
        mainHandler.post { PlaybackService.getInstance()?.playNext() }
        return jsonResponse(Response.Status.OK, mapOf("ok" to true))
    }

    private fun handlePlayPrevious(): Response {
        mainHandler.post { PlaybackService.getInstance()?.playPrevious() }
        return jsonResponse(Response.Status.OK, mapOf("ok" to true))
    }

    private fun handleTogglePlayPause(): Response {
        mainHandler.post { MediaMonitorService.getInstance()?.togglePlayPause() }
        return jsonResponse(Response.Status.OK, mapOf("ok" to true))
    }

    private fun handlePlayAtIndex(uri: String): Response {
        val index = uri.substringAfterLast("/").toIntOrNull()
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Invalid index"))
        mainHandler.post { PlaybackService.getInstance()?.playAtIndex(index) }
        return jsonResponse(Response.Status.OK, mapOf("ok" to true))
    }

    private fun handleSeek(uri: String): Response {
        val positionMs = uri.substringAfterLast("/").toLongOrNull()
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Invalid position"))
        mainHandler.post { MediaMonitorService.getInstance()?.seekTo(positionMs) }
        return jsonResponse(Response.Status.OK, mapOf("ok" to true))
    }

    private fun handleToggleShuffle(): Response {
        mainHandler.post { PlaybackService.getInstance()?.toggleShuffle() }
        return jsonResponse(Response.Status.OK, mapOf("ok" to true))
    }

    private fun handleToggleRepeat(): Response {
        mainHandler.post { PlaybackService.getInstance()?.toggleRepeatMode() }
        return jsonResponse(Response.Status.OK, mapOf("ok" to true))
    }

    // --- Queue ---

    private fun handleGetQueue(): Response {
        val queue = PlaybackService.getInstance()?.getQueue()?.map { it.toRemoteSong() } ?: emptyList()
        return jsonResponse(Response.Status.OK, queue)
    }

    // --- Library ---

    private fun handleGetPlaylists(): Response {
        val result = runBlocking(Dispatchers.IO) {
            val db = MusicHubApplication.getInstance().database
            val playlists = db.playlistDao().getAllPlaylistsList()
            playlists.map { playlist ->
                val count = db.playlistItemDao().getSongCountValue(playlist.id)
                playlist.toRemotePlaylist(count)
            }
        }
        return jsonResponse(Response.Status.OK, result)
    }

    private fun handleGetPlaylistSongs(uri: String): Response {
        val playlistId = uri.split("/").dropLast(1).last().toLongOrNull()
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Invalid playlist ID"))

        val songs = runBlocking(Dispatchers.IO) {
            val db = MusicHubApplication.getInstance().database
            db.playlistItemDao().getSongsForPlaylistList(playlistId).map { it.toRemoteSong() }
        }
        return jsonResponse(Response.Status.OK, songs)
    }

    private fun handleGetAllSongs(): Response {
        val songs = runBlocking(Dispatchers.IO) {
            val db = MusicHubApplication.getInstance().database
            db.songDao().getAllSongsList().map { it.toRemoteSong() }
        }
        return jsonResponse(Response.Status.OK, songs)
    }

    // --- Playlist Management ---

    private fun handleImportSongsToPlaylist(session: IHTTPSession, uri: String): Response {
        val playlistId = uri.split("/").dropLast(1).last().toLongOrNull()
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Invalid playlist ID"))

        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        session.inputStream.read(body, 0, contentLength)
        val bodyStr = String(body)

        val songIds: List<Long> = try {
            val type = com.google.gson.reflect.TypeToken.getParameterized(
                List::class.java, java.lang.Long::class.java
            ).type
            gson.fromJson(bodyStr, type) ?: emptyList()
        } catch (e: Exception) {
            return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Invalid JSON body"))
        }

        if (songIds.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "No song IDs provided"))
        }

        runBlocking(Dispatchers.IO) {
            MusicHubApplication.getInstance().database.playlistItemDao()
                .addSongsToPlaylist(playlistId, songIds)
        }

        return jsonResponse(Response.Status.OK, mapOf("ok" to true, "added" to songIds.size))
    }

    // --- Play from Library ---

    private fun handlePlaySong(uri: String): Response {
        val songId = uri.substringAfterLast("/").toLongOrNull()
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Invalid song ID"))

        val song = runBlocking(Dispatchers.IO) {
            MusicHubApplication.getInstance().database.songDao().getById(songId)
        } ?: return jsonResponse(Response.Status.NOT_FOUND, mapOf("error" to "Song not found"))

        mainHandler.post { PlaybackService.getInstance()?.playSong(song) }
        return jsonResponse(Response.Status.OK, mapOf("ok" to true))
    }

    private fun handlePlayPlaylist(session: IHTTPSession, uri: String): Response {
        val playlistId = uri.substringAfterLast("/").toLongOrNull()
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Invalid playlist ID"))

        val shuffle = session.parms["shuffle"] == "true"

        val songs = runBlocking(Dispatchers.IO) {
            MusicHubApplication.getInstance().database.playlistItemDao()
                .getSongsForPlaylistList(playlistId)
        }

        if (songs.isEmpty()) {
            return jsonResponse(Response.Status.OK, mapOf("ok" to false, "error" to "Playlist is empty"))
        }

        mainHandler.post {
            val playback = PlaybackService.getInstance() ?: return@post
            // Enable/disable shuffle atomically before setting the queue
            if (shuffle && !playback.isShuffleEnabled()) {
                playback.setShuffleEnabled(true)
            } else if (!shuffle && playback.isShuffleEnabled()) {
                playback.setShuffleEnabled(false)
            }
            val startIndex = if (playback.isShuffleEnabled() && songs.size > 1) {
                (0 until songs.size).random()
            } else {
                0
            }
            playback.setQueue(songs, startIndex)
            playback.playAtIndex(startIndex)
        }
        return jsonResponse(Response.Status.OK, mapOf("ok" to true))
    }

    // --- WebSocket ---

    fun startBroadcasting() {
        broadcastJob = broadcastScope.launch {
            while (isActive) {
                try {
                    // Build state in its own try-catch so transient errors (e.g.
                    // ConcurrentModificationException during song transitions) don't
                    // kill the broadcast loop — we just skip this cycle.
                    val json = try {
                        val state = buildCurrentState()
                        gson.toJson(state)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error building state for broadcast: ${e.message}")
                        delay(500)
                        continue
                    }

                    synchronized(connectedClients) {
                        val iterator = connectedClients.iterator()
                        while (iterator.hasNext()) {
                            val client = iterator.next()
                            try {
                                client.send(json)
                            } catch (e: IOException) {
                                Log.d(TAG, "Removing disconnected WebSocket client")
                                iterator.remove()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Broadcast error: ${e.message}")
                }
                delay(500)
            }
        }
    }

    fun stopBroadcasting() {
        broadcastJob?.cancel()
        broadcastJob = null
    }

    // --- Helpers ---

    private fun jsonResponse(status: Response.Status, data: Any?): Response {
        val json = gson.toJson(data)
        return newFixedLengthResponse(status, "application/json", json)
    }

    // --- WebSocket Implementation ---

    inner class RemoteWebSocket(handshake: IHTTPSession) : WebSocket(handshake) {

        override fun onOpen() {
            Log.d(TAG, "WebSocket client connected")
            synchronized(connectedClients) {
                connectedClients.add(this)
            }
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
            Log.d(TAG, "WebSocket client disconnected: $reason")
            synchronized(connectedClients) {
                connectedClients.remove(this)
            }
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            // Controller -> Player messages could be handled here
            // For now, all commands go through REST API
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame) {}

        override fun onException(exception: IOException) {
            Log.d(TAG, "WebSocket exception: ${exception.message}")
            synchronized(connectedClients) {
                connectedClients.remove(this)
            }
        }
    }
}
