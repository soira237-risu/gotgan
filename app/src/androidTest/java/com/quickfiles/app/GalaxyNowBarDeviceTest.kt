package com.quickfiles.app

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.playback.PlaybackConnection
import com.quickfiles.app.playback.PlayerActivity
import com.quickfiles.app.storage.LOCAL_SOURCE
import com.quickfiles.app.storage.StorageLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runners.model.MultipleFailureException
import java.io.File

/** Samsung's real lock-screen buttons, not direct platform-controller commands. */
class GalaxyNowBarDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation
    private var playback: PlaybackConnection? = null
    private var originalAutomationFlags: Int? = null
    private lateinit var evidence: File
    private val captureId = SystemClock.elapsedRealtime().toString()

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        ui.executeShellCommand(command),
    ).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun <T> main(action: () -> T): T = runBlocking(Dispatchers.Main) { action() }

    private fun await(label: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (condition()) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Timed out waiting for $label")
    }

    private fun nodes(root: AccessibilityNodeInfo?, id: String): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40) return
            if (node.viewIdResourceName == PREFIX + id) result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return result
    }

    private fun mediaCard(): AccessibilityNodeInfo? = nodes(ui.rootInActiveWindow, "nowbar_item_root_view")
        .firstOrNull { card -> card.isVisibleToUser && nodes(card, "stack_card_main_text").any { it.text?.toString() == "landscape.mp4" } }

    private fun description(node: AccessibilityNodeInfo) = node.contentDescription?.toString()?.replace(" ", "").orEmpty()

    private fun clickAction(id: String, expected: Set<String>) {
        var selected: AccessibilityNodeInfo? = null
        await("Now bar $id $expected") {
            // Several Now bar cards share IDs. Scope to the fixture card and inspect
            // its first visible matching action rather than clicking another card.
            selected = nodes(mediaCard(), id).firstOrNull { it.isVisibleToUser }
            selected?.let { it.isEnabled && it.isClickable && description(it) in expected } == true
        }
        assertTrue("Now bar $id must accept ACTION_CLICK", selected!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun status(message: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "$message\n")
    })

    private fun screenOff(power: String) = Regex("mWakefulness=(Asleep|Dozing)").containsMatchIn(power)

    private fun capture(label: String, requireScreenshot: Boolean = true): Boolean {
        fun describe(node: AccessibilityNodeInfo?, depth: Int = 0): JSONObject {
            if (node == null || depth > 40) return JSONObject()
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            return JSONObject().put("resourceId", node.viewIdResourceName).put("text", node.text?.toString())
                .put("description", node.contentDescription?.toString()).put("bounds", bounds.toShortString())
                .put("visible", node.isVisibleToUser).put("enabled", node.isEnabled).put("clickable", node.isClickable)
                .put("children", JSONArray((0 until node.childCount).map { describe(node.getChild(it), depth + 1) }))
        }
        val prefix = "nowbar-final-$label-$captureId"
        File(evidence, "$prefix-ui.json").writeText(describe(ui.rootInActiveWindow).toString(2))
        val screenshot = ui.takeScreenshot()
        val saved = screenshot?.let { bitmap ->
            try { File(evidence, "$prefix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        } ?: false
        if (requireScreenshot) assertTrue("Screenshot missing for $label", saved)
        return saved
    }

    @Test fun realNowBarButtonsSeekPauseResumeAndRecordAodVisibility() {
        Assume.assumeTrue("Galaxy S25 Ultra/API 36 only", Build.MODEL == "SM-S938N" && Build.VERSION.SDK_INT == 36)
        val relative = InstrumentationRegistry.getArguments().getString("playerMediaDir")
        Assume.assumeTrue("Generated playerMediaDir required", relative != null)
        val storage = Environment.getExternalStorageDirectory().canonicalFile
        val ownedRoot = File(storage, "Download/QuickFilesTest").canonicalFile
        val requested = File(storage, relative!!).absoluteFile.toPath().normalize().toFile()
        val directory = requested.canonicalFile
        require(directory == requested && directory.parentFile == ownedRoot && directory.isDirectory &&
            directory.name.matches(Regex("validation-player-[A-Za-z0-9-]+"))) { "Expected an execution-owned player fixture directory" }
        val media = File(directory, "landscape.mp4")
        require(media.canonicalFile == media && media.isFile) { "Expected a regular, owned landscape.mp4 fixture" }
        evidence = File(directory, "evidence")
        require(evidence.canonicalFile == evidence && (evidence.isDirectory || evidence.mkdir()))

        val graph = AppGraph.get(compose.activity)
        playback = graph.playback
        val automationInfo = ui.serviceInfo
        originalAutomationFlags = automationInfo.flags
        automationInfo.flags = automationInfo.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        ui.serviceInfo = automationInfo
        val entry = runBlocking { graph.repository.local.stat(StorageLocation(LOCAL_SOURCE, media.relativeTo(storage).invariantSeparatorsPath)) }
        runBlocking { graph.playback.play(entry, listOf(entry)) }
        await("fixture playback", 20_000) { graph.playback.state.value.playing || graph.playback.state.value.error != null }
        assertNull(graph.playback.state.value.error)
        val player = graph.playback.controller!!
        main { player.repeatMode = Player.REPEAT_MODE_ONE; player.setPlaybackSpeed(1f); player.seekTo(15_000) }
        await("fixture seek ready") { main { player.isPlaying && player.playbackState == Player.STATE_READY && player.currentPosition >= 15_000 } }
        main { compose.activity.startActivity(Intent(compose.activity, PlayerActivity::class.java)) }
        await("player window") { ui.rootInActiveWindow?.packageName?.toString() == "com.quickfiles.app" && main { !compose.activity.hasWindowFocus() } }
        shell("input keyevent KEYCODE_HOME")
        await("Home transition") { ui.rootInActiveWindow?.packageName?.toString()?.let { it != "com.quickfiles.app" } == true }
        shell("input keyevent KEYCODE_SLEEP")
        await("sleep transition") { screenOff(shell("dumpsys power")) }
        shell("input keyevent KEYCODE_WAKEUP")
        await("awake lock-screen Now bar") { Regex("mWakefulness=Awake").containsMatchIn(shell("dumpsys power")) && mediaCard() != null }

        clickAction("sec_action_1", setOf("일시중지", "일시정지"))
        await("pause from Now bar") { main { !player.playWhenReady && !player.isPlaying } }
        val pausedPosition = main { player.currentPosition }
        assertTrue("Seek test must remain away from the file boundary", main { pausedPosition + 10_100 < player.duration })
        capture("paused")
        clickAction("sec_action_2", setOf("10초앞으로"))
        await("Now bar +10 seconds") { main { kotlin.math.abs(player.currentPosition - pausedPosition - 10_000) <= 100 } }
        assertFalse(main { player.playWhenReady })
        val forwardPosition = main { player.currentPosition }
        clickAction("sec_action_0", setOf("10초뒤로"))
        await("Now bar -10 seconds") { main { kotlin.math.abs(player.currentPosition - forwardPosition + 10_000) <= 100 } }
        assertFalse(main { player.playWhenReady })
        val backwardPosition = main { player.currentPosition }
        clickAction("sec_action_1", setOf("재생"))
        await("resume from Now bar") { main { player.isPlaying } }
        await("Now bar pause action after resume") { nodes(mediaCard(), "sec_action_1").firstOrNull { it.isVisibleToUser }?.let { description(it) in setOf("일시중지", "일시정지") } == true }
        capture("playing")
        status("NOWBAR_CHECK realUI=true pausedPositionMs=$pausedPosition forwardPositionMs=$forwardPosition backwardPositionMs=$backwardPosition pause=true resume=true")

        shell("input keyevent KEYCODE_SLEEP")
        SystemClock.sleep(2_000)
        await("AOD or asleep power state") { screenOff(shell("dumpsys power")) }
        val power = shell("dumpsys power")
        File(evidence, "nowbar-final-aod-power-$captureId.txt").writeText(power)
        val exposed = mediaCard() != null
        val screenshot = capture("aod", requireScreenshot = false)
        assertTrue("Playback must continue with the screen off", main { player.isPlaying })
        assertEquals(entry.id, main { player.currentMediaItem?.mediaId })
        assertEquals(1, main { player.mediaItemCount })
        status("NOWBAR_CHECK aodPower=${Regex("mWakefulness=(\\w+)").find(power)?.groupValues?.get(1)} aodNodesExposed=$exposed aodScreenshotSaved=$screenshot aodControls=unverified playbackContinues=true evidence=${evidence.absolutePath}")
    }

    @After fun cleanup() {
        val connection = playback ?: return
        val failures = mutableListOf<Throwable>()
        fun step(action: () -> Unit) { try { action() } catch (failure: Throwable) { failures += failure } }
        step { shell("input keyevent KEYCODE_WAKEUP"); await("cleanup wake") { Regex("mWakefulness=Awake").containsMatchIn(shell("dumpsys power")) } }
        // This standard debug command cannot dismiss a secure credential challenge.
        step { shell("wm dismiss-keyguard") }
        step { cleanupPlayerTest(connection) }
        step { originalAutomationFlags?.let { flags -> ui.serviceInfo = ui.serviceInfo.apply { this.flags = flags } } }
        MultipleFailureException.assertEmpty(failures)
    }

    private companion object { const val PREFIX = "com.samsung.android.app.aodservice:id/" }
}
