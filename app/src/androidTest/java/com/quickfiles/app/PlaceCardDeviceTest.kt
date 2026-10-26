package com.quickfiles.app

import android.os.Environment
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.data.GridDensity
import com.quickfiles.app.storage.ConnectionProfile
import com.quickfiles.app.ui.*
import com.quickfiles.app.ui.theme.GotganTheme
import org.junit.*
import org.junit.Assert.*
import java.io.File

class PlaceCardDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mixedNamesStorageMenuAndAddShareSizeAcrossLayoutsAndFonts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val evidence = File(root, "validation-place-cards-${java.util.UUID.randomUUID()}").canonicalFile
        require(evidence.toPath().startsWith(root.toPath()) && evidence != root && evidence.mkdirs())
        val profile = ConnectionProfile("fixture", "Synology NAS", "https://example.invalid", "", "")
        val places = listOf(
            PlaceItem("phone", "휴대폰 저장소", "다운로드 · 사진 · 문서", null),
            PlaceItem("nas", "Synology NAS", "WebDAV", null, profile),
            PlaceItem("add", "저장소 추가", "WebDAV 연결", null),
            PlaceItem("short", "Movies", "휴대폰 저장소 / Movies", null),
            PlaceItem("long", "Hyundai Built-in Cam", "휴대폰 저장소 / Movies", null),
            PlaceItem("photo", "Photos", "Synology NAS / Photos", null),
        )
        var dark by mutableStateOf(false)
        var scale by mutableFloatStateOf(1f)
        var grid by mutableStateOf(true)
        var density by mutableStateOf(GridDensity.NORMAL)
        var opened = ""
        var detailed = false
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                GotganTheme(darkOverride = dark) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                            val columns = if (grid) gridColumns(maxWidth.value - 16, scale, density) else 1
                            LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().testTag("cards"),
                                contentPadding = PaddingValues(if (grid) 8.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(places, key = { it.id }) { place -> PlaceCell(place, grid, { opened = place.id }, { detailed = true }) }
                            }
                        }
                    }
                }
            }
        } }
        for (night in listOf(false, true)) for (font in listOf(1f, 1.3f, 2f)) for (layout in listOf("list", "LARGE", "NORMAL", "SMALL")) {
            compose.runOnIdle { dark = night; scale = font; grid = layout != "list"; if (grid) density = GridDensity.valueOf(layout) }
            compose.waitForIdle()
            val heights = mutableListOf<Float>()
            val widths = mutableListOf<Float>()
            places.forEach { place ->
                val tag = when (place.id) { "phone" -> "storage-phone"; "add" -> "storage-add"; else -> "place-${place.id}" }
                compose.onNodeWithTag("cards").performScrollToNode(hasTestTag(tag))
                val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
                val bounds = node.fetchSemanticsNode().boundsInRoot
                heights += bounds.height; widths += bounds.width
            }
            assertTrue("Unequal height: $night $font $layout $heights", heights.max() - heights.min() <= 1f)
            assertTrue("Unequal width: $night $font $layout $widths", widths.max() - widths.min() <= 1f)
            compose.onNodeWithTag("cards").performScrollToNode(hasTestTag("storage-add"))
            compose.onNodeWithTag("storage-add").performClick()
            assertEquals("add", opened)
            compose.onNodeWithTag("cards").performScrollToNode(hasTestTag("place-nas"))
            compose.onNodeWithContentDescription("Synology NAS 연결 상세").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            assertTrue(detailed)
            compose.runOnIdle { detailed = false }
            if (layout == "NORMAL" && font != 1.3f) {
                compose.onNodeWithTag("cards").performScrollToIndex(0)
                compose.waitForIdle()
                android.os.SystemClock.sleep(350) // Let the platform ripple finish before evidence capture.
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File(evidence, "cards-${if (night) "dark" else "light"}-$font.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
            }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "PLACE_CARDS dark=$night font=$font layout=$layout equalSize=true addAndDetails=true\n") })
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "PLACE_CARDS_EVIDENCE=${evidence.path}\n") })
    }
}
