package com.quickfiles.app.storage

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Versioned read cache only. Transfer stages and trash never live under this root. */
class ContentCache(private val root: File, private val capacity: suspend () -> Long) {
    private val mutex = Mutex()
    private val pins = ConcurrentHashMap<String, Int>()
    private val keyLocks = ConcurrentHashMap<String, Mutex>()
    class Lease(val file: File, private val release: () -> Unit) : Closeable { override fun close() = release() }
    private fun key(entry: FileEntry) = sha256Hex("${entry.id}:${entry.size}:${entry.modified}:${entry.etag}")
    private val directories = ConcurrentHashMap<String, File>()
    private fun directory(source: String) = directories.getOrPut(source) { File(root, sha256Hex(source)) }.also { if (!it.isDirectory) it.mkdirs() }
    private fun pin(file: File) { pins.compute(file.absolutePath) { _, n -> (n ?: 0) + 1 } }
    private fun unpin(file: File) { pins.computeIfPresent(file.absolutePath) { _, n -> (n - 1).takeIf { it > 0 } } }
    private fun files() = root.walkTopDown().filter { it.isFile }.toList()

    /**
     * Bytes the cache holds, kept as a running total so writes do not walk the cache folder. -1 until first measured.
     * It is set again from disk whenever room has to be made, which also absorbs files the system removed on its own.
     * Guarded by [mutex], except [forget], which only ever lowers it.
     */
    private val used = java.util.concurrent.atomic.AtomicLong(-1)
    private fun measured(): Long = used.get().takeIf { it >= 0 } ?: files().sumOf { it.length() }.also { used.set(it) }
    private fun forget(bytes: Long) { if (bytes > 0) used.updateAndGet { if (it < 0) it else maxOf(0, it - bytes) } }
    /** Deletes [file] and takes its size off the running total. */
    private fun discard(file: File) { val size = file.length(); if (file.delete()) forget(size) }

    /** Makes room for [bytes] more, evicting the least recently used unpinned files. Call under [mutex]. */
    private fun makeRoom(bytes: Long, limit: Long, checkDisk: Boolean) {
        if (bytes > limit) throw StorageException(StorageError.TOO_LARGE, "캐시 한도를 넘습니다. 복사 기능에서 휴대폰 저장 위치를 선택해 다운로드해 주세요.")
        if (measured() + bytes > limit) {
            val all = files()
            var total = all.sumOf { it.length() }
            for (file in all.sortedBy { it.lastModified() }) {
                if (total + bytes <= limit) break
                if (!pins.containsKey(file.absolutePath)) { val size = file.length(); if (file.delete()) total -= size }
            }
            used.set(total)
            if (total + bytes > limit) throw StorageException(StorageError.TOO_LARGE, "다른 뷰어에서 사용 중인 캐시가 있습니다. 뷰어를 닫거나 캐시 한도를 늘려 주세요.")
        }
        if (checkDisk && root.usableSpace < bytes + 16 * 1024 * 1024) throw StorageException(StorageError.NO_SPACE, "미리보기 저장 공간이 부족합니다.")
    }
    /** Makes room for [bytes] and counts them as written. Call under [mutex], right before writing them. */
    private fun reserve(bytes: Long, limit: Long, checkDisk: Boolean = false) { makeRoom(bytes, limit, checkDisk); used.addAndGet(bytes) }

    suspend fun clearSource(source: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val dir = directory(source)
        if (dir.walkTopDown().any { pins.containsKey(it.absolutePath) }) throw StorageException(StorageError.BUSY, "이 저장소의 뷰어와 재생을 먼저 닫아 주세요.")
        dir.walkBottomUp().forEach { check(it.delete() || !it.exists()) }
        used.set(-1)
    } }
    suspend fun full(provider: StorageProvider, supplied: FileEntry, maximum: Long = Long.MAX_VALUE, progress: (Long, Long?) -> Unit = { _, _ -> }): Lease = retryRead { withContext(Dispatchers.IO) {
        if (provider is LocalStorageProvider) return@withContext Lease(provider.fileForRead(supplied.location)) {}
        val entry = provider.stat(supplied.location)
        val versionKey = key(entry) + if (entry.etag == null && entry.modified == null) "-${java.util.UUID.randomUUID()}" else ""
        val file = File(directory(entry.location.sourceId), versionKey + ".full")
        keyLocks.getOrPut(file.absolutePath) { Mutex() }.withLock {
            // The setting is read once per download, not once per written block.
            val cacheLimit = capacity()
            mutex.withLock {
                if (!file.exists()) makeRoom(entry.size ?: minOf(maximum, cacheLimit), cacheLimit, checkDisk = true)
                pin(file)
            }
            try {
                if (!file.exists()) {
                    val part = File(file.path + ".part")
                    mutex.withLock { pin(part) }
                    try {
                        val limit = minOf(maximum, cacheLimit)
                        if ((entry.size ?: 0) > limit) throw StorageException(StorageError.TOO_LARGE, "파일이 미리보기 한도(${limit / (1024 * 1024)}MiB)를 넘습니다. 저장 위치를 선택해 복사해 주세요.")
                        provider.openRead(entry.location, 0, entry.revision).use { handle ->
                            part.outputStream().use { output ->
                                val buffer = ByteArray(128 * 1024); var count = 0L
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val read = handle.stream.read(buffer); if (read < 0) break
                                    if (count + read > limit) throw StorageException(StorageError.TOO_LARGE, "미리보기 한도를 초과했습니다.")
                                    mutex.withLock { reserve(read.toLong(), cacheLimit); output.write(buffer, 0, read) }
                                    count += read; progress(count, entry.size)
                                }
                                if (entry.size != null && count != entry.size) throw StorageException(StorageError.NETWORK, "파일을 끝까지 읽지 못했습니다.")
                            }
                        }
                        if (!entry.revision.matches(provider.stat(entry.location).revision)) throw StorageException(StorageError.CONFLICT, "파일이 변경되었습니다. 다시 열어 주세요.")
                        check(part.renameTo(file)) { "캐시를 확정하지 못했습니다." }
                    } finally { if (part.exists()) discard(part); unpin(part) }
                }
                file.setLastModified(System.currentTimeMillis())
                Lease(file) { unpin(file) }
            } catch (e: Exception) { unpin(file); throw e }
        }
    } }
    suspend fun channel(provider: StorageProvider, supplied: FileEntry): SeekableByteChannel = withContext(Dispatchers.IO) {
        val entry = provider.stat(supplied.location)
        if (provider is LocalStorageProvider) return@withContext provider.channel(entry.location)
        if (isLocalSource(entry.location.sourceId) || entry.revision.strongEtag == null || entry.size == null) {
            val lease = full(provider, entry)
            return@withContext LeasedChannel(RandomAccessFile(lease.file, "r").channel, lease)
        }
        // Worked out once per channel: archive and PDF readers call read() thousands of times.
        val folder = directory(entry.location.sourceId)
        val version = key(entry)
        object : SeekableByteChannel {
            private val lifetime = Job()
            private var offset = 0L
            private var opened = true
            private var fallback: SeekableByteChannel? = null
            /** The block the last read landed in, so the many small reads inside one block skip the cache lookup and file open. */
            private var currentStart = -1L
            private var current: ByteArray? = null
            override fun isOpen() = opened
            override fun position() = offset
            override fun position(newPosition: Long): SeekableByteChannel { require(newPosition >= 0); offset = newPosition; return this }
            override fun size() = entry.size
            override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
            override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
            override fun close() { opened = false; lifetime.cancel(); current = null; fallback?.close() }
            @Synchronized override fun read(dst: ByteBuffer): Int {
                check(opened)
                if (!dst.hasRemaining()) return 0
                if (offset >= entry.size) return -1
                fallback?.let { it.position(offset); return it.read(dst).also { n -> if (n > 0) offset += n } }
                val blockStart = offset / BLOCK * BLOCK
                val bytes = current?.takeIf { currentStart == blockStart } ?: load(blockStart)
                    ?: return fallback!!.let { it.position(offset); it.read(dst).also { n -> if (n > 0) offset += n } }
                val start = (offset - blockStart).toInt(); val length = minOf(dst.remaining(), bytes.size - start)
                dst.put(bytes, start, length); offset += length
                return length
            }
            /** The block's bytes from the cache, fetching them once; null when the server refused ranges and [fallback] took over. */
            private fun load(blockStart: Long): ByteArray? {
                val blockLength = minOf(BLOCK, entry.size - blockStart).toInt()
                val file = File(folder, "$version.$blockStart.block")
                val bytes = runBlocking(Dispatchers.IO + lifetime) {
                    keyLocks.getOrPut(file.absolutePath) { Mutex() }.withLock {
                        val limit = capacity()
                        mutex.withLock { if (!file.exists()) makeRoom(blockLength.toLong(), limit, checkDisk = true); pin(file) }
                        try {
                            if (!file.exists()) {
                                try {
                                    retryRead { provider.openRead(entry.location, blockStart, entry.revision).use { h ->
                                        val bytes = h.stream.readAtMost(blockLength)
                                        if (bytes.size != blockLength) throw StorageException(StorageError.NETWORK, "파일 범위를 끝까지 읽지 못했습니다.")
                                        mutex.withLock { reserve(bytes.size.toLong(), limit); file.writeBytes(bytes) }
                                        bytes
                                    } }
                                } catch (e: Exception) {
                                    if (file.exists()) discard(file)
                                    if (e !is StorageException || e.kind != StorageError.UNSUPPORTED) throw e
                                    val lease = full(provider, entry)
                                    fallback = LeasedChannel(RandomAccessFile(lease.file, "r").channel, lease)
                                    null
                                }
                            } else file.readBytes().also { if (it.size != blockLength) { discard(file); throw StorageException(StorageError.READ, "캐시 블록이 손상되었습니다. 다시 열어 주세요.") } }
                        } finally { file.setLastModified(System.currentTimeMillis()); unpin(file) }
                    }
                } ?: return null
                currentStart = blockStart; current = bytes
                return bytes
            }
        }
    }
    private class LeasedChannel(private val channel: SeekableByteChannel, private val lease: Lease) : SeekableByteChannel by channel {
        override fun close() { try { channel.close() } finally { lease.close() } }
    }
    companion object { private const val BLOCK = 1024L * 1024 }
}

private val HEX = "0123456789abcdef".toCharArray()
/** Lower-case hex SHA-256, without a String.format per byte. */
internal fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    val out = CharArray(digest.size * 2)
    for (i in digest.indices) { val b = digest[i].toInt() and 0xff; out[2 * i] = HEX[b ushr 4]; out[2 * i + 1] = HEX[b and 0x0f] }
    return String(out)
}
