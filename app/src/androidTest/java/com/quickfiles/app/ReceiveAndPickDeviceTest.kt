package com.quickfiles.app

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.storage.INBOX_SOURCE
import com.quickfiles.app.storage.FileEntry
import com.quickfiles.app.storage.StorageLocation
import com.quickfiles.app.ui.ExplorerViewModel
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Other apps reaching Gotgan: share into it, open a file with it, and pick a file from it. */
class ReceiveAndPickDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "timed out" }; Thread.sleep(50) }
    }
    private fun model(scenario: ActivityScenario<MainActivity>): ExplorerViewModel {
        var model: ExplorerViewModel? = null
        scenario.onActivity { model = ViewModelProvider(it)["A", ExplorerViewModel::class.java] }
        return model!!
    }

    @Test fun sharedTextIsReceivedIntoTheInboxAndAwaitsADestination() {
        val graph = AppGraph.get(context)
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "회의 메모").putExtra(Intent.EXTRA_SUBJECT, "메모")
        ActivityScenario.launch<MainActivity>(intent).use {
            waitFor { graph.workspace.state.value.pending?.received == true }
            val pending = graph.workspace.state.value.pending!!
            assertEquals(INBOX_SOURCE, pending.sources.single().sourceId)
            assertTrue(pending.sources.single().path.endsWith("/메모.txt"))
            assertEquals("회의 메모", File(graph.repository.inbox.root, pending.sources.single().path).readText())
            // Cancelling drops the received copy.
            graph.workspace.cancelPreparation()
            waitFor { !File(graph.repository.inbox.root, pending.sources.single().path.substringBefore('/')).exists() }
        }
    }

    @Test fun openWithGotganShowsAnotherAppsFileInTheViewer() {
        val file = File(context.cacheDir, "content-v1/receive-test/note.full").apply { parentFile!!.mkdirs(); writeText("다른 앱의 텍스트") }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file, "note.txt")
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                val model = model(scenario)
                waitFor { model.state.value.preview?.let { it.entry.location.sourceId == INBOX_SOURCE && !it.loading } == true }
                assertEquals("note.txt", model.state.value.preview!!.entry.name)
                assertEquals("다른 앱의 텍스트", model.state.value.preview!!.decodedText)
            }
        } finally { file.parentFile!!.deleteRecursively() }
    }

    @Test fun pickerReturnsAReadableUriAndRefusesOtherTypes() {
        val graph = AppGraph.get(context)
        val folder = File(context.filesDir, "inbox/99999999-9999-9999-9999-999999999999").apply { mkdirs() }
        File(folder, "photo.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(folder, "doc.pdf").writeBytes(byteArrayOf(4))
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try {
            ActivityScenario.launchActivityForResult<MainActivity>(intent).use { scenario ->
                val model = model(scenario)
                val pdf = FileEntry(StorageLocation(INBOX_SOURCE, "${folder.name}/doc.pdf"), "doc.pdf", false, 1)
                scenario.onActivity { model.open(pdf) }
                waitFor { model.state.value.notice != null }
                val photo = FileEntry(StorageLocation(INBOX_SOURCE, "${folder.name}/photo.jpg"), "photo.jpg", false, 3)
                scenario.onActivity { model.notice(null); model.open(photo) }
                val result = scenario.result
                assertEquals(Activity.RESULT_OK, result.resultCode)
                val returned = result.resultData.data!!
                assertEquals("image/jpeg", context.contentResolver.getType(returned))
                assertArrayEquals(byteArrayOf(1, 2, 3), context.contentResolver.openInputStream(returned)!!.use { it.readBytes() })
            }
        } finally { folder.deleteRecursively(); synchronized(graph.pickedLeases) { graph.pickedLeases.forEach { it.close() }; graph.pickedLeases.clear() } }
    }
}
