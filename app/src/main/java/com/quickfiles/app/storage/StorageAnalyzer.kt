package com.quickfiles.app.storage

import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** What a folder's space goes to: a broad file category. */
enum class UsageKind(val label: String) { IMAGE("사진"), VIDEO("영상"), AUDIO("음악"), DOCUMENT("문서"), ARCHIVE("압축"), OTHER("기타") }

fun usageKind(entry: FileEntry): UsageKind = when {
    entry.isImage -> UsageKind.IMAGE
    entry.isVideo -> UsageKind.VIDEO
    entry.isAudio -> UsageKind.AUDIO
    entry.isPdf || entry.isText || entry.extension in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "hwp", "hwpx", "odt", "ods", "odp", "rtf", "epub") -> UsageKind.DOCUMENT
    entry.isArchive || entry.extension in setOf("7z", "rar", "tar", "gz", "tgz", "xz", "bz2", "zst", "apk") -> UsageKind.ARCHIVE
    else -> UsageKind.OTHER
}

/** A direct child of the analysed folder with everything beneath it. */
data class ChildUsage(val entry: FileEntry, val bytes: Long, val files: Int)

/** Files with identical content; all but one are reclaimable. */
data class DuplicateGroup(val size: Long, val digest: String, val files: List<FileEntry>) {
    val reclaimable: Long get() = size * (files.size - 1)
}

data class StorageReport(
    val root: StorageLocation,
    val totalBytes: Long, val files: Int, val folders: Int,
    val children: List<ChildUsage>,
    val largest: List<FileEntry>,
    val kinds: Map<UsageKind, Long>,
    val duplicates: List<DuplicateGroup>,
    /** Duplicates were not checked (remote source, where hashing would download every candidate). */
    val duplicatesSkipped: Boolean,
    val unreadableFolders: Int,
) {
    val reclaimable: Long get() = duplicates.sumOf { it.reclaimable }
}

data class ScanProgress(val files: Int, val bytes: Long, val hashing: Boolean = false)

/**
 * Walks a folder tree once and reports where its space goes. Duplicates are found by grouping equal
 * sizes first and hashing only those candidates, so unique files are never read.
 */
class StorageAnalyzer(
    private val largestCount: Int = 50, private val minimumDuplicateSize: Long = 1,
    /** Names the app manages itself (trash, partial transfers), which are not the user's files. */
    private val isManagedName: (String) -> Boolean = { false },
) {
    suspend fun analyze(
        provider: StorageProvider, root: StorageLocation, includeHidden: Boolean, findDuplicates: Boolean,
        progress: (ScanProgress) -> Unit = {},
    ): StorageReport {
        val all = mutableListOf<FileEntry>()
        val childTotals = LinkedHashMap<String, Pair<Long, Int>>()
        val childEntries = LinkedHashMap<String, FileEntry>()
        var folders = 0; var unreadable = 0; var bytes = 0L

        fun visible(entry: FileEntry) = (includeHidden || !entry.name.startsWith('.')) && !isManagedName(entry.name)
        val top = provider.list(root).filter(::visible)
        val queue = ArrayDeque<Pair<FileEntry, String>>() // entry, top-level child id it belongs to
        for (entry in top) { childEntries[entry.id] = entry; childTotals[entry.id] = 0L to 0; queue.add(entry to entry.id) }
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (entry, owner) = queue.removeFirst()
            if (entry.isDirectory) {
                folders++
                val children = try { provider.list(entry.location) } catch (e: StorageException) { unreadable++; continue }
                children.filter(::visible).forEach { queue.add(it to owner) }
            } else {
                val size = entry.size ?: 0
                all += entry; bytes += size
                childTotals[owner] = childTotals.getValue(owner).let { (b, n) -> (b + size) to (n + 1) }
                if (all.size % 200 == 0) progress(ScanProgress(all.size, bytes))
            }
        }
        progress(ScanProgress(all.size, bytes))
        val children = childEntries.values.map { e -> childTotals.getValue(e.id).let { (b, n) -> ChildUsage(e, b, n) } }
            .sortedWith(compareByDescending<ChildUsage> { it.bytes }.thenBy { it.entry.name.lowercase() })
        val largest = all.sortedWith(compareByDescending<FileEntry> { it.size ?: 0 }.thenBy { it.name.lowercase() }).take(largestCount)
        val kinds = all.groupBy(::usageKind).mapValues { (_, files) -> files.sumOf { it.size ?: 0 } }.toSortedMap(compareBy { it.ordinal })
        val duplicates = if (findDuplicates) duplicates(provider, all, progress) else emptyList()
        return StorageReport(root, bytes, all.size, folders, children, largest, kinds, duplicates, !findDuplicates, unreadable)
    }

    /**
     * Same-size files are first compared by a hash of their first [HEAD_BYTES]; only files whose start also matches
     * are read in full. Large same-size videos and photos almost always differ in their first bytes.
     */
    private suspend fun duplicates(provider: StorageProvider, files: List<FileEntry>, progress: (ScanProgress) -> Unit): List<DuplicateGroup> {
        val candidates = files.filter { (it.size ?: 0) >= minimumDuplicateSize }.groupBy { it.size!! }.filterValues { it.size > 1 }
        val groups = mutableListOf<DuplicateGroup>()
        var hashed = 0; var hashedBytes = 0L
        for ((size, sameSize) in candidates) {
            val byHead = LinkedHashMap<String, MutableList<FileEntry>>()
            for (file in sameSize) {
                coroutineContext.ensureActive()
                val head = try { digest(provider, file, HEAD_BYTES) } catch (e: StorageException) { continue }
                byHead.getOrPut(head) { mutableListOf() } += file
                hashedBytes += minOf(size, HEAD_BYTES)
                progress(ScanProgress(hashed, hashedBytes, hashing = true))
            }
            for ((head, sameHead) in byHead) {
                hashed += sameHead.size
                if (sameHead.size < 2) continue
                val byDigest = LinkedHashMap<String, MutableList<FileEntry>>()
                for (file in sameHead) {
                    coroutineContext.ensureActive()
                    // A file no longer than the head was read whole already; its head hash is its full hash.
                    val digest = if (size <= HEAD_BYTES) head else {
                        val full = try { digest(provider, file) } catch (e: StorageException) { continue }
                        hashedBytes += size
                        full
                    }
                    byDigest.getOrPut(digest) { mutableListOf() } += file
                    progress(ScanProgress(hashed, hashedBytes, hashing = true))
                }
                byDigest.forEach { (digest, same) -> if (same.size > 1) groups += DuplicateGroup(size, digest, same.sortedBy { it.location.path.lowercase() }) }
            }
            progress(ScanProgress(hashed, hashedBytes, hashing = true))
        }
        return groups.sortedWith(compareByDescending<DuplicateGroup> { it.reclaimable }.thenBy { it.files.first().name.lowercase() })
    }

    /** SHA-256 of the file's first [limit] bytes (all of it by default). */
    private suspend fun digest(provider: StorageProvider, file: FileEntry, limit: Long = Long.MAX_VALUE): String {
        val sha = MessageDigest.getInstance("SHA-256")
        provider.openRead(file.location).use { handle ->
            val buffer = ByteArray(128 * 1024)
            var left = limit
            while (left > 0) {
                coroutineContext.ensureActive()
                val n = handle.stream.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (n < 0) break
                sha.update(buffer, 0, n); left -= n
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object { const val HEAD_BYTES = 64 * 1024L }
}
