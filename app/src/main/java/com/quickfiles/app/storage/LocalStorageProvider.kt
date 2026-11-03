package com.quickfiles.app.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import kotlin.coroutines.coroutineContext

class LocalStorageProvider(
    root: File, private val sourceId: String = LOCAL_SOURCE, private val rootName: String = "휴대폰 저장소",
    private val writable: Boolean = true, private val canRead: () -> Boolean,
) : WritableStorageProvider {
    constructor(root: File, canRead: () -> Boolean) : this(root, LOCAL_SOURCE, canRead = canRead)
    private val root = root.canonicalFile
    override val capabilities = StorageCapabilities(writable = writable, rangedRead = true)

    private fun resolve(location: StorageLocation): File {
        if (!canRead()) throw StorageException(StorageError.ACCESS, if (sourceId == LOCAL_SOURCE) "휴대폰의 ‘모든 파일 접근’ 권한을 허용해 주세요." else "‘$rootName’에 접근할 수 없습니다. 연결 상태와 ‘모든 파일 접근’ 권한을 확인해 주세요.")
        require(location.sourceId == sourceId)
        var segment = root
        location.path.split('/').filter { it.isNotEmpty() }.forEach {
            segment = File(segment, it)
            if (Files.isSymbolicLink(segment.toPath())) throw StorageException(StorageError.ACCESS, "심볼릭 링크는 파일 작업에서 지원하지 않습니다.")
        }
        val file = File(root, location.path).canonicalFile
        if (file != root && !file.toPath().startsWith(root.toPath())) {
            throw StorageException(StorageError.ACCESS, "공유 저장소 밖의 경로에는 접근할 수 없습니다.")
        }
        val relative = file.relativeTo(root).invariantSeparatorsPath
        if (listOf("Android/data", "Android/obb").any { relative == it || relative.startsWith("$it/") }) {
            throw StorageException(StorageError.ACCESS, "Android에서 보호하는 폴더입니다.")
        }
        return file
    }

    private fun entry(file: File, location: StorageLocation) = FileEntry(
        location, if (location.path.isEmpty()) rootName else file.name, file.isDirectory,
        if (file.isDirectory) null else file.length(), file.lastModified(),
    )

    override suspend fun list(location: StorageLocation): List<FileEntry> = withContext(Dispatchers.IO) {
        StorageReadMetrics.listed(location.sourceId)
        val folder = resolve(location)
        if (!folder.exists()) throw StorageException(StorageError.NOT_FOUND, "폴더를 찾을 수 없습니다.")
        val children = folder.listFiles() ?: throw StorageException(StorageError.ACCESS, "이 폴더를 읽을 권한이 없습니다.")
        children.map { file ->
            coroutineContext.ensureActive()
            entry(file, location.child(file.name))
        }
    }

    override suspend fun stat(location: StorageLocation): FileEntry = withContext(Dispatchers.IO) {
        val file = resolve(location)
        if (!file.exists()) throw StorageException(StorageError.NOT_FOUND, "파일을 찾을 수 없습니다.")
        entry(file, location)
    }

    override suspend fun openRead(location: StorageLocation): ReadHandle = withContext(Dispatchers.IO) {
        val file = resolve(location)
        try { ReadHandle(file.inputStream(), file.length()) }
        catch (e: FileNotFoundException) { throw StorageException(StorageError.ACCESS, "파일을 읽을 수 없습니다. 권한을 확인해 주세요.", e) }
    }

    override suspend fun openRead(location: StorageLocation, offset: Long, expected: Revision?): ReadHandle = withContext(Dispatchers.IO) {
        require(offset >= 0)
        verify(location, expected)
        val file = resolve(location)
        val reader = RandomAccessFile(file, "r")
        if (offset > reader.length()) { reader.close(); throw StorageException(StorageError.CONFLICT, "파일 크기가 변경되었습니다.") }
        reader.seek(offset)
        ReadHandle(object : InputStream() {
            override fun read() = reader.read()
            override fun read(b: ByteArray, off: Int, len: Int) = reader.read(b, off, len)
            override fun close() = reader.close()
        }, reader.length() - offset)
    }

    private suspend fun verify(location: StorageLocation, expected: Revision?) {
        if (expected != null && !expected.matches(stat(location).revision)) throw StorageException(StorageError.CONFLICT, "원본 파일이 변경되어 작업을 중단했습니다.")
    }
    private fun mutableFile(location: StorageLocation): File {
        require(location.path.isNotEmpty()) { "저장소 루트는 변경할 수 없습니다." }
        if (!writable) throw StorageException(StorageError.ACCESS, "‘$rootName’은 읽기 전용으로 연결되어 있습니다.")
        return resolve(location)
    }
    override suspend fun createDirectory(location: StorageLocation): Unit = withContext(Dispatchers.IO) {
        try { Files.createDirectory(mutableFile(location).toPath()); Unit }
        catch (e: FileAlreadyExistsException) { throw StorageException(StorageError.CONFLICT, "같은 이름의 항목이 있습니다.", e) }
    }
    override suspend fun put(location: StorageLocation, length: Long?, input: () -> InputStream, expected: Revision?, createOnly: Boolean, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        verify(location, expected)
        val file = mutableFile(location)
        if (length != null && file.parentFile!!.usableSpace < length) throw StorageException(StorageError.NO_SPACE, "저장 공간이 부족합니다.")
        val options = if (createOnly) arrayOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) else arrayOf(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        try {
            Files.newOutputStream(file.toPath(), *options).use { output ->
                input().use { source ->
                    val buffer = ByteArray(128 * 1024)
                    var count = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (file.parentFile!!.usableSpace < read) throw StorageException(StorageError.NO_SPACE, "저장 공간이 부족합니다.")
                        output.write(buffer, 0, read); count += read; progress(count)
                    }
                    if (length != null && count != length) throw StorageException(StorageError.READ, "파일을 끝까지 받지 못했습니다.")
                }
            }
        } catch (e: FileAlreadyExistsException) { throw StorageException(StorageError.CONFLICT, "같은 이름의 항목이 있습니다.", e) }
    }
    override suspend fun move(source: StorageLocation, destination: StorageLocation, expected: Revision?): Unit = withContext(Dispatchers.IO) {
        verify(source, expected)
        try { Files.move(mutableFile(source).toPath(), mutableFile(destination).toPath()); Unit }
        catch (e: FileAlreadyExistsException) { throw StorageException(StorageError.CONFLICT, "같은 이름의 항목이 있습니다.", e) }
        catch (e: NoSuchFileException) { throw StorageException(StorageError.NOT_FOUND, "항목을 찾을 수 없습니다.", e) }
    }
    override suspend fun delete(location: StorageLocation, expected: Revision?): Unit = withContext(Dispatchers.IO) {
        verify(location, expected)
        // Directory deletion is deliberately non-recursive. The journalled engine enumerates each child.
        Files.delete(mutableFile(location).toPath())
    }
    override suspend fun availableBytes(location: StorageLocation): Long = withContext(Dispatchers.IO) { resolve(location).let { if (it.isDirectory) it else it.parentFile!! }.usableSpace }
    suspend fun fileForRead(location: StorageLocation): File = withContext(Dispatchers.IO) {
        val file = resolve(location)
        if (!file.isFile) throw StorageException(StorageError.READ, "파일을 읽을 수 없습니다.")
        file
    }
    suspend fun channel(location: StorageLocation): java.nio.channels.SeekableByteChannel = withContext(Dispatchers.IO) { java.io.FileInputStream(fileForRead(location)).channel }

    /** Only the journal's uniquely named partial files may be truncated/appended for verified resume. */
    suspend fun appendStage(location: StorageLocation, offset: Long, expected: Revision, remaining: Long?, input: () -> InputStream, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        require(location.path.substringAfterLast('/').matches(Regex("\\.fileexplorer-[a-f0-9-]{36}\\.part")))
        require(offset >= 0)
        verify(location, expected)
        val file = mutableFile(location)
        if (remaining != null && file.parentFile!!.usableSpace < remaining) throw StorageException(StorageError.NO_SPACE, "이어받을 저장 공간이 부족합니다.")
        RandomAccessFile(file, "rw").use { output ->
            require(output.length() >= offset)
            output.setLength(offset); output.seek(offset)
            input().use { stream ->
                val buffer = ByteArray(128 * 1024); var count = 0L
                while (true) {
                    coroutineContext.ensureActive(); val n = stream.read(buffer); if (n < 0) break
                    if (file.parentFile!!.usableSpace < n) throw StorageException(StorageError.NO_SPACE, "저장 공간이 부족합니다.")
                    output.write(buffer, 0, n); count += n; progress(offset + count)
                }
                if (remaining != null && count != remaining) throw StorageException(StorageError.NETWORK, "이어받기가 중단되었습니다.")
                output.fd.sync()
            }
        }
    }
}
