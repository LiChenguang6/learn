package com.example.kleigiftwatcher

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Store {
    private val lock = Any()
    private const val NAME = "klei_settings"
    private const val HISTORY = "history"
    private const val LIMIT = 150

    fun uid(ctx: Context): String = ctx.getSharedPreferences(NAME, 0).getString("uid", "12875864") ?: "12875864"
    fun seconds(ctx: Context): Int = ctx.getSharedPreferences(NAME, 0).getInt("web_seconds", 15).coerceIn(10, 3600)
    fun apiSeconds(ctx: Context): Int = ctx.getSharedPreferences(NAME, 0).getInt("api_seconds", 60).coerceIn(30, 90)
    fun autoClaim(ctx: Context): Boolean = ctx.getSharedPreferences(NAME, 0).getBoolean("auto_claim", true)
    fun keepAwake(ctx: Context): Boolean = ctx.getSharedPreferences(NAME, 0).getBoolean("keep_awake", false)
    fun resumeOnBoot(ctx: Context): Boolean = ctx.getSharedPreferences(NAME, 0).getBoolean("resume_boot", true)
    fun desired(ctx: Context): Boolean = ctx.getSharedPreferences(NAME, 0).getBoolean("desired", false)
    fun setDesired(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(NAME, 0).edit().putBoolean("desired", enabled).commit()
    }
    fun active(ctx: Context): Boolean = ctx.getSharedPreferences(NAME, 0).getBoolean("active", false)
    fun lastStatus(ctx: Context): String = ctx.getSharedPreferences(NAME, 0).getString("status", "尚未检查") ?: "尚未检查"

    fun configure(ctx: Context, uid: String, webSeconds: Int, apiSeconds: Int, auto: Boolean, keepAwake: Boolean, boot: Boolean) {
        ctx.getSharedPreferences(NAME, 0).edit()
            .putString("uid", uid)
            .putInt("web_seconds", webSeconds.coerceIn(10, 3600))
            .putInt("api_seconds", apiSeconds.coerceIn(30, 90))
            .putBoolean("auto_claim", auto)
            .putBoolean("keep_awake", keepAwake)
            .putBoolean("resume_boot", boot)
            .apply()
    }

    fun latestInfo(ctx: Context): String = ctx.getSharedPreferences(NAME, 0)
        .getString("latest_info", "尚未成功解析动态") ?: "尚未成功解析动态"

    fun diagnostic(ctx: Context): String = ctx.getSharedPreferences(NAME, 0)
        .getString("diagnostic", "尚无检测信息") ?: "尚无检测信息"

    fun events(ctx: Context): List<String> = synchronized(lock) {
        val arr = try { JSONArray(ctx.getSharedPreferences(NAME, 0).getString("events", "[]")) }
            catch (_: Exception) { JSONArray() }
        (arr.length() - 1 downTo 0).take(12).map { arr.optString(it) }
    }

    fun event(ctx: Context, message: String) = synchronized(lock) {
        val prefs = ctx.getSharedPreferences(NAME, 0)
        val arr = try { JSONArray(prefs.getString("events", "[]")) } catch (_: Exception) { JSONArray() }
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        arr.put("$stamp · ${message.take(150)}")
        val limited = JSONArray()
        for (i in (arr.length() - 80).coerceAtLeast(0) until arr.length()) limited.put(arr.optString(i))
        prefs.edit().putString("events", limited.toString()).apply()
    }

    fun monitorResult(ctx: Context, method: String, latestId: String, pubTime: String, cards: Int, gifts: Int) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val info = "最近成功检测：$stamp\n通道：$method · 动态数：$cards · 礼物链接：$gifts\n最新动态 ID：${latestId.ifBlank { "无法解析" }}\n发布时间：${pubTime.ifBlank { "无法解析" }}"
        ctx.getSharedPreferences(NAME, 0).edit().putString("latest_info", info)
            .putString("diagnostic", "正常 · $method · $stamp").apply()
        event(ctx, "$method 成功：动态 $cards 条 / 礼物 $gifts 条，最新 ID ${latestId.ifBlank { "未知" }}")
    }

    fun monitorError(ctx: Context, method: String, detail: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        ctx.getSharedPreferences(NAME, 0).edit()
            .putString("diagnostic", "$stamp · $method：${detail.take(140)}").apply()
        event(ctx, "$method：$detail")
    }

    fun setActive(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(NAME, 0).edit().putBoolean("active", value).apply()
    }

    fun status(ctx: Context, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        ctx.getSharedPreferences(NAME, 0).edit().putString("status", "$time · $message").apply()
    }

    // Only allow actual Klei rewards links, never arbitrary URLs from a Bilibili post.
    fun verifiedUrl(input: String): String? {
        return try {
            val uri = Uri.parse(input.trim().trimEnd('.', ',', ';', ')', ']', '。', '，'))
            val path = uri.encodedPath ?: ""
            if (uri.scheme != "https" || uri.host?.lowercase(Locale.ROOT) != "accounts.klei.com") null
            else if (!(path.startsWith("/creatorlink/") || path.startsWith("/link/"))) null
            else if (path.substringAfterLast('/').isBlank()) null
            else uri.buildUpon().fragment(null).build().toString()
        } catch (_: Exception) { null }
    }

    fun addNew(ctx: Context, rawUrl: String, source: String): Boolean = synchronized(lock) {
        val url = verifiedUrl(rawUrl) ?: return@synchronized false
        val data = history(ctx)
        for (i in 0 until data.length()) if (data.optJSONObject(i)?.optString("url") == url) return@synchronized false
        val item = JSONObject()
            .put("url", url)
            .put("source", source)
            .put("status", "发现新链接")
            .put("claim_state", "pending")
            .put("updated", System.currentTimeMillis())
        data.put(item)
        ClaimDiagnostics.event(ctx, url, "discovered", "发现礼物链接")
        save(ctx, data)
        true
    }

    // Claim states survive a killed process. Never replay a link whose claim
    // button may already have been clicked: one-time rewards are not idempotent.
    fun markClaim(ctx: Context, rawUrl: String, state: String, message: String) = synchronized(lock) {
        val url = verifiedUrl(rawUrl) ?: return@synchronized
        val items = history(ctx)
        var row: JSONObject? = null
        for (i in 0 until items.length()) {
            val entry = items.optJSONObject(i) ?: continue
            if (entry.optString("url") == url) { row = entry; break }
        }
        if (row == null) {
            row = JSONObject().put("url", url).put("source", "恢复任务")
            items.put(row)
        }
        row.put("claim_state", state).put("status", message)
            .put("updated", System.currentTimeMillis())
        save(ctx, items)
        event(ctx, "领取状态：$state · ${message.take(75)}")
    }

    fun claimState(ctx: Context, rawUrl: String): String = synchronized(lock) {
        val url = verifiedUrl(rawUrl) ?: return@synchronized ""
        val items = history(ctx)
        for (i in 0 until items.length()) {
            val row = items.optJSONObject(i) ?: continue
            if (row.optString("url") == url) return@synchronized row.optString("claim_state")
        }
        ""
    }

    // Only unclicked jobs can be recovered automatically after a service restart.
    fun pendingClaims(ctx: Context): List<String> = synchronized(lock) {
        val items = history(ctx)
        val result = ArrayList<String>()
        for (i in (items.length() - 1) downTo 0) {
            val row = items.optJSONObject(i) ?: continue
            val state = row.optString("claim_state")
            if (state == "pending" || state == "loading") {
                verifiedUrl(row.optString("url"))?.let { result.add(it) }
            }
            if (result.size >= 150) break
        }
        result
    }

    fun markClicked(ctx: Context, rawUrl: String, message: String) {
        markClaim(ctx, rawUrl, "clicked", message)
        // Persistent commit is completed in markClaim before another navigation.
    }

    fun update(ctx: Context, rawUrl: String, status: String) = synchronized(lock) {
        val url = verifiedUrl(rawUrl) ?: return@synchronized
        val data = history(ctx)
        for (i in 0 until data.length()) {
            val row = data.optJSONObject(i) ?: continue
            if (row.optString("url") == url) {
                row.put("status", status).put("updated", System.currentTimeMillis())
                save(ctx, data)
                return@synchronized
            }
        }
        val row = JSONObject().put("url", url).put("source", "手动添加")
            .put("status", status).put("updated", System.currentTimeMillis())
        data.put(row)
        save(ctx, data)
    }

    fun rows(ctx: Context): List<JSONObject> = synchronized(lock) {
        val data = history(ctx)
        (data.length() - 1 downTo 0).mapNotNull { data.optJSONObject(it) }
    }

    private fun history(ctx: Context): JSONArray = try {
        JSONArray(ctx.getSharedPreferences(NAME, 0).getString(HISTORY, "[]"))
    } catch (_: Exception) { JSONArray() }

    private fun save(ctx: Context, data: JSONArray) {
        val trimmed = JSONArray()
        for (i in (data.length() - LIMIT).coerceAtLeast(0) until data.length()) trimmed.put(data.get(i))
        // commit to avoid racing with notifications/service lifecycle.
        ctx.getSharedPreferences(NAME, 0).edit().putString(HISTORY, trimmed.toString()).commit()
    }
}
