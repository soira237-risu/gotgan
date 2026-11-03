package com.quickfiles.app.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The analyzer must attribute every byte to the right top-level child and only report truly identical files as duplicates. */
class StorageAnalyzerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun tree(): Pair<File, LocalStorageProvider> {
        val root = temp.newFolder("phone")
        fun file(path: String, bytes: ByteArray) = File(root, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        file("DCIM/a.jpg", ByteArray(3000) { 1 })
        file("DCIM/sub/copy.jpg", ByteArray(3000) { 1 })        // duplicate of a.jpg
        file("Download/same-size.bin", ByteArray(3000) { 2 })   // same size, different content
        file("Download/movie.mp4", ByteArray(10_000) { 3 })
        file("Download/note.txt", "hello".toByteArray())
        file("Music/song.mp3", ByteArray(500) { 4 })
        file("Music/.hidden/secret.mp3", ByteArray(700) { 5 })
        file("Download/.fileexplorer-trash-11111111-1111-1111-1111-111111111111", ByteArray(9999))
        file("top.pdf", ByteArray(100))
        return root to LocalStorageProvider(root) { true }
    }

    @Test fun totalsChildrenKindsAndLargest() = runBlocking {
        val (_, provider) = tree()
        val report = StorageAnalyzer(largestCount = 3, isManagedName = { it.startsWith(".fileexplorer-") })
            .analyze(provider, StorageLocation(LOCAL_SOURCE), includeHidden = false, findDuplicates = false)
        assertEquals(3000L + 3000 + 3000 + 10_000 + 5 + 500 + 100, report.totalBytes)
        assertEquals(7, report.files)
        assertEquals(listOf("Download", "DCIM", "Music", "top.pdf"), report.children.map { it.entry.name })
        assertEquals(13_005L, report.children.first().bytes)
        assertEquals(3, report.children.first().files)
        assertEquals(listOf("movie.mp4", "a.jpg", "copy.jpg"), report.largest.map { it.name })
        assertEquals(6000L, report.kinds[UsageKind.IMAGE]); assertEquals(10_000L, report.kinds[UsageKind.VIDEO])
        assertEquals(500L, report.kinds[UsageKind.AUDIO]); assertEquals(105L, report.kinds[UsageKind.DOCUMENT])
        assertEquals(3000L, report.kinds[UsageKind.OTHER])
        assertTrue(report.duplicatesSkipped); assertTrue(report.duplicates.isEmpty())
    }

    @Test fun hiddenFilesCountOnlyWhenShown() = runBlocking {
        val (_, provider) = tree()
        val report = StorageAnalyzer(isManagedName = { it.startsWith(".fileexplorer-") })
            .analyze(provider, StorageLocation(LOCAL_SOURCE, "Music"), includeHidden = true, findDuplicates = false)
        assertEquals(1200L, report.totalBytes)
        assertEquals(2, report.files)
    }

    @Test fun duplicatesNeedIdenticalContentNotJustSize() = runBlocking {
        val (_, provider) = tree()
        val report = StorageAnalyzer(isManagedName = { it.startsWith(".fileexplorer-") })
            .analyze(provider, StorageLocation(LOCAL_SOURCE), includeHidden = false, findDuplicates = true)
        assertEquals(1, report.duplicates.size)
        val group = report.duplicates.single()
        assertEquals(listOf("DCIM/a.jpg", "DCIM/sub/copy.jpg"), group.files.map { it.location.path })
        assertEquals(3000L, group.reclaimable); assertEquals(3000L, report.reclaimable)
        assertFalse(report.duplicatesSkipped)
    }

    @Test fun unreadableSubfoldersAreCountedNotFatal() = runBlocking {
        val root = temp.newFolder("phone2")
        File(root, "ok/a.txt").apply { parentFile!!.mkdirs(); writeText("abc") }
        File(root, "Android/data/x/b.bin").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }
        val report = StorageAnalyzer().analyze(LocalStorageProvider(root) { true }, StorageLocation(LOCAL_SOURCE), includeHidden = false, findDuplicates = false)
        assertEquals(3L, report.totalBytes)
        assertEquals(1, report.unreadableFolders)
    }
}
