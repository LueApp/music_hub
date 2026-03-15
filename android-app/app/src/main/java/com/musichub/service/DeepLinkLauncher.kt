package com.musichub.service

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    // Delay before toggling auto-rotate (let the target app's activity fully start)
    private const val AUTO_ROTATE_TOGGLE_DELAY_MS = 1000L

    // Duration to keep auto-rotate disabled before re-enabling
    private const val AUTO_ROTATE_OFF_DURATION_MS = 200L

    /**
     * Launch a deep link URL, falling back to web URL if the app isn't installed.
     *
     * @param context Android context
     * @param deepLink The app-specific deep link (e.g., orpheus://song/123)
     * @param fallbackUrl The web URL fallback (e.g., https://music.163.com/song?id=123)
     * @return true if launch was successful
     */
    fun launch(context: Context, deepLink: String, fallbackUrl: String = ""): Boolean {
        val isScreenLocked = isScreenLocked(context)
        Log.d(TAG, "Launch requested: deepLink=$deepLink, screenLocked=$isScreenLocked")

        return if (isScreenLocked) {
            launchForLockedScreen(context, deepLink, fallbackUrl)
        } else {
            launchNormal(context, deepLink, fallbackUrl)
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

    private fun launchNormal(context: Context, deepLink: String, fallbackUrl: String): Boolean {
        Log.d(TAG, "Launching deep link: $deepLink")

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(deepLink)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            Log.i(TAG, "Successfully launched: $deepLink")

            // For QQ Music, use accessibility service to click mini player
            if (deepLink.contains("qqmusic://")) {
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

            // For NetEase, toggle auto-rotate to force orientation re-detection.
            // NetEase doesn't detect the current orientation on activity start when
            // the device is already in landscape; toggling auto-rotate forces the
            // system to re-deliver the orientation configuration.
            if (deepLink.startsWith("orpheus://")) {
                toggleAutoRotate(context)
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
     */
    private fun launchForLockedScreen(context: Context, deepLink: String, fallbackUrl: String): Boolean {
        Log.d(TAG, "Launching for locked screen: $deepLink")

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(deepLink)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

        return try {
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
     * Toggle auto-rotate off and back on to force the foreground app to
     * re-detect the current device orientation. This works around apps
     * (like NetEase Cloud Music) that don't detect orientation on activity
     * start when the device is already rotated.
     */
    private fun toggleAutoRotate(context: Context) {
        if (!Settings.System.canWrite(context)) {
            Log.w(TAG, "WRITE_SETTINGS permission not granted, skipping auto-rotate toggle")
            return
        }

        try {
            val currentAutoRotate = Settings.System.getInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                1 // default: auto-rotate enabled
            )

            if (currentAutoRotate != 1) {
                Log.d(TAG, "Auto-rotate is disabled by user, skipping toggle")
                return
            }

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // Disable auto-rotate
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.ACCELEROMETER_ROTATION,
                        0
                    )
                    Log.d(TAG, "Auto-rotate disabled for orientation toggle")

                    // Re-enable after a brief pause
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            Settings.System.putInt(
                                context.contentResolver,
                                Settings.System.ACCELEROMETER_ROTATION,
                                1
                            )
                            Log.d(TAG, "Auto-rotate re-enabled")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to re-enable auto-rotate: ${e.message}")
                        }
                    }, AUTO_ROTATE_OFF_DURATION_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle auto-rotate: ${e.message}")
                }
            }, AUTO_ROTATE_TOGGLE_DELAY_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading auto-rotate setting: ${e.message}")
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
