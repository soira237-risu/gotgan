package com.quickfiles.app.playback

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
