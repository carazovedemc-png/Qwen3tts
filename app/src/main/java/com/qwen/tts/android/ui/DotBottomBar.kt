package com.qwen.tts.android.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

/* ═════════ Дизайн-единицы: вся геометрия считается в «пикселях макета» 1352×333, потом масштабируется ═════════ */

private const val PH0 = 333f            // высота таблетки в макете
private const val LY = 166.5f           // центр линзы по вертикали
private const val LW = 260f             // полуширина линзы
private const val LH = 180f             // полувысота линзы
private const val LR = 150f             // скругление линзы
private const val CLAMP_INSET = 145f    // насколько центр линзы отступает от края (линза вылезает ~на четверть)
private const val MARK_W = 360f
private const val MARK_H = 230f
private const val MARK_R = 100f
private const val MARK_OUTER = 62f      // отступ плашки от скруглённых краёв таблетки
private const val MARK_INNER = 26f      // отступ плашки от перегородок
private const val PAD_H = 130f          // запас под вылезающую линзу
private const val PAD_V = 30f

/** Theme-dependent colours of the bar (light and dark variants). */
@Immutable
private class BarPalette(
    val surface: Color, val ink: Color, val divider: Color, val mark: Color,
    val highlight: Color, val shadow: Color,
)

private val LightBar = BarPalette(
    surface = Color(0xFFEAEAEA), ink = Color(0xFF161616), divider = Color(0xFFA3A3A3), mark = Color.Black.copy(alpha = 0.075f),
    highlight = Color.White.copy(alpha = 0.95f), shadow = Color(0x33000000),
)
private val DarkBar = BarPalette(
    surface = Color(0xFF1B1B21), ink = Color(0xFFECEDF2), divider = Color(0xFF53555F), mark = Color.White.copy(alpha = 0.09f),
    highlight = Color.White.copy(alpha = 0.07f), shadow = Color(0xA6000000),
)

/* ═════════ Геометрия: одинаковые отступы от краёв/иконок/перегородок ═════════ */

private class BarGeometry(val w: Float) {
    private val iconW = floatArrayOf(111.6f, 164.6f, 133.3f) // дом, волна, TTS
    val gap = (w - iconW.sum()) / 6f
    val centers = FloatArray(3)
    val dividers = FloatArray(2)

    init {
        var x = gap
        for (i in 0..2) {
            centers[i] = x + iconW[i] / 2f
            x += iconW[i]
            if (i < 2) { dividers[i] = x + gap; x += 2f * gap }
        }
    }

    fun nearest(x: Float): Int = (0..2).minByOrNull { abs(centers[it] - x) } ?: 0
}

/* ═════════ Точечные иконки ═════════ */

private data class Dot(val x: Float, val y: Float, val r: Float)

private val HOME = listOf(
    "....#....", "...###...", "..#####..", ".#######.", "#########",
    "#########", "###...###", "###...###", "###...###",
)

private fun resample(pts: List<Offset>, n: Int): List<Offset> {
    val len = FloatArray(pts.size + 1)
    for (i in 1..pts.size) {
        val a = pts[i - 1]; val b = pts[i % pts.size]
        len[i] = len[i - 1] + hypot(b.x - a.x, b.y - a.y)
    }
    val tot = len.last()
    return List(n) { k ->
        val d = tot * k / n
        var i = 1
        while (len[i] < d) i++
        val a = pts[i - 1]; val b = pts[i % pts.size]
        val t = (d - len[i - 1]) / (len[i] - len[i - 1]).coerceAtLeast(1e-4f)
        Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }
}

private fun capsule(w: Float, h: Float): List<Offset> {
    val r = w / 2f; val s = 10
    val p = ArrayList<Offset>()
    for (i in 0..s) { val a = PI.toFloat() + PI.toFloat() * i / s; p += Offset(r * cos(a), -h / 2 + r + r * sin(a)) }
    for (i in 0..s) { val a = PI.toFloat() * i / s; p += Offset(r * cos(a), h / 2 - r + r * sin(a)) }
    return p
}

/** Относительно (cx, cy) иконки «волна». */
private val WaveDots: List<Dot> by lazy {
    listOf(-74f to 38f, -44f to 84f, -14f to 40f, 14f to 134f, 43f to 78f, 72f to 36f).flatMap { (dx, h) ->
        resample(capsule(14f, h), max(8, ((h * 2 + 20) / 6.2f).roundToInt())).map { Dot(dx + it.x, it.y, 2.3f) }
    }
}

/** Относительно (ox, oy) иконки TTS. */
private val TtsDots: List<Dot> by lazy {
    val d = ArrayList<Dot>()
    listOf(-67f, -52.5f, -38f, -23.5f, -9f).forEach { d += Dot(it, -47f, 4.9f) }
    listOf(-31f, -15f, 1f, 17f, 34f, 51f).forEach { d += Dot(-38f, it, 4.9f) }
    d += Dot(3f, 6f, 4f)
    fun arc(r: Float, deg: Float, n: Int) {
        for (i in 0 until n) {
            val a = Math.toRadians((-deg + 2 * deg * i / (n - 1)).toDouble()).toFloat()
            d += Dot(-5f + r * cos(a), 3f + r * sin(a), 4.4f)
        }
    }
    arc(32f, 32f, 4); arc(62f, 34f, 6)
    d
}

/** Относительно центра шестерёнки. */
private val GearDots: List<Dot> by lazy {
    val pts = List(256) { i ->
        val a = i / 256f * 2f * PI.toFloat()
        val k = (cos(8 * a) * 2.2f).coerceIn(-1f, 1f)
        val r = 51f + 13f * (k * .5f + .5f)
        Offset(r * cos(a), r * sin(a))
    }
    resample(pts, 52).map { Dot(it.x, it.y, 4.5f) } +
        List(8) { i -> val a = i / 8f * 2f * PI.toFloat(); Dot(18f * cos(a), 18f * sin(a), 4.2f) }
}

private fun DrawScope.drawBarContent(u: Float, wDesign: Float, ox: Float, oy: Float, ink: Color, divider: Color) {
    val g = BarGeometry(wDesign)
    fun dot(x: Float, y: Float, r: Float) = drawCircle(ink, r * u, Offset(ox + x * u, oy + y * u))
    g.dividers.forEach { x ->
        drawRect(divider, Offset(ox + (x - 1f) * u, oy + 98f * u), Size(2f * u, 122f * u))
    }
    val hc = g.centers[0]
    HOME.forEachIndexed { y, row ->
        row.forEachIndexed { x, ch -> if (ch == '#') dot(hc + (x - 4) * 12.4f, 160f + (y - 4) * 12.3f, 5.4f) }
    }
    val wx = g.centers[1] + 1f
    WaveDots.forEach { dot(wx + it.x, 163f + it.y, it.r) }
    val tx = g.centers[2] + 5.5f
    TtsDots.forEach { dot(tx + it.x, 163f + it.y, it.r) }
}

/* ═════════ Неоморфная поверхность ═════════ */

private fun Modifier.neumorphicSurface(pal: BarPalette): Modifier = drawBehind {
    val r = size.minDimension / 2f
    val blur = 22.dp.toPx(); val off = 10.dp.toPx()
    drawIntoCanvas { canvas ->
        val p = android.graphics.Paint().apply { isAntiAlias = true; color = pal.surface.toArgb() }
        p.setShadowLayer(blur, -off, -off, pal.highlight.toArgb())
        canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, p)
        p.setShadowLayer(blur, off, off * 1.2f, pal.shadow.toArgb())
        canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, p)
    }
}

private fun Modifier.expandBounds(padH: Int, padV: Int): Modifier = layout { m, c ->
    val p = m.measure(Constraints.fixed(c.maxWidth + 2 * padH, c.maxHeight + 2 * padV))
    layout(c.maxWidth, c.maxHeight) { p.place(-padH, -padV) }
}

/* ═════════ Линза: преломление по краю + хроматическая аберрация (AGSL, Android 13+) ═════════ */

private const val LENS_AGSL = """
uniform shader content;
uniform float2 center;
uniform float2 halfSize;
uniform float rr;
uniform float mag;
uniform float amt;
uniform float depth;
uniform float3 bg;

half4 main(float2 fc) {
    float2 p = fc - center;
    float2 q = abs(p) - halfSize + rr;
    float sd = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rr;
    half4 orig = content.eval(fc);
    if (sd > 1.0) { return orig; }
    float t = 1.0 - clamp(-sd / depth, 0.0, 1.0);
    float f = 1.0 + amt * 0.75 * t * t * t;
    float ab = amt * 0.045 * t * t * t;
    float2 o = p / mag;
    half4 a = content.eval(center + o * (f * (1.0 + ab)));
    half4 g = content.eval(center + o * f);
    half4 b = content.eval(center + o * (f * (1.0 - ab)));
    float k = 0.5 + 0.5 * clamp(p.y / halfSize.y, -1.0, 1.0);
    half3 bgc = half3(bg) * half(mix(1.03, 0.98, k));
    half3 col = half3(bgc.r * (1.0 - a.a) + a.r, bgc.g * (1.0 - g.a) + g.g, bgc.b * (1.0 - b.a) + b.b);
    half m = half(1.0 - smoothstep(-1.5, 0.0, sd));
    return half4(mix(orig.rgb, col, m), mix(orig.a, half(1.0), m));
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class LensEffect {
    private val shader = RuntimeShader(LENS_AGSL)
    fun apply(scope: GraphicsLayerScope, cx: Float, cy: Float, hw: Float, hh: Float, rr: Float,
              mag: Float, amt: Float, depth: Float, bg: Color) {
        shader.setFloatUniform("center", cx, cy)
        shader.setFloatUniform("halfSize", hw, hh)
        shader.setFloatUniform("rr", rr)
        shader.setFloatUniform("mag", mag)
        shader.setFloatUniform("amt", amt)
        shader.setFloatUniform("depth", depth)
        shader.setFloatUniform("bg", bg.red, bg.green, bg.blue)
        scope.renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

/* ═════════ Публичный API ═════════ */

/**
 * Три вкладки: 0 = Home, 1 = Voice, 2 = Speech.
 * Нажми на панель и потяни — появляется линза; отпустил — выбирается ближайшая вкладка.
 */
@Composable
fun DotBottomBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 64.dp,
    markVisible: Boolean = true,
    settingsActive: Boolean = false,
    dark: Boolean = false,
) {
    val pal = if (dark) DarkBar else LightBar
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val inPreview = LocalInspectionMode.current
    val currentOnSelect by rememberUpdatedState(onSelect)

    val u = with(density) { barHeight.toPx() } / PH0
    val padH = (PAD_H * u).roundToInt()
    val padV = (PAD_V * u).roundToInt()

    var pillW by remember { mutableIntStateOf(0) }
    val geo = remember(pillW, u) { BarGeometry(pillW / u) }
    var dragging by remember { mutableStateOf(false) }
    var hover by remember { mutableIntStateOf(0) }
    val lensX = remember { Animatable(0f) }          // в единицах макета
    val press = remember { Animatable(0f) }
    val lens = remember { if (Build.VERSION.SDK_INT >= 33) LensEffect() else null }

    val markIdx = if (dragging) hover else selectedIndex
    val markAlpha by animateFloatAsState(if (markVisible) 1f else 0f, tween(260), label = "markAlpha")
    // плашка активной вкладки занимает свою «ячейку» между перегородками и не вылезает за них
    val mi = markIdx.coerceIn(0, 2)
    val markL by animateFloatAsState(
        if (pillW > 0) (if (mi == 0) MARK_OUTER else geo.dividers[mi - 1] + MARK_INNER) else 0f,
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "markL",
    )
    val markR by animateFloatAsState(
        if (pillW > 0) (if (mi == 2) geo.w - MARK_OUTER else geo.dividers[mi] - MARK_INNER) else 0f,
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium), label = "markR",
    )

    LaunchedEffect(selectedIndex, pillW) {
        if (pillW > 0 && !dragging) {
            val target = geo.centers[selectedIndex.coerceIn(0, 2)]
            if (lensX.value == 0f) lensX.snapTo(target)
            else lensX.animateTo(target, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium))
        }
    }

    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(barHeight)
                .onSizeChanged { pillW = it.width }
                .neumorphicSurface(pal)
                .pointerInput(geo.w, u) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        dragging = true
                        val minX = CLAMP_INSET
                        val maxX = geo.w - CLAMP_INSET
                        var x = (down.position.x / u).coerceIn(minX, maxX)
                        hover = geo.nearest(x)
                        scope.launch { press.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) }
                        scope.launch { lensX.animateTo(x, spring(stiffness = Spring.StiffnessMedium)) }
                        do {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull() ?: break
                            if (ch.pressed) {
                                ch.consume()
                                x = (ch.position.x / u).coerceIn(minX, maxX)
                                scope.launch { lensX.snapTo(x) }
                                val idx = geo.nearest(x)
                                if (idx != hover) { hover = idx; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            }
                        } while (event.changes.any { it.pressed })
                        val idx = geo.nearest(x)
                        dragging = false
                        currentOnSelect(idx)
                        scope.launch { press.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)) }
                        scope.launch { lensX.animateTo(geo.centers[idx], spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium)) }
                    }
                },
        ) {
            // 1) серая плашка текущей страницы
            Canvas(Modifier.matchParentSize()) {
                if (pillW == 0) return@Canvas
                drawRoundRect(
                    pal.mark.copy(alpha = pal.mark.alpha * markAlpha),
                    topLeft = Offset(markL * u, (LY - MARK_H / 2) * u),
                    size = Size(max(0f, markR - markL) * u, MARK_H * u),
                    cornerRadius = CornerRadius(MARK_R * u),
                )
            }

            // 2) тень линзы (под телом линзы)
            Canvas(Modifier.matchParentSize()) {
                val p = press.value.coerceIn(0f, 1f)
                if (pillW == 0 || p < 0.01f) return@Canvas
                val s = .6f + .4f * press.value
                val cx = lensX.value * u; val cy = LY * u
                drawIntoCanvas { c ->
                    val paint = android.graphics.Paint().apply { isAntiAlias = true; color = pal.surface.toArgb() }
                    paint.setShadowLayer(30f * u * s, 0f, 22f * u * s, Color.Black.copy(alpha = 0.22f * min(1f, p * 4f)).toArgb())
                    c.nativeCanvas.drawRoundRect(
                        cx - LW * u * s, cy - LH * u * s, cx + LW * u * s, cy + LH * u * s,
                        LR * u * s, LR * u * s, paint,
                    )
                }
            }

            // 3) содержимое (иконки + перегородки) со шейдером линзы; слой больше таблетки, чтобы линза могла вылезать
            Canvas(
                Modifier
                    .fillMaxSize()
                    .expandBounds(padH, padV)
                    .graphicsLayer {
                        val p = press.value
                        if (!inPreview && Build.VERSION.SDK_INT >= 33 && lens != null && pillW > 0 && p > 0.001f) {
                            val s = .6f + .4f * p
                            lens.apply(
                                this, lensX.value * u + padH, LY * u + padV,
                                LW * u * s, LH * u * s, LR * u * s,
                                mag = (1f + .55f * p) * s,
                                amt = p.coerceIn(0f, 1f),
                                depth = 170f * u * s,
                                bg = pal.surface,
                            )
                        } else {
                            renderEffect = null
                        }
                    },
            ) {
                if (pillW > 0) drawBarContent(u, geo.w, padH.toFloat(), padV.toFloat(), pal.ink, pal.divider)
            }

            // 4) стеклянные блики поверх линзы
            Canvas(Modifier.matchParentSize()) {
                val pr = press.value
                val p = pr.coerceIn(0f, 1f)
                if (pillW == 0 || p < 0.01f) return@Canvas
                val a = min(1f, p * 4f)
                val s = .6f + .4f * pr
                val cx = lensX.value * u; val cy = LY * u
                val hw = LW * u * s; val hh = LH * u * s; val rr = LR * u * s
                val tl = Offset(cx - hw, cy - hh); val sz = Size(2 * hw, 2 * hh)
                val corner = CornerRadius(rr)
                val white = Color.White

                // мягкие блики внутри: слева-сверху и справа-снизу
                drawRoundRect(
                    Brush.radialGradient(
                        0f to white.copy(alpha = .60f * a), 1f to Color.Transparent,
                        center = Offset(cx - hw * .62f, cy - hh * .64f), radius = hw * .5f,
                    ), tl, sz, corner,
                )
                drawRoundRect(
                    Brush.radialGradient(
                        0f to white.copy(alpha = .50f * a), 1f to Color.Transparent,
                        center = Offset(cx + hw * .62f, cy + hh * .66f), radius = hw * .5f,
                    ), tl, sz, corner,
                )
                // лёгкая подсветка к краю
                drawRoundRect(
                    Brush.radialGradient(
                        0f to Color.Transparent, .58f to Color.Transparent, 1f to white.copy(alpha = .30f * a),
                        center = Offset(cx, cy), radius = hypot(hw, hh),
                    ), tl, sz, corner,
                )
                // ободок: ярко в левом верхнем и правом нижнем углах
                val w = 9f * u * s
                drawRoundRect(
                    Brush.sweepGradient(
                        0.00f to white.copy(alpha = .12f * a),
                        0.06f to white.copy(alpha = .55f * a),
                        0.125f to white.copy(alpha = .98f * a),
                        0.19f to white.copy(alpha = .55f * a),
                        0.27f to white.copy(alpha = .10f * a),
                        0.50f to white.copy(alpha = .10f * a),
                        0.56f to white.copy(alpha = .30f * a),
                        0.625f to white.copy(alpha = 1f * a),
                        0.69f to white.copy(alpha = .30f * a),
                        0.80f to white.copy(alpha = .12f * a),
                        1.00f to white.copy(alpha = .12f * a),
                        center = Offset(cx, cy),
                    ),
                    topLeft = tl + Offset(w / 2, w / 2), size = Size(sz.width - w, sz.height - w),
                    cornerRadius = CornerRadius(rr - w / 2), style = Stroke(width = w),
                )
            }
        }

        Spacer(Modifier.width(10.dp))
        GearButton(size = barHeight * (354f / PH0), u = u, pal = pal, active = settingsActive, onClick = onSettingsClick)
    }
}

@Composable
private fun GearButton(size: Dp, u: Float, pal: BarPalette, active: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.6f), label = "gear")
    Box(
        Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .neumorphicSurface(pal)
            .clip(CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            if (active) drawCircle(pal.mark, this.size.minDimension * 0.40f, c)
            GearDots.forEach { drawCircle(pal.ink, it.r * u, c + Offset(it.x * u, it.y * u)) }
        }
    }
}

