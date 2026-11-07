package com.qwen.tts.android.engine

import com.qwen.tts.studio.engine.QwenEngine
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Process-wide owner of the native engine. Shared by the UI (MainViewModel) and the
 * local API server so both can synthesize without loading the model twice.
 * Every native call must happen under [lock] (the native context is not thread-safe).
 */
object EngineHost {
    val lock = ReentrantLock()

    @Volatile var engine: QwenEngine? = null
    @Volatile var loadedTalker: String? = null

    fun ensureLoaded(modelDir: String, talkerName: String, threads: Int): QwenEngine = lock.withLock {
        if (engine != null && loadedTalker != talkerName) release()
        val native = engine ?: QwenEngine().also { engine = it }
        if (!native.setBackendPreference(QwenEngine.BACKEND_CPU)) {
            error("Could not switch backend to CPU")
        }
        native.setCpuThreads(threads)
        if (loadedTalker != talkerName) {
            if (!native.loadModels(modelDir, talkerName)) {
                val message = native.getLastError() ?: "Native model load failed"
                release()
                error(message)
            }
            loadedTalker = talkerName
        }
        native
    }

    fun release() = lock.withLock {
        engine?.close()
        engine = null
        loadedTalker = null
    }

    fun defaultThreads(): Int {
        val available = Runtime.getRuntime().availableProcessors()
        return when {
            available >= 8 -> 6
            available >= 4 -> 4
            else -> available.coerceAtLeast(1)
        }
    }
}
