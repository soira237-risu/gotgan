package com.quickfiles.app.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToLong

internal data class SeekPreview(
    val mediaId: String, val sessionId: String, val origin: Long, val target: Long,
    val fine: Boolean = false,
)

/** Accumulate deltas, so changing sensitivity never remaps an already travelled distance. */
internal class SeekDrag(
    val mediaId: String, val sessionId: String, origin: Long,
    private val duration: Long, private val widthPx: Float,
) {
    val origin = origin.coerceIn(0, duration.coerceAtLeast(0))
    private var target = this.origin.toDouble()
    fun move(deltaPx: Float, fine: Boolean): SeekPreview {
        if (deltaPx.isFinite() && widthPx.isFinite() && widthPx > 0 && duration > 0) {
            target = (target + deltaPx / widthPx * minOf(duration, 60_000L) * if (fine) .2 else 1.0)
                .coerceIn(0.0, duration.toDouble())
        }
        return SeekPreview(mediaId, sessionId, origin, target.roundToLong(), fine)
    }
}

/** A gesture preview wins over a slider drag, which wins over the sampled playback clock. */
internal fun timelinePosition(preview: SeekPreview?, dragging: Float?, position: Long): Long =
    preview?.target ?: dragging?.toLong() ?: position

/**
 * The playback position the timeline shows when nothing is being dragged.
 *
 * The timeline is only composed while the controls are visible, so this clock lives in the
 * player screen. Showing the controls therefore starts from the real position instead of
 * 0:00, and a committed seek moves the clock in the same snapshot that clears the preview,
 * so the thumb never falls back to the pre-seek position until the next sample.
 */
internal class PlaybackClock(initial: Long) {
    var position by mutableLongStateOf(initial.coerceAtLeast(0)); private set
    fun sample(current: Long) { position = current.coerceAtLeast(0) }
    fun committed(target: Long) { position = target.coerceAtLeast(0) }
}
