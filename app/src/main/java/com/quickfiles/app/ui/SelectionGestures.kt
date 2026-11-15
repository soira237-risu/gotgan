package com.quickfiles.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

internal data class SelectionHit(val id: String, val bounds: Rect)

/** Segment/rectangle intersection also catches cells skipped by a fast pointer event. */
internal fun crossesCell(from: Offset, to: Offset, rect: Rect): Boolean {
    var start = 0f
    var end = 1f
    fun axis(origin: Float, delta: Float, low: Float, high: Float): Boolean {
        if (abs(delta) < .0001f) return origin >= low && origin <= high
        val a = (low - origin) / delta
        val b = (high - origin) / delta
        start = maxOf(start, minOf(a, b))
        end = minOf(end, maxOf(a, b))
        return start <= end
    }
    return axis(from.x, to.x - from.x, rect.left, rect.right) &&
        axis(from.y, to.y - from.y, rect.top, rect.bottom)
}

internal class SelectionStroke(val selecting: Boolean) {
    private val visited = mutableSetOf<String>()
    fun visit(ids: Iterable<String>): Set<String> = ids.filter { visited.add(it) }.toSet()
}

/** What a stroke on a tile becomes once it leaves the waiting state. */
internal enum class StrokeStart { WAIT, TAP, SCROLL, PAINT, PINCH }

/**
 * Decides a touch that began on a tile before the long-press time is up. One finger past the touch slop scrolls,
 * or paints while something is already selected. A second finger means a pinch wherever a pinch is handled,
 * so zooming never selects files by accident; without a pinch it paints with every finger, as before.
 */
internal fun strokeStart(pressed: Int, firstDown: Boolean, travelled: Float, touchSlop: Float, selecting: Boolean, canPinch: Boolean): StrokeStart = when {
    !firstDown -> StrokeStart.TAP
    pressed > 1 -> if (canPinch) StrokeStart.PINCH else if (selecting) StrokeStart.PAINT else StrokeStart.SCROLL
    travelled > touchSlop -> if (selecting) StrokeStart.PAINT else StrokeStart.SCROLL
    else -> StrokeStart.WAIT
}

/** Edge auto-scroll speed as a fraction of the maximum: zero outside the band, easing in so the edge itself is fastest. */
internal fun edgeScrollSpeed(y: Float, height: Float, edge: Float): Float {
    val depth = when {
        y < edge -> -((edge - y) / edge).coerceIn(0f, 1f)
        y > height - edge -> ((y - height + edge) / edge).coerceIn(0f, 1f)
        else -> 0f
    }
    return depth * abs(depth)
}

/** Selected cells paint after touch slop; blank space and idle gestures still scroll/pinch. */
internal fun Modifier.selectionGestures(
    key: Any?,
    hits: () -> List<SelectionHit>,
    selected: () -> Set<String>,
    apply: (Set<String>, Boolean) -> Unit,
    scroll: suspend (Float) -> Unit,
    pinch: ((Float, Offset) -> Unit)? = null,
    /** Called once when a long press starts a selection stroke, for haptic confirmation. */
    started: () -> Unit = {},
): Modifier = pointerInput(key) {
    coroutineScope {
        val scrollScope = this
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val firstHit = hits().firstOrNull { it.bounds.contains(first.position) }
            val selectionMode = firstHit != null && selected().isNotEmpty()
            var event: PointerEvent? = null
            var paintStart: PointerEvent? = null
            val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val next = awaitPointerEvent(PointerEventPass.Initial)
                    event = next
                    val pointer = next.changes.firstOrNull { it.id == first.id }
                    val start = strokeStart(next.changes.count { it.pressed }, pointer?.pressed == true,
                        pointer?.let { (it.position - first.position).getDistance() } ?: 0f, viewConfiguration.touchSlop, selectionMode, pinch != null)
                    if (start == StrokeStart.WAIT) continue
                    if (start == StrokeStart.PAINT) paintStart = next
                    break
                }
                paintStart == null
            } ?: false
            if (!interrupted && firstHit != null) {
                val stroke = SelectionStroke(firstHit.id !in selected())
                if (paintStart == null) started()
                apply(stroke.visit(listOf(firstHit.id)), stroke.selecting)
                val pointers = linkedMapOf(first.id to first.position)
                val edge = 56.dp.toPx()
                val maxPerSecond = 1600.dp.toPx()
                val scrolling = scrollScope.launch {
                    // Paced by the display's frames, so the list moves once per frame at any refresh rate.
                    var previousFrame = 0L
                    while (isActive) {
                        val now = withFrameNanos { it }
                        val elapsed = if (previousFrame == 0L) 0f else ((now - previousFrame) / 1_000_000_000f).coerceAtMost(.05f)
                        previousFrame = now
                        val primary = pointers.values.firstOrNull() ?: break
                        val speed = edgeScrollSpeed(primary.y, size.height.toFloat(), edge)
                        if (speed != 0f && elapsed > 0f) {
                            scroll(speed * maxPerSecond * elapsed)
                            val newIds = stroke.visit(hits().filter { hit -> pointers.values.any(hit.bounds::contains) }.map { it.id })
                            if (newIds.isNotEmpty()) apply(newIds, stroke.selecting)
                        }
                    }
                }
                try {
                    do {
                        // Consume the slop/multi-touch event before the child can start scrolling.
                        val next = paintStart ?: awaitPointerEvent(PointerEventPass.Initial)
                        paintStart = null
                        val visible = hits()
                        val touched = mutableSetOf<String>()
                        next.changes.forEach { change ->
                            val previous = pointers[change.id] ?: change.position
                            if (change.pressed || change.previousPressed) {
                                visible.forEach { if (crossesCell(previous, change.position, it.bounds)) touched += it.id }
                            }
                            if (change.pressed) pointers[change.id] = change.position else pointers.remove(change.id)
                            change.consume()
                        }
                        val newIds = stroke.visit(touched)
                        if (newIds.isNotEmpty()) apply(newIds, stroke.selecting)
                    } while (next.changes.any { it.pressed })
                } finally { scrolling.cancel() }
            } else {
                var scale = 1f
                var changed = false
                while (event?.changes?.any { it.pressed } != false) {
                    val next = event ?: awaitPointerEvent(PointerEventPass.Initial)
                    if (pinch != null && next.changes.count { it.pressed } >= 2) {
                        scale *= next.calculateZoom()
                        if (!changed && (scale < .82f || scale > 1.22f)) {
                            changed = true
                            pinch(scale, next.calculateCentroid())
                        }
                        next.changes.forEach { it.consume() }
                    }
                    if (next.changes.none { it.pressed }) break
                    event = awaitPointerEvent(PointerEventPass.Initial)
                }
            }
        }
    }
}
