package com.maherco.kicktv

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONArray

/**
 * Holds the Kick account session. It can come from the session injected at build
 * time (assets/kick_session.json) or from a cookie export the user imports from
 * inside the app, which is stored in shared preferences and wins over the asset.
 */
object SessionStore {

    private const val ASSET = "kick_session.json"
    private const val PREFS = "kicktv_prefs"
    private const val KEY = "session_json"
    private val HOSTS = listOf("kick.com", "www.kick.com", "player.kick.com")

    @Volatile
    private var header: String = ""

    @Volatile
    private var token: String = ""

    @Volatile
    private var loaded: Boolean = false

    fun cookieHeader(): String = header

    /** value of the session_token cookie, used as the bearer token */
    fun sessionToken(): String = token

    fun hasSession(): Boolean = header.isNotBlank()

    fun apply(context: Context) {
        if (loaded) return
        loaded = true
        val raw = stored(context) ?: bundled(context)
        if (raw.isNullOrBlank()) return
        install(raw)
    }

    /** Pushes the session into the cookie jar again (safe to call any time). */
    fun reapply(context: Context) {
        val raw = stored(context) ?: bundled(context)
        if (raw.isNullOrBlank()) return
        install(raw)
    }

    /**
     * Installs a cookie export the user pasted in the app (JSON array, the same
     * shape a browser "export cookies" extension writes) and remembers it.
     */
    fun importJson(context: Context, raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.length < 2 || !trimmed.startsWith("[")) return false
        if (!install(trimmed)) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, trimmed).apply()
        return true
    }

    private fun stored(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    private fun bundled(context: Context): String? = try {
        context.assets.open(ASSET).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }

    private fun install(raw: String): Boolean {
        try {
            val arr = JSONArray(raw)
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            val sb = StringBuilder()
            var found = false
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val value = o.optString("value")
                if (name.isBlank() || value.isBlank()) continue
                found = true
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
            if (!found) return false
            header = sb.toString()
            cm.flush()
            return true
        } catch (_: Exception) {
            return false
        }
    }
}
