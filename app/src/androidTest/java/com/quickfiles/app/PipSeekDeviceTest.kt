package com.quickfiles.app

import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.playback.PlayerActivity
import com.quickfiles.app.playback.SEEK_END_GUARD_MS
import com.quickfiles.app.storage.LOCAL_SOURCE
import com.quickfiles.app.storage.StorageLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * A burst of PiP "+10초" actions must not run past the end of the file. Landing on the duration
 * finished the item, the folder playlist started the next video and PiP resized to its aspect.
 * Generates its own 4:3 and 16:9 clips, so it needs no phone arguments, only real PiP support.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PipSeekDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph get() = AppGraph.get(context)
    private val relative = "Download/QuickFilesTest/validation-pip-seek"
    private val directory = File(Environment.getExternalStorageDirectory(), relative)

    @After fun stop() {
        try { cleanupPlayerTest(graph.playback) } finally { directory.deleteRecursively() }
    }

    private fun waitFor(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("Timed out waiting for $what")
            SystemClock.sleep(50)
        }
    }

    private fun <T> main(block: () -> T): T = runBlocking(Dispatchers.Main) { block() }

    @Test fun forwardBurstInPipStaysInFileAndKeepsWindowSize() {
        Assume.assumeTrue("Needs PiP", context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
        directory.deleteRecursively(); directory.mkdirs()
        encode(File(directory, "a.mp4"), 640, 480, 40)
        encode(File(directory, "b.mp4"), 640, 360, 10)
        val entries = runBlocking { graph.repository.local.list(StorageLocation(LOCAL_SOURCE, relative)) }.sortedBy { it.name }
        val first = entries.single { it.name == "a.mp4" }
        runBlocking { graph.dao.save(com.quickfiles.app.data.SavedValue("playback:${first.id}", "0")); graph.playback.play(first, entries) }
        waitFor(30_000, "playback") { graph.playback.state.value.playing || graph.playback.state.value.error != null }
        assertEquals(null, graph.playback.state.value.error)

        val monitor = instrumentation.addMonitor(PlayerActivity::class.java.name, null, false)
        val player = try {
            context.startActivity(Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as PlayerActivity
        } finally { instrumentation.removeMonitor(monitor) }
        waitFor(10_000, "player view") { main { player.hasWindowFocus() } }
        main { player.enterPictureInPictureMode(player.buildPipParams()) }
        waitFor(10_000, "PiP") { player.isInPictureInPictureMode }
        fun window() = main { player.window.decorView.run { width to height } }
        var settled = window()
        var stableSince = SystemClock.elapsedRealtime()
        waitFor(10_000, "PiP window size to settle") {
            val now = window()
            if (now != settled) { settled = now; stableSince = SystemClock.elapsedRealtime(); false }
            else SystemClock.elapsedRealtime() - stableSince > 1_500
        }
        val controller = graph.playback.controller!!
        val mediaId = main { controller.currentMediaItem!!.mediaId }
        val aspect = main { player.buildPipParams().aspectRatio }
        val duration = graph.playback.details.value.duration
        assertTrue("duration=$duration", duration in 39_000L..41_000L)

        // Pause so the guarded last second cannot play out into the next file during the check.
        main { controller.pause() }
        val forward = main { player.buildPipParams().actions.single { it.title.toString() == "+10초" } }.actionIntent
        repeat(8) { forward.send(); SystemClock.sleep(60) }
        waitFor(10_000, "seeks to finish") { main { controller.playbackState == androidx.media3.common.Player.STATE_READY || controller.playbackState == androidx.media3.common.Player.STATE_ENDED } }
        SystemClock.sleep(2_000)

        val after = main { Triple(controller.currentMediaItem?.mediaId, controller.currentPosition, controller.playbackState) }
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "PIP_SEEK window=$settled->${window()} aspect=$aspect->${main { player.buildPipParams().aspectRatio }} " +
                "sameItem=${after.first == mediaId} position=${after.second} duration=$duration state=${after.third}\n")
        })
        assertEquals("+10초 must not leave the current file", mediaId, after.first)
        assertTrue("position=${after.second}", after.second in (duration - SEEK_END_GUARD_MS - 200)..(duration - SEEK_END_GUARD_MS + 200))
        assertEquals(aspect, main { player.buildPipParams().aspectRatio })
        assertEquals("PiP window size", settled, window())
        assertTrue(player.isInPictureInPictureMode)
    }

    private fun encode(target: File, width: Int, height: Int, seconds: Int) {
        val fps = 5
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 300_000); setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            var track = -1; var frame = 0; var inputDone = false
            val total = seconds * fps
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val time = frame * 1_000_000L / fps
                        if (frame == total) { codec.queueInputBuffer(index, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else {
                            val image = codec.getInputImage(index)!!
                            image.planes.forEachIndexed { plane, p ->
                                val w = if (plane == 0) width else width / 2; val h = if (plane == 0) height else height / 2
                                for (y in 0 until h) for (x in 0 until w) {
                                    p.buffer.put(y * p.rowStride + x * p.pixelStride, (if (plane == 0) (frame * 4 + x) and 0xff else 128).toByte())
                                }
                            }
                            codec.queueInputBuffer(index, 0, width * height * 3 / 2, time, 0); frame++
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                else if (out >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) muxer.writeSampleData(track, codec.getOutputBuffer(out)!!, info)
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            codec.stop(); muxer.stop()
        } finally { codec.release(); muxer.release() }
    }
}
