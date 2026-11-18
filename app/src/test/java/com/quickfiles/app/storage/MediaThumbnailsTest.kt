package com.quickfiles.app.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Remote thumbnails read scattered ranges; they must return the right bytes, reuse blocks and stop at the budget. */
class MediaThumbnailsTest {
    @get:Rule val temp = TemporaryFolder()

    private class Counting(val inner: StorageProvider) : StorageProvider by inner {
        val offsets = mutableListOf<Long>()
        override suspend fun openRead(location: StorageLocation, offset: Long, expected: Revision?): ReadHandle {
            offsets += offset; return inner.openRead(location, offset, expected)
        }
    }

    private fun media(size: Int): Triple<ByteArray, Counting, FileEntry> {
        val root = temp.newFolder("phone")
        val bytes = ByteArray(size) { (it * 31 + 7).toByte() }
        File(root, "clip.mp4").writeBytes(bytes)
        val provider = Counting(LocalStorageProvider(root) { true })
        return Triple(bytes, provider, runBlocking { provider.stat(StorageLocation(LOCAL_SOURCE, "clip.mp4")) })
    }

    @Test fun readsAcrossBlocksAndReusesCachedOnes() {
        val (bytes, provider, entry) = media(10_000)
        val reader = RangedBlocks(provider, entry, bytes.size.toLong(), budget = 100_000, block = 1024, kept = 2)
        val buffer = ByteArray(3000)
        assertEquals(3000, reader.readAt(900, buffer, 0, 3000))
        assertArrayEquals(bytes.copyOfRange(900, 3900), buffer)
        assertEquals(listOf(0L, 1024L, 2048L, 3072L), provider.offsets)
        // The two most recent blocks stay; a read inside them fetches nothing.
        assertEquals(10, reader.readAt(3080, buffer, 5, 10))
        assertArrayEquals(bytes.copyOfRange(3080, 3090), buffer.copyOfRange(5, 15))
        assertEquals(4, provider.offsets.size)
        // The tail block is short; reads stop at the end and past it report end of stream.
        assertEquals(10_000 - 9990, reader.readAt(9990, buffer, 0, 100))
        assertEquals(-1, reader.readAt(10_000, buffer, 0, 1))
    }

    @Test fun stopsAtTheReadBudget() {
        val (bytes, provider, entry) = media(10_000)
        val reader = RangedBlocks(provider, entry, bytes.size.toLong(), budget = 2048, block = 1024)
        val buffer = ByteArray(1)
        reader.readAt(0, buffer, 0, 1); reader.readAt(5000, buffer, 0, 1)
        assertEquals(2048L, reader.fetched)
        assertThrows(IOException::class.java) { reader.readAt(9000, buffer, 0, 1) }
    }

    @Test fun rangedStreamSkipsWithoutFetchingAndReadsAcrossBlocks() {
        val (bytes, provider, entry) = media(10_000)
        val stream = RangedStream(RangedBlocks(provider, entry, bytes.size.toLong(), budget = 100_000, block = 1024), bytes.size.toLong())
        assertEquals(5000L, stream.skip(5000))
        assertTrue("skipping fetches nothing", provider.offsets.isEmpty())
        val buffer = ByteArray(1500)
        assertEquals(1500, stream.readNBytes(buffer, 0, 1500))
        assertArrayEquals(bytes.copyOfRange(5000, 6500), buffer)
        assertEquals(listOf(4096L, 5120L, 6144L), provider.offsets)
        stream.mark(0); assertEquals(bytes[6500].toInt() and 0xff, stream.read()); stream.reset()
        assertArrayEquals(bytes.copyOfRange(6500, 10_000), stream.readBytes())
        assertEquals(-1, stream.read())
    }

    @Test fun embeddedPreviewMustBeSharpAndTheSameShape() {
        assertTrue(MediaThumbnails.embeddedUsable(512, 384, 4000, 3000, 320))      // typical camera preview
        assertFalse(MediaThumbnails.embeddedUsable(160, 120, 4000, 3000, 320))     // too small for a tile
        assertFalse(MediaThumbnails.embeddedUsable(512, 384, 4000, 2250, 320))     // 4:3 preview of a 16:9 photo
        assertFalse(MediaThumbnails.embeddedUsable(512, 384, 0, 0, 320))           // photo size unknown
        assertTrue(MediaThumbnails.embeddedUsable(384, 512, 3000, 4000, 320))      // portrait stored portrait
    }

    @Test fun meteredNetworksFetchOnlySmallOriginals() {
        assertTrue(MediaThumbnails.fullDownloadAllowed(local = true, unmetered = false, size = 50L * 1024 * 1024))
        assertTrue(MediaThumbnails.fullDownloadAllowed(local = false, unmetered = true, size = 50L * 1024 * 1024))
        assertTrue(MediaThumbnails.fullDownloadAllowed(local = false, unmetered = false, size = 1024L * 1024))
        assertFalse(MediaThumbnails.fullDownloadAllowed(local = false, unmetered = false, size = 5L * 1024 * 1024))
        assertFalse(MediaThumbnails.fullDownloadAllowed(local = false, unmetered = false, size = null))
    }

    @Test fun sampleSizeKeepsTheShortSideAboveTarget() {
        assertEquals(1, MediaThumbnails.sampleSize(500, 400, 320))
        assertEquals(2, MediaThumbnails.sampleSize(1280, 720, 320))
        assertEquals(8, MediaThumbnails.sampleSize(3000, 3000, 320))
        assertEquals(1, MediaThumbnails.sampleSize(100, 100, 320))
    }
}
