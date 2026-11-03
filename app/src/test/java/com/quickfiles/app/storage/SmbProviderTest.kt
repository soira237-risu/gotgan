package com.quickfiles.app.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.UUID

/**
 * Exercises the SMB provider against a real server. There is no embeddable SMB server for the JVM, so this
 * runs only when GOTGAN_SMB_URL (for example smb://127.0.0.1:4455/share), GOTGAN_SMB_USER and GOTGAN_SMB_PASSWORD
 * point at a writable share, such as a local Samba.
 */
class SmbProviderTest {
    private val url = System.getenv("GOTGAN_SMB_URL")

    @Test fun browsesReadsAndWritesAgainstARealShare() = runBlocking {
        assumeTrue("GOTGAN_SMB_URL not set", url != null)
        val smb = SmbProvider("nas", url!!, System.getenv("GOTGAN_SMB_USER").orEmpty(), System.getenv("GOTGAN_SMB_PASSWORD").orEmpty())
        val folder = StorageLocation("nas", "gotgan-test-" + UUID.randomUUID())
        try {
            smb.createDirectory(folder)
            assertEquals(StorageError.CONFLICT, (runCatching { smb.createDirectory(folder) }.exceptionOrNull() as StorageException).kind)
            val bytes = ByteArray(700_000) { (it * 7).toByte() }
            val stage = folder.child(".fileexplorer-x.part")
            var progress = 0L
            smb.put(stage, bytes.size.toLong(), { ByteArrayInputStream(bytes) }, progress = { progress = it })
            assertEquals(bytes.size.toLong(), progress)
            assertEquals(StorageError.CONFLICT, (runCatching { smb.put(stage, 1, { ByteArrayInputStream(byteArrayOf(1)) }) }.exceptionOrNull() as StorageException).kind)
            val target = folder.child("영상 1.bin")
            smb.move(stage, target, smb.stat(stage).revision)
            val entry = smb.list(folder).single()
            assertEquals("영상 1.bin", entry.name); assertEquals(bytes.size.toLong(), entry.size)
            assertEquals("Listing and stat must agree on the version", entry.revision, smb.stat(target).revision)
            assertNotNull(entry.revision.strongEtag)
            assertArrayEquals(bytes, smb.openRead(target).use { it.stream.readBytes() })
            assertArrayEquals(bytes.copyOfRange(650_000, bytes.size), smb.openRead(target, 650_000, entry.revision).use { it.stream.readBytes() })
            // Moving onto an existing name is refused.
            smb.put(stage, 1, { ByteArrayInputStream(byteArrayOf(1)) })
            assertEquals(StorageError.CONFLICT, (runCatching { smb.move(stage, target, null) }.exceptionOrNull() as StorageException).kind)
            smb.delete(stage, smb.stat(stage).revision)
            // A changed file is not deleted with a stale version, and folders are not deleted recursively.
            val old = smb.stat(target).revision
            smb.put(target, 3, { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, expected = old, createOnly = false)
            assertEquals(StorageError.CONFLICT, (runCatching { smb.delete(target, old) }.exceptionOrNull() as StorageException).kind)
            assertTrue(runCatching { smb.delete(folder, smb.stat(folder).revision) }.isFailure)
            assertNotNull(smb.availableBytes(folder))
            smb.delete(target, smb.stat(target).revision)
            smb.delete(folder, smb.stat(folder).revision)
            assertEquals(StorageError.NOT_FOUND, (runCatching { smb.stat(folder) }.exceptionOrNull() as StorageException).kind)
        } finally { smb.close() }
        val wrong = SmbProvider("nas", url, System.getenv("GOTGAN_SMB_USER").orEmpty(), "wrong-password")
        assertEquals(StorageError.AUTH, (runCatching { wrong.list(StorageLocation("nas")) }.exceptionOrNull() as StorageException).kind)
        wrong.close()
    }
}
