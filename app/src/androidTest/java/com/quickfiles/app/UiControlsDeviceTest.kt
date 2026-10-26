package com.quickfiles.app

import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.data.*
import com.quickfiles.app.ui.ExplorerViewModel
import com.quickfiles.app.ui.PendingTransfer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class UiControlsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsHaveSingleNamedChoiceTargetsAndAdjustableValues() {
        val graph = AppGraph.get(compose.activity)
        val original = runBlocking { graph.settings.settings.first() }
        try {
            compose.waitUntil(15_000) { graph.workspace.state.value.ready }
            compose.runOnIdle { graph.workspace.panel("설정") }
            compose.onNodeWithText(original.theme.label).assertIsSelected().assertHeightIsAtLeast(48.dp)
            val hidden = compose.onNodeWithText("숨김 파일 표시").performScrollTo()
            hidden.assertHasClickAction().assertHeightIsAtLeast(48.dp)
            hidden.performClick()
            compose.waitUntil { runBlocking { graph.settings.settings.first().showHidden } != original.showHidden }
            if (original.showHidden) hidden.assertIsOff() else hidden.assertIsOn()
            val cache = compose.onNodeWithContentDescription("미리보기 캐시 한도").performScrollTo()
            cache.performSemanticsAction(SemanticsActions.SetProgress) { it(512f) }
            compose.waitUntil { runBlocking { graph.settings.settings.first().cacheMiB } == 512 }
            cache.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "512MiB"))
            val size = compose.onNodeWithContentDescription("텍스트 글자 크기").performScrollTo()
            size.performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
            compose.waitUntil { runBlocking { graph.settings.settings.first().textSize } == 20 }
            size.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "20sp"))
            // Each checkbox/radio has a single merged text label and action, not an unnamed icon sibling.
            compose.onNodeWithText("텍스트 줄바꿈").performScrollTo().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            report("UI_CONTROLS settingsLabels=true mergedChoices=true minimum48dp=true accessibleAdjustment=true")
        } finally { compose.runOnIdle { graph.workspace.panel(null) }; runBlocking { graph.settings.save(original) } }
    }

    @Test fun sheetsSettleWithin200msAndCanBeDismissedDuringEntry() {
        val graph = AppGraph.get(compose.activity)
        compose.waitUntil(15_000) { graph.workspace.state.value.ready }
        try {
            compose.runOnIdle { graph.workspace.panel(null) }
            for (panel in listOf("폴더 전환", "목적지")) {
                compose.mainClock.autoAdvance = false
                compose.runOnUiThread {
                    if (panel == "목적지") graph.workspace.prepare(PendingTransfer(0, emptyList(), false, null))
                    graph.workspace.panel(panel)
                }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val tops = mutableListOf<Float>()
                repeat(20) {
                    compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                    tops += compose.onNodeWithTag("workspace-sheet").fetchSemanticsNode().boundsInRoot.top
                }
                val lastChange = (1 until tops.size).lastOrNull { kotlin.math.abs(tops[it] - tops[it - 1]) > .1f } ?: 0
                val elapsed = (lastChange + 1) * 16
                assertTrue("$panel geometry still moving after200ms: $tops", elapsed <= 200)
                compose.onNodeWithTag("workspace-sheet").assertExists()
                report("UI_SHEET panel=$panel lastGeometryChangeMs=$elapsed settled200ms=true")
                compose.runOnUiThread { graph.workspace.panel(null) }
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                compose.onNodeWithTag("workspace-sheet").assertDoesNotExist()
            }
            compose.runOnUiThread { graph.workspace.panel("폴더 전환") }
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.onNodeWithContentDescription("폴더 전환 닫기").performClick()
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.onNodeWithTag("workspace-sheet").assertDoesNotExist()
            report("UI_SHEET duringEntryDismiss=true")
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { graph.workspace.cancelPreparation(); graph.workspace.panel(null) }
        }
    }

    private fun report(line: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$line\n") })

    @Test fun standardMenuGeometrySettlesWithin200ms() {
        val graph = AppGraph.get(compose.activity)
        compose.waitUntil(15_000) { graph.workspace.state.value.ready }
        try {
            compose.runOnIdle { graph.workspace.activate(0); graph.workspace.split(false); graph.workspace.panel(null) }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("explorer-menu").performClick()
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            val bounds = (0 until 24).map {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                compose.onNodeWithText("설정").fetchSemanticsNode().boundsInRoot
            }
            val lastChange = (1 until bounds.size).lastOrNull { (bounds[it].topLeft - bounds[it - 1].topLeft).getDistance() > .1f || kotlin.math.abs(bounds[it].width - bounds[it - 1].width) > .1f } ?: 0
            val elapsed = (lastChange + 1) * 16
            assertTrue("Menu still moving after200ms: $bounds", elapsed <= 200)
            report("UI_MENU lastGeometryChangeMs=$elapsed settled200ms=true")
            compose.onNodeWithText("설정").performClick()
        } finally { compose.mainClock.autoAdvance = true; compose.runOnIdle { graph.workspace.panel(null) } }
    }
}
