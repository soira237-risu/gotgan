package com.quickfiles.app.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class VaultTest {
    @get:Rule val temp = TemporaryFolder()

    /** Stands in for the Keystore: a fixed AES key that only works while [allowed], as if the fingerprint check passed. */
    private class FakeGuard : Vault.KeyGuard {
        var allowed = true
        private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        private fun cipher(mode: Int, iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(128, iv)) }
        override fun wrap(key: ByteArray): ByteArray {
            if (!allowed) throw StorageException(StorageError.AUTH, "본인 확인 필요")
            val iv = Random.nextBytes(12); return iv + cipher(Cipher.ENCRYPT_MODE, iv).doFinal(key)
        }
        override fun unwrap(wrapped: ByteArray): ByteArray {
            if (!allowed) throw StorageException(StorageError.AUTH, "본인 확인 필요")
            return cipher(Cipher.DECRYPT_MODE, wrapped.copyOf(12)).doFinal(wrapped, 12, wrapped.size - 12)
        }
    }

    private class Files(val files: MutableMap<String, ByteArray>) : StorageProvider {
        override suspend fun list(location: StorageLocation) = emptyList<FileEntry>()
        override suspend fun stat(location: StorageLocation): FileEntry {
            val bytes = files[location.path] ?: throw StorageException(StorageError.NOT_FOUND, "없음")
            return FileEntry(location, location.path.substringAfterLast('/'), false, bytes.size.toLong(), 1000L)
        }
        override suspend fun openRead(location: StorageLocation) = openRead(location, 0, null)
        override suspend fun openRead(location: StorageLocation, offset: Long, expected: Revision?): ReadHandle {
            val bytes = files[location.path] ?: throw StorageException(StorageError.NOT_FOUND, "없음")
            return ReadHandle(ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()), bytes.size - offset)
        }
    }

    private fun encrypt(key: javax.crypto.SecretKey, plain: ByteArray) = ByteArrayOutputStream().also { VaultCipher.encrypt(key, plain.inputStream(), it) }.toByteArray()
    private fun decrypt(key: javax.crypto.SecretKey, sealed: ByteArray) = ByteArrayOutputStream().also { VaultCipher.decrypt(key, sealed.inputStream(), it) }.toByteArray()
    private fun assertRejected(block: () -> Unit) {
        try { block(); fail("Damaged vault data must not decrypt") } catch (e: StorageException) { assertEquals(StorageError.INVALID_RESPONSE, e.kind) }
    }

    @Test fun cipherRoundTripsAtChunkBoundaries() {
        val key = VaultCipher.newKey()
        val c = VaultCipher.CHUNK
        for (size in listOf(0, 1, c - 1, c, c + 1, 3 * c, 3 * c + 17)) {
            val plain = Random(size).nextBytes(size)
            val sealed = encrypt(key, plain)
            assertArrayEquals("size $size", plain, decrypt(key, sealed))
            // Ciphertext does not contain the plaintext and differs on every encryption (random nonce prefix).
            assertFalse(sealed.contentEquals(encrypt(key, plain)))
        }
    }

    @Test fun tamperingTruncationReorderingAndWrongKeyAreRejected() {
        val key = VaultCipher.newKey()
        val plain = Random(1).nextBytes(VaultCipher.CHUNK * 3 + 5)
        val sealed = encrypt(key, plain)
        // A flipped bit anywhere in a chunk.
        assertRejected { decrypt(key, sealed.copyOf().also { it[sealed.size / 2] = (it[sealed.size / 2].toInt() xor 1).toByte() }) }
        // Wrong key.
        assertRejected { decrypt(VaultCipher.newKey(), sealed) }
        // Split into header + chunks to drop or swap whole chunks, which a per-chunk tag alone would not catch.
        val header = 16
        val chunks = mutableListOf<ByteArray>()
        DataInputStream(sealed.inputStream(header, sealed.size - header)).use { input ->
            while (input.available() > 0) { val n = input.readInt(); chunks += (ByteArray(4) { (n ushr (8 * (3 - it))).toByte() } + ByteArray(n).also(input::readFully)) }
        }
        assertEquals(4, chunks.size)
        val head = sealed.copyOf(header)
        fun join(parts: List<ByteArray>) = parts.fold(head) { acc, part -> acc + part }
        assertArrayEquals(plain, decrypt(key, join(chunks)))
        assertRejected { decrypt(key, join(chunks.dropLast(1))) }            // the last chunk dropped
        assertRejected { decrypt(key, sealed.copyOf(sealed.size - 10)) }             // cut mid-chunk
        assertRejected { decrypt(key, join(listOf(chunks[1], chunks[0], chunks[2], chunks[3]))) }
        assertRejected { decrypt(key, join(chunks + chunks.last())) }       // data after the last chunk
    }

    @Test fun filesAreEncryptedAtRestListedWhileUnlockedAndWipedOnLock() = runBlocking {
        val root = temp.newFolder("vault"); val openRoot = File(temp.root, "vault-open")
        val guard = FakeGuard()
        val secret = "비밀 일기 내용 ".repeat(20000).toByteArray()
        val source = Files(mutableMapOf("Documents/일기.txt" to secret))
        val vault = Vault(root, openRoot, guard)
        assertFalse(vault.isSetUp)
        vault.unlock()
        assertTrue(vault.isSetUp)
        val item = vault.add(source, source.stat(StorageLocation(LOCAL_SOURCE, "Documents/일기.txt")))
        assertEquals("일기.txt", item.name); assertEquals(secret.size.toLong(), item.size)
        assertEquals(StorageLocation(LOCAL_SOURCE, "Documents/일기.txt"), item.origin)
        // Nothing in the vault folder reveals the name or the content.
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val bytes = file.readBytes()
            assertFalse(file.name.contains("일기"))
            assertFalse("${file.name} holds plaintext", String(bytes, Charsets.ISO_8859_1).contains(String("비밀 일기".toByteArray(), Charsets.ISO_8859_1)))
        }
        val listed = vault.provider.list(StorageLocation(VAULT_SOURCE)).single()
        assertEquals(item.location, listed.location)
        assertArrayEquals(secret, vault.provider.openRead(listed.location).use { it.stream.readBytes() })
        val opened = vault.provider.fileForRead(listed.location)
        assertTrue(opened.canonicalPath.startsWith(openRoot.canonicalPath))
        // Locking forgets the key and removes every decrypted copy.
        vault.lock()
        assertFalse(opened.exists()); assertFalse(openRoot.exists())
        assertNull(vault.items.value)
        try { vault.provider.list(StorageLocation(VAULT_SOURCE)); fail("A locked vault must not list") } catch (e: StorageException) { assertEquals(StorageError.ACCESS, e.kind) }
        try { vault.provider.openRead(listed.location); fail("A locked vault must not read") } catch (e: StorageException) { assertEquals(StorageError.ACCESS, e.kind) }
        // Without the owner check nothing opens; a new process with it sees the same file.
        guard.allowed = false
        val restarted = Vault(root, openRoot, guard)
        try { restarted.unlock(); fail("Unlock must need the guard") } catch (e: StorageException) { assertEquals(StorageError.AUTH, e.kind) }
        assertFalse(restarted.isUnlocked)
        guard.allowed = true
        restarted.unlock()
        assertEquals(listOf(item), restarted.items.value)
        assertArrayEquals(secret, restarted.open(restarted.items.value!!.single()).readBytes())
        restarted.delete(restarted.items.value!!)
        assertTrue(restarted.items.value!!.isEmpty())
        assertEquals(listOf("key.bin"), root.list()!!.toList())
        assertFalse(File(openRoot, item.id).exists())
    }

    @Test fun aShortReadIsNotAcceptedAsTheFile() = runBlocking {
        val vault = Vault(temp.newFolder("vault"), File(temp.root, "open"), FakeGuard()).apply { unlock() }
        val source = Files(mutableMapOf("a.bin" to ByteArray(1000)))
        // The listing claimed more bytes than the file had when read.
        val entry = source.stat(StorageLocation(LOCAL_SOURCE, "a.bin")).copy(size = 2000)
        try { vault.add(source, entry); fail("A short copy must not replace the original") } catch (e: StorageException) { assertEquals(StorageError.READ, e.kind) }
        assertTrue(vault.items.value!!.isEmpty())
        assertEquals(listOf("key.bin"), vault.root.list()!!.toList())
    }

    @Test fun theVaultIsOnThePhoneSoItNeverWaitsForANetwork() {
        assertTrue(isDeviceSource(VAULT_SOURCE))
    }
}
