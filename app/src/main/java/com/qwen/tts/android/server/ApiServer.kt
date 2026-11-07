package com.qwen.tts.android.server

import android.content.Context
import com.qwen.tts.android.QwenModel
import com.qwen.tts.android.data.SpeakerCatalog
import com.qwen.tts.android.data.db.QwenDatabase
import com.qwen.tts.android.engine.EngineHost
import com.qwen.tts.studio.engine.QwenEngine
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tiny dependency-free HTTP server with an OpenAI-compatible text-to-speech endpoint.
 *
 *   POST /v1/audio/speech   {"input": "...", "voice": "vivian", "language": "zh"}  -> audio/wav
 *   GET  /v1/voices         list of usable voices
 *   GET  /health            no auth
 *
 * Auth: "Authorization: Bearer <key>" or "X-API-Key: <key>".
 */
class ApiServer(
    private val context: Context,
    private val prefs: ServerPrefs,
) {
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val dao = QwenDatabase.getDatabase(context).qwenDao()
    private val modelDir = File(context.filesDir, "qwen3-tts-models")

    @Volatile private var running = false

    fun start() {
        val bind = if (prefs.lanAccess) null else InetAddress.getByName("127.0.0.1")
        val socket = ServerSocket(prefs.port, 32, bind)
        serverSocket = socket
        running = true
        thread(name = "qwen-api-accept", isDaemon = true) {
            while (running) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                pool.execute { handle(client) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        pool.shutdownNow()
    }

    // ---------------------------------------------------------------- HTTP

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15_000
            val out = s.getOutputStream()
            try {
                val req = readRequest(BufferedInputStream(s.getInputStream())) ?: return
                route(req, out)
            } catch (e: HttpError) {
                writeJson(out, e.status, errorJson(e.message ?: "error"))
            } catch (e: Exception) {
                runCatching { writeJson(out, 500, errorJson(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    private class HttpError(val status: Int, message: String) : Exception(message)

    private fun readRequest(input: BufferedInputStream): Request? {
        fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 16_384) throw HttpError(431, "Header too large")
            }
        }
        val requestLine = readLine() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) throw HttpError(400, "Bad request line")
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) throw HttpError(413, "Body too large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw HttpError(400, "Unexpected end of body")
            read += n
        }
        return Request(parts[0].uppercase(), parts[1].substringBefore('?'), headers, body)
    }

    private fun route(req: Request, out: java.io.OutputStream) {
        if (req.method == "OPTIONS") return writeRaw(out, 204, "text/plain", ByteArray(0))
        if (req.method == "GET" && (req.path == "/health" || req.path == "/")) {
            return writeJson(out, 200, JSONObject().put("status", "ok").put("model_loaded", EngineHost.loadedTalker != null))
        }
        authorize(req)
        when {
            req.method == "GET" && req.path == "/v1/voices" -> writeJson(out, 200, listVoices())
            req.method == "GET" && req.path == "/v1/models" -> writeJson(
                out, 200,
                JSONObject().put("object", "list").put(
                    "data", JSONArray().put(JSONObject().put("id", "qwen3-tts").put("object", "model")),
                ),
            )
            req.method == "POST" && (req.path == "/v1/audio/speech" || req.path == "/tts") -> speech(req, out)
            else -> throw HttpError(404, "Not found: ${req.method} ${req.path}")
        }
    }

    private fun authorize(req: Request) {
        val bearer = req.headers["authorization"]?.removePrefix("Bearer ")?.trim()
        val given = bearer ?: req.headers["x-api-key"]?.trim() ?: ""
        val ok = MessageDigest.isEqual(given.toByteArray(), prefs.apiKey.toByteArray())
        if (!ok) throw HttpError(401, "Invalid API key")
    }

    // ---------------------------------------------------------------- API

    private fun listVoices(): JSONObject {
        val arr = JSONArray()
        SpeakerCatalog.presets.forEach {
            arr.put(
                JSONObject().put("id", it.key).put("name", it.displayName).put("type", "preset")
                    .put("gender", it.gender.name.lowercase()).put("language", it.nativeLanguage),
            )
        }
        runBlocking { dao.getVoices() }.forEach {
            arr.put(JSONObject().put("id", it.voiceId).put("name", it.name).put("type", "cloned"))
        }
        return JSONObject().put("voices", arr).put("default", prefs.defaultVoice)
    }

    private fun speech(req: Request, out: java.io.OutputStream) {
        val json = try { JSONObject(String(req.body, Charsets.UTF_8)) } catch (_: Exception) {
            throw HttpError(400, "Body must be JSON")
        }
        val text = json.optString("input", json.optString("text", "")).trim()
        if (text.isEmpty()) throw HttpError(400, "'input' is required")
        val format = json.optString("response_format", "wav").lowercase()
        val languageId = parseLanguage(json.optString("language", "")) ?: prefs.defaultLanguageId

        val (talker, speakerKey, embeddingPath) = resolveVoice(json.optString("voice", ""))
        val file = File(modelDir, talker)
        if (!file.isFile) throw HttpError(503, "Model file $talker is not downloaded. Open the app → Settings.")

        val samples = EngineHost.lock.withLock {
            val native = EngineHost.ensureLoaded(modelDir.absolutePath, talker, EngineHost.defaultThreads())
            native.setProgressCallback(null)
            val all = ArrayList<FloatArray>()
            for (chunk in splitText(text)) {
                val r = native.synthesize(
                    text = chunk,
                    speakerEmbeddingPath = embeddingPath,
                    params = QwenEngine.NativeParams(languageId = languageId, speaker = speakerKey),
                )
                if (!r.success || r.audio == null) throw HttpError(500, r.errorMsg ?: "Synthesis failed")
                all += r.audio
                all += FloatArray(SAMPLE_RATE / 8) // 125 ms pause between chunks
            }
            all.fold(FloatArray(0)) { acc, a -> acc + a }
        }

        if (format == "pcm") {
            writeRaw(out, 200, "audio/pcm", pcm16(samples))
        } else {
            writeRaw(out, 200, "audio/wav", wav16(samples))
        }
    }

    /** @return Triple(talker file, preset speaker key or null, speaker embedding path or null) */
    private fun resolveVoice(requested: String): Triple<String, String?, String?> {
        val name = requested.trim().ifEmpty { prefs.defaultVoice }
        fun preset(key: String) = SpeakerCatalog.byKey(key)?.let {
            Triple(QwenModel.customVoiceVariant.talkerName, it.key, null as String?)
        }
        preset(name)?.let { return it }
        val cloned = runBlocking { dao.getVoices() }.firstOrNull {
            it.voiceId.equals(name, true) || it.name.equals(name, true)
        }
        if (cloned != null) return Triple(QwenModel.defaultVariant.talkerName, null, cloned.speakerEmbeddingPath)
        // Unknown name (e.g. OpenAI's "alloy"): fall back to the configured default instead of failing.
        preset(prefs.defaultVoice)?.let { return it }
        val clonedDefault = runBlocking { dao.getVoices() }.firstOrNull {
            it.voiceId.equals(prefs.defaultVoice, true) || it.name.equals(prefs.defaultVoice, true)
        } ?: throw HttpError(400, "Unknown voice '$requested'")
        return Triple(QwenModel.defaultVariant.talkerName, null, clonedDefault.speakerEmbeddingPath)
    }

    private fun parseLanguage(value: String): Int? = when (value.trim().lowercase()) {
        "" -> null
        "auto" -> -1
        "en", "english" -> 2050
        "de", "german" -> 2053
        "es", "spanish" -> 2054
        "zh", "chinese" -> 2055
        "ja", "japanese" -> 2058
        "fr", "french" -> 2061
        "ko", "korean" -> 2064
        "ru", "russian" -> 2069
        else -> null
    }

    /** Splits long text into sentence-sized chunks so one request never exceeds the token limit. */
    private fun splitText(text: String, maxChars: Int = 160): List<String> {
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

    // ---------------------------------------------------------------- output

    private fun errorJson(message: String) =
        JSONObject().put("error", JSONObject().put("message", message).put("type", "api_error"))

    private fun writeJson(out: java.io.OutputStream, status: Int, json: JSONObject) =
        writeRaw(out, status, "application/json; charset=utf-8", json.toString().toByteArray())

    private fun writeRaw(out: java.io.OutputStream, status: Int, type: String, body: ByteArray) {
        val reason = when (status) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 503 -> "Service Unavailable"; else -> "Error" }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun pcm16(samples: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return buf.array()
    }

    private fun wav16(samples: FloatArray): ByteArray {
        val data = pcm16(samples)
        val bos = ByteArrayOutputStream(44 + data.size)
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.size)
        bos.write(h.array()); bos.write(data)
        return bos.toByteArray()
    }

    companion object {
        private const val MAX_BODY = 1_000_000
        private const val SAMPLE_RATE = 24_000
    }
}
