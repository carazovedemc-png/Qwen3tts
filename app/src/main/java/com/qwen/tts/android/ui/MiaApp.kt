package com.qwen.tts.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qwen.tts.android.MainViewModel
import com.qwen.tts.android.data.MiaSettings
import com.qwen.tts.android.server.ApiServerService

@Composable
fun MiaApp(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    val settings = remember { MiaSettings.get(ctx) }
    val dark = when (settings.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val colors = if (dark) DarkColors else LightColors
    val fonts = rememberFonts()
    val sheet = remember { SheetController() }
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !dark }
    }
    LaunchedEffect(Unit) { ApiServerService.sync(ctx) }

    CompositionLocalProvider(LocalMia provides colors, LocalFonts provides fonts, LocalSettings provides settings, LocalSheet provides sheet) {
        Root(vm, settings, sheet)
    }
}

@Composable
private fun Root(vm: MainViewModel, settings: MiaSettings, sheet: SheetController) {
    val c = LocalMia.current
    val state by vm.uiState.collectAsState()
    val skyBackdrop = rememberBackdrop()
    var tab by rememberSaveable { mutableStateOf(0) }
    var lastPill by rememberSaveable { mutableStateOf(0) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tab) { if (tab < 3) lastPill = tab }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.fillMaxSize().backdropSource(skyBackdrop)) {
            if (settings.background == "space") PixelSky(Modifier.fillMaxSize(), color = c.fg)
        }
        CompositionLocalProvider(LocalSkyBackdrop provides skyBackdrop) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = 260f)) { dir * it / 5 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(220)) { -dir * it / 6 } + fadeOut(tween(160)))
                },
                label = "tabs",
            ) { t ->
                when (t) {
                    0 -> StudioScreen(vm)
                    1 -> VoicesScreen(vm)
                    2 -> QueueScreen(vm)
                    else -> SettingsScreen(vm)
                }
            }
            // title + history button; swipe down on this strip also opens the history
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 22.dp, end = 16.dp, top = 12.dp)
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total > 50f) historyOpen = true }) { _, dy -> total += dy }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T("Mia ai voice", 30, display = true, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconBtn(40, { historyOpen = true }) { historyGlyph(it) }
            }
        }
        DotBottomBar(
            selectedIndex = lastPill,
            onSelect = { tab = it },
            onSettingsClick = { tab = 3 },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            markVisible = tab != 3,
            settingsActive = tab == 3,
            dark = c.dark,
        )
        CompositionLocalProvider(LocalSkyBackdrop provides skyBackdrop) { HistoryDrop(vm, historyOpen) { historyOpen = false } }
        SheetHost(sheet)
        Toast(state.snackbarMessage) { vm.clearSnackbarMessage() }
        if (!settings.onboarded) {
            Onboarding(vm) { settings.onboarded = true }
        }
    }
}
