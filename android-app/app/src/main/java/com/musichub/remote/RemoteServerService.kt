package com.musichub.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.musichub.R
import com.musichub.ui.MainActivity
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Foreground service that runs the RemoteServer.
 * Shows a notification with the server IP address so the controller phone knows where to connect.
 */
class RemoteServerService : Service() {

    private var server: RemoteServer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Log.d(TAG, "RemoteServerService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        instance = null
        Log.d(TAG, "RemoteServerService destroyed")
    }

    private fun startServer() {
        if (server != null) return

        val port = RemoteMode.DEFAULT_PORT
        server = RemoteServer(port).also {
            try {
                it.start()
                it.startBroadcasting()
                val ip = getDeviceIpAddress()
                Log.i(TAG, "Remote server started on $ip:$port")

                val notification = buildNotification(ip, port)
                startForeground(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server: ${e.message}")
                server = null
            }
        }
    }

    private fun stopServer() {
        server?.let {
            it.stopBroadcasting()
            it.stop()
            Log.i(TAG, "Remote server stopped")
        }
        server = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Remote Control Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Remote control server is running"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(ip: String, port: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Control Server")
            .setContentText("Controller can connect to: $ip:$port")
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "RemoteServerService"
        private const val CHANNEL_ID = "remote_server_channel"
        private const val NOTIFICATION_ID = 1003

        const val ACTION_START = "com.musichub.remote.START_SERVER"
        const val ACTION_STOP = "com.musichub.remote.STOP_SERVER"

        @Volatile
        private var instance: RemoteServerService? = null

        fun getInstance(): RemoteServerService? = instance

        fun start(context: Context) {
            val intent = Intent(context, RemoteServerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RemoteServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Get the device's IP address on the local network.
         * Works for both WiFi client and WiFi hotspot modes.
         */
        fun getDeviceIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    if (networkInterface.isLoopback || !networkInterface.isUp) continue

                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (address is Inet4Address && !address.isLoopbackAddress) {
                            return address.hostAddress ?: "unknown"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("RemoteServerService", "Error getting IP: ${e.message}")
            }
            return "unknown"
        }
    }
}
