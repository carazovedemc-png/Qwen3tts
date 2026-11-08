package com.qwen.tts.android.engine

import android.content.Context
import com.qwen.tts.android.QwenModel
import com.qwen.tts.android.data.MiaSettings
import com.qwen.tts.android.data.SpeakerCatalog
import com.qwen.tts.android.data.db.QwenDatabase
import com.qwen.tts.studio.engine.QwenEngine
import java.io.File
import kotlin.concurrent.withLock
import kotlinx.coroutines.runBlocking

/** One place that turns (text, voice, language) into audio. Used by API, previews and the batch queue. */
object Synth {
    const val SAMPLE_RATE = 24_000

    class Voice(val talker: String, val preset: String?, val embeddingPath: String?, val label: String)

    fun modelDir(context: Context) = File(context.filesDir, "qwen3-tts-models")

    fun isReady(context: Context, talker: String): Boolean {
        val variant = QwenModel.variants.firstOrNull { it.talkerName == talker } ?: return false
        return variant.files.all { f -> File(modelDir(context), f.name).let { it.isFile && it.length() >= f.sizeBytes } }
    }

    /** id may be "preset:vivian", a cloned voiceId, or a plain name/key. Null/blank -> [fallback]. */
    fun resolve(context: Context, id: String?, fallback: String? = null): Voice? {
        val wanted = (id?.takeIf { it.isNotBlank() } ?: fallback)?.trim() ?: return randomVoice()
        SpeakerCatalog.byVoiceId(wanted)?.let { return presetVoice(it.key, it.displayName) }
        SpeakerCatalog.byKey(wanted)?.let { return presetVoice(it.key, it.displayName) }
        val clones = runBlocking { QwenDatabase.getDatabase(context).qwenDao().getVoices() }
        clones.firstOrNull { it.voiceId.equals(wanted, true) || it.name.equals(wanted, true) }?.let {
            return Voice(QwenModel.defaultVariant.talkerName, null, it.speakerEmbeddingPath, it.name)
        }
        return null
    }

    private fun randomVoice() = Voice(QwenModel.defaultVariant.talkerName, null, null, "Random")
    private fun presetVoice(key: String, name: String) = Voice(QwenModel.customVoiceVariant.talkerName, key, null, name)

    fun splitText(text: String, maxChars: Int = 160): List<String> {
        val sentences = Regex("[^。！？.!?；;\\n]+[。！？.!?；;]*").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }
        val chunks = ArrayList<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            if (cur.isNotEmpty() && cur.length + s.length + 1 > maxChars) { chunks += cur.toString(); cur.clear() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
        if (cur.isNotEmpty()) chunks += cur.toString()
        return chunks.ifEmpty { listOf(text) }
    }

    fun params(context: Context, languageId: Int, preset: String?, instruction: String?): QwenEngine.NativeParams {
        val s = MiaSettings.get(context)
        return QwenEngine.NativeParams(
            languageId = languageId,
            instruction = instruction?.trim()?.takeIf { it.isNotEmpty() },
            speaker = preset,
            maxAudioTokens = s.maxAudioTokens,
            temperature = s.temperature,
            topP = s.topP,
            topK = s.topK,
            repetitionPenalty = s.repetition,
            seed = s.effectiveSeed,
        )
    }

    fun backendPreference(context: Context): Int = when (MiaSettings.get(context).backend) {
        1 -> QwenEngine.BACKEND_CPU
        2 -> QwenEngine.BACKEND_GPU
        else -> QwenEngine.BACKEND_AUTO
    }

    fun threads(context: Context): Int = MiaSettings.get(context).threads.takeIf { it > 0 } ?: EngineHost.defaultThreads()

    /** Runs synthesis chunk by chunk. [onChunk] gets (doneChunks, totalChunks). Throws on failure. */
    fun run(
        context: Context,
        text: String,
        voice: Voice,
        languageId: Int,
        instruction: String?,
        onChunk: ((Int, Int) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): FloatArray {
        if (!isReady(context, voice.talker)) error("Model ${voice.talker} is not downloaded")
        val chunks = splitText(text)
        val parts = ArrayList<FloatArray>()
        EngineHost.lock.withLock {
            val native = EngineHost.ensureLoaded(modelDir(context).absolutePath, voice.talker, threads(context), backendPreference(context))
            native.setProgressCallback(null)
            chunks.forEachIndexed { index, chunk ->
                if (isCancelled()) error("Cancelled")
                val r = native.synthesize(
                    text = chunk,
                    speakerEmbeddingPath = voice.embeddingPath,
                    params = params(context, languageId, voice.preset, instruction),
                )
                val audio = r.audio
                if (!r.success || audio == null) error(r.errorMsg ?: "Synthesis failed")
                parts += audio
                parts += FloatArray(SAMPLE_RATE / 8)
                onChunk?.invoke(index + 1, chunks.size)
            }
        }
        val out = FloatArray(parts.sumOf { it.size })
        var pos = 0
        parts.forEach { p -> p.copyInto(out, pos); pos += p.size }
        return out
    }
}
