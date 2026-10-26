package com.quickfiles.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Environment
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.data.*
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.ExplorerViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class UiLayoutMatrixDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun folderGridSplitAndViewerFitThemeFontAndOrientationMatrix() {
        val graph = AppGraph.get(compose.activity)
        val original = runBlocking { graph.settings.settings.first() }
        val originalScale = Settings.System.getFloat(compose.activity.contentResolver, Settings.System.FONT_SCALE, 1f)
        val originalOrientation = compose.activity.requestedOrientation
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val run = File(root, "validation-${UUID.randomUUID()}").canonicalFile
        require(run.toPath().startsWith(root.toPath()) && run != root && run.mkdirs())
        File(run, "destination").mkdir()
        File(run, "긴 이름을 가진 문서의 목록과 그리드 표시 확인.txt").writeText("layout fixture")
        val location = StorageLocation(LOCAL_SOURCE, run.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        lateinit var first: ExplorerViewModel; lateinit var second: ExplorerViewModel
        var initialized = false
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun font(scale: Float) {
            automation.executeShellCommand("settings put system font_scale $scale").close()
            compose.waitUntil(15_000) { kotlin.math.abs(compose.activity.resources.configuration.fontScale - scale) < .01f }
            compose.waitForIdle()
        }
        fun orientation(landscape: Boolean) {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(15_000) { compose.activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
            compose.waitForIdle()
        }
        try {
            compose.waitUntil(15_000) { graph.workspace.state.value.ready }
            compose.runOnIdle {
                graph.workspace.activate(0); graph.workspace.split(false)
                first = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java)
                second = ViewModelProvider(compose.activity).get("B", ExplorerViewModel::class.java)
                initialized = true
            }
            compose.waitUntil(15_000) { first.state.value.profilesReady && second.state.value.profilesReady }
            compose.runOnIdle { first.navigate(location); second.navigate(location.child("destination")) }
            compose.waitUntil(15_000) { !first.state.value.loading && !second.state.value.loading }
            val entry = first.state.value.entries.single { !it.isDirectory }
            val queries = StorageReadMetrics.count(LOCAL_SOURCE)
            for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (scale in listOf(1f, 1.3f, 2f)) {
                orientation(false); font(scale)
                runBlocking { graph.settings.save(original.copy(theme = theme, grid = false)) }
                compose.waitForIdle()
                compose.onNodeWithTag("entry-${entry.location.path}").assertIsDisplayed()
                compose.onNodeWithTag("folder-switch").performClick()
                compose.onNodeWithContentDescription("폴더 전환 닫기").assertIsDisplayed().performClick()
                compose.onNodeWithTag("view-grid").performClick()
                compose.onNodeWithTag("entry-${entry.location.path}").assertIsDisplayed()
                compose.runOnIdle { graph.workspace.split(true) }
                orientation(true)
                compose.onAllNodesWithTag("folder-switch").assertCountEquals(2)
                compose.onNodeWithTag("folder-grid").performScrollToIndex(1)
                compose.onNodeWithTag("entry-${entry.location.path}").assertIsDisplayed()
                compose.runOnIdle { first.open(entry) }
                compose.waitUntil(15_000) { first.state.value.preview?.document != null }
                compose.onNodeWithTag("close-preview").assertIsDisplayed().performClick()
                compose.onAllNodesWithTag("folder-switch").assertCountEquals(2)
                orientation(false)
                compose.onAllNodesWithTag("folder-switch").assertCountEquals(1)
                compose.onNodeWithTag("entry-${entry.location.path}").assertIsDisplayed()
                assertEquals(queries, StorageReadMetrics.count(LOCAL_SOURCE))
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "UI_LAYOUT theme=$theme font=$scale portrait=true split=true fullViewer=true directoryQueries=0\n") })
            }
        } finally {
            compose.runOnIdle { graph.workspace.split(false); if (initialized) { first.home(); second.home() } }
            runBlocking { graph.settings.save(original); graph.dao.removeSaved("recent:${location.key}"); graph.dao.removeSaved("recent:${location.child("destination").key}") }; font(originalScale)
            compose.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
            require(run.canonicalFile.toPath().startsWith(root.toPath()) && run != root); run.deleteRecursively()
        }
    }
}
