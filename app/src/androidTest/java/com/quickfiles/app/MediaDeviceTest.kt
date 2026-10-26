package com.quickfiles.app

import android.content.Intent
import android.os.Environment
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.quickfiles.app.data.SavedValue
import com.quickfiles.app.playback.PlayerActivity
import com.quickfiles.app.storage.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = AppGraph.get(compose.activity)
    private fun sample(): StorageLocation {
        val path = InstrumentationRegistry.getArguments().getString("mediaPath")
        Assume.assumeTrue("Own generated media fixture required", path != null)
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val file = File(Environment.getExternalStorageDirectory(), path!!).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file.parentFile!!.name.startsWith("validation-") && file.isFile)
        return StorageLocation(LOCAL_SOURCE, path)
    }
    private fun play(location: StorageLocation) {
        runBlocking {
            val entry = graph.repository.provider(LOCAL_SOURCE).stat(location)
            graph.dao.save(SavedValue("playback:${entry.id}", "0"))
            graph.playback.play(entry, listOf(entry))
        }
        compose.waitUntil(30_000) { graph.playback.state.value.playing || graph.playback.state.value.error != null }
        assertNull(graph.playback.state.value.error); assertTrue(graph.playback.state.value.playing)
    }
    @After fun stop() = cleanupPlayerTest(graph.playback)
    @Test fun musicSeekSpeedPlaylistAndSavedPosition() {
        val folder = sample().parent()!!
        val entries = runBlocking { listOf("music.mp3", "music-2.mp3").map { graph.repository.local.stat(folder.child(it)) } }
        runBlocking { graph.playback.play(entries.first(), entries) }
        compose.waitUntil(15_000) { graph.playback.state.value.playing || graph.playback.state.value.error != null }
        assertNull(graph.playback.state.value.error); assertTrue(graph.playback.state.value.playing)
        assertFalse(graph.playback.state.value.video)
        compose.runOnIdle { graph.playback.controller!!.seekToNextMediaItem() }
        compose.waitUntil(10_000) { runBlocking(Dispatchers.Main) { graph.playback.controller!!.currentMediaItem?.mediaId == entries.last().id } }
        compose.runOnIdle { graph.playback.controller!!.seekToPreviousMediaItem() }
        compose.waitUntil(10_000) { runBlocking(Dispatchers.Main) { graph.playback.controller!!.currentMediaItem?.mediaId == entries.first().id } }
        compose.runOnIdle { graph.playback.controller!!.setPlaybackSpeed(1.5f); graph.playback.controller!!.seekTo(10_000) }
        compose.waitUntil(10_000) { runBlocking(Dispatchers.Main) { graph.playback.controller!!.currentPosition >= 10_000 } }
        compose.runOnIdle { assertEquals(1.5f, graph.playback.controller!!.playbackParameters.speed, 0f); graph.playback.toggle() }
        compose.waitUntil(10_000) { !graph.playback.state.value.playing }
        compose.runOnIdle { graph.playback.toggle() }
        compose.waitUntil(10_000) { graph.playback.state.value.playing }
        runBlocking { graph.playback.stop(); graph.playback.play(entries.first(), entries) }
        compose.waitUntil(15_000) { graph.playback.state.value.playing }
        compose.runOnIdle { assertTrue(graph.playback.controller!!.currentPosition >= 9_000) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "MEDIA audio=true seek=true speed=true nextPrevious=true restoredPosition=true\n") })
    }
    @Test fun twentyPipEntriesAndReturnsKeepVideoPlaying() = pipCycles(false)
    @Test fun twentyAccessiblePipEntriesAndReturnsKeepVideoPlaying() = pipCycles(true)
    // Controller-only regression: this does not open or validate the OS PiP menu.
    @Test fun pipControllerKeepsDurationAndUpdatedSeekPosition() {
        val location = sample()
        play(location)
        val controller = graph.playback.controller!!
        runBlocking(Dispatchers.Main) {
            controller.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            controller.pause()
            controller.seekTo(20_000)
        }
        compose.waitUntil(10_000) { runBlocking(Dispatchers.Main) { controller.currentPosition in 19_800L..20_200L } }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val evidenceRoot = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val evidence = File(evidenceRoot, "validation-pip-duration-${java.util.UUID.randomUUID()}").canonicalFile
        require(evidence.toPath().startsWith(evidenceRoot.toPath()) && evidence != evidenceRoot && evidence.mkdirs())
        val monitor = instrumentation.addMonitor(PlayerActivity::class.java.name, null, false)
        val player = try {
            compose.runOnIdle { compose.activity.startActivity(Intent(compose.activity, PlayerActivity::class.java)) }
            instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as? PlayerActivity
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        val pipActivity = requireNotNull(player)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("player-pip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player-pip").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { pipActivity.isInPictureInPictureMode }
        compose.waitUntil(10_000) {
            runBlocking(Dispatchers.Main) {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().any { it.hasWindowFocus() } && !pipActivity.hasWindowFocus()
            }
        }

        fun capture(name: String, expectedPosition: Long) {
            // Android does not expose the pinned PlayerActivity's Compose tree through
            // the foreground MainActivity root. Pair controller assertions with the
            // actual compositor screenshot instead of treating that omission as a UI failure.
            compose.waitUntil(10_000) { !graph.playback.details.value.buffering }
            compose.waitForIdle(); SystemClock.sleep(1_500)
            val (position, playWhenReady) = runBlocking(Dispatchers.Main) {
                controller.currentPosition to controller.playWhenReady
            }
            val detail = graph.playback.details.value
            assertTrue(position.toString(), position in (expectedPosition - 200)..(expectedPosition + 200))
            assertTrue(detail.duration.toString(), detail.duration in 59_000L..61_000L)
            assertTrue(pipActivity.isInPictureInPictureMode)
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(evidence, "$name.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                File(evidence, "$name-state.json").writeText(
                    org.json.JSONObject()
                        .put("positionMs", position)
                        .put("durationMs", detail.duration)
                        .put("seekable", detail.seekable)
                        .put("playWhenReady", playWhenReady)
                        .put("pictureInPicture", pipActivity.isInPictureInPictureMode)
                        .put("screenshotWidth", bitmap.width)
                        .put("screenshotHeight", bitmap.height)
                        .toString(2),
                )
                bitmap.recycle()
            }
        }

        capture("pip-controller-position-20", 20_000)
        val forward = runBlocking(Dispatchers.Main) {
            pipActivity.buildPipParams().actions.single { it.title.toString() == "+10초" }.actionIntent
        }
        forward.send()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.Main) { controller.currentPosition in 29_800L..30_200L } }
        capture("pip-controller-position-30", 30_000)
        assertFalse(graph.playback.state.value.playWhenReady)
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "PIP_CONTROLLER_DURATION positionDuration=true screenshots=true seekUpdate=true paused=true nativeMenuVerified=false evidence=${evidence.path}\n")
        })
    }
    @Test fun pipRemoteActionsSeekAndTogglePlayback() {
        play(sample())
        val controller = graph.playback.controller!!
        runBlocking(Dispatchers.Main) {
            controller.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            controller.pause()
            controller.seekTo(20_000)
        }
        compose.waitUntil(10_000) { runBlocking(Dispatchers.Main) { controller.currentPosition in 19_800L..20_200L } }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(PlayerActivity::class.java.name, null, false)
        var player = try {
            compose.runOnIdle { compose.activity.startActivity(Intent(compose.activity, PlayerActivity::class.java)) }
            instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as? PlayerActivity
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        assertNotNull(player)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("player-pip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player-pip").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { player!!.isInPictureInPictureMode }
        compose.waitUntil(10_000) {
            runBlocking(Dispatchers.Main) {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().any { it.hasWindowFocus() } && !player!!.hasWindowFocus()
            }
        }
        SystemClock.sleep(400)

        fun action(title: String): android.app.RemoteAction = runBlocking(Dispatchers.Main) {
            player!!.buildPipParams().actions.single { it.title.toString() == title }
        }
        val titles = runBlocking(Dispatchers.Main) { player!!.buildPipParams().actions.map { it.title.toString() } }
        assertTrue(titles.toString(), titles.containsAll(listOf("−10초", "재생", "+10초")))
        if (android.os.Build.VERSION.SDK_INT >= 33) assertNotNull(runBlocking(Dispatchers.Main) { player!!.buildPipParams().closeAction })

        val firstSessionId = graph.playback.sessionState.value.sessionId
        val staleForward = action("+10초").actionIntent
        staleForward.send()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.Main) { controller.currentPosition in 29_800L..30_200L } }
        action("−10초").actionIntent.send()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.Main) { controller.currentPosition in 19_800L..20_200L } }
        action("재생").actionIntent.send()
        compose.waitUntil(10_000) { graph.playback.state.value.playWhenReady }
        compose.waitUntil(10_000) { runBlocking(Dispatchers.Main) { player!!.buildPipParams().actions.any { it.title.toString() == "일시정지" } } }
        action("일시정지").actionIntent.send()
        compose.waitUntil(10_000) { !graph.playback.state.value.playWhenReady }
        compose.waitUntil(10_000) { runBlocking(Dispatchers.Main) { player!!.buildPipParams().actions.any { it.title.toString() == "재생" } } }

        compose.onNodeWithTag("mini-player-open").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            val resumed = runBlocking(Dispatchers.Main) {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<PlayerActivity>().firstOrNull { !it.isInPictureInPictureMode }
            }
            resumed?.let { player = it }
            resumed != null
        }
        compose.waitUntil(10_000) { player!!.hasWindowFocus() }
        assertSame(controller, graph.playback.controller)

        play(sample())
        val secondSessionId = graph.playback.sessionState.value.sessionId
        assertNotEquals(firstSessionId, secondSessionId)
        runBlocking(Dispatchers.Main) {
            controller.pause()
            controller.seekTo(20_000)
        }
        compose.waitUntil(10_000) { runBlocking(Dispatchers.Main) { controller.currentPosition in 19_800L..20_200L } }
        staleForward.send()
        SystemClock.sleep(1_000)
        assertTrue(
            "A delayed PiP action from the previous session must be ignored",
            runBlocking(Dispatchers.Main) { controller.currentPosition in 19_800L..20_200L },
        )
        action("+10초").actionIntent.send()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.Main) { controller.currentPosition in 29_800L..30_200L } }
        compose.onNodeWithTag("player-pip").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { player!!.isInPictureInPictureMode }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runBlocking(Dispatchers.Main) { player!!.buildPipParams().closeAction!!.actionIntent }.send()
        } else {
            instrumentation.runOnMainSync { player!!.finish() }
        }
        compose.waitUntil(10_000) { !graph.playback.state.value.visible }
        compose.waitUntil(10_000) { player!!.isFinishing || player!!.isDestroyed }
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "MEDIA_PIP_ACTIONS seekBack=true seekForward=true toggle=true closeAction=true closeStoppedPlayback=true sessionPreserved=true staleSessionIgnored=true\n")
        })
    }
    private fun pipCycles(accessible: Boolean) {
        play(sample())
        runBlocking(Dispatchers.Main) {
            graph.playback.controller!!.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            graph.playback.controller!!.seekTo(0)
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val evidenceRoot = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val evidence = File(evidenceRoot, "validation-pip-${java.util.UUID.randomUUID()}").canonicalFile
        require(evidence.toPath().startsWith(evidenceRoot.toPath()) && evidence != evidenceRoot && evidence.mkdirs())
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "MEDIA_PIP input=${if (accessible) "accessibility" else "touch"} evidence=${evidence.path}\n") })
        fun capture(name: String) {
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(evidence, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            fun describe(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0): org.json.JSONObject {
                val result = org.json.JSONObject()
                if (node == null || depth > 40) return result
                val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
                return result.put("text", node.text?.toString()).put("description", node.contentDescription?.toString()).put("bounds", bounds.toShortString())
                    .put("children", org.json.JSONArray((0 until node.childCount).map { describe(node.getChild(it), depth + 1) }))
            }
            File(evidence, "$name-ui.json").writeText(describe(instrumentation.uiAutomation.rootInActiveWindow).toString(2))
        }
        val monitor = instrumentation.addMonitor(PlayerActivity::class.java.name, null, false)
        var player = try {
            compose.runOnIdle { compose.activity.startActivity(Intent(compose.activity, PlayerActivity::class.java)) }
            instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as? PlayerActivity
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        assertNotNull(player)
        repeat(20) {
            if (compose.onAllNodesWithTag("player-pip").fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithTag("player-surface").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .5f, height * .25f)) }
                // Single tap waits for the double-tap timeout before revealing controls.
                compose.waitUntil(5000) { compose.onAllNodesWithTag("player-pip").fetchSemanticsNodes().isNotEmpty() }
            }
            val pipButton = compose.onNodeWithTag("player-pip").assertIsDisplayed()
            if (accessible) pipButton.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() } else pipButton.performClick()
            compose.waitUntil(10_000) { player!!.isInPictureInPictureMode }
            // The PiP flag changes before the window shrinks and the underlying activity
            // receives input. A fixed sleep can tap the old fullscreen window instead.
            compose.waitUntil(10_000) {
                var ready = false
                instrumentation.runOnMainSync {
                    val main = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull { it.hasWindowFocus() }
                    // PiP is compositor-scaled; the decor may retain fullscreen dimensions.
                    ready = main != null && player!!.isInPictureInPictureMode && !player!!.hasWindowFocus()
                }
                ready
            }
            SystemClock.sleep(400)
            compose.waitForIdle()
            if (it == 0) capture("pip-small")
            val returnButton = compose.onNodeWithTag("mini-player-open").assertIsDisplayed()
            if (accessible) returnButton.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() } else returnButton.performClick()
            var lastDiagnostic = 0L
            compose.waitUntil(10_000) {
                var resumed: PlayerActivity? = null
                instrumentation.runOnMainSync {
                    resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>().firstOrNull { !it.isInPictureInPictureMode }
                    if (SystemClock.elapsedRealtime() - lastDiagnostic >= 2000) {
                        lastDiagnostic = SystemClock.elapsedRealtime()
                        val stages = Stage.entries.joinToString { stage -> "$stage=" + ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).joinToString { it.javaClass.simpleName } }
                        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "PIP_RETURN pip=${player!!.isInPictureInPictureMode} lifecycle=${player!!.lifecycle.currentState} focused=${player!!.hasWindowFocus()} $stages\n") })
                    }
                }
                resumed?.let { player = it }
                resumed != null
            }
            compose.waitUntil(10_000) { player!!.hasWindowFocus() && player!!.window.decorView.width > player!!.window.decorView.height }
            SystemClock.sleep(400)
            compose.waitForIdle(); assertTrue(graph.playback.state.value.playing)
            if (it == 0 || it == 19) capture("pip-return-${it + 1}")
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "MEDIA pipCycle=${it + 1} fullScreenReturned=true\n") })
        }
        instrumentation.runOnMainSync { player!!.finish() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("재생 정지").assertExists()
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "MEDIA pipCycles=20 playbackPreserved=true evidence=${evidence.path}\n") })
    }
    @Test fun tenMinutesInBackgroundKeepPlaybackAdvancing() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("longMedia") == "true")
        play(sample())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(PlayerActivity::class.java.name, null, false)
        val backgroundPlayer = try {
            compose.runOnIdle { compose.activity.startActivity(Intent(compose.activity, PlayerActivity::class.java)) }
            instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as? PlayerActivity
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        assertNotNull(backgroundPlayer)
        runBlocking(Dispatchers.Main) { graph.playback.controller!!.seekTo(0) }
        val initial = runBlocking(Dispatchers.Main) { graph.playback.controller!!.currentPosition }
        val start = SystemClock.elapsedRealtime()
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        repeat(10) { minute ->
            runBlocking { delay(60_000) }
            val current = runBlocking(Dispatchers.Main) { assertTrue(graph.playback.controller!!.isPlaying); graph.playback.controller!!.currentPosition }
            assertTrue(current >= initial + (minute + 1) * 58_000)
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "MEDIA backgroundMinute=${minute + 1} positionMs=$current error=${graph.playback.state.value.error != null}\n") })
        }
        assertTrue(SystemClock.elapsedRealtime() - start >= 600_000)
        // Do not lock the screen: the user's latest instruction is to keep the connected phone unlocked.
        instrumentation.runOnMainSync { backgroundPlayer!!.finish(); compose.activity.startActivity(Intent(compose.activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
    }
}
