package com.musichub.service

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.musichub.platform.Platforms
import com.musichub.ui.MainActivity

/**
 * Handles launching songs in their native music apps via deep links.
 */
object DeepLinkLauncher {

    private const val TAG = "DeepLinkLauncher"

    // Delay before returning to previous app (ms)
    private const val BACKGROUND_RETURN_DELAY_MS = 800L

    // Delay for Bilibili (needs longer to load video and start playback)
    private const val BILIBILI_RETURN_DELAY_MS = 2500L

    // Delay for QQ Music (needs longer to process deep link and start correct song)
    private const val QQMUSIC_RETURN_DELAY_MS = 1500L

    // Delay for locked screen launches (needs more time for app to process)
    private const val LOCKED_SCREEN_LAUNCH_DELAY_MS = 500L

    /**
     * Playback mode determines how app switching is handled.
     */
    enum class PlaybackMode {
        BACKGROUND,  // Stay in user's current app (minimal disruption)
        FOREGROUND   // Switch to music app and stay there
    }

    // Current playback mode (default to background for minimal disruption)
    var playbackMode: PlaybackMode = PlaybackMode.BACKGROUND

    // Track the package name of the app user was using before launch
    private var previousAppPackage: String? = null

    /**
     * Launch a deep link URL, falling back to web URL if the app isn't installed.
     *
     * @param context Android context
     * @param deepLink The app-specific deep link (e.g., orpheus://song/123)
     * @param fallbackUrl The web URL fallback (e.g., https://music.163.com/song?id=123)
     * @return true if launch was successful
     */
    fun launch(context: Context, deepLink: String, fallbackUrl: String = ""): Boolean {
        // Check if screen is locked
        val isScreenLocked = isScreenLocked(context)
        Log.d(TAG, "Launch requested: deepLink=$deepLink, screenLocked=$isScreenLocked, mode=$playbackMode")

        return when {
            isScreenLocked -> launchForLockedScreen(context, deepLink, fallbackUrl)
            playbackMode == PlaybackMode.FOREGROUND -> launchNormal(context, deepLink, fallbackUrl)
            else -> launchInBackground(context, deepLink, fallbackUrl)
        }
    }

    /**
     * Check if the screen is currently locked.
     */
    private fun isScreenLocked(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // Screen is locked if keyguard is locked OR screen is off
        val isKeyguardLocked = keyguardManager.isKeyguardLocked
        val isScreenOn = powerManager.isInteractive

        Log.d(TAG, "Screen state: keyguardLocked=$isKeyguardLocked, screenOn=$isScreenOn")
        return isKeyguardLocked || !isScreenOn
    }

    /**
     * Get the package name of the current foreground app.
     * Returns null if unable to determine.
     * Uses multiple methods to maximize compatibility across Android versions.
     */
    private fun getForegroundAppPackage(context: Context): String? {
        // Method 1: Try UsageStatsManager (requires PACKAGE_USAGE_STATS permission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                if (usageStatsManager != null) {
                    val endTime = System.currentTimeMillis()
                    val beginTime = endTime - 10000 // Last 10 seconds
                    val usageStats = usageStatsManager.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY, beginTime, endTime
                    )
                    if (usageStats != null && usageStats.isNotEmpty()) {
                        val recentApp = usageStats
                            .filter { it.lastTimeUsed > beginTime }
                            .maxByOrNull { it.lastTimeUsed }
                        if (recentApp != null) {
                            Log.d(TAG, "UsageStats found foreground app: ${recentApp.packageName}")
                            return recentApp.packageName
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "UsageStatsManager method failed: ${e.message}")
            }
        }

        // Method 2: Try ActivityManager.getRunningAppProcesses (fallback)
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appProcesses = activityManager.runningAppProcesses
            if (appProcesses != null) {
                for (process in appProcesses) {
                    if (process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        Log.d(TAG, "ActivityManager found foreground app: ${process.processName}")
                        return process.processName
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ActivityManager method failed: ${e.message}")
        }

        Log.w(TAG, "Could not determine foreground app")
        return null
    }

    /**
     * Check if Music Hub is currently in the foreground.
     */
    private fun isMusicHubInForeground(context: Context): Boolean {
        return getForegroundAppPackage(context) == context.packageName
    }

    private fun launchNormal(context: Context, deepLink: String, fallbackUrl: String): Boolean {
        Log.d(TAG, "Launching deep link (foreground mode): $deepLink")

        // In foreground mode, launch the deep link directly without pre-launching the app.
        // Pre-launching opens the app's home screen first, which prevents the deep link
        // from navigating to the song detail/player page (e.g., NetEase's orpheus://song/{id}
        // opens the player page only when it's the first intent the app receives).
        return performNormalLaunch(context, deepLink, fallbackUrl)
    }

    /**
     * Perform the actual normal (foreground) launch after ensuring app is running.
     */
    private fun performNormalLaunch(context: Context, deepLink: String, fallbackUrl: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(deepLink)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            Log.i(TAG, "Successfully launched: $deepLink")

            // For QQ Music in foreground mode, use accessibility service to click mini player
            if (deepLink.contains("qqmusic://")) {
                Handler(Looper.getMainLooper()).postDelayed({
                    val a11yService = PlayerAccessibilityService.getInstance()
                    if (a11yService != null) {
                        a11yService.requestClickMiniPlayer()
                        Log.i(TAG, "Requested accessibility service to open QQ Music player")
                    } else {
                        // Service not running - just log it, don't bother the user
                        Log.d(TAG, "Accessibility service not running, skipping auto-open player")
                    }
                }, 2500)
            }

            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch deep link, trying fallback: ${e.message}")
            if (fallbackUrl.isNotEmpty()) {
                launchFallback(context, fallbackUrl)
            } else {
                false
            }
        }
    }

    /**
     * Launch deep link for when the screen is locked.
     * Uses broadcast-based launch to avoid activity lifecycle issues.
     */
    private fun launchForLockedScreen(context: Context, deepLink: String, fallbackUrl: String): Boolean {
        Log.d(TAG, "Launching for locked screen: $deepLink")

        // For locked screen, we don't try to switch apps visually
        // Just send the intent and let the music app handle it in background
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(deepLink)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // These flags help with background launch
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

        return try {
            // Small delay to ensure previous pause command was processed
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    context.startActivity(intent)
                    Log.i(TAG, "Successfully launched for locked screen: $deepLink")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch for locked screen: ${e.message}")
                    if (fallbackUrl.isNotEmpty()) {
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
     * Launch deep link and immediately return to user's previous app.
     * This minimizes disruption - user stays in whatever app they were using.
     */
    private fun launchInBackground(context: Context, deepLink: String, fallbackUrl: String): Boolean {
        Log.d(TAG, "Launching in background mode: $deepLink")

        // Check platform type - different apps need different delays
        val isBilibili = deepLink.contains("bilibili")
        val isQQMusic = deepLink.contains("qqmusic://")

        // Remember the current foreground app so we can return to it
        previousAppPackage = getForegroundAppPackage(context)
        Log.d(TAG, "Previous app: $previousAppPackage, isBilibili: $isBilibili, isQQMusic: $isQQMusic")

        // Ensure target app is running first (cold start scenario)
        val preLaunchDelay = ensureAppRunning(context, deepLink)
        if (preLaunchDelay > 0) {
            Log.d(TAG, "App not running, waiting ${preLaunchDelay}ms for it to start")
            // Schedule the actual deep link launch after app starts
            Handler(Looper.getMainLooper()).postDelayed({
                performBackgroundLaunch(context, deepLink, fallbackUrl, isBilibili, isQQMusic)
            }, preLaunchDelay)
            return true
        }

        return performBackgroundLaunch(context, deepLink, fallbackUrl, isBilibili, isQQMusic)
    }

    /**
     * Perform the actual background launch after ensuring app is running.
     */
    private fun performBackgroundLaunch(
        context: Context,
        deepLink: String,
        fallbackUrl: String,
        isBilibili: Boolean,
        isQQMusic: Boolean
    ): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(deepLink)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Reduce transition animation
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }

        return try {
            context.startActivity(intent)
            Log.i(TAG, "Successfully launched: $deepLink")

            // Different apps need different delays to process deep links:
            // - Bilibili: longest delay (2500ms) to load and start video playback
            // - QQ Music: medium delay (1500ms) to process deep link and start correct song
            // - NetEase/others: short delay (800ms) - responds quickly to deep links
            val returnDelay = when {
                isBilibili -> BILIBILI_RETURN_DELAY_MS
                isQQMusic -> QQMUSIC_RETURN_DELAY_MS
                else -> BACKGROUND_RETURN_DELAY_MS
            }
            Log.d(TAG, "Using return delay: ${returnDelay}ms")

            // Schedule return to previous app after brief delay
            Handler(Looper.getMainLooper()).postDelayed({
                returnToPreviousApp(context)
            }, returnDelay)

            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch deep link, trying fallback: ${e.message}")
            if (fallbackUrl.isNotEmpty()) {
                launchFallback(context, fallbackUrl)
            } else {
                false
            }
        }
    }

    /**
     * Return to the app user was using before we launched the music app.
     * This keeps the user in their working context.
     */
    private fun returnToPreviousApp(context: Context) {
        try {
            val previousPkg = previousAppPackage
            Log.d(TAG, "Returning to previous app: $previousPkg")

            when {
                // If user was in Music Hub, return to Music Hub
                previousPkg == context.packageName -> {
                    Log.d(TAG, "Returning to Music Hub")
                    val musicHubIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                    context.startActivity(musicHubIntent)
                }
                // If we know the previous app, try to bring it back to front
                previousPkg != null -> {
                    Log.d(TAG, "Returning to previous app: $previousPkg")
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(previousPkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        context.startActivity(launchIntent)
                    } else {
                        // Can't launch the previous app, just press home
                        Log.d(TAG, "Can't find launch intent for $previousPkg, going home")
                        goHome(context)
                    }
                }
                // Fallback: just go home
                else -> {
                    Log.d(TAG, "No previous app known, going home")
                    goHome(context)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to return to previous app: ${e.message}")
            // Last resort: go home
            goHome(context)
        }
    }

    private fun goHome(context: Context) {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to go home: ${e.message}")
        }
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
     *
     * @param context Android context
     * @param platform Platform identifier (netease or qqmusic)
     * @return true if the app is installed
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
     * Check if an app is currently running (either foreground or background).
     */
    private fun isAppRunning(context: Context, packageName: String): Boolean {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningProcesses = activityManager.runningAppProcesses ?: return false

            for (process in runningProcesses) {
                if (process.processName == packageName) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if app is running: ${e.message}")
        }
        return false
    }

    /**
     * Ensure the target music app is running before trying to deep link.
     * If the app is not running, launch it first and wait a bit.
     *
     * @return delay in ms to wait before launching deep link
     */
    private fun ensureAppRunning(context: Context, deepLink: String): Long {
        // Determine target package based on deep link
        val targetPackage = when {
            deepLink.startsWith("orpheus://") -> Platforms.PACKAGE_NAMES[Platforms.NETEASE]
            deepLink.startsWith("qqmusic://") -> Platforms.PACKAGE_NAMES[Platforms.QQMUSIC]
            deepLink.contains("bilibili") -> Platforms.PACKAGE_NAMES[Platforms.BILIBILI]
            else -> null
        }

        if (targetPackage == null) {
            Log.d(TAG, "Unknown deep link scheme, no pre-launch needed")
            return 0L
        }

        // Check if app is already running
        if (isAppRunning(context, targetPackage)) {
            Log.d(TAG, "App $targetPackage is already running")
            return 0L
        }

        // App is not running - try to launch it first
        Log.d(TAG, "App $targetPackage is not running, launching first")
        val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            try {
                context.startActivity(launchIntent)
                // Return delay to wait for app to initialize
                return 1500L
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pre-launch app $targetPackage: ${e.message}")
            }
        }
        return 0L
    }

    /**
     * Get installed music apps.
     *
     * @param context Android context
     * @return List of platform identifiers for installed apps
     */
    fun getInstalledApps(context: Context): List<String> {
        return Platforms.PACKAGE_NAMES.keys.filter { platform ->
            isAppInstalled(context, platform)
        }
    }

    /**
     * Open a music app directly without playing a specific song.
     *
     * @param context Android context
     * @param platform Platform identifier
     * @return true if the app was opened
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
