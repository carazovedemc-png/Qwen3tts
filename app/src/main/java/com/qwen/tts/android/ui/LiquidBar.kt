package com.qwen.tts.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

private fun DrawScope.icon(kind: Int, color: Color) {
    val u = size.width / 24f
    val st = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), 1.8f * u, StrokeCap.Round)
    when (kind) {
        0 -> { line(4f, 12f, 4f, 12.1f); line(8f, 8f, 8f, 16f); line(12f, 4f, 12f, 20f); line(16f, 8f, 16f, 16f); line(20f, 12f, 20f, 12.1f) }
        1 -> {
            drawCircle(color, 3.6f * u, Offset(12f * u, 8f * u), style = st)
            val p = Path().apply { moveTo(5f * u, 20f * u); cubicTo(5f * u, 15f * u, 8.5f * u, 14f * u, 12f * u, 14f * u); cubicTo(15.5f * u, 14f * u, 19f * u, 15f * u, 19f * u, 20f * u) }
            drawPath(p, color, style = st)
        }
        2 -> { line(9f, 6f, 20f, 6f); line(9f, 12f, 20f, 12f); line(9f, 18f, 20f, 18f); line(4.5f, 6f, 4.6f, 6f); line(4.5f, 12f, 4.6f, 12f); line(4.5f, 18f, 4.6f, 18f) }
        else -> {
            line(4f, 7f, 13f, 7f); line(19f, 7f, 20f, 7f); line(4f, 17f, 5f, 17f); line(11f, 17f, 20f, 17f)
            drawCircle(color, 2.3f * u, Offset(16f * u, 7f * u), style = st)
            drawCircle(color, 2.3f * u, Offset(8f * u, 17f * u), style = st)
        }
    }
}

/** Floating glass tab bar with a draggable "lens" (Nothing-style dotted ring). */
@Composable
fun LiquidBar(selected: Int, onSelect: (Int) -> Unit, backdrop: Backdrop, modifier: Modifier = Modifier) {
    val c = LocalMia.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current

    BoxWithConstraints(modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp), contentAlignment = Alignment.BottomCenter) {
        val barW = minOf(292.dp, maxWidth - 56.dp)
        val barH = 64.dp
        val pad = 4.dp
        val barWpx = with(density) { barW.toPx() }
        val padPx = with(density) { pad.toPx() }
        val itemW = (barWpx - 2 * padPx) / 4f
        val lensSize = 54.dp
        val lensPx = with(density) { lensSize.toPx() }
        fun center(i: Int) = padPx + itemW * (i + 0.5f)

        val lensX = remember { Animatable(center(selected)) }
        val scale = remember { Animatable(1f) }
        var dragging by remember { mutableStateOf(false) }
        val currentSelected by rememberUpdatedState(selected)
        val onSel by rememberUpdatedState(onSelect)

        LaunchedEffect(selected, itemW) {
            if (!dragging) lensX.animateTo(center(selected), spring(dampingRatio = 0.55f, stiffness = 320f))
        }

        Box(
            Modifier.size(barW, barH)
                .pointerInput(itemW) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        dragging = true
                        scope.launch { scale.animateTo(1.32f, spring(dampingRatio = 0.5f, stiffness = 420f)) }
                        fun idx(x: Float) = ((x - padPx) / itemW).toInt().coerceIn(0, 3)
                        fun clampC(x: Float) = x.coerceIn(padPx + itemW / 2f, barWpx - padPx - itemW / 2f)
                        var last = idx(down.position.x)
                        scope.launch { lensX.snapTo(clampC(down.position.x)) }
                        onSel(last)
                        drag(down.id) { ch ->
                            scope.launch { lensX.snapTo(clampC(ch.position.x)) }
                            val i = idx(ch.position.x)
                            if (i != last) { last = i; onSel(i) }
                            ch.consume()
                        }
                        dragging = false
                        scope.launch {
                            scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f))
                        }
                        scope.launch { lensX.animateTo(center(last), spring(dampingRatio = 0.5f, stiffness = 300f)) }
                    }
                },
        ) {
            // glass body
            Box(
                Modifier.matchParentSize()
                    .shadow(18.dp, RoundedCornerShape(32.dp), ambientColor = Color.Black, spotColor = Color.Black)
                    .glass(backdrop, RoundedCornerShape(32.dp), blur = 22.dp, enabled = settings.glass),
            )
            // lens
            Canvas(
                Modifier.size(lensSize)
                    .offset { IntOffset((lensX.value - lensPx / 2f).roundToInt(), with(density) { (barH - lensSize).roundToPx() } / 2) }
                    .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
            ) {
                val r = size.minDimension / 2f
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = if (c.dark) 0.30f else 0.55f), Color.White.copy(alpha = 0.06f)),
                        center = Offset(r * 0.7f, r * 0.55f), radius = r * 1.5f,
                    ),
                )
                drawCircle(Color.White.copy(alpha = 0.35f), r - 0.5f, style = Stroke(1.dp.toPx()))
                val dots = 22
                for (k in 0 until dots) {
                    val a = 2.0 * PI * k / dots
                    drawCircle(
                        c.fg.copy(alpha = 0.55f), 1.2.dp.toPx(),
                        Offset(r + (r - 5.dp.toPx()) * cos(a).toFloat(), r + (r - 5.dp.toPx()) * sin(a).toFloat()),
                    )
                }
            }
            // icons
            Row(Modifier.matchParentSize().padding(horizontal = pad)) {
                for (i in 0..3) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        val tint = if (i == selected) c.fg else c.mute
                        Canvas(Modifier.size(25.dp)) { icon(i, tint) }
                    }
                }
            }
        }
    }
}
