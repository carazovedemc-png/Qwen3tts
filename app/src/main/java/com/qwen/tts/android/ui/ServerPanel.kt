package com.qwen.tts.android.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qwen.tts.android.data.SpeakerCatalog
import com.qwen.tts.android.server.ApiServerService
import com.qwen.tts.android.server.ServerPrefs

@Composable
fun ServerPanel() {
    val context = LocalContext.current
    val prefs = remember { ServerPrefs(context) }
    val state by ApiServerService.state.collectAsState()
    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    var lan by remember { mutableStateOf(prefs.lanAccess) }
    var defaultVoice by remember { mutableStateOf(prefs.defaultVoice) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ApiServerService.start(context)
    }

    fun copy(label: String, value: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, value))
    }

    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Локальный API (OpenAI-совместимый)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.running) "Работает" else "Выключен",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.running, onCheckedChange = { on ->
                    if (!on) ApiServerService.stop(context)
                    else if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else ApiServerService.start(context)
                })
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Text("Base URL", style = MaterialTheme.typography.labelMedium)
            val url = state.url ?: "http://${if (lan) "<IP телефона>" else "127.0.0.1"}:${prefs.port}/v1"
            SelectionContainer { Text(url, fontFamily = FontFamily.Monospace) }

            Text("API key", style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(apiKey, fontFamily = FontFamily.Monospace) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { copy("API key", apiKey) }, modifier = Modifier.weight(1f)) { Text("Копировать ключ") }
                OutlinedButton(onClick = { apiKey = prefs.regenerateKey() }, modifier = Modifier.weight(1f)) { Text("Новый ключ") }
            }
            OutlinedButton(onClick = { copy("Base URL", url) }, modifier = Modifier.fillMaxWidth()) { Text("Копировать URL") }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Доступ из Wi-Fi сети")
                    Text(
                        "Выкл — только приложения на этом телефоне. Применится при следующем запуске.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = lan, onCheckedChange = { lan = it; prefs.lanAccess = it })
            }

            Text("Голос по умолчанию для API", style = MaterialTheme.typography.labelMedium)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SpeakerCatalog.presets.forEach { p ->
                    FilterChip(
                        selected = defaultVoice == p.key,
                        onClick = { defaultVoice = p.key; prefs.defaultVoice = p.key },
                        label = { Text(p.displayName) },
                    )
                }
            }

            Text(
                "Пример:\ncurl ${url}/audio/speech -H \"Authorization: Bearer <key>\" \\\n  -H \"Content-Type: application/json\" \\\n  -d '{\"input\":\"你好\",\"voice\":\"vivian\"}' -o out.wav",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
