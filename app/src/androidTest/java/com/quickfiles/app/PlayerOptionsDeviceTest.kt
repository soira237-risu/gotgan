package com.quickfiles.app

import android.content.Intent
import android.content.res.Configuration
import android.os.Environment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.quickfiles.app.playback.PlayerActivity
import com.quickfiles.app.playback.SleepTimerKind
import com.quickfiles.app.data.SavedValue
import com.quickfiles.app.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.compose.ui.unit.dp
import org.junit.*
import org.junit.Assert.*
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerOptionsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = AppGraph.get(compose.activity)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var folder: StorageLocation
    private lateinit var entries: List<FileEntry>
    private fun <T> main(block: () -> T): T = runBlocking(Dispatchers.Main) { block() }
    private fun currentActivity(): PlayerActivity? = main { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>().firstOrNull() }
    @Before fun prepare() {
        val path = InstrumentationRegistry.getArguments().getString("playerMediaDir")
        Assume.assumeTrue("Generated player fixtures required", path != null)
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val directory = File(Environment.getExternalStorageDirectory(), path!!).canonicalFile
        require(directory.toPath().startsWith(root.toPath()) && directory.name.startsWith("validation-player-"))
        folder = StorageLocation(LOCAL_SOURCE, path)
        entries = runBlocking { graph.repository.local.list(folder) }
    }
    @After fun finish() = cleanupPlayerTest(graph.playback)
    private fun play(name: String, open: Boolean = true) {
        val entry = entries.single { it.name == name }
        runBlocking {
            graph.dao.save(SavedValue("playback:${entry.id}", "0"))
            graph.playback.play(entry, entries)
        }
        compose.waitUntil(20000) { graph.playback.details.value.duration > 0 && graph.playback.state.value.playing || graph.playback.state.value.error != null }
        assertNull("$name: ${graph.playback.state.value.error}", graph.playback.state.value.error)
        if (open && currentActivity() == null) main { compose.activity.startActivity(Intent(compose.activity, PlayerActivity::class.java)) }
        if (open) compose.waitUntil(15000) { currentActivity() != null }
    }
    private fun controls() {
        if (compose.onAllNodesWithTag("player-options").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("player-surface").performClick()
        compose.onNodeWithTag("player-options").assertIsDisplayed()
    }
    private fun capture(name: String) {
        compose.waitUntil(20000) { !graph.playback.details.value.buffering }
        // Configuration changes precede the platform's rotation transition completion.
        android.os.SystemClock.sleep(800)
        compose.waitForIdle()
        val destination = File(Environment.getExternalStorageDirectory(), folder.child("evidence").path).apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap -> File(destination, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        fun describe(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0): org.json.JSONObject {
            val result = org.json.JSONObject()
            if (node == null || depth > 40) return result
            val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
            result.put("text", node.text?.toString()).put("description", node.contentDescription?.toString()).put("bounds", bounds.toShortString())
            result.put("children", org.json.JSONArray((0 until node.childCount).map { describe(node.getChild(it), depth + 1) }))
            return result
        }
        File(destination, "$name-ui.json").writeText(describe(instrumentation.uiAutomation.rootInActiveWindow).toString(2))
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "PLAYER_CHECK $name\n") })
    }
    @Test fun unsupportedItemCanNavigateToPlayableNeighboursWithoutOverlappingErrorControls() {
        val playlist = listOf("landscape.mp4", "mpeg1-pack.mpg", "portrait.mp4").map { name -> entries.single { it.name == name } }
        runBlocking { graph.playback.play(playlist[1], playlist) }
        compose.waitUntil(20_000) { graph.playback.state.value.error != null }
        assertFalse(graph.playback.state.value.retryable)
        val identity = graph.playback.controller!!
        val sessionId = graph.playback.sessionState.value.sessionId
        assertTrue(runBlocking { graph.playback.setSleepTimerMinutes(15) })
        main { compose.activity.startActivity(Intent(compose.activity, PlayerActivity::class.java)) }
        compose.waitUntil(15_000) { currentActivity() != null }
        compose.onNodeWithTag("player-error-panel").assertIsDisplayed()
        compose.onNodeWithTag("player-error-back").assertIsDisplayed()
        compose.onNodeWithTag("player-toggle").assertDoesNotExist()
        compose.onNodeWithTag("player-timeline").assertDoesNotExist()
        compose.onNodeWithTag("player-retry").assertDoesNotExist()
        capture("unsupported-error-controls")
        compose.onNodeWithTag("player-previous").performClick()
        compose.waitUntil(20_000) { main { identity.currentMediaItem?.mediaId == playlist[0].id && identity.isPlaying } }
        assertNull(graph.playback.state.value.error)
        assertSame(identity, graph.playback.controller)
        assertEquals(sessionId, graph.playback.sessionState.value.sessionId)
        assertEquals(SleepTimerKind.DURATION, graph.playback.sessionState.value.timerKind)
        main { identity.pause() }
        controls()
        compose.onNodeWithTag("player-next").performClick()
        compose.waitUntil(20_000) { graph.playback.state.value.error != null }
        assertFalse(main { identity.playWhenReady })
        compose.onNodeWithTag("player-next").performClick()
        compose.waitUntil(20_000) { main {
            identity.currentMediaItem?.mediaId == playlist[2].id && identity.playbackState == androidx.media3.common.Player.STATE_READY
        } }
        assertNull(graph.playback.state.value.error)
        assertFalse(main { identity.playWhenReady })
        assertSame(identity, graph.playback.controller)
        assertEquals(sessionId, graph.playback.sessionState.value.sessionId)
        assertEquals(SleepTimerKind.DURATION, graph.playback.sessionState.value.timerKind)
        assertTrue(graph.playback.sessionState.value.timerRemainingMs in 800_000L..900_000L)
        capture("unsupported-neighbour-recovered")
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream",
            "PLAYER_ERROR_RECOVERY previousPlaying=true nextPaused=true controlsSeparated=true sessionRetained=true timerRetained=true\n") })
    }

    @Test fun displayedAspectControlsDefaultOrientationAndManualRotationKeepsPosition() {
        val metadata = android.media.MediaMetadataRetriever()
        try {
            metadata.setDataSource(File(Environment.getExternalStorageDirectory(), folder.child("rotated.mp4").path).path)
            val rotation = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
            assertTrue("Fixture must contain a quarter-turn rotation (Android reports clockwise)", rotation != null && rotation % 180 == 90)
        } finally { metadata.release() }
        for ((name, orientation) in listOf("landscape.mp4" to Configuration.ORIENTATION_LANDSCAPE, "portrait.mp4" to Configuration.ORIENTATION_PORTRAIT,
            "square.mp4" to Configuration.ORIENTATION_LANDSCAPE, "rotated.mp4" to Configuration.ORIENTATION_PORTRAIT)) {
            play(name)
            compose.waitUntil(15000) { currentActivity()?.resources?.configuration?.orientation == orientation }
            assertEquals(name, orientation, currentActivity()!!.resources.configuration.orientation)
            main { graph.playback.controller!!.pause(); graph.playback.controller!!.seekTo(15000) }
            controls(); capture("orientation-${name.substringBefore('.')}")
            val identity = graph.playback.controller
            compose.onNodeWithTag("player-rotate").performClick()
            compose.waitUntil(15000) { currentActivity()?.resources?.configuration?.orientation != orientation }
            assertSame(identity, graph.playback.controller)
            assertTrue(main { graph.playback.controller!!.currentPosition in 14900..15100 })
        }
    }
    @Test fun doubleTapSeeksTenSecondsClampsAndLockBlocksGestures() {
        play("landscape.mp4")
        main { graph.playback.controller!!.pause(); graph.playback.controller!!.seekTo(5000) }
        controls()
        compose.onNodeWithTag("player-surface").performTouchInput { doubleClick(Offset(width * .8f, height * .3f)) }
        compose.waitUntil(10000) { main { graph.playback.controller!!.currentPosition in 14900..15100 } }
        compose.onNodeWithTag("player-surface").performTouchInput { doubleClick(Offset(width * .2f, height * .3f)) }
        compose.waitUntil(10000) { main { graph.playback.controller!!.currentPosition in 4900..5100 } }
        compose.onNodeWithTag("player-seek-back").performClick()
        compose.waitUntil(10000) { main { graph.playback.controller!!.currentPosition <= 100 } }
        compose.onNodeWithTag("player-lock").performClick()
        compose.onNodeWithTag("player-toggle").assertDoesNotExist()
        compose.onNodeWithTag("player-surface").performTouchInput { doubleClick(Offset(width * .8f, height * .3f)) }
        assertTrue(main { graph.playback.controller!!.currentPosition <= 100 })
        capture("locked")
        compose.onNodeWithTag("player-unlock").performClick()
        compose.onNodeWithTag("player-toggle").assertIsDisplayed()
        compose.onNodeWithTag("player-surface").performTouchInput { click(Offset(width * .5f, height * .25f)) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("player-options").fetchSemanticsNodes().isEmpty() }
        capture("immersive")
    }
    @Test fun optionsAndExternalSubtitlesPreserveSession() {
        play("tracks.mkv")
        main { graph.playback.controller!!.pause(); graph.playback.controller!!.seekTo(5000) }
        val identity = graph.playback.controller
        controls(); compose.onNodeWithTag("player-options").performClick()
        compose.onNodeWithTag("speed-1.5").performScrollTo().performClick()
        compose.onNodeWithText("화면 채움").performScrollTo().performClick()
        compose.onNodeWithText("한 파일 반복").performScrollTo().performClick()
        assertEquals(1.5f, main { identity!!.playbackParameters.speed }, 0f)
        assertEquals(androidx.media3.common.Player.REPEAT_MODE_ONE, main { identity!!.repeatMode })
        assertTrue(graph.playback.options.fill.value)
        compose.onNodeWithText("자막", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("captions.srt").performScrollTo().performClick()
        compose.waitUntil(15000) { graph.playback.details.value.tracks.groups.any { group -> group.type == androidx.media3.common.C.TRACK_TYPE_TEXT && (0 until group.length).any { group.getTrackFormat(it).label == "captions.srt" && group.isTrackSelected(it) } } }
        assertSame(identity, graph.playback.controller)
        assertTrue(main { identity!!.currentPosition in 4900..5100 })
        compose.onNodeWithText("자막 끄기").performScrollTo().performClick()
        compose.waitUntil(10000) { main { identity!!.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT) } }
        capture("subtitle-options")
        compose.onNodeWithText("닫기").performClick()
        controls(); compose.onNodeWithTag("player-options").performClick()
        compose.onNodeWithText("오디오", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("en ·", substring = true).performScrollTo().performClick()
        compose.waitUntil(10000) { graph.playback.details.value.tracks.groups.any { group -> group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && group.isSelected && group.getTrackFormat(0).language == "en" } }
        capture("audio-options")
    }

    @Test fun sleepTimerAndAudioOnlyKeepTheExistingSession() {
        play("landscape.mp4")
        val identity = graph.playback.controller
        main { identity!!.pause(); identity.seekTo(5_000) }
        assertTrue("The app controller must receive the timer command", main {
            identity!!.isSessionCommandAvailable(com.quickfiles.app.playback.PlaybackCommands.timerMinutes)
        })
        assertTrue("The playback service must accept a valid timer", runBlocking {
            graph.playback.setSleepTimerMinutes(15)
        })
        assertEquals(SleepTimerKind.DURATION, graph.playback.sessionState.value.timerKind)
        assertTrue(runBlocking { graph.playback.clearSleepTimer() })
        controls(); compose.onNodeWithTag("player-options").performClick()
        compose.onNodeWithTag("player-sleep-timer-entry").performScrollTo().performClick()
        compose.onNodeWithTag("sleep-timer-15").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { graph.playback.sessionState.value.timerKind == SleepTimerKind.DURATION }
        assertTrue(graph.playback.sessionState.value.timerRemainingMs in 890_000L..900_000L)
        compose.onNodeWithTag("sleep-timer-running").assertTextContains("타이머", substring = true)
        compose.onNodeWithTag("sleep-timer-clear").performClick()
        compose.waitUntil(5_000) { graph.playback.sessionState.value.timerKind == SleepTimerKind.OFF }
        assertTrue("Finite media must remain eligible for end-of-item timer", graph.playback.details.value.run {
            mediaId.isNotBlank() && duration > 0
        })
        compose.onNodeWithText("닫기").performClick()
        controls(); compose.onNodeWithTag("player-options").performClick()
        compose.onNodeWithTag("player-sleep-timer-entry").performScrollTo().performClick()
        compose.onNodeWithTag("sleep-timer-end").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { graph.playback.sessionState.value.timerKind == SleepTimerKind.END_OF_ITEM }
        val firstId = main { identity!!.currentMediaItem!!.mediaId }
        val portraitId = entries.single { it.name == "portrait.mp4" }.id
        main {
            val portraitIndex = (0 until identity!!.mediaItemCount).first { identity.getMediaItemAt(it).mediaId == portraitId }
            identity.seekTo(portraitIndex, 0)
        }
        compose.waitUntil(10_000) { main { identity!!.currentMediaItem?.mediaId == portraitId } }
        compose.waitUntil(5_000) { graph.playback.sessionState.value.timerKind == SleepTimerKind.OFF }
        compose.onNodeWithText("닫기").performClick()

        main {
            val landscapeIndex = (0 until identity!!.mediaItemCount).first { identity.getMediaItemAt(it).mediaId == firstId }
            identity.seekTo(landscapeIndex, 5_000)
            identity.play()
        }
        compose.waitUntil(10_000) { graph.playback.details.value.run { mediaId == firstId && audioAvailable } }
        controls()
        compose.onNodeWithTag("player-audio-only").performScrollTo().performClick()
        compose.waitUntil(10_000) { graph.playback.sessionState.value.audioOnly && currentActivity() == null }
        assertSame(identity, graph.playback.controller)
        assertTrue(main { identity!!.playWhenReady })
        assertTrue(main { identity!!.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO) })

        val monitor = instrumentation.addMonitor(PlayerActivity::class.java.name, null, false)
        val reopened = try {
            main { compose.activity.startActivity(Intent(compose.activity, PlayerActivity::class.java)) }
            instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as? PlayerActivity
        } finally { instrumentation.removeMonitor(monitor) }
        assertNotNull(reopened)
        compose.waitUntil(10_000) {
            !graph.playback.sessionState.value.audioOnly && main {
                !identity!!.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO)
            }
        }
        assertSame(identity, graph.playback.controller)
    }

    @Test fun oneMinuteTimerExpiresWhilePlayerIsInBackground() {
        play("landscape.mp4")
        assertTrue(runBlocking { graph.playback.setSleepTimerMinutes(1) })
        val started = android.os.SystemClock.elapsedRealtime()
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        compose.waitUntil(70_000) { !graph.playback.state.value.visible }
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        assertTrue("Timer stopped too early: ${elapsed}ms", elapsed >= 59_000)
        assertTrue("Timer stopped too late: ${elapsed}ms", elapsed <= 66_000)
        assertEquals(SleepTimerKind.OFF, graph.playback.sessionState.value.timerKind)
        assertFalse(graph.playback.state.value.playing)
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "SLEEP_TIMER background=true elapsedMs=$elapsed stopped=true\n") })
    }

    @Test fun oneMinuteTimerClosesPictureInPicture() {
        Assume.assumeTrue(
            "Run only for the explicit PiP timer integration check",
            InstrumentationRegistry.getArguments().getString("pipSleepTimer") == "true",
        )
        play("landscape.mp4")
        val player = currentActivity()!!
        main {
            graph.playback.controller!!.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            graph.playback.controller!!.seekTo(0)
        }
        assertTrue(runBlocking { graph.playback.setSleepTimerMinutes(1) })
        val started = android.os.SystemClock.elapsedRealtime()
        controls()
        compose.onNodeWithTag("player-pip").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { player.isInPictureInPictureMode }
        fun hasSystemPipTask(): Boolean {
            val tasks = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("dumpsys activity activities"),
            ).use { it.readBytes().toString(Charsets.UTF_8) }
            return tasks.lineSequence().any {
                it.contains("Task{") && it.contains("mode=pinned") && it.contains("com.quickfiles.app")
            }
        }
        compose.waitUntil(10_000) { hasSystemPipTask() }

        compose.waitUntil(70_000) { !graph.playback.state.value.visible }
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        compose.waitUntil(10_000) { main { player.isDestroyed } && !hasSystemPipTask() }
        assertTrue("Timer stopped too early: ${elapsed}ms", elapsed >= 59_000L)
        assertTrue("Timer stopped too late: ${elapsed}ms", elapsed <= 66_000L)
        assertEquals(SleepTimerKind.OFF, graph.playback.sessionState.value.timerKind)
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "SLEEP_TIMER pip=true elapsedMs=$elapsed playbackStopped=true pipClosed=true\n")
        })
    }

    @Test fun fifteenMinuteTimerExpiresWhileScreenIsOff() {
        Assume.assumeTrue(
            "Run only for the explicit physical-device endurance check",
            InstrumentationRegistry.getArguments().getString("lockedSleepTimer") == "true",
        )
        play("landscape.mp4")
        main {
            graph.playback.controller!!.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            graph.playback.controller!!.seekTo(0)
            graph.playback.controller!!.play()
        }
        assertTrue(runBlocking { graph.playback.setSleepTimerMinutes(15) })
        compose.waitUntil(5_000) {
            graph.playback.sessionState.value.run {
                timerKind == SleepTimerKind.DURATION && timerRemainingMs in 890_000L..900_000L
            }
        }

        val started = android.os.SystemClock.elapsedRealtime()
        try {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            android.os.SystemClock.sleep(2_000)
            val powerState = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("dumpsys power"),
            ).use { it.readBytes().toString(Charsets.UTF_8) }
            assertTrue(
                "Device did not enter a screen-off power state",
                Regex("mWakefulness=(Asleep|Dozing)").containsMatchIn(powerState) ||
                    Regex("Display Power: state=OFF").containsMatchIn(powerState),
            )
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("stream", "SLEEP_TIMER screenOff=true durationMinutes=15\n")
            })

            val deadline = started + 925_000L
            while (
                graph.playback.sessionState.value.timerKind != SleepTimerKind.OFF &&
                android.os.SystemClock.elapsedRealtime() < deadline
            ) {
                android.os.SystemClock.sleep(500)
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            assertEquals(SleepTimerKind.OFF, graph.playback.sessionState.value.timerKind)
            assertTrue("Timer stopped too early: ${elapsed}ms", elapsed >= 899_000L)
            assertTrue("Timer stopped too late: ${elapsed}ms", elapsed <= 925_000L)
            assertFalse(graph.playback.state.value.playing)
            assertFalse(graph.playback.state.value.visible)

            val notificationManager = compose.activity.getSystemService(android.app.NotificationManager::class.java)
            val notificationDeadline = android.os.SystemClock.elapsedRealtime() + 10_000L
            while (
                notificationManager.activeNotifications.any {
                    it.notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)
                } && android.os.SystemClock.elapsedRealtime() < notificationDeadline
            ) {
                android.os.SystemClock.sleep(250)
            }
            assertTrue(
                "Media notification remained after the sleep timer stopped playback",
                notificationManager.activeNotifications.none {
                    it.notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)
                },
            )
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString(
                    "stream",
                    "SLEEP_TIMER screenOff=true elapsedMs=$elapsed playbackStopped=true notificationRemoved=true\n",
                )
            })
        } finally {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        }
    }

    @Test fun playerMenusRemainUsableWithBothThemesAndLargeFonts() {
        val original = runBlocking { graph.settings.settings.first() }
        val scale = android.provider.Settings.System.getFloat(compose.activity.contentResolver, android.provider.Settings.System.FONT_SCALE, 1f)
        fun font(value: Float) {
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale $value").close()
            compose.waitUntil(15000) { currentActivity()?.resources?.configuration?.fontScale?.let { kotlin.math.abs(it - value) < .01f } == true }
        }
        play("landscape.mp4")
        main { graph.playback.controller!!.pause(); graph.playback.controller!!.seekTo(12000) }
        val identity = graph.playback.controller
        try {
            for (theme in listOf(com.quickfiles.app.data.ThemeMode.LIGHT, com.quickfiles.app.data.ThemeMode.DARK)) for (value in listOf(1f, 1.3f, 2f)) {
                runBlocking { graph.settings.save(original.copy(theme = theme)) }
                font(value); controls()
                compose.onNodeWithTag("player-back").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                compose.onNodeWithTag("player-options").performClick()
                compose.onNodeWithText("전체 반복").performScrollTo().assertIsDisplayed()
                capture("player-${theme.name}-$value")
                compose.onNodeWithText("닫기").performClick()
                assertSame(identity, graph.playback.controller)
                assertTrue(main { identity!!.currentPosition in 11900..12100 })
                assertFalse("Foreground video must remain enabled after Activity recreation", main { identity!!.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO) })
            }
        } finally { runBlocking { graph.settings.save(original) }; font(scale) }
    }
}
