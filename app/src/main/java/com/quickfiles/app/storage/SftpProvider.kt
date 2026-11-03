package com.quickfiles.app.storage

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/** Server keys Gotgan has accepted, by `host` or `[host]:port`. The first key a server presents is trusted; a different one later is refused. */
interface HostKeys {
    suspend fun known(host: String): String?
    suspend fun trust(host: String, key: String)
}

/** OpenSSH-style `SHA256:…` fingerprint of a base64 key blob. */
fun hostKeyFingerprint(key: String): String =
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key)))

/**
 * SFTP over one SSH session per connection, reconnected on demand. Each listing or metadata call borrows an
 * idle SFTP channel; each read or upload stream gets its own channel, closed with the stream.
 * Revisions use size and modification time, which is what SFTP servers offer.
 */
class SftpProvider(
    private val sourceId: String, address: String, private val username: String, private val password: String,
    private val hostKeys: HostKeys, private val timeoutMillis: Int = 20_000,
) : WritableStorageProvider, Closeable {
    private val address = RemoteAddress.parse(address).also { require(it.protocol == Protocol.SFTP) }
    override val capabilities = StorageCapabilities(writable = true, rangedRead = true)
    private val connecting = Mutex()
    @Volatile private var session: Session? = null
    @Volatile private var base: String = "/"
    private val idle = ArrayDeque<ChannelSftp>()
    /** Fingerprint of a server key trusted for the first time while connecting, so a connection check can show it. */
    @Volatile var newlyTrustedKey: String? = null; private set

    override suspend fun list(location: StorageLocation): List<FileEntry> = sftp { channel ->
        val path = path(location)
        val attrs = channel.stat(quote(path))
        if (!attrs.isDir) throw StorageException(StorageError.UNSUPPORTED, "폴더가 아닙니다.")
        channel.ls(quote(path)).filter { it.filename != "." && it.filename != ".." && it.filename.none { c -> c == '/' || c == '\u0000' } }.map { item ->
            val child = location.child(item.filename)
            val resolved = if (item.attrs.isLink) runCatching { channel.stat(quote("$path/${item.filename}")) }.getOrNull() else item.attrs
            if (resolved == null) FileEntry(child, item.filename, false, accessError = "연결 대상을 찾을 수 없는 링크입니다.")
            else entry(child, item.filename, resolved)
        }
    }

    override suspend fun stat(location: StorageLocation): FileEntry = sftp { channel ->
        entry(location, location.path.substringAfterLast('/').ifEmpty { address.segments.lastOrNull() ?: address.host }, channel.stat(quote(path(location))))
    }

    override suspend fun openRead(location: StorageLocation): ReadHandle = openRead(location, 0, null)

    override suspend fun openRead(location: StorageLocation, offset: Long, expected: Revision?): ReadHandle {
        require(offset >= 0)
        val current = stat(location)
        if (current.isDirectory) throw StorageException(StorageError.READ, "폴더는 읽을 수 없습니다.")
        if (expected != null && !expected.matches(current.revision)) throw StorageException(StorageError.CONFLICT, "파일이 변경되었습니다. 다시 열어 주세요.")
        val channel = open()
        try {
            val stream = runInterruptible(Dispatchers.IO) { channel.get(quote(path(location)), null as SftpProgressMonitor?, offset) }
            return ReadHandle(MappedStream(stream), current.size?.let { (it - offset).coerceAtLeast(0) }) { channel.disconnect() }
        } catch (e: Exception) { channel.disconnect(); throw mapped(e) }
    }

    override suspend fun createDirectory(location: StorageLocation) = sftp<Unit> { channel ->
        if (exists(channel, path(location))) throw StorageException(StorageError.CONFLICT, "같은 이름의 항목이 있습니다.")
        channel.mkdir(quote(path(location)))
    }

    override suspend fun put(location: StorageLocation, length: Long?, input: () -> InputStream, expected: Revision?, createOnly: Boolean, progress: (Long) -> Unit) {
        require(location.path.isNotEmpty())
        val existing = find(location)
        if (createOnly && existing != null) throw StorageException(StorageError.CONFLICT, "같은 이름의 항목이 있습니다.")
        if (!createOnly) {
            if (expected == null) throw StorageException(StorageError.CONFLICT, "덮어쓰기에는 기존 파일 버전이 필요합니다.")
            if (existing == null || !expected.matches(existing.revision)) throw StorageException(StorageError.CONFLICT, "파일이 변경되었습니다. 덮어쓰지 않았습니다.")
        }
        val context = currentCoroutineContext()
        val channel = open()
        var count = 0L
        try {
            runInterruptible(Dispatchers.IO) {
                input().use { source ->
                    channel.put(source, quote(path(location)), object : SftpProgressMonitor {
                        override fun init(op: Int, src: String?, dest: String?, max: Long) {}
                        override fun count(bytes: Long): Boolean { count += bytes; progress(count); return context[kotlinx.coroutines.Job]?.isActive != false }
                        override fun end() {}
                    }, ChannelSftp.OVERWRITE)
                }
            }
            context.ensureActive()
            if (length != null && count != length) throw StorageException(StorageError.READ, "업로드할 내용을 끝까지 읽지 못했습니다.")
        } catch (e: Exception) { throw mapped(e) } finally { channel.disconnect() }
    }

    override suspend fun move(source: StorageLocation, destination: StorageLocation, expected: Revision?) = sftp<Unit> { channel ->
        require(source.path.isNotEmpty() && destination.path.isNotEmpty())
        verify(channel, source, expected)
        // SFTP v3 rename refuses an existing target, so nothing is overwritten; check first for a clear message.
        if (exists(channel, path(destination))) throw StorageException(StorageError.CONFLICT, "대상이 존재합니다. 덮어쓰지 않았습니다.")
        channel.rename(quote(path(source)), quote(path(destination)))
    }

    override suspend fun delete(location: StorageLocation, expected: Revision?) = sftp<Unit> { channel ->
        require(location.path.isNotEmpty())
        val attrs = verify(channel, location, expected)
        // Deliberately non-recursive, like the phone provider: the engine removes each child itself.
        if (attrs.isDir) channel.rmdir(quote(path(location))) else channel.rm(quote(path(location)))
    }

    override suspend fun availableBytes(location: StorageLocation): Long? = runCatching {
        sftp { channel -> channel.statVFS(quote(path(location))).let { it.availForNonRoot * 1024 } }
    }.getOrNull()

    override fun close() {
        synchronized(idle) { idle.forEach { it.disconnect() }; idle.clear() }
        session?.disconnect(); session = null
    }

    private fun verify(channel: ChannelSftp, location: StorageLocation, expected: Revision?): SftpATTRS {
        val attrs = channel.stat(quote(path(location)))
        if (expected != null && !expected.matches(entry(location, "", attrs).revision)) throw StorageException(StorageError.CONFLICT, "작업 시작 후 파일이 변경되었습니다.")
        return attrs
    }

    private fun exists(channel: ChannelSftp, path: String) = try { channel.lstat(quote(path)); true }
        catch (e: SftpException) { if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e }

    private fun entry(location: StorageLocation, name: String, attrs: SftpATTRS): FileEntry {
        val modified = attrs.mTime.toLong() * 1000
        val size = if (attrs.isDir) null else attrs.size
        return FileEntry(location, name, attrs.isDir, size, modified, etag = "\"sftp-${attrs.size}-${attrs.mTime}\"")
    }

    private fun path(location: StorageLocation): String = if (location.path.isEmpty()) base else base.trimEnd('/') + "/" + location.path

    private suspend fun <T> sftp(block: (ChannelSftp) -> T): T {
        val channel = borrow()
        var reusable = false
        try {
            val result = runInterruptible(Dispatchers.IO) { block(channel) }
            reusable = true
            return result
        } catch (e: SftpException) {
            reusable = true // a refused request leaves the channel usable
            throw mapped(e)
        } catch (e: StorageException) { reusable = true; throw e }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw mapped(e) }
        finally {
            if (reusable && channel.isConnected) synchronized(idle) { if (idle.size < 4) idle.addLast(channel) else channel.disconnect() }
            else channel.disconnect()
        }
    }

    private suspend fun borrow(): ChannelSftp = synchronized(idle) { idle.removeFirstOrNull() }?.takeIf { it.isConnected && session?.isConnected == true } ?: open()

    private suspend fun open(): ChannelSftp {
        repeat(2) { attempt ->
            val current = session()
            try {
                return runInterruptible(Dispatchers.IO) { (current.openChannel("sftp") as ChannelSftp).apply { connect(timeoutMillis) } }
            } catch (e: JSchException) {
                // The server dropped the session (sleep, network change); reconnect once.
                if (attempt == 1 || current.isConnected) throw mapped(e)
                current.disconnect(); if (session === current) session = null
            }
        }
        error("unreachable")
    }

    private suspend fun session(): Session = connecting.withLock {
        session?.takeIf { it.isConnected }?.let { return it }
        val host = address.hostKey
        val known = hostKeys.known(host)
        var presented: String? = null
        val jsch = JSch()
        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(h: String?, key: ByteArray): Int {
                val encoded = Base64.getEncoder().encodeToString(key); presented = encoded
                return if (known == null || known == encoded) HostKeyRepository.OK else HostKeyRepository.CHANGED
            }
            override fun add(hostkey: HostKey?, ui: UserInfo?) {}
            override fun remove(host: String?, type: String?) {}
            override fun remove(host: String?, type: String?, key: ByteArray?) {}
            override fun getKnownHostsRepositoryID() = "gotgan"
            override fun getHostKey(): Array<HostKey> = getHostKey(host, null)
            // Offering the known key's type makes the server present that same key again.
            override fun getHostKey(h: String?, type: String?): Array<HostKey> =
                known?.let { runCatching { arrayOf(HostKey(host, Base64.getDecoder().decode(it))) }.getOrNull() } ?: emptyArray()
        }
        val created = jsch.getSession(username, address.host, address.port).apply {
            setPassword(password)
            userInfo = PasswordPrompt(password)
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", "password,keyboard-interactive")
            timeout = timeoutMillis
            serverAliveInterval = 30_000
        }
        try {
            runInterruptible(Dispatchers.IO) { created.connect(timeoutMillis) }
        } catch (e: JSchException) {
            created.disconnect()
            if (known != null && presented != null && presented != known) throw StorageException(StorageError.TLS,
                "서버 키가 바뀌었습니다(${hostKeyFingerprint(presented!!)}). 서버를 교체한 것이 아니라면 연결하지 마세요. 맞다면 연결을 삭제하고 다시 추가해 주세요.", e)
            throw mapped(e)
        }
        if (known == null) presented?.let { hostKeys.trust(host, it); newlyTrustedKey = hostKeyFingerprint(it) }
        base = if (address.segments.isEmpty()) runCatching {
            runInterruptible(Dispatchers.IO) { (created.openChannel("sftp") as ChannelSftp).run { connect(timeoutMillis); try { home } finally { disconnect() } } }
        }.getOrDefault("/") else "/" + address.segments.joinToString("/")
        session = created
        created
    }

    private class PasswordPrompt(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword() = password
        override fun promptPassword(message: String?) = true
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(destination: String?, name: String?, instruction: String?, prompt: Array<out String>, echo: BooleanArray) =
            Array(prompt.size) { password }
    }

    /** SFTP read errors surface as plain IOExceptions from the stream; keep the cause. */
    private class MappedStream(private val inner: InputStream) : InputStream() {
        override fun read() = guard { inner.read() }
        override fun read(b: ByteArray, off: Int, len: Int) = guard { inner.read(b, off, len) }
        override fun available() = guard { inner.available() }
        override fun close() = inner.close()
        private inline fun <T> guard(block: () -> T): T = try { block() } catch (e: IOException) { throw StorageException(StorageError.NETWORK, "파일을 읽는 중 연결이 끊어졌습니다.", e) }
    }

    companion object {
        /** JSch expands `*` and `?` in paths; escape them so names are taken literally. */
        fun quote(path: String): String = buildString { path.forEach { if (it == '\\' || it == '*' || it == '?') append('\\'); append(it) } }

        fun mapped(e: Exception): Exception = when (e) {
            is StorageException, is CancellationException -> e
            is SftpException -> when (e.id) {
                ChannelSftp.SSH_FX_NO_SUCH_FILE -> StorageException(StorageError.NOT_FOUND, "항목을 찾을 수 없습니다.", e)
                ChannelSftp.SSH_FX_PERMISSION_DENIED -> StorageException(StorageError.ACCESS, "서버에서 권한이 없습니다.", e)
                ChannelSftp.SSH_FX_OP_UNSUPPORTED -> StorageException(StorageError.UNSUPPORTED, "서버가 이 작업을 지원하지 않습니다.", e)
                else -> StorageException(StorageError.CONFLICT, "서버가 요청을 거부했습니다. 같은 이름의 항목이나 남은 공간을 확인해 주세요.", e)
            }
            is JSchException -> when {
                e.message.orEmpty().contains("Auth", ignoreCase = true) -> StorageException(StorageError.AUTH, "사용자 이름이나 비밀번호가 맞지 않습니다.", e)
                e.message.orEmpty().contains("HostKey", ignoreCase = true) -> StorageException(StorageError.TLS, "서버 키를 확인할 수 없습니다.", e)
                else -> StorageException(StorageError.NETWORK, "서버에 연결하지 못했습니다. 주소와 네트워크를 확인한 뒤 다시 시도해 주세요.", e)
            }
            is IOException -> StorageException(StorageError.NETWORK, "서버에 연결하지 못했습니다. 주소와 네트워크를 확인한 뒤 다시 시도해 주세요.", e)
            else -> StorageException(StorageError.INVALID_RESPONSE, "서버 응답을 처리하지 못했습니다.", e)
        }
    }
}
