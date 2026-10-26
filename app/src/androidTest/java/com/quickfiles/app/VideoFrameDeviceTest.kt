package com.quickfiles.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Environment
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.data.SavedValue
import com.quickfiles.app.playback.PlayerActivity
import com.quickfiles.app.storage.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.util.Collections

/** Uses only the previous release's public APIs, allowing the same test APK on both builds. */
open class VideoFrameDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    protected open fun decoderSnapshot(): JSONObject? = null
    private fun reportDecoder(before: JSONObject?, after: JSONObject?): JSONObject {
        val result = JSONObject().put("available", before != null && after != null)
        if (before == null || after == null) return result
        val same = before.getInt("generation") == after.getInt("generation")
        result.put("sameGeneration", same)
        if (same) {
            for (key in listOf("rendered", "dropped", "skipped", "decoderInitializations", "decoderReleases")) {
                result.put(key, after.getInt(key) - before.getInt(key))
            }
            val count = after.getInt("processingSamples") - before.getInt("processingSamples")
            result.put("processingSamples", count).put("meanProcessingOffsetUs",
                if (count > 0) (after.getLong("processingOffsetUs") - before.getLong("processingOffsetUs")).toDouble() / count else JSONObject.NULL)
        }
        return result
    }
    @Test fun thirtySecondsOfVideoWithSurfaceEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("playerMediaDir")
        Assume.assumeTrue(path != null)
        val directory = File(Environment.getExternalStorageDirectory(), path!!).canonicalFile
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        require(directory.toPath().startsWith(root.toPath()) && directory.name.startsWith("validation-player-"))
        val graph = AppGraph.get(compose.activity)
        val media = runBlocking { graph.repository.local.stat(StorageLocation(LOCAL_SOURCE, "$path/landscape.mp4")) }
        runBlocking {
            graph.dao.save(SavedValue("playback:${media.id}", "0"))
            graph.playback.play(media, listOf(media))
        }
        compose.waitUntil(20000) { graph.playback.state.value.playing }
        runBlocking(Dispatchers.Main) { graph.playback.controller!!.seekTo(0) }
        val monitor = instrumentation.addMonitor(PlayerActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { compose.activity.startActivity(Intent(compose.activity, PlayerActivity::class.java)) }
        val activity = instrumentation.waitForMonitorWithTimeout(monitor, 15000) as PlayerActivity
        instrumentation.removeMonitor(monitor)
        instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        SystemClock.sleep(5000)
        val samples = Collections.synchronizedList(mutableListOf<Double>())
        val handlerThread = android.os.HandlerThread("video-frame-evidence").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> samples.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0) }
        instrumentation.runOnMainSync { activity.window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(handlerThread.looper)) }
        try {
            val before = runBlocking(Dispatchers.Main) { graph.playback.controller!!.currentPosition }
            val decoderBefore = decoderSnapshot()
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "VIDEO_READY\n") })
            repeat(30) { second ->
                SystemClock.sleep(1000); assertNull(graph.playback.state.value.error)
                if (second == 24) instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "VIDEO_DECODER_25S ${reportDecoder(decoderBefore, decoderSnapshot())}\n")
                })
            }
            val after = runBlocking(Dispatchers.Main) { graph.playback.controller!!.currentPosition }
            val snapshot = synchronized(samples) { samples.sorted() }
            val report = JSONObject().put("frames", snapshot.size).put("positionDeltaMs", after - before)
                .put("p95UiFrameMs", snapshot.takeIf { it.isNotEmpty() }?.get(((snapshot.size - 1) * .95).toInt()) ?: JSONObject.NULL)
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "VIDEO_FRAMES $report\n") })
            assertTrue("Video must keep advancing: delta=${after - before} ms", after >= before + 28000)
        } finally {
            instrumentation.runOnMainSync { activity.window.removeOnFrameMetricsAvailableListener(listener); activity.finish() }
            handlerThread.quitSafely(); runBlocking { graph.playback.stop() }
        }
    }
}
