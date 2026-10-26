package com.quickfiles.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Environment
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.playback.PlayerActivity
import com.quickfiles.app.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runners.model.MultipleFailureException
import java.io.File
import java.io.DataOutputStream
import java.util.UUID

/** Exercises the real platform session used by Android/Samsung system media controls. */
class MediaNotificationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun platformNotificationControlsAndReopenPreservePlayback() = checkNotification(false)
    @Test fun videoNotificationControlsAndReopenPreservePlayback() = checkNotification(true)

    private fun checkNotification(video: Boolean) {
        val graph = AppGraph.get(compose.activity)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalTaskId = compose.activity.taskId
        val manager = compose.activity.getSystemService(NotificationManager::class.java)
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val run = File(root, "media-notification-${UUID.randomUUID()}").canonicalFile
        require(run.toPath().startsWith(root.toPath()) && run != root && run.mkdirs())
        val audio = File(run, if (video) "notification-video.mp4" else "notification-tone.wav")
        val samples = 8000 * 60
        if (video) {
            val path = requireNotNull(InstrumentationRegistry.getArguments().getString("mediaPath")) { "Generated video fixture required" }
            val source = File(Environment.getExternalStorageDirectory(), path).canonicalFile
            require(source.toPath().startsWith(root.toPath()) && source.parentFile!!.name.startsWith("validation-") && source.isFile)
            source.copyTo(audio)
        } else DataOutputStream(audio.outputStream().buffered()).use { out ->
            fun word(value: Int) = out.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
            fun number(value: Int) = out.writeInt(Integer.reverseBytes(value))
            out.writeBytes("RIFF"); number(36 + samples * 2); out.writeBytes("WAVEfmt "); number(16)
            word(1); word(1); number(8000); number(16000); word(2); word(16)
            out.writeBytes("data"); number(samples * 2)
            repeat(samples) { word((kotlin.math.sin(it * 2 * Math.PI * 220 / 8000) * 1000).toInt()) }
        }
        val path = audio.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath
        var savedId: String? = null
        var activity: PlayerActivity? = null
        val failures = mutableListOf<Throwable>()
        fun cleanupStep(action: () -> Unit) {
            try { action() } catch (failure: Throwable) { failures += failure }
        }
        try {
            val entry = runBlocking { graph.repository.local.stat(StorageLocation(LOCAL_SOURCE, path)) }
            savedId = entry.id
            runBlocking { graph.playback.play(entry, listOf(entry)) }
            compose.waitUntil(20_000) { graph.playback.state.value.playing || graph.playback.state.value.error != null }
            assertNull(graph.playback.state.value.error)
            assertEquals(video, graph.playback.state.value.video)
            compose.waitUntil(10_000) { manager.activeNotifications.any { it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) } }
            val notification = manager.activeNotifications.first { it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }.notification
            assertEquals("android.app.Notification$" + "MediaStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
            val token = androidx.core.os.BundleCompat.getParcelable(notification.extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)!!
            val platform = MediaController(compose.activity, token)
            assertEquals(entry.name, requireNotNull(platform.metadata).getString(android.media.MediaMetadata.METADATA_KEY_TITLE))
            assertNotNull("System media card must reopen the player", platform.sessionActivity)
            val actions = requireNotNull(platform.playbackState).actions
            assertTrue(actions and PlaybackState.ACTION_PLAY_PAUSE != 0L || actions and PlaybackState.ACTION_PAUSE != 0L)
            assertTrue(actions and PlaybackState.ACTION_SEEK_TO != 0L)
            assertTrue(actions and PlaybackState.ACTION_REWIND != 0L)
            assertTrue(actions and PlaybackState.ACTION_FAST_FORWARD != 0L)
            val monitor = instrumentation.addMonitor(PlayerActivity::class.java.name, null, false)
            activity = try {
                platform.sessionActivity!!.send()
                instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? PlayerActivity
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            assertNotNull(activity)
            platform.transportControls.seekTo(10_000)
            // A controller reports the requested seek position before the decoder is ready.
            compose.waitUntil(15_000) { runBlocking(Dispatchers.Main) {
                graph.playback.controller!!.let { it.isPlaying && it.playbackState == androidx.media3.common.Player.STATE_READY && it.currentPosition >= 10_100 }
            } }
            platform.transportControls.pause()
            compose.waitUntil(5_000) { !graph.playback.state.value.playWhenReady }
            val seekStart = runBlocking(Dispatchers.Main) { graph.playback.controller!!.currentPosition }
            platform.transportControls.fastForward()
            compose.waitUntil(5_000) { runBlocking(Dispatchers.Main) { graph.playback.controller!!.currentPosition >= seekStart + 9_900 } }
            val seekForward = runBlocking(Dispatchers.Main) { graph.playback.controller!!.currentPosition }
            platform.transportControls.rewind()
            compose.waitUntil(5_000) { runBlocking(Dispatchers.Main) { graph.playback.controller!!.currentPosition <= seekForward - 9_900 } }
            platform.transportControls.play()
            compose.waitUntil(5_000) { graph.playback.state.value.playing }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            val before = runBlocking(Dispatchers.Main) { graph.playback.controller!!.currentPosition }
            SystemClock.sleep(5000)
            val after = runBlocking(Dispatchers.Main) { graph.playback.controller!!.currentPosition }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "MEDIA_BACKGROUND video=$video positionDeltaMs=${after - before}\n") })
            assertTrue("Background playback video=$video before=$before after=$after state=${graph.playback.state.value}", after >= before + 4000)
            platform.transportControls.pause()
            compose.waitUntil(5_000) { !graph.playback.state.value.playing }
            platform.transportControls.play()
            compose.waitUntil(5_000) { graph.playback.state.value.playing }
            assertEquals(entry.id, runBlocking(Dispatchers.Main) { graph.playback.controller!!.currentMediaItem!!.mediaId })
            val playerIdentity = graph.playback.controller
            platform.sessionActivity!!.send()
            compose.waitUntil(15000) { activity!!.hasWindowFocus() && !activity!!.isInPictureInPictureMode }
            compose.waitUntil(15000) { runBlocking(Dispatchers.Main) { graph.playback.controller!!.isPlaying && !graph.playback.controller!!.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO) } }
            assertSame(playerIdentity, graph.playback.controller)
            if (video) {
                // The generated video has a blue field. A playing controller and enabled
                // track alone cannot establish that the returning surface displays frames.
                compose.waitUntil(15000) {
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        val pixel = bitmap.getPixel(bitmap.width / 4, bitmap.height / 2)
                        bitmap.recycle()
                        android.graphics.Color.blue(pixel) > android.graphics.Color.red(pixel) + 20 && android.graphics.Color.green(pixel) > android.graphics.Color.red(pixel) + 10
                    } ?: false
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "MEDIA_VIDEO_RETURN renderedBlueFixture=true\n") })
            }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cmd statusbar expand-notifications")).use { it.readBytes() }
            compose.waitUntil(10000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.systemui" }
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                File(run, "media-notification.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
        } catch (failure: Throwable) {
            failures += failure
        } finally {
            // Closing the descriptor alone does not establish command completion.
            // This also runs when an assertion fails while the shade is expanded.
            cleanupStep {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse")).use { it.readBytes() }
            }
            cleanupStep { cleanupPlayerTest(graph.playback) }
            cleanupStep {
                // Home may still be in front after a failed background assertion.
                // Reorder the scenario's existing task rather than create a new task.
                compose.activityRule.scenario.onActivity { main ->
                    assertEquals("Cleanup must return to the original task", originalTaskId, main.taskId)
                    main.startActivity(Intent(main, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
                compose.waitUntil(10_000) {
                    var focused = false
                    compose.activityRule.scenario.onActivity { main ->
                        focused = main.taskId == originalTaskId && main.hasWindowFocus() &&
                            main.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
                    }
                    focused
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "MEDIA_CLEANUP shadeCollapsed=true originalMainFocused=true\n") })
            }
            cleanupStep { runBlocking { savedId?.let { graph.dao.removeSaved("playback:$it") } } }
            cleanupStep {
                require(audio.canonicalFile.toPath().startsWith(run.toPath()))
                assertTrue("Owned notification fixture must be deleted", !audio.exists() || audio.delete())
            }
        }
        MultipleFailureException.assertEmpty(failures)
        compose.waitUntil(10_000) { manager.activeNotifications.none { it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) } }
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "MEDIA_NOTIFICATION video=$video mediaStyle=true title=true reopen=true seek=true backgroundSeconds=5 pauseResume=true stopRemoves=true evidence=${run.absolutePath}\n") })
    }
}
