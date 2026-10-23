package com.example.kleigiftwatcher

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.regex.Pattern

class BiliScanner(private val context: Context) {
    data class Gift(val url: String, val source: String)

    // Matches the literal URL also when surrounded by rich-text markup.
    private val linkPattern = Pattern.compile("https?://accounts\\.klei\\.com/(?:creatorlink|link)/[^\\s\\\"'<>\\]\\[(){}]+", Pattern.CASE_INSENSITIVE)
    private val encodedPattern = Pattern.compile("https?%3A%2F%2Faccounts\\.klei\\.com%2F(?:creatorlink|link)%2F[^\\s\\\"'<>\\]\\[(){}]+", Pattern.CASE_INSENSITIVE)

    fun scan(uid: String): List<Gift> {
        require(uid.matches(Regex("[0-9]{1,20}"))) { "B站 UID 必须是数字" }
        val root = getJson("https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?host_mid=${URLEncoder.encode(uid, "UTF-8")}&features=itemOpusStyle")
        val items = root.optJSONObject("data")?.optJSONArray("items")
            ?: throw IllegalStateException("B站动态列表为空或接口已发生变化")
        val found = LinkedHashMap<String, Gift>()
        val total = items.length().coerceAtMost(15)
        for (i in 0 until total) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id_str")
            val source = if (id.matches(Regex("[0-9]+"))) "https://www.bilibili.com/opus/$id" else "https://space.bilibili.com/$uid/dynamic"
            val links = linkedSetOf<String>()
            findLinks(item, links)
            // Some articles contain the URL only in the full opus body.
            // Limit detail requests to three latest text/image posts to reduce Bilibili rate limits.
            val type = item.optString("type").uppercase()
            if (links.isEmpty() && i < 3 && id.matches(Regex("[0-9]+")) &&
                (type.contains("DRAW") || type.contains("ARTICLE") || type.contains("WORD"))) {
                try {
                    val detail = getJson("https://api.bilibili.com/x/polymer/web-dynamic/v1/opus/detail?id=$id&features=htmlNewStyle")
                    findLinks(detail.optJSONObject("data"), links)
                } catch (_: Exception) { /* feed still useful even if detail blocked */ }
            }
            for (link in links) found[link] = Gift(link, source)
        }
        return found.values.toList()
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 9000
            connection.readTimeout = 9000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
            connection.setRequestProperty("Referer", "https://space.bilibili.com/")
            connection.setRequestProperty("Accept", "application/json")
            val cookie = try {
                CookieManager.getInstance().getCookie("https://www.bilibili.com")
            } catch (_: Exception) { null }
            if (!cookie.isNullOrBlank()) connection.setRequestProperty("Cookie", cookie)
            val code = connection.responseCode
            if (code != 200) throw IllegalStateException("B站 HTTP $code")
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size > 2_000_000) throw IllegalStateException("B站接口响应过大")
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            if (json.optInt("code", Int.MIN_VALUE) != 0) {
                throw IllegalStateException("B站 API ${json.optInt("code")}: ${json.optString("message")}")
            }
            return json
        } finally { connection.disconnect() }
    }

    private fun findLinks(node: Any?, result: MutableSet<String>, depth: Int = 0) {
        if (node == null || depth > 14 || result.size >= 30) return
        when (node) {
            is JSONObject -> {
                for (key in node.keys()) findLinks(node.opt(key), result, depth + 1)
            }
            is JSONArray -> {
                for (i in 0 until node.length().coerceAtMost(150)) findLinks(node.opt(i), result, depth + 1)
            }
            is String -> {
                val variants = listOf(node, node.replace("&amp;", "&"),
                    try { java.net.URLDecoder.decode(node, "UTF-8") } catch (_: Exception) { node })
                for (s in variants) {
                    val matcher = linkPattern.matcher(s)
                    while (matcher.find()) Store.verifiedUrl(matcher.group())?.let { result.add(it) }
                    val encoded = encodedPattern.matcher(s)
                    while (encoded.find()) {
                        val decoded = try { java.net.URLDecoder.decode(encoded.group(), "UTF-8") } catch (_: Exception) { "" }
                        Store.verifiedUrl(decoded)?.let { result.add(it) }
                    }
                }
            }
        }
    }
}
