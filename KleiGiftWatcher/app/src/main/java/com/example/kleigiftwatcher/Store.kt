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
    fun active(ctx: Context): Boolean = ctx.getSharedPreferences(NAME, 0).getBoolean("active", false)
    fun lastStatus(ctx: Context): String = ctx.getSharedPreferences(NAME, 0).getString("status", "尚未检查") ?: "尚未检查"

    fun configure(ctx: Context, uid: String, webSeconds: Int, apiSeconds: Int, auto: Boolean) {
        ctx.getSharedPreferences(NAME, 0).edit()
            .putString("uid", uid)
            .putInt("web_seconds", webSeconds.coerceIn(10, 3600))
            .putInt("api_seconds", apiSeconds.coerceIn(30, 90))
            .putBoolean("auto_claim", auto)
            .apply()
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
            .put("updated", System.currentTimeMillis())
        data.put(item)
        save(ctx, data)
        true
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
