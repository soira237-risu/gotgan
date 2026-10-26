package com.quickfiles.app

import android.os.Environment
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.data.*
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.ExplorerViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Run seed, force-stop the app, then verify in a separate instrumentation invocation. */
class PersistenceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun tabsTrashTasksAndConnectionsSurviveProcessRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("persistence")
        Assume.assumeTrue(phase in listOf("seed", "verify"))
        val graph = AppGraph.get(compose.activity)
        runBlocking { graph.ready.await() }
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        lateinit var a: ExplorerViewModel; lateinit var b: ExplorerViewModel
        compose.runOnIdle { a = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java); b = ViewModelProvider(compose.activity).get("B", ExplorerViewModel::class.java) }
        compose.waitUntil(15_000) { a.state.value.profilesReady && b.state.value.profilesReady }
        compose.waitUntil(15_000) { graph.workspace.state.value.ready }
        if (phase == "seed") {
            assertNull(runBlocking { graph.dao.saved("validation:restart") })
            val run = File(root, "validation-${UUID.randomUUID()}").canonicalFile
            require(run.toPath().startsWith(root.toPath()) && run != root && run.mkdirs())
            val location = StorageLocation(LOCAL_SOURCE, run.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
            File(run, "a").mkdir(); File(run, "b").mkdir()
            repeat(30) { File(run, "a/row-$it.txt").writeText("fixture $it") }
            File(run, "restore.txt").writeText("persistent trash fixture")
            runBlocking {
                assertTrue(graph.dao.tasks().first().none { it.state in listOf("QUEUED", "RUNNING") })
                val task = graph.operations.enqueue("TRASH", listOf(location.child("restore.txt")))
                graph.operations.drain(); assertEquals("COMPLETE", graph.dao.task(task)!!.state)
                val trash = graph.dao.trashItems().first().single { it.original == location.child("restore.txt").path }
                val data = JSONObject().put("location", location.encode()).put("task", task).put("trash", trash.id)
                    .put("profiles", JSONArray(a.state.value.profiles.map { it.id }).toString())
                    .put("active", graph.workspace.state.value.active).put("split", graph.workspace.state.value.split)
                graph.dao.save(SavedValue("validation:restart", data.toString()))
            }
            compose.runOnIdle { a.navigate(location.child("a")); b.navigate(location.child("b")) }
            compose.waitUntil(15_000) { !a.state.value.loading && !b.state.value.loading }
            compose.runOnIdle {
                graph.workspace.activate(1); graph.workspace.split(true)
                a.scroll(12, 7); a.select(a.state.value.entries[12])
                graph.workspace.prepare(com.quickfiles.app.ui.PendingTransfer(0, listOf(a.state.value.entries[12].location), false, location.child("b")))
                graph.workspace.panel(null)
            }
            compose.waitUntil(10_000) { runBlocking { graph.dao.saved("tab:A")?.value?.let { JSONObject(it).optInt("index") == 12 } == true } }
            compose.waitUntil(10_000) { runBlocking { graph.dao.saved("workspace:ui")?.value?.let { JSONObject(it).optInt("active") == 1 && JSONObject(it).optBoolean("split") } == true } }
            compose.activityRule.scenario.recreate(); compose.waitForIdle()
            assertNotNull(graph.workspace.state.value.pending)
            assertTrue(a.state.value.selected.isNotEmpty())
        } else {
            val data = runBlocking { JSONObject(requireNotNull(graph.dao.saved("validation:restart")).value) }
            val location = decodeLocation(data.getString("location"))
            val run = File(Environment.getExternalStorageDirectory(), location.path).canonicalFile
            require(run.toPath().startsWith(root.toPath()) && run != root && run.name.startsWith("validation-"))
            compose.waitUntil(15_000) { !a.state.value.loading && a.state.value.location == location.child("a") && b.state.value.location == location.child("b") }
            assertEquals(12, a.state.value.index); assertEquals(7, a.state.value.offset)
            assertEquals(1, graph.workspace.state.value.active); assertTrue(graph.workspace.state.value.split)
            assertNull(graph.workspace.state.value.pending); assertNull(graph.workspace.state.value.panel)
            assertTrue(a.state.value.selected.isEmpty())
            assertEquals(data.getString("profiles"), JSONArray(a.state.value.profiles.map { it.id }).toString())
            runBlocking {
                assertEquals("COMPLETE", graph.dao.task(data.getString("task"))!!.state)
                assertEquals("TRASHED", graph.dao.trash(data.getString("trash"))!!.state)
                val restore = graph.operations.enqueue("RESTORE", extra = JSONObject().put("trashIds", JSONArray(listOf(data.getString("trash")))))
                graph.operations.drain(); assertEquals("COMPLETE", graph.dao.task(restore)!!.state)
                assertEquals("persistent trash fixture", File(run, "restore.txt").readText())
            }
            compose.runOnIdle { a.home(); b.home(); graph.workspace.activate(data.getInt("active")); graph.workspace.split(data.getBoolean("split")) }
            runBlocking { graph.dao.removeSaved("validation:restart"); graph.dao.removeSaved("recent:${location.child("a").key}"); graph.dao.removeSaved("recent:${location.child("b").key}") }
            run.deleteRecursively()
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "PERSISTENCE phase=$phase passed=true\n") })
    }
}
