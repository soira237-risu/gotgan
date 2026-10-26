package com.quickfiles.app

import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.data.*
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.ExplorerViewModel
import com.quickfiles.app.ui.theme.appColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Exercises the settings offered by the real activity, rather than a preview. */
class AppearanceSettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsOfferNamedColorChoicesAndListDensity() {
        val graph = AppGraph.get(compose.activity)
        compose.waitUntil(15_000) { graph.workspace.state.value.ready }
        compose.runOnIdle {
            graph.workspace.activate(0)
            graph.workspace.split(false)
            ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java).home()
            graph.workspace.panel("설정")
        }
        compose.onNodeWithText("색상 테마").assertIsDisplayed()
        listOf("테라코타", "블루", "세이지 그린", "라벤더", "로즈", "흑백").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed().assertHasClickAction()
        }
        compose.onNodeWithText("목록 간격").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-list-COMPACT").assertHasClickAction()
        compose.onNodeWithTag("settings-list-NORMAL").assertHasClickAction()
        compose.runOnIdle { graph.workspace.panel(null) }
    }

    private fun start(graph: AppGraph): ExplorerViewModel {
        compose.waitUntil(15_000) { graph.workspace.state.value.ready }
        lateinit var model: ExplorerViewModel
        compose.runOnIdle {
            graph.workspace.activate(0); graph.workspace.split(false); graph.workspace.panel(null)
            model = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java)
            model.home()
        }
        compose.waitUntil(15_000) { model.state.value.profilesReady }
        return model
    }

    private fun runFolder(): File {
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val run = File(root, "appearance-${UUID.randomUUID()}").canonicalFile
        require(run.toPath().startsWith(root.toPath()) && run != root && run.mkdirs())
        return run
    }

    private fun capture(run: File, name: String) {
        compose.waitForIdle()
        SystemClock.sleep(150)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(5_000) { automation.rootInActiveWindow?.packageName?.toString() == compose.activity.packageName }
        assertEquals("Screenshot must show the app", compose.activity.packageName, automation.rootInActiveWindow?.packageName?.toString())
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(run, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun artifacts(run: File) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "APPEARANCE_ARTIFACTS=${run.absolutePath}\n")
        })
    }

    private fun assertSurface(tag: String, expected: androidx.compose.ui.graphics.Color) {
        val image = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        // Bottom centre is padding, away from text, icons and the rounded corners.
        val actual = image[image.width / 2, image.height - 8].toArgb()
        val wanted = expected.toArgb()
        for (shift in listOf(16, 8, 0)) assertTrue("$tag actual=${actual.toUInt().toString(16)} expected=${wanted.toUInt().toString(16)}",
            kotlin.math.abs(((actual ushr shift) and 255) - ((wanted ushr shift) and 255)) <= 2)
    }

    @Test fun namedColorChoicesApplyToActualHomeSurfacesInLightAndDarkModes() {
        val graph = AppGraph.get(compose.activity)
        val original = runBlocking { graph.settings.settings.first() }
        val model = start(graph)
        val run = runFolder()
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                runBlocking { graph.settings.update { it.copy(theme = mode, grid = false) } }
                for (color in ColorTheme.entries) {
                    compose.runOnIdle { graph.workspace.panel("설정") }
                    compose.onNodeWithTag("color-theme-${color.name}").performScrollTo().performClick()
                    compose.waitUntil(5_000) { model.settings.value.colorTheme == color }
                    compose.onNodeWithTag("color-theme-${color.name}").assertIsSelected()
                    if (color == ColorTheme.LAVENDER) capture(run, "settings-${mode.name.lowercase()}")
                    compose.runOnIdle { graph.workspace.panel(null) }
                    compose.waitForIdle()
                    assertSurface("storage-phone", appColors(mode == ThemeMode.DARK, color).surfaceContainerLow)
                    capture(run, "home-${color.name.lowercase()}-${mode.name.lowercase()}")
                }
            }
        } finally {
            compose.runOnIdle { graph.workspace.panel(null); model.home() }
            runBlocking { graph.settings.save(original) }
            artifacts(run)
        }
    }

    @Test fun compactDensityPreservesThe300thFileSelectionAndViewerWithoutRelisting() {
        assertTrue(Environment.isExternalStorageManager())
        val graph = AppGraph.get(compose.activity)
        val original = runBlocking { graph.settings.settings.first() }
        val model = start(graph)
        val run = runFolder()
        val files = File(run, "files").apply { mkdir() }
        repeat(320) { File(files, "row-${it.toString().padStart(3, '0')}.txt").writeText("Appearance fixture $it") }
        val location = StorageLocation(LOCAL_SOURCE, files.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        try {
            runBlocking { graph.settings.save(original.copy(grid = false, theme = ThemeMode.LIGHT, listDensity = ListDensity.COMPACT)) }
            compose.runOnIdle { model.navigate(location) }
            compose.waitUntil(15_000) { !model.state.value.loading && model.state.value.entries.size == 320 }
            val entries = model.state.value.entries
            val first = entries.first()
            val density = compose.activity.resources.displayMetrics.density
            fun rowHeight() = compose.onNodeWithTag("entry-${first.location.path}").fetchSemanticsNode().boundsInRoot.height / density
            assertEquals(56f, rowHeight(), 1f)
            capture(run, "list-compact")
            compose.onNodeWithTag("list-density").performClick()
            compose.onNodeWithTag("list-density-NORMAL").performClick()
            compose.waitUntil(5_000) { model.settings.value.listDensity == ListDensity.NORMAL }
            compose.waitForIdle()
            assertEquals(64f, rowHeight(), 1f)
            capture(run, "list-normal")
            compose.onNodeWithTag("entry-${entries[1].location.path}").performTouchInput { longClick() }
            compose.onNodeWithTag("folder-list").performScrollToIndex(300)
            compose.waitForIdle()
            val anchor = model.currentViewportAnchor()
            val visible = entries.single { it.id == anchor.fileId }
            val reads = StorageReadMetrics.count(LOCAL_SOURCE)
            compose.onNodeWithTag("list-density").performClick()
            compose.onNodeWithTag("list-density-COMPACT").performClick()
            compose.waitUntil(5_000) { model.settings.value.listDensity == ListDensity.COMPACT }
            compose.waitForIdle()
            compose.onNodeWithTag("entry-${visible.location.path}").assertIsDisplayed()
            assertEquals(anchor.fileId, model.currentViewportAnchor().fileId)
            runBlocking { graph.settings.update { it.copy(colorTheme = ColorTheme.BLUE) } }
            compose.waitForIdle()
            assertEquals(anchor.fileId, model.currentViewportAnchor().fileId)
            compose.runOnIdle { model.open(visible) }
            compose.waitUntil(15_000) { model.state.value.preview?.document != null }
            compose.onNodeWithTag("close-preview").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("entry-${visible.location.path}").assertIsDisplayed()
            assertEquals(setOf(entries[1].id), model.state.value.selected)
            assertEquals(location, model.state.value.location)
            assertSame(entries, model.state.value.entries)
            assertEquals(reads, StorageReadMetrics.count(LOCAL_SOURCE))
            capture(run, "list-restored-blue")
            compose.onNodeWithTag("view-grid").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("entry-${visible.location.path}").assertIsDisplayed()
            capture(run, "grid-blue")
            compose.onNodeWithTag("view-list").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("entry-${visible.location.path}").assertIsDisplayed()
            assertEquals(setOf(entries[1].id), model.state.value.selected)
            assertSame(entries, model.state.value.entries)
            assertEquals(reads, StorageReadMetrics.count(LOCAL_SOURCE))
        } finally {
            compose.runOnIdle { model.home() }
            runBlocking { graph.settings.save(original); graph.dao.removeSaved("recent:${location.key}") }
            artifacts(run)
        }
    }

    @Test fun largeFontsPortraitLandscapeAndSplitKeepReadableFilesAndTouchTargets() {
        val graph = AppGraph.get(compose.activity)
        val original = runBlocking { graph.settings.settings.first() }
        val originalScale = Settings.System.getFloat(compose.activity.contentResolver, Settings.System.FONT_SCALE, 1f)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val model = start(graph)
        val run = runFolder()
        val files = File(run, "files").apply { mkdir() }
        val name = "긴 파일 이름도 두 줄로 편하게 읽을 수 있는 목록과 색상 확인 문서.txt"
        File(files, name).writeText("This run owns this file.")
        val location = StorageLocation(LOCAL_SOURCE, files.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        fun font(scale: Float) {
            automation.executeShellCommand("settings put system font_scale $scale").close()
            compose.waitUntil(15_000) { kotlin.math.abs(compose.activity.resources.configuration.fontScale - scale) < .01f }
            compose.waitForIdle()
        }
        try {
            runBlocking { graph.settings.save(original.copy(grid = false, listDensity = ListDensity.COMPACT, colorTheme = ColorTheme.LAVENDER)) }
            compose.runOnIdle { model.navigate(location) }
            compose.waitUntil(15_000) { !model.state.value.loading && model.state.value.entries.size == 1 }
            for (scale in listOf(1f, 1.3f, 2f)) {
                font(scale)
                for (rotation in listOf(android.app.UiAutomation.ROTATION_FREEZE_0, android.app.UiAutomation.ROTATION_FREEZE_90)) {
                    automation.setRotation(rotation)
                    val orientation = if (rotation == android.app.UiAutomation.ROTATION_FREEZE_0)
                        android.content.res.Configuration.ORIENTATION_PORTRAIT else android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    compose.waitUntil(15_000) { compose.activity.resources.configuration.orientation == orientation }
                    compose.waitForIdle()
                    val row = compose.onNodeWithTag("entry-${location.child(name).path}").assertIsDisplayed().assertHasClickAction()
                    assertTrue(row.fetchSemanticsNode().boundsInRoot.height / compose.activity.resources.displayMetrics.density >= 48f)
                    compose.onNodeWithText(name, useUnmergedTree = true).assertIsDisplayed()
                    compose.onNodeWithTag("explorer-menu").assertIsDisplayed().assertHasClickAction()
                    capture(run, "font-${scale}-rotation-$rotation")
                }
            }
            font(1f)
            automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
            compose.waitForIdle()
            lateinit var other: ExplorerViewModel
            compose.runOnIdle {
                other = ViewModelProvider(compose.activity).get("B", ExplorerViewModel::class.java)
                other.navigate(location); graph.workspace.split(true)
            }
            compose.waitUntil(15_000) { !other.state.value.loading && other.state.value.entries.size == 1 }
            compose.onAllNodesWithTag("folder-list").assertCountEquals(2)
            capture(run, "split-lavender")
        } finally {
            compose.runOnIdle { graph.workspace.split(false); model.home(); ViewModelProvider(compose.activity).get("B", ExplorerViewModel::class.java).home() }
            runBlocking { graph.settings.save(original); graph.dao.removeSaved("recent:${location.key}") }
            font(originalScale)
            automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
            artifacts(run)
        }
    }

    @Test fun selectedAppearanceSurvivesActivityRecreationAndFreshStoreRead() {
        val graph = AppGraph.get(compose.activity)
        val original = runBlocking { graph.settings.settings.first() }
        val model = start(graph)
        try {
            compose.runOnIdle { graph.workspace.panel("설정") }
            compose.onNodeWithTag("color-theme-ROSE").performScrollTo().performClick()
            compose.onNodeWithTag("settings-list-NORMAL").performScrollTo().performClick()
            compose.waitUntil(5_000) { model.settings.value.colorTheme == ColorTheme.ROSE && model.settings.value.listDensity == ListDensity.NORMAL }
            compose.runOnIdle { graph.workspace.panel(null) }
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            val restored = runBlocking { SettingsStore(compose.activity).settings.first() }
            assertEquals(ColorTheme.ROSE, restored.colorTheme)
            assertEquals(ListDensity.NORMAL, restored.listDensity)
            compose.runOnIdle { graph.workspace.panel("설정") }
            compose.onNodeWithTag("color-theme-ROSE").assertIsSelected()
            compose.onNodeWithTag("settings-list-NORMAL").performScrollTo().assertIsSelected()
        } finally {
            compose.runOnIdle { graph.workspace.panel(null) }
            runBlocking { graph.settings.save(original) }
        }
    }
}
