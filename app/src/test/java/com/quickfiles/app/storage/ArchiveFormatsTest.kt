package com.quickfiles.app.storage

import com.quickfiles.app.viewer.*
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Every supported container lists the same members and returns their exact bytes, in or out of archive order. */
class ArchiveFormatsTest {
    @get:Rule val temp = TemporaryFolder()
    private val members = linkedMapOf("사진/a.jpg" to ByteArray(70_000) { (it % 251).toByte() }, "사진/b.txt" to "두 번째".toByteArray(), "readme.md" to "# hi".toByteArray())

    private fun tar(out: OutputStream, withLink: Boolean = false) = TarArchiveOutputStream(out).use { tar ->
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        tar.putArchiveEntry(TarArchiveEntry("사진/")); tar.closeArchiveEntry()
        members.forEach { (name, bytes) -> tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() }); tar.write(bytes); tar.closeArchiveEntry() }
        if (withLink) { tar.putArchiveEntry(TarArchiveEntry("escape", TarArchiveEntry.LF_SYMLINK).apply { linkName = "/etc/passwd" }); tar.closeArchiveEntry() }
    }

    private fun open(file: File) = runBlocking { ArchiveDocument.open(Files.newByteChannel(file.toPath(), StandardOpenOption.READ), ArchiveFormat.detect(file.name)!!) }

    private fun assertMembers(file: File) {
        open(file).use { archive ->
            assertEquals(listOf("사진/a.jpg", "사진/b.txt", "readme.md"), archive.entries.filter { !it.isDirectory }.map { it.name.trimEnd('/') })
            val files = archive.entries.filter { !it.isDirectory }
            // In archive order (extraction), then backwards (random previews).
            for (item in files + files.reversed()) assertArrayEquals(file.name + " " + item.name, members.getValue(item.name), archive.open(item).use { it.readBytes() })
            assertArrayEquals(members.getValue("사진/b.txt"), runBlocking { archive.preview(files[1], 1024) })
        }
    }

    @Test fun detectsFormatsByName() {
        assertEquals(ArchiveFormat.Zip, ArchiveFormat.detect("만화.CBZ"))
        assertEquals(ArchiveFormat.SevenZ, ArchiveFormat.detect("backup.7z"))
        assertEquals(ArchiveFormat.Tar(null), ArchiveFormat.detect("src.tar"))
        assertEquals(ArchiveFormat.Tar(Compression.GZIP), ArchiveFormat.detect("src.tar.gz"))
        assertEquals(ArchiveFormat.Tar(Compression.GZIP), ArchiveFormat.detect("src.tgz"))
        assertEquals(ArchiveFormat.Tar(Compression.XZ), ArchiveFormat.detect("src.tar.xz"))
        assertEquals(ArchiveFormat.Tar(Compression.BZIP2), ArchiveFormat.detect("src.tbz2"))
        assertEquals(ArchiveFormat.Tar(Compression.ZSTD), ArchiveFormat.detect("src.tar.zst"))
        assertEquals(ArchiveFormat.Single(Compression.GZIP, "log.txt"), ArchiveFormat.detect("log.txt.gz"))
        assertNull(ArchiveFormat.detect("image.svgz")); assertNull(ArchiveFormat.detect(".zip")); assertNull(ArchiveFormat.detect("movie.ts"))
        assertEquals("photos", archiveStem("photos.tar.gz")); assertEquals("photos", archiveStem("photos.zip")); assertEquals("noext", archiveStem("noext"))
        assertTrue(FileEntry(StorageLocation(LOCAL_SOURCE, "a.tar.xz"), "a.tar.xz", false).isArchive)
    }

    @Test fun tarInEveryCompression() {
        assertMembers(temp.newFile("plain.tar").also { tar(it.outputStream()) })
        assertMembers(temp.newFile("a.tar.gz").also { tar(GzipCompressorOutputStream(it.outputStream())) })
        assertMembers(temp.newFile("a.tar.xz").also { tar(XZCompressorOutputStream(it.outputStream())) })
        assertMembers(temp.newFile("a.tar.bz2").also { tar(BZip2CompressorOutputStream(it.outputStream())) })
        assertMembers(temp.newFile("a.tar.zst").also { tar(ZstdCompressorOutputStream(it.outputStream())) })
    }

    @Test fun sevenZ() {
        val file = temp.newFile("a.7z")
        SevenZOutputFile(file).use { out ->
            members.forEach { (name, bytes) -> out.putArchiveEntry(out.createArchiveEntry(temp.newFile().apply { writeBytes(bytes) }, name)); out.write(bytes); out.closeArchiveEntry() }
        }
        assertMembers(file)
    }

    @Test fun singleCompressedFileHoldsOneMember() {
        val file = temp.newFile("server.log.gz")
        GzipCompressorOutputStream(file.outputStream()).use { it.write("line1\nline2\n".toByteArray()) }
        open(file).use { archive ->
            assertEquals(listOf("server.log"), archive.entries.map { it.name })
            assertEquals("line1\nline2\n", archive.open(archive.entries.single()).use { String(it.readBytes()) })
        }
    }

    @Test fun tarLinksAreLeftOutAndTraversalIsRejected() {
        val links = temp.newFile("links.tar").also { tar(it.outputStream(), withLink = true) }
        open(links).use { archive -> assertTrue(archive.entries.none { it.name == "escape" }) }
        val evil = temp.newFile("evil.tar")
        TarArchiveOutputStream(evil.outputStream()).use { tar -> tar.putArchiveEntry(TarArchiveEntry("../outside.txt").apply { size = 1 }); tar.write(1); tar.closeArchiveEntry() }
        assertThrows(IllegalArgumentException::class.java) { open(evil) }
    }

    @Test fun mislabelledFilesFailWithAReadableError() {
        val fake = temp.newFile("fake.tar.gz").apply { writeText("not gzip at all") }
        val error = assertThrows(StorageException::class.java) { open(fake) }
        assertEquals(StorageError.READ, error.kind)
    }
}
