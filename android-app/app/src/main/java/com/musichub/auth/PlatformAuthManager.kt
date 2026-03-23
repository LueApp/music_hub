package com.musichub.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import com.musichub.platform.Platforms
import okhttp3.Request

/**
 * Manages auth state per platform: login status, cookies, token refresh.
 */
class PlatformAuthManager(context: Context) {

    private val cookieStore = CookieStore(context)

    fun isLoggedIn(platform: String): Boolean {
        return cookieStore.hasCookies(platform)
    }

    fun getCookies(platform: String): String? {
        return cookieStore.getCookies(platform)
    }

    fun storeCookies(platform: String, cookies: String) {
        cookieStore.setCookies(platform, cookies)
    }

    fun logout(platform: String) {
        cookieStore.clearCookies(platform)
        Log.d(TAG, "Logged out from platform: $platform")
    }

    /**
     * Inject auth cookies into an OkHttp request.
     */
    fun injectAuth(requestBuilder: Request.Builder, platform: String): Request.Builder {
        val cookies = cookieStore.getCookies(platform) ?: return requestBuilder
        requestBuilder.header("Cookie", cookies)
        return requestBuilder
    }

    /**
     * Create an intent to launch the platform login activity.
     */
    fun createLoginIntent(context: Context, platform: String): Intent {
        return PlatformLoginActivity.createIntent(context, platform)
    }

    /**
     * Handle login result from PlatformLoginActivity.
     * Returns true if login was successful.
     */
    fun handleLoginResult(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK) return false

        val platform = data?.getStringExtra(PlatformLoginActivity.EXTRA_PLATFORM) ?: return false
        val cookies = data.getStringExtra(PlatformLoginActivity.EXTRA_COOKIES) ?: return false

        storeCookies(platform, cookies)
        Log.d(TAG, "Login successful for platform: $platform")
        return true
    }

    /**
     * Check if a response indicates an expired auth session.
     */
    fun isAuthExpired(responseCode: Int): Boolean {
        return responseCode == 401 || responseCode == 403
    }

    /**
     * Handle expired auth by clearing cookies for the platform.
     */
    fun handleExpiredAuth(platform: String) {
        logout(platform)
        Log.w(TAG, "Auth expired for platform: $platform")
    }

    companion object {
        private const val TAG = "PlatformAuthManager"

        // Platform login URLs
        fun getLoginUrl(platform: String): String {
            return when (platform) {
                Platforms.NETEASE -> "https://music.163.com/#/login"
                Platforms.QQMUSIC -> "https://y.qq.com"
                else -> ""
            }
        }

        // Auth cookie detection keys (any of these indicates successful login)
        fun getAuthCookieKeys(platform: String): List<String> {
            return when (platform) {
                Platforms.NETEASE -> listOf("MUSIC_U")
                Platforms.QQMUSIC -> listOf("qm_keyst", "qqmusic_key", "p_skey")
                else -> emptyList()
            }
        }

        // Primary auth cookie key for backward compatibility
        fun getAuthCookieKey(platform: String): String {
            return getAuthCookieKeys(platform).firstOrNull() ?: ""
        }

        // Domains to check for cookies
        fun getCookieDomains(platform: String): List<String> {
            return when (platform) {
                Platforms.NETEASE -> listOf("music.163.com", ".163.com")
                Platforms.QQMUSIC -> listOf("y.qq.com", ".qq.com", "graph.qq.com", ".y.qq.com", "u.y.qq.com", "ssl.ptlogin2.qq.com", "xui.ptlogin2.qq.com")
                else -> emptyList()
            }
        }

        // Platforms that support auth-based recommendations
        val SUPPORTED_PLATFORMS = listOf(Platforms.NETEASE, Platforms.QQMUSIC)
    }
}
