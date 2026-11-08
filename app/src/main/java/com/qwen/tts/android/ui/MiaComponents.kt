package com.qwen.tts.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun Modifier.tap(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

@Composable
fun HLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(LocalMia.current.line))

@Composable
fun Section(title: String) {
    Spacer(Modifier.height(22.dp))
    T(title, 18, LocalMia.current.mute)
    Spacer(Modifier.height(4.dp))
}

/** Whole row is the tap target (title, subtitle, value and arrow). */
@Composable
fun NavRow(title: String, sub: String? = null, value: String? = null, onClick: () -> Unit) {
    val c = LocalMia.current
    Row(Modifier.fillMaxWidth().tap(onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title)
            if (sub != null) T(sub, 17, c.mute)
        }
        if (value != null) { T(value, 21, c.mute); Spacer(Modifier.width(8.dp)) }
        T("›", 26, c.mute)
    }
    HLine()
}

@Composable
fun MiaSwitch(checked: Boolean, onChecked: (Boolean) -> Unit) {
    val c = LocalMia.current
    val track by animateColorAsState(if (checked) c.red else c.line, tween(250), label = "track")
    val x by animateDpAsState(if (checked) 22.dp else 3.dp, spring(dampingRatio = 0.5f, stiffness = 420f), label = "thumb")
    Box(
        Modifier.size(48.dp, 28.dp).clip(CircleShape).background(track).tap { onChecked(!checked) },
    ) {
        Box(Modifier.offset(x, 3.dp).size(22.dp).clip(CircleShape).background(c.fg))
    }
}

/** Title (taps open details) | vertical divider | switch. */
@Composable
fun SwitchRow(title: String, sub: String, checked: Boolean, onChecked: (Boolean) -> Unit, onOpen: () -> Unit) {
    val c = LocalMia.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).tap(onOpen).padding(vertical = 14.dp)) {
            T(title)
            T(sub, 17, c.mute)
        }
        Box(Modifier.padding(horizontal = 14.dp).width(1.dp).fillMaxHeight().padding(vertical = 12.dp).background(c.line))
        MiaSwitch(checked, onChecked)
    }
    HLine()
}

@Composable
fun Chip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalMia.current
    val bg by animateColorAsState(if (selected) c.fg else Color.Transparent, tween(220), label = "chip")
    Box(
        Modifier.clip(CircleShape).background(bg)
            .border(1.dp, if (selected) c.fg else c.line, CircleShape)
            .tap { if (enabled) onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        T(text, 20, if (selected) c.bg else if (enabled) c.mute else c.line)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(options: List<String>, selected: String, enabledOf: (String) -> Boolean = { true }, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { Chip(it, it == selected, enabledOf(it)) { onSelect(it) } }
    }
}

/** Nothing-style dotted slider: filled dots + red bar thumb. */
@Composable
fun DotSlider(value: Float, min: Float, max: Float, step: Float, onChange: (Float) -> Unit) {
    val c = LocalMia.current
    val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
    val latest by rememberUpdatedState(onChange)
    Canvas(
        Modifier.fillMaxWidth().height(26.dp).pointerInput(min, max, step) {
            fun at(x: Float) {
                val f = (x / size.width).coerceIn(0f, 1f)
                val raw = min + f * (max - min)
                latest((Math.round(raw / step) * step).coerceIn(min, max))
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                at(down.position.x)
                drag(down.id) { at(it.position.x); it.consume() }
            }
        },
    ) {
        val gap = 9.dp.toPx()
        val n = (size.width / gap).toInt().coerceAtLeast(2)
        val cy = size.height / 2
        for (i in 0..n) {
            val x = i * size.width / n
            drawCircle(if (x / size.width <= frac) c.fg else c.line, 2.2.dp.toPx(), Offset(x, cy))
        }
        val tx = frac * size.width
        drawRoundRect(c.red, Offset(tx - 4.dp.toPx(), 1.dp.toPx()), Size(8.dp.toPx(), size.height - 2.dp.toPx()), CornerRadius(4.dp.toPx()))
    }
}

@Composable
fun SliderRow(label: String, value: Float, min: Float, max: Float, step: Float, decimals: Int = 2, onChange: (Float) -> Unit) {
    val c = LocalMia.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T(label, 20, c.mute)
            T(if (decimals == 0) value.roundToInt().toString() else "%.${decimals}f".format(value), 22, display = true)
        }
        DotSlider(value, min, max, step, onChange)
    }
}

@Composable
fun DotProgress(frac: Float, modifier: Modifier = Modifier) {
    val c = LocalMia.current
    val f by animateFloatAsState(frac.coerceIn(0f, 1f), tween(300), label = "progress")
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val gap = 8.dp.toPx()
        val n = (size.width / gap).toInt().coerceAtLeast(2)
        for (i in 0..n) {
            val x = i * size.width / n
            drawCircle(if (x / size.width <= f) c.fg else c.line, 1.8.dp.toPx(), Offset(x, size.height / 2))
        }
    }
}

@Composable
fun MiaField(
    value: String, onValue: (String) -> Unit, placeholder: String = "", singleLine: Boolean = true,
    size: Int = 21, keyboard: KeyboardType = KeyboardType.Text, modifier: Modifier = Modifier,
) {
    val c = LocalMia.current
    val fonts = LocalFonts.current
    Column(modifier) {
        BasicTextField(
            value = value, onValueChange = onValue, singleLine = singleLine,
            textStyle = TextStyle(color = c.fg, fontSize = size.sp, lineHeight = (size * 1.2f).sp, fontFamily = fonts.ui),
            cursorBrush = SolidColor(c.red),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            decorationBox = { inner ->
                Box { if (value.isEmpty()) T(placeholder, size, c.mute); inner() }
            },
        )
        HLine()
    }
}

/** Frosted (or, if glass is off, solid) button. */
@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    val c = LocalMia.current
    val settings = LocalSettings.current
    val shape = RoundedCornerShape(20.dp)
    val base = modifier.fillMaxWidth().height(54.dp)
    val styled = if (primary) base.clip(shape).background(if (enabled) c.fg else c.line)
    else base.glass(LocalSkyBackdrop.current, shape, enabled = settings.glass)
    Box(styled.tap { if (enabled) onClick() }, contentAlignment = Alignment.Center) {
        T(text, 24, if (primary) c.bg else c.fg, weight = FontWeight.SemiBold)
    }
}

/** Round icon button with a drawn glyph. */
@Composable
fun IconBtn(size: Int = 44, onClick: () -> Unit, glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit) {
    val c = LocalMia.current
    Box(
        Modifier.size(size.dp).glass(LocalSkyBackdrop.current, CircleShape, enabled = LocalSettings.current.glass).tap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size((size * 0.42f).dp)) { this.glyph(c.fg) }
    }
}

/* ---- bottom sheet ---------------------------------------------------------------------- */

class SheetController {
    var title by mutableStateOf("")
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    var open by mutableStateOf(false)
    fun show(title: String, content: @Composable () -> Unit) { this.title = title; this.content = content; open = true }
    fun hide() { open = false }
}

val LocalSheet = staticCompositionLocalOf { SheetController() }

@Composable
fun SheetHost(ctl: SheetController) {
    val c = LocalMia.current
    BackHandler(ctl.open) { ctl.hide() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(ctl.open, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).tap { ctl.hide() })
        }
        AnimatedVisibility(
            ctl.open,
            enter = slideInVertically(spring(dampingRatio = 0.72f, stiffness = 300f)) { it },
            exit = slideOutVertically(tween(260)) { it },
        ) {
            val maxH = (LocalConfiguration.current.screenHeightDp * 0.86f).dp
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxH)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(c.bg2)
                    .border(1.dp, c.line, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding().imePadding()
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 4.dp).clip(CircleShape).background(c.line))
                Spacer(Modifier.height(14.dp))
                T(ctl.title, 30, display = true, weight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                ctl.content?.invoke()
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    val c = LocalMia.current
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        T(key, 20, c.mute)
        Spacer(Modifier.width(12.dp))
        T(value, 20, display = true, align = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
    }
    HLine()
}

@Composable
fun SelectRow(text: String, sub: String? = null, selected: Boolean, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val c = LocalMia.current
    Row(Modifier.fillMaxWidth().tap(onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (selected) c.red else Color.Transparent).border(1.dp, if (selected) c.red else c.line, CircleShape))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { T(text); if (sub != null) T(sub, 17, c.mute) }
        trailing?.invoke()
    }
    HLine()
}

@Composable
fun Toast(message: String?, onShown: () -> Unit) {
    val c = LocalMia.current
    LaunchedEffect(message) { if (message != null) { kotlinx.coroutines.delay(3200); onShown() } }
    AnimatedVisibility(
        message != null, enter = slideInVertically(spring(0.6f, 400f)) { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(c.fg).padding(horizontal = 16.dp, vertical = 10.dp)) {
                T(message ?: "", 20, c.bg)
            }
        }
    }
}
