package com.musichub.remote

/**
 * Singleton tracking the app's remote control mode.
 *
 * STANDALONE: Normal operation (default)
 * PLAYER: This phone runs the server, another phone controls it
 * CONTROLLER: This phone controls a remote player phone
 */
object RemoteMode {

    enum class AppMode {
        STANDALONE,
        PLAYER,
        CONTROLLER
    }

    @Volatile
    var currentMode: AppMode = AppMode.STANDALONE
        private set

    var serverHost: String = ""
        private set

    var serverPort: Int = DEFAULT_PORT
        private set

    fun setStandalone() {
        currentMode = AppMode.STANDALONE
        serverHost = ""
    }

    fun setPlayer() {
        currentMode = AppMode.PLAYER
    }

    fun setController(host: String, port: Int = DEFAULT_PORT) {
        currentMode = AppMode.CONTROLLER
        serverHost = host
        serverPort = port
    }

    fun isController(): Boolean = currentMode == AppMode.CONTROLLER
    fun isPlayer(): Boolean = currentMode == AppMode.PLAYER
    fun isStandalone(): Boolean = currentMode == AppMode.STANDALONE

    fun getServerUrl(): String = "http://$serverHost:$serverPort"

    const val DEFAULT_PORT = 8765
}
