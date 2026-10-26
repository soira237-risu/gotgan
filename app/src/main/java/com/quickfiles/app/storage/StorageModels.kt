package com.quickfiles.app.storage

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.util.Locale

const val LOCAL_SOURCE = "phone"

data class StorageLocation(val sourceId: String, val path: String = "") {
    init {
        require(path.isEmpty() || path.split('/').all {
            it.isNotEmpty() && it != "." && it != ".." && !it.contains('\u0000') && !it.contains('\\')
        }) { "유효하지 않은 경로입니다." }
    }
    fun child(name: String) = StorageLocation(sourceId, if (path.isEmpty()) name else "$path/$name")
    fun parent() = if (path.isEmpty()) null else copy(path = path.substringBeforeLast('/', ""))
    val key: String get() = "$sourceId:$path"
}

data class FileEntry(
    val location: StorageLocation,
    val name: String,
    val isDirectory: Boolean,
    val size: Long? = null,
    val modified: Long? = null,
    val etag: String? = null,
    val accessError: String? = null,
) {
    val id: String get() = location.key
    val extension: String get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val isImage: Boolean get() = !isDirectory && FileFormats.isImage(name)
    val isText: Boolean get() = !isDirectory && FileFormats.isText(name)
    val isEditableText: Boolean get() = !isDirectory && FileFormats.isEditableText(name)
    val isPdf: Boolean get() = !isDirectory && extension == "pdf"
    val isArchive: Boolean get() = !isDirectory && extension in listOf("zip", "cbz")
    val isVideo: Boolean get() = !isDirectory && extension in FileFormats.videos
    val isAudio: Boolean get() = !isDirectory && extension in FileFormats.audio
    val revision: Revision get() = Revision(size, modified, etag)
}

data class Revision(val size: Long? = null, val modified: Long? = null, val etag: String? = null) {
    val strongEtag: String? get() = etag?.takeIf { it.startsWith('"') && !it.startsWith("W/") }
    fun matches(other: Revision) = if (strongEtag != null) strongEtag == other.strongEtag else size == other.size && modified == other.modified
}

data class StorageCapabilities(val writable: Boolean = false, val serverCopy: Boolean = false, val rangedRead: Boolean = false)

enum class SortOrder(val label: String) { NAME("이름"), DATE("날짜"), SIZE("크기") }
enum class SortDirection(val label: String) { ASCENDING("오름차순"), DESCENDING("내림차순") }
data class SortSpec(val order: SortOrder = SortOrder.NAME, val direction: SortDirection = legacyDirection(order))
fun legacyDirection(order: SortOrder) = if (order == SortOrder.NAME) SortDirection.ASCENDING else SortDirection.DESCENDING

fun sortedEntries(entries: List<FileEntry>, order: SortOrder): List<FileEntry> = sortedEntries(entries, SortSpec(order))
fun sortedEntries(entries: List<FileEntry>, spec: SortSpec): List<FileEntry> {
    val names = compareBy<FileEntry> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name }
    val fields = when (spec.order) {
        SortOrder.NAME -> names
        SortOrder.DATE -> compareBy<FileEntry> { it.modified ?: Long.MIN_VALUE }.then(names)
        SortOrder.SIZE -> compareBy<FileEntry> { it.size ?: Long.MIN_VALUE }.then(names)
    }
    return entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.then(if (spec.direction == SortDirection.DESCENDING) fields.reversed() else fields))
}

enum class StorageError { ACCESS, AUTH, TLS, NOT_FOUND, NETWORK, UNSUPPORTED, TOO_LARGE, INVALID_RESPONSE, READ, CONFLICT, NO_SPACE, BUSY }

class StorageException(val kind: StorageError, message: String, cause: Throwable? = null) : IOException(message, cause)

class ReadHandle(val stream: InputStream, val length: Long?, private val cleanup: () -> Unit = {}) : Closeable {
    override fun close() { try { stream.close() } finally { cleanup() } }
}

interface StorageProvider {
    val capabilities: StorageCapabilities get() = StorageCapabilities()
    suspend fun list(location: StorageLocation): List<FileEntry>
    suspend fun stat(location: StorageLocation): FileEntry
    suspend fun openRead(location: StorageLocation): ReadHandle
    suspend fun openRead(location: StorageLocation, offset: Long, expected: Revision?): ReadHandle {
        require(offset >= 0)
        if (expected != null && !expected.matches(stat(location).revision)) throw StorageException(StorageError.CONFLICT, "파일이 변경되었습니다. 다시 열어 주세요.")
        val handle = openRead(location)
        try {
            var remaining = offset
            val buffer = ByteArray(32 * 1024)
            while (remaining > 0) {
                coroutineContext.ensureActive()
                val read = handle.stream.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (read < 0) throw StorageException(StorageError.READ, "파일 범위를 읽지 못했습니다.")
                remaining -= read
            }
            return handle
        } catch (e: Exception) { handle.close(); throw e }
    }
}

interface WritableStorageProvider : StorageProvider {
    suspend fun createDirectory(location: StorageLocation)
    suspend fun put(location: StorageLocation, length: Long?, input: () -> InputStream, expected: Revision? = null, createOnly: Boolean = true, progress: (Long) -> Unit = {})
    suspend fun move(source: StorageLocation, destination: StorageLocation, expected: Revision? = null)
    suspend fun copy(source: StorageLocation, destination: StorageLocation, expected: Revision? = null) { throw StorageException(StorageError.UNSUPPORTED, "서버 내부 복사를 지원하지 않습니다.") }
    suspend fun delete(location: StorageLocation, expected: Revision? = null)
    suspend fun availableBytes(location: StorageLocation): Long? = null
}

suspend fun StorageProvider.find(location: StorageLocation): FileEntry? = try { stat(location) } catch (e: StorageException) { if (e.kind == StorageError.NOT_FOUND) null else throw e }
fun requireFileName(name: String) { require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' || it.code < 32 }) { "파일 이름을 확인해 주세요." } }

/** Bounded Java 8-compatible read for minSdk 30; cancellation also covers decompression loops. */
suspend fun InputStream.readAtMost(maximum: Int): ByteArray {
    require(maximum >= 0)
    val output = java.io.ByteArrayOutputStream(minOf(maximum, 32 * 1024))
    val buffer = ByteArray(minOf(maximum.coerceAtLeast(1), 32 * 1024))
    while (output.size() < maximum) {
        coroutineContext.ensureActive()
        val n = read(buffer, 0, minOf(buffer.size, maximum - output.size()))
        if (n < 0) break
        if (n == 0) continue
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}

fun readableError(error: Throwable): String = when (error) {
    is StorageException -> error.message ?: "파일을 읽을 수 없습니다."
    is SecurityException -> "접근 권한이 없습니다. 휴대폰 권한 또는 저장소 계정을 확인해 주세요."
    is IllegalArgumentException -> error.message ?: "파일 또는 입력값을 확인해 주세요."
    else -> "파일을 읽지 못했습니다. 연결과 파일 상태를 확인한 뒤 다시 시도해 주세요."
}
