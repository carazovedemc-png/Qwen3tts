package com.qwen.tts.android.server

import android.content.Context
import java.security.SecureRandom

class ServerPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("api_server", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt("port", 8765)
        set(v) = prefs.edit().putInt("port", v.coerceIn(1024, 65535)).apply()

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** Address the server binds to: 127.0.0.1 = this phone only, 0.0.0.0 = whole Wi-Fi network, or a custom IP. */
    var bindHost: String
        get() = prefs.getString("host", "127.0.0.1") ?: "127.0.0.1"
        set(v) = prefs.edit().putString("host", v.trim().ifBlank { "127.0.0.1" }).apply()

    var lanAccess: Boolean
        get() = bindHost != "127.0.0.1" && bindHost != "localhost"
        set(v) { bindHost = if (v) "0.0.0.0" else "127.0.0.1" }

    var defaultVoice: String
        get() = prefs.getString("default_voice", "vivian") ?: "vivian"
        set(v) = prefs.edit().putString("default_voice", v).apply()

    /** Codec language id, -1 = auto-detect from text. */
    var defaultLanguageId: Int
        get() = prefs.getInt("default_lang", -1)
        set(v) = prefs.edit().putInt("default_lang", v).apply()

    var apiKey: String
        get() = prefs.getString("api_key", null) ?: regenerateKey()
        set(v) = prefs.edit().putString("api_key", v.trim().ifBlank { regenerateKey() }).apply()

    fun regenerateKey(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = "qwen-" + bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("api_key", key).apply()
        return key
    }
}
