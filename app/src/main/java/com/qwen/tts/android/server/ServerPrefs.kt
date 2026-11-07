package com.qwen.tts.android.server

import android.content.Context
import java.security.SecureRandom

class ServerPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("api_server", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt("port", 8765)
        set(v) = prefs.edit().putInt("port", v.coerceIn(1024, 65535)).apply()

    /** false = only apps on this phone (127.0.0.1); true = also other devices in the Wi-Fi network. */
    var lanAccess: Boolean
        get() = prefs.getBoolean("lan", false)
        set(v) = prefs.edit().putBoolean("lan", v).apply()

    var defaultVoice: String
        get() = prefs.getString("default_voice", "vivian") ?: "vivian"
        set(v) = prefs.edit().putString("default_voice", v).apply()

    /** Codec language id, -1 = auto-detect from text. */
    var defaultLanguageId: Int
        get() = prefs.getInt("default_lang", -1)
        set(v) = prefs.edit().putInt("default_lang", v).apply()

    val apiKey: String
        get() = prefs.getString("api_key", null) ?: regenerateKey()

    fun regenerateKey(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = "qwen-" + bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("api_key", key).apply()
        return key
    }
}
