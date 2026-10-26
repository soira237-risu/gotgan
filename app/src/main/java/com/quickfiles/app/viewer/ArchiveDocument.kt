package com.quickfiles.app.viewer

import com.quickfiles.app.storage.*
import kotlinx.coroutines.*
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.Closeable
import java.io.InputStream
import java.nio.channels.SeekableByteChannel

object ArchiveSafety {
    const val MAX_ENTRIES = 100000
    const val MAX_OUTPUT = 20L * 1024 * 1024 * 1024
    fun path(name: String): String {
        require(name.isNotBlank() && !name.startsWith('/') && '\\' !in name && ':' !in name && '\u0000' !in name) { "압축파일에 안전하지 않은 경로가 있습니다." }
        val path = name.trimEnd('/')
        require(path.split('/').all { it.isNotBlank() && it != "." && it != ".." && it.none { c -> c.code < 32 } }) { "압축파일 경로가 대상 폴더를 벗어납니다." }
        return path
    }
    fun validate(entries: List<ZipArchiveEntry>) {
        require(entries.size <= MAX_ENTRIES) { "압축파일 항목은 최대 10만 개까지 지원합니다." }
        val seen = HashSet<String>(); var bytes = 0L
        for (entry in entries) {
            val path = path(entry.name)
            require(seen.add(path)) { "중복 경로가 있는 압축파일입니다." }
            require(!entry.isUnixSymlink && !entry.generalPurposeBit.usesEncryption()) { "심볼릭 링크와 암호화 ZIP은 지원하지 않습니다." }
            if (entry.size > 0) { require(entry.size <= MAX_OUTPUT - bytes) { "압축 해제 총량이 20GiB를 초과합니다." }; bytes += entry.size }
        }
        val files = entries.filterNot { it.isDirectory }.map { path(it.name) }.toSet()
        for (entry in entries) {
            val segments = path(entry.name).split('/')
            for (i in 1 until segments.size) require(segments.take(i).joinToString("/") !in files) { "파일과 폴더 경로가 충돌하는 압축파일입니다." }
        }
    }
}

class ArchiveDocument private constructor(private val zip: ZipFile, val entries: List<ZipArchiveEntry>) : Closeable {
    fun open(entry: ZipArchiveEntry): InputStream {
        require(entry in entries && !entry.isDirectory)
        if (!zip.canReadEntryData(entry)) throw StorageException(StorageError.UNSUPPORTED, "이 압축 방식을 지원하지 않습니다.")
        return zip.getInputStream(entry)
    }
    suspend fun preview(entry: ZipArchiveEntry, maximum: Int): ByteArray = withContext(Dispatchers.IO) {
        open(entry).use { stream ->
            val bytes = stream.readAtMost(maximum + 1)
            if (bytes.size > maximum) throw StorageException(StorageError.TOO_LARGE, "압축파일 미리보기 제한을 초과했습니다.")
            bytes
        }
    }
    override fun close() = zip.close()
    companion object {
        suspend fun open(channel: SeekableByteChannel): ArchiveDocument = withContext(Dispatchers.IO) {
            try {
                val zip = ZipFile.builder().setSeekableByteChannel(channel).get()
                try {
                    val items = mutableListOf<ZipArchiveEntry>()
                    val enumeration = zip.entries
                    while (enumeration.hasMoreElements()) {
                        ensureActive(); require(items.size < ArchiveSafety.MAX_ENTRIES) { "압축파일 항목이 10만 개를 초과합니다." }; items += enumeration.nextElement()
                    }
                    ArchiveSafety.validate(items)
                    ArchiveDocument(zip, items)
                } catch (e: Exception) { zip.close(); throw e }
            } catch (e: Exception) { channel.close(); throw e }
        }
    }
}
