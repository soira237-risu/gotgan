package com.quickfiles.app

import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.playback.SleepTimerKind
import com.quickfiles.app.storage.FileEntry
import com.quickfiles.app.storage.LOCAL_SOURCE
import com.quickfiles.app.storage.StorageLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SleepTimerEndDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = AppGraph.get(compose.activity)
    private lateinit var playlist: List<FileEntry>
    private fun <T> main(block: () -> T): T = runBlocking(Dispatchers.Main) { block() }

    @Before fun prepare() {
        val path = InstrumentationRegistry.getArguments().getString("playerMediaDir")
        Assume.assumeTrue("Generated player fixtures required", path != null)
        val external = Environment.getExternalStorageDirectory().canonicalFile
        val root = File(external, "Download/QuickFilesTest").canonicalFile
        val directory = File(external, path!!).canonicalFile
        require(directory.parentFile == root && directory.name.startsWith("validation-player-") && directory.isDirectory) {
            "Only an existing canonical Download/QuickFilesTest/validation-player-* directory is allowed"
        }
        val folder = StorageLocation(LOCAL_SOURCE, external.toPath().relativize(directory.toPath()).toString().replace(File.separatorChar, '/'))
        val names = listOf("landscape.mp4", "portrait.mp4")
        names.forEach { name ->
            val file = File(directory, name).canonicalFile
            require(file.parentFile == directory && file.isFile) { "Fixture must remain inside its generated directory: $name" }
        }
        playlist = runBlocking { names.map { graph.repository.local.stat(folder.child(it)) } }
        assertTrue(playlist.all { it.isVideo })
    }

    @After fun finish() = cleanupPlayerTest(graph.playback)

    // Avoid rewriting fixture files or saved-position rows. Reset only the live
    // controller position before waiting for the generated media to become ready.
    private fun preparePaused(entry: FileEntry) {
        runBlocking(Dispatchers.Main) {
            graph.playback.play(entry, playlist)
            graph.playback.controller!!.apply {
                pause()
                seekTo(0)
            }
        }
        compose.waitUntil(20_000) {
            graph.playback.state.value.error != null || main {
                graph.playback.controller!!.run {
                    currentMediaItem?.mediaId == entry.id && playbackState == Player.STATE_READY && duration > 1_000
                }
            }
        }
        assertNull(graph.playback.state.value.error)
        main {
            assertEquals(entry.id, graph.playback.controller!!.currentMediaItem?.mediaId)
            assertEquals(2, graph.playback.controller!!.mediaItemCount)
            assertFalse(graph.playback.controller!!.playWhenReady)
        }
    }

    @Test fun endOfItemStopsBeforeNextFileWithRepeatOff() = verifyEndOfItem(Player.REPEAT_MODE_OFF)

    @Test fun endOfItemStopsBeforeNextFileWithRepeatOne() = verifyEndOfItem(Player.REPEAT_MODE_ONE)

    @Test fun endOfItemStopsBeforeNextFileWithRepeatAll() = verifyEndOfItem(Player.REPEAT_MODE_ALL)

    private fun verifyEndOfItem(repeatMode: Int) {
        val landscape = playlist.first()
        val portrait = playlist.last()
        preparePaused(landscape)
        val controller = main { graph.playback.controller!! }
        val duration = main { controller.duration }
        main {
            controller.repeatMode = repeatMode
            controller.setPlaybackSpeed(1f)
            controller.seekTo(duration - 900L)
        }
        compose.waitUntil(10_000) {
            main { controller.currentPosition in (duration - 1_000L)..(duration - 700L) && controller.playbackState == Player.STATE_READY }
        }

        // All listener fields and controller access are confined to Main. Recording
        // transitions catches a brief next-item selection that polling could miss.
        var selectedNext = false
        var repeatedCurrent = false
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem?.mediaId == portrait.id) selectedNext = true
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) repeatedCurrent = true
            }
        }
        main { controller.addListener(listener) }
        try {
            assertTrue("End-of-item timer was rejected", runBlocking { graph.playback.setSleepTimerEndOfItem() })
            assertEquals(SleepTimerKind.END_OF_ITEM, graph.playback.sessionState.value.timerKind)
            assertEquals(landscape.id, graph.playback.sessionState.value.timerTargetMediaId)
            main {
                assertEquals("Arming the timer must preserve the selected repeat setting", repeatMode, controller.repeatMode)
                assertEquals(landscape.id, controller.currentMediaItem?.mediaId)
                assertFalse(controller.playWhenReady)
                controller.play()
            }
            val started = SystemClock.elapsedRealtime()
            compose.waitUntil(15_000) {
                graph.playback.state.value.error != null || (
                    !graph.playback.state.value.visible &&
                        graph.playback.sessionState.value.timerKind == SleepTimerKind.OFF &&
                        main { controller.mediaItemCount == 0 && !controller.playWhenReady && !controller.isPlaying }
                    )
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            assertNull(graph.playback.state.value.error)
            assertEquals(SleepTimerKind.OFF, graph.playback.sessionState.value.timerKind)
            assertFalse(graph.playback.state.value.visible)
            assertFalse(graph.playback.state.value.playing)
            // Observe after shutdown as well, so a delayed automatic transition
            // cannot make this a pass merely because an empty state was transient.
            SystemClock.sleep(1_000)
            main {
                assertFalse("The next playlist item was selected before/after timer expiry", selectedNext)
                assertFalse("The current item repeated instead of ending the session", repeatedCurrent)
                assertEquals(0, controller.mediaItemCount)
                assertFalse(controller.playWhenReady)
                assertFalse(controller.isPlaying)
            }
            report("SLEEP_TIMER_END repeat=$repeatMode elapsedMs=$elapsed stopped=true timerOff=true nextItemSelected=false repeated=false")
        } finally {
            main { controller.removeListener(listener) }
        }
    }

    @Test fun explicitPlayChangesSessionAndRetainsDurationTimerAndPlaybackOptions() {
        preparePaused(playlist.first())
        val controller = main { graph.playback.controller!! }
        main {
            controller.setPlaybackSpeed(1.5f)
            controller.repeatMode = Player.REPEAT_MODE_ALL
            controller.volume = 0f
            graph.playback.options.fill.value = true
        }
        assertTrue(runBlocking { graph.playback.setSleepTimerMinutes(15) })
        val firstSessionId = graph.playback.sessionState.value.sessionId
        assertTrue(firstSessionId.isNotBlank())
        // Let at least two seconds elapse while paused. A reset to fifteen minutes
        // during explicit play would then exceed the recorded remaining time.
        compose.waitUntil(5_000) {
            graph.playback.sessionState.value.run {
                timerKind == SleepTimerKind.DURATION && timerRemainingMs in 890_000L..898_000L
            }
        }
        val remainingBefore = graph.playback.sessionState.value.timerRemainingMs
        runBlocking(Dispatchers.Main) {
            graph.playback.play(playlist.last(), playlist)
            graph.playback.controller!!.seekTo(0)
        }
        compose.waitUntil(20_000) {
            graph.playback.state.value.error != null || main {
                graph.playback.controller!!.run { currentMediaItem?.mediaId == playlist.last().id && isPlaying }
            }
        }
        assertNull(graph.playback.state.value.error)
        val session = graph.playback.sessionState.value
        assertTrue(session.sessionId.isNotBlank())
        assertNotEquals(firstSessionId, session.sessionId)
        assertEquals(SleepTimerKind.DURATION, session.timerKind)
        assertTrue("The existing duration timer was reset or lost: before=$remainingBefore after=${session.timerRemainingMs}",
            session.timerRemainingMs in (remainingBefore - 30_000L)..remainingBefore)
        main {
            assertSame(controller, graph.playback.controller)
            assertEquals(playlist.last().id, controller.currentMediaItem?.mediaId)
            assertEquals(2, controller.mediaItemCount)
            assertTrue(controller.isPlaying)
            assertEquals(1.5f, controller.playbackParameters.speed, 0f)
            assertEquals(Player.REPEAT_MODE_ALL, controller.repeatMode)
            assertEquals(0f, controller.volume, 0f)
            assertTrue(graph.playback.options.fill.value)
        }
        report("SLEEP_TIMER_EXPLICIT_PLAY sessionChanged=true timerRetained=true speedRetained=true repeatRetained=true muteRetained=true fillRetained=true")
    }

    private fun report(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }
}
