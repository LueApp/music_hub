package com.musichub.service

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.musichub.platform.Platforms

/**
 * Handles launching songs in their native music apps via deep links.
 */
object DeepLinkLauncher {

    private const val TAG = "DeepLinkLauncher"

    // Delay for locked screen launches (needs more time for app to process)
    private const val LOCKED_SCREEN_LAUNCH_DELAY_MS = 500L

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
