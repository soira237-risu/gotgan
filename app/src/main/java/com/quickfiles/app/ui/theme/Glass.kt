package com.quickfiles.app.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Thin: buttons over photos. Regular: dock, header buttons, mini player. Thick: sheets and popovers. */
enum class GlassLevel { THIN, REGULAR, THICK }

/**
 * Translucent surface tokens. Floating controls only; list rows and cards stay opaque.
 * The top highlight and outer hairline keep an edge visible on plain backgrounds.
 */
@Immutable
data class GlassColors(
    val base: Color,
    val thin: Float, val regular: Float, val thick: Float,
    val highlight: Color, val lowlight: Color, val hairline: Color, val shadow: Color,
    /** Selected item inside a glass bar, such as the current dock entry. */
    val pill: Color,
) {
    fun tint(level: GlassLevel) = base.copy(alpha = when (level) { GlassLevel.THIN -> thin; GlassLevel.REGULAR -> regular; GlassLevel.THICK -> thick })
}

internal fun lightGlass(background: Color) = GlassColors(background, .5f, .72f, .86f,
    Color.White.copy(alpha = .9f), Color.White.copy(alpha = .3f), Color.Black.copy(alpha = .1f), Color(0x3D1E1814), Color.White.copy(alpha = .85f))

internal fun darkGlass(surface: Color) = GlassColors(surface, .5f, .72f, .9f,
    Color.White.copy(alpha = .16f), Color.White.copy(alpha = .05f), Color.Black.copy(alpha = .5f), Color(0x73000000), Color.White.copy(alpha = .12f))

/** Neutral glass over photos, video and pages, so a palette tint never shifts media colors. */
val MediaGlass = GlassColors(Color(0xFF1C1C1E), .4f, .56f, .72f,
    Color.White.copy(alpha = .2f), Color.White.copy(alpha = .06f), Color.Black.copy(alpha = .4f), Color(0x66000000), Color.White.copy(alpha = .16f))

val LocalGlass = staticCompositionLocalOf { lightGlass(Color(0xFFFAF9F5)) }

/** [behind] draws between the clip and the tint, e.g. [backdropBlur]. */
fun Modifier.glass(colors: GlassColors, shape: Shape = CircleShape, level: GlassLevel = GlassLevel.REGULAR, elevated: Boolean = true,
    behind: Modifier = Modifier): Modifier =
    (if (elevated) shadow(8.dp, shape, clip = false, ambientColor = colors.shadow, spotColor = colors.shadow) else this)
        .clip(shape)
        .then(behind)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val edge = Brush.verticalGradient(listOf(colors.highlight, colors.lowlight))
            // Strokes are centred on the clipped edge, so only their inner half shows.
            val highlight = Stroke(2.dp.toPx())
            val hairline = Stroke(1.dp.toPx())
            onDrawWithContent {
                drawOutline(outline, colors.tint(level))
                drawContent()
                drawOutline(outline, edge, style = highlight)
                drawOutline(outline, colors.hairline, style = hairline)
            }
        }

/**
 * Content recorded once per frame so floating glass can blur what lies behind it.
 * [frame] changes whenever the source redraws, invalidating the glass that samples it.
 */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
    internal var frame by mutableIntStateOf(0)
    /** Opaque colour under the recorded content, so the blurred copy hides the sharp original beneath the glass. */
    internal var fill by mutableStateOf(Color.Black)
}

@Composable
fun rememberBackdrop(fill: Color): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }.also { it.fill = fill }
}

val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Records this content into [backdrop]; it still draws normally. */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier =
    onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
            backdrop.frame++
        }

/** Real blur of the recorded backdrop on API 31+; older versions keep the translucent tint only. */
@Composable
fun Modifier.backdropBlur(backdrop: Backdrop? = LocalBackdrop.current, radius: Dp = 24.dp): Modifier {
    if (backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    val blur = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin = it.positionInRoot() }.drawBehind {
        backdrop.frame // Re-sample whenever the source redraws.
        val px = radius.toPx()
        blur.renderEffect = BlurEffect(px, px, TileMode.Clamp)
        blur.record {
            drawRect(backdrop.fill)
            translate(backdrop.origin.x - origin.x, backdrop.origin.y - origin.y) { drawLayer(backdrop.layer) }
        }
        drawLayer(blur)
    }
}
