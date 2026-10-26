package com.quickfiles.app

import com.quickfiles.app.storage.FileEntry
import com.quickfiles.app.storage.StorageLocation
import com.quickfiles.app.ui.FileKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Search type filters must pick each file into exactly the expected kind, and never folders. */
class FileKindTest {
    private fun entry(name: String, folder: Boolean = false) = FileEntry(StorageLocation("phone", "Download/$name"), name, folder)

    private fun kinds(name: String, folder: Boolean = false) = FileKind.entries.filter { it.matches(entry(name, folder)) }.toSet()

    @Test fun mediaAndDocumentsLandInOneKind() {
        assertEquals(setOf(FileKind.IMAGE), kinds("제주_노을.JPG"))
        assertEquals(setOf(FileKind.IMAGE), kinds("photo.heic"))
        assertEquals(setOf(FileKind.VIDEO), kinds("강의_03.mp4"))
        assertEquals(setOf(FileKind.AUDIO), kinds("녹음.m4a"))
        assertEquals(setOf(FileKind.DOCUMENT), kinds("견적서.pdf"))
        assertEquals(setOf(FileKind.DOCUMENT), kinds("회의록.txt"))
        assertEquals(setOf(FileKind.DOCUMENT), kinds("notes.md"))
        assertEquals(setOf(FileKind.ARCHIVE), kinds("백업.zip"))
        assertEquals(setOf(FileKind.ARCHIVE), kinds("만화_01.cbz"))
    }

    @Test fun foldersAndUnknownFilesMatchNoKind() {
        assertEquals(emptySet<FileKind>(), kinds("사진.jpg", folder = true))
        assertEquals(emptySet<FileKind>(), kinds("setup.exe"))
    }
}
