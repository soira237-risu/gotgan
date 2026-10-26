package com.quickfiles.app

import android.os.Environment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.quickfiles.app.storage.LOCAL_SOURCE
import com.quickfiles.app.storage.StorageLocation
import com.quickfiles.app.storage.StorageReadMetrics
import com.quickfiles.app.ui.ExplorerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Transitions while a real Compose drag still owns the pointer, before scroll settlement. */
class ViewportDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun activeDragSurvivesViewerAndTabRoundTripsWithoutRequery() {
        assertTrue("Grant all-files permission before device tests", Environment.isExternalStorageManager())
        val graph = AppGraph.get(compose.activity)
        val originalSettings = runBlocking { graph.settings.settings.first() }
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val run = File(root, "viewport-${UUID.randomUUID()}").canonicalFile
        require(run.toPath().startsWith(root.toPath()) && run != root && run.mkdirs())
        val location = StorageLocation(LOCAL_SOURCE, run.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        var model: ExplorerViewModel? = null
        var other: ExplorerViewModel? = null
        try {
            // Instrumentation-thread I/O; these are exclusively this run's files.
            repeat(120) { index ->
                File(run, "row-${index.toString().padStart(3, '0')}.txt").writeText("Viewport interruption fixture $index")
            }
            runBlocking { graph.settings.save(originalSettings.copy(grid = false)) }
            compose.waitUntil(15_000) { graph.workspace.state.value.ready }
            compose.runOnIdle {
                graph.workspace.activate(0); graph.workspace.split(false); graph.workspace.panel(null)
                model = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java)
                other = ViewModelProvider(compose.activity).get("B", ExplorerViewModel::class.java)
                model!!.home(); other!!.home()
            }
            val browser = requireNotNull(model)
            compose.waitUntil(15_000) { browser.state.value.profilesReady }
            compose.runOnIdle { browser.navigate(location) }
            compose.waitUntil(15_000) { !browser.state.value.loading && browser.state.value.entries.size == 120 }
            val entries = browser.state.value.entries
            val selected = entries[1]
            compose.onNodeWithTag("entry-${selected.location.path}").performTouchInput { longClick() }
            compose.waitForIdle()
            val queries = StorageReadMetrics.count(LOCAL_SOURCE)

            for (transition in listOf("viewer", "tab")) {
                // Keep DOWN active across the transition. A complete swipe followed by
                // runOnIdle would only test an already settled, persisted viewport.
                compose.onNodeWithTag("folder-list").performTouchInput {
                    // In selection mode, file drags paint; use the blank side gutter to scroll.
                    down(Offset(1f, height * .80f))
                    moveTo(Offset(1f, height * .55f), 120)
                    moveTo(Offset(1f, height * .30f), 120)
                }
                // Let layout/snapshotFlow consume MOVE without releasing the pointer.
                compose.waitForIdle()
                val interrupted = compose.runOnUiThread {
                    val latest = browser.currentViewportAnchor()
                    assertNotEquals("$transition must interrupt a position that has not settled", browser.state.value.anchor, latest)
                    assertTrue("The gesture must move beyond the initial row", latest.index > 0)
                    latest
                }
                val visible = entries.single { it.id == interrupted.fileId }
                val topBefore = compose.onNodeWithTag("entry-${visible.location.path}").fetchSemanticsNode().boundsInRoot.top

                compose.runOnUiThread {
                    if (transition == "viewer") browser.open(visible) else graph.workspace.activate(1)
                }
                // Release after changing context. Age the last movement sample so an
                // artificial UP cannot start a second fling behind the viewer.
                compose.onRoot().performTouchInput { advanceEventTime(1_000); up() }
                if (transition == "viewer") {
                    compose.waitUntil(15_000) { browser.state.value.preview?.document != null }
                    compose.onNodeWithTag("close-preview").performClick()
                } else {
                    compose.onNodeWithTag("storage-home").assertExists()
                    compose.runOnIdle { graph.workspace.activate(0) }
                }
                compose.waitForIdle()
                val topAfter = compose.onNodeWithTag("entry-${visible.location.path}")
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
                compose.runOnIdle {
                    assertEquals("$transition changed the folder", location, browser.state.value.location)
                    assertSame("$transition replaced the cached listing", entries, browser.state.value.entries)
                    assertEquals("$transition lost the in-progress anchor", interrupted, browser.state.value.anchor)
                    assertEquals("$transition lost selection", setOf(selected.id), browser.state.value.selected)
                    assertEquals("$transition refetched the directory", queries, StorageReadMetrics.count(LOCAL_SOURCE))
                }
                assertEquals("$transition changed the rendered row position", topBefore, topAfter, 1f)
            }
        } finally {
            // A failed assertion can leave the synthetic finger down; cancel it
            // before returning home so it cannot affect a following device test.
            runCatching { compose.onRoot().performTouchInput { cancel() } }
            compose.runOnIdle { model?.home(); other?.home(); graph.workspace.activate(0) }
            runBlocking { graph.settings.save(originalSettings); graph.dao.removeSaved("recent:${location.key}") }
            require(run.canonicalFile.toPath().startsWith(root.toPath()) && run.canonicalFile != root && run.name.startsWith("viewport-"))
            run.deleteRecursively()
        }
    }
}
