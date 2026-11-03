package com.quickfiles.app.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Digests shown in file info, computed together in one read of the file. */
enum class ChecksumKind(val algorithm: String, val label: String) { MD5("MD5", "MD5"), SHA1("SHA-1", "SHA-1"), SHA256("SHA-256", "SHA-256") }

object Checksums {
    suspend fun compute(provider: StorageProvider, entry: FileEntry, progress: (Long) -> Unit = {}): Map<ChecksumKind, String> = withContext(Dispatchers.IO) {
        require(!entry.isDirectory) { "폴더의 체크섬은 계산할 수 없습니다." }
        val digests = ChecksumKind.entries.associateWith { MessageDigest.getInstance(it.algorithm) }
        provider.openRead(entry.location, 0, entry.revision.takeIf { it.size != null || it.etag != null }).use { handle ->
            val buffer = ByteArray(256 * 1024)
            var read = 0L
            while (true) {
                coroutineContext.ensureActive()
                val n = handle.stream.read(buffer)
                if (n < 0) break
                digests.values.forEach { it.update(buffer, 0, n) }
                read += n; progress(read)
            }
        }
        digests.mapValues { (_, digest) -> digest.digest().joinToString("") { "%02x".format(it) } }
    }

    /** Which digest a pasted value matches, ignoring case, spaces and an `algorithm:` prefix; null when none does. */
    fun match(results: Map<ChecksumKind, String>, pasted: String): ChecksumKind? {
        val value = pasted.substringAfterLast(':').filterNot { it.isWhitespace() }.lowercase()
        if (value.isEmpty()) return null
        return results.entries.firstOrNull { it.value == value }?.key
    }
}
