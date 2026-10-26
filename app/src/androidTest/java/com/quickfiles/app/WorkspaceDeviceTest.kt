package com.quickfiles.app

import android.os.Environment
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.ExplorerViewModel
import com.quickfiles.app.operations.ConflictPolicy
import androidx.compose.ui.semantics.SemanticsActions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class WorkspaceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var first: ExplorerViewModel
    private lateinit var second: ExplorerViewModel
    @Test fun movePreparationCancelDoesNotModifyFiles() {
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val run = File(root, "validation-${UUID.randomUUID()}").canonicalFile
        require(run.toPath().startsWith(root.toPath()) && run != root && run.mkdirs())
        val a = File(run, "a").also { check(it.mkdir()) }
        val b = File(run, "b").also { check(it.mkdir()) }
        val original = "cancel must preserve the original".toByteArray()
        val file = File(a, "selected.txt").also { it.writeBytes(original) }
        fun location(value: File) = StorageLocation(LOCAL_SOURCE, value.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        val graph = AppGraph.get(compose.activity)
        val settings = runBlocking { graph.settings.settings.first() }
        val workspace = runBlocking { graph.workspace.state.first { it.ready } }
        try {
            runBlocking { graph.settings.save(settings.copy(grid = false)) }
            compose.runOnIdle {
                first = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java)
                second = ViewModelProvider(compose.activity).get("B", ExplorerViewModel::class.java)
                first.home(); second.home(); graph.workspace.cancelPreparation(); graph.workspace.activate(0); graph.workspace.split(false)
            }
            compose.waitUntil(15_000) { graph.workspace.state.value.ready && first.state.value.profilesReady && second.state.value.profilesReady }
            compose.runOnIdle { first.navigate(location(a)); second.navigate(location(b)) }
            compose.waitUntil(15_000) { !first.state.value.loading && !second.state.value.loading && first.state.value.entries.size == 1 }
            val entries = first.state.value.entries
            val taskIds = runBlocking { graph.dao.tasks().first().map { it.id }.toSet() }
            compose.onNodeWithTag("entry-${location(file).path}").performSemanticsAction(SemanticsActions.OnLongClick) { assertTrue(it()) }
            val selected = setOf(entries.single().id)
            assertEquals(selected, first.state.value.selected)
            compose.onNodeWithText("이동", substring = false).performClick()
            val pending = checkNotNull(graph.workspace.state.value.pending)
            assertTrue(pending.move)
            assertEquals(listOf(location(file)), pending.sources)
            assertEquals(location(b), pending.destination)
            assertEquals(ConflictPolicy.SKIP, pending.policy)
            compose.onNodeWithText("여기로 이동", substring = false).assertExists()
            compose.onNodeWithText("준비 취소", substring = false).performClick()
            compose.waitForIdle()
            assertNull(graph.workspace.state.value.pending)
            assertNull(graph.workspace.state.value.panel)
            assertEquals(taskIds, runBlocking { graph.dao.tasks().first().map { it.id }.toSet() })
            assertArrayEquals(original, file.readBytes())
            assertFalse(File(b, "selected.txt").exists())
            assertSame(entries, first.state.value.entries)
            assertEquals(selected, first.state.value.selected)
        } finally {
            compose.runOnIdle { graph.workspace.cancelPreparation(); if (::first.isInitialized) first.home(); if (::second.isInitialized) second.home(); graph.workspace.activate(workspace.active); graph.workspace.split(workspace.split) }
            runBlocking { graph.settings.save(settings); graph.dao.removeSaved("recent:${location(a).key}"); graph.dao.removeSaved("recent:${location(b).key}") }
            require(run.canonicalFile.toPath().startsWith(root.toPath()) && run != root); run.deleteRecursively()
        }
    }
    @Test fun twoTabsCopyPasteSearchFavoritesAndGrid() {
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val run = File(root, "validation-${UUID.randomUUID()}").canonicalFile
        require(run.toPath().startsWith(root.toPath()) && run != root && run.mkdirs())
        val a = File(run, "a").also { check(it.mkdir()) }; val b = File(run, "b").also { check(it.mkdir()) }
        File(a, "selected.txt").writeText("copied through the actual UI")
        File(a, "other.txt").writeText("keep original")
        File(a, "nested").mkdir(); File(a, "nested/deep.txt").writeText("recursive search")
        fun location(file: File) = StorageLocation(LOCAL_SOURCE, file.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        val graph = AppGraph.get(compose.activity)
        val settings = runBlocking { graph.settings.settings.first() }
        val workspace = runBlocking { graph.workspace.state.first { it.ready } }
        try {
            runBlocking { graph.settings.save(settings.copy(grid = false)) }
            compose.runOnIdle { first = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java); second = ViewModelProvider(compose.activity).get("B", ExplorerViewModel::class.java); first.home(); second.home(); graph.workspace.activate(0); graph.workspace.split(false) }
            compose.waitUntil(15_000) { first.state.value.profilesReady && second.state.value.profilesReady }
            compose.runOnIdle { first.navigate(location(a)); second.navigate(location(b)) }
            compose.waitUntil(15_000) { !first.state.value.loading && !second.state.value.loading }
            compose.onNodeWithTag("folder-switch").performClick(); compose.onNodeWithTag("workspace-location-0").performClick()
            compose.onNodeWithTag("entry-${location(a).child("selected.txt").path}").performTouchInput { longClick() }
            compose.onNodeWithText("복사", substring = false).performClick()
            val unchanged = first.state.value.entries
            compose.onNodeWithText("여기에 복사").assertExists()
            compose.onNodeWithText("건너뛰기 (기본)").assertExists()
            compose.onNodeWithText("목적지 변경").performClick()
            compose.onAllNodesWithTag("view-grid").onLast().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("이 폴더 선택").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
            }
            compose.onNodeWithText("이 폴더 선택").performClick()
            compose.onNodeWithText("접기").performClick()
            compose.activityRule.scenario.recreate(); compose.waitForIdle()
            assertNotNull(graph.workspace.state.value.pending)
            assertTrue(first.state.value.selected.isNotEmpty())
            assertSame(unchanged, first.state.value.entries)
            compose.onNodeWithText("1개 복사 준비 · 목적지 확인").performClick()
            compose.onNodeWithText("여기에 복사").performClick()
            compose.waitUntil(30_000) { second.state.value.entries.any { it.name == "selected.txt" } }
            assertNull("Successful queueing must not interrupt with a dialog", first.state.value.notice)
            assertEquals("copied through the actual UI", File(b, "selected.txt").readText())
            assertSame(unchanged, first.state.value.entries)
            compose.onNodeWithTag("folder-switch").performClick(); compose.onNodeWithTag("workspace-location-0").performClick()
            compose.onNodeWithContentDescription("검색").performClick()
            compose.onNodeWithTag("folder-search").performTextReplacement("deep")
            compose.onNodeWithText("검색 결과가 없습니다.").assertExists()
            compose.onNodeWithText("하위 폴더 포함 검색").performClick()
            compose.waitUntil(15_000) { !first.state.value.searching && first.state.value.searchResults?.any { it.name == "deep.txt" } == true }
            compose.onNodeWithText("deep.txt").assertIsDisplayed()
            compose.onNode(hasSetTextAction() and hasText("deep")).performTextReplacement("other")
            compose.onNodeWithText("other.txt").assertIsDisplayed()
            compose.onNode(hasSetTextAction() and hasText("other")).performTextClearance()
            compose.onNodeWithTag("explorer-menu").performClick(); compose.onNodeWithText("즐겨찾기 추가/해제").performClick()
            compose.waitUntil { first.places.value.any { it.key == "favorite:${location(a).key}" } }
            runBlocking { graph.settings.save(settings.copy(grid = true)) }
            compose.waitUntil { first.settings.value.grid }
            compose.onNodeWithTag("folder-grid").assertExists()
            runBlocking { graph.settings.save(settings.copy(grid = false)) }
            compose.waitUntil { !first.settings.value.grid }
            compose.onNodeWithTag("folder-list").assertExists()
            compose.onNodeWithTag("explorer-menu").performClick(); compose.onNodeWithText("새 폴더").performClick()
            compose.onNode(hasSetTextAction() and hasText("이름")).performTextInput("created-in-ui")
            compose.onNodeWithText("확인").performClick()
            compose.waitUntil(30_000) { File(a, "created-in-ui").isDirectory }
            assertNull("Successful queueing must not interrupt with a dialog", first.state.value.notice)
        } finally {
            compose.runOnIdle { graph.workspace.cancelPreparation(); if (::first.isInitialized) first.home(); if (::second.isInitialized) second.home(); graph.workspace.activate(workspace.active); graph.workspace.split(workspace.split) }
            runBlocking { graph.settings.save(settings); graph.dao.removeSaved("favorite:${location(a).key}"); graph.dao.removeSaved("recent:${location(a).key}"); graph.dao.removeSaved("recent:${location(b).key}") }
            require(run.canonicalFile.toPath().startsWith(root.toPath()) && run != root); run.deleteRecursively()
        }
    }
}
