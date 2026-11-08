package com.qwen.tts.android.data

import android.app.Application
import com.qwen.tts.android.engine.Synth
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QueueItem(
    val id: Long,
    val title: String,
    val text: String,
    val status: String = "waiting", // waiting | working | done | error
    val progress: Float = 0f,
    val result: String? = null,
)

/** Sequential batch generation: each item is synthesized with the current voice/language and exported to a file. */
class BatchQueue(
    private val app: Application,
    private val scope: CoroutineScope,
    private val voiceId: () -> String?,
    private val languageId: () -> Int,
) {
    private val ids = AtomicLong(1)
    private val _items = MutableStateFlow<List<QueueItem>>(emptyList())
    val items: StateFlow<List<QueueItem>> = _items.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private var job: Job? = null
    @Volatile private var cancelled = false

    fun add(title: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        _items.update { it + QueueItem(ids.getAndIncrement(), title.ifBlank { t.take(24) }, t) }
    }

    fun remove(id: Long) = _items.update { list -> list.filterNot { it.id == id && it.status != "working" } }
    fun clearFinished() = _items.update { list -> list.filter { it.status == "waiting" || it.status == "working" } }

    fun stop() {
        cancelled = true
    }

    fun start(format: ExportFormat) {
        if (_running.value) return
        val voice = Synth.resolve(app, voiceId(), "vivian")
        if (voice == null) { fail("Голос не найден"); return }
        cancelled = false
        _running.value = true
        val settings = MiaSettings.get(app)
        job = scope.launch(Dispatchers.IO) {
            try {
                for (item in _items.value.filter { it.status == "waiting" || it.status == "error" }) {
                    if (cancelled) break
                    update(item.id) { it.copy(status = "working", progress = 0f, result = null) }
                    try {
                        val samples = Synth.run(
                            app, item.text, voice, languageId(), settings.instruction,
                            onChunk = { done, total -> update(item.id) { it.copy(progress = done / total.toFloat()) } },
                            isCancelled = { cancelled },
                        )
                        val name = "${item.title}-${System.currentTimeMillis() % 100000}"
                        AudioExporter.export(app, samples, Synth.SAMPLE_RATE, name, format)
                        update(item.id) { it.copy(status = "done", progress = 1f, result = "$name.${format.ext}") }
                    } catch (t: Throwable) {
                        update(item.id) { it.copy(status = if (cancelled) "waiting" else "error", result = t.message) }
                    }
                }
            } finally {
                _running.value = false
            }
        }
    }

    private fun fail(message: String) = _items.update { l -> l.map { if (it.status == "waiting") it.copy(status = "error", result = message) else it } }
    private fun update(id: Long, f: (QueueItem) -> QueueItem) = _items.update { l -> l.map { if (it.id == id) f(it) else it } }
}
