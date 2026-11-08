package com.qwen.tts.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.qwen.tts.android.MainViewModel
import com.qwen.tts.android.QwenModel
import com.qwen.tts.android.data.*
import com.qwen.tts.android.engine.EngineHost
import com.qwen.tts.android.formatBytes
import com.qwen.tts.android.server.ApiServerService
import com.qwen.tts.android.server.ServerPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val screenMod = Modifier.fillMaxSize()

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        screenMod.fadeTop().verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 70.dp, bottom = 130.dp),
        content = content,
    )
}

/* glyphs for icon buttons */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.playGlyph(c: Color) {
    val p = Path().apply { moveTo(size.width * .15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * .15f, size.height); close() }
    drawPath(p, c)
}
private fun androidx.compose.ui.graphics.drawscope.DrawScope.stopGlyph(c: Color) =
    drawRect(c, Offset(size.width * .1f, size.height * .1f), Size(size.width * .8f, size.height * .8f))
private fun androidx.compose.ui.graphics.drawscope.DrawScope.saveGlyph(c: Color) {
    val s = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val w = size.width; val h = size.height
    val p = Path().apply { moveTo(w / 2, 0f); lineTo(w / 2, h * .66f); moveTo(w * .22f, h * .42f); lineTo(w / 2, h * .7f); lineTo(w * .78f, h * .42f); moveTo(0f, h); lineTo(w, h) }
    drawPath(p, c, style = s)
}
private fun androidx.compose.ui.graphics.drawscope.DrawScope.crossGlyph(c: Color) {
    val s = 2.dp.toPx()
    drawLine(c, Offset(0f, 0f), Offset(size.width, size.height), s, StrokeCap.Round)
    drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), s, StrokeCap.Round)
}

private val ruLang = mapOf("Chinese" to "Китайский", "English" to "Английский", "Japanese" to "Японский", "Korean" to "Корейский")
private fun PresetSpeaker.langRu(): String = nativeLanguage.replace(Regex("^(Chinese|English|Japanese|Korean)")) { ruLang[it.value] ?: it.value }

private val tonePhrases = listOf(
    "спокойно" to "Speak calmly and gently.",
    "радостно" to "Speak cheerfully, with a smile.",
    "шёпотом" to "Whispering, very soft and quiet voice.",
    "строго" to "Speak in a strict, serious tone.",
    "грустно" to "Speak sadly, with a low voice.",
)

/* ============================ voice pickers ============================ */

@Composable
private fun VoiceChoices(vm: MainViewModel, onPicked: () -> Unit) {
    val state by vm.uiState.collectAsState()
    val voices by vm.voices.collectAsState()
    Column {
        SelectRow("Случайный голос", "Base-модель, каждый раз новый", state.selectedVoiceId == null) { vm.selectVoice(null); onPicked() }
        voices.forEach { v -> SelectRow(v.name, "мой клон", state.selectedVoiceId == v.voiceId) { vm.selectVoice(v.voiceId); onPicked() } }
        SpeakerCatalog.presets.forEach { p ->
            SelectRow(p.displayName, "${p.gender.label}, ${p.langRu()}", state.selectedVoiceId == p.voiceId) { vm.selectVoice(p.voiceId); onPicked() }
        }
    }
}

@Composable
private fun LanguageChoices(vm: MainViewModel, onPicked: () -> Unit) {
    val state by vm.uiState.collectAsState()
    Column {
        LanguageCatalog.all.forEach { l -> SelectRow(l.name, null, state.selectedLanguageId == l.id) { vm.updateLanguage(l.id); onPicked() } }
    }
}

/** Title taps open a sheet; value taps expand the list inline. */
@Composable
private fun ExpandRow(
    title: String, sub: String, value: String, expanded: Boolean,
    onTitle: () -> Unit, onValue: () -> Unit, body: @Composable () -> Unit,
) {
    val c = LocalMia.current
    Row(Modifier.fillMaxWidth().padding(vertical = 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).tap(onTitle).padding(vertical = 14.dp)) { T(title); T(sub, 17, c.mute) }
        Row(Modifier.tap(onValue).padding(start = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            T(value, 22, display = true)
            Spacer(Modifier.width(8.dp))
            T(if (expanded) "⌃" else "⌄", 22, c.mute)
        }
    }
    AnimatedVisibility(expanded, enter = expandVertically(tween(380)), exit = shrinkVertically(tween(280))) { Column { body() } }
    HLine()
}

/* ================================ Studio ================================ */

@Composable
fun StudioScreen(vm: MainViewModel) {
    val c = LocalMia.current
    val settings = LocalSettings.current
    val sheet = LocalSheet.current
    val state by vm.uiState.collectAsState()
    val voices by vm.voices.collectAsState()
    val gens by vm.generations.collectAsState()
    var voiceOpen by remember { mutableStateOf(false) }
    var langOpen by remember { mutableStateOf(false) }
    var instrOpen by remember { mutableStateOf(false) }

    ScreenColumn {
        MiaField(state.text, { vm.updateText(it.take(2000)) }, "Текст для озвучки", singleLine = false, size = 25)
        T("${state.text.length} / 2000", 17, c.mute, display = true, modifier = Modifier.align(Alignment.End).padding(top = 4.dp, bottom = 6.dp))

        ExpandRow(
            "Голос", "любой голос, любой язык", resolveVoiceName(state.selectedVoiceId, voices), voiceOpen,
            onTitle = { sheet.show("Голос") { VoiceChoices(vm) { sheet.hide() } } },
            onValue = { voiceOpen = !voiceOpen; if (voiceOpen) langOpen = false },
        ) { VoiceChoices(vm) { voiceOpen = false } }

        ExpandRow(
            "Язык", "интонация и акцент", LanguageCatalog.byId(state.selectedLanguageId).name, langOpen,
            onTitle = { sheet.show("Язык") { LanguageChoices(vm) { sheet.hide() } } },
            onValue = { langOpen = !langOpen; if (langOpen) voiceOpen = false },
        ) { LanguageChoices(vm) { langOpen = false } }

        Row(Modifier.fillMaxWidth().tap { instrOpen = !instrOpen }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T("Инструкция"); T("тон и эмоция, работает не на всех моделях", 17, c.mute) }
            T(if (instrOpen) "⌃" else "⌄", 22, c.mute)
        }
        AnimatedVisibility(instrOpen, enter = expandVertically(tween(380)), exit = shrinkVertically(tween(280))) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                MiaField(settings.instruction, { settings.instruction = it }, "Например: спокойно, с лёгкой улыбкой")
                Chips(tonePhrases.map { it.first }, tonePhrases.firstOrNull { it.second == settings.instruction }?.first ?: "") { label ->
                    settings.instruction = tonePhrases.first { it.first == label }.second
                }
            }
        }
        HLine()

        val working = state.busy && !state.downloading
        val pct = if (state.maxAudioTokens > 0) (state.synthesisFrames * 100 / state.maxAudioTokens).coerceIn(0, 99) else 0
        Spacer(Modifier.height(18.dp))
        GlassButton(if (working) "Генерация… $pct%" else "Озвучить", { vm.synthesize() }, primary = true, enabled = !state.busy)
        if (working) T(state.status, 17, c.mute, modifier = Modifier.padding(top = 8.dp))
        state.error?.let { T(it, 18, c.red, modifier = Modifier.padding(top = 8.dp)) }

        val latest = gens.firstOrNull()
        if (latest != null) {
            Spacer(Modifier.height(14.dp))
            ResultPlayer(vm, latest, state.playing && state.playingGenerationId == latest.generationId || state.playing && state.playingGenerationId == null)
        }
        if (gens.size > 1) {
            Section("История")
            gens.drop(1).take(12).forEach { g ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(36, { if (state.playing && state.playingGenerationId == g.generationId) vm.stopAudio() else vm.playGeneration(g.generationId) }) {
                        if (state.playing && state.playingGenerationId == g.generationId) stopGlyph(it) else playGlyph(it)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { T(g.text, 20, maxLines = 1); T(g.voiceName, 16, c.mute) }
                    IconBtn(36, { vm.deleteGeneration(g) }) { crossGlyph(it) }
                }
                HLine()
            }
        }
    }
}

@Composable
private fun ResultPlayer(vm: MainViewModel, gen: com.qwen.tts.android.data.db.GenerationEntity, playing: Boolean) {
    val c = LocalMia.current
    val settings = LocalSettings.current
    val sheet = LocalSheet.current
    val bars by produceState(FloatArray(36) { 0.2f }, gen.wavPath) { value = withContext(Dispatchers.IO) { Waveform.read(gen.wavPath, 36) } }
    val progress = remember(gen.generationId) { Animatable(0f) }
    LaunchedEffect(playing, gen.generationId) {
        if (playing) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween((gen.sampleCount * 1000L / gen.sampleRate).toInt().coerceAtLeast(300), easing = LinearEasing))
        } else progress.snapTo(0f)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(46, { if (playing) vm.stopAudio() else vm.playGeneration(gen.generationId) }) { if (playing) stopGlyph(it) else playGlyph(it) }
        Spacer(Modifier.width(14.dp))
        Canvas(Modifier.weight(1f).height(44.dp)) {
            val n = bars.size
            val w = size.width / n
            bars.forEachIndexed { i, a ->
                val h = size.height * a
                val col = if ((i + 0.5f) / n <= progress.value) c.fg else c.line
                drawRect(col, Offset(i * w + w * 0.2f, (size.height - h) / 2), Size(w * 0.6f, h))
            }
        }
        Spacer(Modifier.width(14.dp))
        IconBtn(46, {
            sheet.show("Сохранить") {
                var fmt by remember { mutableStateOf(settings.exportFormat) }
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    T("Формат файла", 19, c.mute)
                    Chips(ExportFormat.entries.map { it.label }, fmt) { fmt = it; settings.exportFormat = it }
                    T("OGG (Opus) и M4A (AAC) меньше по размеру. Файлы попадают в Музыка/Mia ai voice.", 17, c.mute)
                    GlassButton("Сохранить на телефон", { vm.exportLatest(ExportFormat.of(fmt), false); sheet.hide() }, primary = true)
                    GlassButton("Поделиться", { vm.exportLatest(ExportFormat.of(fmt), true); sheet.hide() })
                }
            }
        }) { saveGlyph(it) }
    }
    HLine()
}

/* ================================ Voices ================================ */

@Composable
fun VoicesScreen(vm: MainViewModel) {
    val c = LocalMia.current
    val sheet = LocalSheet.current
    val state by vm.uiState.collectAsState()
    val voices by vm.voices.collectAsState()
    val previewing by vm.previewing.collectAsState()
    var filtersOpen by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf("Все") }
    var gender by remember { mutableStateOf("Все") }

    ScreenColumn {
        Section("Мои голоса")
        if (voices.isEmpty()) T("Пока нет клонов. Запишите голос или загрузите аудиофайл.", 19, c.mute, modifier = Modifier.padding(vertical = 10.dp))
        voices.forEach { v ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(38, { vm.previewVoice(v.voiceId) }) { if (previewing == v.voiceId) stopGlyph(it) else playGlyph(it) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).tap { vm.selectVoice(v.voiceId) }) {
                    T(v.name); T("клон, ${v.durationMillis / 1000} с" + if (state.selectedVoiceId == v.voiceId) ", выбран" else "", 17, c.mute)
                }
                IconBtn(38, { vm.deleteVoice(v) }) { crossGlyph(it) }
            }
            HLine()
        }
        Spacer(Modifier.height(14.dp))
        GlassButton("＋ Новый голос", { sheet.show("Новый голос") { NewVoiceSheet(vm) } })

        Section("Готовые голоса")
        Row(Modifier.fillMaxWidth().tap { filtersOpen = !filtersOpen }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Фильтры", 21, modifier = Modifier.weight(1f)); T(if (filtersOpen) "⌃" else "⌄", 22, c.mute)
        }
        AnimatedVisibility(filtersOpen, enter = expandVertically(tween(380)), exit = shrinkVertically(tween(280))) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                Chips(listOf("Все", "Китайский", "Английский", "Японский", "Корейский"), lang) { lang = it }
                Chips(listOf("Все", "Женский", "Мужской"), gender) { gender = it }
            }
        }
        HLine()
        SpeakerCatalog.presets.filter { p ->
            (lang == "Все" || p.nativeLanguage.startsWith(ruLang.entries.first { e -> e.value == lang }.key)) && (gender == "Все" || p.gender.label == gender)
        }.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(38, { vm.previewVoice(p.voiceId) }) { if (previewing == p.voiceId) stopGlyph(it) else playGlyph(it) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).tap { vm.selectVoice(p.voiceId) }) {
                    T(p.displayName); T("${p.gender.label}, ${p.langRu()}" + if (state.selectedVoiceId == p.voiceId) ", выбран" else "", 17, c.mute)
                }
            }
            HLine()
        }
    }
}

@Composable
private fun NewVoiceSheet(vm: MainViewModel) {
    val c = LocalMia.current
    val ctx = LocalContext.current
    val sheet = LocalSheet.current
    val rec by vm.recorderState.collectAsState()
    val state by vm.uiState.collectAsState()
    val voices by vm.voices.collectAsState()
    val initial = remember { voices.size }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(voices.size) { if (voices.size > initial) sheet.hide() }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.startVoiceRecording() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) vm.importVoiceFromUri(uri, name) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        MiaField(name, { name = it }, "Название голоса")
        if (rec.isRecording) {
            T("Идёт запись: ${rec.elapsedMillis / 1000} с", 24, c.red, display = true)
            T("Говорите 10–30 секунд чётко, без музыки и шума.", 18, c.mute)
            GlassButton("Остановить и создать голос", { vm.stopVoiceRecordingAndCreate(name) }, primary = true)
            GlassButton("Отмена", { vm.cancelVoiceRecording() })
        } else {
            GlassButton("Записать с микрофона", {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startVoiceRecording()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }, primary = true, enabled = !state.busy)
            GlassButton("Выбрать аудиофайл", { picker.launch(arrayOf("audio/*", "video/*", "application/ogg")) }, enabled = !state.busy)
            T("mp3, m4a, ogg, wav, голосовые сообщения. Нужно от 3 до 30 секунд чистой речи одного человека.", 17, c.mute)
            T("Для клонирования используется модель Base.", 17, c.mute)
        }
        if (state.busy) T(state.status, 19, c.mute)
        state.error?.let { T(it, 18, c.red) }
    }
}

/* ================================ Queue ================================ */

@Composable
fun QueueScreen(vm: MainViewModel) {
    val c = LocalMia.current
    val settings = LocalSettings.current
    val sheet = LocalSheet.current
    val ctx = LocalContext.current
    val items by vm.queue.items.collectAsState()
    val running by vm.queue.running.collectAsState()
    var fmt by remember { mutableStateOf(settings.exportFormat) }

    val txtPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching {
                val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                val title = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { q -> if (q.moveToFirst()) q.getString(0)?.substringBeforeLast('.') else null } ?: "Текст"
                vm.queue.add(title, text)
            }
        }
    }

    ScreenColumn {
        Section("Очередь")
        if (items.isEmpty()) T("Добавьте тексты или .txt файлы. Каждый станет отдельным аудиофайлом.", 19, c.mute, modifier = Modifier.padding(vertical = 10.dp))
        items.forEach { it ->
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(it.title, maxLines = 1)
                        T(
                            when (it.status) { "done" -> "готово: ${it.result}"; "error" -> "ошибка: ${it.result ?: ""}"; "working" -> "в работе"; else -> "ждёт" },
                            17, if (it.status == "error") c.red else c.mute, maxLines = 2,
                        )
                    }
                    if (it.status != "working") IconBtn(34, { vm.queue.remove(it.id) }) { crossGlyph(it) }
                }
                Spacer(Modifier.height(8.dp))
                DotProgress(it.progress)
            }
            HLine()
        }
        Spacer(Modifier.height(14.dp))
        GlassButton("＋ Добавить текст", {
            sheet.show("Новый текст") {
                var title by remember { mutableStateOf("") }
                var text by remember { mutableStateOf("") }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiaField(title, { title = it }, "Название (необязательно)")
                    MiaField(text, { text = it }, "Текст", singleLine = false)
                    GlassButton("Добавить", { vm.queue.add(title, text); sheet.hide() }, primary = true, enabled = text.isNotBlank())
                }
            }
        })
        Spacer(Modifier.height(10.dp))
        GlassButton("＋ Из .txt файлов", { txtPicker.launch(arrayOf("text/plain")) })

        Section("Экспорт пакета")
        Chips(ExportFormat.entries.map { it.label }, fmt) { fmt = it; settings.exportFormat = it }
        T("Голос и язык берутся из вкладки «Студия».", 17, c.mute, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(14.dp))
        if (running) GlassButton("Остановить", { vm.queue.stop() })
        else GlassButton("Запустить пакет", { vm.queue.start(ExportFormat.of(fmt)) }, primary = true, enabled = items.any { it.status == "waiting" || it.status == "error" })
        if (items.any { it.status == "done" }) {
            Spacer(Modifier.height(10.dp))
            GlassButton("Убрать готовые", { vm.queue.clearFinished() })
        }
    }
}

/* ================================ Settings ================================ */

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val c = LocalMia.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val sheet = LocalSheet.current
    val state by vm.uiState.collectAsState()
    val prefs = remember { ServerPrefs(ctx) }
    var apiOn by remember { mutableStateOf(prefs.enabled) }
    var lanOn by remember { mutableStateOf(prefs.lanAccess) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ApiServerService.sync(ctx) }
    fun ensureNotifThenSync() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notif.launch(Manifest.permission.POST_NOTIFICATIONS) else ApiServerService.sync(ctx)
    }
    val backendNames = listOf("Авто", "CPU", "GPU")
    val mask by produceState(1) { value = withContext(Dispatchers.IO) { EngineHost.compiledBackendMask() } }
    val statusText = listOf("не скачана", "на диске", "в памяти")

    ScreenColumn {
        Section("Модели")
        QwenModel.variants.forEach { v ->
            val st = vm.variantStatus(v.id).also { state.status }
            val title = if (v.id == QwenModel.customVoiceVariant.id) "CustomVoice" else "Base"
            val desc = if (v.id == QwenModel.customVoiceVariant.id) "готовые голоса" else "клонирование голоса"
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).tap { sheet.show(title) { ModelSheet(vm, v.id, title, desc) } }.padding(vertical = 14.dp)) {
                    T(title); T("$desc, ${statusText[st]}", 17, c.mute)
                }
                Box(Modifier.padding(horizontal = 14.dp).width(1.dp).fillMaxHeight().padding(vertical = 12.dp).background(c.line))
                Box(Modifier.tap {
                    when (st) { 0 -> vm.downloadAll(listOf(v.id)); 1 -> vm.loadVariant(v.id); else -> vm.unloadModel() }
                }.padding(vertical = 14.dp)) { T(when (st) { 0 -> "Скачать"; 1 -> "Загрузить"; else -> "Выгрузить" }, 21) }
            }
            HLine()
        }
        if (state.downloading) {
            Spacer(Modifier.height(10.dp)); T(state.status, 18, c.mute); Spacer(Modifier.height(6.dp)); DotProgress(state.downloadProgress)
        }

        Section("Работа")
        NavRow("Генерация", "температура, top-p, seed…") { sheet.show("Генерация") { GenerationSheet() } }
        NavRow("Процессор", "бэкенд и потоки", backendNames[settings.backend.coerceIn(0, 2)]) {
            sheet.show("Процессор") { ProcessorSheet(vm, mask, backendNames) }
        }
        SwitchRow("Работа в фоне", "модель в памяти, сервис с уведомлением", settings.keepAlive,
            onChecked = { settings.keepAlive = it; ensureNotifThenSync() },
            onOpen = { sheet.show("Работа в фоне") { BackgroundSheet { ensureNotifThenSync() } } })
        SwitchRow("Локальный API", "OpenAI-совместимый сервер", apiOn,
            onChecked = { apiOn = it; prefs.enabled = it; ensureNotifThenSync() },
            onOpen = { sheet.show("Локальный API") { ApiSheet(prefs) { ensureNotifThenSync() } } })
        SwitchRow("Доступ из Wi-Fi", "другие устройства в сети", lanOn,
            onChecked = { lanOn = it; prefs.lanAccess = it; if (prefs.enabled) ensureNotifThenSync() },
            onOpen = { sheet.show("Доступ из Wi-Fi") { WifiSheet(prefs) { lanOn = it; if (prefs.enabled) ensureNotifThenSync() } } })
        NavRow("Оформление", "тема, фон, стекло", null) { sheet.show("Оформление") { AppearanceSheet() } }
    }
}

@Composable
private fun ModelSheet(vm: MainViewModel, id: String, title: String, desc: String) {
    val c = LocalMia.current
    val sheet = LocalSheet.current
    val state by vm.uiState.collectAsState()
    val st = vm.variantStatus(id).also { state.status }
    val v = QwenModel.variantById(id)
    Column {
        KeyValue("Назначение", desc)
        KeyValue("Статус", listOf("не скачана", "на диске", "в памяти")[st])
        KeyValue("Размер", formatBytes(v.totalBytes))
        KeyValue("Файл", v.talkerName)
        if (state.downloading) { Spacer(Modifier.height(12.dp)); T(state.status, 18, c.mute); Spacer(Modifier.height(6.dp)); DotProgress(state.downloadProgress) }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (st) {
                0 -> GlassButton("Скачать", { vm.downloadAll(listOf(id)) }, primary = true, enabled = !state.busy)
                1 -> GlassButton("Загрузить в память", { vm.loadVariant(id); sheet.hide() }, primary = true, enabled = !state.busy)
                else -> GlassButton("Выгрузить из памяти", { vm.unloadModel() }, primary = true, enabled = !state.busy)
            }
            if (st > 0) GlassButton("Удалить файл с телефона", { vm.deleteVariant(id) }, enabled = !state.busy)
        }
        state.error?.let { T(it, 18, c.red, modifier = Modifier.padding(top = 10.dp)) }
    }
}

@Composable
private fun GenerationSheet() {
    val s = LocalSettings.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SliderRow("Температура", s.temperature, 0.1f, 1.5f, 0.05f) { s.temperature = it }
        SliderRow("Top-p", s.topP, 0.1f, 1f, 0.05f) { s.topP = it }
        SliderRow("Top-k", s.topK.toFloat(), 1f, 100f, 1f, 0) { s.topK = it.toInt() }
        SliderRow("Штраф повторов", s.repetition, 1f, 1.5f, 0.01f) { s.repetition = it }
        SliderRow("Макс. длина фразы, с", s.maxSeconds.toFloat(), 5f, 40f, 1f, 0) { s.maxSeconds = it.toInt() }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T("Фиксировать seed"); T("один и тот же голос между запусками", 17, LocalMia.current.mute) }
            MiaSwitch(s.seedFixed) { s.seedFixed = it }
        }
        if (s.seedFixed) MiaField(s.seed.toString(), { s.seed = it.filter { ch -> ch.isDigit() }.take(9).toLongOrNull() ?: 0L }, "Seed", keyboard = KeyboardType.Number)
        GlassButton("Сбросить по умолчанию", { s.resetGeneration() })
    }
}

@Composable
private fun ProcessorSheet(vm: MainViewModel, mask: Int, names: List<String>) {
    val c = LocalMia.current
    val s = LocalSettings.current
    val state by vm.uiState.collectAsState()
    val gpuOk = (mask and 2) != 0
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        T("Бэкенд вычислений", 19, c.mute)
        Chips(names, names[s.backend.coerceIn(0, 2)], enabledOf = { it != "GPU" || gpuOk }) { s.backend = names.indexOf(it); vm.restartEngine() }
        T(
            if (gpuOk) "GPU экспериментальный: при сбое переключитесь на CPU." else "В этой сборке нет GPU-бэкенда (Vulkan), доступен только CPU.",
            17, c.mute,
        )
        val threads = if (s.threads > 0) s.threads else EngineHost.defaultThreads()
        SliderRow("Потоки CPU", threads.toFloat(), 1f, 8f, 1f, 0) { vm.updateCpuThreads(it.toInt()) }
        GlassButton("Потоки: авто", { s.threads = 0; vm.restartEngine() })
        state.activeBackendName?.let { KeyValue("Сейчас работает", it) }
    }
}

@Composable
private fun BackgroundSheet(onChanged: () -> Unit) {
    val c = LocalMia.current
    val s = LocalSettings.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T("Держать модель в памяти"); T("сервис с уведомлением, система не выгрузит приложение", 17, c.mute) }
            MiaSwitch(s.keepAlive) { s.keepAlive = it; onChanged() }
        }
        HLine()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T("Прогрев при старте"); T("модель загружается сразу, первая фраза без задержки", 17, c.mute) }
            MiaSwitch(s.warmStart) { s.warmStart = it; onChanged() }
        }
        HLine()
        T("Если телефон агрессивно убивает фоновые приложения (Xiaomi, Huawei), разрешите Mia ai voice работу без ограничений в настройках батареи.", 17, c.mute)
    }
}

@Composable
private fun ApiSheet(prefs: ServerPrefs, onApply: () -> Unit) {
    val c = LocalMia.current
    val clip = LocalClipboardManager.current
    val server by ApiServerService.state.collectAsState()
    var host by remember { mutableStateOf(prefs.bindHost) }
    var port by remember { mutableStateOf(prefs.port.toString()) }
    var key by remember { mutableStateOf(prefs.apiKey) }
    var voice by remember { mutableStateOf(prefs.defaultVoice) }
    var lang by remember {
        mutableStateOf(prefs.defaultLanguageId.let { id -> if (id < 0) "auto" else LanguageCatalog.all.firstOrNull { it.id == id }?.code ?: id.toString() })
    }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KeyValue("Состояние", if (server.running) "работает" else "выключен")
        server.url?.let { KeyValue("Адрес", it) }
        server.error?.let { T(it, 18, c.red) }
        T("Адрес привязки (127.0.0.1 — только телефон, 0.0.0.0 — вся сеть)", 17, c.mute, modifier = Modifier.padding(top = 10.dp))
        MiaField(host, { host = it }, "127.0.0.1", keyboard = KeyboardType.Uri)
        T("Порт", 17, c.mute, modifier = Modifier.padding(top = 10.dp))
        MiaField(port, { port = it.filter(Char::isDigit).take(5) }, "8765", keyboard = KeyboardType.Number)
        T("API-ключ", 17, c.mute, modifier = Modifier.padding(top = 10.dp))
        MiaField(key, { key = it }, "ключ")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { GlassButton("Копировать", { clip.setText(AnnotatedString(key)) }) }
            Box(Modifier.weight(1f)) { GlassButton("Новый ключ", { key = prefs.regenerateKey() }) }
        }
        T("Голос по умолчанию (vivian, serena, имя вашего клона…)", 17, c.mute, modifier = Modifier.padding(top = 10.dp))
        MiaField(voice, { voice = it }, "vivian")
        T("Язык по умолчанию (ru, en, zh, ja, ko, de, fr, es или auto)", 17, c.mute, modifier = Modifier.padding(top = 10.dp))
        MiaField(lang, { lang = it }, "auto")
        error?.let { T(it, 18, c.red) }
        Spacer(Modifier.height(10.dp))
        GlassButton("Применить и перезапустить", {
            val p = port.toIntOrNull()
            val l = LanguageCatalog.parse(lang)
            when {
                p == null || p !in 1024..65535 -> error = "Порт должен быть от 1024 до 65535"
                l == null -> error = "Неизвестный язык: $lang"
                else -> {
                    error = null
                    prefs.bindHost = host; prefs.port = p; prefs.apiKey = key; prefs.defaultVoice = voice.trim().ifBlank { "vivian" }; prefs.defaultLanguageId = l
                    onApply()
                }
            }
        }, primary = true)
        T("Пример: curl http://127.0.0.1:${port.ifBlank { "8765" }}/v1/audio/speech -H \"Authorization: Bearer ключ\" -H \"Content-Type: application/json\" -d '{\"input\":\"Привет\",\"voice\":\"$voice\"}' -o out.wav", 16, c.mute)
    }
}

@Composable
private fun WifiSheet(prefs: ServerPrefs, onChanged: (Boolean) -> Unit) {
    val c = LocalMia.current
    var on by remember { mutableStateOf(prefs.lanAccess) }
    val addrs = remember { ApiServerService.localAddresses() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T("Разрешить подключения из сети"); T("сервер слушает 0.0.0.0", 17, c.mute) }
            MiaSwitch(on) { on = it; prefs.lanAccess = it; onChanged(it) }
        }
        HLine()
        if (addrs.isEmpty()) T("Адрес в сети не найден: подключитесь к Wi-Fi.", 18, c.mute)
        addrs.forEach { KeyValue("Адрес", "http://$it:${prefs.port}/v1") }
        T("Любое устройство в вашей сети сможет обращаться к серверу, но только с API-ключом. Не включайте в публичных сетях.", 17, c.mute)
    }
}

@Composable
private fun AppearanceSheet() {
    val c = LocalMia.current
    val s = LocalSettings.current
    val themes = listOf("auto" to "Авто", "dark" to "Тёмная", "light" to "Светлая")
    val bgs = listOf("space" to "Космос", "clean" to "Чистый")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        T("Тема", 19, c.mute)
        Chips(themes.map { it.second }, themes.first { it.first == s.theme }.second) { l -> s.theme = themes.first { it.second == l }.first }
        T("Фон", 19, c.mute)
        Chips(bgs.map { it.second }, bgs.first { it.first == s.background }.second) { l -> s.background = bgs.first { it.second == l }.first }
        HLine()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T("Стеклянные кнопки"); T("размытый фон под кнопками и панелью", 17, c.mute) }
            MiaSwitch(s.glass) { s.glass = it }
        }
    }
}

/* =============================== Onboarding =============================== */

@Composable
fun Onboarding(vm: MainViewModel, onDone: () -> Unit) {
    val c = LocalMia.current
    val ctx = LocalContext.current
    val state by vm.uiState.collectAsState()
    val ids = QwenModel.variants.map { it.id }
    val ready = ids.all { vm.variantStatus(it).also { _ -> state.status } > 0 }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        T("Mia ai voice", 50, display = true, weight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        T("Голоса работают прямо на телефоне, без интернета. Один раз нужно скачать модели.", 21, c.mute)
        Spacer(Modifier.height(24.dp))
        QwenModel.variants.forEach { v ->
            val st = vm.variantStatus(v.id).also { state.status }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(if (v.id == QwenModel.customVoiceVariant.id) "CustomVoice" else "Base")
                    T(if (v.id == QwenModel.customVoiceVariant.id) "готовые голоса" else "клонирование голоса", 17, c.mute)
                }
                T(if (st > 0) "готово" else formatBytes(v.totalBytes), 20, if (st > 0) c.fg else c.mute, display = true)
            }
            HLine()
        }
        Spacer(Modifier.height(14.dp))
        if (state.downloading) { T(state.status, 18, c.mute); Spacer(Modifier.height(6.dp)); DotProgress(state.downloadProgress); Spacer(Modifier.height(10.dp)) }
        state.error?.let { T(it, 18, c.red); Spacer(Modifier.height(8.dp)) }
        Box(Modifier.tap { mic.launch(Manifest.permission.RECORD_AUDIO) }.padding(vertical = 6.dp)) { T("Разрешить микрофон (для записи своего голоса)", 18, c.mute) }
        Spacer(Modifier.height(10.dp))
        GlassButton(
            when { ready -> "Войти"; state.downloading -> "Скачиваю…"; else -> "Скачать и начать" },
            { if (ready) onDone() else vm.downloadAll(ids) },
            primary = true, enabled = !state.downloading,
        )
        Box(Modifier.fillMaxWidth().tap { onDone() }.padding(top = 14.dp), contentAlignment = Alignment.Center) { T("Пропустить", 19, c.mute) }
    }
}
