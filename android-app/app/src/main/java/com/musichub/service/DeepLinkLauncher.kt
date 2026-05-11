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
import android.widget.Toast
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
    private const val KUGOU_PACKAGE = "com.kugou.android"

    private fun isKugouLink(deepLink: String): Boolean =
        deepLink.startsWith("kugou://") || deepLink.contains("kugou.com")

    private const val LAUNCH_MODE_KEY = "launch_mode"
    const val LAUNCH_MODE_BACKGROUND = "background"
    const val LAUNCH_MODE_FOREGROUND = "foreground"

    // SharedPreferences key for the orientation snapshot taken by
    // forcePortraitRotation. Persists the user's pre-workaround
    // (ACCELEROMETER_ROTATION, USER_ROTATION) values so restoreAutoRotation
    // can put them back exactly. Persisting (vs in-memory) survives a process
    // kill mid-workaround — HyperOS occasionally kills the launching process
    // when CLEAR_TASK switches focus to NetEase.
    private const val PREFS_NAME = "musichub_prefs"
    private const val PREF_KEY_ROTATION_SNAPSHOT = "rotation_workaround_snapshot"

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
        val mode = getLaunchMode(context)
        val isScreenLocked = isScreenLocked(context)
        Log.d(TAG, "Launch requested: deepLink=$deepLink, mode=$mode, screenLocked=$isScreenLocked, skipAutoRotate=$skipAutoRotate")

        // Locked-screen path is shared by both modes — freeform doesn't render meaningfully
        // under the keyguard, and the foreground workarounds (rotation/a11y) also don't apply.
        if (isScreenLocked) {
            return launchForLockedScreen(context, deepLink, fallbackUrl)
        }

        return when (mode) {
            LAUNCH_MODE_FOREGROUND -> launchForeground(context, deepLink, fallbackUrl, skipAutoRotate)
            else -> launchBackground(context, deepLink, fallbackUrl)
        }
    }

    private fun getLaunchMode(context: Context): String {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(LAUNCH_MODE_KEY, LAUNCH_MODE_BACKGROUND) ?: LAUNCH_MODE_BACKGROUND
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

    private fun launchForeground(context: Context, deepLink: String, fallbackUrl: String, skipAutoRotate: Boolean = false): Boolean {
        val resolvedLink = convertLegacyBilibiliDeepLink(deepLink)
        Log.d(TAG, "Launching deep link (foreground mode): $resolvedLink (original: $deepLink)")

        // Drop any leftover background-mode tracking so PlaybackService's
        // DisplayListener / onConfigurationChanged hook doesn't fire a
        // `am task resize` against the music app we're about to launch
        // fullscreen. Even though that hook is also gated by launch_mode
        // below, clearing here closes the small race where the mode-change
        // pref hasn't propagated yet.
        ShizukuLauncher.clearTargetState()

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
            if (isKugouLink(resolvedLink)) {
                setPackage(KUGOU_PACKAGE)
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
            if (isKugouLink(resolvedLink) && fallbackUrl.isNotEmpty()) {
                Log.d(TAG, "Kugou app not available, falling back to browser: $fallbackUrl")
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
     * Background-mode launch. Sends the deep link to the music app in a freeform
     * (small floating window) so Music Hub stays visible behind it. Deliberately
     * skips the foreground-mode workarounds (NetEase rotation hack, QQ Music
     * accessibility tap, CLEAR_TASK) — those exist to recover from the disruption
     * of a full app switch, which background mode is trying to avoid in the first place.
     */
    private fun launchBackground(context: Context, deepLink: String, fallbackUrl: String): Boolean {
        val resolvedLink = convertLegacyBilibiliDeepLink(deepLink)
        Log.d(TAG, "Launching deep link (background mode): $resolvedLink (original: $deepLink)")

        // Preferred path: ask Shizuku to invoke `am start --windowingMode 5`. This is the
        // only way a non-system app can actually trigger freeform — the standard
        // ActivityOptions.setLaunchWindowingMode hint is silently stripped without the
        // signature-only MANAGE_ACTIVITY_TASKS permission. We also pass bounds so the
        // window lands compact in the bottom-right corner instead of HyperOS's default
        // ~50%-screen centered placement.
        val bounds = computeBackgroundBounds(context)
        // Pass a provider so the watchdog re-reads the floating-ball position
        // each tick — the music-app freeform follows the ball when the user
        // drags it mid-playback. Use applicationContext to avoid leaking the
        // calling context across the watchdog's lifetime.
        val appCtx = context.applicationContext
        val boundsProvider: () -> android.graphics.Rect = { computeBackgroundBounds(appCtx) }
        if (ShizukuLauncher.launchFreeform(context, resolvedLink, bounds, boundsProvider)) {
            Log.i(TAG, "Successfully launched (background, via Shizuku, bounds=$bounds): $resolvedLink")
            return true
        }

        // Shizuku unavailable — surface why so the user can fix it. APK reinstalls
        // revoke Shizuku permission, which silently regresses background mode to
        // a regular fullscreen launch.
        val shizukuStatus = ShizukuLauncher.status(context)
        if (shizukuStatus != ShizukuLauncher.Status.READY) {
            val msg = when (shizukuStatus) {
                ShizukuLauncher.Status.NOT_INSTALLED -> "未安装 Shizuku，已使用全屏方式打开"
                ShizukuLauncher.Status.SERVICE_NOT_RUNNING -> "Shizuku 服务未运行，已使用全屏方式打开"
                ShizukuLauncher.Status.PERMISSION_DENIED -> "Shizuku 未授权（请在设置中重新授权），已使用全屏方式打开"
                else -> "Shizuku 不可用"
            }
            Log.w(TAG, "Background mode fell back to fullscreen: shizukuStatus=$shizukuStatus")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
            }
        }

        // Fallback path: regular Intent + freeform-bundle hint. This will degrade to a
        // fullscreen launch on most ROMs (the windowing-mode hint gets stripped) but
        // still gets the song playing — preferable to silent failure.
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(resolvedLink)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (resolvedLink.startsWith("bilibili://") || resolvedLink.contains("bilibili.com")) {
                setPackage(BILIBILI_PACKAGE)
            }
            if (isKugouLink(resolvedLink)) {
                setPackage(KUGOU_PACKAGE)
            }
        }

        val launchBundle = buildFreeformLaunchBundle(context)

        return try {
            if (launchBundle != null) {
                context.startActivity(intent, launchBundle)
            } else {
                context.startActivity(intent)
            }
            Log.i(TAG, "Successfully launched (background, fallback): $resolvedLink")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch in background mode: ${e.message}")
            val isBilibili = resolvedLink.startsWith("bilibili://") || resolvedLink.contains("bilibili.com")
            if (isBilibili && fallbackUrl.isNotEmpty()) {
                Log.d(TAG, "Bilibili app not available, falling back to browser: $fallbackUrl")
                return launchFallback(context, fallbackUrl)
            }
            if (isKugouLink(resolvedLink) && fallbackUrl.isNotEmpty()) {
                Log.d(TAG, "Kugou app not available, falling back to browser: $fallbackUrl")
                return launchFallback(context, fallbackUrl)
            }
            if (fallbackUrl.isNotEmpty()) launchFallback(context, fallbackUrl) else false
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
            if (isKugouLink(resolvedLink)) {
                setPackage(KUGOU_PACKAGE)
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
                    } else if (isKugouLink(resolvedLink) && fallbackUrl.isNotEmpty()) {
                        Log.d(TAG, "Kugou app not available on locked screen, falling back to browser")
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
     * Build an ActivityOptions bundle that asks the system to launch the target
     * activity in freeform windowing mode at a tiny corner rect, so Music Hub
     * remains visible behind a small floating music-app window instead of being
     * fully replaced. Returns null if the device doesn't support freeform or
     * the call fails — caller should then start the activity normally.
     *
     * Requires `enable_freeform_support=1` (developer option / adb settings) on
     * stock Android; some OEM ROMs gate it differently. The setLaunchWindowingMode
     * call is reflective because it's a hidden API; setLaunchBounds is the public
     * API and is enough to trigger freeform on supporting devices.
     */
    /**
     * Off-screen sliver: position the freeform window so the bulk of it sits
     * past the right edge of the display. Only a thin vertical strip
     * (SLIVER_VISIBLE_PX) remains on-screen, so the music app is barely
     * visible but still drag-back-able. HyperOS happily accepts freeform
     * bounds that extend beyond the screen — verified via dumpsys (existing
     * tasks observed at e.g. Rect(724, -168 - 1804, 1560)).
     *
     * Closest we can get to HyperOS's native floating-bar without reaching
     * for the proprietary miui_multi_sence/IFloatingWindow service or
     * synthesising a drag-to-edge accessibility gesture (both of which
     * would only kick in *after* a visible flash of the full freeform window).
     */
    private fun computeBackgroundBounds(context: Context): android.graphics.Rect {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(metrics)
        val screenW = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
        val screenH = if (metrics.heightPixels > 0) metrics.heightPixels else 2400

        val anchorBounds = FloatingWindowService.getInstance()?.getOverlayScreenBounds()
        if (anchorBounds != null) {
            // Floating ball is shown — push the music app FULLY off-screen so
            // it's truly invisible. The user controls playback through the
            // ball; the music app never needs to be visible.
            //
            // Why off-screen instead of "small bounds at ball position":
            // HyperOS renders freeform window chrome (a ~150×150 white frame
            // around the task) on a separate system surface, sized to a
            // minimum regardless of how small we set task bounds. Even with
            // bounds=10×10, the chrome shows as a visible square behind the
            // ball (verified via screenshot). Pushing bounds past the right
            // screen edge moves the chrome off-screen too — the only visible
            // UI is our floating ball. Music keeps playing because the task
            // is still freeform-visible-and-active.
            //
            // We anchor the *vertical* position to the ball so if the ball
            // is dragged, the off-screen window follows in lockstep — keeps
            // the chrome consistently off-screen at the ball's row even
            // during a home-gesture snap-back.
            val w = 100
            val h = 100
            val centerY = (anchorBounds.top + anchorBounds.bottom) / 2
            val left = screenW + 50  // start 50 px past the right edge
            val top = (centerY - h / 2).coerceIn(0, (screenH - h).coerceAtLeast(0))
            return android.graphics.Rect(left, top, left + w, top + h)
        }

        // No floating overlay — fall back to the off-screen sliver. HyperOS
        // accepts down to 1x1; we keep ~20 px visible on the right edge so
        // the user has a handle to drag the window back into view.
        val sliverVisiblePx = 20
        val windowWidth = 100
        val windowHeight = 100
        val left = screenW - sliverVisiblePx
        val top = (screenH - windowHeight) / 2
        return android.graphics.Rect(
            left,
            top,
            left + windowWidth,
            top + windowHeight
        )
    }

    private fun buildFreeformLaunchBundle(context: Context): android.os.Bundle? {
        return try {
            val options = android.app.ActivityOptions.makeBasic()

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealMetrics(metrics)
            val screenW = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
            val screenH = if (metrics.heightPixels > 0) metrics.heightPixels else 1920

            // Use a reasonably-sized window in the bottom-right corner. A 1x1 rect was
            // being silently clamped on some ROMs to fullscreen rather than the freeform
            // minimum. ~360x640 is comfortably above the freeform minimum size.
            val w = (screenW * 0.4).toInt().coerceAtLeast(360)
            val h = (screenH * 0.4).toInt().coerceAtLeast(640)
            val bounds = android.graphics.Rect(
                screenW - w, screenH - h, screenW, screenH
            )
            options.setLaunchBounds(bounds)
            Log.d(TAG, "Built freeform bounds: $bounds (screen=${screenW}x${screenH})")

            // WINDOWING_MODE_FREEFORM = 5; setLaunchWindowingMode is @hide so we use
            // reflection. On Android 9+ this is on the hidden-API list — reflection
            // either succeeds, throws NoSuchMethodException (blocklist), or returns
            // silently with the call ignored. Log all three outcomes so we can tell
            // from logcat which path the device is on.
            var windowingModeApplied = false
            try {
                val method = android.app.ActivityOptions::class.java
                    .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                method.invoke(options, 5)
                windowingModeApplied = true
                Log.d(TAG, "setLaunchWindowingMode(FREEFORM) reflection invoked OK")
            } catch (e: NoSuchMethodException) {
                Log.w(TAG, "setLaunchWindowingMode not on this Android version (NoSuchMethod): ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "setLaunchWindowingMode reflection failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            // Verify the bundle actually carries a windowing-mode hint by reading it back.
            val bundle = options.toBundle()
            val wmHint = bundle.getInt("android.activity.windowingMode", -1)
            Log.d(TAG, "Freeform launch bundle: windowingMode=$wmHint, applied=$windowingModeApplied, bounds=$bounds")

            bundle
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build freeform launch bundle: ${e.message}")
            null
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
     * Snapshot the current (ACCELEROMETER_ROTATION, USER_ROTATION) pair so
     * restoreAutoRotation can return the device to exactly its pre-workaround
     * state. Uses commit() (synchronous) so the snapshot survives a process
     * kill that happens between forcePortraitRotation and restoreAutoRotation
     * — that's the realistic failure mode given CLEAR_TASK can move focus
     * away from us and HyperOS sometimes kills the now-background process.
     */
    private fun snapshotRotation(context: Context, accel: Int, user: Int) {
        @Suppress("ApplySharedPref")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY_ROTATION_SNAPSHOT, "$accel,$user")
            .commit()
    }

    private fun consumeRotationSnapshot(context: Context): Pair<Int, Int>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_KEY_ROTATION_SNAPSHOT, null) ?: return null
        @Suppress("ApplySharedPref")
        prefs.edit().remove(PREF_KEY_ROTATION_SNAPSHOT).commit()
        val parts = raw.split(",")
        val a = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val u = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return a to u
    }

    /**
     * Recovery path called from MusicHubApplication.onCreate when an orphaned
     * snapshot is detected (a previous workaround was interrupted by a process
     * kill before restoreAutoRotation could run). Reapplies the snapshotted
     * values exactly — if no snapshot exists, this is a no-op.
     */
    fun recoverOrphanedRotationSnapshot(context: Context) {
        val snapshot = consumeRotationSnapshot(context) ?: return
        if (!Settings.System.canWrite(context)) {
            Log.w(TAG, "Orphaned rotation snapshot ($snapshot) present but WRITE_SETTINGS not granted; cannot restore")
            return
        }
        val (accel, user) = snapshot
        try {
            val curAccel = Settings.System.getInt(
                context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1
            )
            val curUser = Settings.System.getInt(
                context.contentResolver, Settings.System.USER_ROTATION, 0
            )
            Settings.System.putInt(
                context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, accel
            )
            Settings.System.putInt(
                context.contentResolver, Settings.System.USER_ROTATION, user
            )
            Log.i(TAG, "Recovered orphaned rotation snapshot from previous session")
            Log.i(TAG, "Settings.System.putInt ACCELEROMETER_ROTATION $curAccel -> $accel")
            Log.i(TAG, "Settings.System.putInt USER_ROTATION $curUser -> $user")
            landscapeWorkaroundActive = false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply orphaned rotation snapshot: ${e.message}")
        }
    }

    /**
     * Force the device into portrait rotation by disabling auto-rotate
     * and setting user rotation to portrait. Returns false if WRITE_SETTINGS
     * permission is not granted.
     *
     * Snapshots the pre-call values so restoreAutoRotation can return the
     * device to its exact prior state — restoring only ACCELEROMETER_ROTATION
     * (the original pre-fix behavior) leaked USER_ROTATION=0 into persistent
     * system settings, which becomes user-visible when auto-rotate is later
     * turned off via quicksettings.
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

            val currentUserRotation = Settings.System.getInt(
                context.contentResolver,
                Settings.System.USER_ROTATION,
                0
            )

            snapshotRotation(context, currentAutoRotate, currentUserRotation)

            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                0
            )
            Log.i(TAG, "Settings.System.putInt ACCELEROMETER_ROTATION $currentAutoRotate -> 0")
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.USER_ROTATION,
                0 // ROTATION_0 = portrait
            )
            Log.i(TAG, "Settings.System.putInt USER_ROTATION $currentUserRotation -> 0")
            Log.d(TAG, "Forced portrait rotation (auto-rotate off, user-rotation=portrait); snapshot=($currentAutoRotate,$currentUserRotation)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force portrait rotation: ${e.message}")
            return false
        }
    }

    /**
     * Restore the (ACCELEROMETER_ROTATION, USER_ROTATION) pair to the values
     * snapshotted at the start of the workaround. If the snapshot is missing
     * (e.g. restore was already called once for this cycle), fall back to a
     * safe default: ACCELEROMETER_ROTATION=1 and leave USER_ROTATION as-is —
     * the user may legitimately have set USER_ROTATION themselves, so we
     * don't overwrite it without evidence we set it.
     */
    private fun restoreAutoRotation(context: Context) {
        try {
            val snapshot = consumeRotationSnapshot(context)
            if (snapshot != null) {
                val (accel, user) = snapshot
                val curAccel = Settings.System.getInt(
                    context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1
                )
                val curUser = Settings.System.getInt(
                    context.contentResolver, Settings.System.USER_ROTATION, 0
                )
                Settings.System.putInt(
                    context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, accel
                )
                Log.i(TAG, "Settings.System.putInt ACCELEROMETER_ROTATION $curAccel -> $accel")
                Settings.System.putInt(
                    context.contentResolver, Settings.System.USER_ROTATION, user
                )
                Log.i(TAG, "Settings.System.putInt USER_ROTATION $curUser -> $user")
            } else {
                val curAccel = Settings.System.getInt(
                    context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1
                )
                Settings.System.putInt(
                    context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1
                )
                Log.i(TAG, "Settings.System.putInt ACCELEROMETER_ROTATION $curAccel -> 1 (no snapshot)")
            }
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
