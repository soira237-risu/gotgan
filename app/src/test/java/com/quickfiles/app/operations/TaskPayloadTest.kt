package com.quickfiles.app.operations

import com.quickfiles.app.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class TaskPayloadTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun fourMiBEditUsesSmallDatabaseRowAndPreservesOriginalInTrash() = runBlocking {
        val journal = FakeJournal(); val storage = OperationEngineTest.MemoryStorage("phone")
        storage.file("edit.txt", "original")
        val location = StorageLocation("phone", "edit.txt")
        val root = temporary.newFolder()
        val engine = OperationEngine(journal, { storage }, root, recordError = { _, _, _ -> })
        val bytes = ByteArray(4 * 1024 * 1024) { 65 }
        val task = engine.enqueueText(bytes, location, storage.stat(location).revision, false)
        assertTrue(journal.task(task)!!.payload.toByteArray().size < 4096)
        assertEquals(bytes.size.toLong(), File(root, "$task.edit").length())
        engine.drain()
        assertEquals("COMPLETE", journal.task(task)!!.state)
        assertArrayEquals(bytes, storage.nodes["edit.txt"]!!.bytes)
        assertEquals("original", storage.text(journal.trashRows.values.single().payloadPath))
        assertFalse(File(root, "$task.edit").exists())
    }
    @Test fun truncatedEditStageNeverOverwritesOriginal() = runBlocking {
        val journal = FakeJournal(); val storage = OperationEngineTest.MemoryStorage("phone"); storage.file("edit.txt", "original")
        val root = temporary.newFolder(); val location = StorageLocation("phone", "edit.txt")
        val engine = OperationEngine(journal, { storage }, root, recordError = { _, _, _ -> })
        val task = engine.enqueueText("new content".toByteArray(), location, storage.stat(location).revision, false)
        File(root, "$task.edit").writeText("partial")
        engine.drain()
        assertEquals("FAILED", journal.task(task)!!.state); assertEquals("original", storage.text("edit.txt")); assertTrue(journal.trashRows.isEmpty())
    }
    @Test fun tenThousandSelectionsSpoolManifestRatherThanOverflowingCursorWindow() = runBlocking {
        val journal = FakeJournal(); val storage = OperationEngineTest.MemoryStorage("phone"); val root = temporary.newFolder()
        val engine = OperationEngine(journal, { storage }, root, recordError = { _, _, _ -> })
        val sources = (0 until 10000).map { StorageLocation("phone", "Download/long-folder-name/sample-file-$it.txt") }
        val task = engine.enqueue("COPY", sources, StorageLocation("nas", "out"))
        assertTrue(journal.task(task)!!.payload.length < 4096)
        assertTrue(journal.task(task)!!.payload.contains("nas"))
        assertTrue(File(root, "$task.json").length() > 128 * 1024)
        assertEquals("QUEUED", journal.task(task)!!.state)
    }
}
