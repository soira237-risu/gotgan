package com.quickfiles.app

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.playback.GotganRenderersFactory
import com.quickfiles.app.playback.M2tsDataSource
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Plays a 192-byte-packet AVCHD file (H.264 + AC-3) through the same data source and renderers as PlaybackService. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class M2tsPlaybackDeviceTest {
    @Test fun avchdMtsWithAc3PlaysSeeksAndEnds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val file = File(app.cacheDir, "m2ts-device-test.mts")
        instrumentation.context.assets.open("avchd.mts").use { input -> file.outputStream().use(input::copyTo) }
        val ready = CountDownLatch(1); val ended = CountDownLatch(1)
        var error: PlaybackException? = null
        var player: ExoPlayer? = null
        val main = Handler(Looper.getMainLooper())
        try {
            main.post {
                player = ExoPlayer.Builder(app, GotganRenderersFactory(app))
                    .setMediaSourceFactory(DefaultMediaSourceFactory { M2tsDataSource(FileDataSource()) { true } })
                    .build().apply {
                        volume = 0f
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                if (state == Player.STATE_READY) ready.countDown()
                                if (state == Player.STATE_ENDED) ended.countDown()
                            }
                            override fun onPlayerError(e: PlaybackException) { error = e; ready.countDown(); ended.countDown() }
                        })
                        setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                        prepare()
                    }
            }
            assertTrue("never ready", ready.await(20, TimeUnit.SECONDS))
            assertNull(error)
            val supported = CountDownLatch(1)
            var video = false; var audio = false; var duration = C.TIME_UNSET
            main.post {
                val p = player!!
                p.currentTracks.groups.forEach { group ->
                    val mime = group.getTrackFormat(0).sampleMimeType
                    if (mime == MimeTypes.VIDEO_H264) video = group.isSupported && group.isSelected
                    if (mime == MimeTypes.AUDIO_AC3) audio = group.isSupported && group.isSelected
                }
                duration = p.duration
                p.seekTo(500); p.play()
                supported.countDown()
            }
            supported.await(5, TimeUnit.SECONDS)
            assertTrue("H.264 track not playable", video)
            assertTrue("AC-3 track not playable (FFmpeg fallback missing?)", audio)
            assertTrue("duration $duration", duration in 700..1500)
            assertTrue("never ended", ended.await(20, TimeUnit.SECONDS))
            assertNull(error)
        } finally {
            main.post { player?.release() }
            file.delete()
        }
    }
}
