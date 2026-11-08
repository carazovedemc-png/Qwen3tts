package com.qwen.tts.android.engine

import com.qwen.tts.studio.engine.QwenEngine
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Process-wide owner of the native engine. Shared by the UI, the batch queue, voice previews
 * and the local API server so the model is loaded once. Every native call must run under [lock].
 */
object EngineHost {
    val lock = ReentrantLock()

    @Volatile var engine: QwenEngine? = null
    @Volatile var loadedTalker: String? = null
    @Volatile private var loadedBackend: Int = -1

    fun ensureLoaded(
        modelDir: String,
        talkerName: String,
        threads: Int,
        backend: Int = QwenEngine.BACKEND_CPU,
    ): QwenEngine = lock.withLock {
        if (engine != null && (loadedTalker != talkerName || loadedBackend != backend)) release()
        val native = engine ?: QwenEngine().also { engine = it }
        if (!native.setBackendPreference(backend)) {
            native.setBackendPreference(QwenEngine.BACKEND_CPU)
        }
        native.setCpuThreads(threads)
        if (loadedTalker != talkerName) {
            if (!native.loadModels(modelDir, talkerName)) {
                val message = native.getLastError() ?: "Native model load failed"
                release()
                error(message)
            }
            loadedTalker = talkerName
            loadedBackend = backend
        }
        native
    }

    fun release() = lock.withLock {
        engine?.close()
        engine = null
        loadedTalker = null
        loadedBackend = -1
    }

    fun defaultThreads(): Int {
        val available = Runtime.getRuntime().availableProcessors()
        return when {
            available >= 8 -> 6
            available >= 4 -> 4
            else -> available.coerceAtLeast(1)
        }
    }

    /** Bit mask of backends compiled into the native library (bit 0 CPU, bit 1 GPU). */
    fun compiledBackendMask(): Int = runCatching {
        engine?.getCompiledBackendMask() ?: QwenEngine().use { it.getCompiledBackendMask() }
    }.getOrDefault(1)
}
