package com.qwen.tts.android.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    WAV("WAV", "wav", "audio/x-wav"),
    OGG("OGG", "ogg", "audio/ogg"),
    M4A("M4A", "m4a", "audio/mp4");

    companion object {
        fun of(label: String) = entries.firstOrNull { it.label.equals(label, true) } ?: WAV
    }
}

/** Saves float PCM to Music/Mia ai voice (MediaStore) as WAV, OGG (Opus) or M4A (AAC). */
object AudioExporter {
    fun export(context: Context, samples: FloatArray, sampleRate: Int, baseName: String, format: ExportFormat): Uri {
        val safe = baseName.replace(Regex("[\\\\/:*?\"<>|\\n]"), "_").take(60).ifBlank { "voice" }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$safe.${format.ext}")
            put(MediaStore.Audio.Media.MIME_TYPE, format.mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Mia ai voice")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create file")
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                when (format) {
                    ExportFormat.WAV -> out.write(wavBytes(samples, sampleRate))
                    ExportFormat.OGG -> encodeToStream(context, samples, sampleRate, "audio/opus", MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, out)
                    ExportFormat.M4A -> encodeToStream(context, samples, sampleRate, "audio/mp4a-latm", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, out)
                }
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return uri
    }

    fun readWav(file: File): Pair<FloatArray, Int> {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val rate = buf.getInt(24)
        val count = (bytes.size - 44) / 2
        val out = FloatArray(count)
        for (i in 0 until count) out[i] = buf.getShort(44 + i * 2) / 32768f
        return out to rate
    }

    fun wavBytes(samples: FloatArray, rate: Int): ByteArray {
        val data = pcm16(samples)
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.size)
        return h.array() + data
    }

    private fun pcm16(samples: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) b.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return b.array()
    }

    private fun encodeToStream(
        context: Context, samples: FloatArray, rate: Int, mime: String, container: Int, out: java.io.OutputStream,
    ) {
        val tmp = File.createTempFile("enc", ".bin", context.cacheDir)
        try {
            val format = MediaFormat.createAudioFormat(mime, rate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                if (mime == "audio/mp4a-latm") setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val codec = MediaCodec.createEncoderByType(mime)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val muxer = MediaMuxer(tmp.absolutePath, container)
            var track = -1
            var muxing = false
            val pcm = pcm16(samples)
            var offset = 0
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            buf.clear()
                            val n = minOf(buf.remaining(), pcm.size - offset)
                            val pts = offset / 2L * 1_000_000L / rate
                            if (n <= 0) {
                                codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buf.put(pcm, offset, n)
                                codec.queueInputBuffer(i, 0, n, pts, 0)
                                offset += n
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxing = true
                        }
                        o >= 0 -> {
                            val data = codec.getOutputBuffer(o)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxing) {
                                data.position(info.offset)
                                data.limit(info.offset + info.size)
                                muxer.writeSampleData(track, data, info)
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
                if (muxing) runCatching { muxer.stop() }
                muxer.release()
            }
            tmp.inputStream().use { it.copyTo(out) }
        } finally {
            tmp.delete()
        }
    }
}
