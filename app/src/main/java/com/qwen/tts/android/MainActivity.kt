package com.qwen.tts.android

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.qwen.tts.android.data.AudioImporter
import com.qwen.tts.android.data.AudioExporter
import com.qwen.tts.android.data.BatchQueue
import com.qwen.tts.android.data.ExportFormat
import com.qwen.tts.android.data.MiaSettings
import com.qwen.tts.android.data.Player
import com.qwen.tts.android.engine.Synth
import com.qwen.tts.android.data.SpeakerCatalog
import com.qwen.tts.android.data.resolveVoiceName
import com.qwen.tts.android.engine.EngineHost
import com.qwen.tts.android.server.ApiServerService
import com.qwen.tts.android.data.VoiceRecorder
import com.qwen.tts.android.data.db.GenerationEntity
import com.qwen.tts.android.data.db.QwenDatabase
import com.qwen.tts.android.data.db.VoiceProfileEntity
import com.qwen.tts.android.ui.theme.QwenTtsTheme
import com.qwen.tts.studio.engine.QwenEngine
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.withLock
import kotlinx.coroutines.withContext

internal data class ModelFile(
    val name: String,
    val url: String?,
    val sizeBytes: Long,
)

internal data class ModelVariant(
    val id: String,
    val label: String,
    val displayName: String,
    val files: List<ModelFile>,
    val talkerName: String,
) {
    val totalBytes: Long = files.sumOf { it.sizeBytes }
}

internal data class LanguageOption(val label: String, val id: Int)
private data class BackendOption(val id: String, val label: String, val preference: Int)
private data class GenerationTimeEstimate(val totalMillis: Long, val sampleSize: Int)

private val languageOptions = listOf(
    LanguageOption("English", 2050),
    LanguageOption("German", 2053),
    LanguageOption("Spanish", 2054),
    LanguageOption("French", 2061),
    LanguageOption("Chinese", 2055),
    LanguageOption("Japanese", 2058),
    LanguageOption("Korean", 2064),
    LanguageOption("Russian", 2069),
)

private val backendOptions = listOf(
    BackendOption("cpu", "CPU", QwenEngine.BACKEND_CPU),
)

private val defaultBackendOption = backendOptions.first { it.id == "cpu" }

internal object QwenModel {
    private const val repo = "Serveurperso/Qwen3-TTS-GGUF"
    private const val baseUrl = "https://huggingface.co/$repo/resolve/main"

    val defaultVariant: ModelVariant = variant(
        id = "q4_k_m",
        label = "Q4_K_M",
        displayName = "Qwen3-TTS 0.6B Q4_K_M",
        talkerName = "qwen-talker-0.6b-base-Q4_K_M.gguf",
        tokenizerName = "qwen-tokenizer-12hz-Q4_K_M.gguf",
        talkerSizeBytes = 628_905_056L,
        tokenizerSizeBytes = 254_974_752L,
    )

    // CustomVoice = built-in named speakers (Vivian, Serena, Ryan, ...). Size is approximate:
    // the completeness check only needs a lower bound, so it is set slightly below the real file.
    val customVoiceVariant: ModelVariant = variant(
        id = "customvoice_q4_k_m",
        label = "CustomVoice Q4_K_M",
        displayName = "Qwen3-TTS 0.6B CustomVoice Q4_K_M",
        talkerName = "qwen-talker-0.6b-customvoice-Q4_K_M.gguf",
        tokenizerName = "qwen-tokenizer-12hz-Q4_K_M.gguf",
        talkerSizeBytes = 600_000_000L,
        tokenizerSizeBytes = 254_974_752L,
    )

    val variants = listOf(defaultVariant, customVoiceVariant)

    val obsoleteFileNames = setOf(
        "qwen-talker-0.6b-base-Q8_0.gguf",
        "qwen-talker-0.6b-base-NVFP4.gguf",
        "qwen-tokenizer-12hz-Q8_0.gguf",
    )

    // Q8_0 and NVFP4 used to be available here. The Android app now intentionally
    // ships one supported path: Q4_K_M.

    fun variantById(id: String): ModelVariant =
        variants.firstOrNull { it.id == id } ?: defaultVariant

    private fun variant(
        id: String,
        label: String,
        displayName: String,
        talkerName: String,
        tokenizerName: String,
        talkerSizeBytes: Long,
        tokenizerSizeBytes: Long,
        talkerUrl: String? = "$baseUrl/$talkerName?download=true",
        tokenizerUrl: String? = "$baseUrl/$tokenizerName?download=true",
    ): ModelVariant =
        ModelVariant(
            id = id,
            label = label,
            displayName = displayName,
            talkerName = talkerName,
            files = listOf(
                ModelFile(tokenizerName, tokenizerUrl, tokenizerSizeBytes),
                ModelFile(talkerName, talkerUrl, talkerSizeBytes),
            ),
        )
}

data class QwenTtsUiState(
    val text: String = "Привет! Меня зовут Мия. Я здесь, чтобы помочь и ответить на все ваши вопросы.",
    val selectedModelId: String = QwenModel.defaultVariant.id,
    val selectedBackendId: String = defaultBackendOption.id,
    val selectedVoiceId: String? = "preset:vivian",
    val activeBackendName: String? = null,
    val modelReady: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadBytes: Long = 0L,
    val downloadTotalBytes: Long = QwenModel.defaultVariant.totalBytes,
    val status: String = "Model not downloaded",
    val error: String? = null,
    val sampleRate: Int = 0,
    val sampleCount: Int = 0,
    val synthesisMillis: Long = 0,
    val playing: Boolean = false,
    val playingGenerationId: Long? = null,
    val operationElapsedMillis: Long = 0,
    val synthesisFrames: Int = 0,
    val maxAudioTokens: Int = 512,
    val cpuThreads: Int = 0,
    val selectedCpuThreads: Int = 0,
    val selectedLanguageId: Int = 2069,
    val tokenizeMillis: Long = 0,
    val encodeMillis: Long = 0,
    val generateMillis: Long = 0,
    val decodeMillis: Long = 0,
    val decodeFrames: Int = 0,
    val decodeSamples: Long = 0,
    val decodeGraphComputeMillis: Long = 0,
    val savedFileName: String? = null,
    val snackbarMessage: String? = null,
    val estimatedSynthesisMillis: Long? = null,
    val estimateSampleCount: Int = 0,
    val supportsCloning: Boolean? = null,
    val speakerEmbeddingDim: Int = 0,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val modelDir = File(application.filesDir, "qwen3-tts-models")
    private val voiceDir = File(application.filesDir, "voices")
    private val generationDir = File(application.filesDir, "generations")
    private val dao = QwenDatabase.getDatabase(application).qwenDao()
    private val recorder = VoiceRecorder()

    val voices = dao.observeVoices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val generations = dao.observeGenerations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recorderState = recorder.state

    private val _uiState = MutableStateFlow(
        QwenTtsUiState(
            selectedCpuThreads = MiaSettings.get(application).threads,
            modelReady = isModelReady(QwenModel.defaultVariant),
            downloadTotalBytes = QwenModel.defaultVariant.totalBytes,
        ),
    )
    val uiState = _uiState.asStateFlow()

    private var engine: QwenEngine?
        get() = EngineHost.engine
        set(value) { EngineHost.engine = value }
    private var generatedAudio: FloatArray? = null
    private var playJob: Job? = null
    private var operationTickerJob: Job? = null
    private var activeTrack: AudioTrack? = null

    init {
        val threads = preferredCpuThreadCount()
        _uiState.update { it.copy(selectedCpuThreads = threads, cpuThreads = threads) }
        refreshModelState()
    }

    fun updateText(value: String) {
        _uiState.update { it.copy(text = value) }
    }

    fun updateCpuThreads(value: Int) {
        if (_uiState.value.busy) return
        MiaSettings.get(getApplication()).threads = value
        _uiState.update { it.copy(selectedCpuThreads = value, cpuThreads = value) }
        engine?.setCpuThreads(value)
    }

    fun updateLanguage(languageId: Int) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(selectedLanguageId = languageId) }
    }

    fun selectVoice(voiceId: String?) {
        _uiState.update { it.copy(selectedVoiceId = voiceId) }
    }

    fun clearSnackbarMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun selectModel(modelId: String) {
        if (_uiState.value.busy || _uiState.value.selectedModelId == modelId) return
        val variant = QwenModel.variantById(modelId)
        stopAudio()
        EngineHost.release()
        generatedAudio = null
        val ready = isModelReady(variant)
        _uiState.update {
            it.copy(
                selectedModelId = variant.id,
                activeBackendName = null,
                modelReady = ready,
                loaded = false,
                status = if (ready) "Model ready" else "Model not downloaded",
                error = null,
                downloadProgress = 0f,
                downloadBytes = 0L,
                downloadTotalBytes = variant.totalBytes,
                sampleRate = 0,
                sampleCount = 0,
                synthesisMillis = 0,
                synthesisFrames = 0,
                tokenizeMillis = 0,
                encodeMillis = 0,
                generateMillis = 0,
                decodeMillis = 0,
                decodeFrames = 0,
                decodeSamples = 0,
                decodeGraphComputeMillis = 0,
                savedFileName = null,
                estimatedSynthesisMillis = null,
                estimateSampleCount = 0,
                supportsCloning = null,
                speakerEmbeddingDim = 0,
            )
        }
    }

    fun downloadModel() {
        if (_uiState.value.downloading) return
        viewModelScope.launch {
            val variant = selectedVariant()
            startBusy(status = "Preparing model download", downloading = true)
            _uiState.update {
                it.copy(downloadProgress = 0f, downloadBytes = 0L, downloadTotalBytes = variant.totalBytes)
            }

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    modelDir.mkdirs()
                    downloadFiles(variant)
                }
            }

            result.fold(
                onSuccess = {
                    stopBusyTicker()
                    refreshModelState("Model ready")
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            downloading = false,
                            status = "Download failed",
                            error = throwable.message ?: "Download failed",
                        )
                    }
                },
            )
        }
    }

    fun loadModel() {
        if (!_uiState.value.modelReady || _uiState.value.loaded || _uiState.value.busy) return
        viewModelScope.launch {
            startBusy(status = "Loading native model")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val native = ensureEngineLoaded(selectedVariant())
                    native.getActiveBackendName().orEmpty() to native.getCpuThreads()
                }
            }

            result.fold(
                onSuccess = { (backend, threads) ->
                    stopBusyTicker()
                    val caps = engine?.getModelCapabilities()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            loaded = true,
                            status = if (backend.isBlank()) "Model loaded" else "Model loaded on $backend",
                            activeBackendName = backend.ifBlank { null },
                            cpuThreads = threads,
                            supportsCloning = caps?.supportsCloning,
                            speakerEmbeddingDim = caps?.speakerEmbeddingDim ?: 0,
                        )
                    }
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            loaded = false,
                            status = "Load failed",
                            error = throwable.message ?: "Load failed",
                        )
                    }
                },
            )
        }
    }

    fun synthesize() {
        val text = _uiState.value.text.trim()
        if (text.isEmpty() || _uiState.value.busy) return
        val preset = SpeakerCatalog.byVoiceId(_uiState.value.selectedVoiceId)
        val wanted = if (preset != null) QwenModel.customVoiceVariant else QwenModel.defaultVariant
        if (_uiState.value.selectedModelId != wanted.id) selectModel(wanted.id)
        if (!isModelReady(wanted)) {
            _uiState.update {
                it.copy(
                    snackbarMessage = "Для этого голоса нужна модель ${wanted.displayName} (${formatBytes(wanted.totalBytes)}). Settings → Download model.",
                )
            }
            return
        }
        viewModelScope.launch {
            val variant = wanted
            val selectedVoice = _uiState.value.selectedVoiceId?.let { id -> voices.value.firstOrNull { it.voiceId == id } }
            val estimate = estimateSynthesisMillis(text)
            startBusy(status = "Synthesizing speech", resetSynthesis = true, synthesisEstimate = estimate)

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val native = ensureEngineLoaded(variant)
                    native.setProgressCallback { frames, maxTokens ->
                        _uiState.update {
                            it.copy(
                                loaded = true,
                                status = "Generating speech codes",
                                synthesisFrames = frames,
                                maxAudioTokens = maxTokens,
                            )
                        }
                    }
                    try {
                        EngineHost.lock.withLock {
                            native.synthesize(
                                text = text,
                                speakerEmbeddingPath = selectedVoice?.speakerEmbeddingPath,
                                params = Synth.params(
                                    getApplication(),
                                    _uiState.value.selectedLanguageId,
                                    preset?.key,
                                    MiaSettings.get(getApplication()).instruction,
                                ),
                            )
                        }
                    } finally {
                        _uiState.update {
                            it.copy(activeBackendName = native.getActiveBackendName()?.takeIf { name -> name.isNotBlank() })
                        }
                        native.setProgressCallback(null)
                    }
                }
            }

            result.fold(
                onSuccess = { nativeResult ->
                    stopBusyTicker()
                    if (!nativeResult.success || nativeResult.audio == null) {
                        _uiState.update {
                            it.copy(
                                busy = false,
                                loaded = true,
                                status = "Synthesis failed",
                                error = nativeResult.errorMsg ?: "Native synthesis failed",
                            )
                        }
                        return@fold
                    }

                    generatedAudio = nativeResult.audio
                    val sampleRate = nativeResult.sampleRate.takeIf { it > 0 } ?: 24_000
                    val voiceName = selectedVoice?.name ?: preset?.displayName ?: "Default Voice"
                    persistGeneratedAudio(text, nativeResult.audio, sampleRate, voiceName, _uiState.value.selectedVoiceId, nativeResult.timeMs)
                    _uiState.update {
                        it.copy(
                            busy = false,
                            loaded = true,
                            status = "Speech ready",
                            sampleRate = sampleRate,
                            sampleCount = nativeResult.audio.size,
                            synthesisMillis = nativeResult.timeMs,
                            tokenizeMillis = nativeResult.tokenizeMs,
                            encodeMillis = nativeResult.encodeMs,
                            generateMillis = nativeResult.generateMs,
                            decodeMillis = nativeResult.decodeMs,
                            decodeFrames = nativeResult.decodeFrames,
                            decodeSamples = nativeResult.decodeSamples,
                            decodeGraphComputeMillis = nativeResult.decodeGraphComputeMs,
                            estimatedSynthesisMillis = null,
                            estimateSampleCount = 0,
                            error = null,
                        )
                    }
                    playAudio()
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            status = "Synthesis failed",
                            error = throwable.message ?: "Synthesis failed",
                        )
                    }
                },
            )
        }
    }

    fun startVoiceRecording() {
        if (_uiState.value.busy || recorderState.value.isRecording) return
        val file = File(voiceDir, "pending-${System.currentTimeMillis()}.wav")
        if (!recorder.start(file)) {
            _uiState.update { it.copy(error = "Could not start microphone recording") }
        }
    }

    fun stopVoiceRecordingAndCreate(name: String) {
        val trimmedName = name.trim().ifBlank { "Voice ${voices.value.size + 1}" }
        val result = recorder.stop() ?: return
        viewModelScope.launch {
            startBusy(status = "Creating voice embedding")
            val created = runCatching {
                withContext(Dispatchers.IO) {
                    val native = ensureEngineLoaded(selectedVariant())
                    val caps = native.getModelCapabilities()
                    if (caps?.supportsCloning == false) {
                        error("The loaded model does not expose a speaker encoder.")
                    }

                    val voiceId = "voice-${System.currentTimeMillis()}"
                    val targetDir = File(voiceDir, voiceId).apply { mkdirs() }
                    val reference = File(targetDir, "reference.wav")
                    result.file.copyTo(reference, overwrite = true)
                    runCatching { result.file.delete() }

                    val embedding = File(targetDir, "speaker.json")
                    if (!native.extractSpeakerEmbedding(reference.absolutePath, embedding.absolutePath)) {
                        error(native.getLastError() ?: "Speaker embedding extraction failed")
                    }

                    VoiceProfileEntity(
                        voiceId = voiceId,
                        name = makeUniqueVoiceName(trimmedName),
                        referenceWavPath = reference.absolutePath,
                        speakerEmbeddingPath = embedding.absolutePath,
                        durationMillis = result.durationMillis,
                    )
                }
            }

            created.fold(
                onSuccess = { voice ->
                    dao.insertVoice(voice)
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            selectedVoiceId = voice.voiceId,
                            status = "Voice created",
                            error = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            status = "Voice creation failed",
                            error = throwable.message ?: "Voice creation failed",
                        )
                    }
                },
            )
        }
    }

    fun importVoiceFromUri(uri: Uri, name: String) {
        if (_uiState.value.busy) return
        val base = QwenModel.defaultVariant
        if (_uiState.value.selectedModelId != base.id) selectModel(base.id)
        if (!isModelReady(base)) {
            _uiState.update { it.copy(snackbarMessage = "Для клонирования нужна Base-модель: Settings → Download model.") }
            return
        }
        val context = getApplication<Application>()
        viewModelScope.launch {
            startBusy(status = "Importing audio file")
            val created = runCatching {
                withContext(Dispatchers.IO) {
                    val voiceId = "voice-${System.currentTimeMillis()}"
                    val targetDir = File(voiceDir, voiceId).apply { mkdirs() }
                    val reference = File(targetDir, "reference.wav")
                    val durationMs = AudioImporter.importToWav(context, uri, reference)
                    if (durationMs < AudioImporter.MIN_SECONDS * 1000L) {
                        targetDir.deleteRecursively()
                        error("Слишком короткий образец (${durationMs / 1000} с). Нужно минимум ${AudioImporter.MIN_SECONDS} с чистой речи.")
                    }
                    val native = ensureEngineLoaded(base)
                    val embedding = File(targetDir, "speaker.json")
                    val ok = EngineHost.lock.withLock {
                        native.extractSpeakerEmbedding(reference.absolutePath, embedding.absolutePath)
                    }
                    if (!ok) {
                        targetDir.deleteRecursively()
                        error(native.getLastError() ?: "Speaker embedding extraction failed")
                    }
                    val displayName = name.trim().ifBlank {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null }
                            ?: "Voice ${voices.value.size + 1}"
                    }
                    VoiceProfileEntity(
                        voiceId = voiceId,
                        name = makeUniqueVoiceName(displayName),
                        referenceWavPath = reference.absolutePath,
                        speakerEmbeddingPath = embedding.absolutePath,
                        durationMillis = durationMs,
                    )
                }
            }
            created.fold(
                onSuccess = { voice ->
                    dao.insertVoice(voice)
                    stopBusyTicker()
                    _uiState.update { it.copy(busy = false, selectedVoiceId = voice.voiceId, status = "Voice created", error = null) }
                },
                onFailure = { t ->
                    stopBusyTicker()
                    _uiState.update { it.copy(busy = false, status = "Voice import failed", error = t.message ?: "Voice import failed") }
                },
            )
        }
    }

    fun cancelVoiceRecording() {
        recorder.cancel()
    }

    fun deleteVoice(voice: VoiceProfileEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteVoice(voice)
            runCatching { File(voice.referenceWavPath).parentFile?.deleteRecursively() }
            if (_uiState.value.selectedVoiceId == voice.voiceId) {
                _uiState.update { it.copy(selectedVoiceId = null) }
            }
        }
    }

    fun playGeneration(generationId: Long) {
        if (_uiState.value.playing && _uiState.value.playingGenerationId == generationId) {
            stopAudio()
            return
        }
        viewModelScope.launch {
            val generation = withContext(Dispatchers.IO) { dao.getGeneration(generationId) } ?: return@launch
            val loaded = runCatching { withContext(Dispatchers.IO) { readWav(generation.wavPath) } }
            loaded.onSuccess { wav ->
                stopAudio()
                generatedAudio = wav.samples
                _uiState.update {
                    it.copy(
                        sampleRate = wav.sampleRate,
                        sampleCount = wav.samples.size,
                        status = "Speech ready",
                        error = null,
                    )
                }
                playAudio(generationId)
            }.onFailure { throwable ->
                _uiState.update { it.copy(error = throwable.message ?: "Could not play history item") }
            }
        }
    }

    fun deleteGeneration(generation: GenerationEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteGeneration(generation)
            runCatching { File(generation.wavPath).delete() }
        }
    }

    fun playAudio(historyGenerationId: Long? = null) {
        val samples = generatedAudio ?: return
        val sampleRate = _uiState.value.sampleRate.takeIf { it > 0 } ?: 24_000
        stopAudio()
        playJob = viewModelScope.launch(Dispatchers.IO) {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples.size * 4)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            activeTrack = track
            _uiState.update { it.copy(playing = true, playingGenerationId = historyGenerationId) }
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            val durationMillis = (samples.size.toDouble() / sampleRate.toDouble() * 1000.0).toLong()
            kotlinx.coroutines.delay(durationMillis.coerceAtLeast(200L))
            track.release()
            if (activeTrack == track) activeTrack = null
            _uiState.update { it.copy(playing = false, playingGenerationId = null) }
        }
    }

    fun stopAudio() {
        playJob?.cancel()
        playJob = null
        activeTrack?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        activeTrack = null
        _uiState.update { it.copy(playing = false, playingGenerationId = null) }
    }

    fun suggestedExportFileName(generation: GenerationEntity): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
        return "qwen3-tts-export-${LocalDateTime.now().format(formatter)}-${generation.generationId}.wav"
    }

    fun exportGenerationToUri(generation: GenerationEntity, uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { output ->
                        File(generation.wavPath).inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: error("Could not open media output")
                }
            }
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            savedFileName = null,
                            snackbarMessage = "Export complete",
                            status = "Exported WAV",
                            error = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            snackbarMessage = throwable.message ?: "Could not export WAV",
                            error = null,
                        )
                    }
                },
            )
        }
    }

    private fun ensureEngineLoaded(variant: ModelVariant): QwenEngine {
        val native = EngineHost.ensureLoaded(modelDir.absolutePath, variant.talkerName, selectedCpuThreadCount(), Synth.backendPreference(getApplication()))
        val caps = native.getModelCapabilities()
        _uiState.update {
            it.copy(
                loaded = true,
                cpuThreads = native.getCpuThreads(),
                activeBackendName = native.getActiveBackendName()?.takeIf { name -> name.isNotBlank() },
                supportsCloning = caps?.supportsCloning,
                speakerEmbeddingDim = caps?.speakerEmbeddingDim ?: 0,
            )
        }
        return native
    }

    private fun preferredCpuThreadCount(): Int {
        val available = Runtime.getRuntime().availableProcessors()
        return when {
            available >= 8 -> 6
            available >= 4 -> 4
            else -> available.coerceAtLeast(1)
        }
    }

    private fun selectedCpuThreadCount(): Int =
        _uiState.value.selectedCpuThreads.takeIf { it > 0 } ?: preferredCpuThreadCount()

    private fun selectedBackendOption(): BackendOption =
        backendOptionById(_uiState.value.selectedBackendId)

    private fun startBusy(
        status: String,
        downloading: Boolean = false,
        resetSynthesis: Boolean = false,
        synthesisEstimate: GenerationTimeEstimate? = null,
    ) {
        operationTickerJob?.cancel()
        val start = SystemClock.elapsedRealtime()
        _uiState.update {
            it.copy(
                busy = true,
                downloading = downloading,
                error = null,
                status = status,
                operationElapsedMillis = 0L,
                synthesisFrames = if (resetSynthesis) 0 else it.synthesisFrames,
                sampleCount = if (resetSynthesis) 0 else it.sampleCount,
                synthesisMillis = if (resetSynthesis) 0 else it.synthesisMillis,
                tokenizeMillis = if (resetSynthesis) 0 else it.tokenizeMillis,
                encodeMillis = if (resetSynthesis) 0 else it.encodeMillis,
                generateMillis = if (resetSynthesis) 0 else it.generateMillis,
                decodeMillis = if (resetSynthesis) 0 else it.decodeMillis,
                decodeFrames = if (resetSynthesis) 0 else it.decodeFrames,
                decodeSamples = if (resetSynthesis) 0 else it.decodeSamples,
                decodeGraphComputeMillis = if (resetSynthesis) 0 else it.decodeGraphComputeMillis,
                savedFileName = if (resetSynthesis) null else it.savedFileName,
                estimatedSynthesisMillis = synthesisEstimate?.totalMillis ?: if (resetSynthesis) null else it.estimatedSynthesisMillis,
                estimateSampleCount = synthesisEstimate?.sampleSize ?: if (resetSynthesis) 0 else it.estimateSampleCount,
            )
        }
        operationTickerJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(250L)
                val elapsed = SystemClock.elapsedRealtime() - start
                _uiState.update { current ->
                    if (current.busy) current.copy(operationElapsedMillis = elapsed) else current
                }
            }
        }
    }

    private fun stopBusyTicker() {
        operationTickerJob?.cancel()
        operationTickerJob = null
    }

    private fun estimateSynthesisMillis(text: String): GenerationTimeEstimate? {
        val ratios = generations.value
            .asSequence()
            .filter { it.synthesisMillis > 0L && it.text.isNotBlank() }
            .take(20)
            .map { generation ->
                generation.synthesisMillis.toDouble() / generation.text.length.coerceAtLeast(1).toDouble()
            }
            .filter { it.isFinite() && it > 0.0 }
            .sorted()
            .toList()

        if (ratios.size < 3) return null

        val trimmed = if (ratios.size >= 5) {
            ratios.drop(1).dropLast(1)
        } else {
            ratios
        }
        val millisPerCharacter = trimmed.average()
        val estimate = (millisPerCharacter * text.length.coerceAtLeast(1)).toLong()
            .coerceAtLeast(1_000L)
        return GenerationTimeEstimate(estimate, ratios.size)
    }

    private fun refreshModelState(status: String? = null) {
        val variant = selectedVariant()
        val ready = isModelReady(variant)
        stopBusyTicker()
        _uiState.update {
            it.copy(
                modelReady = ready,
                busy = false,
                downloading = false,
                downloadTotalBytes = variant.totalBytes,
                status = status ?: if (ready) "Model ready" else "Model not downloaded",
            )
        }
    }

    private fun selectedVariant(): ModelVariant =
        QwenModel.variantById(_uiState.value.selectedModelId)

    private fun isModelReady(variant: ModelVariant): Boolean =
        variant.files.all { file ->
            val local = File(modelDir, file.name)
            local.isFile && local.length() >= file.sizeBytes
        }

    private fun downloadFiles(variant: ModelVariant) {
        var completedBytes = 0L
        variant.files.forEachIndexed { index, file ->
            val target = File(modelDir, file.name)
            if (target.isFile && target.length() >= file.sizeBytes) {
                completedBytes += file.sizeBytes
                return@forEachIndexed
            }
            val url = file.url ?: error("${file.name} is a local experiment file. Use scripts/install_nvfp4_android.ps1 to sideload it.")
            val temp = File(modelDir, "${file.name}.download")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 30_000
            }

            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var fileBytes = 0L
                    var lastUpdate = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        fileBytes += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 250L) {
                            lastUpdate = now
                            val total = completedBytes + fileBytes
                            _uiState.update {
                                it.copy(
                                    downloadBytes = total,
                                    downloadProgress = total.toFloat() / variant.totalBytes.toFloat(),
                                    status = "Downloading ${index + 1}/${variant.files.size}: ${file.name}",
                                )
                            }
                        }
                    }
                }
            }
            connection.disconnect()

            if (temp.length() < file.sizeBytes) {
                temp.delete()
                error("Downloaded ${file.name} is incomplete")
            }
            if (target.exists() && !target.delete()) {
                temp.delete()
                error("Could not replace ${file.name}")
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                error("Could not save ${file.name}")
            }
            completedBytes += file.sizeBytes
        }
    }

    private fun persistGeneratedAudio(
        text: String,
        samples: FloatArray,
        sampleRate: Int,
        voiceName: String,
        voiceId: String?,
        synthesisMillis: Long,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            generationDir.mkdirs()
            val file = File(generationDir, "generation-${System.currentTimeMillis()}.wav")
            file.outputStream().use { writeWav(it, samples, sampleRate) }
            dao.insertGeneration(
                GenerationEntity(
                    text = text,
                    voiceId = voiceId,
                    voiceName = voiceName,
                    wavPath = file.absolutePath,
                    sampleRate = sampleRate,
                    sampleCount = samples.size,
                    synthesisMillis = synthesisMillis,
                ),
            )
        }
    }

    private fun makeUniqueVoiceName(name: String): String {
        val existing = voices.value.map { it.name }.toSet()
        if (name !in existing) return name
        var index = 2
        while ("$name $index" in existing) index += 1
        return "$name $index"
    }

    private fun writeWav(output: OutputStream, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        output.writeAscii("RIFF")
        output.writeIntLe(36 + dataBytes)
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeIntLe(16)
        output.writeShortLe(1)
        output.writeShortLe(1)
        output.writeIntLe(sampleRate)
        output.writeIntLe(sampleRate * 2)
        output.writeShortLe(2)
        output.writeShortLe(16)
        output.writeAscii("data")
        output.writeIntLe(dataBytes)
        samples.forEach { sample ->
            val clamped = sample.coerceIn(-1f, 1f)
            output.writeShortLe((clamped * 32767f).toInt())
        }
    }

    // ---- Mia additions -------------------------------------------------------------
    private val player = Player()
    private var previewJob: kotlinx.coroutines.Job? = null
    private val _preview = MutableStateFlow<String?>(null)
    val previewing = _preview.asStateFlow()

    val queue = BatchQueue(
        application, viewModelScope,
        voiceId = { _uiState.value.selectedVoiceId },
        languageId = { _uiState.value.selectedLanguageId },
    )

    private fun say(message: String) = _uiState.update { it.copy(snackbarMessage = message) }

    /** 0 = not downloaded, 1 = on disk, 2 = loaded in memory. */
    fun variantStatus(id: String): Int {
        val v = QwenModel.variantById(id)
        return when {
            !isModelReady(v) -> 0
            EngineHost.loadedTalker == v.talkerName -> 2
            else -> 1
        }
    }

    fun downloadAll(ids: List<String>) {
        if (_uiState.value.downloading) return
        viewModelScope.launch {
            startBusy(status = "Preparing model download", downloading = true)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    modelDir.mkdirs()
                    ids.map { QwenModel.variantById(it) }.filterNot { isModelReady(it) }.forEach { v ->
                        _uiState.update {
                            it.copy(downloadProgress = 0f, downloadBytes = 0L, downloadTotalBytes = v.totalBytes, status = "Downloading ${v.label}")
                        }
                        downloadFiles(v)
                    }
                }
            }
            result.fold(
                onSuccess = { stopBusyTicker(); refreshModelState("Model ready") },
                onFailure = { t ->
                    stopBusyTicker()
                    _uiState.update { it.copy(busy = false, downloading = false, status = "Download failed", error = t.message ?: "Download failed") }
                },
            )
        }
    }

    fun loadVariant(id: String) {
        if (_uiState.value.busy) return
        if (_uiState.value.selectedModelId != id) selectModel(id)
        if (EngineHost.loadedTalker == QwenModel.variantById(id).talkerName) return
        _uiState.update { it.copy(loaded = false) }
        loadModel()
    }

    fun unloadModel() {
        if (_uiState.value.busy) return
        stopAudio()
        EngineHost.release()
        _uiState.update { it.copy(loaded = false, activeBackendName = null, status = "Model unloaded") }
    }

    fun deleteVariant(id: String) {
        if (_uiState.value.busy) return
        val v = QwenModel.variantById(id)
        if (EngineHost.loadedTalker == v.talkerName) { stopAudio(); EngineHost.release() }
        File(modelDir, v.talkerName).delete()
        val othersExist = QwenModel.variants.any { it.id != id && File(modelDir, it.talkerName).isFile }
        if (!othersExist) v.files.forEach { File(modelDir, it.name).delete() }
        _uiState.update { it.copy(loaded = false, activeBackendName = null) }
        refreshModelState("Model deleted")
    }

    fun restartEngine() {
        if (_uiState.value.busy) return
        EngineHost.release()
        _uiState.update { it.copy(loaded = false, activeBackendName = null) }
    }

    private fun previewPhrase(languageId: Int) = when (languageId) {
        2050 -> "Hello! My name is Mia."
        2053 -> "Hallo! Ich heiße Mia."
        2054 -> "¡Hola! Me llamo Mia."
        2055 -> "你好，我是米娅。"
        2058 -> "こんにちは、ミアです。"
        2061 -> "Bonjour ! Je m'appelle Mia."
        2064 -> "안녕하세요, 저는 미아예요."
        else -> "Привет! Меня зовут Мия."
    }

    fun previewVoice(voiceId: String?) {
        val key = voiceId ?: "random"
        val wasThis = _preview.value == key
        previewJob?.cancel(); player.stop(); _preview.value = null
        if (wasThis) return
        val app = getApplication<Application>()
        val voice = Synth.resolve(app, voiceId)
        if (voice == null) { say("Голос не найден"); return }
        if (!Synth.isReady(app, voice.talker)) { say("Сначала скачайте нужную модель в Настройках"); return }
        _preview.value = key
        previewJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val lang = _uiState.value.selectedLanguageId
                val samples = Synth.run(app, previewPhrase(lang), voice, lang, null)
                if (_preview.value == key) {
                    player.play(samples, Synth.SAMPLE_RATE)
                    kotlinx.coroutines.delay(samples.size * 1000L / Synth.SAMPLE_RATE + 200)
                }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) say(t.message ?: "Preview failed")
            } finally {
                if (_preview.value == key) _preview.value = null
            }
        }
    }

    fun exportLatest(format: ExportFormat, share: Boolean) {
        val gen = generations.value.firstOrNull() ?: run { say("Сначала озвучьте текст"); return }
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val (samples, rate) = AudioExporter.readWav(File(gen.wavPath))
                val name = gen.text.take(24).ifBlank { "voice" } + "-" + (System.currentTimeMillis() % 100000)
                AudioExporter.export(app, samples, rate, name, format) to name
            }.fold(
                onSuccess = { (uri, name) ->
                    if (share) {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = format.mime
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        app.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else say("Сохранено: Music/Mia ai voice/$name.${format.ext}")
                },
                onFailure = { say(it.message ?: "Не удалось сохранить") },
            )
        }
    }

    override fun onCleared() {
        recorder.cancel()
        stopAudio()
        stopBusyTicker()
        if (!ApiServerService.isRunning && !MiaSettings.get(getApplication()).keepAlive) EngineHost.release()
        super.onCleared()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        MiaSettings.get(this)
        setContent { com.qwen.tts.android.ui.MiaApp() }
    }
}

private data class WavData(val samples: FloatArray, val sampleRate: Int)

private fun readWav(path: String): WavData {
    val bytes = File(path).readBytes()
    require(bytes.size > 44) { "WAV file is empty" }
    fun intLe(offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    fun shortLe(offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    var cursor = 12
    var sampleRate = 24_000
    var channels = 1
    var bits = 16
    var dataOffset = -1
    var dataSize = 0
    while (cursor + 8 <= bytes.size) {
        val id = String(bytes, cursor, 4, Charsets.US_ASCII)
        val size = intLe(cursor + 4)
        val payload = cursor + 8
        when (id) {
            "fmt " -> {
                channels = shortLe(payload + 2).coerceAtLeast(1)
                sampleRate = intLe(payload + 4)
                bits = shortLe(payload + 14)
            }
            "data" -> {
                dataOffset = payload
                dataSize = size
                break
            }
        }
        cursor = payload + size
    }
    require(dataOffset >= 0 && bits == 16) { "Only PCM16 WAV history items are supported" }
    val frameBytes = channels * 2
    val frameCount = dataSize / frameBytes
    val samples = FloatArray(frameCount)
    var offset = dataOffset
    for (i in 0 until frameCount) {
        var sum = 0f
        for (channel in 0 until channels) {
            val value = ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xff)).toShort()
            sum += value / 32768f
            offset += 2
        }
        samples[i] = sum / channels
    }
    return WavData(samples, sampleRate)
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return "%.1f %s".format(value, units[unitIndex])
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) "%d:%02d".format(Locale.US, minutes, seconds) else "%ds".format(Locale.US, seconds)
}

private fun formatSeconds(seconds: Double): String =
    "%.1fs".format(Locale.US, seconds.coerceAtLeast(0.0))

private fun formatDate(epochMillis: Long): String {
    val date = java.text.SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault())
    return date.format(java.util.Date(epochMillis))
}

private fun backendOptionById(id: String): BackendOption =
    backendOptions.firstOrNull { it.id == id } ?: defaultBackendOption

private fun OutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun OutputStream.writeIntLe(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
    write((value shr 16) and 0xff)
    write((value shr 24) and 0xff)
}

private fun OutputStream.writeShortLe(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
}
