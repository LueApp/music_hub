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
    private var intentionalDisconnect = false
    private var reconnectDelay = INITIAL_RECONNECT_DELAY
    private var reconnectRunnable: Runnable? = null

    // Bumped on every connect() so callbacks from a replaced socket can detect they
    // are stale and bail out instead of clobbering the new connection's state.
    @Volatile
    private var connectionGeneration = 0

    private const val INITIAL_RECONNECT_DELAY = 1000L  // 1 second
    private const val MAX_RECONNECT_DELAY = 30000L     // 30 seconds

    // Cached state from WebSocket updates
    @Volatile
    var currentState: RemoteState? = null
        private set

    // Listeners
    private val stateListeners = mutableListOf<(RemoteState) -> Unit>()
    private val connectionListeners = mutableListOf<(Boolean) -> Unit>()
    private val resyncListeners = mutableListOf<() -> Unit>()

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

        intentionalDisconnect = false
        cancelReconnect()

        val gen = ++connectionGeneration

        // Close any existing socket so it doesn't linger; its callbacks will see
        // a stale generation and bail out.
        webSocket?.close(1000, "Reconnecting")
        webSocket = null

        val wsUrl = baseUrl().replace("http://", "ws://") + "/ws"
        Log.d(TAG, "Connecting WebSocket to $wsUrl (gen=$gen)")

        try {
            val request = Request.Builder().url(wsUrl).build()
            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (gen != connectionGeneration) return
                    Log.i(TAG, "WebSocket connected")
                    isConnected = true
                    reconnectDelay = INITIAL_RECONNECT_DELAY
                    notifyConnectionListeners(true)
                    fetchStateAndPublish()
                    notifyResyncListeners()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (gen != connectionGeneration) return
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
                    if (gen != connectionGeneration) return
                    Log.i(TAG, "WebSocket closed: $reason")
                    isConnected = false
                    // Keep currentState so the UI can show stale-but-readable info
                    // alongside the "reconnecting" banner instead of going blank.
                    notifyConnectionListeners(false)
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (gen != connectionGeneration) return
                    Log.e(TAG, "WebSocket failure: ${t.message}")
                    isConnected = false
                    notifyConnectionListeners(false)
                    scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket connection: ${e.message}")
            scheduleReconnect()
        }
    }

    /**
     * Tear down any pending reconnect, reset the backoff, and reconnect immediately.
     * Use when the user explicitly asks for a reconnect (e.g. "Reconnect now" button)
     * or when caller knows the existing connection is stuck.
     */
    fun forceReconnect() {
        if (!RemoteMode.isController()) {
            Log.w(TAG, "forceReconnect: not in controller mode")
            return
        }
        if (RemoteMode.serverHost.isBlank()) {
            Log.w(TAG, "forceReconnect: no server host")
            return
        }
        Log.i(TAG, "Force reconnect requested")
        cancelReconnect()
        reconnectDelay = INITIAL_RECONNECT_DELAY
        connect()
    }

    /**
     * Fetch /api/state once and publish it to state listeners. Called right after
     * the WebSocket opens so controllers see the current song/playback info without
     * waiting up to 500 ms for the next broadcast tick.
     */
    private fun fetchStateAndPublish() {
        val url = baseUrl() + "/api/state"
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Initial state fetch failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string()
                    if (body != null) {
                        val state = gson.fromJson(body, RemoteState::class.java)
                        currentState = state
                        mainHandler.post { notifyStateListeners(state) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Initial state fetch parse error: ${e.message}")
                } finally {
                    response.close()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (intentionalDisconnect || !RemoteMode.isController()) return

        Log.d(TAG, "Scheduling reconnect in ${reconnectDelay}ms")
        val runnable = Runnable {
            if (!intentionalDisconnect && RemoteMode.isController()) {
                Log.d(TAG, "Attempting reconnect...")
                connect()
            }
        }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, reconnectDelay)
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
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

    private fun notifyResyncListeners() {
        mainHandler.post {
            for (listener in resyncListeners.toList()) {
                try {
                    listener()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in resync listener: ${e.message}", e)
                }
            }
        }
    }

    fun disconnect() {
        intentionalDisconnect = true
        cancelReconnect()
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

    /**
     * Resync listeners fire on the main thread after a (re)connection's WebSocket
     * opens. Subscribers should re-fetch any data they hold that isn't already
     * pushed by the broadcast loop (playlists, full song library, etc.).
     */
    fun addResyncListener(listener: () -> Unit) {
        resyncListeners.add(listener)
    }

    fun removeResyncListener(listener: () -> Unit) {
        resyncListeners.remove(listener)
    }

    // --- Playback Controls (fire-and-forget POST requests) ---

    fun playNext() = postAsync("/api/play/next")
    fun playPrevious() = postAsync("/api/play/previous")
    fun togglePlayPause() = postAsync("/api/play/pause")
    fun toggleShuffle() = postAsync("/api/shuffle")
    fun toggleRepeat() = postAsync("/api/repeat")
    fun seekTo(positionMs: Long) = postAsync("/api/seek/$positionMs")
    fun playAtIndex(index: Int) = postAsync("/api/play/index/$index")
    fun setVolume(level: Int) = postAsync("/api/volume/$level")
    fun moveInQueue(from: Int, to: Int) = postAsync("/api/queue/move/$from/$to")
    fun removeFromQueue(index: Int) = postAsync("/api/queue/remove/$index")
    fun moveInShuffleOrder(from: Int, to: Int) = postAsync("/api/shuffle/move/$from/$to")
    fun playSong(songId: Long) = postAsync("/api/play/song/$songId")
    fun playPlaylist(playlistId: Long, shuffle: Boolean = false) =
        postAsync("/api/play/playlist/$playlistId" + if (shuffle) "?shuffle=true" else "")

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

    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        val url = baseUrl() + "/api/playlists/$playlistId/import"
        val json = gson.toJson(songIds)
        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        val response = httpClient.newCall(request).execute()
        response.close()
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
        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: return emptyList()
        response.close()
        val type = TypeToken.getParameterized(List::class.java, T::class.java).type
        return gson.fromJson(body, type) ?: emptyList()
    }
}
