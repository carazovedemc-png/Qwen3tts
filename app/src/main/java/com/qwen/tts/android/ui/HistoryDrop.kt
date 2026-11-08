package com.qwen.tts.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.qwen.tts.android.MainViewModel
import com.qwen.tts.android.data.ExportFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun DrawScope.historyGlyph(c: Color) {
    val s = 2.dp.toPx()
    val r = size.minDimension / 2f - s
    drawCircle(c, r, center, style = Stroke(s))
    drawLine(c, center, Offset(center.x, center.y - r * 0.6f), s, StrokeCap.Round)
    drawLine(c, center, Offset(center.x + r * 0.45f, center.y + r * 0.2f), s, StrokeCap.Round)
}

/** Panel that slides down from the top edge and lists every generated voice-over with play / save / delete. */
@Composable
fun HistoryDrop(vm: MainViewModel, open: Boolean, onClose: () -> Unit) {
    val c = LocalMia.current
    val settings = LocalSettings.current
    val state by vm.uiState.collectAsState()
    val gens by vm.generations.collectAsState()
    var fmt by remember { mutableStateOf(settings.exportFormat) }
    val fmtDate = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }

    BackHandler(open) { onClose() }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).tap(onClose))
        }
        AnimatedVisibility(
            open,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(spring(dampingRatio = 0.72f, stiffness = 300f)) { -it },
            exit = slideOutVertically(tween(260)) { -it },
        ) {
            val maxH = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
            val shape = RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp)
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxH).clip(shape).background(c.bg2).border(1.dp, c.line, shape)
                    .statusBarsPadding().padding(horizontal = 22.dp).padding(top = 12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    T("История", 30, display = true, weight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
                    T("${gens.size}", 22, c.mute, display = true)
                }
                Spacer(Modifier.height(10.dp))
                Chips(ExportFormat.entries.map { it.label }, fmt) { fmt = it; settings.exportFormat = it }
                Spacer(Modifier.height(10.dp))
                if (gens.isEmpty()) {
                    T("Пока пусто. Озвученные тексты появятся здесь.", 19, c.mute, modifier = Modifier.padding(vertical = 18.dp))
                } else {
                    GlassButton("Скачать всё (${gens.size})", { vm.exportAll(ExportFormat.of(fmt)) })
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        gens.forEach { g ->
                            val playing = state.playing && state.playingGenerationId == g.generationId
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconBtn(38, { if (playing) vm.stopAudio() else vm.playGeneration(g.generationId) }) {
                                    if (playing) stopGlyph(it) else playGlyph(it)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    T(g.text, 20, maxLines = 2)
                                    T("${g.voiceName}, ${g.sampleCount / g.sampleRate.coerceAtLeast(1)} с, ${fmtDate.format(Date(g.createdAt))}", 16, c.mute, maxLines = 1)
                                }
                                Spacer(Modifier.width(8.dp))
                                IconBtn(38, { vm.exportGeneration(g, ExportFormat.of(fmt), false) }) { saveGlyph(it) }
                                Spacer(Modifier.width(8.dp))
                                IconBtn(38, { vm.deleteGeneration(g) }) { crossGlyph(it) }
                            }
                            HLine()
                        }
                    }
                }
                // handle: tap or swipe up to close
                Box(
                    Modifier.fillMaxWidth().tap(onClose).pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total < -40f) onClose() }) { _, dy -> total += dy }
                    }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(44.dp, 4.dp).clip(CircleShape).background(c.line))
                }
            }
        }
    }
}
