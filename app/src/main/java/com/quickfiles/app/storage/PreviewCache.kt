package com.quickfiles.app.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

const val MIB = 1024L * 1024
const val IMAGE_LIMIT = 64 * MIB
const val CACHE_LIMIT = 256 * MIB
const val TEXT_LIMIT = 1 * MIB

data class TextPreview(val bytes: ByteArray, val truncated: Boolean) {
    fun decode(cp949: Boolean): String {
        if (cp949) return bytes.toString(charset("MS949"))
        val (offset, encoding) = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> 3 to Charsets.UTF_8
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> 2 to Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> 2 to Charsets.UTF_16BE
            else -> 0 to Charsets.UTF_8
        }
        var length = bytes.size - offset
        // Truncation can cut a UTF-16 code unit; do not display a spurious replacement character.
        if (truncated && encoding != Charsets.UTF_8) length -= length % 2
        return String(bytes, offset, length, encoding)
    }
}

class PreviewCache(private val root: File, private val maximum: Long = CACHE_LIMIT) {
    private val mutex = Mutex()
    suspend fun image(provider: StorageProvider, entry: FileEntry, progress: (Long, Long?) -> Unit): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = provider.stat(entry.location)
            if ((current.size ?: 0) > IMAGE_LIMIT) throw StorageException(StorageError.TOO_LARGE, "원격 이미지 미리보기는 파일당 64MiB까지 지원합니다.")
            val source = File(root, hash(entry.location.sourceId)).apply { mkdirs() }
            val version = current.etag ?: current.modified?.let { "$it:${current.size}" }
            val target = File(source, hash("${entry.location.path}:$version"))
            if (version != null && target.isFile) { target.setLastModified(System.currentTimeMillis()); return@withLock target }
            // One viewer at a time; old partial files are never treated as complete cache entries.
            prune(IMAGE_LIMIT)
            val partial = File.createTempFile("preview-", ".part", source)
            try {
                provider.openRead(entry.location).use { handle ->
                    if ((handle.length ?: 0) > IMAGE_LIMIT) throw StorageException(StorageError.TOO_LARGE, "원격 이미지 미리보기는 파일당 64MiB까지 지원합니다.")
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var count = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = handle.stream.read(buffer)
                            if (read < 0) break
                            count += read
                            if (count > IMAGE_LIMIT) throw StorageException(StorageError.TOO_LARGE, "원격 이미지 미리보기는 파일당 64MiB까지 지원합니다.")
                            output.write(buffer, 0, read)
                            progress(count, handle.length ?: current.size)
                        }
                        if (handle.length != null && count != handle.length) throw StorageException(StorageError.READ, "파일을 끝까지 받지 못했습니다. 다시 시도해 주세요.")
                    }
                }
                coroutineContext.ensureActive()
                if (target.exists() && !target.delete()) throw StorageException(StorageError.READ, "임시 캐시를 갱신할 수 없습니다.")
                if (!partial.renameTo(target)) throw StorageException(StorageError.READ, "임시 캐시를 저장하지 못했습니다.")
                target.setLastModified(System.currentTimeMillis())
                target
            } finally { partial.delete() }
        }
    }

    suspend fun clearSource(sourceId: String) = withContext(Dispatchers.IO) { mutex.withLock {
        // Computed directory is a hex digest under our private cache root, never a user path.
        val directory = File(root, hash(sourceId))
        directory.deleteRecursively()
    } }

    private fun prune(reserve: Long) {
        root.mkdirs()
        val files = root.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
        var total = files.sumOf { it.length() }
        files.forEach { file ->
            if (file.extension == "part" || total + reserve > maximum) {
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
        if (total + reserve > maximum) throw StorageException(StorageError.READ, "미리보기 캐시 공간을 확보하지 못했습니다.")
    }

    companion object {
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        suspend fun text(provider: StorageProvider, entry: FileEntry, progress: (Long, Long?) -> Unit): TextPreview = withContext(Dispatchers.IO) {
            provider.openRead(entry.location).use { handle ->
                readText(handle.stream) { read -> progress(read, handle.length) }
            }
        }

        internal suspend fun readText(stream: InputStream, progress: (Long) -> Unit = {}): TextPreview {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (output.size() <= TEXT_LIMIT) {
                coroutineContext.ensureActive()
                val length = minOf(buffer.size.toLong(), TEXT_LIMIT + 1 - output.size()).toInt()
                val read = stream.read(buffer, 0, length)
                if (read < 0) break
                output.write(buffer, 0, read)
                progress(output.size().toLong())
            }
            val bytes = output.toByteArray()
            return TextPreview(if (bytes.size > TEXT_LIMIT) bytes.copyOf(TEXT_LIMIT.toInt()) else bytes, bytes.size > TEXT_LIMIT)
        }
    }
}
