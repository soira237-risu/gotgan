package com.quickfiles.app.storage

import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/** Runs the provider against a real SFTP server (Apache MINA SSHD) serving a temporary folder. */
class SftpProviderTest {
    @get:Rule val temp = TemporaryFolder()
    private val servers = mutableListOf<SshServer>()
    private class MemoryKeys : HostKeys {
        val keys = HashMap<String, String>()
        override suspend fun known(host: String) = keys[host]
        override suspend fun trust(host: String, key: String) { keys.putIfAbsent(host, key) }
    }

    private fun server(root: File, keyFile: File, port: Int = 0): SshServer = SshServer.setUpDefaultServer().apply {
        this.port = port; host = "127.0.0.1"
        keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile.toPath()).apply { algorithm = "EC" }
        setPasswordAuthenticator { user, password, _ -> user == "dj" && password == "secret" }
        fileSystemFactory = VirtualFileSystemFactory(root.toPath())
        subsystemFactories = listOf(SftpSubsystemFactory())
        start()
    }.also { servers += it }

    @After fun stop() { servers.forEach { it.stop(true) } }

    @Test fun browsesReadsAndWritesLikeTheOtherProviders() = runBlocking {
        val root = temp.newFolder("srv"); File(root, "Music").mkdirs()
        File(root, "Music/song*?.mp3").writeBytes(ByteArray(5000) { it.toByte() })
        val server = server(root, File(temp.root, "host.key"))
        val keys = MemoryKeys()
        val sftp = SftpProvider("nas", "sftp://127.0.0.1:${server.port}/", "dj", "secret", keys)
        try {
            assertEquals(listOf("Music"), sftp.list(StorageLocation("nas")).map { it.name })
            assertEquals(1, keys.keys.size); assertNotNull(sftp.newlyTrustedKey)
            val song = sftp.list(StorageLocation("nas", "Music")).single()
            assertEquals("song*?.mp3", song.name); assertEquals(5000L, song.size)
            assertNotNull("SFTP revisions must allow verified edits", song.revision.strongEtag)
            // Ranged reads start where asked, and names with glob characters are taken literally.
            val tail = sftp.openRead(song.location, 4990, song.revision).use { it.stream.readBytes() }
            assertArrayEquals(ByteArray(10) { (4990 + it).toByte() }, tail)

            val folder = StorageLocation("nas", "Docs")
            sftp.createDirectory(folder)
            assertThrows(StorageException::class.java) { runBlocking { sftp.createDirectory(folder) } }
            val stage = folder.child(".part")
            var progress = 0L
            sftp.put(stage, 11, { ByteArrayInputStream("hello there".toByteArray()) }, progress = { progress = it })
            assertEquals(11L, progress)
            val e = runCatching { sftp.put(stage, 1, { ByteArrayInputStream(byteArrayOf(1)) }) }.exceptionOrNull() as StorageException
            assertEquals(StorageError.CONFLICT, e.kind)
            sftp.move(stage, folder.child("note.txt"), sftp.stat(stage).revision)
            assertEquals("hello there", File(root, "Docs/note.txt").readText())
            // Moving onto an existing name is refused, never overwritten.
            sftp.put(stage, 1, { ByteArrayInputStream(byteArrayOf(1)) })
            assertEquals(StorageError.CONFLICT, (runCatching { sftp.move(stage, folder.child("note.txt"), null) }.exceptionOrNull() as StorageException).kind)
            sftp.delete(stage, sftp.stat(stage).revision)
            // Directory delete is not recursive.
            assertTrue(runCatching { sftp.delete(folder, sftp.stat(folder).revision) }.isFailure)
            assertTrue(File(root, "Docs/note.txt").exists())
            val stale = sftp.stat(folder.child("note.txt")).revision.copy(etag = "\"other\"")
            assertEquals(StorageError.CONFLICT, (runCatching { sftp.delete(folder.child("note.txt"), stale) }.exceptionOrNull() as StorageException).kind)
            sftp.delete(folder.child("note.txt"), sftp.stat(folder.child("note.txt")).revision)
            sftp.delete(folder, sftp.stat(folder).revision)
            assertFalse(File(root, "Docs").exists())
            assertEquals(StorageError.NOT_FOUND, (runCatching { sftp.stat(folder) }.exceptionOrNull() as StorageException).kind)
        } finally { sftp.close() }
    }

    @Test fun refusesWrongPasswordAndChangedServerKey() = runBlocking {
        val root = temp.newFolder("srv")
        val first = server(root, File(temp.root, "a.key"))
        val keys = MemoryKeys()
        val wrong = SftpProvider("nas", "sftp://127.0.0.1:${first.port}/", "dj", "nope", keys)
        assertEquals(StorageError.AUTH, (runCatching { wrong.list(StorageLocation("nas")) }.exceptionOrNull() as StorageException).kind)
        wrong.close()
        SftpProvider("nas", "sftp://127.0.0.1:${first.port}/", "dj", "secret", keys).apply { list(StorageLocation("nas")); close() }
        val port = first.port
        first.stop(true)
        // Same address, different key: an impostor must not get the password.
        server(root, File(temp.root, "b.key"), port)
        val again = SftpProvider("nas", "sftp://127.0.0.1:$port/", "dj", "secret", keys)
        val refused = runCatching { again.list(StorageLocation("nas")) }.exceptionOrNull() as StorageException
        assertEquals(StorageError.TLS, refused.kind)
        assertTrue(refused.message!!.contains("SHA256:"))
        again.close()
    }

    @Test fun addressesAreValidatedAndCanonical() {
        assertEquals("sftp://host:2222/home/dj/", RemoteAddress.parse("sftp://host:2222/home/dj").toString())
        val share = RemoteAddress.parse("smb://nas.local/%EA%B3%B5%EC%9C%A0/a%20b")
        assertEquals(listOf("공유", "a b"), share.segments)
        assertEquals(share, RemoteAddress.parse(share.toString()))
        assertEquals("[h]:2222", RemoteAddress.parse("sftp://h:2222/").hostKey)
        assertEquals("h", RemoteAddress.parse("sftp://h/").hostKey)
        assertThrows(IllegalArgumentException::class.java) { RemoteAddress.parse("smb://nas.local/") }
        assertThrows(IllegalArgumentException::class.java) { RemoteAddress.parse("sftp://user:pw@host/") }
        assertThrows(IllegalArgumentException::class.java) { RemoteAddress.parse("sftp://host/a/../b") }
        assertEquals(Protocol.WEBDAV, Protocol.of("https://x/")); assertEquals(Protocol.SMB, Protocol.of("SMB://x/s"))
    }
}
