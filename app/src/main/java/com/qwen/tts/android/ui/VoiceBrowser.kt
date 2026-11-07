package com.qwen.tts.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qwen.tts.android.data.Gender
import com.qwen.tts.android.data.SpeakerCatalog
import com.qwen.tts.android.data.db.VoiceProfileEntity

/** Filterable list of the built-in CustomVoice speakers (language + gender chips). */
@Composable
fun PresetVoiceBrowser(selectedVoiceId: String?, onSelect: (String) -> Unit) {
    var language by remember { mutableStateOf<String?>(null) }
    var gender by remember { mutableStateOf<Gender?>(null) }
    val languages = remember { SpeakerCatalog.presets.map { it.nativeLanguage.substringBefore(" ") }.distinct() }
    val filtered = SpeakerCatalog.presets.filter {
        (language == null || it.nativeLanguage.startsWith(language!!)) && (gender == null || it.gender == gender)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.horizontalChips(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = language == null, onClick = { language = null }, label = { Text("Все языки") })
            languages.forEach { l ->
                FilterChip(selected = language == l, onClick = { language = if (language == l) null else l }, label = { Text(l) })
            }
        }
        Row(
            modifier = Modifier.horizontalChips(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = gender == null, onClick = { gender = null }, label = { Text("Любой пол") })
            Gender.values().forEach { g ->
                FilterChip(selected = gender == g, onClick = { gender = if (gender == g) null else g }, label = { Text(g.label) })
            }
        }
        if (filtered.isEmpty()) {
            Text("Нет голосов по этому фильтру", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        filtered.forEach { p ->
            VoiceItem(
                title = p.displayName,
                subtitle = "${p.gender.label} · ${p.nativeLanguage} · ${p.description}",
                selected = selectedVoiceId == p.voiceId,
                onClick = { onSelect(p.voiceId) },
            )
        }
    }
}

@Composable
private fun Modifier.horizontalChips(): Modifier =
    this.fillMaxWidth().horizontalScroll(rememberScrollState())

@Composable
fun VoiceItem(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Default.RecordVoiceOver, contentDescription = "Selected", modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerSheetV2(
    voices: List<VoiceProfileEntity>,
    selectedVoiceId: String?,
    onDismiss: () -> Unit,
    onVoiceSelected: (String?) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Выбор голоса", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            VoiceItem(
                title = "Случайный голос",
                subtitle = "Base-модель без выбора: каждый раз новый голос",
                selected = selectedVoiceId == null,
                onClick = { onVoiceSelected(null) },
            )
            Text("Готовые голоса (модель CustomVoice)", style = MaterialTheme.typography.titleSmall)
            PresetVoiceBrowser(selectedVoiceId, onSelect = { onVoiceSelected(it) })
            if (voices.isNotEmpty()) {
                Text("Мои голоса (клоны)", style = MaterialTheme.typography.titleSmall)
                voices.forEach { v ->
                    VoiceItem(v.name, "Клон · ${v.durationMillis / 1000} с", selectedVoiceId == v.voiceId) { onVoiceSelected(v.voiceId) }
                }
            }
        }
    }
}
