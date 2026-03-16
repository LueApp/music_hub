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
    private var foundUIElement = false  // Track if we found the actual UI element
    private var lastFoundCard = false  // Track if card was found in last attempt
    private var lastFoundMiniPlayer = false  // Track if mini player was found in last attempt

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
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            packageNames = arrayOf(QQMUSIC_PACKAGE)
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 200
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Log all click events to help identify which node opens the player page
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val source = event.source
            if (source != null && event.packageName == QQMUSIC_PACKAGE) {
                val rect = android.graphics.Rect()
                source.getBoundsInScreen(rect)
                Log.i(TAG, "CLICK DETECTED - ID: ${source.viewIdResourceName}, Class: ${source.className}, Bounds: $rect, Clickable: ${source.isClickable}, ChildCount: ${source.childCount}")

                // Log children info
                for (i in 0 until source.childCount.coerceAtMost(5)) {
                    val child = source.getChild(i)
                    if (child != null) {
                        Log.i(TAG, "  Child[$i] - ID: ${child.viewIdResourceName}, Class: ${child.className}, Clickable: ${child.isClickable}")
                    }
                }
                source.recycle()
            }
        }

        if (!pendingClick) return

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
        foundUIElement = false
        lastFoundCard = false
        lastFoundMiniPlayer = false
        Log.d(TAG, "Mini player click requested")

        // Wait 2 seconds for QQ Music card animation to finish before first attempt
        handler.postDelayed({
            scheduleRetry()
        }, 2000L)

        // Timeout: give up after 10 seconds
        handler.postDelayed({
            if (pendingClick) {
                pendingClick = false
                Log.w(TAG, "Mini player click timed out after $retryCount retries, $clickCount clicks")
            }
        }, 10000L)
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
                // Stop only if we found UI element AND it's no longer visible (page changed)
                // OR if we've exceeded max retries
                if (retryCount < MAX_RETRIES) {
                    scheduleRetry()
                } else {
                    pendingClick = false
                    Log.i(TAG, "Finished after $retryCount retries, $clickCount clicks")
                }
            }
        }, RETRY_INTERVAL_MS)
    }

    private fun tryClickMiniPlayer(): Boolean {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.d(TAG, "rootInActiveWindow is null (retry $retryCount)")
            return tryFallbackClick()
        }

        try {
            // Strategy 1: Try the music card (cxy) - appears during song transitions
            val cardNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "$QQMUSIC_PACKAGE:id/cxy"
            )
            Log.d(TAG, "Found ${cardNodes.size} cxy (card) nodes")
            if (cardNodes.isNotEmpty()) {
                val node = cardNodes[0]
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                Log.d(TAG, "Music card - clickable: ${node.isClickable}, enabled: ${node.isEnabled}, bounds: $rect, size: ${rect.width()}x${rect.height()}")

                if (rect.width() > 50 && rect.height() > 50) {
                    lastFoundCard = true
                    foundUIElement = true

                    // Click at 1/4 height from top (likely where the album cover/title is)
                    val x = (rect.left + rect.right) / 2f
                    val y = rect.top + (rect.height() / 4f)

                    if (performGestureClick(x, y)) {
                        Log.i(TAG, "Clicked music card via gesture at ($x, $y) at 1/4 height (retry $retryCount)")
                        return true
                    }
                }
            } else if (lastFoundCard) {
                // Card was present before but now disappeared - page changed!
                Log.i(TAG, "Music card disappeared, page changed successfully")
                pendingClick = false
                return true
            }

            // Strategy 2: Try the mini player container (jrh or jro)
            var miniPlayerNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "$QQMUSIC_PACKAGE:id/jrh"
            )
            Log.d(TAG, "Found ${miniPlayerNodes.size} jrh nodes")
            if (miniPlayerNodes.isEmpty()) {
                miniPlayerNodes = rootNode.findAccessibilityNodeInfosByViewId(
                    "$QQMUSIC_PACKAGE:id/jro"
                )
                Log.d(TAG, "Found ${miniPlayerNodes.size} jro nodes")
            }
            if (miniPlayerNodes.isNotEmpty()) {
                val node = miniPlayerNodes[0]
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)

                if (rect.width() > 100 && rect.height() > 100) {
                    lastFoundMiniPlayer = true
                    foundUIElement = true
                    // Use gesture click on the album art area (left side of mini player).
                    // performAction(ACTION_CLICK) on the ViewGroup container is received
                    // by QQ Music but doesn't trigger navigation to the player page —
                    // only a real touch event (gesture) opens it.
                    val x = rect.left + (rect.height() / 2f)  // center of album art area
                    val y = (rect.top + rect.bottom) / 2f
                    if (performGestureClick(x, y)) {
                        Log.i(TAG, "Clicked mini player via gesture at ($x, $y) (retry $retryCount)")
                        return true
                    }
                } else {
                    Log.i(TAG, "Mini player too small, assuming player page already open")
                    pendingClick = false
                    return true
                }
            } else if (lastFoundMiniPlayer) {
                // Mini player was present before but now disappeared - player page opened!
                Log.i(TAG, "Mini player disappeared, player page opened successfully")
                pendingClick = false
                return true
            }

            // Strategy 3: If no elements found after retries, assume already on player page
            if (retryCount > 3) {
                Log.i(TAG, "No clickable elements found after $retryCount retries, assuming player page already open")
                pendingClick = false
                return true
            }

            // Strategy 4: Fallback to known position
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
        // Fallback position: center of mini player at [50,2022][1058,2213]
        val x = 554f  // Center horizontally
        val y = 2117f  // Center vertically
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
