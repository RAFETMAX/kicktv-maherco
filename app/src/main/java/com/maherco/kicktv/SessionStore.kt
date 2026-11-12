package com.maherco.kicktv

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONArray

/**
 * Holds the account session that is bundled with the app, so the user never
 * has to sign in. The session file is injected at build time and is never
 * committed to the repository.
 */
object SessionStore {

    private const val ASSET = "kick_session.json"
    private val HOSTS = listOf("kick.com", "www.kick.com", "player.kick.com")

    @Volatile
    private var header: String = ""

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var token: String = ""

    fun cookieHeader(): String = header

    fun hasSession(): Boolean = header.isNotBlank()

    /** value of the session_token cookie, used as the bearer token */
    fun sessionToken(): String = token

    fun apply(context: Context) {
        if (loaded) return
        loaded = true

        val raw = try {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
        if (raw.isBlank()) return

        try {
            val arr = JSONArray(raw)
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val value = o.optString("value")
                if (name.isBlank() || value.isBlank()) continue
                val domain = o.optString("domain", "kick.com").removePrefix(".")
                val path = o.optString("path", "/")
                val cookie = "$name=$value; domain=$domain; path=$path"
                for (host in HOSTS) {
                    try {
                        cm.setCookie("https://$host$path", cookie)
                    } catch (_: Exception) {
                    }
                }
                if (name == "session_token") token = value
                if (sb.isNotEmpty()) sb.append("; ")
                sb.append(name).append('=').append(value)
            }
            header = sb.toString()
            cm.flush()
        } catch (_: Exception) {
        }
    }
}
