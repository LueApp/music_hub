package com.musichub.service

import android.content.Intent
import android.util.Log

/**
 * Handles receiving shared content from other apps.
 */
object ShareReceiver {

    private const val TAG = "ShareReceiver"

    /**
     * Extract shared text from an intent.
     *
     * @param intent The incoming intent
     * @return The shared text, or null if not a share intent
     */
    fun getSharedText(intent: Intent?): String? {
        if (intent == null) return null

        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                Log.d(TAG, "Received shared text: $sharedText")
                return sharedText
            }
        }

        return null
    }

    /**
     * Check if an intent is a share intent.
     *
     * @param intent The intent to check
     * @return true if this is a share intent with text content
     */
    fun isShareIntent(intent: Intent?): Boolean {
        return intent?.action == Intent.ACTION_SEND &&
                intent.type == "text/plain" &&
                !intent.getStringExtra(Intent.EXTRA_TEXT).isNullOrBlank()
    }

    /**
     * Clear the share data from an intent to prevent re-processing.
     *
     * @param intent The intent to clear
     */
    fun clearIntent(intent: Intent?) {
        intent?.apply {
            action = null
            removeExtra(Intent.EXTRA_TEXT)
        }
    }
}
