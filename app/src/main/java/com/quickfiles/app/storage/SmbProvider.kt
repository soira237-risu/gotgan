package com.quickfiles.app.storage

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
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
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * One SMB2/3 share (`smb://host/share/folder`) over a single authenticated connection, reconnected on demand.
 * Revisions use the server's change time and size; change time moves on every write, so it works as a version tag.
 */
class SmbProvider(
    private val sourceId: String, address: String, username: String, private val password: String,
    private val timeoutSeconds: Long = 30,
) : WritableStorageProvider, Closeable {
    private val address = RemoteAddress.parse(address).also { require(it.protocol == Protocol.SMB) }
    private val shareName = this.address.segments.first()
    private val base = this.address.segments.drop(1)
    private val user = username.substringAfter('\\')
    private val domain = username.substringBefore('\\', "").ifEmpty { null }
    override val capabilities = StorageCapabilities(writable = true, rangedRead = true)
    private val connecting = Mutex()
    @Volatile private var client: SMBClient? = null
    @Volatile private var connection: Connection? = null
    @Volatile private var share: DiskShare? = null

    override suspend fun list(location: StorageLocation): List<FileEntry> = smb { share ->
        val path = path(location)
        if (!share.getFileInformation(path).standardInformation.isDirectory) throw StorageException(StorageError.UNSUPPORTED, "폴더가 아닙니다.")
        share.list(path).filter { it.fileName != "." && it.fileName != ".." }.map { item ->
            val directory = item.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            entry(location.child(item.fileName), item.fileName, directory, item.endOfFile, item.lastWriteTime.toEpochMillis(), item.changeTime.windowsTimeStamp)
        }
    }

    override suspend fun stat(location: StorageLocation): FileEntry = smb { share ->
        val info = share.getFileInformation(path(location))
        entry(location, location.path.substringAfterLast('/').ifEmpty { base.lastOrNull() ?: shareName }, info.standardInformation.isDirectory,
            info.standardInformation.endOfFile, info.basicInformation.lastWriteTime.toEpochMillis(), info.basicInformation.changeTime.windowsTimeStamp)
    }

    override suspend fun openRead(location: StorageLocation): ReadHandle = openRead(location, 0, null)

    override suspend fun openRead(location: StorageLocation, offset: Long, expected: Revision?): ReadHandle {
        require(offset >= 0)
        val current = stat(location)
        if (current.isDirectory) throw StorageException(StorageError.READ, "폴더는 읽을 수 없습니다.")
        if (expected != null && !expected.matches(current.revision)) throw StorageException(StorageError.CONFLICT, "파일이 변경되었습니다. 다시 열어 주세요.")
        val file = smb { share ->
            share.openFile(path(location), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        }
        return ReadHandle(FileStream(file, offset), current.size?.let { (it - offset).coerceAtLeast(0) }) { runCatching { file.closeNoWait() } }
    }

    override suspend fun createDirectory(location: StorageLocation) = smb<Unit> { share ->
        try { share.mkdir(path(location)) } catch (e: SMBApiException) {
            if (e.status == NtStatus.STATUS_OBJECT_NAME_COLLISION) throw StorageException(StorageError.CONFLICT, "같은 이름의 항목이 있습니다.", e)
            throw e
        }
    }

    override suspend fun put(location: StorageLocation, length: Long?, input: () -> InputStream, expected: Revision?, createOnly: Boolean, progress: (Long) -> Unit) {
        require(location.path.isNotEmpty())
        if (!createOnly) {
            if (expected == null) throw StorageException(StorageError.CONFLICT, "덮어쓰기에는 기존 파일 버전이 필요합니다.")
            val existing = find(location)
            if (existing == null || !expected.matches(existing.revision)) throw StorageException(StorageError.CONFLICT, "파일이 변경되었습니다. 덮어쓰지 않았습니다.")
        }
        val context = currentCoroutineContext()
        // FILE_CREATE fails on an existing name, so a create-only upload can never replace a file.
        val file = smb { share ->
            try {
                share.openFile(path(location), EnumSet.of(AccessMask.GENERIC_WRITE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), EnumSet.noneOf(SMB2ShareAccess::class.java),
                    if (createOnly) SMB2CreateDisposition.FILE_CREATE else SMB2CreateDisposition.FILE_OVERWRITE, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
            } catch (e: SMBApiException) {
                if (e.status == NtStatus.STATUS_OBJECT_NAME_COLLISION) throw StorageException(StorageError.CONFLICT, "같은 이름의 항목이 있습니다.", e)
                throw e
            }
        }
        try {
            var count = 0L
            runInterruptible(Dispatchers.IO) {
                input().use { source ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        context.ensureActive()
                        val n = source.read(buffer)
                        if (n < 0) break
                        var written = 0
                        while (written < n) written += file.write(buffer, count + written, written, n - written).toInt()
                        count += n; progress(count)
                    }
                }
            }
            if (length != null && count != length) throw StorageException(StorageError.READ, "업로드할 내용을 끝까지 읽지 못했습니다.")
        } catch (e: Exception) { throw mapped(e) } finally { runCatching { file.close() } }
    }

    override suspend fun move(source: StorageLocation, destination: StorageLocation, expected: Revision?) {
        require(source.path.isNotEmpty() && destination.path.isNotEmpty())
        verify(source, expected)
        smb<Unit> { share ->
            share.open(path(source), EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { entry ->
                try { entry.rename(path(destination), false) } catch (e: SMBApiException) {
                    if (e.status == NtStatus.STATUS_OBJECT_NAME_COLLISION) throw StorageException(StorageError.CONFLICT, "대상이 존재합니다. 덮어쓰지 않았습니다.", e)
                    throw e
                }
            }
        }
    }

    override suspend fun delete(location: StorageLocation, expected: Revision?) {
        require(location.path.isNotEmpty())
        val current = verify(location, expected)
        // Deliberately non-recursive, like the phone provider: the engine removes each child itself.
        smb<Unit> { share -> if (current.isDirectory) share.rmdir(path(location), false) else share.rm(path(location)) }
    }

    override suspend fun availableBytes(location: StorageLocation): Long? = runCatching { smb { it.shareInformation.callerFreeSpace } }.getOrNull()

    override fun close() {
        runCatching { share?.close() }; runCatching { connection?.close() }; runCatching { client?.close() }
        share = null; connection = null; client = null
    }

    private suspend fun verify(location: StorageLocation, expected: Revision?): FileEntry = stat(location).also {
        if (expected != null && !expected.matches(it.revision)) throw StorageException(StorageError.CONFLICT, "작업 시작 후 파일이 변경되었습니다.")
    }

    private fun entry(location: StorageLocation, name: String, directory: Boolean, size: Long, modified: Long, changed: Long) =
        FileEntry(location, name, directory, if (directory) null else size, modified, etag = "\"smb-$changed-$size\"")

    /** Share-relative path with backslashes, as SMB expects. */
    private fun path(location: StorageLocation): String =
        (base + location.path.split('/').filter { it.isNotEmpty() }).joinToString("\\")

    private suspend fun <T> smb(block: (DiskShare) -> T): T {
        repeat(2) { attempt ->
            val current = share()
            try {
                return runInterruptible(Dispatchers.IO) { block(current) }
            } catch (e: CancellationException) { throw e }
            catch (e: StorageException) { throw e }
            catch (e: SMBApiException) { throw mapped(e) }
            catch (e: Exception) {
                // A dropped connection (sleep, network change) is retried once on a fresh one.
                if (attempt == 1 || current.isConnected) throw mapped(e)
                close()
            }
        }
        error("unreachable")
    }

    private suspend fun share(): DiskShare = connecting.withLock {
        share?.takeIf { it.isConnected }?.let { return it }
        close()
        try {
            runInterruptible(Dispatchers.IO) {
                val created = SMBClient(SmbConfig.builder().withTimeout(timeoutSeconds, TimeUnit.SECONDS).withSoTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .withDfsEnabled(false).withMultiProtocolNegotiate(true).build())
                client = created
                val opened = created.connect(address.host, address.port).also { connection = it }
                val session = opened.authenticate(AuthenticationContext(user, password.toCharArray(), domain))
                (session.connectShare(shareName) as? DiskShare ?: throw StorageException(StorageError.UNSUPPORTED, "파일 공유가 아닌 공유 이름입니다.")).also { share = it }
            }
        } catch (e: CancellationException) { close(); throw e }
        catch (e: Exception) { close(); throw mapped(e) }
    }

    /** Sequential reads from an open SMB file starting at [position]. */
    private class FileStream(private val file: SmbFile, private var position: Long) : InputStream() {
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = try { file.read(b, position, off, len) } catch (e: SMBRuntimeException) { throw StorageException(StorageError.NETWORK, "파일을 읽는 중 연결이 끊어졌습니다.", e) }
            if (n <= 0) return -1
            position += n; return n
        }
        override fun close() { runCatching { file.close() } }
    }

    companion object {
        fun mapped(e: Exception): Exception = when (e) {
            is StorageException, is CancellationException -> e
            is SMBApiException -> when (e.status) {
                NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_DELETE_PENDING ->
                    StorageException(StorageError.NOT_FOUND, "항목을 찾을 수 없습니다.", e)
                NtStatus.STATUS_ACCESS_DENIED -> StorageException(StorageError.ACCESS, "서버에서 권한이 없습니다.", e)
                NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCOUNT_DISABLED -> StorageException(StorageError.AUTH, "사용자 이름이나 비밀번호가 맞지 않습니다.", e)
                NtStatus.STATUS_BAD_NETWORK_NAME -> StorageException(StorageError.NOT_FOUND, "공유 폴더를 찾을 수 없습니다. 주소의 공유 이름을 확인해 주세요.", e)
                NtStatus.STATUS_DISK_FULL -> StorageException(StorageError.NO_SPACE, "서버 공간이 부족합니다.", e)
                NtStatus.STATUS_OBJECT_NAME_COLLISION -> StorageException(StorageError.CONFLICT, "같은 이름의 항목이 있습니다.", e)
                NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> StorageException(StorageError.CONFLICT, "폴더가 비어 있지 않습니다.", e)
                NtStatus.STATUS_SHARING_VIOLATION -> StorageException(StorageError.BUSY, "다른 프로그램이 이 파일을 사용 중입니다.", e)
                else -> StorageException(StorageError.INVALID_RESPONSE, "서버가 요청을 거부했습니다(${e.status}).", e)
            }
            is IOException, is SMBRuntimeException -> StorageException(StorageError.NETWORK, "서버에 연결하지 못했습니다. 주소와 네트워크를 확인한 뒤 다시 시도해 주세요.", e)
            else -> StorageException(StorageError.INVALID_RESPONSE, "서버 응답을 처리하지 못했습니다.", e)
        }
    }
}
