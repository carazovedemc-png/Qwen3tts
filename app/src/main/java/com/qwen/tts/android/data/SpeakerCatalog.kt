package com.qwen.tts.android.data

import com.qwen.tts.android.data.db.VoiceProfileEntity

enum class Gender(val label: String) { FEMALE("Женский"), MALE("Мужской") }

/** Built-in speaker of the Qwen3-TTS CustomVoice model. */
data class PresetSpeaker(
    val key: String,           // value passed to the native engine (lowercase)
    val displayName: String,
    val gender: Gender,
    val nativeLanguage: String,
    val nativeLanguageId: Int,
    val description: String,
) {
    val voiceId: String get() = SpeakerCatalog.PRESET_PREFIX + key
}

object SpeakerCatalog {
    const val PRESET_PREFIX = "preset:"

    val presets: List<PresetSpeaker> = listOf(
        PresetSpeaker("vivian", "Vivian", Gender.FEMALE, "Chinese", 2055, "Яркий, чуть дерзкий молодой голос"),
        PresetSpeaker("serena", "Serena", Gender.FEMALE, "Chinese", 2055, "Тёплый, мягкий, милый голос"),
        PresetSpeaker("uncle_fu", "Uncle Fu", Gender.MALE, "Chinese", 2055, "Зрелый низкий мужской голос"),
        PresetSpeaker("dylan", "Dylan", Gender.MALE, "Chinese (Beijing)", 2055, "Молодой пекинский акцент"),
        PresetSpeaker("eric", "Eric", Gender.MALE, "Chinese (Sichuan)", 2055, "Живой сычуаньский акцент"),
        PresetSpeaker("ryan", "Ryan", Gender.MALE, "English", 2050, "Динамичный, ритмичный голос"),
        PresetSpeaker("aiden", "Aiden", Gender.MALE, "English", 2050, "Солнечный американский голос"),
        PresetSpeaker("ono_anna", "Ono Anna", Gender.FEMALE, "Japanese", 2058, "Игривый японский голос"),
        PresetSpeaker("sohee", "Sohee", Gender.FEMALE, "Korean", 2064, "Тёплый корейский голос"),
    )

    fun byVoiceId(voiceId: String?): PresetSpeaker? =
        voiceId?.takeIf { it.startsWith(PRESET_PREFIX) }?.let { id -> presets.firstOrNull { it.voiceId == id } }

    fun byKey(key: String?): PresetSpeaker? {
        val k = key?.trim()?.lowercase()?.replace(' ', '_') ?: return null
        return presets.firstOrNull { it.key == k }
    }
}

fun resolveVoiceName(voiceId: String?, voices: List<VoiceProfileEntity>): String =
    SpeakerCatalog.byVoiceId(voiceId)?.displayName
        ?: voices.firstOrNull { it.voiceId == voiceId }?.name
        ?: "Случайный голос"
