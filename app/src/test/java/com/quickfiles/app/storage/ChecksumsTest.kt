package com.quickfiles.app.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChecksumsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun computesKnownDigestsInOnePass() = runBlocking {
        val root = temp.newFolder("phone"); File(root, "abc.txt").writeText("abc")
        val provider = LocalStorageProvider(root) { true }
        var last = 0L
        val results = Checksums.compute(provider, provider.stat(StorageLocation(LOCAL_SOURCE, "abc.txt"))) { last = it }
        assertEquals("900150983cd24fb0d6963f7d28e17f72", results[ChecksumKind.MD5])
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", results[ChecksumKind.SHA1])
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", results[ChecksumKind.SHA256])
        assertEquals(3L, last)
        assertEquals(ChecksumKind.SHA256, Checksums.match(results, " SHA256: BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD \n"))
        assertEquals(ChecksumKind.MD5, Checksums.match(results, "900150983cd24fb0d6963f7d28e17f72"))
        assertNull(Checksums.match(results, "deadbeef")); assertNull(Checksums.match(results, "  "))
    }
}
