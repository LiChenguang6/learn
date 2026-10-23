package com.example.kleigiftwatcher

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.ArrayDeque

/**
 * Best-effort browser automation. This cannot guarantee a reward is claimed:
 * Klei/Steam may require user interaction, CAPTCHA, or use a changed UI.
 * No passwords or cookies are sent to our servers.
 * All WebView calls run on Android's main thread.
 */
class AutoRedeemer(private val context: Context, private val onUpdate: (String, String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var web: WebView? = null
    private var current: String? = null
    private var pageId = 0
    private var clickAttempted = false
    private var finished = false
    private var checks = 0

    @SuppressLint("SetJavaScriptEnabled")
    fun enqueue(raw: String) {
        val url = Store.verifiedUrl(raw) ?: return
        if (current == url || queue.contains(url)) return
        queue.add(url)
        if (current == null) next()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun next() {
        web?.stopLoading()
        web?.destroy()
        web = null
        current = queue.poll()
        val url = current ?: return
        finished = false
        clickAttempted = false
        checks = 0
        val id = ++pageId
        try {
            val view = WebView(context)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, currentUrl: String) {
                    if (pageId != id || finished) return
                    val host = Uri.parse(currentUrl).host?.lowercase() ?: ""
                    val path = Uri.parse(currentUrl).path ?: ""
                    if (host != "accounts.klei.com" || path.startsWith("/login")) {
                        complete(url, "需要在 App 内重新登录科雷账号")
                    } else if (!path.startsWith("/creatorlink/") && !path.startsWith("/link/")) {
                        complete(url, "跳转到其他科雷页面，请手动核实领取结果")
                    } else {
                        handler.postDelayed({ inspect(id, url) }, 1250)
                    }
                }
                override fun onReceivedError(v: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame && !finished && id == pageId) complete(url, "网页访问失败，请手动检查")
                }
            }
            web = view
            // Give the headless view a usable layout size. WebView execution is still
            // subject to device-specific background rendering restrictions.
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1800, android.view.View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, 1080, 1800)
            onUpdate(url, "正在尝试自动领取")
            view.loadUrl(url)
            handler.postDelayed({
                if (pageId == id && !finished) complete(url, "自动操作超时，请手动领取")
            }, 25000)
        } catch (_: Exception) { complete(url, "自动操作不可用，请手动领取") }
    }

    private fun inspect(id: Int, url: String) {
        if (id != pageId || finished || checks++ > 5) return
        val view = web ?: return
        // A safe, local heuristic: click only a visible enabled reward button
        // on the verified Klei origin. Never click on external OAuth pages.
        val js = if (clickAttempted) INSPECT_JS else TRY_CLAIM_JS
        view.evaluateJavascript(js) { raw ->
            if (id != pageId || finished) return@evaluateJavascript
            val result = raw.orEmpty()
            when {
                result.contains("CONFIRMED") -> complete(url, "网页显示已领取（请核实账号）")
                result.contains("CLICKED") -> {
                    clickAttempted = true
                    onUpdate(url, "已点击领取按钮，等待网页反馈")
                    handler.postDelayed({ inspect(id, url) }, 2400)
                }
                checks >= 5 -> complete(url,
                    if (clickAttempted) "已尝试自动领取（未核实结果）" else "未找到领取按钮，请手动检查")
                else -> handler.postDelayed({ inspect(id, url) }, 1500)
            }
        }
    }

    private fun complete(url: String, result: String) {
        if (finished) return
        finished = true
        onUpdate(url, result)
        handler.postDelayed({ next() }, 300)
    }

    fun destroy() {
        ++pageId
        finished = true
        current = null
        queue.clear()
        web?.stopLoading()
        web?.destroy()
        web = null
    }

    companion object {
        private const val DOM_CHECK = """
            var t=(document.body && document.body.innerText || '').toLowerCase();
            if (/claimed successfully|successfully claimed|you have claimed|领取成功|成功领取|已成功领取|兑换成功/.test(t)) return 'CONFIRMED';
        """
        private val INSPECT_JS = "(function(){ $DOM_CHECK return 'WAIT'; })()"
        private val TRY_CLAIM_JS = """(function(){
            $DOM_CHECK
            var buttons=Array.from(document.querySelectorAll('button, input[type=submit], a[role=button], a'));
            var b=buttons.find(function(x){
                var text=(x.innerText||x.value||x.getAttribute('aria-label')||'').trim();
                var style=window.getComputedStyle(x);
                return /^(claim|claim reward|claim item|claim your reward|claim your gift|redeem|redeem reward|领取|立即领取|领取奖励|兑换|获取奖励)$/i.test(text)
                   && !x.disabled && style.display!=='none' && style.visibility!=='hidden';
            });
            if(b){b.click();return 'CLICKED';}
            return 'WAIT';
        })()"""
    }
}
