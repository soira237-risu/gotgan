package com.quickfiles.app

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.data.*
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.ExplorerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

@OptIn(ExperimentalTestApi::class)
class MaterialMotionDeviceTest {
    private val durationScale = object : MotionDurationScale {
        override var scaleFactor by mutableFloatStateOf(1f)
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>(effectContext = durationScale)
    private val graph get() = AppGraph.get(compose.activity)
    private lateinit var original: ExplorerSettings
    private lateinit var run: File
    private lateinit var location: StorageLocation
    private lateinit var browser: ExplorerViewModel

    @Before fun prepare() {
        assertTrue(Environment.isExternalStorageManager())
        original = runBlocking { graph.settings.settings.first() }
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        run = File(root, "motion-${UUID.randomUUID()}").canonicalFile
        require(run.toPath().startsWith(root.toPath()) && run != root && run.mkdirs())
        repeat(320) { require(File(run, "folder-${it.toString().padStart(3, '0')}").mkdir()) }
        val child = File(run, "folder-299")
        File(child, "note.txt").writeText("Material motion fixture")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(60, 130, 210))
        File(child, "image.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        location = StorageLocation(LOCAL_SOURCE, run.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        runBlocking { graph.settings.save(original.copy(grid = false, theme = ThemeMode.LIGHT, colorTheme = ColorTheme.BLUE)) }
        compose.waitUntil(15_000) { graph.workspace.state.value.ready }
        compose.runOnIdle {
            graph.workspace.activate(0); graph.workspace.split(false); graph.workspace.panel(null)
            browser = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java)
            browser.home()
        }
        compose.waitUntil(15_000) { browser.state.value.profilesReady }
        compose.runOnIdle { browser.navigate(location) }
        compose.waitUntil(15_000) { !browser.state.value.loading && browser.state.value.entries.size == 320 }
        compose.waitForIdle()
    }

    @After fun restore() {
        compose.mainClock.autoAdvance = true
        runCatching { compose.onRoot().performTouchInput { cancel() } }
        durationScale.scaleFactor = 1f
        if (::browser.isInitialized) compose.runOnIdle { graph.workspace.panel(null); browser.home() }
        if (::original.isInitialized) runBlocking { graph.settings.save(original) }
        if (::run.isInitialized) InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "MOTION_ARTIFACTS=${run.absolutePath}\n")
        })
    }

    @Test fun backRespondsImmediatelyAndRestoresThe300thFolderWithoutRequery() {
        compose.onNodeWithTag("folder-list").performScrollToIndex(299)
        compose.waitForIdle()
        val parentEntries = browser.state.value.entries
        val anchor = compose.runOnUiThread { browser.currentViewportAnchor() }
        val folder = parentEntries[299]
        val tag = "entry-${folder.location.path}"
        val before = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag(tag).performClick()
        compose.waitUntil(15_000) { browser.state.value.location == folder.location && !browser.state.value.loading }
        val queries = StorageReadMetrics.count(LOCAL_SOURCE)
        val back = requireNotNull(compose.onNodeWithTag("navigate-up").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnUiThread {
            assertTrue(back())
            assertEquals("Back must change location before any animation frame", location, browser.state.value.location)
            assertSame(parentEntries, browser.state.value.entries)
            assertEquals(anchor, browser.state.value.anchor)
        }
        compose.waitForIdle()
        val after = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertEquals(before.toDouble(), after.toDouble(), 1.0)
        assertEquals("Returning to a cached folder must not relist it", queries, StorageReadMetrics.count(LOCAL_SOURCE))
        capture("folder-restored")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(5_000) { automation.rootInActiveWindow?.packageName?.toString() == compose.activity.packageName }
        assertEquals(compose.activity.packageName, automation.rootInActiveWindow?.packageName?.toString())
        val bitmap = requireNotNull(automation.takeScreenshot())
        val screens = File(run, "screens").apply { require(mkdirs() || isDirectory) }
        File(screens, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun pixel(tag: String): Int {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height - 8].toArgb()
    }

    private fun click(tag: String) {
        val action = requireNotNull(compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnUiThread { assertTrue(action()) }
    }

    @Test fun selectionAnimatesPixelsAndKeepsTheRowSizeAndListing() {
        val entry = browser.state.value.entries.first()
        val tag = "entry-${entry.location.path}"
        val normal = pixel(tag)
        val height = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height
        val entries = browser.state.value.entries
        val queries = StorageReadMetrics.count(LOCAL_SOURCE)
        val select = requireNotNull(compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnLongClick].action)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { assertTrue(select()); assertEquals(setOf(entry.id), browser.state.value.selected) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(tag).assertIsSelected()
        compose.mainClock.advanceTimeBy(48)
        val middle = pixel(tag)
        capture("selection-middle")
        compose.mainClock.advanceTimeBy(200)
        val selected = pixel(tag)
        capture("selection-final")
        assertNotEquals(normal, selected)
        assertNotEquals("Selection needs an intermediate color", normal, middle)
        assertNotEquals("Selection must not jump to its final color", selected, middle)
        assertEquals(height, compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height, 1f)
        assertSame(entries, browser.state.value.entries)
        assertEquals(queries, StorageReadMetrics.count(LOCAL_SOURCE))
        click("navigate-up")
        assertTrue(browser.state.value.selected.isEmpty())
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag(tag).assertIsNotSelected()
        assertEquals(normal, pixel(tag))
    }

    @Test fun sheetAnimatesCanCloseDuringEntranceAndTracksHeaderDrag() {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { graph.workspace.panel("폴더 전환") }
        compose.mainClock.advanceTimeByFrame()
        val initial = compose.onNodeWithTag("workspace-sheet").fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.advanceTimeBy(64)
        val middle = compose.onNodeWithTag("workspace-sheet").fetchSemanticsNode().boundsInRoot.top
        assertTrue("The panel must move upward while entering", middle < initial)
        capture("sheet-entering")
        val close = requireNotNull(compose.onNodeWithContentDescription("폴더 전환 닫기").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnUiThread { assertTrue(close()); assertNull(graph.workspace.state.value.panel) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("workspace-sheet").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { graph.workspace.panel("폴더 전환") }
        compose.waitForIdle()
        val top = compose.onNodeWithTag("workspace-sheet").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("workspace-sheet-header").performTouchInput { swipe(center, center + Offset(0f, 20.dp.toPx()), 500) }
        compose.onNodeWithTag("workspace-sheet").assertIsDisplayed()
        assertEquals(top, compose.onNodeWithTag("workspace-sheet").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("workspace-sheet-header").performTouchInput { down(center); moveBy(Offset(0f, 90.dp.toPx()), 300) }
        val dragged = compose.onNodeWithTag("workspace-sheet").fetchSemanticsNode().boundsInRoot.top
        assertTrue("The panel must follow the finger before release", dragged > top + 40 * compose.activity.resources.displayMetrics.density)
        assertEquals("폴더 전환", graph.workspace.state.value.panel)
        capture("sheet-dragging")
        compose.onNodeWithTag("workspace-sheet-header").performTouchInput { up() }
        compose.onNodeWithTag("workspace-sheet").assertDoesNotExist()
        assertNull(graph.workspace.state.value.panel)
    }

    @Test fun imageTransitionKeepsSelectionPixelsAndCachedListing() {
        compose.onNodeWithTag("folder-list").performScrollToIndex(299)
        click("entry-${location.child("folder-299").path}")
        compose.waitUntil(15_000) { !browser.state.value.loading && browser.state.value.entries.size == 2 }
        val entries = browser.state.value.entries
        val image = entries.single { it.isImage }
        val note = entries.single { it.isText }
        compose.onNodeWithTag("entry-${note.location.path}").performSemanticsAction(SemanticsActions.OnLongClick) { assertTrue(it()) }
        val queries = StorageReadMetrics.count(LOCAL_SOURCE)
        val anchor = compose.runOnUiThread { browser.currentViewportAnchor() }
        compose.runOnIdle { browser.open(image) }
        compose.waitUntil(15_000) { browser.state.value.preview?.let { !it.loading && it.asset != null } == true }
        compose.onNodeWithTag("image-gesture-surface").assertIsDisplayed()
        compose.waitUntil(10_000) {
            val pixels = compose.onNodeWithTag("image-gesture-surface").captureToImage().toPixelMap()
            val actual = pixels[pixels.width / 2, pixels.height / 2].toArgb()
            val expected = android.graphics.Color.rgb(60, 130, 210)
            (0..2).all { shift -> kotlin.math.abs(((actual ushr (shift * 8)) and 255) - ((expected ushr (shift * 8)) and 255)) <= 2 }
        }
        capture("image-open")
        compose.onNodeWithTag("close-preview").performClick()
        compose.waitUntil(10_000) { browser.state.value.preview == null }
        compose.onNodeWithTag("entry-${image.location.path}").assertIsDisplayed()
        assertSame(entries, browser.state.value.entries)
        assertEquals(anchor, browser.state.value.anchor)
        assertEquals(setOf(note.id), browser.state.value.selected)
        assertEquals(queries, StorageReadMetrics.count(LOCAL_SOURCE))
        capture("image-return")
    }

    @Test fun zeroDurationScaleSnapsWithoutIntermediatePixels() {
        durationScale.scaleFactor = 0f
        compose.mainClock.autoAdvance = false
        val tag = "entry-${browser.state.value.entries.first().location.path}"
        val normal = pixel(tag)
        val select = requireNotNull(compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnLongClick].action)
        compose.runOnUiThread { assertTrue(select()) }
        val frames = (1..3).map { compose.mainClock.advanceTimeByFrame(); pixel(tag) }
        compose.onNodeWithTag(tag).assertIsSelected()
        compose.mainClock.advanceTimeBy(200)
        val end = pixel(tag)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "ZERO_MOTION normal=$normal frames=$frames end=$end\n")
        })
        assertEquals("Scale zero must finish within composition/animation startup frames", end, frames.last())
        assertTrue("Scale zero must not draw an interpolated color", frames.all { it == normal || it == end })
        compose.runOnUiThread { graph.workspace.panel("폴더 전환") }
        val positions = (1..3).map {
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("workspace-sheet").fetchSemanticsNode().boundsInRoot.top
        }
        compose.mainClock.advanceTimeBy(200)
        val finalTop = compose.onNodeWithTag("workspace-sheet").fetchSemanticsNode().boundsInRoot.top
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "ZERO_SHEET frames=$positions end=$finalTop\n")
        })
        assertEquals("Scale zero must settle after startup layout", finalTop, positions.last(), 1f)
        assertTrue("Scale zero must not draw an interpolated position", positions.all {
            kotlin.math.abs(it - positions.first()) < 1f || kotlin.math.abs(it - finalTop) < 1f
        })
        capture("scale-zero")
    }
}
