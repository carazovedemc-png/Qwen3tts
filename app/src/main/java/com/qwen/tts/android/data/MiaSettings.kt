package com.qwen.tts.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlin.reflect.KProperty

/** A Compose-observable value that is written to SharedPreferences on every change. */
class PState<T>(initial: T, private val save: (T) -> Unit) {
    private val state = mutableStateOf(initial)
    operator fun getValue(thisRef: Any?, p: KProperty<*>): T = state.value
    operator fun setValue(thisRef: Any?, p: KProperty<*>, value: T) {
        state.value = value
        save(value)
    }
}

class MiaSettings private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("mia_settings", Context.MODE_PRIVATE)

    private fun str(key: String, def: String) = PState(prefs.getString(key, def) ?: def) { prefs.edit().putString(key, it).apply() }
    private fun bool(key: String, def: Boolean) = PState(prefs.getBoolean(key, def)) { prefs.edit().putBoolean(key, it).apply() }
    private fun int(key: String, def: Int) = PState(prefs.getInt(key, def)) { prefs.edit().putInt(key, it).apply() }
    private fun flt(key: String, def: Float) = PState(prefs.getFloat(key, def)) { prefs.edit().putFloat(key, it).apply() }
    private fun lng(key: String, def: Long) = PState(prefs.getLong(key, def)) { prefs.edit().putLong(key, it).apply() }

    // appearance
    var theme by str("theme", "auto")            // auto | dark | light
    var background by str("background", "space") // space | clean
    var glass by bool("glass", true)
    var onboarded by bool("onboarded", false)

    // generation
    var temperature by flt("temperature", 0.9f)
    var topP by flt("top_p", 1.0f)
    var topK by int("top_k", 50)
    var repetition by flt("repetition", 1.05f)
    var maxSeconds by int("max_seconds", 30)
    var seedFixed by bool("seed_fixed", false)
    var seed by lng("seed", 42L)
    var instruction by str("instruction", "")

    // runtime
    var backend by int("backend", 0)             // 0 auto, 1 cpu, 2 gpu
    var threads by int("threads", 0)             // 0 = auto
    var keepAlive by bool("keep_alive", false)
    var warmStart by bool("warm_start", false)
    var exportFormat by str("export_format", "WAV")

    val maxAudioTokens: Int get() = (maxSeconds * 12.5f).toInt().coerceIn(64, 1024)
    val effectiveSeed: Long get() = if (seedFixed) seed else -1L

    fun resetGeneration() {
        temperature = 0.9f; topP = 1.0f; topK = 50; repetition = 1.05f; maxSeconds = 30; seedFixed = false
    }

    companion object {
        @Volatile private var instance: MiaSettings? = null
        fun get(context: Context): MiaSettings =
            instance ?: synchronized(this) { instance ?: MiaSettings(context).also { instance = it } }
    }
}

data class LanguageEntry(val name: String, val id: Int, val code: String)

object LanguageCatalog {
    val all = listOf(
        LanguageEntry("Русский", 2069, "ru"),
        LanguageEntry("English", 2050, "en"),
        LanguageEntry("中文 Chinese", 2055, "zh"),
        LanguageEntry("日本語 Japanese", 2058, "ja"),
        LanguageEntry("한국어 Korean", 2064, "ko"),
        LanguageEntry("Deutsch", 2053, "de"),
        LanguageEntry("Français", 2061, "fr"),
        LanguageEntry("Español", 2054, "es"),
    )

    fun byId(id: Int) = all.firstOrNull { it.id == id } ?: all.first()

    /** Accepts a name ("русский", "Chinese"), a code ("ru") or a numeric id; "auto" = -1. */
    fun parse(value: String): Int? {
        val v = value.trim().lowercase()
        if (v.isEmpty()) return null
        if (v == "auto" || v == "авто") return -1
        v.toIntOrNull()?.let { return it }
        return all.firstOrNull { it.code == v || it.name.lowercase().split(' ').any { part -> part == v } }?.id
    }
}
