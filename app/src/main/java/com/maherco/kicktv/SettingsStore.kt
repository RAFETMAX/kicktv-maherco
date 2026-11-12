package com.maherco.kicktv

import android.content.Context

class SettingsStore(context: Context) {

    private val p = context.getSharedPreferences("kicktv_prefs", Context.MODE_PRIVATE)

    var channelSlug: String
        get() = p.getString("slug", "maherco") ?: "maherco"
        set(v) {
            val clean = v.trim().lowercase().ifBlank { "maherco" }
            p.edit().putString("slug", clean).apply()
        }

    /** 0 = auto, otherwise max video height in pixels */
    var quality: Int
        get() = p.getInt("quality", 0)
        set(v) = p.edit().putInt("quality", v).apply()

    /** live edge target offset in milliseconds (lower = less delay) */
    var latencyMs: Int
        get() {
            val v = p.getInt("latency", 2000)
            return if (v < 2000) 2000 else v
        }
        set(v) = p.edit().putInt("latency", v).apply()

    /** 0 = fit, 1 = fill, 2 = zoom */
    var resizeMode: Int
        get() = p.getInt("resize", 0)
        set(v) = p.edit().putInt("resize", v).apply()

    /** milliseconds before the overlay hides; 0 = never */
    var autoHideMs: Int
        get() = p.getInt("autohide", 4000)
        set(v) = p.edit().putInt("autohide", v).apply()

    /** 0 = native low latency player, 1 = official web player */
    var engine: Int
        get() = p.getInt("engine", 0)
        set(v) = p.edit().putInt("engine", v).apply()

    var kickUsername: String
        get() = p.getString("kick_user", "") ?: ""
        set(v) = p.edit().putString("kick_user", v).apply()

    var lastVolume: Int
        get() = p.getInt("volume", -1)
        set(v) = p.edit().putInt("volume", v).apply()
}
