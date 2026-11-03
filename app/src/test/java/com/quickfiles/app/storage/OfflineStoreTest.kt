package com.quickfiles.app.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class OfflineStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private class Server(var bytes: ByteArray) : StorageProvider {
        var version = 1; var offline = false; var reads = 0
        /** Changes the file after this many bytes have been read, as if someone saved it mid-download. */
        var changeAfterRead = false
        override suspend fun list(location: StorageLocation) = emptyList<FileEntry>()
        override suspend fun stat(location: StorageLocation): FileEntry {
            if (offline) throw StorageException(StorageError.NETWORK, "서버에 연결하지 못했습니다.")
            return FileEntry(location, location.path.substringAfterLast('/'), false, bytes.size.toLong(), version.toLong(), "\"$version\"")
        }
        override suspend fun openRead(location: StorageLocation) = openRead(location, 0, null)
        override suspend fun openRead(location: StorageLocation, offset: Long, expected: Revision?): ReadHandle {
            if (offline) throw StorageException(StorageError.NETWORK, "서버에 연결하지 못했습니다.")
            reads++
            val data = bytes
            if (changeAfterRead) version++
            return ReadHandle(ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt()), data.size - offset)
        }
    }

    @Test fun keptFilesListOpenAndSurviveRestart() = runBlocking {
        val root = temp.newFolder("offline")
        val store = OfflineStore(root)
        val server = Server("앨범 소개".toByteArray())
        val origin = StorageLocation("nas", "music/소개.txt")
        val kept = store.keep(server, server.stat(origin))
        assertEquals("소개.txt", kept.file.name)
        assertArrayEquals(server.bytes, kept.file.readBytes())
        assertEquals(kept.file.canonicalFile, store.copyOf(origin)!!.file.canonicalFile)
        val listed = store.provider.list(StorageLocation(OFFLINE_SOURCE)).single()
        assertEquals(kept.location, listed.location)
        assertEquals("앨범 소개", store.provider.openRead(listed.location).use { it.stream.reader().readText() })
        // A new process finds the same copies and where they came from.
        val reopened = OfflineStore(root)
        assertEquals(origin, reopened.copyOf(origin)!!.origin)
        assertEquals(server.stat(origin).revision, reopened.copyOf(origin)!!.revision)
        reopened.remove(listOf(origin))
        assertNull(reopened.copyOf(origin))
        assertTrue(reopened.provider.list(StorageLocation(OFFLINE_SOURCE)).isEmpty())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun aFileChangedWhileDownloadingIsNotKept() = runBlocking {
        val store = OfflineStore(temp.newFolder("offline"))
        val server = Server(ByteArray(1000) { 1 }).apply { changeAfterRead = true }
        val origin = StorageLocation("nas", "a.bin")
        try { store.keep(server, server.stat(origin)); fail("A changed file must not be kept") }
        catch (e: StorageException) { assertEquals(StorageError.CONFLICT, e.kind) }
        assertNull(store.copyOf(origin))
        assertTrue("No staging leftovers", store.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun cacheUsesTheKeptCopyWhenCurrentOrUnreachable() = runBlocking {
        val store = OfflineStore(temp.newFolder("offline"))
        val cache = ContentCache(temp.newFolder("cache"), store::copyOf) { 4 * 1024 * 1024L }
        val server = Server(ByteArray(2000) { 7 })
        val origin = StorageLocation("nas", "photo.jpg")
        val kept = store.keep(server, server.stat(origin))
        val readsAfterKeep = server.reads
        cache.full(server, server.stat(origin)).use { assertEquals(kept.file, it.file) }
        assertEquals("A current kept copy needs no download", readsAfterKeep, server.reads)
        val entry = server.stat(origin)
        server.offline = true
        cache.full(server, entry).use { assertEquals(kept.file, it.file) }
        cache.channel(server, entry).use { assertEquals(2000L, it.size()) }
        // Back online with a newer version: the server copy wins and is downloaded.
        server.offline = false; server.version++; server.bytes = ByteArray(3000) { 9 }
        cache.full(server, server.stat(origin)).use { assertNotEquals(kept.file, it.file); assertEquals(3000L, it.file.length()) }
    }

    @Test fun phoneFilesCannotBeKeptAgain() = runBlocking {
        val store = OfflineStore(temp.newFolder("offline"))
        val server = Server(ByteArray(1))
        for (source in listOf(LOCAL_SOURCE, INBOX_SOURCE, OFFLINE_SOURCE)) {
            try { store.keep(server, FileEntry(StorageLocation(source, "a"), "a", false, 1)); fail(source) } catch (_: IllegalArgumentException) {}
        }
    }
}
