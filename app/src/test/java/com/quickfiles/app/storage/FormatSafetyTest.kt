package com.quickfiles.app.storage

import com.quickfiles.app.viewer.*
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.junit.Assert.*
import org.junit.Test

class FormatSafetyTest {
    @Test fun rejectsZipTraversalLinksDuplicateAndExpansion() {
        listOf("../x", "/etc/file", "C:/outside", "a/../../x", "a\\b", "a//b", "./a").forEach { name -> assertThrows(IllegalArgumentException::class.java) { ArchiveSafety.path(name) } }
        assertEquals("한글 폴더/a.txt", ArchiveSafety.path("한글 폴더/a.txt"))
        assertThrows(IllegalArgumentException::class.java) { ArchiveSafety.validate(listOf(ZipArchiveEntry("a"), ZipArchiveEntry("a"))) }
        assertThrows(IllegalArgumentException::class.java) { ArchiveSafety.validate(listOf(ZipArchiveEntry("a"), ZipArchiveEntry("a/b"))) }
        assertThrows(IllegalArgumentException::class.java) { ArchiveSafety.validate(listOf(ZipArchiveEntry("link").apply { unixMode = 0xA1FF })) }
        assertThrows(IllegalArgumentException::class.java) { ArchiveSafety.validate(listOf(ZipArchiveEntry("huge").apply { size = ArchiveSafety.MAX_OUTPUT + 1 })) }
    }
    @Test fun preservesUtf16BomCrLfAndCp949OnSaveAndRejectsPartialEditing() {
        val original = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "한글\r\n다음\r\n".toByteArray(Charsets.UTF_16LE)
        val utf16 = TextDocument(original, true, Revision())
        assertEquals("한글\r\n다음\r\n", utf16.decode()); assertArrayEquals(original, utf16.encode(utf16.decode()))
        val cp = TextDocument("한글\r\n".toByteArray(charset("MS949")), true, Revision(), true)
        assertArrayEquals(cp.bytes, cp.encode(cp.decode()))
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { cp.encode("emoji 😀") }
        assertEquals("emoji 😀", cp.encode("emoji 😀", true).toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { cp.copy(complete = false).encode("prefix") }
        assertThrows(IllegalArgumentException::class.java) { cp.copy(start = 1024).encode("suffix") }
    }
}
