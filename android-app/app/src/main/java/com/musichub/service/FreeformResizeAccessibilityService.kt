package com.musichub.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal accessibility service whose only job is to detect when HyperOS pulls
 * one of our background-launched freeform music tasks back on-screen during a
 * home gesture / recents transition, and to re-fire the Shizuku-driven resize
 * so the task stays sized down off-screen.
 *
 * Why this is a separate service from [PlayerAccessibilityService]:
 *
 *   HyperOS's accessibility security audit (com.lbe.security.miui /
 *   com.miui.securitycenter) silently disables sideloaded services whose
 *   manifest profile looks too powerful — specifically the combination of
 *   `canRetrieveWindowContent=true` + `canPerformGestures=true` + a broad
 *   packageNames list. PlayerAccessibilityService needs both of those
 *   capabilities (for the QQ Music mini-player click), so isolating it to a
 *   single package (com.tencent.qqmusic) keeps its profile narrow.
 *
 *   The freeform-resize trigger only needs to observe window-bounds-changed
 *   events on four music apps — no content reading, no gesture dispatch.
 *   Declaring it as its own service with `canRetrieveWindowContent=false`
 *   and `canPerformGestures=false` gives it a clean profile that the audit
 *   shouldn't flag, even though it lists four packages.
 *
 *   Splitting also limits blast radius: if HyperOS does manage to disable one
 *   of them, the other feature keeps working.
 */
class FreeformResizeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "FreeformResizeAccessibilityService connected")

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED
            packageNames = ShizukuLauncher.musicAppPackages().toTypedArray()
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 200
            // FLAG_RETRIEVE_INTERACTIVE_WINDOWS is required to receive
            // TYPE_WINDOWS_CHANGED events. It does NOT grant content reading
            // (that's canRetrieveWindowContent, which is false here).
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ShizukuLauncher.musicAppPackages()) {
            ShizukuLauncher.triggerResize(pkg)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "FreeformResizeAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "FreeformResizeAccessibilityService destroyed")
    }

    companion object {
        private const val TAG = "FreeformResizeA11y"

        @Volatile
        private var instance: FreeformResizeAccessibilityService? = null

        fun getInstance(): FreeformResizeAccessibilityService? = instance

        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(
                "${context.packageName}/${FreeformResizeAccessibilityService::class.java.canonicalName}"
            )
        }

        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
