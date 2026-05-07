package com.musichub.service

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.musichub.platform.Platforms

/**
 * Handles launching songs in their native music apps via deep links.
 */
object DeepLinkLauncher {

    private const val TAG = "DeepLinkLauncher"

    // Delay for locked screen launches (needs more time for app to process)
    private const val LOCKED_SCREEN_LAUNCH_DELAY_MS = 500L

    // Timeout for waiting for NetEase playback to start before restoring auto-rotation
    private const val LANDSCAPE_ROTATION_TIMEOUT_MS = 15000L

    private const val BILIBILI_PACKAGE = "tv.danmaku.bili"
    private const val NETEASE_PACKAGE = "com.netease.cloudmusic"

    // Track whether we're in the middle of the landscape workaround.
    // When true, the playback timeout should be extended and further launches
    // should know that the device WAS in landscape before we forced portrait.
    @Volatile
    var landscapeWorkaroundActive = false
        private set


    // Patterns for converting legacy HTTPS Bilibili deep links to bilibili:// scheme
    private val bilibiliVideoPattern = Regex("""https?://(?:(?:www|m)\.)?bilibili\.com/video/((?:BV[a-zA-Z0-9]+|av\d+))""")
    private val bilibiliAudioPattern = Regex("""https?://(?:(?:www|m)\.)?bilibili\.com/audio/au(\d+)""")

    /**
     * Launch a deep link URL, falling back to web URL if the app isn't installed.
     *
     * @param context Android context
     * @param deepLink The app-specific deep link (e.g., orpheus://song/123)
     * @param fallbackUrl The web URL fallback (e.g., https://music.163.com/song?id=123)
     * @param skipAutoRotate Skip landscape rotation workaround (for re-sends)
     * @return true if launch was successful
     */
    fun launch(context: Context, deepLink: String, fallbackUrl: String = "", skipAutoRotate: Boolean = false): Boolean {
        val isScreenLocked = isScreenLocked(context)
        Log.d(TAG, "Launch requested: deepLink=$deepLink, screenLocked=$isScreenLocked, skipAutoRotate=$skipAutoRotate")

        return if (isScreenLocked) {
            launchForLockedScreen(context, deepLink, fallbackUrl)
        } else {
            launchNormal(context, deepLink, fallbackUrl, skipAutoRotate)
        }
    }

    /**
     * Check if the screen is currently locked.
     */
    private fun isScreenLocked(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        val isKeyguardLocked = keyguardManager.isKeyguardLocked
        val isScreenOn = powerManager.isInteractive

        Log.d(TAG, "Screen state: keyguardLocked=$isKeyguardLocked, screenOn=$isScreenOn")
        return isKeyguardLocked || !isScreenOn
    }

    private fun launchNormal(context: Context, deepLink: String, fallbackUrl: String, skipAutoRotate: Boolean = false): Boolean {
        val resolvedLink = convertLegacyBilibiliDeepLink(deepLink)
        Log.d(TAG, "Launching deep link: $resolvedLink (original: $deepLink)")

        // For NetEase in landscape: force portrait before launch, use CLEAR_TASK,
        // then restore auto-rotation when playback starts (event-driven).
        // Also check landscapeWorkaroundActive: if we already forced portrait for a
        // previous launch (e.g., playback timeout triggered a skip), the device reports
        // portrait but we still need the workaround since the user's phone is landscape.
        val isNeteaseLandscape = resolvedLink.startsWith("orpheus://") &&
            !skipAutoRotate &&
            (isDeviceLandscape(context) || landscapeWorkaroundActive)

        if (isNeteaseLandscape && !landscapeWorkaroundActive) {
            if (!forcePortraitRotation(context)) {
                Log.w(TAG, "Cannot force portrait (WRITE_SETTINGS not granted), launching normally")
            } else {
                landscapeWorkaroundActive = true
                Log.d(TAG, "Forced portrait rotation for NetEase landscape workaround")
            }
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(resolvedLink)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isNeteaseLandscape) {
                // CLEAR_TASK forces a fresh PlayerActivity with a new OrientationEventListener
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            if (resolvedLink.startsWith("bilibili://") || resolvedLink.contains("bilibili.com")) {
                setPackage(BILIBILI_PACKAGE)
            }
        }

        return try {
            context.startActivity(intent)
            Log.i(TAG, "Successfully launched: $resolvedLink")

            // For QQ Music, use accessibility service to click mini player
            if (resolvedLink.contains("qqmusic://")) {
                Handler(Looper.getMainLooper()).postDelayed({
                    val a11yService = PlayerAccessibilityService.getInstance()
                    if (a11yService != null) {
                        a11yService.requestClickMiniPlayer()
                        Log.i(TAG, "Requested accessibility service to open QQ Music player")
                    } else {
                        Log.d(TAG, "Accessibility service not running, skipping auto-open player")
                    }
                }, 2500)
            }

            // For NetEase landscape: register callback to restore auto-rotation
            // when NetEase reports STATE_PLAYING (meaning player is loaded)
            if (isNeteaseLandscape) {
                val monitor = MediaMonitorService.getInstance()
                if (monitor != null) {
                    monitor.onNextPlaybackStart(NETEASE_PACKAGE, LANDSCAPE_ROTATION_TIMEOUT_MS) {
                        Log.d(TAG, "NetEase playback detected, restoring auto-rotation")
                        restoreAutoRotation(context)
                    }
                } else {
                    // No monitor available, restore after timeout
                    Log.w(TAG, "MediaMonitorService not available, scheduling fallback rotation restore")
                    Handler(Looper.getMainLooper()).postDelayed({
                        restoreAutoRotation(context)
                    }, LANDSCAPE_ROTATION_TIMEOUT_MS)
                }
            }

            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch deep link: ${e.message}")
            // Restore rotation if we forced portrait but launch failed
            if (isNeteaseLandscape) {
                restoreAutoRotation(context)
            }
            // For Bilibili links, fall back to HTTPS URL (browser)
            val isBilibili = resolvedLink.startsWith("bilibili://") || resolvedLink.contains("bilibili.com")
            if (isBilibili && fallbackUrl.isNotEmpty()) {
                Log.d(TAG, "Bilibili app not available, falling back to browser: $fallbackUrl")
                return launchFallback(context, fallbackUrl)
            }
            if (fallbackUrl.isNotEmpty()) {
                launchFallback(context, fallbackUrl)
            } else {
                false
            }
        }
    }

    /**
     * Launch deep link for when the screen is locked.
     */
    private fun launchForLockedScreen(context: Context, deepLink: String, fallbackUrl: String): Boolean {
        val resolvedLink = convertLegacyBilibiliDeepLink(deepLink)
        Log.d(TAG, "Launching for locked screen: $resolvedLink (original: $deepLink)")

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(resolvedLink)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            if (resolvedLink.startsWith("bilibili://") || resolvedLink.contains("bilibili.com")) {
                setPackage(BILIBILI_PACKAGE)
            }
        }

        return try {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    context.startActivity(intent)
                    Log.i(TAG, "Successfully launched for locked screen: $resolvedLink")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch for locked screen: ${e.message}")
                    val isBilibili = resolvedLink.startsWith("bilibili://") || resolvedLink.contains("bilibili.com")
                    if (isBilibili && fallbackUrl.isNotEmpty()) {
                        Log.d(TAG, "Bilibili app not available on locked screen, falling back to browser")
                        launchFallback(context, fallbackUrl)
                    } else if (fallbackUrl.isNotEmpty()) {
                        launchFallback(context, fallbackUrl)
                    }
                }
            }, LOCKED_SCREEN_LAUNCH_DELAY_MS)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch deep link for locked screen: ${e.message}")
            if (fallbackUrl.isNotEmpty()) {
                launchFallback(context, fallbackUrl)
            } else {
                false
            }
        }
    }

    /**
     * Convert legacy HTTPS Bilibili deep links to bilibili:// scheme.
     * Existing database entries store HTTPS URLs; this converts them at launch time
     * so the Bilibili app handles them directly.
     */
    private fun convertLegacyBilibiliDeepLink(deepLink: String): String {
        // Already using bilibili:// scheme, no conversion needed
        if (deepLink.startsWith("bilibili://")) return deepLink

        // Try video pattern: https://www.bilibili.com/video/BVxxx or /video/av123
        val videoMatch = bilibiliVideoPattern.find(deepLink)
        if (videoMatch != null) {
            val videoId = videoMatch.groupValues[1]
            val converted = "bilibili://video/$videoId?start_progress=0"
            Log.d(TAG, "Converted legacy Bilibili deep link: $deepLink -> $converted")
            return converted
        }

        // Try audio pattern: https://www.bilibili.com/audio/au123
        val audioMatch = bilibiliAudioPattern.find(deepLink)
        if (audioMatch != null) {
            val audioId = audioMatch.groupValues[1]
            val converted = "bilibili://music/detail/$audioId"
            Log.d(TAG, "Converted legacy Bilibili deep link: $deepLink -> $converted")
            return converted
        }

        return deepLink
    }

    private fun launchFallback(context: Context, url: String): Boolean {
        Log.d(TAG, "Launching fallback URL: $url")

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            Log.i(TAG, "Successfully launched fallback: $url")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch fallback URL: ${e.message}")
            false
        }
    }

    /**
     * Check if a music app is installed.
     */
    fun isAppInstalled(context: Context, platform: String): Boolean {
        val packageName = Platforms.PACKAGE_NAMES[platform] ?: return false
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Get installed music apps.
     */
    fun getInstalledApps(context: Context): List<String> {
        return Platforms.PACKAGE_NAMES.keys.filter { platform ->
            isAppInstalled(context, platform)
        }
    }

    /**
     * Check if the device is currently in landscape orientation.
     * Uses both configuration orientation AND physical device rotation,
     * because the config orientation may reflect the foreground app's orientation
     * (e.g., portrait QQ Music) rather than how the user is holding the device.
     */
    private fun isDeviceLandscape(context: Context): Boolean {
        val configLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Also check physical device rotation via WindowManager
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val rotation = windowManager?.defaultDisplay?.rotation
        val physicalLandscape = rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270
        val result = configLandscape || physicalLandscape
        if (result) {
            Log.d(TAG, "Device landscape detected (config=$configLandscape, physical=$physicalLandscape, rotation=$rotation)")
        }
        return result
    }

    /**
     * Force the device into portrait rotation by disabling auto-rotate
     * and setting user rotation to portrait. Returns false if WRITE_SETTINGS
     * permission is not granted.
     */
    private fun forcePortraitRotation(context: Context): Boolean {
        if (!Settings.System.canWrite(context)) {
            return false
        }

        try {
            val currentAutoRotate = Settings.System.getInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                1
            )

            if (currentAutoRotate != 1) {
                Log.d(TAG, "Auto-rotate is disabled by user, skipping landscape workaround")
                return false
            }

            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                0
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.USER_ROTATION,
                0 // ROTATION_0 = portrait
            )
            Log.d(TAG, "Forced portrait rotation (auto-rotate off, user-rotation=portrait)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force portrait rotation: ${e.message}")
            return false
        }
    }

    /**
     * Restore auto-rotation (re-enable accelerometer-based rotation).
     * After restoring, run any deferred action (e.g., double-send for NetEase).
     */
    private fun restoreAutoRotation(context: Context) {
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                1
            )
            landscapeWorkaroundActive = false
            Log.d(TAG, "Auto-rotation restored")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore auto-rotation: ${e.message}")
        }
    }

    /**
     * Check if WRITE_SETTINGS permission is granted.
     */
    fun canWriteSettings(context: Context): Boolean {
        return Settings.System.canWrite(context)
    }

    /**
     * Open a music app directly without playing a specific song.
     */
    fun openApp(context: Context, platform: String): Boolean {
        val packageName = Platforms.PACKAGE_NAMES[platform] ?: return false

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(launchIntent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open app $packageName: ${e.message}")
                false
            }
        } else {
            Log.w(TAG, "App not installed: $packageName")
            false
        }
    }
}
