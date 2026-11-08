package com.qwen.tts.android.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qwen.tts.android.data.MiaSettings

@Immutable
class MiaColors(
    val bg: Color, val bg2: Color, val fg: Color, val mute: Color, val line: Color,
    val red: Color, val glassTint: Color, val glassEdge: Color, val dark: Boolean,
)

val DarkColors = MiaColors(
    bg = Color(0xFF0D0D11), bg2 = Color(0xFF17171C), fg = Color(0xFFECEDF2), mute = Color(0xFF8D909B),
    line = Color(0x1CFFFFFF), red = Color(0xFFFF2A2A), glassTint = Color(0x55303440), glassEdge = Color(0x38FFFFFF), dark = true,
)
val LightColors = MiaColors(
    bg = Color(0xFFEFEEEB), bg2 = Color(0xFFFBFAF8), fg = Color(0xFF16161A), mute = Color(0xFF6B6E78),
    line = Color(0x1F000000), red = Color(0xFFE01818), glassTint = Color(0x80FFFFFF), glassEdge = Color(0xB3FFFFFF), dark = false,
)

val LocalMia = staticCompositionLocalOf { DarkColors }
val LocalSettings = staticCompositionLocalOf<MiaSettings> { error("MiaSettings not provided") }

class MiaFonts(val ui: FontFamily, val display: FontFamily)
val LocalFonts = staticCompositionLocalOf { MiaFonts(FontFamily.Monospace, FontFamily.Monospace) }

private fun loadFont(context: Context, path: String): FontFamily? =
    runCatching { FontFamily(Typeface.createFromAsset(context.assets, path)) }.getOrNull()

/** Dot-matrix fonts are loaded from assets/fonts; if a file is missing the app falls back to monospace. */
@Composable
fun rememberFonts(): MiaFonts {
    val ctx = LocalContext.current
    return remember {
        val ui = loadFont(ctx, "fonts/handjet.ttf") ?: FontFamily.Monospace
        MiaFonts(ui = ui, display = loadFont(ctx, "fonts/doto.ttf") ?: ui)
    }
}

@Composable
fun T(
    text: String,
    size: Int = 21,
    color: Color = LocalMia.current.fg,
    display: Boolean = false,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    val fonts = LocalFonts.current
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            lineHeight = (size * 1.2f).sp,
            fontFamily = if (display) fonts.display else fonts.ui,
            fontWeight = weight,
            textAlign = align,
        ),
    )
}

/* ---- backdrop blur ("liquid glass") ---------------------------------------------------- */

class Backdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** Everything drawn by this composable becomes available to [glass] elements as a blurred backdrop. */
fun Modifier.backdropSource(b: Backdrop): Modifier = this
    .onGloballyPositioned { b.origin = it.positionInRoot() }
    .drawWithContent {
        b.layer.record { this@drawWithContent.drawContent() }
        drawLayer(b.layer)
    }

val LocalSkyBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Frosted glass: blurred copy of [b] behind a translucent tint. Falls back to a solid fill when disabled. */
@Composable
fun Modifier.glass(b: Backdrop?, shape: Shape, blur: Dp = 18.dp, enabled: Boolean = true): Modifier {
    val c = LocalMia.current
    if (!enabled || b == null) return this.clip(shape).background(c.bg2).border(1.dp, c.line, shape)
    val blurLayer = rememberGraphicsLayer()
    var pos by remember { mutableStateOf(Offset.Zero) }
    val radius = with(LocalDensity.current) { blur.toPx() }
    return this
        .onGloballyPositioned { pos = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            val w = size.width.toInt()
            val h = size.height.toInt()
            if (w > 0 && h > 0) {
                blurLayer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                blurLayer.record(size = IntSize(w, h)) {
                    translate(b.origin.x - pos.x, b.origin.y - pos.y) { drawLayer(b.layer) }
                }
                drawLayer(blurLayer)
            }
            drawRect(c.glassTint)
        }
        .border(1.dp, c.glassEdge, shape)
}

/** Fades content out towards the top edge so it slides under the title instead of colliding with it. */
fun Modifier.fadeTop(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 70f, endY = 210f),
            blendMode = BlendMode.DstIn,
        )
    }
