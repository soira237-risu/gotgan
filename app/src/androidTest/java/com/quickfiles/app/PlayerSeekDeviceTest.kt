package com.quickfiles.app

import android.content.Intent
import android.os.Environment
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.quickfiles.app.data.SavedValue
import com.quickfiles.app.playback.PlayerActivity
import com.quickfiles.app.playback.SleepTimerKind
import com.quickfiles.app.storage.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerSeekDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = AppGraph.get(compose.activity)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var directory: File
    private fun <T> main(block: () -> T): T = runBlocking(Dispatchers.Main) { block() }
    private fun position() = main { graph.playback.controller!!.currentPosition }
    private fun surface() = compose.onNodeWithTag("player-surface")
    @Before fun prepare() {
        val relative = requireNotNull(InstrumentationRegistry.getArguments().getString("playerMediaDir"))
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        directory = File(Environment.getExternalStorageDirectory(), relative).canonicalFile
        require(directory.toPath().startsWith(root.toPath()) && directory.name.startsWith("validation-player-seek-"))
        val location = StorageLocation(LOCAL_SOURCE, "$relative/landscape.mp4")
        runBlocking {
            val entry = graph.repository.local.stat(location)
            graph.dao.save(SavedValue("playback:${entry.id}", "0"))
            graph.playback.play(entry, listOf(entry))
        }
        compose.waitUntil(20_000) { graph.playback.details.value.seekable && graph.playback.state.value.playing }
        main {
            graph.playback.controller!!.pause(); graph.playback.controller!!.seekTo(20_000)
            compose.activity.startActivity(Intent(compose.activity, PlayerActivity::class.java))
        }
        compose.waitUntil(15_000) { main {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<PlayerActivity>().any { it.hasWindowFocus() && it.window.decorView.width > it.window.decorView.height }
        } }
        SystemClock.sleep(700)
        compose.onNodeWithTag("player-toggle").assertIsDisplayed()
    }
    @After fun finish() = cleanupPlayerTest(graph.playback)
    private fun capture(name: String) {
        compose.waitUntil(15_000) { !graph.playback.details.value.buffering }
        compose.waitForIdle()
        compose.waitUntil(15_000) {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.quickfiles.app"
        }
        assertEquals("Screenshot must show this app", "com.quickfiles.app", instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val evidence = File(directory, "seek-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(evidence, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun dragOnlyPreviewsFineModeDoesNotJumpAndCommitsWithoutUndo() {
        val origin = position()
        capture("landscape-controls")
        surface().performTouchInput { down(Offset(width * .25f, height * .24f)); moveTo(Offset(width * .45f, height * .24f), 300) }
        compose.onNodeWithTag("player-seek-preview").assertIsDisplayed()
        val coarse = compose.onNodeWithTag("player-seek-target").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        assertEquals("No decoder seek before release", origin, position())
        surface().performTouchInput { moveTo(Offset(width * .45f, height * .24f + 80.dp.toPx()), 250) }
        compose.onNodeWithTag("player-seek-mode").assertTextEquals("정밀 탐색 · 1/5")
        compose.onNodeWithTag("player-seek-target").assertTextEquals(coarse)
        surface().performTouchInput { moveTo(Offset(width * .55f, height * .24f + 80.dp.toPx()), 300) }
        assertEquals(origin, position())
        capture("fine-seek-preview")
        surface().performTouchInput { up() }
        compose.waitUntil(10_000) { position() in 33_000..36_000 }
        assertFalse(main { graph.playback.controller!!.playWhenReady })
        compose.onNodeWithTag("player-seek-preview").assertDoesNotExist()
        compose.onNodeWithTag("player-seek-undo").assertDoesNotExist()
        capture("fine-seek-committed")
        assertTrue("Committed position stays at the preview target", position() in 33_000..36_000)
    }
    @Test fun cancelledVerticalAndMultitouchGesturesAndLockDoNotSeek() {
        val origin = position()
        surface().performTouchInput { swipe(Offset(width * .25f, height * .24f), Offset(width * .26f, height * .45f), 400) }
        assertEquals(origin, position())
        compose.onNodeWithTag("player-options").assertIsDisplayed()
        surface().performTouchInput { down(Offset(width * .25f, height * .24f)); moveTo(Offset(width * .5f, height * .24f), 300); cancel() }
        compose.onNodeWithTag("player-seek-preview").assertDoesNotExist()
        assertEquals(origin, position())
        compose.onNodeWithTag("player-options").assertIsDisplayed()
        surface().performTouchInput {
            down(0, Offset(width * .25f, height * .24f)); moveTo(0, Offset(width * .45f, height * .24f), 300)
            down(1, Offset(width * .65f, height * .24f)); moveBy(0, Offset(60f, 0f)); up(1); up(0)
        }
        compose.onNodeWithTag("player-seek-preview").assertDoesNotExist()
        assertEquals(origin, position())
        compose.onNodeWithTag("player-lock").performClick()
        surface().performTouchInput { swipe(Offset(width * .25f, height * .24f), Offset(width * .6f, height * .24f), 500) }
        assertEquals(origin, position())
        compose.onNodeWithTag("player-unlock").performClick()
        compose.onNodeWithTag("player-toggle").assertIsDisplayed()
    }
    @Test fun playingDragTimelineAndRotationKeepSessionAndAccessibleControls() {
        val sessionId = graph.playback.sessionState.value.sessionId
        main { graph.playback.controller!!.play() }
        surface().performTouchInput { swipe(Offset(width * .25f, height * .24f), Offset(width * .5f, height * .24f), 500) }
        compose.waitUntil(10_000) { position() > 33_000 }
        assertTrue(main { graph.playback.controller!!.playWhenReady })
        main { graph.playback.controller!!.pause() }
        compose.onNodeWithTag("player-timeline").performSemanticsAction(SemanticsActions.SetProgress) { it(15_000f) }
        compose.waitUntil(10_000) { position() in 14_900..15_100 }
        compose.onNodeWithTag("player-seek-undo").assertDoesNotExist()
        compose.onNodeWithTag("player-rotate").performClick()
        compose.waitUntil(15_000) { main {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<PlayerActivity>().any { it.window.decorView.height > it.window.decorView.width }
        } }
        SystemClock.sleep(700)
        compose.onNodeWithTag("player-toggle").assertIsDisplayed().assertWidthIsAtLeast(64.dp).assertHeightIsAtLeast(64.dp)
        listOf("player-back", "player-seek-back", "player-seek-forward", "player-pip", "player-next").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        assertEquals(sessionId, graph.playback.sessionState.value.sessionId)
        assertFalse(main { graph.playback.controller!!.playWhenReady })
        capture("portrait-controls")
    }

    @Test fun compactLandscapeControlsKeepTransportTogetherAndNavigateFiles() {
        val screen = surface().fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("player-header").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("player-footer").fetchSemanticsNode().boundsInRoot
        val transport = compose.onNodeWithTag("player-transport").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Header should occupy at most 56dp", header.height / density <= 56f)
        assertTrue("Three bottom control rows fit within 176dp", footer.height / density <= 176f)
        assertTrue("Transport is in the lower half", transport.center.y > screen.center.y)
        assertTrue("Header and transport do not overlap", header.bottom < transport.top)
        assertTrue("Transport is contained in the footer", transport.top >= footer.top && transport.bottom <= footer.bottom)
        val tags = listOf("player-previous", "player-seek-back", "player-toggle", "player-seek-forward", "player-next")
        val bounds = tags.map { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        }
        bounds.zipWithNext().forEach { (left, right) ->
            assertTrue(left.right <= right.left)
            assertTrue(kotlin.math.abs(left.center.y - right.center.y) < density)
        }
        val entries = listOf("landscape.mp4", "second.mp4").map {
            runBlocking { graph.repository.local.stat(StorageLocation(LOCAL_SOURCE, "${directory.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath}/$it")) }
        }
        runBlocking { graph.playback.play(entries.first(), entries) }
        compose.waitUntil(15_000) { graph.playback.details.value.next && graph.playback.state.value.playing }
        main { graph.playback.controller!!.pause() }
        val sessionId = graph.playback.sessionState.value.sessionId
        compose.onNodeWithTag("player-next").performClick()
        compose.waitUntil(15_000) { graph.playback.details.value.mediaId == entries.last().id && graph.playback.details.value.previous }
        compose.onNodeWithTag("player-previous").performClick()
        compose.waitUntil(15_000) { graph.playback.details.value.mediaId == entries.first().id }
        assertEquals(sessionId, graph.playback.sessionState.value.sessionId)
        assertFalse(main { graph.playback.controller!!.playWhenReady })
        capture("compact-landscape-transport")
    }

    @Test fun audioScreenSupportsForwardBackwardDragWithoutUndoOrStartingPlayback() {
        val relative = directory.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath
        val audio = runBlocking { graph.repository.local.stat(StorageLocation(LOCAL_SOURCE, "$relative/audio.wav")) }
        runBlocking { graph.playback.play(audio, listOf(audio)) }
        compose.waitUntil(15_000) { graph.playback.details.value.seekable && !graph.playback.state.value.video && graph.playback.state.value.playing }
        main {
            graph.playback.controller!!.pause()
            graph.playback.controller!!.seekTo(20_000)
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<PlayerActivity>().single().requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(15_000) { surface().fetchSemanticsNode().boundsInRoot.let { it.height > it.width } }
        SystemClock.sleep(700)
        surface().performTouchInput { swipe(Offset(width * .25f, height * .25f), Offset(width * .5f, height * .25f), 500) }
        compose.waitUntil(10_000) { position() > 33_000 }
        val forward = position()
        assertFalse(main { graph.playback.controller!!.playWhenReady })
        compose.onNodeWithTag("player-seek-undo").assertDoesNotExist()
        val beforeTick = compose.onNodeWithTag("player-timeline").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
        // The UI polls Media3 every 250 ms on Compose's clock, independently of wall time.
        compose.mainClock.advanceTimeBy(300)
        val afterTick = compose.onNodeWithTag("player-timeline").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "AUDIO_TIMELINE player=$forward beforeTick=$beforeTick afterTick=$afterTick\n")
        })
        assertEquals("Timeline refresh must reflect the actual paused decoder position", forward.toFloat(), afterTick, 150f)
        compose.onNodeWithTag("player-timeline").performSemanticsAction(SemanticsActions.SetProgress) {
            assertTrue("Accessibility seek action must accept a changed target", it(20_000f))
        }
        compose.waitUntil(10_000) { position() in 19_900..20_100 }
        surface().performTouchInput { swipe(Offset(width * .6f, height * .25f), Offset(width * .4f, height * .25f), 500) }
        compose.waitUntil(10_000) { position() < 10_000 }
        assertTrue(position() < forward)
        assertFalse(main { graph.playback.controller!!.playWhenReady })
        compose.onNodeWithTag("player-seek-undo").assertDoesNotExist()
        capture("audio-controls")
    }

    @Test fun activeSleepTimerRemainsAfterSeekingWithoutUndo() {
        assertTrue(runBlocking { graph.playback.setSleepTimerMinutes(15) })
        compose.waitUntil(10_000) { graph.playback.sessionState.value.timerKind == SleepTimerKind.DURATION }
        compose.onNodeWithTag("player-sleep-timer").assertIsDisplayed()
        compose.onNodeWithTag("player-seek-forward").performClick()
        compose.waitUntil(10_000) { position() in 29_900..30_100 }
        compose.onNodeWithTag("player-seek-undo").assertDoesNotExist()
        compose.onNodeWithTag("player-sleep-timer").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sleep-timer-running").assertIsDisplayed()
        capture("active-sleep-timer")
    }

    @Test fun buttonsDoubleTapAndTimelineSeekWithoutUndoOrExtraFooterRow() {
        val footerHeight = compose.onNodeWithTag("player-footer").fetchSemanticsNode().boundsInRoot.height
        fun assertSeek(target: Long) {
            compose.waitUntil(10_000) { kotlin.math.abs(position() - target) < 150 }
            assertFalse(main { graph.playback.controller!!.playWhenReady })
            compose.onNodeWithTag("player-seek-undo").assertDoesNotExist()
            assertTrue("Seeking must not add a footer row",
                compose.onNodeWithTag("player-footer").fetchSemanticsNode().boundsInRoot.height <= footerHeight + 1f)
        }
        compose.onNodeWithTag("player-seek-forward").performClick()
        assertSeek(30_000)
        compose.onNodeWithTag("player-seek-back").performClick()
        assertSeek(20_000)
        surface().performTouchInput { doubleClick(Offset(width * .75f, height * .24f)) }
        assertSeek(30_000)
        surface().performTouchInput { doubleClick(Offset(width * .25f, height * .24f)) }
        assertSeek(20_000)
        compose.onNodeWithTag("player-timeline").performSemanticsAction(SemanticsActions.SetProgress) { it(45_000f) }
        assertSeek(45_000)
        capture("seek-without-undo")
        main { graph.playback.controller!!.play() }
        compose.waitUntil(10_000) { graph.playback.state.value.playing }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_800)
        compose.onNodeWithTag("player-footer").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("player-footer").assertDoesNotExist()
        assertTrue(main { graph.playback.controller!!.playWhenReady })
    }
}
