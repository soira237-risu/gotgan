package com.quickfiles.app

import android.provider.Settings
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.data.*
import com.quickfiles.app.ui.ExplorerViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class UiAppearanceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun lightDarkAndLargeFontsKeepReadableTitleAndAccessibleTopTools() {
        val graph = AppGraph.get(compose.activity)
        val settings = runBlocking { graph.settings.settings.first() }
        val originalScale = Settings.System.getFloat(compose.activity.contentResolver, Settings.System.FONT_SCALE, 1f)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun font(scale: Float) { automation.executeShellCommand("settings put system font_scale $scale").close(); compose.waitUntil(15_000) { kotlin.math.abs(compose.activity.resources.configuration.fontScale - scale) < .01f }; compose.waitForIdle() }
        try {
            compose.waitUntil(15_000) { graph.workspace.state.value.ready }
            compose.runOnIdle { graph.workspace.activate(0); graph.workspace.split(false); ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java).home() }
            for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (scale in listOf(1f, 1.3f, 2f)) {
                font(scale)
                runBlocking { graph.settings.save(settings.copy(theme = theme, grid = false)) }
                compose.waitForIdle()
                val title = compose.onNodeWithText("곳간", substring = false)
                title.assertIsDisplayed()
                val pixels = title.captureToImage().toPixelMap()
                var minimum = 1f; var maximum = 0f
                for (x in 0 until pixels.width step 2) for (y in 0 until pixels.height step 2) { val luminance = pixels[x, y].luminance(); minimum = minOf(minimum, luminance); maximum = maxOf(maximum, luminance) }
                assertTrue("Title must contrast with its background: theme=$theme font=$scale min=$minimum max=$maximum", maximum - minimum > .5f)
                compose.onNodeWithContentDescription("폴더 전환").assertIsDisplayed().assertHasClickAction()
                compose.onNodeWithContentDescription("더보기").assertIsDisplayed().assertHasClickAction()
                compose.onNodeWithTag("view-grid").performClick()
                compose.onNodeWithTag("storage-phone").assertIsDisplayed().assertHasClickAction()
                compose.onNodeWithTag("view-list").performClick()
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "UI_APPEARANCE theme=$theme font=$scale titleContrast=true topActions=true\n") })
            }
        } finally { runBlocking { graph.settings.save(settings) }; font(originalScale) }
    }
}
