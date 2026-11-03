package com.quickfiles.app.viewer

import com.quickfiles.app.storage.*
import kotlinx.coroutines.*
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.Locale

/** One member of any supported archive. [size] and [crc] are -1 when the format does not record them. */
data class ArchiveItem(val name: String, val isDirectory: Boolean, val size: Long = -1, val crc: Long = -1,
    val link: Boolean = false, val encrypted: Boolean = false)

enum class Compression { GZIP, XZ, BZIP2, ZSTD }

/** Archive formats recognised by file name. */
sealed interface ArchiveFormat {
    data object Zip : ArchiveFormat
    data object SevenZ : ArchiveFormat
    data class Tar(val compression: Compression?) : ArchiveFormat
    /** A single compressed file such as `notes.txt.gz`; it holds one member named without the suffix. */
    data class Single(val compression: Compression, val inner: String) : ArchiveFormat

    companion object {
        fun detect(name: String): ArchiveFormat? {
            val lower = name.lowercase(Locale.ROOT)
            fun ends(vararg suffixes: String) = suffixes.firstOrNull { lower.endsWith(it) && lower.length > it.length }
            return when {
                ends(".zip", ".cbz") != null -> Zip
                ends(".7z", ".cb7") != null -> SevenZ
                ends(".tar", ".cbt") != null -> Tar(null)
                ends(".tar.gz", ".tgz") != null -> Tar(Compression.GZIP)
                ends(".tar.xz", ".txz") != null -> Tar(Compression.XZ)
                ends(".tar.bz2", ".tbz2", ".tbz") != null -> Tar(Compression.BZIP2)
                ends(".tar.zst", ".tzst") != null -> Tar(Compression.ZSTD)
                else -> listOf(".gz" to Compression.GZIP, ".xz" to Compression.XZ, ".bz2" to Compression.BZIP2, ".zst" to Compression.ZSTD)
                    .firstOrNull { (suffix, _) -> ends(suffix) != null }?.let { (suffix, compression) -> Single(compression, name.dropLast(suffix.length)) }
            }
        }
    }
}

/** A name for the folder an archive extracts into: `photos.tar.gz` gives `photos`. */
fun archiveStem(name: String): String {
    val lower = name.lowercase(Locale.ROOT)
    val suffix = listOf(".tar.gz", ".tar.xz", ".tar.bz2", ".tar.zst").firstOrNull { lower.endsWith(it) && lower.length > it.length }
    return if (suffix != null) name.dropLast(suffix.length) else name.substringBeforeLast('.').ifEmpty { name }
}

object ArchiveSafety {
    const val MAX_ENTRIES = 100000
    const val MAX_OUTPUT = 20L * 1024 * 1024 * 1024
    fun path(name: String): String {
        require(name.isNotBlank() && !name.startsWith('/') && '\\' !in name && ':' !in name && '\u0000' !in name) { "압축파일에 안전하지 않은 경로가 있습니다." }
        val path = name.trimEnd('/')
        require(path.split('/').all { it.isNotBlank() && it != "." && it != ".." && it.none { c -> c.code < 32 } }) { "압축파일 경로가 대상 폴더를 벗어납니다." }
        return path
    }
    @JvmName("validateZip")
    fun validate(entries: List<ZipArchiveEntry>) = validate(entries.map(::item))
    fun validate(entries: List<ArchiveItem>) {
        require(entries.size <= MAX_ENTRIES) { "압축파일 항목은 최대 10만 개까지 지원합니다." }
        val seen = HashSet<String>(); var bytes = 0L
        for (entry in entries) {
            val path = path(entry.name)
            require(seen.add(path)) { "중복 경로가 있는 압축파일입니다." }
            require(!entry.link && !entry.encrypted) { "심볼릭 링크와 암호화 압축파일은 지원하지 않습니다." }
            if (entry.size > 0) { require(entry.size <= MAX_OUTPUT - bytes) { "압축 해제 총량이 20GiB를 초과합니다." }; bytes += entry.size }
        }
        val files = entries.filterNot { it.isDirectory }.map { path(it.name) }.toSet()
        for (entry in entries) {
            val segments = path(entry.name).split('/')
            for (i in 1 until segments.size) require(segments.take(i).joinToString("/") !in files) { "파일과 폴더 경로가 충돌하는 압축파일입니다." }
        }
    }
    internal fun item(entry: ZipArchiveEntry) = ArchiveItem(entry.name, entry.isDirectory, entry.size, entry.crc, entry.isUnixSymlink, entry.generalPurposeBit.usesEncryption())
}

/**
 * A read-only view of a ZIP, 7z, tar (plain or gz/xz/bz2/zst compressed) or single compressed file.
 * Members open one at a time. Tar has no index, so members are read through one forward cursor:
 * visiting them in archive order (as extraction does) decompresses the archive once.
 */
class ArchiveDocument private constructor(private val backend: Backend, val entries: List<ArchiveItem>, val format: ArchiveFormat) : Closeable {
    fun open(entry: ArchiveItem): InputStream {
        val index = entries.indexOf(entry)
        require(index >= 0 && !entry.isDirectory)
        return backend.open(index)
    }
    suspend fun preview(entry: ArchiveItem, maximum: Int): ByteArray = withContext(Dispatchers.IO) {
        open(entry).use { stream ->
            val bytes = stream.readAtMost(maximum + 1)
            if (bytes.size > maximum) throw StorageException(StorageError.TOO_LARGE, "압축파일 미리보기 제한을 초과했습니다.")
            bytes
        }
    }
    override fun close() = backend.close()

    private interface Backend : Closeable { fun open(index: Int): InputStream }

    private class ZipBackend(private val zip: ZipFile, private val members: List<ZipArchiveEntry>) : Backend {
        override fun open(index: Int): InputStream {
            val entry = members[index]
            if (!zip.canReadEntryData(entry)) throw StorageException(StorageError.UNSUPPORTED, "이 압축 방식을 지원하지 않습니다.")
            return zip.getInputStream(entry)
        }
        override fun close() = zip.close()
    }

    private class SevenZBackend(private val file: SevenZFile, private val members: List<SevenZArchiveEntry>) : Backend {
        override fun open(index: Int): InputStream = try { file.getInputStream(members[index]) }
            catch (e: PasswordRequiredException) { throw StorageException(StorageError.UNSUPPORTED, "암호가 걸린 7z 파일은 지원하지 않습니다.", e) }
        override fun close() = file.close()
    }

    private class TarBackend(private val channel: SeekableByteChannel, private val compression: Compression?, private val ordinals: List<Int>) : Backend {
        private var tar: TarArchiveInputStream? = null
        private var position = -1 // raw ordinal of the entry the cursor stands on
        override fun open(index: Int): InputStream {
            val target = ordinals[index]
            if (tar == null || target <= position) { tar?.close(); tar = TarArchiveInputStream(decompressed(channel, compression)); position = -1 }
            val stream = tar!!
            while (position < target) { stream.nextEntry ?: throw StorageException(StorageError.READ, "압축파일이 중간에 끝났습니다."); position++ }
            // Closing a member must not close the cursor shared by later members.
            return object : FilterInputStream(stream) { override fun close() {} }
        }
        override fun close() { try { tar?.close() } finally { channel.close() } }
    }

    private class SingleBackend(private val channel: SeekableByteChannel, private val compression: Compression) : Backend {
        override fun open(index: Int): InputStream { require(index == 0); return decompressed(channel, compression) }
        override fun close() = channel.close()
    }

    companion object {
        /** Opens [channel] as [format]; the document owns the channel and closes it. */
        suspend fun open(channel: SeekableByteChannel, format: ArchiveFormat = ArchiveFormat.Zip): ArchiveDocument = withContext(Dispatchers.IO) {
            try {
                when (format) {
                    ArchiveFormat.Zip -> {
                        val zip = ZipFile.builder().setSeekableByteChannel(channel).get()
                        try {
                            val members = mutableListOf<ZipArchiveEntry>()
                            val enumeration = zip.entries
                            while (enumeration.hasMoreElements()) {
                                ensureActive(); require(members.size < ArchiveSafety.MAX_ENTRIES) { "압축파일 항목이 10만 개를 초과합니다." }; members += enumeration.nextElement()
                            }
                            val items = members.map(ArchiveSafety::item)
                            ArchiveSafety.validate(items)
                            ArchiveDocument(ZipBackend(zip, members), items, format)
                        } catch (e: Exception) { zip.close(); throw e }
                    }
                    ArchiveFormat.SevenZ -> {
                        val file = try { SevenZFile.builder().setSeekableByteChannel(channel).get() }
                            catch (e: PasswordRequiredException) { throw StorageException(StorageError.UNSUPPORTED, "암호가 걸린 7z 파일은 지원하지 않습니다.", e) }
                        try {
                            val members = file.entries.filter { !it.isAntiItem }
                            require(members.size <= ArchiveSafety.MAX_ENTRIES) { "압축파일 항목이 10만 개를 초과합니다." }
                            // SevenZFile checks each member's CRC itself while reading, so no CRC is passed on.
                            val items = members.map { ArchiveItem(it.name, it.isDirectory, if (it.isDirectory) -1 else it.size) }
                            ArchiveSafety.validate(items)
                            ArchiveDocument(SevenZBackend(file, members), items, format)
                        } catch (e: Exception) { file.close(); throw e }
                    }
                    is ArchiveFormat.Tar -> {
                        val items = mutableListOf<ArchiveItem>(); val ordinals = mutableListOf<Int>()
                        TarArchiveInputStream(decompressed(channel, format.compression)).use { tar ->
                            var ordinal = -1
                            while (true) {
                                ensureActive()
                                val entry: TarArchiveEntry = tar.nextEntry ?: break
                                ordinal++
                                require(ordinal < ArchiveSafety.MAX_ENTRIES) { "압축파일 항목이 10만 개를 초과합니다." }
                                // Links, devices and FIFOs are not files Gotgan can recreate safely; they are left out.
                                if (entry.isSymbolicLink || entry.isLink || entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO || !(entry.isDirectory || entry.isFile)) continue
                                items += ArchiveItem(entry.name, entry.isDirectory, if (entry.isDirectory) -1 else entry.size); ordinals += ordinal
                            }
                        }
                        ArchiveSafety.validate(items)
                        ArchiveDocument(TarBackend(channel, format.compression, ordinals), items, format)
                    }
                    is ArchiveFormat.Single -> {
                        val name = ArchiveSafety.path(format.inner.substringAfterLast('/').ifBlank { "압축 해제된 파일" })
                        // Probe the header now so a damaged or mislabelled file fails on open, not on extract.
                        decompressed(channel, format.compression).use { it.read() }
                        ArchiveDocument(SingleBackend(channel, format.compression), listOf(ArchiveItem(name, false)), format)
                    }
                }
            } catch (e: Exception) {
                channel.close()
                if (e is IOException && e !is StorageException) throw StorageException(StorageError.READ, "압축파일을 읽을 수 없습니다. 손상되었거나 지원하지 않는 형식입니다.", e)
                throw e
            }
        }

        private fun decompressed(channel: SeekableByteChannel, compression: Compression?): InputStream {
            channel.position(0)
            val raw = BufferedInputStream(ChannelInput(channel), 64 * 1024)
            return when (compression) {
                null -> raw
                Compression.GZIP -> GzipCompressorInputStream(raw, true)
                Compression.XZ -> XZCompressorInputStream(raw)
                Compression.BZIP2 -> BZip2CompressorInputStream(raw, true)
                Compression.ZSTD -> ZstdCompressorInputStream(raw)
            }
        }
    }

    /** Reads the channel from its current position; closing it leaves the channel open for the next pass. */
    private class ChannelInput(private val channel: SeekableByteChannel) : InputStream() {
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var n: Int
            do { n = channel.read(ByteBuffer.wrap(b, off, len)) } while (n == 0)
            return n
        }
    }
}
