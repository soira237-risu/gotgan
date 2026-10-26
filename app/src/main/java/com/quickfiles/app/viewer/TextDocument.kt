package com.quickfiles.app.viewer

import com.quickfiles.app.storage.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class TextDocument(val bytes: ByteArray, val complete: Boolean, val revision: Revision, val cp949: Boolean = false, val start: Long = 0, val encodingName: String? = null) {
    val bom: ByteArray get() = when {
        start != 0L -> byteArrayOf()
        bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> bytes.take(3).toByteArray()
        bytes.size >= 2 && ((bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) || (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte())) -> bytes.take(2).toByteArray()
        else -> byteArrayOf()
    }
    val charset: Charset get() = when {
        cp949 -> Charset.forName("MS949")
        bom.size == 2 && bom[0] == 0xff.toByte() -> Charsets.UTF_16LE
        bom.size == 2 -> Charsets.UTF_16BE
        encodingName != null -> Charset.forName(encodingName)
        else -> Charsets.UTF_8
    }
    fun decode() = String(bytes, bom.size, bytes.size - bom.size, charset)
    fun encode(text: String, utf8: Boolean = false): ByteArray {
        require(complete && start == 0L && bytes.size <= EDIT_LIMIT) { "전체를 읽은 4MiB 이하 파일만 편집할 수 있습니다." }
        val encoder = (if (utf8) Charsets.UTF_8 else charset).newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val original = decode()
        val normalized = if (original.contains("\r\n") && !original.replace("\r\n", "").contains('\n')) text.replace("\r\n", "\n").replace("\n", "\r\n") else text
        val encoded = encoder.encode(CharBuffer.wrap(normalized))
        val prefix = if (utf8) byteArrayOf() else bom
        require(encoded.remaining() + prefix.size <= EDIT_LIMIT) { "저장할 내용이 4MiB를 초과합니다." }
        return prefix + ByteArray(encoded.remaining()).also { encoded.get(it) }
    }
    companion object {
        const val CHUNK = 1024 * 1024
        const val EDIT_LIMIT = 4 * CHUNK
        // Android's ICU decoder may consume an incomplete character into internal state even
        // with endOfInput=false. Its ByteBuffer position is therefore not a safe file offset.
        private fun completePrefix(doc: TextDocument): Int {
            val bytes = doc.bytes
            val start = doc.bom.size
            fun unsigned(index: Int) = bytes[index].toInt() and 0xff
            if (doc.cp949) {
                var index = start
                while (index < bytes.size) {
                    val lead = unsigned(index)
                    if (lead in 0x81..0xfe) {
                        if (index + 1 == bytes.size) return index
                        val trail = unsigned(index + 1)
                        if (trail in 0x41..0x5a || trail in 0x61..0x7a || trail in 0x81..0xfe) { index += 2; continue }
                    }
                    index++
                }
                return bytes.size
            }
            if (doc.charset == Charsets.UTF_16LE || doc.charset == Charsets.UTF_16BE) {
                var end = bytes.size - (bytes.size - start) % 2
                if (end - start >= 2) {
                    val last = if (doc.charset == Charsets.UTF_16LE) unsigned(end - 2) or (unsigned(end - 1) shl 8)
                        else (unsigned(end - 2) shl 8) or unsigned(end - 1)
                    if (last in 0xd800..0xdbff) end -= 2
                }
                return end
            }
            if (doc.charset == Charsets.UTF_8 && bytes.size > start) {
                var lead = bytes.lastIndex
                while (lead > start && bytes.lastIndex - lead < 3 && unsigned(lead) in 0x80..0xbf) lead--
                val length = when (unsigned(lead)) { in 0xc2..0xdf -> 2; in 0xe0..0xef -> 3; in 0xf0..0xf4 -> 4; else -> 1 }
                if (bytes.size - lead < length) return lead
            }
            return bytes.size
        }
        suspend fun read(provider: StorageProvider, entry: FileEntry, maximum: Int = CHUNK, cp949: Boolean = false, offset: Long = 0, expected: Revision? = null, encodingName: String? = null): TextDocument = retryRead { withContext(Dispatchers.IO) {
            val revision = provider.stat(entry.location).revision
            if (expected != null && !expected.matches(revision)) throw StorageException(StorageError.CONFLICT, "읽는 동안 파일이 변경되었습니다. 다시 열어 주세요.")
            val handle = try { provider.openRead(entry.location, offset, revision) }
            catch (e: StorageException) {
                if (offset <= 0 || e.kind != StorageError.UNSUPPORTED) throw e
                val full = provider.openRead(entry.location, 0, revision)
                try {
                    var left = offset; val buffer = ByteArray(128 * 1024)
                    while (left > 0) { ensureActive(); val n = full.stream.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt()); if (n < 0) throw StorageException(StorageError.CONFLICT, "파일 크기가 변경되었습니다."); left -= n }
                    full
                } catch (error: Exception) { full.close(); throw error }
            }
            handle.use { h ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(32 * 1024)
                var eof = false
                while (output.size() <= maximum) {
                    ensureActive()
                    val n = h.stream.read(buffer, 0, minOf(buffer.size, maximum + 1 - output.size()))
                    if (n < 0) { eof = true; break }; output.write(buffer, 0, n)
                }
                val bytes = output.toByteArray()
                if (!revision.matches(provider.stat(entry.location).revision)) throw StorageException(StorageError.CONFLICT, "읽는 동안 파일이 변경되었습니다. 다시 열어 주세요.")
                var doc = TextDocument(if (bytes.size > maximum) bytes.copyOf(maximum) else bytes, eof, revision, cp949, offset, encodingName)
                if (!eof) {
                    // Keep an incomplete multibyte character for the next window, never drop its bytes.
                    doc = doc.copy(bytes = doc.bytes.copyOf(completePrefix(doc)))
                }
                doc
            }
        } }
    }
}
