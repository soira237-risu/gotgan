package com.quickfiles.app.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Received files: untrusted names, read-only access for the engine and viewers, and cleanup that never drops a pending save. */
class InboxTest {
    @get:Rule val temp = TemporaryFolder()
    private val batchA = "11111111-1111-1111-1111-111111111111"
    private val batchB = "22222222-2222-2222-2222-222222222222"

    @Test fun senderNamesAreSanitized() {
        assertEquals("passwd", Inbox.sanitizeName("../../etc/passwd", "x"))
        assertEquals("a.txt", Inbox.sanitizeName("C:\\temp\\a.txt", "x"))
        assertEquals("bashrc", Inbox.sanitizeName(".bashrc", "x"))
        assertEquals("새 문서.pdf", Inbox.sanitizeName("새\u0000 문서\n.pdf", "x"))
        assertEquals("tab.txt", Inbox.sanitizeName("tab\t.txt", "x"))
        assertEquals("fallback", Inbox.sanitizeName("   ", "fallback"))
        assertEquals("fallback", Inbox.sanitizeName(null, "fallback"))
        assertEquals("fallback", Inbox.sanitizeName("..", "fallback"))
        assertEquals(200, Inbox.sanitizeName("a".repeat(500), "x").length)
        assertTrue(runCatching { requireFileName(Inbox.sanitizeName("a/b\u0001c", "x")) }.isSuccess)
    }

    @Test fun missingExtensionsComeFromTheMimeTypeAndDuplicatesGetNumbers() {
        assertEquals("IMG_1.jpg", Inbox.withExtension("IMG_1", "jpg"))
        assertEquals("a.png", Inbox.withExtension("a.png", "jpg"))
        assertEquals("a", Inbox.withExtension("a", null))
        val taken = mutableSetOf<String>()
        assertEquals("사진.jpg", Inbox.uniqueName("사진.jpg", taken))
        assertEquals("사진 (2).jpg", Inbox.uniqueName("사진.JPG", taken).lowercase())
        assertEquals("사진 (3).jpg", Inbox.uniqueName("사진.jpg", taken))
        assertEquals("README", Inbox.uniqueName("README", taken))
        assertEquals("readme (2)", Inbox.uniqueName("readme", taken))
    }

    @Test fun providerReadsFromOffsetAndStaysInsideTheInbox() = runBlocking {
        val inbox = Inbox(temp.newFolder("inbox"))
        File(inbox.root, batchA).mkdirs()
        File(inbox.root, "$batchA/clip.mp4").writeBytes(ByteArray(100) { it.toByte() })
        File(inbox.root, "$batchA/.task").writeText("t")
        val location = StorageLocation(INBOX_SOURCE, "$batchA/clip.mp4")
        assertFalse(inbox.provider.capabilities.writable)
        assertEquals(listOf("clip.mp4"), inbox.provider.list(StorageLocation(INBOX_SOURCE, batchA)).map { it.name })
        val entry = inbox.provider.stat(location)
        assertEquals(100L, entry.size)
        inbox.provider.openRead(location, 40, entry.revision).use { handle ->
            assertEquals(60L, handle.length)
            assertEquals(40, handle.stream.read())
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { inbox.provider.stat(StorageLocation(LOCAL_SOURCE, "x")) } }
        try { inbox.provider.stat(StorageLocation(INBOX_SOURCE, "$batchA/missing")); fail() } catch (e: StorageException) { assertEquals(StorageError.NOT_FOUND, e.kind) }
    }

    @Test fun cleanupKeepsPendingSavesAndRemovesFinishedOrOrphanedBatches() = runBlocking {
        val inbox = Inbox(temp.newFolder("inbox"))
        val now = System.currentTimeMillis()
        fun batch(id: String, task: String?, age: Long): File = File(inbox.root, id).apply {
            mkdirs(); File(this, "a.txt").writeText("a"); task?.let { File(this, ".task").writeText(it) }; setLastModified(now - age)
        }
        val running = batch(batchA, "running", 0)
        val done = batch(batchB, "done", 0)
        val fresh = batch("33333333-3333-3333-3333-333333333333", null, 1_000)
        val stale = batch("44444444-4444-4444-4444-444444444444", null, 25L * 60 * 60 * 1000)
        val gone = batch("55555555-5555-5555-5555-555555555555", "deleted", 0)
        val states = mapOf("running" to "RUNNING", "done" to "COMPLETE")
        inbox.cleanup({ states[it] }, now = now)
        assertTrue(running.exists()); assertTrue(fresh.exists())
        assertFalse(done.exists()); assertFalse(stale.exists()); assertFalse(gone.exists())
        // After a restart nobody can still be choosing a destination for an unclaimed batch received earlier.
        inbox.cleanup({ states[it] }, processStartedAt = now, now = now)
        assertFalse(fresh.exists()); assertTrue(running.exists())
    }

    @Test fun attachAndDiscardOnlyTouchTheirOwnBatches() {
        val inbox = Inbox(temp.newFolder("inbox"))
        File(inbox.root, "not-a-batch/x").apply { parentFile!!.mkdirs(); writeText("x") }
        listOf(batchA, batchB).forEach { File(inbox.root, "$it/f.txt").apply { parentFile!!.mkdirs(); writeText("x") } }
        inbox.attach(listOf(StorageLocation(INBOX_SOURCE, "$batchA/f.txt"), StorageLocation(LOCAL_SOURCE, "$batchB/f.txt")), "task-1")
        assertEquals("task-1", File(inbox.root, "$batchA/.task").readText())
        assertFalse(File(inbox.root, "$batchB/.task").exists())
        inbox.discard(listOf(StorageLocation(INBOX_SOURCE, "$batchB/f.txt"), StorageLocation(INBOX_SOURCE, "not-a-batch/x")))
        assertFalse(File(inbox.root, batchB).exists())
        assertTrue(File(inbox.root, batchA).exists())
        assertTrue("Only well-formed batch ids are ever deleted", File(inbox.root, "not-a-batch/x").exists())
    }
}
