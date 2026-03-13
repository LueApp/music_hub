package com.musichub.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "PlayerAccessibilityService connected")

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
        Log.d(TAG, "PlayerAccessibilityService destroyed")
    }

    /**
     * Request clicking the QQ Music mini player bar.
     * Call this after launching a QQ Music deep link in foreground mode.
     */
    fun requestClickMiniPlayer() {
        pendingClick = true
        retryCount = 0
        Log.d(TAG, "Mini player click requested")

        // Start retry loop — QQ Music needs time to process the deep link
        // and show the mini player bar. We retry every 500ms for up to 8 seconds.
        scheduleRetry()

        // Timeout: give up after 8 seconds
        handler.postDelayed({
            if (pendingClick) {
                pendingClick = false
                Log.w(TAG, "Mini player click timed out after $retryCount retries")
            }
        }, MAX_TIMEOUT_MS)
    }

    private fun scheduleRetry() {
        handler.postDelayed({
            if (pendingClick) {
                retryCount++
                if (!tryClickMiniPlayer()) {
                    // Schedule next retry
                    if (retryCount < MAX_RETRIES) {
                        scheduleRetry()
                    }
                }
            }
        }, RETRY_INTERVAL_MS)
    }

    private fun tryClickMiniPlayer(): Boolean {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.d(TAG, "rootInActiveWindow is null (retry $retryCount)")
            return false
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
                // Click in the upper portion of the card (song info area, not controls)
                val x = (rect.left + rect.right) / 2f
                val y = rect.top + 150f  // 150px from top of card (in song info area)
                if (performGestureClick(x, y)) {
                    pendingClick = false
                    Log.i(TAG, "Clicked now-playing card (cxs) at ($x, $y) (retry $retryCount)")
                    return true
                }
            }

            // Strategy 2: After retries, try the mini player container (jqv)
            val miniPlayerNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "$QQMUSIC_PACKAGE:id/jqv"
            )
            if (miniPlayerNodes.isNotEmpty()) {
                val node = miniPlayerNodes[0]
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)

                val x = (rect.left + rect.right) / 2f
                val y = (rect.top + rect.bottom) / 2f

                if (performGestureClick(x, y)) {
                    pendingClick = false
                    Log.i(TAG, "Clicked mini player at ($x, $y) (retry $retryCount)")
                    return true
                }
            }

            Log.d(TAG, "No clickable target found (retry $retryCount)")
        } catch (e: Exception) {
            Log.e(TAG, "Error trying to click mini player: ${e.message}")
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
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
            path, 0, 100
        )
        gestureBuilder.addStroke(strokeDescription)

        return dispatchGesture(gestureBuilder.build(), null, null)
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

        @Volatile
        private var instance: PlayerAccessibilityService? = null

        fun getInstance(): PlayerAccessibilityService? = instance

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
