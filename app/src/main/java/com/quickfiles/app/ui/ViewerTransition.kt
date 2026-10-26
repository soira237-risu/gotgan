package com.quickfiles.app.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil3.compose.asPainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/** A grid or list thumbnail the viewer can grow from and return to: where it sits and the image it already decoded. */
internal class TileRef(val coordinates: () -> LayoutCoordinates?, val image: () -> coil3.Image?)

private fun Rect.scaledAbout(center: Offset, scale: Float) = Rect(center + (topLeft - center) * scale, center + (bottomRight - center) * scale)

/** Container transform: the tapped thumbnail grows into the fitted image, and closing flies it back into its tile.
 * The flight reuses the tile's decoded thumbnail, so no second decoder or bitmap copy runs during navigation. */
@Composable
internal fun ImagePreviewTransition(preview: PreviewState, model: ExplorerViewModel, backEnabled: Boolean) {
    key(preview.token) {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val tileRadius = with(LocalDensity.current) { 12.dp.toPx() }
        // Moving between images keeps the viewer on screen; only a fresh open flies in from the folder.
        val continuing = remember { model.lastViewedImageId != null }
        val backdrop = remember { Animatable(if (continuing) 1f else 0f) }
        val content = remember { Animatable(if (continuing) 1f else 0f) }
        val flight = remember { Animatable(0f) }
        var from by remember { mutableStateOf(Rect.Zero) }
        var to by remember { mutableStateOf(Rect.Zero) }
        var flyer by remember { mutableStateOf<Painter?>(null) }
        var shown by remember { mutableStateOf(continuing) }
        var container by remember { mutableStateOf<Rect?>(null) }
        var closing by remember { mutableStateOf(false) }
        var rising by remember { mutableStateOf(false) }
        var transition by remember { mutableStateOf<Job?>(null) }
        // Fade from dragging the image down, and shrink from a predictive back swipe.
        var pull by remember { mutableFloatStateOf(0f) }
        var backScale by remember { mutableFloatStateOf(1f) }
        fun tile(): Pair<Rect, Painter>? {
            val box = container ?: return null
            val ref = model.tiles[preview.entry.id] ?: return null
            val coordinates = ref.coordinates()?.takeIf { it.isAttached } ?: return null
            val image = ref.image() ?: return null
            val bounds = coordinates.boundsInWindow().translate(-box.topLeft)
            return if (bounds.isEmpty) null else bounds to image.asPainter(context)
        }
        // Matches the viewer's ContentScale.Fit, so the flight lands exactly where the full image draws.
        fun fitted(painter: Painter): Rect? {
            val box = container ?: return null
            val image = painter.intrinsicSize
            if (!image.isSpecified || image.width <= 0f || image.height <= 0f) return null
            val scale = min(box.width / image.width, box.height / image.height)
            val size = Size(image.width * scale, image.height * scale)
            return Rect(Offset((box.width - size.width) / 2, (box.height - size.height) / 2), size)
        }
        fun finish() {
            if (model.flightEntryId == preview.entry.id) model.flightEntryId = null
            if (model.state.value.preview?.token == preview.token) model.closePreview()
        }
        fun close(start: Rect? = null) {
            if (closing) return
            closing = true
            transition?.cancel()
            val target = tile()
            // Closing mid-flight turns around where the thumbnail is now.
            val origin = start ?: (if (flyer != null) lerp(from, to, flight.value) else null)
                ?: target?.let { fitted(it.second) }?.let { it.scaledAbout(it.center, backScale) }
            val fade = backdrop.value * (1f - .65f * pull)
            transition = scope.launch {
                backdrop.snapTo(fade); pull = 0f
                if (target != null && origin != null) {
                    flyer = target.second; from = origin; to = target.first
                    flight.snapTo(0f)
                    model.flightEntryId = preview.entry.id
                    content.snapTo(0f); backScale = 1f
                    launch { backdrop.animateTo(0f, MaterialMotion.screenExit(250)) }
                    flight.animateTo(1f, MaterialMotion.container(350))
                } else {
                    flyer = null; rising = true
                    launch { backdrop.animateTo(0f, MaterialMotion.screenExit()) }
                    content.animateTo(0f, MaterialMotion.screenExit())
                }
                finish()
            }
        }
        LaunchedEffect(container != null) {
            if (container == null || continuing || closing) return@LaunchedEffect
            val start = tile()
            val end = start?.let { fitted(it.second) }
            transition = launch {
                if (start != null && end != null) {
                    flyer = start.second; from = start.first; to = end
                    model.flightEntryId = preview.entry.id
                    launch { backdrop.animateTo(1f, MaterialMotion.screenEnter(300)) }
                    flight.animateTo(1f, MaterialMotion.container())
                    content.animateTo(1f, MaterialMotion.state())
                    // The thumbnail stays under the viewer until the full image has drawn over it.
                    snapshotFlow { shown }.first { it }
                    flyer = null
                    if (model.flightEntryId == preview.entry.id) model.flightEntryId = null
                } else {
                    shown = true; rising = true
                    launch { backdrop.animateTo(1f, MaterialMotion.screenEnter(300)) }
                    content.animateTo(1f, MaterialMotion.screenEnter(300))
                }
            }
        }
        DisposableEffect(Unit) { onDispose { if (model.flightEntryId == preview.entry.id) model.flightEntryId = null } }
        // Keep this callback enabled through its committed animation. Disabling it
        // mid-handler cancels the coroutine before the preview can be released.
        PredictiveBackHandler(enabled = backEnabled) { events ->
            if (closing) { events.collect { }; return@PredictiveBackHandler }
            try {
                events.collect { backScale = 1f - .1f * it.progress.coerceIn(0f, 1f); pull = .6f * it.progress.coerceIn(0f, 1f) }
                close()
            } catch (cancelled: CancellationException) {
                scope.launch { animate(backScale, 1f, animationSpec = MaterialMotion.spatial()) { value, _ -> backScale = value } }
                scope.launch { animate(pull, 0f, animationSpec = MaterialMotion.spatial()) { value, _ -> pull = value } }
                throw cancelled
            }
        }
        Box(Modifier.fillMaxSize().onGloballyPositioned { container = it.boundsInWindow() }) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = backdrop.value * (1f - .65f * pull) }.background(Color.Black))
            flyer?.let { painter ->
                Image(painter, null, contentScale = ContentScale.Crop, modifier = Modifier
                    .layout { measurable, constraints ->
                        val rect = lerp(from, to, flight.value)
                        val placeable = measurable.measure(Constraints.fixed(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)))
                        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(IntOffset(rect.left.roundToInt(), rect.top.roundToInt())) }
                    }
                    .graphicsLayer {
                        // Tile corners at the grid end, square at the viewer end.
                        val corner = if (closing) lerp(0f, tileRadius, flight.value) else lerp(tileRadius, 0f, flight.value)
                        shape = RoundedCornerShape(corner); clip = true
                    })
            }
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = content.value
                // Without a tile to grow from, the viewer rises in place as before.
                val rise = if (rising) .96f + .04f * content.value else 1f
                scaleX = rise * backScale; scaleY = scaleX
            }) {
                ImagePreviewScreen(preview, model, background = Color.Transparent, onClose = { close() },
                    onSwipedClosed = { rect -> close(rect) }, onDismissDrag = { pull = it }, onShown = { shown = true })
            }
        }
    }
}
