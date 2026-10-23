package com.example.kleigiftwatcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Log in through the platform page here; cookies stay in Android WebView's private app storage. */
class BrowserActivity : Activity() {
    companion object {
        const val EXTRA_SITE = "site"
        const val EXTRA_URL = "url"
    }
    private lateinit var web: WebView
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val site = intent.getStringExtra(EXTRA_SITE) ?: "klei"
        val initial = if (site == "bili") "https://www.bilibili.com/" else "https://accounts.klei.com/"
        val requested = intent.getStringExtra(EXTRA_URL)
        val url = if (site == "klei") Store.verifiedUrl(requested ?: "") ?: initial else initial

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 14.dp(), 12.dp(), 8.dp())
            setBackgroundColor(Color.WHITE)
        }
        status = TextView(this).apply {
            text = if (site == "klei") "请在科雷网页核对领取账号，然后登录" else "请登录 B站，以便读取公开动态（可选）"
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }
        root.addView(status)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = Button(this).apply { text = "返回"; setOnClickListener { if (web.canGoBack()) web.goBack() else finish() } }
        val reload = Button(this).apply { text = "刷新"; setOnClickListener { web.reload() } }
        val home = Button(this).apply { text = "账号首页"; setOnClickListener { web.loadUrl(initial) } }
        nav.addView(back, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(reload, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(home, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(nav)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, current: String) {
                status.text = "当前页面：${Uri.parse(current).host ?: current}  ·  登录后返回 App 开始监控"
                CookieManager.getInstance().flush()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https" || uri.scheme == "http") return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri)); true
                } catch (_: Exception) { true }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        web.loadUrl(url)
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }
}

fun Int.dp(): Int = (this * android.content.res.Resources.getSystem().displayMetrics.density + 0.5f).toInt()
