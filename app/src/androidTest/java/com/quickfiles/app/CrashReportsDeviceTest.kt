package com.quickfiles.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.quickfiles.app.ui.ExplorerViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A crash report written by the real handler path shows up in Settings, opens in full and can be deleted there. */
class CrashReportsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recordedCrashIsListedReadableAndDeletableInSettings() {
        val graph = AppGraph.get(compose.activity)
        // The application installed the handler at start; the graph reads the same folder.
        assertNotNull(Thread.getDefaultUncaughtExceptionHandler())
        val before = graph.crashLog.reports().map { it.name }.toSet()
        val marker = "device-test-${System.nanoTime()}"
        val name = graph.crashLog.record("device-test", IllegalStateException(marker))
        try {
            val report = graph.crashLog.reports().first()
            assertEquals("newest first", name, report.name)
            compose.waitUntil(15_000) { graph.workspace.state.value.ready }
            compose.runOnIdle {
                graph.workspace.activate(0)
                graph.workspace.split(false)
                ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java).home()
                graph.workspace.panel("설정")
            }
            compose.onNodeWithTag("crash-reports").performScrollTo()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("java.lang.IllegalStateException: $marker", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("crash-report-0").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("스레드: device-test", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("crash-report-text").assertTextContains("at com.quickfiles.app.CrashReportsDeviceTest", substring = true)
            compose.onNodeWithText("공유").assertHasClickAction()
            compose.onNodeWithTag("crash-delete").performClick()
            compose.waitUntil(5_000) { graph.crashLog.reports().none { it.name == name } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().isEmpty() }
            assertEquals(before, graph.crashLog.reports().map { it.name }.toSet())
            compose.runOnIdle { graph.workspace.panel(null) }
        } finally {
            runCatching { graph.crashLog.delete(name) }
        }
    }
}
