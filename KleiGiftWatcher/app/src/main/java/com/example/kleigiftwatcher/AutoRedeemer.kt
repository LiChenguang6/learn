package com.example.kleigiftwatcher

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.ArrayDeque

/**
 * Klei in-app WebView redemption queue.
 *
 * Every distinct link receives its own persisted state and retry schedule.
 * Slow/failing pages are retried indefinitely while monitoring is active,
 * with per-link backoff and queue fairness. After a possible claim click we
 * NEVER retry the button automatically because the request is not idempotent.
 */
class AutoRedeemer(
    private val context: Context,
    private val onUpdate: (String, String) -> Unit,
    private val onActive: () -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private val attempts = HashMap<String, Int>()
    private val eligibleAt = HashMap<String, Long>()
    private var web: WebView? = null
    private var current: String? = null
    private var jobId = 0
    private var loadId = 0
    private var pageId = 0
    private var finished = true
    private var clicked = false
    private var polls = 0
    private var startedAt = 0L
    private var loginPolls = 0
    private var lastPageState = ""
    private var loginSince = 0L
    private var loginPage = ""
    private val pageScript by lazy { context.assets.open("claim-page.js").bufferedReader().use { it.readText() } }
    private fun script(click: Boolean = false): String {
        val expected = org.json.JSONObject.quote(Uri.parse(current.orEmpty()).encodedPath.orEmpty())
        return "window.__kleiDoClick = $click; window.__kleiExpectedPath = $expected;\n$pageScript"
    }
    private fun trace(url: String, stage: String, detail: String) {
        ClaimDiagnostics.event(context, url, stage, detail, web?.url)
    }

    fun retryUnsubmitted(url: String) {
        if (Store.claimState(context, url) in listOf("clicked", "clicking", "confirmed", "uncertain")) return
        if (current == url || queue.contains(url)) return
        Store.markClaim(context, url, "pending", "用户请求重试尚未提交的领取")
        trace(url, "manual_retry", "重置未提交任务")
        enqueue(url)
    }

    fun enqueue(url: String) { enqueueAll(listOf(url)) }

    /** Enqueue ALL unique links from a dynamic as a batch, in source order. */
    fun enqueueAll(rawLinks: List<String>) {
        var added = 0
        for (raw in rawLinks) {
            val url = Store.verifiedUrl(raw) ?: continue
            val state = Store.claimState(context, url)
            if (state in listOf("clicked", "clicking", "confirmed", "uncertain", "blocked", "expired", "taken", "needs_login"))
                continue
            if (current == url || queue.contains(url)) continue
            queue.addLast(url)
            added++
        }
        if (added == 0) return
        Store.event(context, "加入 $added 个独立领取链接，队列现有 ${queue.size} 个")
        if (current == null) next()
        else if (!clicked && SystemClock.elapsedRealtime() - startedAt >= 12000L) {
            // Newly published gifts should not wait behind a stalled old page.
            val stalled = current ?: return
            Store.markClaim(context, stalled, "pending", "为新发现的多个礼物让出浏览器，稍后重试")
            eligibleAt[stalled] = SystemClock.elapsedRealtime() + 4000L
            queue.addLast(stalled)
            ++jobId
            ++loadId
            current = null
            finished = true
            next()
        }
    }

    private fun next() {
        if (current != null || queue.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        var waitMs = Long.MAX_VALUE
        val count = queue.size
        for (i in 0 until count) {
            val url = queue.pollFirst() ?: break
            if (Store.claimState(context, url) in listOf(
                    "clicked", "clicking", "confirmed", "uncertain", "blocked", "expired", "taken", "needs_login")) continue
            val remaining = (eligibleAt[url] ?: 0L) - now
            if (remaining > 0) {
                queue.addLast(url)
                waitMs = minOf(waitMs, remaining)
                continue
            }
            current = url
            ++jobId
            finished = false
            clicked = false
            polls = 0
            beginNavigation(url)
            return
        }
        if (queue.isNotEmpty() && waitMs != Long.MAX_VALUE)
            handler.postDelayed({ if (current == null) next() }, waitMs.coerceAtLeast(1000L))
    }

    private fun beginNavigation(url: String) {
        if (current != url || finished || clicked) return
        val job = jobId
        val load = ++loadId
        val attempt = (attempts[url] ?: 0) + 1
        attempts[url] = attempt
        startedAt = SystemClock.elapsedRealtime()
        polls = 0
        loginPolls = 0
        loginSince = 0L
        loginPage = ""
        lastPageState = ""
        trace(url, "navigation_start", "attempt=$attempt")
        if (attempt == 1) trace(url, "runtime", "Android=${android.os.Build.VERSION.SDK_INT}; WebView=${WebView.getCurrentWebViewPackage()?.versionName ?: "unknown"}")
        Store.markClaim(context, url, "loading", "科雷页面第 $attempt 次加载，尚未点击领取")
        onActive()
        try {
            val view = obtainWebView()
            view.stopLoading()
            view.loadUrl(url)
            // This timeout only ends the current page load, NOT the gift task.
            handler.postDelayed({
                if (job == jobId && load == loadId && current == url && !finished && !clicked)
                    retryLater(url, "单次网页加载超过 35 秒")
            }, 35000L)
        } catch (e: Exception) {
            retryLater(url, "科雷网页加载异常：${e.javaClass.simpleName}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun obtainWebView(): WebView {
        return web ?: WebView(context).also { view ->
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.databaseEnabled = true
            view.settings.setSupportMultipleWindows(false)
            view.settings.javaScriptCanOpenWindowsAutomatically = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(w: WebView, pageUrl: String, favicon: android.graphics.Bitmap?) {
                    if (w !== web || finished) return
                    pageId++
                    loginPolls = 0
                    polls = 0
                    current?.let { trace(it, "page_started", "page=$pageId") }
                }
                override fun onPageFinished(w: WebView, pageUrl: String) {
                    if (w !== web || finished) return
                    val url = current ?: return
                    // Ignore late events from the previous gift's navigation.
                    if (isOtherGift(pageUrl, url)) return
                    CookieManager.getInstance().flush()
                    val job = jobId
                    if (clicked) {
                        handler.postDelayed({ checkAfterClick(job, url) }, 900L)
                        return
                    }
                    trace(url, "page_finished", "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                    // Redirects/challenges are inspected as intermediate states, never proof of expiry.
                    val page = pageId
                    handler.postDelayed({ inspect(job, url, page) }, 900L)
                }

                override fun onReceivedError(w: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (w !== web || !request.isForMainFrame || finished) return
                    val url = current ?: return
                    if (isOtherGift(request.url.toString(), url)) return
                    if (clicked) terminal(url, "uncertain", "领取点击后网络异常，结果需要核实")
                    else retryLater(url, "科雷网络错误：${error.description.toString().take(65)}")
                }

                override fun onReceivedHttpError(w: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (w !== web || !request.isForMainFrame || finished) return
                    val url = current ?: return
                    if (isOtherGift(request.url.toString(), url)) return
                    val status = response.statusCode
                    trace(url, "http_error", "HTTP $status")
                    when {
                        clicked -> terminal(url, "uncertain", "点击领取后返回 HTTP $status，结果待核实")
                        status == 403 || status == 429 -> terminal(url, "blocked", "科雷 HTTP $status：访问限制，停止自动重试")
                        status == 404 || status == 410 -> terminal(url, "expired", "科雷 HTTP $status：链接不存在或失效")
                        status >= 400 -> retryLater(url, "科雷 HTTP $status")
                    }
                }

                override fun onRenderProcessGone(w: WebView, detail: RenderProcessGoneDetail): Boolean {
                    val url = current
                    try { w.destroy() } catch (_: Exception) {}
                    if (web === w) web = null
                    if (url != null && !finished) {
                        if (clicked) terminal(url, "uncertain", "领取后网页渲染进程终止，结果待核实")
                        else retryLater(url, "Android 终止了领奖页面渲染进程")
                    }
                    return true
                }
            }
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1800, android.view.View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, 1080, 1800)
            web = view
        }
    }

    private fun isOtherGift(pageUrl: String, expected: String): Boolean {
        val got = Uri.parse(pageUrl)
        val want = Uri.parse(expected)
        val path = got.path.orEmpty()
        return (path.startsWith("/creatorlink/") || path.startsWith("/link/")) &&
            (got.host != want.host || path != want.path)
    }

    private fun retryLater(url: String, reason: String) {
        if (current != url || finished) return
        if (clicked) {
            terminal(url, "uncertain", "$reason；已进入提交阶段，不会自动重复领取")
            return
        }
        val attempt = attempts[url] ?: 1
        trace(url, "retry", "$reason; attempt=$attempt")
        if (attempt == 1 || attempt % 5 == 0) ClaimDiagnostics.capture(context, web, url, "retry")
        val seconds = when {
            attempt <= 1 -> 4L
            attempt == 2 -> 8L
            attempt == 3 -> 15L
            attempt == 4 -> 30L
            else -> 60L
        }
        Store.markClaim(context, url, "pending", "$reason；未执行领取，$seconds 秒后继续尝试")
        eligibleAt[url] = SystemClock.elapsedRealtime() + seconds * 1000L
        if (!queue.contains(url)) queue.addLast(url)
        ++jobId
        ++loadId
        current = null
        finished = true
        // Let all other links run immediately rather than waiting for this link.
        handler.post { next() }
    }

    private fun inspect(job: Int, url: String, page: Int) {
        if (page != pageId || job != jobId || current != url || finished || clicked) return
        val view = web ?: run { retryLater(url, "网页实例丢失"); return }
        if (++polls > 14) {
            retryLater(url, "页面尚未出现领取按钮或明确状态")
            return
        }
        view.evaluateJavascript(script()) { raw ->
            if (page != pageId || job != jobId || current != url || finished || clicked) return@evaluateJavascript
            if (raw != lastPageState) {
                lastPageState = raw
                trace(url, "page_state", raw)
            }
            if (raw.contains("LOGIN")) {
                if (loginPolls == 0 || loginPage != view.url.orEmpty()) {
                    loginSince = SystemClock.elapsedRealtime()
                    loginPage = view.url.orEmpty()
                    loginPolls = 0
                }
                loginPolls++
            } else loginPolls = 0
            when {
                raw.contains("TAKEN") -> terminal(url, "taken", "科雷页面明确显示已被其他人领取")
                raw.contains("ALREADY") -> terminal(url, "confirmed", "科雷网页显示本账号已领取（请核对奖励记录）")
                raw.contains("UNAVAILABLE") -> terminal(url, "expired", "科雷网页显示礼物失效或已领完")
                raw.contains("LOGIN") && loginPolls >= 3 && SystemClock.elapsedRealtime() - loginSince >= 3500L -> terminal(url, "needs_login", "连续检查发现登录表单或登录要求，请在 App 内完成认证")
                raw.contains("READY") -> clickOnce(job, url)
                else -> handler.postDelayed({ inspect(job, url, page) }, 1300L)
            }
        }
    }

    private fun clickOnce(job: Int, url: String) {
        if (job != jobId || current != url || finished || clicked) return
        val view = web ?: run { retryLater(url, "点击前网页实例丢失"); return }
        // Safeguard against a process crash after dispatch: persist first.
        clicked = true
        Store.markClaim(context, url, "clicking", "准备提交一次领取；不允许重复点击")
        trace(url, "click_dispatch", "已持久化提交保护，即将执行领取脚本")
        view.evaluateJavascript(script(true)) { raw ->
            if (job != jobId || current != url || finished) return@evaluateJavascript
            trace(url, "claim_response", raw)
            when {
                raw.contains("CLICKED") -> {
                    Store.markClicked(context, url, "已点击领取，等待确认")
                    for (delay in listOf(1800L, 3500L, 6500L, 10000L))
                        handler.postDelayed({ checkAfterClick(job, url) }, delay)
                    handler.postDelayed({
                        if (job == jobId && current == url && !finished)
                            terminal(url, "uncertain", "领取按钮已点击，但未获得明确成功反馈")
                    }, 14500L)
                }
                raw.contains("NO_BUTTON") -> {
                    // JS explicitly reports NO click; this is safe to retry.
                    clicked = false
                    retryLater(url, "按钮在点击前消失；未执行领取")
                }
                raw.contains("TAKEN") -> terminal(url, "taken", "点击前礼物已被其他人领取，未提交")
                raw.contains("ALREADY") -> terminal(url, "confirmed", "点击前网页已显示领取成功")
                raw.contains("UNAVAILABLE") -> terminal(url, "expired", "点击前礼物已失效")
                raw.contains("LOGIN") || raw.contains("BUSY") || raw.contains("INTERMEDIATE") || raw.contains("WAIT") -> {
                    clicked = false // Script explicitly returned before b.click().
                    retryLater(url, "点击前页面状态改变：$raw；未执行点击")
                }
                else -> terminal(url, "uncertain", "领取脚本没有可靠返回值，无法确认是否提交")
            }
        }
        handler.postDelayed({
            if (job == jobId && current == url && !finished &&
                Store.claimState(context, url) == "clicking")
                terminal(url, "uncertain", "领奖脚本未返回，不能安全重复提交")
        }, 11000L)
    }

    private fun checkAfterClick(job: Int, url: String) {
        if (job != jobId || current != url || finished) return
        val view = web ?: return
        view.evaluateJavascript(script()) { raw ->
            if (job != jobId || current != url || finished) return@evaluateJavascript
            trace(url, "claim_response", raw)
            when {
                raw.contains("ALREADY") -> terminal(url, "confirmed", "科雷页面显示已领取（请核实账号）")
                raw.contains("TAKEN") -> terminal(url, "uncertain", "点击后页面显示被其他人领取，请核对奖励记录")
                raw.contains("UNAVAILABLE") -> terminal(url, "uncertain", "点击后礼物显示不可领取，结果待核实")
                raw.contains("LOGIN") -> terminal(url, "uncertain", "提交后出现登录要求，不能确认领取结果，请人工核对")
            }
        }
    }

    private fun terminal(url: String, state: String, result: String) {
        if (current != url || finished) return
        trace(url, "terminal", "$state: $result; elapsedMs=${SystemClock.elapsedRealtime() - startedAt}; submissionGuard=$clicked")
        ClaimDiagnostics.capture(context, web, url, state, state == "needs_login")
        finished = true
        attempts.remove(url)
        eligibleAt.remove(url)
        Store.markClaim(context, url, state, result)
        onUpdate(url, result)
        current = null
        handler.postDelayed({ next() }, 350L)
    }

    fun destroy() {
        ++jobId
        ++loadId
        finished = true
        clicked = false
        current = null
        queue.clear()
        attempts.clear()
        eligibleAt.clear()
        handler.removeCallbacksAndMessages(null)
        web?.stopLoading()
        web?.destroy()
        web = null
    }

}
