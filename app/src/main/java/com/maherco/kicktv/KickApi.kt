package com.maherco.kicktv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

data class ChannelInfo(
    val slug: String,
    val title: String,
    val isLive: Boolean,
    val playbackUrl: String?,
    val viewers: Int,
    val id: Int
)

object KickApi {

    /**
     * Kick's website API refuses a full browser User-Agent sent by a non-browser
     * client, so API calls use the plain one. The video engine keeps a normal
     * player User-Agent.
     */
    private const val API_UA = "Mozilla/5.0"

    private const val PLAYER_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    fun userAgent(): String = PLAYER_UA

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
                val id = o.optInt("id", 0)
                if (playback != null || isLive) {
                    return ChannelInfo(clean, title, isLive, playback, viewers, id)
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * true  -> Kick accepted the bundled session
     * false -> rejected, or the check could not run
     */
    fun checkSession(channelId: Int): Boolean {
        if (channelId <= 0) return false
        val body = get("https://kick.com/api/v2/channels/$channelId/me") ?: return false
        return body.trimStart().startsWith("{")
    }

    private fun bearer(): String {
        val raw = SessionStore.sessionToken()
        if (raw.isBlank()) return ""
        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            raw
        }
    }

    private fun get(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", API_UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Referer", "https://kick.com/")
                setRequestProperty("Origin", "https://kick.com")
                setRequestProperty("x-app-platform", "web")
                val token = bearer()
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                val cookie = SessionStore.cookieHeader()
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            }
            if (conn.responseCode !in 200..299) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
