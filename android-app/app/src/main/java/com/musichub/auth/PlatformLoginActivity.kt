package com.musichub.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.musichub.databinding.ActivityPlatformLoginBinding
import com.musichub.platform.Platforms

/**
 * WebView-based login activity for platform authentication.
 * Loads the platform's official web login page, monitors CookieManager
 * for auth cookies, and returns the cookies on success.
 */
class PlatformLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlatformLoginBinding
    private lateinit var platform: String
    private var authCookieKeys: List<String> = emptyList()
    private var cookieDomains: List<String> = emptyList()
    private var loginCompleted = false

    private val handler = Handler(Looper.getMainLooper())
    private val cookiePollingRunnable = object : Runnable {
        override fun run() {
            if (!loginCompleted) {
                checkForAuthCookies("polling")
                handler.postDelayed(this, COOKIE_POLL_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlatformLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        platform = intent.getStringExtra(EXTRA_PLATFORM) ?: run {
            finish()
            return
        }

        authCookieKeys = PlatformAuthManager.getAuthCookieKeys(platform)
        cookieDomains = PlatformAuthManager.getCookieDomains(platform)
        val loginUrl = PlatformAuthManager.getLoginUrl(platform)

        if (loginUrl.isEmpty()) {
            finish()
            return
        }

        setupToolbar()
        setupWebView(loginUrl)
    }

    private fun setupToolbar() {
        val platformName = when (platform) {
            Platforms.NETEASE -> "网易云音乐"
            Platforms.QQMUSIC -> "QQ音乐"
            else -> platform
        }
        binding.toolbar.title = "登录 $platformName"
        binding.toolbar.setNavigationOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(loginUrl: String) {
        // Clear existing cookies for a clean login
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            // QQ Music mobile site shows a download page with mobile UA,
            // so use desktop UA for QQ Music to get the full website with login button.
            // NetEase works fine with mobile UA.
            userAgentString = if (platform == Platforms.QQMUSIC) {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            } else {
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page finished: $url")
                checkForAuthCookies(url ?: "")
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                Log.d(TAG, "URL loading: $url")

                // Check cookies on every navigation
                checkForAuthCookies(url)

                // Intercept non-HTTP(S) schemes (e.g. wtloginmqq://) to launch
                // external apps like QQ for OAuth login
                val uri = request.url
                val scheme = uri?.scheme?.lowercase()
                if (scheme != null && scheme != "http" && scheme != "https") {
                    Log.d(TAG, "Intercepting custom scheme: $scheme")
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.w(TAG, "No app to handle scheme '$scheme': ${e.message}")
                    }
                    return true
                }

                // Let WebView handle all HTTP(S) URLs in the login flow
                return false
            }
        }

        binding.webView.loadUrl(loginUrl)

        // Start polling for cookies - QQ login sometimes sets cookies via JS
        // without triggering onPageFinished
        handler.postDelayed(cookiePollingRunnable, COOKIE_POLL_INTERVAL_MS)
    }

    private fun checkForAuthCookies(source: String) {
        if (loginCompleted) return

        val cookieManager = CookieManager.getInstance()

        for (domain in cookieDomains) {
            val cookies = cookieManager.getCookie(domain) ?: continue
            for (key in authCookieKeys) {
                if (cookies.contains("$key=") && !cookies.contains("$key=;") && !cookies.contains("$key= ;")) {
                    Log.d(TAG, "Auth cookie '$key' detected for $platform on domain $domain (source: $source)")
                    loginCompleted = true
                    handler.removeCallbacks(cookiePollingRunnable)
                    onLoginSuccess(cookies, domain)
                    return
                }
            }
        }
    }

    private fun onLoginSuccess(cookies: String, source: String) {
        Log.i(TAG, "Login successful for $platform (from $source)")

        // Collect all relevant cookies from all domains
        val allCookies = collectAllRelevantCookies()
        Log.d(TAG, "Collected cookies: ${allCookies.take(200)}...")

        val resultIntent = Intent().apply {
            putExtra(EXTRA_PLATFORM, platform)
            putExtra(EXTRA_COOKIES, allCookies)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun collectAllRelevantCookies(): String {
        val cookieManager = CookieManager.getInstance()
        val allCookies = mutableSetOf<String>()

        for (domain in cookieDomains) {
            val cookies = cookieManager.getCookie(domain)
            if (cookies != null) {
                cookies.split(";").forEach { cookie ->
                    val trimmed = cookie.trim()
                    if (trimmed.isNotEmpty()) {
                        allCookies.add(trimmed)
                    }
                }
            }
        }

        return allCookies.joinToString("; ")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // When QQ app completes OAuth, it redirects back via the callback URL.
        // The WebView will handle the redirect and set cookies.
        val url = intent?.data?.toString()
        if (url != null) {
            Log.d(TAG, "onNewIntent URL: $url")
            binding.webView.loadUrl(url)
        } else {
            // QQ app returned without a URL — check cookies in case they were
            // set during the external app flow
            Log.d(TAG, "onNewIntent with no URL, checking cookies")
            checkForAuthCookies("onNewIntent")
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            setResult(Activity.RESULT_CANCELED)
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        loginCompleted = true
        handler.removeCallbacks(cookiePollingRunnable)
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlatformLoginActivity"
        private const val COOKIE_POLL_INTERVAL_MS = 1500L
        const val EXTRA_PLATFORM = "extra_platform"
        const val EXTRA_COOKIES = "extra_cookies"

        fun createIntent(context: Context, platform: String): Intent {
            return Intent(context, PlatformLoginActivity::class.java).apply {
                putExtra(EXTRA_PLATFORM, platform)
            }
        }
    }
}
