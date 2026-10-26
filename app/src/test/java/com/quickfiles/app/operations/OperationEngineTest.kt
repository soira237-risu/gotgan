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

    class MemoryStorage(private val id: String) : WritableStorageProvider {
        data class Node(val bytes: ByteArray? = null, val version: Long)
        val nodes = linkedMapOf("" to Node(version = 0))
        private var version = 0L
        var corrupt = false; var noSpace = false; var denyMove = false; var failAfterMove = false
        var puts = 0; var deletes = 0
        override val capabilities = StorageCapabilities(true, true, true)
        fun file(path: String, text: String) { nodes[path] = Node(text.toByteArray(), ++version) }
        fun folder(path: String) { nodes[path] = Node(version = ++version) }
        fun text(path: String) = nodes[path]!!.bytes!!.toString(Charsets.UTF_8)
        private fun location(path: String) = StorageLocation(id, path)
        override suspend fun stat(location: StorageLocation): FileEntry {
            val node = nodes[location.path] ?: throw StorageException(StorageError.NOT_FOUND, "missing")
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
            if (destination.path in nodes) throw StorageException(StorageError.CONFLICT, "exists")
            val moving = nodes.filterKeys { it == source.path || it.startsWith(source.path + "/") }
            moving.forEach { (path, node) -> nodes[destination.path + path.removePrefix(source.path)] = node; nodes.remove(path) }
            if (failAfterMove) throw StorageException(StorageError.NETWORK, "ack lost")
        }
        override suspend fun delete(location: StorageLocation, expected: Revision?) {
            if (expected != null && !expected.matches(stat(location).revision)) throw StorageException(StorageError.CONFLICT, "changed")
            check(nodes.keys.none { it.startsWith(location.path + "/") })
            deletes++; nodes.remove(location.path)
        }
    }
}
