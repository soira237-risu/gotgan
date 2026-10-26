package com.quickfiles.app

import android.content.Intent
import android.os.Environment
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.quickfiles.app.playback.PlayerActivity
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.ExplorerViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File

class PlayerBackDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun toolbarAndSystemBackRestoreBrowserAfterHomeAndPip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = requireNotNull(InstrumentationRegistry.getArguments().getString("mediaPath"))
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val video = File(Environment.getExternalStorageDirectory(), path).canonicalFile
        require(video.toPath().startsWith(root.toPath()) && video.parentFile!!.name.startsWith("validation-") && video.isFile)
        val folder = StorageLocation(LOCAL_SOURCE, path.substringBeforeLast('/'))
        val generated = (0 until 60).map { File(video.parentFile, "back-row-${it.toString().padStart(3, '0')}.txt") }
        require(generated.none { it.exists() })
        generated.forEach { it.writeText("Back navigation fixture") }
        val graph = AppGraph.get(compose.activity)
        val originalSettings = runBlocking { graph.settings.settings.first() }
        compose.waitUntil(60_000) { graph.workspace.state.value.ready }
        val originalWorkspace = graph.workspace.state.value
        lateinit var model: ExplorerViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java) }
        val originalLocation = model.state.value.location
        try {
            runBlocking { graph.settings.save(originalSettings.copy(grid = false, autoPip = true)) }
            compose.runOnIdle { graph.workspace.activate(0); graph.workspace.split(false); graph.workspace.panel(null); model.navigate(folder) }
            compose.waitUntil(60_000) { !model.state.value.loading && model.state.value.location == folder && model.state.value.entries.size >= 60 }
            compose.onNodeWithTag("folder-list").performScrollToIndex(30)
            compose.waitUntil(5_000) { model.state.value.index == 30 }
            compose.runOnIdle { model.select(model.state.value.entries[30]) }
            val selected = model.state.value.selected
            val index = model.state.value.index
            val sort = model.state.value.sort
            val reads = StorageReadMetrics.count(LOCAL_SOURCE)
            for (mode in listOf("toolbar", "system-after-home", "system-after-pip")) {
                runBlocking { graph.settings.update { it.copy(autoPip = mode != "system-after-home") } }
                val monitor = instrumentation.addMonitor(PlayerActivity::class.java.name, null, false)
                var player = try {
                    compose.runOnIdle { model.open(model.state.value.entries.single { it.location.path == path }) }
                    requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as? PlayerActivity)
                } finally { instrumentation.removeMonitor(monitor) }
                compose.waitUntil(15_000) { player.hasWindowFocus() && graph.playback.state.value.playing }
                instrumentation.runOnMainSync {
                    val visible = player.getSystemService(android.app.ActivityManager::class.java).appTasks.filter {
                        it.taskInfo.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS == 0
                    }
                    assertEquals("Only the fullscreen viewer appears in Recents", listOf(player.taskId), visible.map { it.taskInfo.taskId })
                }
                val footer = compose.onNodeWithTag("player-footer").fetchSemanticsNode().boundsInRoot
                val transport = compose.onNodeWithTag("player-transport").fetchSemanticsNode().boundsInRoot
                val speed = compose.onNodeWithTag("player-speed").fetchSemanticsNode().boundsInRoot
                val pipButton = compose.onNodeWithTag("player-pip").fetchSemanticsNode().boundsInRoot
                assertTrue("Transport belongs to footer", transport.top >= footer.top && transport.bottom <= footer.bottom)
                assertTrue("PiP follows speed on the same row", pipButton.left >= speed.right && kotlin.math.abs(pipButton.center.y - speed.center.y) <= 1f)
                if (mode == "toolbar") {
                    val evidence = File(video.parentFile, "navigation-evidence").apply { mkdirs() }
                    fun capture(name: String) {
                        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                            File(evidence, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            bitmap.recycle()
                        }
                    }
                    capture("player-footer.png")
                    instrumentation.runOnMainSync { graph.playback.controller?.pause() }
                    val originalFill = graph.playback.options.fill.value
                    compose.onNodeWithTag("player-fill").performClick()
                    compose.runOnIdle { assertEquals(!originalFill, graph.playback.options.fill.value) }
                    compose.onNodeWithTag("player-fill").performClick()
                    compose.onNodeWithTag("player-rotate").performClick()
                    compose.waitUntil(10_000) { player.window.decorView.height > player.window.decorView.width }
                    compose.waitForIdle()
                    val portraitFooter = compose.onNodeWithTag("player-footer").fetchSemanticsNode().boundsInRoot
                    val portraitTransport = compose.onNodeWithTag("player-transport").fetchSemanticsNode().boundsInRoot
                    assertTrue(portraitTransport.top >= portraitFooter.top && portraitTransport.bottom <= portraitFooter.bottom)
                    compose.onNodeWithTag("player-pip").assertIsDisplayed()
                    android.os.SystemClock.sleep(350)
                    capture("player-footer-portrait.png")
                    compose.onNodeWithTag("player-rotate").performClick()
                    compose.waitUntil(10_000) { player.window.decorView.width > player.window.decorView.height }
                    instrumentation.runOnMainSync { graph.playback.controller?.play() }
                    instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_APP_SWITCH").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                    android.os.SystemClock.sleep(900)
                    capture("recents-player.png")
                    instrumentation.runOnMainSync {
                        player.getSystemService(android.app.ActivityManager::class.java).appTasks.single { it.taskInfo.taskId == player.taskId }.moveToFront()
                    }
                    compose.waitUntil(10_000) { player.hasWindowFocus() }
                    if (compose.onAllNodesWithTag("player-back").fetchSemanticsNodes().isEmpty()) {
                        compose.onNodeWithTag("player-surface").performTouchInput { click() }
                    }
                }
                if (mode == "system-after-home") {
                    // Reproduces a player task reopened above the launcher, not directly above MainActivity.
                    instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                    compose.waitUntil(10_000) { !player.hasWindowFocus() }
                    instrumentation.runOnMainSync {
                        player.getSystemService(android.app.ActivityManager::class.java).appTasks
                            .single { it.taskInfo.taskId == player.taskId }.moveToFront()
                    }
                } else if (mode == "system-after-pip") {
                    compose.onNodeWithTag("player-pip").performClick()
                    compose.waitUntil(10_000) { player.isInPictureInPictureMode }
                    compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
                    val evidenceDir = File(video.parentFile, "navigation-evidence").apply { mkdirs() }
                    val taskDump = compose.activity.getSystemService(android.app.ActivityManager::class.java).appTasks.map { task ->
                        val info = task.taskInfo
                        "task=${info.taskId} flags=0x${Integer.toHexString(info.baseIntent.flags)} base=${info.baseIntent.component?.flattenToString()} top=${info.topActivity?.flattenToString()}"
                    }
                    instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                        File(evidenceDir, "pip-browser-focused.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                    }
                    instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_APP_SWITCH").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                    android.os.SystemClock.sleep(1200)
                    instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                        File(evidenceDir, "recents-pip.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                    }
                    instrumentation.runOnMainSync {
                        val visible = compose.activity.getSystemService(android.app.ActivityManager::class.java).appTasks.filter {
                            it.taskInfo.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS == 0
                        }
                        assertEquals("PiP leaves one browser card" + System.lineSeparator() + taskDump.joinToString(System.lineSeparator()), listOf(compose.activity.taskId), visible.map { it.taskInfo.taskId })
                    }
                    instrumentation.runOnMainSync { compose.activity.startActivity(Intent(compose.activity, PlayerActivity::class.java)) }
                }
                compose.waitUntil(15_000) {
                    runBlocking(Dispatchers.Main) {
                        val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<PlayerActivity>().firstOrNull { it.hasWindowFocus() && !it.isInPictureInPictureMode }
                        if (resumed != null) player = resumed
                        resumed != null
                    }
                }
                if (mode == "toolbar") compose.onNodeWithTag("player-back").performClick()
                else instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                compose.waitUntil(15_000) { compose.activity.hasWindowFocus() && player.isDestroyed }
                compose.onNodeWithTag("folder-list").assertIsDisplayed()
                instrumentation.runOnMainSync {
                    val visible = compose.activity.getSystemService(android.app.ActivityManager::class.java).appTasks.filter {
                        it.taskInfo.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS == 0
                    }
                    assertEquals(listOf(compose.activity.taskId), visible.map { it.taskInfo.taskId })
                }
                compose.runOnIdle {
                    assertSame(model, ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java))
                    assertEquals(folder, model.state.value.location)
                    assertEquals(selected, model.state.value.selected)
                    assertEquals(index, model.state.value.index)
                    assertEquals(sort, model.state.value.sort)
                    assertEquals(reads, StorageReadMetrics.count(LOCAL_SOURCE))
                    assertTrue(graph.playback.state.value.playing)
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "PLAYER_BACK mode=$mode browserFocused=true locationSelectionScrollSort=true refetch=0 playback=true\n") })
            }
        } finally {
            cleanupPlayerTest(graph.playback)
            runBlocking { graph.settings.save(originalSettings); graph.dao.removeSaved("recent:${folder.key}") }
            compose.runOnIdle {
                model.clearSelection()
                if (originalLocation == null) model.home() else model.navigate(originalLocation)
                graph.workspace.activate(originalWorkspace.active)
                graph.workspace.split(originalWorkspace.split)
            }
            generated.forEach { check(it.delete()) }
        }
    }
}
