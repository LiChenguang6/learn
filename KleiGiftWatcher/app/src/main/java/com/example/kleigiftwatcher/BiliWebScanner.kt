package com.example.kleigiftwatcher

import android.annotation.SuppressLint
import android.content.Context
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
 * Experimental page reader. Loads real web content; this is NOT a way to bypass
 * Bilibili access controls. Headless rendering may fail on some devices.
 */
class BiliWebScanner(
    private val context: Context,
    private val onResult: (BiliScanner.ScanResult?, Boolean, Boolean, String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var activeId = 0
    private var finished = true
    private var attempts = 0

    fun isBusy(): Boolean = !finished

    @SuppressLint("SetJavaScriptEnabled")
    fun refresh(uid: String) {
        if (!uid.matches(Regex("[0-9]{1,20}"))) {
            onResult(null, false, false, "无效 UID")
            return
        }
        // Never kill a browser navigation in the middle of loading.
        if (!finished) return
        val id = ++activeId
        finished = false
        attempts = 0
        try {
            val view = web ?: WebView(context).also { v ->
                v.settings.javaScriptEnabled = true
                v.settings.domStorageEnabled = true
                v.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                v.settings.javaScriptCanOpenWindowsAutomatically = false
                v.settings.userAgentString = DESKTOP_UA
                CookieManager.getInstance().setAcceptCookie(true)
                v.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        if (view !== web || finished) return
                        val current = activeId
                        // onPageFinished does not mean JS-rendered cards are ready.
                        handler.postDelayed({ inspect(current) }, 2400)
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse
                    ) {
                        val code = errorResponse.statusCode
                        val host = request.url.host.orEmpty()
                        val path = request.url.path.orEmpty()
                        if ((code == 412 || code == 403) &&
                            ((host == "api.bilibili.com" && path.contains("web-dynamic")) ||
                            (host == "space.bilibili.com" && request.isForMainFrame))) {
                            done(activeId, null, false, true, "网页请求 HTTP $code")
                        } else if (request.isForMainFrame && code >= 400) {
                            done(activeId, null, false, false, "网页主文档 HTTP $code")
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        if (request.isForMainFrame) done(activeId, null, false, false,
                            "网页网络失败：${error.description.toString().take(75)}")
                    }
                }
                v.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY)
                )
                v.layout(0, 0, 1080, 1600)
                web = v
            }
            view.loadUrl("https://space.bilibili.com/$uid/dynamic")
            handler.postDelayed({
                if (id == activeId && !finished) {
                    inspect(id, lastAttempt = true)
                }
            }, 16000L)
            handler.postDelayed({
                if (id == activeId && !finished) done(id, null, false, false, "网页超时或 JavaScript 未响应")
            }, 21000L)
        } catch (e: Exception) {
            done(id, null, false, false, "WebView 初始化失败：${e.javaClass.simpleName}")
        }
    }

    private fun inspect(id: Int, lastAttempt: Boolean = false) {
        if (id != activeId || finished) return
        val view = web ?: return
        view.evaluateJavascript(EXTRACT_JS) { encoded ->
            if (id != activeId || finished) return@evaluateJavascript
            try {
                val decoded = JSONTokener(encoded).nextValue() as? String ?: "{}"
                val root = JSONObject(decoded)
                val cards = root.optJSONArray("cards")
                val meta = root.optJSONObject("meta") ?: JSONObject()
                val page = meta.optString("url").take(95)
                val title = meta.optString("title").take(50)
                if (cards != null && cards.length() > 0) {
                    val elements = ArrayList<BiliScanner.Card>()
                    for (i in 0 until cards.length().coerceAtMost(15)) {
                        val item = cards.optJSONObject(i) ?: continue
                        val source = item.optString("source").takeIf { it.startsWith("https://www.bilibili.com/opus/") }
                            ?: page
                        elements.add(BiliScanner.Card(source, item.optString("content"),
                            item.optString("id"), item.optString("time")))
                    }
                    val result = BiliScanner(context).scanPage(elements)
                    done(id, result, true, false,
                        "读取 ${elements.size} 条动态，最新 ID ${result.latestId.ifBlank { "未知" }}")
                } else if (!lastAttempt && ++attempts < 5) {
                    handler.postDelayed({ inspect(id) }, 2300L)
                } else {
                    val preview = meta.optString("preview").replace("\n", " ").take(75)
                    done(id, null, false, false,
                        "未找到动态卡片；标题=$title；地址=$page；片段=$preview")
                }
            } catch (e: Exception) {
                done(id, null, false, false, "网页脚本解析失败：${e.javaClass.simpleName}")
            }
        }
    }

    private fun done(id: Int, result: BiliScanner.ScanResult?, ready: Boolean, blocked: Boolean, info: String) {
        if (id != activeId || finished) return
        finished = true
        onResult(result, ready, blocked, info)
    }

    fun destroy() {
        ++activeId
        finished = true
        handler.removeCallbacksAndMessages(null)
        web?.stopLoading()
        web?.destroy()
        web = null
    }

    companion object {
        const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** Same script is used for visible diagnostics and background monitoring. */
        val EXTRACT_JS = """(function() {
            var selectors = '.bili-dyn-list__item, .bili-dyn-item[data-did], [data-did].bili-dyn-list__item, [data-dynamic-id]';
            var nodes = Array.from(document.querySelectorAll(selectors));
            if (!nodes.length) {
                // Some versions place a dyn-id on the post content instead of the wrapper.
                nodes = Array.from(document.querySelectorAll('.dyn-card-opus[dyn-id], .bili-dyn-card-video[dyn-id]'))
                    .map(function(x) { return x.closest('.bili-dyn-item, .bili-dyn-list__item') || x; });
            }
            nodes = nodes.filter(function(el, i) {
                return nodes.indexOf(el) === i && !nodes.some(function(p) { return p !== el && p.contains(el); });
            }).slice(0, 15);
            var result = nodes.map(function(el) {
                var opus = el.querySelector('a[href*="/opus/"]');
                var anchor = opus ? opus.href : '';
                var links = Array.from(el.querySelectorAll('a[href]'))
                    .slice(0, 150).map(function(a) { return a.href; }).join(' ');
                var dyn = el.getAttribute('data-did') || el.getAttribute('data-dynamic-id') ||
                    (el.querySelector('[dyn-id]') || {}).getAttribute?.('dyn-id') || '';
                var id = dyn || ((anchor.match(/\/opus\/(\d+)/) || [])[1] || '');
                var timeEl = el.querySelector('.bili-dyn-time, .bili-dyn-item__header__time, [class*="pub-time"]');
                return {
                    source: anchor || (id ? 'https://www.bilibili.com/opus/' + id : location.href),
                    id: id,
                    time: timeEl ? (timeEl.innerText || '').slice(0, 70) : '',
                    content: (el.innerText || '').slice(0, 70000) + ' ' + links + ' ' +
                        (el.innerHTML || '').slice(0, 130000)
                };
            });
            return JSON.stringify({cards: result, meta: {
                url: location.href,
                title: document.title || '',
                ready: document.readyState,
                count: nodes.length,
                preview: (document.body ? document.body.innerText : '').slice(0, 140)
            }});
        })()"""
    }
}
