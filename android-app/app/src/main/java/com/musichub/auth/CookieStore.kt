package com.musichub.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure cookie storage using EncryptedSharedPreferences.
 * Stores auth cookies keyed by platform name.
 */
class CookieStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getCookies(platform: String): String? {
        return prefs.getString(keyFor(platform), null)
    }

    fun setCookies(platform: String, cookies: String) {
        prefs.edit().putString(keyFor(platform), cookies).apply()
        Log.d(TAG, "Stored cookies for platform: $platform")
    }

    fun clearCookies(platform: String) {
        prefs.edit().remove(keyFor(platform)).apply()
        Log.d(TAG, "Cleared cookies for platform: $platform")
    }

    fun hasCookies(platform: String): Boolean {
        return prefs.getString(keyFor(platform), null) != null
    }

    private fun keyFor(platform: String): String = "auth_$platform"

    companion object {
        private const val TAG = "CookieStore"
        private const val PREFS_NAME = "musichub_auth_cookies"
    }
}
