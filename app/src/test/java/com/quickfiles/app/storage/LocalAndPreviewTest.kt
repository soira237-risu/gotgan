package com.quickfiles.app.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class LocalAndPreviewTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun localReadOnlyNavigationAndPermissionRevocation() = runBlocking {
        val root = temp.newFolder("phone")
        File(root, "한글.txt").writeText("테스트")
        File(root, "folder").mkdir()
        var permission = true
        val provider = LocalStorageProvider(root) { permission }
        val result = sortedEntries(provider.list(StorageLocation(LOCAL_SOURCE)), SortOrder.NAME)
        assertTrue(result.first().isDirectory)
        val text = result.last()
        assertEquals("테스트", provider.openRead(text.location).use { it.stream.reader().readText() })
        permission = false
        try { provider.openRead(text.location); fail("Permission must be checked for each operation") }
        catch (e: StorageException) { assertEquals(StorageError.ACCESS, e.kind) }
        assertEquals("테스트", File(root, "한글.txt").readText())
    }

    @Test fun localProtectedPathsAndTraversal() = runBlocking {
        val root = temp.newFolder("phone")
        val provider = LocalStorageProvider(root) { true }
        listOf("../secret", "a/../secret", "/absolute", "a//b", "a\\b").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { StorageLocation(LOCAL_SOURCE, path) }
        }
        listOf("Android/data", "Android/obb/a.txt").forEach { path ->
            try { provider.stat(StorageLocation(LOCAL_SOURCE, path)); fail("Protected path") }
            catch (e: StorageException) { assertEquals(StorageError.ACCESS, e.kind) }
        }
    }

    @Test fun textBomKoreanEncodingAndBoundedRead() = runBlocking {
        assertEquals("한글", TextPreview("한글".toByteArray(Charsets.UTF_8), false).decode(false))
        assertEquals("한글", TextPreview(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "한글".toByteArray(Charsets.UTF_16LE), false).decode(false))
        assertEquals("한글", TextPreview("한글".toByteArray(charset("MS949")), false).decode(true))
        var consumed = 0L
        val endless = object : InputStream() {
            override fun read(): Int { consumed++; return 'a'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill('a'.code.toByte(), off, off + len); consumed += len; return len }
        }
        val text = PreviewCache.readText(endless)
        assertTrue(text.truncated)
        assertEquals(TEXT_LIMIT.toInt(), text.bytes.size)
        assertEquals(TEXT_LIMIT + 1, consumed)
        assertFalse(PreviewCache.readText(ByteArrayInputStream(ByteArray(TEXT_LIMIT.toInt()))).truncated)
    }

    @Test fun boundedXmlStreamFailsAtLimit() {
        val stream = BoundedInputStream(ByteArrayInputStream(ByteArray(30)), 16)
        assertThrows(StorageException::class.java) { stream.readBytes() }
    }

    @Test fun previewCacheVersioningSourceIsolationLimitsAndCleanup() = runBlocking {
        val root = temp.newFolder("cache")
        val cache = PreviewCache(root)
        val provider = FakeProvider()
        val one = FileEntry(StorageLocation("one", "a.jpg"), "a.jpg", false)
        val two = one.copy(location = StorageLocation("two", "a.jpg"))
        val first = cache.image(provider, one) { _, _ -> }
        assertArrayEquals(byteArrayOf(1, 2, 3), first.readBytes())
        assertEquals(first, cache.image(provider, one) { _, _ -> })
        assertEquals(1, provider.opens)
        val second = cache.image(provider, two) { _, _ -> }
        assertNotEquals(first, second)
        provider.version = "2"
        assertNotEquals(first, cache.image(provider, one) { _, _ -> })
        provider.size = IMAGE_LIMIT + 1
        try { cache.image(provider, one) { _, _ -> }; fail("Oversized content") }
        catch (e: StorageException) { assertEquals(StorageError.TOO_LARGE, e.kind) }
        cache.clearSource("one")
        assertFalse(first.exists())
        assertTrue(second.exists())
        assertTrue(root.walk().none { it.extension == "part" })
    }

    @Test fun unknownContentLengthStillEnforcesImageCap() = runBlocking {
        val root = temp.newFolder("cache")
        val provider = object : StorageProvider {
            override suspend fun list(location: StorageLocation) = emptyList<FileEntry>()
            override suspend fun stat(location: StorageLocation) = FileEntry(location, "large.jpg", false)
            override suspend fun openRead(location: StorageLocation) = ReadHandle(object : InputStream() {
                override fun read() = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(0, off, off + len); return len }
            }, null)
        }
        try { PreviewCache(root).image(provider, provider.stat(StorageLocation("one", "large.jpg"))) { _, _ -> }; fail("Unlimited stream") }
        catch (e: StorageException) { assertEquals(StorageError.TOO_LARGE, e.kind) }
        assertTrue(root.walk().none { it.isFile })
    }

    private class FakeProvider : StorageProvider {
        var opens = 0
        var version = "1"
        var size = 3L
        override suspend fun list(location: StorageLocation) = emptyList<FileEntry>()
        override suspend fun stat(location: StorageLocation) = FileEntry(location, "a.jpg", false, size, etag = version)
        override suspend fun openRead(location: StorageLocation): ReadHandle { opens++; return ReadHandle(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3) }
    }
}
