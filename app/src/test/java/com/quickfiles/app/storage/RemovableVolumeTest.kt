package com.quickfiles.app.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class RemovableVolumeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun volumeIdsAreStableAndSafe() {
        assertEquals("usb-1A2B-3C4D", volumeSourceId("1a2b-3c4d", File("/storage/1A2B-3C4D")))
        assertEquals("usb-1A2B-3C4D", volumeSourceId(null, File("/storage/1A2B-3C4D")))
        assertEquals("usb-VOLUME", volumeSourceId("", File("/storage/:/")))
        val id = volumeSourceId("ab:cd/../ef", File("/x"))
        assertFalse(id.contains(':') || id.contains('/') || id.contains('.'))
        assertTrue(isLocalSource(LOCAL_SOURCE)); assertTrue(isLocalSource(id)); assertFalse(isLocalSource("webdav-1"))
        assertTrue(isVolumeSource(id)); assertFalse(isVolumeSource(LOCAL_SOURCE))
    }

    @Test fun volumeProviderUsesItsOwnSourceAndName() = runBlocking {
        val root = temp.newFolder("usb")
        File(root, "사진.jpg").writeText("x")
        val provider = LocalStorageProvider(root, sourceId = "usb-AAAA-BBBB", rootName = "SanDisk USB", canRead = { true })
        assertEquals("SanDisk USB", provider.stat(StorageLocation("usb-AAAA-BBBB")).name)
        assertEquals(listOf("사진.jpg"), provider.list(StorageLocation("usb-AAAA-BBBB")).map { it.name })
        // A location from another source must never resolve inside this volume.
        assertThrows(IllegalArgumentException::class.java) { runBlocking { provider.stat(StorageLocation(LOCAL_SOURCE)) } }
        provider.put(StorageLocation("usb-AAAA-BBBB", "new.txt"), 2, { ByteArrayInputStream("hi".toByteArray()) })
        assertEquals("hi", File(root, "new.txt").readText())
    }

    @Test fun readOnlyVolumeRejectsWritesButReads() = runBlocking {
        val root = temp.newFolder("ro")
        File(root, "a.txt").writeText("keep")
        val provider = LocalStorageProvider(root, sourceId = "usb-RO", rootName = "USB", writable = false, canRead = { true })
        assertFalse(provider.capabilities.writable)
        assertEquals("keep", provider.openRead(StorageLocation("usb-RO", "a.txt")).use { it.stream.reader().readText() })
        listOf<suspend () -> Unit>(
            { provider.delete(StorageLocation("usb-RO", "a.txt")) },
            { provider.createDirectory(StorageLocation("usb-RO", "d")) },
            { provider.put(StorageLocation("usb-RO", "b.txt"), 1, { ByteArrayInputStream(byteArrayOf(1)) }) },
        ).forEach { write ->
            try { write(); fail("Read-only volume must reject writes") } catch (e: StorageException) { assertEquals(StorageError.ACCESS, e.kind) }
        }
        assertEquals("keep", File(root, "a.txt").readText())
    }

    @Test fun unpluggedVolumeFailsWithAccessError() = runBlocking {
        val root = temp.newFolder("gone")
        var mounted = true
        val provider = LocalStorageProvider(root, sourceId = "usb-GONE", rootName = "USB", canRead = { mounted })
        provider.list(StorageLocation("usb-GONE"))
        mounted = false
        try { provider.list(StorageLocation("usb-GONE")); fail() } catch (e: StorageException) { assertEquals(StorageError.ACCESS, e.kind); assertTrue(e.message!!.contains("USB")) }
    }
}
