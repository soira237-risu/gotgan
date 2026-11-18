package com.quickfiles.app.operations

import com.quickfiles.app.storage.*
import com.quickfiles.app.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class OperationEngineTest {
    private val journal = FakeJournal()
    private val phone = MemoryStorage("phone")
    private val nas = MemoryStorage("nas")
    private fun engine() = OperationEngine(journal, { if (it == "phone") phone else nas }, recordError = { _, _, _ -> })
    private fun source() = StorageLocation("phone", "input.txt")
    private fun destination() = StorageLocation("nas", "out")
    private fun seed() { phone.file("input.txt", "verified original"); nas.folder("out") }

    @Test fun crossStorageMoveVerifiesThenTrashesSourceAndRestores() = runBlocking {
        seed(); val engine = engine()
        val task = engine.enqueue("MOVE", listOf(source()), destination())
        engine.drain()
        assertEquals("COMPLETE", journal.task(task)!!.state)
        assertEquals("verified original", nas.text("out/input.txt"))
        assertFalse(phone.nodes.containsKey("input.txt"))
        val trash = journal.trashRows.values.single()
        assertEquals("TRASHED", trash.state)
        assertEquals("verified original", phone.text(trash.payloadPath))
        engine.enqueue("RESTORE", extra = JSONObject().put("trashIds", JSONArray(listOf(trash.id))))
        engine.drain()
        assertEquals("verified original", phone.text("input.txt"))
        assertEquals("RESTORED", journal.trash(trash.id)!!.state)
    }
    @Test fun batchRenameGivesEachSourceItsOwnNameAndNewFileIsEmpty() = runBlocking {
        phone.file("a.txt", "first"); phone.file("b.txt", "second"); phone.file("taken.txt", "keep")
        val engine = engine()
        val names = JSONObject().put(StorageLocation("phone", "a.txt").encode(), "Trip 1.txt")
            .put(StorageLocation("phone", "b.txt").encode(), "taken.txt")
        val rename = engine.enqueue("RENAME", listOf(StorageLocation("phone", "a.txt"), StorageLocation("phone", "b.txt")), StorageLocation("phone"), extra = JSONObject().put("names", names))
        engine.drain()
        assertEquals("first", phone.text("Trip 1.txt"))
        // A name already in use is skipped, never overwritten.
        assertEquals("keep", phone.text("taken.txt")); assertEquals("second", phone.text("b.txt"))
        assertEquals("PARTIAL", journal.task(rename)!!.state)
        val created = engine.enqueue("NEWFILE", emptyList(), StorageLocation("phone", "empty.md"))
        engine.drain()
        assertEquals("COMPLETE", journal.task(created)!!.state)
        assertEquals("", phone.text("empty.md"))
        val again = engine.enqueue("NEWFILE", emptyList(), StorageLocation("phone", "taken.txt"))
        engine.drain()
        assertEquals("FAILED", journal.task(again)!!.state); assertEquals("keep", phone.text("taken.txt"))
    }
    @Test fun corruptionAndNoSpaceNeverRemoveSource() = runBlocking {
        for (error in listOf("corrupt", "space")) {
            seed(); nas.corrupt = error == "corrupt"; nas.noSpace = error == "space"
            val engine = engine(); val task = engine.enqueue("MOVE", listOf(source()), destination())
            engine.drain()
            assertEquals("FAILED", journal.task(task)!!.state)
            assertEquals("verified original", phone.text("input.txt"))
            assertFalse(nas.nodes.containsKey("out/input.txt"))
            assertTrue(journal.trashRows.isEmpty())
        }
    }
    @Test fun skipIsDefaultAndExplicitReplacePreservesPreviousFile() = runBlocking {
        seed(); nas.file("out/input.txt", "old")
        val engine = engine()
        val skip = engine.enqueue("COPY", listOf(source()), destination())
        engine.drain(); assertEquals("PARTIAL", journal.task(skip)!!.state); assertEquals("old", nas.text("out/input.txt"))
        val replace = engine.enqueue("COPY", listOf(source()), destination(), ConflictPolicy.REPLACE)
        engine.drain(); assertEquals("COMPLETE", journal.task(replace)!!.state)
        assertEquals("verified original", nas.text("out/input.txt"))
        val backup = journal.trashRows.values.single(); assertEquals("old", nas.text(backup.payloadPath))
    }
    @Test fun requeueDoesNotUploadCompletedItemsAgain() = runBlocking {
        seed(); val engine = engine(); val task = engine.enqueue("COPY", listOf(source()), destination())
        engine.drain(); val puts = nas.puts
        journal.task(journal.task(task)!!.copy(state = "QUEUED"))
        engine().drain()
        assertEquals(puts, nas.puts); assertEquals("COMPLETE", journal.task(task)!!.state)
    }
    @Test fun interruptedCommitIsReconciledByHashBeforeSourceTrash() = runBlocking {
        seed(); nas.failAfterMove = true
        val task = engine().enqueue("MOVE", listOf(source()), destination())
        engine().drain()
        assertEquals("FAILED", journal.task(task)!!.state)
        assertEquals("verified original", phone.text("input.txt"))
        val puts = nas.puts
        nas.failAfterMove = false; engine().resume(task); engine().drain()
        assertEquals("COMPLETE", journal.task(task)!!.state)
        assertEquals(puts, nas.puts); assertFalse(phone.nodes.containsKey("input.txt"))
    }
    @Test fun failedTrashKeepsOriginalAndReportsFailure() = runBlocking {
        seed(); phone.denyMove = true
        val task = engine().enqueue("TRASH", listOf(source()))
        engine().drain()
        assertEquals("FAILED", journal.task(task)!!.state)
        assertEquals("verified original", phone.text("input.txt"))
        assertTrue(journal.trashRows.values.none { it.state == "TRASHED" })
    }
    @Test fun changedSourceOnResumeIsNeverRemoved() = runBlocking {
        seed(); nas.noSpace = true
        val task = engine().enqueue("MOVE", listOf(source()), destination()); engine().drain()
        phone.file("input.txt", "new external content")
        nas.noSpace = false; engine().resume(task); engine().drain()
        assertEquals("FAILED", journal.task(task)!!.state)
        assertEquals("new external content", phone.text("input.txt"))
    }
    @Test fun restoreConflictDoesNotOverwriteAndPurgeRequiresOwnershipMarker() = runBlocking {
        seed(); engine().enqueue("TRASH", listOf(source())); engine().drain()
        val trash = journal.trashRows.values.single()
        phone.file("input.txt", "external")
        val restore = engine().enqueue("RESTORE", extra = JSONObject().put("trashIds", JSONArray(listOf(trash.id))))
        engine().drain(); assertEquals("FAILED", journal.task(restore)!!.state); assertEquals("external", phone.text("input.txt"))
        phone.file(trash.directory + "/owner.json", "{}")
        val purge = engine().enqueue("PURGE", extra = JSONObject().put("trashIds", JSONArray(listOf(trash.id))))
        engine().drain(); assertEquals("FAILED", journal.task(purge)!!.state)
        assertEquals("verified original", phone.text(trash.payloadPath))
    }
    @Test fun mergeCopiesUniqueFilesButPreservesCollidingFilesAndMoveSource() = runBlocking {
        phone.folder("tree"); phone.file("tree/a.txt", "a"); phone.file("tree/b.txt", "b")
        nas.folder("out"); nas.folder("out/tree"); nas.file("out/tree/a.txt", "existing")
        val task = engine().enqueue("MOVE", listOf(StorageLocation("phone", "tree")), destination(), ConflictPolicy.MERGE)
        engine().drain()
        assertEquals("PARTIAL", journal.task(task)!!.state)
        assertEquals("existing", nas.text("out/tree/a.txt")); assertEquals("b", nas.text("out/tree/b.txt"))
        assertEquals("a", phone.text("tree/a.txt")); assertEquals("b", phone.text("tree/b.txt"))
    }
    @Test fun nativeMoveDoesNotUploadOrDeleteAndDescendantsAreRejected() = runBlocking {
        seed(); phone.folder("out")
        val task = engine().enqueue("MOVE", listOf(source()), StorageLocation("phone", "out")); engine().drain()
        assertEquals("COMPLETE", journal.task(task)!!.state); assertEquals(0, phone.puts); assertEquals(0, phone.deletes)
        assertEquals("verified original", phone.text("out/input.txt"))
        assertThrows(IllegalArgumentException::class.java) { OperationEngine.requireDestination(StorageLocation("phone", "out"), StorageLocation("phone", "out/sub")) }
        Unit
    }

    @Test fun conflictingStageIsNeverDeletedOnRetry() = runBlocking {
        seed()
        val colliding = object : WritableStorageProvider by nas {
            override suspend fun put(location: StorageLocation, length: Long?, input: () -> InputStream, expected: Revision?, createOnly: Boolean, progress: (Long) -> Unit) {
                nas.file(location.path, "foreign file")
                throw StorageException(StorageError.CONFLICT, "exists")
            }
        }
        val engine = OperationEngine(journal, { if (it == "phone") phone else colliding }, recordError = { _, _, _ -> })
        val task = engine.enqueue("COPY", listOf(source()), destination()); engine.drain()
        val stage = decodeLocation(journal.items(task).single { it.stage.isNotEmpty() }.stage)
        engine.resume(task); engine.drain()
        assertEquals("FAILED", journal.task(task)!!.state)
        assertEquals("foreign file", nas.text(stage.path)); assertEquals(0, nas.deletes)
    }
    @Test fun resumedCommittedMoveRehashesSourceEvenIfMetadataWasPreserved() = runBlocking {
        seed(); nas.failAfterMove = true
        val task = engine().enqueue("MOVE", listOf(source()), destination()); engine().drain()
        val item = journal.items(task).single { it.phase == "COMMITTING" }
        journal.item(item.copy(phase = "COMMITTED"))
        val old = phone.nodes.getValue("input.txt")
        phone.nodes["input.txt"] = old.copy(bytes = "different source!".toByteArray())
        nas.failAfterMove = false; engine().resume(task); engine().drain()
        assertEquals("FAILED", journal.task(task)!!.state)
        assertEquals("different source!", phone.text("input.txt"))
    }

    @Test fun folderChangedDuringFinalVerificationIsNotTrashed() = runBlocking {
        for (removeChild in listOf(false, true)) {
            val journal = FakeJournal(); val phone = MemoryStorage("phone"); val nas = MemoryStorage("nas")
            phone.folder("tree"); phone.file("tree/a.txt", "a"); nas.folder("out")
            var lists = 0
            val changing = object : WritableStorageProvider by phone {
                override suspend fun list(location: StorageLocation): List<FileEntry> {
                    if (location.path == "tree" && ++lists == 3) {
                        if (removeChild) phone.nodes.remove("tree/a.txt") else phone.folder("tree")
                    }
                    return phone.list(location)
                }
            }
            val engine = OperationEngine(journal, { if (it == "phone") changing else nas }, recordError = { _, _, _ -> })
            val task = engine.enqueue("MOVE", listOf(StorageLocation("phone", "tree")), StorageLocation("nas", "out")); engine.drain()
            assertEquals("FAILED", journal.task(task)!!.state)
            assertTrue(phone.nodes.containsKey("tree")); assertTrue(journal.trashRows.isEmpty())
            assertEquals("a", nas.text("out/tree/a.txt"))
        }
    }

    @Test fun copyIntoItsOwnFolderMakesACopyOnlyWhenAskedForANewName() = runBlocking {
        phone.file("docs/report.txt", "original"); phone.folder("docs")
        val engine = engine()
        val copy = engine.enqueue("COPY", listOf(StorageLocation("phone", "docs/report.txt")), StorageLocation("phone", "docs"), ConflictPolicy.RENAME)
        engine.drain()
        assertEquals("COMPLETE", journal.task(copy)!!.state)
        assertEquals("original", phone.text("docs/report (1).txt")); assertEquals("original", phone.text("docs/report.txt"))
        // Any other choice says why nothing happened instead of reporting a silent skip.
        val skip = engine.enqueue("COPY", listOf(StorageLocation("phone", "docs/report.txt")), StorageLocation("phone", "docs"), ConflictPolicy.SKIP)
        engine.drain()
        assertEquals("FAILED", journal.task(skip)!!.state)
        assertTrue(journal.task(skip)!!.message.contains("새 이름으로 보관"))
        assertEquals(setOf("docs", "docs/report.txt", "docs/report (1).txt"), phone.nodes.keys - "")
    }
    @Test fun renamingOnlyTheLetterCaseWorksOnCaseInsensitiveStorage() = runBlocking {
        phone.ignoreCase = true
        phone.file("photo.JPG", "pixels"); phone.folder("trip"); phone.file("trip/a.txt", "inside")
        val engine = engine()
        val file = engine.enqueue("RENAME", listOf(StorageLocation("phone", "photo.JPG")), StorageLocation("phone"), extra = JSONObject().put("name", "photo.jpg"))
        val folder = engine.enqueue("RENAME", listOf(StorageLocation("phone", "trip")), StorageLocation("phone"), extra = JSONObject().put("name", "Trip"))
        engine.drain()
        assertEquals("COMPLETE", journal.task(file)!!.state); assertEquals("COMPLETE", journal.task(folder)!!.state)
        assertEquals(setOf("photo.jpg", "Trip", "Trip/a.txt"), phone.nodes.keys - "")
        assertEquals("pixels", phone.text("photo.jpg")); assertEquals("inside", phone.text("Trip/a.txt"))
    }
    @Test fun aDifferentFileWithTheNewCaseIsNeverReplaced() = runBlocking {
        // Storage that keeps case apart (SFTP) can hold both names; the rename is skipped, nothing overwritten.
        nas.file("a.txt", "lower"); nas.file("A.txt", "upper")
        val engine = engine()
        val task = engine.enqueue("RENAME", listOf(StorageLocation("nas", "a.txt")), StorageLocation("nas"), extra = JSONObject().put("name", "A.txt"))
        engine.drain()
        assertEquals("PARTIAL", journal.task(task)!!.state)
        assertEquals("lower", nas.text("a.txt")); assertEquals("upper", nas.text("A.txt"))
    }
    @Test fun aTaskPausedBeforeItStartsStaysPaused() = runBlocking {
        seed()
        // Paused in the moment between being picked from the queue and starting.
        val racing = object : ExplorerDao by journal {
            override suspend fun nextTask() = journal.nextTask()?.also { journal.task(it.copy(state = "PAUSED", message = "일시정지됨")) }
        }
        val engine = OperationEngine(racing, { if (it == "phone") phone else nas }, recordError = { _, _, _ -> })
        val task = engine.enqueue("COPY", listOf(source()), destination())
        engine.drain()
        assertEquals("PAUSED", journal.task(task)!!.state)
        assertFalse(nas.nodes.containsKey("out/input.txt"))
    }

    class MemoryStorage(private val id: String) : WritableStorageProvider {
        data class Node(val bytes: ByteArray? = null, val version: Long)
        val nodes = linkedMapOf("" to Node(version = 0))
        private var version = 0L
        var corrupt = false; var noSpace = false; var denyMove = false; var failAfterMove = false
        var puts = 0; var deletes = 0
        /** Like shared phone storage: names are found regardless of letter case, but kept as written. */
        var ignoreCase = false
        private fun stored(path: String) = if (!ignoreCase) path else nodes.keys.firstOrNull { it.equals(path, ignoreCase = true) } ?: path
        override val capabilities = StorageCapabilities(true, true, true)
        fun file(path: String, text: String) { nodes[path] = Node(text.toByteArray(), ++version) }
        fun folder(path: String) { nodes[path] = Node(version = ++version) }
        fun text(path: String) = nodes[path]!!.bytes!!.toString(Charsets.UTF_8)
        private fun location(path: String) = StorageLocation(id, path)
        override suspend fun stat(location: StorageLocation): FileEntry {
            val node = nodes[stored(location.path)] ?: throw StorageException(StorageError.NOT_FOUND, "missing")
            return FileEntry(location, location.path.substringAfterLast('/'), node.bytes == null, node.bytes?.size?.toLong(), node.version, "\"${node.version}\"")
        }
        override suspend fun list(location: StorageLocation) = nodes.keys.filter { it.isNotEmpty() && this.location(it).parent() == location }.map { stat(this.location(it)) }
        override suspend fun openRead(location: StorageLocation) = ReadHandle(ByteArrayInputStream(nodes[location.path]!!.bytes!!), nodes[location.path]!!.bytes!!.size.toLong())
        override suspend fun createDirectory(location: StorageLocation) { if (location.path in nodes) throw StorageException(StorageError.CONFLICT, "exists"); folder(location.path) }
        override suspend fun put(location: StorageLocation, length: Long?, input: () -> InputStream, expected: Revision?, createOnly: Boolean, progress: (Long) -> Unit) {
            if (noSpace) throw StorageException(StorageError.NO_SPACE, "full")
            if (createOnly && location.path in nodes) throw StorageException(StorageError.CONFLICT, "exists")
            puts++
            val bytes = input().use { it.readBytes() }
            nodes[location.path] = Node(if (corrupt) bytes + byteArrayOf(0) else bytes, ++version)
            progress(bytes.size.toLong())
        }
        override suspend fun move(source: StorageLocation, destination: StorageLocation, expected: Revision?) {
            if (denyMove) throw StorageException(StorageError.ACCESS, "denied")
            if (expected != null && !expected.matches(stat(source).revision)) throw StorageException(StorageError.CONFLICT, "changed")
            if (stored(destination.path) in nodes) throw StorageException(StorageError.CONFLICT, "exists")
            val from = stored(source.path)
            val moving = nodes.filterKeys { it == from || it.startsWith("$from/") }
            moving.forEach { (path, node) -> nodes.remove(path); nodes[destination.path + path.removePrefix(from)] = node }
            if (failAfterMove) throw StorageException(StorageError.NETWORK, "ack lost")
        }
        override suspend fun delete(location: StorageLocation, expected: Revision?) {
            if (expected != null && !expected.matches(stat(location).revision)) throw StorageException(StorageError.CONFLICT, "changed")
            check(nodes.keys.none { it.startsWith(location.path + "/") })
            deletes++; nodes.remove(location.path)
        }
    }
}
