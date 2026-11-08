package com.qwen.tts.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

private class Star(val nx: Float, val ny: Float, val phase: Float, val big: Boolean, val alpha: Float)
private class Comet(val nx: Float, val t0: Float, val speed: Float, val drift: Float)

/** Pixel starfield with an occasional comet: red head, trail red -> yellow -> white. */
@Composable
fun PixelSky(modifier: Modifier = Modifier, color: Color) {
    val time = remember { mutableFloatStateOf(0f) }
    val comets = remember { mutableStateListOf<Comet>() }
    val stars = remember {
        val r = Random(7)
        List(170) { Star(r.nextFloat(), r.nextFloat(), r.nextFloat() * 6.28f, r.nextFloat() < 0.12f, 0.25f + r.nextFloat() * 0.6f) }
    }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            time.floatValue += (now - last) / 1_000_000_000f
            last = now
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(Random.nextLong(9_000, 18_000))
            comets.removeAll { time.floatValue - it.t0 > 40f }
            comets.add(Comet(Random.nextFloat(), time.floatValue, 55f + Random.nextFloat() * 30f, 0.15f + Random.nextFloat() * 0.15f))
        }
    }
    val red = Color(0xFFFF2A2A)
    val yellow = Color(0xFFFFD400)
    Canvas(modifier) {
        val px = 3.dp.toPx()
        val gw = size.width / px
        val t = time.floatValue
        val cell = Size(px, px)
        stars.forEach { s ->
            val a = s.alpha * (0.55f + 0.45f * sin(t * 1.1f + s.phase))
            val x = floor(s.nx * gw) * px
            val y = floor(s.ny * (size.height / px)) * px
            drawRect(color.copy(alpha = a.coerceIn(0f, 1f)), Offset(x, y), if (s.big) Size(px * 2, px * 2) else cell)
        }
        comets.forEach { c ->
            val dy = (t - c.t0) * c.speed
            val hx = c.nx * gw + dy * c.drift
            for (i in 0 until 18) {
                val f = i / 18f
                val col = when {
                    i == 0 -> red
                    f < 0.4f -> lerp(red, yellow, f / 0.4f)
                    else -> lerp(yellow, Color.White, (f - 0.4f) / 0.6f)
                }
                val gx = floor(hx - i * c.drift * 1.1f)
                val gy = floor(dy - i)
                if (gy >= 0) drawRect(col.copy(alpha = 1f - f * 0.9f), Offset(gx * px, gy * px), cell)
            }
        }
    }
}
