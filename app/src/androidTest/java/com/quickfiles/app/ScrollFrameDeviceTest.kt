package com.quickfiles.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.data.GridDensity
import com.quickfiles.app.storage.LOCAL_SOURCE
import com.quickfiles.app.storage.StorageLocation
import com.quickfiles.app.storage.StorageReadMetrics
import com.quickfiles.app.ui.ExplorerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.Collections
import java.util.UUID

/** Same gestures and fixtures on the baseline and updated release; emulator numbers are comparative. */
class ScrollFrameDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun scrollImageListAndGrid() {
        val graph = AppGraph.get(compose.activity)
        val original = runBlocking { graph.settings.settings.first() }
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val run = File(root, "scroll-${UUID.randomUUID()}").canonicalFile
        require(run.toPath().startsWith(root.toPath()) && run != root && run.mkdirs())
        // All file creation runs on the instrumentation thread, never the app's main thread.
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { textSize = 44f; color = Color.WHITE }
        repeat(400) { index ->
            canvas.drawColor(Color.rgb(40 + index % 150, 65, 110))
            canvas.drawText("Image $index", 32f, 200f, paint)
            File(run, "image-${index.toString().padStart(4, '0')}.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        bitmap.recycle()
        val location = StorageLocation(LOCAL_SOURCE, run.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        val evidence = File(run, "evidence")
        val frames = Collections.synchronizedList(mutableListOf<Double>())
        val thread = HandlerThread("scroll-frame-metrics").apply { start() }
        val recording = java.util.concurrent.atomic.AtomicBoolean(false)
        val stateEmissions = java.util.concurrent.atomic.AtomicInteger(0)
        val observation = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (recording.get()) frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
        }
        lateinit var model: ExplorerViewModel
        var initialized = false
        try {
            compose.waitUntil(15_000) { graph.workspace.state.value.ready }
            compose.runOnIdle {
                graph.workspace.activate(0); graph.workspace.split(false)
                model = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java)
                initialized = true
                model.home()
                compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
            }
            compose.waitUntil(15_000) { model.state.value.profilesReady }
            compose.runOnIdle { model.navigate(location) }
            compose.waitUntil(30_000) { !model.state.value.loading && model.state.value.entries.size == 400 }
            observation.launch { model.state.collect { if (recording.get()) stateEmissions.incrementAndGet() } }
            evidence.mkdirs()
            for (grid in listOf(false, true)) {
                runBlocking { graph.settings.save(original.copy(grid = grid, density = GridDensity.NORMAL)) }
                val tag = if (grid) "folder-grid" else "folder-list"
                compose.waitForIdle()
                val collection = compose.onNodeWithTag(tag)
                val queries = StorageReadMetrics.count(LOCAL_SOURCE)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "SCROLL_READY ${if (grid) "grid" else "list"}\n") })
                repeat(3) { runIndex ->
                    collection.performScrollToIndex(0)
                    compose.waitForIdle()
                    val bounds = collection.fetchSemanticsNode().boundsInRoot
                    val x = bounds.center.x.toInt()
                    val startY = (bounds.top + bounds.height * .80f).toInt()
                    val endY = (bounds.top + bounds.height * .25f).toInt()
                    frames.clear(); stateEmissions.set(0); recording.set(true)
                    repeat(6) {
                        // Pace input using the real system clock. Compose's synthetic
                        // gesture clock is useful for assertions, not physical frame timing.
                        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input swipe $x $startY $x $endY 350").use { descriptor ->
                            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                        }
                        android.os.SystemClock.sleep(900)
                    }
                    recording.set(false)
                    compose.waitForIdle()
                    val sorted = synchronized(frames) { frames.sorted() }
                    assertTrue("No rendered frames were captured", sorted.size > 20)
                    val result = JSONObject().put("mode", if (grid) "grid" else "list").put("run", runIndex + 1)
                        .put("frames", sorted.size).put("over16_67Ms", sorted.count { it > 16.67 })
                        .put("p50Ms", sorted[(sorted.size * .50).toInt().coerceAtMost(sorted.lastIndex)])
                        .put("p95Ms", sorted[(sorted.size * .95).toInt().coerceAtMost(sorted.lastIndex)])
                        .put("maxMs", sorted.last()).put("durationsMs", JSONArray(sorted))
                        .put("screenStateEmissions", stateEmissions.get())
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "SCROLL_FRAMES $result\n") })
                }
                assertEquals("Scrolling must not requery the folder", queries, StorageReadMetrics.count(LOCAL_SOURCE))
                evidence.mkdirs()
                val mode = if (grid) "grid" else "list"
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                    File(evidence, "$mode.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    screenshot.recycle()
                }
                File(evidence, "$mode-ui.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
                instrumentation.uiAutomation.executeShellCommand("dumpsys gfxinfo com.quickfiles.app framestats").use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> File(evidence, "$mode-gfxinfo.txt").outputStream().use { input.copyTo(it) } }
                }
            }
        } finally {
            observation.cancel()
            compose.runOnIdle { recording.set(false); compose.activity.window.removeOnFrameMetricsAvailableListener(listener); if (initialized) model.home() }
            thread.quitSafely()
            runBlocking { graph.settings.save(original); graph.dao.removeSaved("recent:${location.key}") }
            require(run.canonicalFile.toPath().startsWith(root.toPath()) && run != root)
            run.listFiles()?.filter { it != evidence }?.forEach { it.deleteRecursively() }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "SCROLL_EVIDENCE ${evidence.absolutePath}\n") })
        }
    }
}
