package com.quickfiles.app.operations

import com.quickfiles.app.storage.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.util.concurrent.CopyOnWriteArrayList

class ResumeTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun pausedDownloadVerifiesCheckpointThenUsesRangeAndDoesNotRestartFromZero() = runBlocking {
        val root = temp.newFolder("phone")
        val local = LocalStorageProvider(root) { true }
        val data = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
        val offsets = CopyOnWriteArrayList<Long>()
        val remote = object : StorageProvider {
            override suspend fun list(location: StorageLocation) = emptyList<FileEntry>()
            override suspend fun stat(location: StorageLocation) = FileEntry(location, "big.bin", false, data.size.toLong(), 1, "\"stable\"")
            override suspend fun openRead(location: StorageLocation) = openRead(location, 0, null)
            override suspend fun openRead(location: StorageLocation, offset: Long, expected: Revision?): ReadHandle {
                offsets += offset
                return ReadHandle(object : FilterInputStream(ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int { Thread.sleep(30); return `in`.read(b, off, len) }
                }, data.size - offset)
            }
        }
        val journal = FakeJournal()
        val engine = OperationEngine(journal, { if (it == "phone") local else remote }, recordError = { _, _, _ -> })
        val task = engine.enqueue("COPY", listOf(StorageLocation("nas", "big.bin")), StorageLocation("phone"))
        val running = launch(Dispatchers.Default) { engine.drain() }
        delay(950)
        engine.pause(task)
        withTimeout(10000) { running.join() }
        assertEquals("PAUSED", journal.task(task)!!.state)
        assertTrue(journal.items(task).single { it.stage.isNotEmpty() }.bytes > 0)
        assertFalse(File(root, "big.bin").exists())
        // A fresh engine represents process restart; only the Room-compatible journal and disk survive.
        val restarted = OperationEngine(journal, { if (it == "phone") local else remote }, recordError = { _, _, _ -> })
        restarted.resume(task); restarted.drain()
        assertEquals("COMPLETE", journal.task(task)!!.state)
        assertEquals(2, offsets.size); assertEquals(0L, offsets[0]); assertTrue(offsets[1] > 0)
        assertArrayEquals(data, File(root, "big.bin").readBytes())
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
    }
}
