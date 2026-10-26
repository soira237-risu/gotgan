package com.quickfiles.app

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.data.ExplorerSettings
import com.quickfiles.app.data.GridDensity
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.ExplorerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

class CompactNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: ExplorerViewModel
    private lateinit var directory: File
    private lateinit var location: StorageLocation
    private lateinit var settings: ExplorerSettings
    private val graph get() = AppGraph.get(compose.activity)

    @Before fun prepare() {
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        directory = File(root, "validation-compact-${java.util.UUID.randomUUID()}").canonicalFile
        require(directory.toPath().startsWith(root.toPath()) && directory != root && directory.mkdirs())
        repeat(120) { File(directory, "item-${it.toString().padStart(3, '0')}-긴 파일 이름.txt").writeText("Test only") }
        val image = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        File(directory, "image.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        location = StorageLocation(LOCAL_SOURCE, directory.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        settings = runBlocking { graph.settings.settings.first() }
        runBlocking { graph.settings.save(settings.copy(grid = true, density = GridDensity.NORMAL)) }
        compose.waitUntil(20_000) { graph.workspace.state.value.ready }
        compose.runOnIdle {
            graph.workspace.activate(0); graph.workspace.split(false); graph.workspace.panel(null)
            model = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java)
            model.navigate(location)
        }
        compose.waitUntil(20_000) { !model.state.value.loading && model.state.value.entries.size == 121 }
    }

    @After fun cleanup() {
        if (::model.isInitialized) compose.runOnIdle { model.home() }
        if (::settings.isInitialized) runBlocking { graph.settings.save(settings) }
        if (::directory.isInitialized) {
            val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
            require(directory.canonicalFile.toPath().startsWith(root.toPath()) && directory.name.startsWith("validation-compact-"))
            directory.deleteRecursively()
        }
    }

    private fun point(index: Int, grid: Boolean = true): Offset {
        val container = compose.onNodeWithTag(if (grid) "folder-grid" else "folder-list").fetchSemanticsNode().boundsInRoot
        val item = model.state.value.entries[index]
        return compose.onNodeWithTag("entry-${item.location.path}").fetchSemanticsNode().boundsInRoot.center - container.topLeft
    }

    @Test fun selectionModeStartsTwoFingerPaintWithoutAnotherLongPress() {
        compose.onNodeWithTag("entry-${model.state.value.entries[0].location.path}").performTouchInput { longClick() }
        compose.waitForIdle()
        val collection = compose.onNodeWithTag("folder-grid")
        val a = point(1); val b = point(2); val c = point(4)
        val density = model.settings.value.density
        val anchor = model.currentViewportAnchor()
        collection.performTouchInput {
            down(0, a); moveTo(0, b, 32); down(1, c)
            moveTo(1, c + Offset(1f, 1f), 16); up(1); moveTo(0, a, 32); up(0)
        }
        compose.waitForIdle()
        assertTrue("Selection mode must paint immediately after lifting the long-press finger",
            model.state.value.selected.containsAll(listOf(0, 1, 2, 4).map { model.state.value.entries[it].id }))
        assertEquals("Painting must not change grid density", density, model.settings.value.density)
        assertEquals("Painting away from edges must not scroll", anchor, model.currentViewportAnchor())
        assertNull(model.state.value.preview)
        collection.performTouchInput { down(a); moveTo(b, 32); moveTo(a, 32); up() }
        compose.waitForIdle()
        assertFalse(model.state.value.entries[1].id in model.state.value.selected)
        assertFalse(model.state.value.entries[2].id in model.state.value.selected)
        assertTrue(model.state.value.entries[4].id in model.state.value.selected)
    }

    @Test fun selectionModeFastListPaintAndTapsUseTheSameSelection() {
        runBlocking { graph.settings.update { it.copy(grid = false) } }
        compose.onNodeWithTag("entry-${model.state.value.entries[0].location.path}").performTouchInput { longClick() }
        compose.waitForIdle()
        val collection = compose.onNodeWithTag("folder-list")
        val a = point(1, false); val b = point(4, false)
        val anchor = model.currentViewportAnchor()
        collection.performTouchInput { down(a); moveTo(b, 32); up() }
        compose.waitForIdle()
        assertEquals((0..4).map { model.state.value.entries[it].id }.toSet(), model.state.value.selected)
        assertEquals(anchor, model.currentViewportAnchor())
        val selected = model.state.value.selected
        collection.performTouchInput { down(a) }
        compose.waitForIdle()
        assertEquals("A pending tap must not toggle on DOWN", selected, model.state.value.selected)
        collection.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(selected - model.state.value.entries[1].id, model.state.value.selected)
        assertNull(model.state.value.preview)
    }

    @Test fun selectionModeStillScrollsFromTheListGutter() {
        runBlocking { graph.settings.update { it.copy(grid = false) } }
        val seed = model.state.value.entries[0]
        compose.onNodeWithTag("entry-${seed.location.path}").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("folder-list").performTouchInput {
            down(Offset(1f, height * .8f)); moveTo(Offset(1f, height * .3f), 160); up()
        }
        compose.waitForIdle()
        assertTrue("The blank side gutter remains available for manual scrolling", model.currentViewportAnchor().index > 0)
        assertEquals(setOf(seed.id), model.state.value.selected)
        assertNull(model.state.value.preview)
    }

    @Test fun dragAddsAndRemovesWithTwoFingersWithoutOpeningFiles() {
        val collection = compose.onNodeWithTag("folder-grid")
        val a = point(1); val b = point(2); val c = point(4)
        collection.performTouchInput { down(0, a) }
        compose.mainClock.advanceTimeBy(650)
        compose.waitUntil(5_000) { model.state.value.selected.isNotEmpty() }
        collection.performTouchInput { moveTo(0, b, 100); down(1, c); moveTo(1, c + Offset(1f, 1f), 50); up(1); moveTo(0, a, 100); up(0) }
        compose.waitForIdle()
        val expected = listOf(1, 2, 4).map { model.state.value.entries[it].id }.toSet()
        assertTrue(model.state.value.selected.containsAll(expected))
        assertNull(model.state.value.preview)
        collection.performTouchInput { down(0, a) }
        compose.mainClock.advanceTimeBy(650)
        compose.waitUntil(5_000) { model.state.value.entries[1].id !in model.state.value.selected }
        collection.performTouchInput { moveTo(0, b, 100); moveTo(0, a, 100); up(0) }
        compose.waitForIdle()
        assertFalse(model.state.value.entries[2].id in model.state.value.selected)
        assertTrue(model.state.value.entries[4].id in model.state.value.selected)
    }

    @Test fun listPaintScrollAndHomePreserveOtherPane() {
        runBlocking { graph.settings.update { it.copy(grid = false) } }
        val collection = compose.onNodeWithTag("folder-list")
        val a = point(1, false); val b = point(3, false)
        collection.performTouchInput { down(a) }
        compose.mainClock.advanceTimeBy(650)
        compose.waitUntil(5_000) { model.state.value.selected.isNotEmpty() }
        collection.performTouchInput { moveTo(b, 150); up() }
        compose.waitForIdle()
        assertTrue(model.state.value.selected.containsAll((1..3).map { model.state.value.entries[it].id }))
        compose.runOnIdle { model.clearSelection() }
        collection.performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(model.currentViewportAnchor().index > 0)
        lateinit var other: ExplorerViewModel
        compose.runOnIdle { other = ViewModelProvider(compose.activity).get("B", ExplorerViewModel::class.java) }
        val untouched = other.state.value.location
        compose.onNodeWithTag("storage-home-button").performClick()
        compose.onNodeWithTag("storage-home").assertIsDisplayed()
        assertNull(model.state.value.location)
        assertEquals(untouched, other.state.value.location)
    }

    @Test fun imageAnimatedCloseAndSystemBackRetainListingWithoutReads() {
        val entry = model.state.value.entries.single { it.isImage }
        val reads = StorageReadMetrics.count(LOCAL_SOURCE)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val frames = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val frameThread = android.os.HandlerThread("viewer-frame-test").apply { start() }
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames.add(metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION))
        }
        val memoryBefore = android.os.Debug.getNativeHeapAllocatedSize()
        instrumentation.runOnMainSync { compose.activity.window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(frameThread.looper)) }
        try { repeat(30) { index ->
            compose.onNodeWithTag("entry-${entry.location.path}").performClick()
            compose.waitUntil(10_000) { model.state.value.preview?.asset != null }
            compose.onNodeWithTag("preview").assertIsDisplayed()
            compose.onNodeWithTag("storage-home-button").assertDoesNotExist()
            if (index == 2) {
                val token = model.state.value.preview!!.token
                instrumentation.runOnMainSync {
                    compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 200f, 0f, androidx.activity.BackEventCompat.EDGE_LEFT))
                    compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(androidx.activity.BackEventCompat(100f, 200f, .6f, androidx.activity.BackEventCompat.EDGE_LEFT))
                }
                compose.mainClock.advanceTimeBy(64)
                instrumentation.runOnMainSync { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
                compose.waitForIdle()
                assertEquals("Cancelled predictive Back keeps the image", token, model.state.value.preview?.token)
            }
            if (index % 2 == 1) instrumentation.runOnMainSync { compose.activity.onBackPressedDispatcher.onBackPressed() }
            else compose.onNodeWithTag("close-preview").performClick()
            compose.waitUntil(10_000) { model.state.value.preview == null }
            compose.onNodeWithTag("folder-grid").assertIsDisplayed()
            assertEquals(location, model.state.value.location)
            assertEquals(reads, StorageReadMetrics.count(LOCAL_SOURCE))
        } } finally {
            instrumentation.runOnMainSync { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) }
            frameThread.quitSafely()
        }
        val sorted = frames.sorted()
        val p95 = sorted.getOrNull((sorted.size * .95).toInt().coerceAtMost((sorted.size - 1).coerceAtLeast(0)))?.div(1_000_000.0)
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream",
            "IMAGE_TRANSITIONS cycles=30 refetch=0 frames=${sorted.size} frameTotalP95Ms=$p95 nativeHeapBefore=$memoryBefore nativeHeapAfter=${android.os.Debug.getNativeHeapAllocatedSize()}\n") })
    }

    @Test fun edgePaintScrollsAndIdlePinchStillChangesDensity() {
        val collection = compose.onNodeWithTag("folder-grid")
        val first = point(1)
        collection.performTouchInput { down(first) }
        compose.mainClock.advanceTimeBy(650)
        compose.waitUntil(5_000) { model.state.value.selected.isNotEmpty() }
        collection.performTouchInput { moveTo(Offset(width / 2f, height - 8f), 200) }
        compose.waitUntil(5_000) { model.currentViewportAnchor().index > 0 }
        collection.performTouchInput { up() }
        compose.waitForIdle()
        assertTrue(model.state.value.selected.size > 3)
        compose.runOnIdle { model.clearSelection() }
        collection.performTouchInput {
            down(0, Offset(width * .25f, height * .45f))
            down(1, Offset(width * .75f, height * .45f))
            moveTo(0, Offset(width * .4f, height * .45f), 100)
            moveTo(1, Offset(width * .6f, height * .45f), 100)
            up(0); up(1)
        }
        compose.waitUntil(10_000) { model.settings.value.density == GridDensity.SMALL }
        assertTrue(model.state.value.selected.isEmpty())
    }
}
