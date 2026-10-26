package com.quickfiles.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.quickfiles.app.data.SavedValue
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.ExplorerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class ImageViewerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun doubleTapImmersivePullDownAndTrashRestore() {
        val graph = AppGraph.get(compose.activity)
        val original = runBlocking { graph.settings.settings.first() }
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val run = File(root, "viewer-${UUID.randomUUID()}").canonicalFile
        require(run.toPath().startsWith(root.toPath()) && run != root && run.mkdirs())
        val bitmap = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(35, 85, 120)) }
        repeat(40) { index -> File(run, "image-${index.toString().padStart(3, '0')}.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        bitmap.recycle()
        val location = StorageLocation(LOCAL_SOURCE, run.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        lateinit var model: ExplorerViewModel
        var initialized = false
        var trashId: String? = null
        val evidence = File(run, "evidence")
        fun capture(name: String) {
            evidence.mkdirs()
            compose.waitForIdle()
            // Insets/surface transactions use the real system clock, outside Compose's test clock.
            android.os.SystemClock.sleep(1000)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { screenshot ->
                File(evidence, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            File(evidence, "$name-ui.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() })
        }
        try {
            runBlocking { graph.settings.save(original.copy(grid = false)) }
            compose.waitUntil(15_000) { graph.workspace.state.value.ready }
            compose.runOnIdle {
                graph.workspace.activate(0); graph.workspace.split(false)
                model = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java)
                initialized = true; model.home()
            }
            compose.waitUntil(15_000) { model.state.value.profilesReady }
            compose.runOnIdle { model.navigate(location) }
            compose.waitUntil(30_000) { model.state.value.entries.size == 40 && !model.state.value.loading }
            compose.onNodeWithTag("folder-list").performScrollToIndex(20)
            compose.waitForIdle()
            val anchor = model.state.value.anchor
            val reads = StorageReadMetrics.count(LOCAL_SOURCE)
            val entry = model.state.value.entries[20]
            val keepSelected = model.state.value.entries[21]
            compose.runOnIdle { model.select(keepSelected); model.open(entry) }
            compose.waitUntil(15_000) { model.state.value.preview?.loading == false }
            val image = compose.onNodeWithTag("image-gesture-surface")
            compose.waitUntil(15_000) { image.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "화면 맞춤" }
            capture("image-controls")
            image.performTouchInput { doubleClick() }
            image.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "확대됨"))
            capture("image-zoomed")
            image.performTouchInput { swipeDown(durationMillis = 160) }
            compose.onNodeWithTag("preview").assertExists()
            image.performTouchInput { doubleClick() }
            image.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "화면 맞춤"))
            image.performTouchInput { click() }
            // A single tap is dispatched after the double-tap recognition window.
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("close-preview").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("close-preview").assertDoesNotExist()
            compose.onNodeWithTag("viewer-trash").assertDoesNotExist()
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()) == false }
            capture("image-immersive")
            image.performTouchInput { click() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("close-preview").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("close-preview").assertExists()
            // A short slow drag snaps back. A directional fling closes only at fit.
            image.performTouchInput { swipe(center, center + Offset(0f, 20f), 500) }
            compose.onNodeWithTag("preview").assertExists()
            image.performTouchInput { swipe(center, Offset(center.x, height * .88f), 160) }
            compose.waitUntil(5_000) { model.state.value.preview == null }
            assertEquals(anchor, model.state.value.anchor)
            assertTrue(keepSelected.id in model.state.value.selected)
            assertEquals(reads, StorageReadMetrics.count(LOCAL_SOURCE))
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()) == true }
            compose.runOnIdle { model.open(entry) }
            compose.waitUntil(15_000) { model.state.value.preview?.loading == false }
            compose.onNodeWithTag("viewer-trash").performClick()
            compose.onNodeWithText("취소").performClick()
            assertTrue(File(run, entry.name).exists())
            compose.onNodeWithTag("viewer-trash").performClick()
            capture("image-trash-confirmation")
            compose.onNodeWithTag("confirm-viewer-trash").performClick()
            compose.waitUntil(15_000) { model.state.value.preview == null }
            compose.waitUntil(30_000) { !File(run, entry.name).exists() }
            assertNull("Queued operations must not open acknowledgement dialogs", model.state.value.notice)
            assertTrue(keepSelected.id in model.state.value.selected)
            // The worker renames the payload before committing its durable journal state.
            val trash = runBlocking { kotlinx.coroutines.withTimeout(30_000) {
                graph.dao.trashItems().first { items -> items.any { it.original == entry.location.path && it.state == "TRASHED" } }
                    .single { it.original == entry.location.path && it.state == "TRASHED" }
            } }
            trashId = trash.id
            runBlocking {
                graph.operations.enqueue("RESTORE", emptyList(), null, extra = org.json.JSONObject().put("trashIds", org.json.JSONArray(listOf(trash.id))))
                graph.operations.drain()
            }
            assertTrue(File(run, entry.name).exists())
        } finally {
            if (initialized) compose.runOnIdle { model.closePreview(); model.home() }
            runBlocking {
                graph.settings.save(original); graph.dao.removeSaved("recent:${location.key}")
                trashId?.let { graph.dao.forgetTrash(it) }
            }
            require(run.canonicalFile.toPath().startsWith(root.toPath()) && run != root)
            // Preserve only this run's screenshots/UI evidence for adb pull.
            run.listFiles()?.filter { it != evidence }?.forEach { it.deleteRecursively() }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("stream", "VIEWER_EVIDENCE ${evidence.absolutePath}\n")
            })
        }
    }
}
