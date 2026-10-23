package com.example.kleigiftwatcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject
import org.json.JSONTokener
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
    private var debugging = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val site = intent.getStringExtra(EXTRA_SITE) ?: "klei"
        debugging = site == "bili_debug"
        val initial = when (site) {
            "bili_debug" -> "https://space.bilibili.com/${Store.uid(this)}/dynamic"
            "bili" -> "https://www.bilibili.com/"
            else -> "https://accounts.klei.com/"
        }
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
        if (debugging) {
            val check = Button(this).apply {
                text = "检测当前可见网页的动态卡片"
                setOnClickListener { inspectPage() }
            }
            root.addView(check)
            status.text = "正在打开 B站动态网页。看到动态后点击「检测当前可见网页」。"
        }
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setSupportMultipleWindows(false)
        if (debugging) web.settings.userAgentString = BiliWebScanner.DESKTOP_UA
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, current: String) {
                if (debugging) {
                    status.text = "页面已加载，正在检查动态卡片：$current"
                    web.postDelayed({ if (!isFinishing) inspectPage() }, 4500)
                } else {
                    status.text = "当前页面：${Uri.parse(current).host ?: current}  ·  登录后返回 App 开始监控"
                }
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

    private fun inspectPage() {
        if (!debugging || !::web.isInitialized) return
        web.evaluateJavascript(BiliWebScanner.EXTRACT_JS) { encoded ->
            try {
                val json = JSONObject(JSONTokener(encoded).nextValue() as String)
                val meta = json.getJSONObject("meta")
                val cards = json.getJSONArray("cards")
                val list = ArrayList<BiliScanner.Card>()
                for (i in 0 until cards.length().coerceAtMost(15)) {
                    val card = cards.getJSONObject(i)
                    list.add(BiliScanner.Card(card.optString("source"), card.optString("content"),
                        card.optString("id"), card.optString("time")))
                }
                val result = BiliScanner(this).scanPage(list)
                val page = meta.optString("url").take(75)
                val title = meta.optString("title").take(55)
                val preview = meta.optString("preview").replace('\n', ' ').take(70)
                status.text = "网页诊断：动态卡片 ${result.cardCount} 条；礼物链接 ${result.gifts.size} 条；" +
                    "最新动态 ID ${result.latestId.ifBlank { "未知" }}\\n标题：$title\\n地址：$page\\n片段：$preview"
                Store.event(this, "可见网页诊断：卡片 ${result.cardCount}，链接 ${result.gifts.size}；$title")
            } catch (e: Exception) {
                status.text = "网页诊断脚本失败：${e.javaClass.simpleName}"
            }
        }
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
