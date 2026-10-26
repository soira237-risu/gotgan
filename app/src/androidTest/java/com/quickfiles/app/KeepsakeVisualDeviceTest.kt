package com.quickfiles.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.data.*
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.ExplorerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class KeepsakeVisualDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launcherLayersStayInsideSafeAreaAndCaptureThemeStates() {
        val graph = AppGraph.get(compose.activity)
        val original = runBlocking { graph.settings.settings.first() }
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val run = File(root, "design-f-${UUID.randomUUID()}").canonicalFile
        require(run.toPath().startsWith(root.toPath()) && run != root && run.mkdirs())
        val fixtures = File(run, "디자인 확인").apply { mkdir() }
        listOf("문서", "사진", "음악").forEach { File(fixtures, it).mkdir() }
        File(fixtures, "회의 메모.txt").writeText("디자인 확인용으로 이 실행이 만든 문서입니다.")
        val location = StorageLocation(LOCAL_SOURCE, fixtures.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun save(name: String, bitmap: Bitmap) {
            File(run, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun screenshot(name: String) {
            compose.waitForIdle()
            SystemClock.sleep(250)
            assertEquals("A system dialog must not cover the screenshot: $name",
                compose.activity.packageName, automation.rootInActiveWindow?.packageName?.toString())
            save(name, requireNotNull(automation.takeScreenshot()))
        }
        val icon = compose.activity.applicationInfo.loadIcon(compose.activity.packageManager)
        assertTrue("Launcher must use adaptive layers", icon is AdaptiveIconDrawable)
        val adaptive = icon as AdaptiveIconDrawable
        val foreground = requireNotNull(adaptive.foreground.constantState).newDrawable().mutate()
        val layer = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        foreground.setBounds(0, 0, 108, 108)
        foreground.draw(Canvas(layer))
        var filled = 0
        for (x in 0 until 108) for (y in 0 until 108) if (Color.alpha(layer.getPixel(x, y)) > 0) {
            filled++
            assertTrue("Foreground outside 66dp safe area: $x,$y", x in 21..86 && y in 21..86)
        }
        assertTrue("Foreground must contain a visible symbol", filled > 1000)
        layer.recycle()
        fun render(name: String, drawable: android.graphics.drawable.Drawable) {
            val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 432, 432)
            drawable.draw(Canvas(bitmap))
            save(name, bitmap)
        }
        render("launcher-adaptive", adaptive)
        if (Build.VERSION.SDK_INT >= 33) {
            val mono = requireNotNull(adaptive.monochrome).mutate()
            mono.setTint(Color.rgb(101, 59, 43))
            render("launcher-themed", AdaptiveIconDrawable(ColorDrawable(Color.rgb(246, 221, 205)), mono))
        }
        lateinit var model: ExplorerViewModel
        try {
            compose.waitUntil(15_000) { graph.workspace.state.value.ready }
            compose.runOnIdle {
                graph.workspace.activate(0); graph.workspace.split(false); graph.workspace.panel(null)
                model = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java)
                model.home()
            }
            compose.waitUntil(15_000) { model.state.value.profilesReady }
            for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                runBlocking { graph.settings.save(original.copy(theme = theme, grid = false)) }
                compose.runOnIdle { model.home() }
                compose.onNodeWithTag("storage-phone").assertIsDisplayed()
                screenshot("home-${theme.name.lowercase()}")
                compose.runOnIdle { model.navigate(location) }
                compose.waitUntil(15_000) { !model.state.value.loading && model.state.value.entries.size == 4 }
                screenshot("list-${theme.name.lowercase()}")
                val entry = model.state.value.entries.first()
                compose.onNodeWithTag("entry-${entry.location.path}").performTouchInput { longClick() }
                assertTrue(entry.id in model.state.value.selected)
                screenshot("selection-${theme.name.lowercase()}")
                compose.runOnIdle { model.clearSelection() }
                compose.onNodeWithTag("view-grid").performClick()
                screenshot("grid-${theme.name.lowercase()}")
                compose.runOnIdle { model.navigate(location.child("문서")) }
                compose.waitUntil(15_000) { !model.state.value.loading && model.state.value.entries.isEmpty() }
                compose.onNodeWithText("빈 폴더입니다.").assertIsDisplayed()
                screenshot("empty-${theme.name.lowercase()}")
                compose.runOnIdle { graph.workspace.panel("설정") }
                screenshot("settings-${theme.name.lowercase()}")
                compose.runOnIdle { graph.workspace.panel(null) }
            }
        } finally {
            compose.runOnIdle { graph.workspace.panel(null) }
            runBlocking {
                graph.settings.save(original)
                graph.dao.removeSaved("recent:${location.key}")
                graph.dao.removeSaved("recent:${location.child("문서").key}")
            }
        }
        // Keep this run's fixtures/screenshots for bounded adb pull and visual review.
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "KEEPSAKE_ARTIFACTS=${run.absolutePath}\n")
        })
    }
}
