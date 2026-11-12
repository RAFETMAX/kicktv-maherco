package com.maherco.kicktv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChannelInfo(
    val slug: String,
    val title: String,
    val isLive: Boolean,
    val playbackUrl: String?,
    val viewers: Int
)

object KickApi {

    fun userAgent(): String = UA

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun fetch(slug: String): ChannelInfo? {
        val clean = slug.trim().lowercase()
        if (clean.isEmpty()) return null
        val endpoints = listOf(
            "https://kick.com/api/v2/channels/$clean",
            "https://kick.com/api/v1/channels/$clean"
        )
        for (endpoint in endpoints) {
            val body = get(endpoint) ?: continue
            try {
                val o = JSONObject(body)
                val playback = o.optString("playback_url", "").ifBlank { null }
                val ls = o.optJSONObject("livestream")
                val isLive = ls != null && !ls.isNull("id")
                var title = clean
                var viewers = 0
                if (ls != null) {
                    val t = ls.optString("session_title", "")
                    if (t.isNotBlank()) title = t
                    viewers = ls.optInt("viewer_count", 0)
                }
                val user = o.optJSONObject("user")
                val name = user?.optString("username", "") ?: ""
                if (name.isNotBlank() && title == clean) title = name
                if (playback != null || isLive) {
                    return ChannelInfo(clean, title, isLive, playback, viewers)
                }
            } catch (_: Exception) {
                // try next endpoint
            }
        }
        return null
    }

    private fun get(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Referer", "https://kick.com/")
                setRequestProperty("Origin", "https://kick.com")
                val cookie = SessionStore.cookieHeader()
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            }
            if (conn.responseCode !in 200..299) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }
}
