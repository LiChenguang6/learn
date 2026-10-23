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
import android.webkit.RenderProcessGoneDetail
import java.util.ArrayDeque

/**
 * App-only best-effort Klei redemption. The WebView cookie jar is shared with
 * the visible in-app login screen; no Chrome/Custom Tabs are needed.
 *
 * No claim button is clicked twice automatically for a given link. A click in
 * progress is durably recorded before JavaScript is executed so crashes do not
 * cause unsafe automatic re-submission.
 */
class AutoRedeemer(private val context: Context, private val onUpdate: (String, String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var view: WebView? = null
    private var current: String? = null
    private var generation = 0
    private var finished = true
    private var clicked = false
    private var pageRetries = 0
    private var polls = 0

    fun enqueue(raw: String) {
        val url = Store.verifiedUrl(raw) ?: return
        val state = Store.claimState(context, url)
        if (state in listOf("clicked", "clicking", "confirmed", "uncertain")) {
            Store.event(context, "避免重复自动提交：链接领取状态为 $state")
            return
        }
        if (current == url || queue.contains(url)) return
        queue.add(url)
        if (current == null) next()
    }

    private fun next() {
        if (current != null) return
        val url = queue.poll() ?: return
        current = url
        val id = ++generation
        finished = false
        clicked = false
        pageRetries = 0
        polls = 0
        Store.markClaim(context, url, "loading", "正在打开科雷领奖网页")
        try {
            val web = obtainWebView()
            web.stopLoading()
            web.loadUrl(url)
            handler.postDelayed({
                if (generation == id && !finished) {
                    finish(url, "uncertain", "自动领奖超时；请进入 App 内查看领奖页面")
                }
            }, 50000L)
        } catch (e: Exception) {
            finish(url, "failed", "科雷 WebView 无法启动：${e.javaClass.simpleName}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun obtainWebView(): WebView {
        return view ?: WebView(context).also { web ->
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.setSupportMultipleWindows(false)
            web.settings.javaScriptCanOpenWindowsAutomatically = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(w: WebView, url: String) {
                    if (finished || w !== view) return
                    CookieManager.getInstance().flush()
                    val original = current ?: return
                    val uri = Uri.parse(url)
                    if (uri.host?.lowercase() != "accounts.klei.com") {
                        finish(original, "needs_login", "需要在 App 内完成科雷或游戏平台登录；已保留领奖链接")
                        return
                    }
                    val id = generation
                    handler.postDelayed({ inspect(id, original) }, 900L)
                }

                override fun onReceivedError(w: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame || finished) return
                    current?.let { transientFailure(it, "科雷网络失败：${error.description.toString().take(75)}") }
                }

                override fun onReceivedHttpError(w: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (!request.isForMainFrame || finished) return
                    val status = response.statusCode
                    current?.let { url ->
                        when {
                            status == 403 || status == 429 -> finish(url, "failed", "科雷 HTTP $status：访问受限；请在 App 内查看")
                            status in 500..599 -> transientFailure(url, "科雷 HTTP $status")
                            status >= 400 -> finish(url, "failed", "科雷 HTTP $status：打开链接失败；请在 App 内查看")
                        }
                    }
                }

                override fun onRenderProcessGone(w: WebView, detail: RenderProcessGoneDetail): Boolean {
                    val url = current
                    w.destroy()
                    view = null
                    if (url != null && !finished) finish(url, "failed", "网页渲染进程终止，请在 App 内重新打开")
                    return true
                }
            }
            web.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1800, android.view.View.MeasureSpec.EXACTLY)
            )
            web.layout(0, 0, 1080, 1800)
            view = web
        }
    }

    private fun transientFailure(url: String, reason: String) {
        if (finished) return
        // Retries are navigation-only; never repeat a clicked claim operation.
        if (clicked || pageRetries >= 2) {
            finish(url, if (clicked) "uncertain" else "failed", "$reason，自动重试结束")
            return
        }
        val id = generation
        pageRetries++
        polls = 0
        Store.markClaim(context, url, "loading", "$reason；准备重载网页（$pageRetries/2）")
        handler.postDelayed({
            if (generation != id || finished || clicked) return@postDelayed
            try { view?.stopLoading(); view?.loadUrl(url) }
            catch (e: Exception) { finish(url, "failed", "重新加载网页失败：${e.javaClass.simpleName}") }
        }, if (pageRetries == 1) 1800L else 4000L)
    }

    private fun inspect(id: Int, url: String) {
        if (generation != id || finished || clicked) return
        val web = view ?: return
        if (++polls > 13) {
            finish(url, "needs_login", "网页打开后未发现可领取按钮，请进入 App 内核实登录")
            return
        }
        web.evaluateJavascript(INSPECT_JS) { raw ->
            if (generation != id || finished || clicked) return@evaluateJavascript
            when {
                raw.contains("ALREADY") -> finish(url, "confirmed", "科雷页面提示已领取或领取成功（请核对账号）")
                raw.contains("UNAVAILABLE") -> finish(url, "failed", "科雷页面提示礼物不可领取或已经失效")
                raw.contains("LOGIN") -> finish(url, "needs_login", "科雷账号登录已失效，请在 App 内登录并重试")
                raw.contains("READY") -> clickOnce(id, url)
                else -> handler.postDelayed({ inspect(id, url) }, 1400L)
            }
        }
    }

    private fun clickOnce(id: Int, url: String) {
        if (generation != id || finished || clicked) return
        // Persist uncertainty BEFORE executing the mutating JavaScript action.
        // This blocks repeating a one-use action after an Android process crash.
        clicked = true
        Store.markClaim(context, url, "clicking", "正在提交一次领取操作；不会自动重复提交")
        val web = view ?: run { finish(url, "uncertain", "领取操作前网页丢失，请人工核对"); return }
        web.evaluateJavascript(CLICK_JS) { raw ->
            if (generation != id || finished) return@evaluateJavascript
            if (!raw.contains("CLICKED")) {
                finish(url, "uncertain", "网页按钮在点击前发生变化，请在 App 内核对")
                return@evaluateJavascript
            }
            Store.markClicked(context, url, "已点击领取按钮，正在等待科雷网页反馈")
            for (delay in listOf(1800L, 4000L, 7000L, 11000L)) {
                handler.postDelayed({ checkAfterClick(id, url) }, delay)
            }
            handler.postDelayed({
                if (generation == id && !finished)
                    finish(url, "uncertain", "已执行一次领取点击，但科雷未返回可确认的成功结果")
            }, 15500L)
        }
    }

    private fun checkAfterClick(id: Int, url: String) {
        if (generation != id || finished) return
        val web = view ?: return
        web.evaluateJavascript(INSPECT_JS) { raw ->
            if (generation != id || finished) return@evaluateJavascript
            when {
                raw.contains("ALREADY") -> finish(url, "confirmed", "科雷网页显示已领取（请核实账号）")
                raw.contains("UNAVAILABLE") -> finish(url, "uncertain", "点击后礼物显示不可领取；请核实是否领取成功")
                // The first click can trigger a redirect to a login page.
                raw.contains("LOGIN") -> finish(url, "needs_login", "领取时需要重新登录科雷，请在 App 内处理")
            }
        }
    }

    private fun finish(url: String, state: String, result: String) {
        if (finished) return
        finished = true
        Store.markClaim(context, url, state, result)
        onUpdate(url, result)
        current = null
        // Reuse the WebView for the next link while preserving the shared cookie store.
        handler.postDelayed({ if (current == null) next() }, 500L)
    }

    fun destroy() {
        ++generation
        finished = true
        current = null
        queue.clear()
        handler.removeCallbacksAndMessages(null)
        view?.stopLoading()
        view?.destroy()
        view = null
    }

    companion object {
        private const val JS_BASE = """
            var text=((document.body && document.body.innerText) || '').toLowerCase();
            if (/successfully claimed|claimed successfully|you have claimed|reward claimed|领取成功|已成功领取|兑换成功/.test(text)) return 'ALREADY';
            if (/already claimed|已领取|已经领取/.test(text)) return 'ALREADY';
            if (/expired|no longer available|sold out|out of stock|gift has been claimed|已失效|已领完|已过期/.test(text)) return 'UNAVAILABLE';
            if (/please log in|sign in to|login to|请先登录|需要登录|登录科雷账号/.test(text)) return 'LOGIN';
            var buttons=Array.from(document.querySelectorAll('button, input[type=submit], a[role=button], a'));
            var b=buttons.find(function(x){
                var label=(x.innerText || x.value || x.getAttribute('aria-label') || '').trim();
                var style=window.getComputedStyle(x);
                return /^(claim|claim reward|claim item|claim your reward|claim your gift|redeem|redeem reward|领取|立即领取|领取奖励|兑换|获取奖励)$/i.test(label)
                && !x.disabled && style.display!=='none' && style.visibility!=='hidden'
                && x.getBoundingClientRect().width>0 && x.getBoundingClientRect().height>0;
            });
        """
        private val INSPECT_JS = "(function(){ $JS_BASE if (b) return 'READY'; return 'WAIT'; })()"
        private val CLICK_JS = "(function(){ $JS_BASE if(b){b.click();return 'CLICKED';} return 'NO_BUTTON'; })()"
    }
}
