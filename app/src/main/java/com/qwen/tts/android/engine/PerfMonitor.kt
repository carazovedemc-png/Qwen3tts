package com.qwen.tts.android.engine

import android.os.Debug
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Locale

/**
 * Монитор скорости генерации. Все цифры — реальные замеры на телефоне (System.nanoTime вокруг
 * нативного вызова), ничего не оценивается «на глаз». RTF = время генерации / длительность аудио:
 * меньше 1.0 значит быстрее реального времени.
 */
object PerfMonitor {
    class Entry(
        val label: String,
        val textChars: Int,
        val chunks: Int,
        val loadMs: Long,
        val wallMs: Long,
        val firstChunkMs: Long,
        val audioSec: Double,
        val threads: Int,
        val ramMb: Int,
    ) {
        val rtf: Double get() = if (audioSec > 0.0) wallMs / 1000.0 / audioSec else 0.0
    }

    var last by mutableStateOf<Entry?>(null)
        private set
    var engineLog by mutableStateOf("")
        private set
    var runs by mutableStateOf(0)
        private set
    private var sumRtf = 0.0

    val averageRtf: Double get() = if (runs > 0) sumRtf / runs else 0.0

    fun record(entry: Entry, log: String) {
        last = entry
        engineLog = log
        if (entry.rtf > 0.0) { sumRtf += entry.rtf; runs += 1 }
    }

    fun reset() { last = null; engineLog = ""; runs = 0; sumRtf = 0.0 }

    fun ramMb(): Int = runCatching {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        mi.totalPss / 1024
    }.getOrDefault(0)

    /** Что реально умеет процессор (из /proc/cpuinfo): нужно, чтобы понять, можно ли включать i8mm. */
    fun cpuFeatures(): String = runCatching {
        val line = File("/proc/cpuinfo").useLines { seq -> seq.firstOrNull { it.startsWith("Features") } }.orEmpty()
        val f = line.substringAfter(':').trim().split(' ').toSet()
        fun yes(b: Boolean) = if (b) "да" else "нет"
        "dotprod: ${yes("asimddp" in f)}, i8mm: ${yes("i8mm" in f)}, bf16: ${yes("bf16" in f)}, " +
            "fp16: ${yes("asimdhp" in f)}, SVE: ${yes("sve" in f)}"
    }.getOrDefault("не удалось прочитать")

    /** Выжимка строк «Total / Compute / Graph build» из отчёта движка, чтобы не листать 80 строк. */
    fun summarizeLog(log: String): String {
        if (log.isBlank()) return "Отчёт движка пока пуст (запустите озвучку)."
        val keep = log.lineSequence().filter {
            val t = it.trim()
            t.startsWith("===") || t.startsWith("Prefill") || t.startsWith("Talker") ||
                t.startsWith("Code predictor") || t.startsWith("Total") || t.startsWith("Compute") ||
                t.startsWith("Graph build") || t.startsWith("Graph alloc") || t.startsWith("Forward total") ||
                t.startsWith("Steps") || t.startsWith("Sampling") || t.startsWith("Data I/O") ||
                t.contains("Native CPU threads")
        }
        // Берём последний отчёт целиком (после последней строки-заголовка).
        val all = keep.toList()
        val from = all.indexOfLast { it.trim().startsWith("=== Detailed") }.coerceAtLeast(0)
        return all.drop(from).joinToString("\n")
    }

    fun fmt(v: Double, d: Int = 2): String = String.format(Locale.US, "%.${d}f", v)
}
