package com.maherco.kicktv

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        web = findViewById(R.id.web_login)
        findViewById<ImageButton>(R.id.btn_close_login).setOnClickListener { finish() }

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                return if (host.endsWith("kick.com") || host.endsWith("google.com") ||
                    host.endsWith("gstatic.com") || host.endsWith("googleusercontent.com") ||
                    host.endsWith("accounts.google.com")
                ) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: Exception) {
                    }
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (finished) return
                val cookie = CookieManager.getInstance().getCookie("https://kick.com") ?: return
                if (cookie.contains("session_token")) {
                    finished = true
                    SettingsStore(this@LoginActivity).signedInName = "Kick"
                    Toast.makeText(this@LoginActivity, R.string.login_done, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        web.loadUrl("https://kick.com/login")
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        try {
            if (::web.isInitialized) {
                web.loadUrl("about:blank")
                web.destroy()
            }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
