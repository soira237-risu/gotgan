package com.quickfiles.app.playback

import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import java.lang.ref.WeakReference

/** On-demand, on-device counters for instrumentation. No polling, logging or media identifiers. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PlaybackDiagnostics {
    private var source: WeakReference<ExoPlayer>? = null

    fun attach(player: ExoPlayer) { source = WeakReference(player) }
    fun detach(player: ExoPlayer) { if (source?.get() === player) source = null }

    fun snapshot(): VideoDecoderSnapshot? {
        val player = source?.get() ?: return null
        check(Looper.myLooper() == player.applicationLooper)
        val counters = player.videoDecoderCounters ?: return null
        counters.ensureUpdated()
        return VideoDecoderSnapshot(System.identityHashCode(counters), counters.renderedOutputBufferCount,
            counters.droppedBufferCount, counters.skippedOutputBufferCount,
            counters.decoderInitCount, counters.decoderReleaseCount,
            counters.maxConsecutiveDroppedBufferCount, counters.totalVideoFrameProcessingOffsetUs,
            counters.videoFrameProcessingOffsetCount)
    }
}

internal data class VideoDecoderSnapshot(
    val generation: Int, val rendered: Int, val dropped: Int, val skipped: Int,
    val initializations: Int, val releases: Int, val maxConsecutiveDropped: Int,
    val processingOffsetUs: Long, val processingOffsetCount: Int,
)
