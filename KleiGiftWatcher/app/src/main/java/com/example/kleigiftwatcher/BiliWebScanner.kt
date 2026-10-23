package com.example.kleigiftwatcher

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Experimental browser-based dynamic scanner.
 * It loads the real space page and reads rendered cards, not the dynamic JSON API directly.
 * The Bilibili website can still use APIs internally and may return HTTP 412.
 * All methods and callbacks run on the Android main thread.
 */
class BiliWebScanner(
    private val context: Context,
    private val onResult: (BiliScanner.ScanResult?, Boolean, Boolean, String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var activeId = 0
    private var finished = true
    private var cardAttempts = 0
    private var saw412 = false

    @SuppressLint("SetJavaScriptEnabled")
    fun refresh(uid: String) {
        if (!uid.matches(Regex("[0-9]{1,20}"))) {
            onResult(null, false, false, "无效的 UID")
            return
        }
        val id = ++activeId
        finished = false
        cardAttempts = 0
        saw412 = false
        val url = "https://space.bilibili.com/$uid/dynamic"
        try {
            val view = web ?: WebView(context).also { v ->
                v.settings.javaScriptEnabled = true
                v.settings.domStorageEnabled = true
                v.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                CookieManager.getInstance().setAcceptCookie(true)
                v.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        val current = activeId
                        if (finished || view !== web) return
                        handler.postDelayed({ inspect(current) }, 2500)
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse
                    ) {
                        if (errorResponse.statusCode != 412) return
                        val host = request.url.host.orEmpty()
                        val path = request.url.path.orEmpty()
                        if ((host == "api.bilibili.com" && path.contains("web-dynamic")) ||
                            (host == "space.bilibili.com" && request.isForMainFrame)) {
                            saw412 = true
                            done(activeId, null, false, true, "网页加载遇到 HTTP 412")
                        }
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) done(activeId, null, false, false, "B站网页加载失败")
                    }
                }
                // Headless WebView rendering is device-dependent. It still needs a layout.
                v.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1800, android.view.View.MeasureSpec.EXACTLY)
                )
                v.layout(0, 0, 1080, 1800)
                web = v
            }
            view.stopLoading()
            view.loadUrl(url)
            handler.postDelayed({
                if (id == activeId && !finished) done(id, null, false, saw412, "网页超时，切换备用 API")
            }, 14000)
        } catch (e: Exception) {
            done(id, null, false, false, "WebView 不可用：${e.javaClass.simpleName}")
        }
    }

    private fun inspect(id: Int) {
        if (id != activeId || finished) return
        val view = web ?: return
        view.evaluateJavascript(EXTRACT_JS) { encoded ->
            if (id != activeId || finished) return@evaluateJavascript
            try {
                val decoded = JSONTokener(encoded).nextValue() as? String ?: "{}"
                val root = JSONObject(decoded)
                val cards = root.optJSONArray("cards")
                if (cards != null && cards.length() > 0) {
                    val elements = ArrayList<BiliScanner.Card>()
                    val currentUrl = view.url.orEmpty()
                    for (i in 0 until cards.length().coerceAtMost(15)) {
                        val item = cards.optJSONObject(i) ?: continue
                        val src = item.optString("source").takeIf {
                            it.startsWith("https://www.bilibili.com/opus/") ||
                            it.startsWith("https://space.bilibili.com/")
                        } ?: currentUrl
                        elements.add(BiliScanner.Card(src, item.optString("content"), item.optString("id"), item.optString("time")))
                    }
                    val matches = BiliScanner(context).scanPage(elements)
                    done(id, matches, true, false, "网页解析 ${elements.size} 条动态")
                } else if (++cardAttempts < 3) {
                    handler.postDelayed({ inspect(id) }, 2500)
                } else {
                    done(id, null, false, false, "网页未显示动态卡片，改用备用 API")
                }
            } catch (_: Exception) {
                done(id, null, false, false, "网页内容解析失败")
            }
        }
    }

    private fun done(id: Int, gifts: BiliScanner.ScanResult?, ready: Boolean, blocked: Boolean, info: String) {
        if (id != activeId || finished) return
        finished = true
        onResult(gifts, ready, blocked, info)
    }

    fun destroy() {
        ++activeId
        finished = true
        web?.stopLoading()
        web?.destroy()
        web = null
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val EXTRACT_JS = """(function() {
            var cards = Array.from(document.querySelectorAll(
                '.bili-dyn-list__item, .bili-dyn-item, [data-dynamic-id]'
            ));
            cards = cards.filter(function(el) {
                return !cards.some(function(parent) { return parent !== el && parent.contains(el); });
            }).slice(0, 15);
            var result = cards.map(function(el) {
                var a = el.querySelector('a[href*="/opus/"]');
                var links = Array.from(el.querySelectorAll('a[href]'))
                    .slice(0, 40).map(function(a) { return a.href; }).join(' ');
                return {
                    source: a ? a.href : location.href,
                    id: (a ? ((a.href.match(/\\/opus\\/(\\d+)/)||[])[1] || '') : '') || el.getAttribute('data-dynamic-id') || '',
                    time: (el.querySelector('.bili-dyn-time, .bili-dyn-item__header__time, [class*="pub-time"]') || {}).innerText || '',
                    content: (el.innerText || '').slice(0, 18000) + ' ' +
                        links + ' ' + (el.innerHTML || '').slice(0, 30000)
                };
            });
            return JSON.stringify({cards: result});
        })()"""
    }
}
