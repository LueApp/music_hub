package com.musichub.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Surface

/**
 * Helper for controlling system screen rotation.
 * Used to force landscape mode when viewing lyrics in music apps.
 */
object ScreenRotationHelper {

    private const val TAG = "ScreenRotationHelper"

    // Track whether we changed the rotation so we can restore it
    private var wasAutoRotateEnabled = true
    private var previousRotation = Surface.ROTATION_0
    private var didWeChangeRotation = false

    /**
     * Check if we have WRITE_SETTINGS permission.
     */
    fun canWriteSettings(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }
    }

    /**
     * Request WRITE_SETTINGS permission from the user.
     */
    fun requestWriteSettingsPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * Force the system to landscape rotation.
     */
    fun forceRotation(context: Context, landscape: Boolean) {
        if (!canWriteSettings(context)) {
            Log.w(TAG, "Cannot write settings, requesting permission")
            requestWriteSettingsPermission(context)
            return
        }

        try {
            // Save current state before changing
            if (!didWeChangeRotation) {
                wasAutoRotateEnabled = Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    1
                ) == 1
                previousRotation = Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.USER_ROTATION,
                    Surface.ROTATION_0
                )
            }

            // Disable auto-rotate
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                0
            )

            // Set rotation
            val rotation = if (landscape) Surface.ROTATION_90 else Surface.ROTATION_0
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.USER_ROTATION,
                rotation
            )

            didWeChangeRotation = true
            Log.d(TAG, "Forced rotation to ${if (landscape) "landscape" else "portrait"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force rotation: ${e.message}")
        }
    }

    /**
     * Check if we are currently in landscape mode (forced by us).
     */
    fun isLandscapeForced(context: Context): Boolean {
        if (!didWeChangeRotation) return false
        val currentRotation = Settings.System.getInt(
            context.contentResolver,
            Settings.System.USER_ROTATION,
            Surface.ROTATION_0
        )
        return currentRotation == Surface.ROTATION_90 || currentRotation == Surface.ROTATION_270
    }

    /**
     * Restore the original rotation settings.
     */
    fun restoreRotation(context: Context) {
        if (!didWeChangeRotation) return
        if (!canWriteSettings(context)) return

        try {
            // Restore auto-rotate setting
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (wasAutoRotateEnabled) 1 else 0
            )

            // Restore previous rotation
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.USER_ROTATION,
                previousRotation
            )

            didWeChangeRotation = false
            Log.d(TAG, "Restored original rotation settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore rotation: ${e.message}")
        }
    }

    /**
     * Toggle between landscape and portrait.
     * If currently landscape (forced by us), restore to portrait.
     * If currently portrait, force to landscape.
     */
    fun toggleLandscape(context: Context) {
        if (isLandscapeForced(context)) {
            restoreRotation(context)
        } else {
            forceRotation(context, landscape = true)
        }
    }
}
