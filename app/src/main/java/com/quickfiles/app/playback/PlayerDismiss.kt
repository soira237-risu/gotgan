package com.quickfiles.app.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Decide who owns a gesture once it leaves the touch slop: null while undecided, true for a
 * mostly downward swipe, false for anything the seek surface, timeline or scroll rows own.
 * Horizontal seeking takes |dx| > 1.5|dy|, so the two recognizers never claim the same stroke.
 */
internal fun dismissDragStart(dx: Float, dy: Float, slop: Float): Boolean? {
    if (abs(dx) <= slop && abs(dy) <= slop) return null
    return dy > 0f && dy > abs(dx) * 1.5f
}

/** A fling down always dismisses, a fling back up always returns, otherwise distance decides. */
internal fun shouldDismissPlayer(offsetPx: Float, velocityY: Float, heightPx: Float, flingVelocity: Float): Boolean {
    if (!heightPx.isFinite() || heightPx <= 0f || !offsetPx.isFinite() || offsetPx <= 0f) return false
    val velocity = if (velocityY.isFinite()) velocityY else 0f
    if (velocity >= flingVelocity) return true
    if (velocity <= -flingVelocity) return false
    return offsetPx >= heightPx * .25f
}

/**
 * Lets the music player be pulled down with the finger. The player window is translucent while
 * this is enabled, so the travelled area reveals the browser task underneath, under a scrim that
 * clears as the player leaves. Releasing past the threshold slides the player out and calls
 * [onDismiss]; otherwise it springs back.
 */
@Composable
internal fun PlayerDismissFrame(enabled: Boolean, onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val offset = remember { Animatable(0f) }
    val dismiss by rememberUpdatedState(onDismiss)
    var leaving by remember { mutableStateOf(false) }
    // Setting leaving restarts the gesture block, so the exit must not run in its scope.
    val exitScope = rememberCoroutineScope()
    LaunchedEffect(enabled) { if (!enabled && !leaving) offset.snapTo(0f) }
    Box(Modifier.fillMaxSize().testTag("player-dismiss-frame").pointerInput(enabled && !leaving) {
        if (!enabled || leaving) return@pointerInput
        val flingVelocity = 1200.dp.toPx()
        coroutineScope {
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false)
                // Buttons and the timeline consume their press; a swipe must start on the stage.
                if (first.isConsumed) return@awaitEachGesture
                val tracker = VelocityTracker().apply { addPointerInputChange(first) }
                val base = offset.value
                var current = base
                var anchor = Float.NaN
                var released = false
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) break
                    val change = event.changes.firstOrNull { it.id == first.id } ?: break
                    if (anchor.isNaN()) {
                        if (change.isConsumed || !change.pressed) break
                        val delta = change.position - first.position
                        when (dismissDragStart(delta.x, delta.y, viewConfiguration.touchSlop)) {
                            null -> continue
                            false -> break
                            true -> anchor = change.position.y
                        }
                    }
                    tracker.addPointerInputChange(change)
                    if (!change.pressed) { released = true; break }
                    change.consume()
                    current = (base + change.position.y - anchor).coerceAtLeast(0f)
                    val target = current
                    launch { offset.snapTo(target) }
                }
                if (anchor.isNaN()) return@awaitEachGesture
                val height = size.height.toFloat()
                val velocity = tracker.calculateVelocity().y
                if (released && shouldDismissPlayer(current, velocity, height, flingVelocity)) {
                    leaving = true
                    // Keep the finger's speed, but never crawl or snap out.
                    val speed = maxOf(velocity, flingVelocity)
                    val duration = ((height - current) / speed * 1000f).toInt().coerceIn(120, 260)
                    exitScope.launch {
                        offset.animateTo(height, tween(duration, easing = LinearEasing))
                        dismiss()
                    }
                } else launch {
                    offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow), initialVelocity = velocity)
                }
            }
        }
    }) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = if (offset.value > 0f && size.height > 0f) .45f * (1f - offset.value / size.height).coerceIn(0f, 1f) else 0f
        }.background(Color.Black))
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationY = offset.value
            if (offset.value > 0f) { shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp); clip = true }
        }.background(Color.Black), content = content)
    }
}
