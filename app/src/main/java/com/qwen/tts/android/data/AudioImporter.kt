package com.qwen.tts.android.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Decodes any audio (or video with audio track) the platform can read — mp3, m4a, ogg, opus,
 * wav, flac, aac, voice messages, mp4 — into a 24 kHz mono 16-bit WAV, the format the native
 * speaker encoder expects. Keeps at most [MAX_SECONDS] of speech (leading silence trimmed).
 */
object AudioImporter {
    const val TARGET_RATE = 24_000
    const val MAX_SECONDS = 30
    const val MIN_SECONDS = 3

    /** @return duration of the written WAV in milliseconds. */
    fun importToWav(context: Context, uri: Uri, out: File): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; format = f; break
                }
            }
            require(track >= 0 && format != null) { "В файле нет аудиодорожки" }
            extractor.selectTrack(track)

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var pcm = FloatArray(sampleRate * 10)
            var count = 0
            fun append(v: Float) {
                if (count == pcm.size) pcm = pcm.copyOf(pcm.size * 2)
                pcm[count++] = v
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var skippingSilence = true
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buf = codec.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val nf = codec.outputFormat
                            sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        outIndex >= 0 -> {
                            val shorts = codec.getOutputBuffer(outIndex)!!
                                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frames = shorts.remaining() / channels.coerceAtLeast(1)
                            for (f in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until channels) sum += shorts.get() / 32768f
                                val mono = sum / channels
                                if (skippingSilence && abs(mono) < 0.01f) continue
                                skippingSilence = false
                                append(mono)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            if (count >= sampleRate * MAX_SECONDS) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }

            require(count > sampleRate) { "Слишком короткий или пустой аудиофайл" }
            val limit = min(count, sampleRate * MAX_SECONDS)
            val resampled = resampleLinear(pcm, limit, sampleRate, TARGET_RATE)

            var peak = 0f
            for (v in resampled) peak = maxOf(peak, abs(v))
            if (peak > 0.001f) {
                val gain = 0.9f / peak
                for (i in resampled.indices) resampled[i] *= gain
            }

            out.parentFile?.mkdirs()
            out.outputStream().buffered().use { writeWav16(it, resampled, TARGET_RATE) }
            return resampled.size * 1000L / TARGET_RATE
        } finally {
            extractor.release()
        }
    }

    private fun resampleLinear(src: FloatArray, n: Int, from: Int, to: Int): FloatArray {
        if (from == to) return src.copyOf(n)
        val outLen = (n.toLong() * to / from).toInt()
        val out = FloatArray(outLen)
        val ratio = from.toDouble() / to
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt()
            val i1 = min(i0 + 1, n - 1)
            val frac = (pos - i0).toFloat()
            out[i] = src[i0] * (1 - frac) + src[i1] * frac
        }
        return out
    }

    private fun writeWav16(out: java.io.OutputStream, samples: FloatArray, rate: Int) {
        val dataSize = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(dataSize)
        out.write(header.array())
        val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) body.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        out.write(body.array())
    }
}
