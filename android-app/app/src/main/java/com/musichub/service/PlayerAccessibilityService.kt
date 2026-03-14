package com.musichub.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that can click the mini player bar in QQ Music
 * to navigate to the full player/lyrics page.
 *
 * This is needed because QQ Music doesn't expose any deep link or intent
 * to navigate to the player/lyrics page. The only way is to tap the mini
 * player bar at the bottom of the QQ Music screen.
 */
class PlayerAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingClick = false
    private var retryCount = 0
    private var clickCount = 0  // Track how many times we've clicked

    // Broadcast receiver to handle click requests from other components
    private val clickRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REQUEST_CLICK) {
                Log.d(TAG, "Received click request via broadcast")
                requestClickMiniPlayer()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "PlayerAccessibilityService connected")

        // Register broadcast receiver
        val filter = IntentFilter(ACTION_REQUEST_CLICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(clickRequestReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(clickRequestReceiver, filter)
        }

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            packageNames = arrayOf(QQMUSIC_PACKAGE)
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 200
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!pendingClick) return
        if (event == null) return

        if (event.packageName == QQMUSIC_PACKAGE) {
            // Try to find and click the mini player bar
            handler.postDelayed({
                if (pendingClick) {
                    tryClickMiniPlayer()
                }
            }, 300)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "PlayerAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(clickRequestReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
        Log.d(TAG, "PlayerAccessibilityService destroyed")
    }

    /**
     * Request clicking the QQ Music mini player bar.
     * Call this after launching a QQ Music deep link in foreground mode.
     */
    fun requestClickMiniPlayer() {
        pendingClick = true
        retryCount = 0
        clickCount = 0
        Log.d(TAG, "Mini player click requested")

        // Start retry loop — QQ Music needs time to process the deep link
        // and show the mini player bar. We retry every 500ms for up to 8 seconds.
        scheduleRetry()

        // Timeout: give up after 8 seconds
        handler.postDelayed({
            if (pendingClick) {
                pendingClick = false
                Log.w(TAG, "Mini player click timed out after $retryCount retries, $clickCount clicks")
            }
        }, MAX_TIMEOUT_MS)
    }

    private fun scheduleRetry() {
        handler.postDelayed({
            if (pendingClick) {
                retryCount++
                val clicked = tryClickMiniPlayer()
                if (clicked) {
                    clickCount++
                    Log.i(TAG, "Click successful, total clicks: $clickCount")
                }
                // Keep retrying even after clicking - click multiple times to ensure it works
                // Stop only after we've clicked 1 time or reached max retries
                if (clickCount < 1 && retryCount < MAX_RETRIES) {
                    scheduleRetry()
                } else if (clickCount >= 1) {
                    pendingClick = false
                    Log.i(TAG, "Finished clicking after $clickCount clicks")
                }
            }
        }, RETRY_INTERVAL_MS)
    }

    private fun tryClickMiniPlayer(): Boolean {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.d(TAG, "rootInActiveWindow is null (retry $retryCount)")
            // Fallback: try clicking at known position even without root window
            return tryFallbackClick()
        }

        try {
            // Strategy 1: Look for now-playing card (cxs) - expanded player at bottom
            // Card bounds: [50,1603][1058,2213], click on the album/song info area (not controls)
            val nowPlayingNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "$QQMUSIC_PACKAGE:id/cxs"
            )
            if (nowPlayingNodes.isNotEmpty()) {
                val node = nowPlayingNodes[0]
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                // Click on song title/artist area (center-left, avoiding progress bar at bottom)
                val x = rect.left + 250f  // 250px from left edge (song info area)
                val y = rect.top + 80f  // 80px from top (song title area)
                Log.d(TAG, "Found cxs card at bounds: $rect, clicking at ($x, $y)")
                if (performGestureClick(x, y)) {
                    Log.i(TAG, "Clicked now-playing card (cxs) at ($x, $y) (retry $retryCount)")
                    return true
                }
            }

            // Strategy 2: Try the mini player container (jqv)
            val miniPlayerNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "$QQMUSIC_PACKAGE:id/jqv"
            )
            if (miniPlayerNodes.isNotEmpty()) {
                val node = miniPlayerNodes[0]
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)

                val x = rect.left + 100f  // Click on album art area (left side)
                val y = (rect.top + rect.bottom) / 2f  // Vertical center
                Log.d(TAG, "Found jqv mini player at bounds: $rect, clicking at ($x, $y)")

                if (performGestureClick(x, y)) {
                    Log.i(TAG, "Clicked mini player (jqv) at ($x, $y) (retry $retryCount)")
                    return true
                }
            }

            // Strategy 3: Fallback to known position if no elements found
            Log.d(TAG, "No UI elements found (retry $retryCount), trying fallback position")
            return tryFallbackClick()
        } catch (e: Exception) {
            Log.e(TAG, "Error trying to click mini player: ${e.message}")
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }

        return false
    }

    /**
     * Try clicking at a known fallback position where the mini player typically appears.
     * This is used when we can't find the UI elements but know QQ Music is active.
     */
    private fun tryFallbackClick(): Boolean {
        // Fallback position: center-bottom of screen where mini player usually is
        // Based on previous successful clicks around (554, 1753) and (550, 2100)
        val x = 550f
        val y = 2000f  // Bottom area where mini player bar appears
        Log.d(TAG, "Attempting fallback click at ($x, $y)")
        if (performGestureClick(x, y)) {
            Log.i(TAG, "Fallback click dispatched at ($x, $y) (retry $retryCount)")
            return true
        }
        return false
    }

    private fun performGestureClick(x: Float, y: Float): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return false
        }

        val path = android.graphics.Path()
        path.moveTo(x, y)

        val gestureBuilder = android.accessibilityservice.GestureDescription.Builder()
        val strokeDescription = android.accessibilityservice.GestureDescription.StrokeDescription(
            path, 0, 100  // 100ms tap duration
        )
        gestureBuilder.addStroke(strokeDescription)

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.d(TAG, "Gesture completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.w(TAG, "Gesture cancelled at ($x, $y)")
            }
        }

        return dispatchGesture(gestureBuilder.build(), callback, handler)
    }

    private fun findNodesByContentDesc(
        root: AccessibilityNodeInfo,
        desc: String
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByContentDescRecursive(root, desc, results)
        return results
    }

    private fun findNodesByContentDescRecursive(
        node: AccessibilityNodeInfo,
        desc: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.contentDescription?.toString() == desc) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByContentDescRecursive(child, desc, results)
        }
    }

    companion object {
        private const val TAG = "PlayerA11yService"
        private const val QQMUSIC_PACKAGE = "com.tencent.qqmusic"
        private const val RETRY_INTERVAL_MS = 500L
        private const val MAX_RETRIES = 16  // 16 * 500ms = 8 seconds
        private const val MAX_TIMEOUT_MS = 8000L
        const val ACTION_REQUEST_CLICK = "com.musichub.action.REQUEST_CLICK_MINIPLAYER"

        @Volatile
        private var instance: PlayerAccessibilityService? = null

        fun getInstance(): PlayerAccessibilityService? = instance

        /**
         * Request clicking the mini player via broadcast.
         * This works even if getInstance() returns null, as long as the service is running.
         */
        fun requestClickViaBroadcast(context: Context) {
            val intent = Intent(ACTION_REQUEST_CLICK)
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent click request broadcast")
        }

        /**
         * Check if the accessibility service is enabled.
         */
        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains("${context.packageName}/${PlayerAccessibilityService::class.java.canonicalName}")
        }

        /**
         * Open accessibility settings so the user can enable this service.
         */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
