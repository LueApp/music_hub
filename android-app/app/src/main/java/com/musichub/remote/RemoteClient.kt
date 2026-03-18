package com.musichub.remote

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP + WebSocket client for controller mode.
 * Sends commands to the player phone's RemoteServer and receives state updates.
 */
object RemoteClient {

    private const val TAG = "RemoteClient"
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    // Cached state from WebSocket updates
    @Volatile
    var currentState: RemoteState? = null
        private set

    // Listeners
    private val stateListeners = mutableListOf<(RemoteState) -> Unit>()
    private val connectionListeners = mutableListOf<(Boolean) -> Unit>()

    @Volatile
    var isConnected: Boolean = false
        private set

    private fun baseUrl(): String = RemoteMode.getServerUrl()

    // --- Connection ---

    fun connect() {
        if (RemoteMode.serverHost.isBlank()) {
            Log.w(TAG, "Cannot connect: server host is empty")
            return
        }

        val wsUrl = baseUrl().replace("http://", "ws://") + "/ws"
        Log.d(TAG, "Connecting WebSocket to $wsUrl")

        try {
            val request = Request.Builder().url(wsUrl).build()
            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket connected")
                    isConnected = true
                    notifyConnectionListeners(true)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val state = gson.fromJson(text, RemoteState::class.java)
                        currentState = state
                        mainHandler.post {
                            notifyStateListeners(state)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing state: ${e.message}")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket closed: $reason")
                    isConnected = false
                    currentState = null
                    notifyConnectionListeners(false)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure: ${t.message}")
                    isConnected = false
                    notifyConnectionListeners(false)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket connection: ${e.message}")
        }
    }

    private fun notifyStateListeners(state: RemoteState) {
        for (listener in stateListeners.toList()) {
            try {
                listener(state)
            } catch (e: Exception) {
                Log.e(TAG, "Error in state listener: ${e.message}", e)
            }
        }
    }

    private fun notifyConnectionListeners(connected: Boolean) {
        mainHandler.post {
            for (listener in connectionListeners.toList()) {
                try {
                    listener(connected)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in connection listener: ${e.message}", e)
                }
            }
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
        isConnected = false
        currentState = null
    }

    // --- Listeners ---

    fun addStateListener(listener: (RemoteState) -> Unit) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: (RemoteState) -> Unit) {
        stateListeners.remove(listener)
    }

    fun addConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.add(listener)
    }

    fun removeConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.remove(listener)
    }

    // --- Playback Controls (fire-and-forget POST requests) ---

    fun playNext() = postAsync("/api/play/next")
    fun playPrevious() = postAsync("/api/play/previous")
    fun togglePlayPause() = postAsync("/api/play/pause")
    fun toggleShuffle() = postAsync("/api/shuffle")
    fun toggleRepeat() = postAsync("/api/repeat")
    fun seekTo(positionMs: Long) = postAsync("/api/seek/$positionMs")
    fun playAtIndex(index: Int) = postAsync("/api/play/index/$index")
    fun playSong(songId: Long) = postAsync("/api/play/song/$songId")
    fun playPlaylist(playlistId: Long) = postAsync("/api/play/playlist/$playlistId")

    private fun postAsync(path: String) {
        val url = baseUrl() + path
        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "POST $path failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    // --- Data Fetching (blocking, call from coroutine/IO thread) ---

    fun fetchQueue(): List<RemoteSong> {
        return fetchList("/api/queue")
    }

    fun fetchPlaylists(): List<RemotePlaylist> {
        return fetchList("/api/playlists")
    }

    fun fetchPlaylistSongs(playlistId: Long): List<RemoteSong> {
        return fetchList("/api/playlists/$playlistId/songs")
    }

    fun fetchAllSongs(): List<RemoteSong> {
        return fetchList("/api/songs")
    }

    fun fetchState(): RemoteState? {
        val url = baseUrl() + "/api/state"
        val request = Request.Builder().url(url).get().build()
        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return null
            response.close()
            gson.fromJson(body, RemoteState::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Fetch state failed: ${e.message}")
            null
        }
    }

    private inline fun <reified T> fetchList(path: String): List<T> {
        val url = baseUrl() + path
        val request = Request.Builder().url(url).get().build()
        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            response.close()
            val type = TypeToken.getParameterized(List::class.java, T::class.java).type
            gson.fromJson(body, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Fetch $path failed: ${e.message}")
            emptyList()
        }
    }
}
