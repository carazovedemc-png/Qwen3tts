package com.qwen.tts.android.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import kotlin.math.abs

/** Plays a float PCM buffer once; [stop] cancels. Not tied to the main synthesis state. */
class Player {
    private var track: AudioTrack? = null

    @Synchronized
    fun play(samples: FloatArray, rate: Int) {
        stop()
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(samples.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        t.play()
        track = t
    }

    @Synchronized
    fun stop() {
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
    }
}

object Waveform {
    /** Reads a 16-bit WAV and returns [bars] normalized amplitudes in 0..1. */
    fun read(path: String, bars: Int): FloatArray {
        val f = File(path)
        if (!f.isFile) return FloatArray(bars) { 0.15f }
        val (samples, _) = AudioExporter.readWav(f)
        if (samples.isEmpty()) return FloatArray(bars) { 0.15f }
        val out = FloatArray(bars)
        val step = samples.size / bars.toFloat()
        for (b in 0 until bars) {
            var peak = 0f
            val from = (b * step).toInt()
            val to = minOf(samples.size, ((b + 1) * step).toInt().coerceAtLeast(from + 1))
            for (i in from until to) peak = maxOf(peak, abs(samples[i]))
            out[b] = peak
        }
        val max = out.max().coerceAtLeast(0.05f)
        return FloatArray(bars) { (out[it] / max).coerceIn(0.08f, 1f) }
    }
}
