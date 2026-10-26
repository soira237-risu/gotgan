package com.quickfiles.app.playback

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
internal fun PlayerSeekSurface(
    modifier: Modifier, identity: Any, enabled: Boolean, tapsEnabled: Boolean,
    leftEdgePx: Float, rightEdgePx: Float,
    onStart: (Float) -> SeekDrag?, onPreview: (SeekPreview) -> Unit,
    onCommit: (SeekPreview) -> Unit, onCancel: () -> Unit,
    onTap: () -> Unit, onDoubleTap: (Boolean) -> Unit,
) {
    val start by rememberUpdatedState(onStart)
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    val cancel by rememberUpdatedState(onCancel)
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    Box(modifier.testTag("player-surface").semantics {
        contentDescription = "재생 화면 · 좌우로 밀어 시간 이동"
        onClick("재생 도구 표시 전환") { if (tapsEnabled) tap(); true }
    }.pointerInput(identity, enabled, tapsEnabled, leftEdgePx, rightEdgePx) {
        // A single recognizer owns the whole sequence. In particular, ACTION_CANCEL
        // and a second finger cannot be reinterpreted as a delayed single tap.
        coroutineScope {
            var pendingTap: Job? = null
            var lastTapUp = Long.MIN_VALUE
            try {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val doubleCandidate = pendingTap?.isActive == true &&
                        first.uptimeMillis - lastTapUp in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis
                    pendingTap?.cancel(); pendingTap = null
                    val inside = first.position.x >= leftEdgePx && first.position.x <= size.width - rightEdgePx
                    var drag: SeekDrag? = null
                    var latest: SeekPreview? = null
                    var previousX = first.position.x
                    var moved = false
                    var released = false
                    var upTime = first.uptimeMillis
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed || it.previousPressed } > 1) {
                                if (drag != null) event.changes.forEach { it.consume() }
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == first.id } ?: break
                            if (change.isConsumed) break
                            if (!change.pressed) { released = true; upTime = change.uptimeMillis; break }
                            val dx = change.position.x - first.position.x
                            val dy = change.position.y - first.position.y
                            if (drag == null && (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop)) {
                                moved = true
                                if (!enabled || !inside || abs(dx) <= abs(dy) * 1.5f) break
                                drag = start((size.width - leftEdgePx - rightEdgePx).coerceAtLeast(1f)) ?: break
                            }
                            drag?.let {
                                latest = it.move(change.position.x - previousX, dy > 56.dp.toPx())
                                previousX = change.position.x
                                change.consume()
                                preview(latest!!)
                            }
                        }
                        if (released) {
                            if (latest != null) commit(latest!!)
                            else if (!moved && tapsEnabled && upTime - first.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                                if (doubleCandidate) {
                                    when {
                                        first.position.x < size.width * .4f -> doubleTap(false)
                                        first.position.x > size.width * .6f -> doubleTap(true)
                                    }
                                } else {
                                    lastTapUp = upTime
                                    pendingTap = launch { delay(viewConfiguration.doubleTapTimeoutMillis); tap() }
                                }
                            }
                        }
                    } finally {
                        if (drag != null && !released) cancel()
                    }
                }
            } finally {
                pendingTap?.cancel()
            }
        }
    })
}
