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
import java.util.ArrayDeque

/**
 * Best-effort automated Klei claim. Does not use the Chrome cookie jar.
 * Only retries a failed navigation once BEFORE clicking a claim button.
 * Cannot guarantee headless WebView rendering on a locked Android device.
 */
class AutoRedeemer(private val context: Context, private val onUpdate: (String, String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var web: WebView? = null
    private var current: String? = null
    private var pageId = 0
    private var clickAttempted = false
    private var finished = true
    private var checks = 0
    private var loadRetries = 0

    fun enqueue(raw: String) {
        val url = Store.verifiedUrl(raw) ?: return
        if (current == url || queue.contains(url)) return
        queue.add(url)
        if (current == null) next()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun next() {
        current = queue.poll()
        val url = current ?: return
        finished = false
        clickAttempted = false
        checks = 0
        loadRetries = 0
        val id = ++pageId
        try {
            // Reuse WebView within the service lifetime; avoid creating a new
            // Chromium instance for every reward.
            val view = web ?: WebView(context).also { v ->
                v.settings.javaScriptEnabled = true
                v.settings.domStorageEnabled = true
                v.settings.setSupportMultipleWindows(false)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
                v.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1800, android.view.View.MeasureSpec.EXACTLY)
                )
                v.layout(0, 0, 1080, 1800)
                web = v
            }
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, currentUrl: String) {
                    if (id != pageId || finished) return
                    val host = Uri.parse(currentUrl).host?.lowercase() ?: ""
                    if (host != "accounts.klei.com") {
                        complete(url, "科雷跳转到第三方登录或其他站点，请通过浏览器人工处理")
                    } else {
                        // Do not treat redirects within Klei as failures:
                        // they might be confirmation, an error, or a sign-in page.
                        handler.postDelayed({ inspect(id, url) }, 1500)
                    }
                }

                override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame || id != pageId || finished) return
                    retryBeforeClaim(id, url, "网络加载失败：${error.description.toString().take(65)}")
                }

                override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (!request.isForMainFrame || id != pageId || finished) return
                    val code = response.statusCode
                    if (code in 500..599) retryBeforeClaim(id, url, "Klei HTTP $code")
                    else if (code == 403 || code == 429) complete(url, "Klei HTTP $code：访问受限，请浏览器人工检查")
                    else if (code >= 400) complete(url, "Klei HTTP $code：网页未正常响应，请人工检查")
                }
            }
            onUpdate(url, "科雷网页正在加载，尝试自动领取")
            view.stopLoading()
            view.loadUrl(url)
            handler.postDelayed({
                if (id == pageId && !finished) complete(url,
                    if (clickAttempted) "已经尝试领取但结果未核实，请手动核对" else "科雷网页加载或自动操作超时，请浏览器人工领取")
            }, 38000)
        } catch (e: Exception) {
            complete(url, "自动领奖网页不可用：${e.javaClass.simpleName}，请人工领取")
        }
    }

    private fun retryBeforeClaim(id: Int, url: String, cause: String) {
        if (id != pageId || finished) return
        if (clickAttempted || loadRetries >= 1) {
            complete(url, "$cause；自动重试已停止，请人工领取")
            return
        }
        ++loadRetries
        checks = 0
        onUpdate(url, "$cause，正在重试网页加载（第 1/1 次）")
        handler.postDelayed({
            if (id != pageId || finished || clickAttempted) return@postDelayed
            try { web?.stopLoading(); web?.loadUrl(url) }
            catch (_: Exception) { complete(url, "重试 Klei 页面失败，请人工领取") }
        }, 2200)
    }

    private fun inspect(id: Int, url: String) {
        if (id != pageId || finished || checks++ > 7) return
        val view = web ?: return
        val js = if (clickAttempted) INSPECT_JS else TRY_CLAIM_JS
        view.evaluateJavascript(js) { raw ->
            if (id != pageId || finished) return@evaluateJavascript
            val result = raw.orEmpty()
            when {
                result.contains("CONFIRMED") -> complete(url, "科雷网页显示已领取（仍需核实账号）")
                result.contains("CLICKED") -> {
                    clickAttempted = true
                    onUpdate(url, "已点击领取按钮，等待网页反馈（不会重复点击）")
                    handler.postDelayed({ inspect(id, url) }, 2500)
                }
                checks >= 7 -> complete(url,
                    if (clickAttempted) "已点击领取按钮但未能核实结果，请人工检查"
                    else "没有找到领取按钮或登录会话失效，请浏览器人工处理")
                else -> handler.postDelayed({ inspect(id, url) }, 1700)
            }
        }
    }

    private fun complete(url: String, result: String) {
        if (finished) return
        finished = true
        onUpdate(url, result)
        handler.postDelayed({ next() }, 400)
    }

    fun destroy() {
        ++pageId
        finished = true
        current = null
        queue.clear()
        web?.stopLoading()
        web?.destroy()
        web = null
        handler.removeCallbacksAndMessages(null)
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
