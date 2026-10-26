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
import kotlinx.coroutines.delay
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

/** Selected cells paint after touch slop; blank space and idle gestures still scroll/pinch. */
internal fun Modifier.selectionGestures(
    key: Any?,
    hits: () -> List<SelectionHit>,
    selected: () -> Set<String>,
    apply: (Set<String>, Boolean) -> Unit,
    scroll: suspend (Float) -> Unit,
    pinch: ((Float, Offset) -> Unit)? = null,
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
                    if (pointer == null || !pointer.pressed) break
                    if (next.changes.count { it.pressed } != 1 ||
                        (pointer.position - first.position).getDistance() > viewConfiguration.touchSlop) {
                        if (selectionMode) paintStart = next
                        break
                    }
                }
                paintStart == null
            } ?: false
            if (!interrupted && firstHit != null) {
                val stroke = SelectionStroke(firstHit.id !in selected())
                apply(stroke.visit(listOf(firstHit.id)), stroke.selecting)
                val pointers = linkedMapOf(first.id to first.position)
                val edge = 56.dp.toPx()
                val maxPerSecond = 600.dp.toPx()
                val scrolling = scrollScope.launch {
                    var previousFrame = 0L
                    while (isActive) {
                        delay(16)
                        val now = System.nanoTime()
                        val elapsed = if (previousFrame == 0L) 0f else ((now - previousFrame) / 1_000_000_000f).coerceAtMost(.05f)
                        previousFrame = now
                        val primary = pointers.values.firstOrNull() ?: break
                        val speed = when {
                            primary.y < edge -> -((edge - primary.y) / edge).coerceIn(0f, 1f)
                            primary.y > size.height - edge -> ((primary.y - size.height + edge) / edge).coerceIn(0f, 1f)
                            else -> 0f
                        }
                        if (speed != 0f) {
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
