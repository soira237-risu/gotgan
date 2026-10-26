package com.quickfiles.app.storage

import com.quickfiles.app.viewer.TextDocument
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

class ContentCacheTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun pinsActiveFilesAndSeparatesAccountAndVersion() = runBlocking {
        val cache = ContentCache(temp.newFolder()) { 1024 * 1024L }
        val provider = BytesProvider(ByteArray(700 * 1024) { 7 })
        val first = cache.full(provider, provider.stat(StorageLocation("one", "a")))
        try {
            try { cache.full(provider, provider.stat(StorageLocation("two", "b"))); fail("Pinned content cannot be evicted") } catch (e: StorageException) { assertEquals(StorageError.TOO_LARGE, e.kind) }
            assertTrue(first.file.exists())
            try { cache.clearSource("one"); fail("Active source cannot be cleared") } catch (e: StorageException) { assertEquals(StorageError.BUSY, e.kind) }
        } finally { first.close() }
        val second = cache.full(provider, provider.stat(StorageLocation("two", "b")))
        assertNotEquals(first.file, second.file); assertFalse(first.file.exists()); second.close()
        provider.version++
        cache.full(provider, provider.stat(StorageLocation("two", "b"))).use { assertNotEquals(second.file, it.file) }
    }
    @Test fun seekDownloadsOnlyRequestedBlockAndCanFallbackWhenRangeUnsupported() = runBlocking {
        val data = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val provider = BytesProvider(data)
        val cache = ContentCache(temp.newFolder()) { 4 * 1024 * 1024L }
        cache.channel(provider, provider.stat(StorageLocation("nas", "book.zip"))).use { channel ->
            channel.position(2 * 1024 * 1024L + 123)
            val buffer = ByteBuffer.allocate(100)
            assertEquals(100, channel.read(buffer))
            assertArrayEquals(data.copyOfRange(2 * 1024 * 1024 + 123, 2 * 1024 * 1024 + 223), buffer.array())
            assertEquals(listOf(2 * 1024 * 1024L), provider.offsets)
        }
        val fallback = BytesProvider(data).apply { noRange = true }
        cache.channel(fallback, fallback.stat(StorageLocation("other", "book.zip"))).use { channel -> channel.position(1024 * 1024L); assertEquals(10, channel.read(ByteBuffer.allocate(10))) }
        assertEquals(listOf(1024 * 1024L, 0L), fallback.offsets)
    }
    @Test fun textWindowsPreserveMultibyteBoundaryAndDetectExternalChanges() = runBlocking {
        val text = "a".repeat(TextDocument.CHUNK - 1) + "한글\r\n다음"
        val provider = BytesProvider(text.toByteArray())
        val entry = provider.stat(StorageLocation("nas", "test.txt"))
        val first = TextDocument.read(provider, entry)
        assertFalse(first.complete); assertFalse(first.decode().contains('\uFFFD'))
        val next = TextDocument.read(provider, entry, offset = first.bytes.size.toLong(), expected = first.revision, encodingName = first.charset.name())
        assertEquals(text, first.decode() + next.decode()); assertTrue(next.complete)
        provider.version++
        try { TextDocument.read(provider, entry, offset = first.bytes.size.toLong(), expected = first.revision); fail("Changed text cannot be appended") } catch (e: StorageException) { assertEquals(StorageError.CONFLICT, e.kind) }
    }
    @Test fun everyTextWindowBoundaryPreservesUtf8Utf16AndCp949Characters() = runBlocking {
        for (encoding in listOf("UTF-8", "UTF-16LE", "UTF-16BE", "MS949")) {
            val text = if (encoding == "MS949") "AB한글C똠D\r\n끝" else "AB한글C😀D\r\n끝"
            val bom = when (encoding) { "UTF-16LE" -> byteArrayOf(0xff.toByte(), 0xfe.toByte()); "UTF-16BE" -> byteArrayOf(0xfe.toByte(), 0xff.toByte()); else -> byteArrayOf() }
            val bytes = bom + text.toByteArray(charset(encoding))
            for (window in 5..bytes.size) {
                val provider = BytesProvider(bytes)
                val entry = provider.stat(StorageLocation("nas", "test.txt"))
                var offset = 0L; var decoded = ""; var encodingName: String? = null
                do {
                    val doc = TextDocument.read(provider, entry, window, encoding == "MS949", offset, encodingName = encodingName)
                    decoded += doc.decode(); encodingName = doc.charset.name()
                    assertTrue("Each non-final window must advance", doc.complete || doc.bytes.isNotEmpty())
                    offset += doc.bytes.size
                } while (!doc.complete)
                assertEquals("$encoding window=$window", text, decoded)
                assertEquals(bytes.size.toLong(), offset)
            }
        }
    }
    @Test fun retryBudgetIsSharedAndNeverRetriesAuthTlsOrCancellation() = runBlocking {
        var calls = 0
        try { retryRead { retryRead { calls++; throw StorageException(StorageError.NETWORK, "temporary") } }; fail("Expected failure") } catch (_: StorageException) { }
        assertEquals(3, calls)
        for (kind in listOf(StorageError.AUTH, StorageError.TLS, StorageError.CONFLICT)) {
            calls = 0
            try { retryRead { calls++; throw StorageException(kind, "no retry") } } catch (_: StorageException) { }
            assertEquals(1, calls)
        }
    }
    @Test fun concurrentRangeDownloadsCannotExceedCacheCapacity() = runBlocking {
        val folder = temp.newFolder(); val limit = 1024 * 1024L
        val cache = ContentCache(folder) { limit }
        val bytes = BytesProvider(ByteArray(limit.toInt()) { 7 })
        val arrived = java.util.concurrent.atomic.AtomicInteger()
        val both = CompletableDeferred<Unit>()
        val provider = object : StorageProvider by bytes {
            override suspend fun openRead(location: StorageLocation, offset: Long, expected: Revision?): ReadHandle {
                if (arrived.incrementAndGet() == 2) both.complete(Unit)
                withTimeout(10_000) { both.await() }
                return ReadHandle(ByteArrayInputStream(bytes.bytes), limit)
            }
        }
        listOf("one", "two").map { name -> async(Dispatchers.IO) {
            runCatching { cache.channel(provider, provider.stat(StorageLocation("nas", name))).use { it.read(ByteBuffer.allocate(1)) } }
        } }.awaitAll().forEach { result -> result.exceptionOrNull()?.let { assertTrue(it is StorageException && it.kind == StorageError.TOO_LARGE) } }
        assertEquals(2, arrived.get())
        assertTrue("Concurrent reservations must not overfill the cache", folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= limit)
    }
    private class BytesProvider(val bytes: ByteArray) : StorageProvider {
        var version = 1; var noRange = false; val offsets = mutableListOf<Long>()
        override suspend fun list(location: StorageLocation) = emptyList<FileEntry>()
        override suspend fun stat(location: StorageLocation) = FileEntry(location, location.path.substringAfterLast('/'), false, bytes.size.toLong(), version.toLong(), "\"$version\"")
        override suspend fun openRead(location: StorageLocation) = openRead(location, 0, null)
        override suspend fun openRead(location: StorageLocation, offset: Long, expected: Revision?): ReadHandle {
            offsets += offset
            if (noRange && offset > 0) throw StorageException(StorageError.UNSUPPORTED, "no range")
            if (expected != null && !expected.matches(stat(location).revision)) throw StorageException(StorageError.CONFLICT, "changed")
            return ReadHandle(ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()), bytes.size - offset)
        }
    }
}
