package com.example.kleigiftwatcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Bounded private diagnostics. No cookies, credentials, form values or page HTML. */
object ClaimDiagnostics {
    private val io = Executors.newSingleThreadExecutor()
    fun directory(ctx: Context) = File(ctx.filesDir, "claim-diagnostics").apply { mkdirs() }
    fun safeUrl(raw: String?): String = try {
        val u = Uri.parse(raw ?: "")
        if (u.host == "accounts.klei.com") "https://accounts.klei.com${u.path.orEmpty()}"
        else u.host.orEmpty()
    } catch (_: Exception) { "invalid-url" }

    fun event(ctx: Context, gift: String, stage: String, detail: String, page: String? = null) {
        val app = ctx.applicationContext
        val row = JSONObject().put("time", System.currentTimeMillis())
            .put("at", java.time.Instant.now().toString()).put("gift", safeUrl(gift)).put("stage", stage).put("detail", detail.take(300))
            .put("page", safeUrl(page)).put("app", BuildConfig.VERSION_NAME)
        io.execute {
            try {
                val dir = directory(app)
                val log = File(dir, "events.jsonl")
                if (log.length() > 512 * 1024) {
                    File(dir, "events.previous.jsonl").delete()
                    log.renameTo(File(dir, "events.previous.jsonl"))
                }
                log.appendText(row.toString() + "\n")
            } catch (_: Exception) { /* Diagnostics must never interrupt a claim. */ }
        }
    }

    /** Best effort WebView drawing. Login/challenge providers are never captured. */
    fun capture(ctx: Context, view: WebView?, gift: String, stage: String, login: Boolean = false) {
        if (view == null || login || Store.verifiedUrl(view.url.orEmpty()) == null) {
            event(ctx, gift, "screenshot_skipped", "页面不可用或处于登录/非礼物页面", view?.url)
            return
        }
        val app = ctx.applicationContext
        val page = view.url
        try {
            view.evaluateJavascript("(function(){return document.querySelector('input[type=password],input[autocomplete=one-time-code]') === null;})()") { safe ->
                if (safe != "true" || view.url != page || view.width <= 0 || view.height <= 0) {
                    event(app, gift, "screenshot_skipped", "页面已跳转、包含认证输入框或尚未布局", page)
                    return@evaluateJavascript
                }
                try {
                    val bitmap = Bitmap.createBitmap(540, 900, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    canvas.scale(540f / view.width, 900f / view.height)
                    view.draw(canvas)
                    val name = "${System.currentTimeMillis()}-${stage.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.png"
                    io.execute {
                        try {
                            val dir = directory(app)
                            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            val pictures = dir.listFiles { f -> f.extension == "png" }.orEmpty().sortedByDescending { it.name }
                            var bytes = 0L
                            pictures.forEachIndexed { i, f ->
                                bytes += f.length()
                                if (i >= 20 || bytes > 10 * 1024 * 1024) f.delete()
                            }
                            event(app, gift, "screenshot", name, page)
                        } catch (_: Exception) {
                            event(app, gift, "screenshot_failed", "无法保存截图", page)
                        } finally { bitmap.recycle() }
                    }
                } catch (_: Exception) { event(app, gift, "screenshot_failed", "WebView 绘制失败", page) }
            }
        } catch (_: Exception) { event(app, gift, "screenshot_failed", "页面不可用", page) }
    }

    fun execute(task: () -> Unit) { io.execute(task) }
}
