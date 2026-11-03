package com.quickfiles.app.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.CancellationSignal
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Video frames and embedded album art for file tiles, cached as small JPEGs keyed by file version.
 * Local files use the platform thumbnailers. Remote files are read through ranged requests with a
 * per-thumbnail byte budget, so a file whose index sits at its end costs a bounded download, not the file.
 */
class MediaThumbnails(private val root: File, private val maximum: Long = 64 * MIB) {
    suspend fun thumbnail(provider: StorageProvider, entry: FileEntry, size: Int = 320): File? = withContext(Dispatchers.IO) {
        if (!entry.isVideo && !entry.isAudio) return@withContext null
        root.mkdirs()
        val key = hash("${entry.id}:${entry.size}:${entry.modified}:${entry.etag}:$size")
        val target = File(root, "$key.jpg"); val miss = File(root, "$key.none")
        if (target.isFile) { target.setLastModified(System.currentTimeMillis()); return@withContext target }
        if (miss.exists()) return@withContext null
        val bitmap = try {
            if (provider is LocalStorageProvider) local(provider.fileForRead(entry.location), entry, size)
            else if (provider.capabilities.rangedRead) remote(provider, entry, size)
            else null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
        if (bitmap == null) { runCatching { miss.createNewFile() }; return@withContext null }
        val partial = File(root, "$key.part")
        try {
            partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (!partial.renameTo(target)) return@withContext null
        } finally { bitmap.recycle(); partial.delete() }
        prune()
        target
    }

    private suspend fun local(file: File, entry: FileEntry, size: Int): Bitmap? {
        val signal = CancellationSignal()
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { signal.cancel() }
            val result = try {
                if (entry.isVideo) ThumbnailUtils.createVideoThumbnail(file, Size(size, size), signal)
                else ThumbnailUtils.createAudioThumbnail(file, Size(size, size), signal)
            } catch (_: IOException) { null } // no frame or no embedded art
            continuation.resumeWith(Result.success(result))
        }
    }

    private suspend fun remote(provider: StorageProvider, entry: FileEntry, size: Int): Bitmap? {
        val length = entry.size ?: provider.stat(entry.location).size ?: return null
        val source = RangedMediaSource(provider, entry, length)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source)
            coroutineContext.ensureActive()
            return if (entry.isVideo) {
                val frame = retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
                    ?: retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
                frame
            } else retriever.embeddedPicture?.let { decodeScaled(it, size) }
        } finally { retriever.release(); source.close() }
    }

    private fun prune() {
        val files = root.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) { if (total <= maximum) break; val n = file.length(); if (file.delete()) total -= n }
    }

    companion object {
        /** Remote bytes one thumbnail may read before giving up. */
        const val REMOTE_BUDGET = 24 * MIB
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        fun decodeScaled(bytes: ByteArray, size: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, size) })
        }

        /** Largest power-of-two reduction that keeps the shorter side at least [target] pixels. */
        fun sampleSize(width: Int, height: Int, target: Int): Int {
            var sample = 1
            while (minOf(width, height) / (sample * 2) >= target) sample *= 2
            return sample
        }
    }
}

/** Hands MediaMetadataRetriever's reads to a [RangedBlocks] reader. */
internal class RangedMediaSource(provider: StorageProvider, entry: FileEntry, private val length: Long) : MediaDataSource() {
    private val blocks = RangedBlocks(provider, entry, length)
    override fun getSize(): Long = length
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = blocks.readAt(position, buffer, offset, size)
    override fun close() = blocks.close()
}

/**
 * Serves small, scattered reads from 256KiB blocks fetched with ranged reads, keeping a few recent
 * blocks. Fetching beyond [budget] bytes fails with an IOException, which ends the thumbnail attempt.
 */
internal class RangedBlocks(private val provider: StorageProvider, private val entry: FileEntry, private val length: Long,
    private val budget: Long = MediaThumbnails.REMOTE_BUDGET, private val block: Long = 256 * 1024L, private val kept: Int = 8) {
    private val blocks = LinkedHashMap<Long, ByteArray>(kept, .75f, true)
    var fetched = 0L; private set

    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= length) return -1
        var copied = 0
        while (copied < size && position + copied < length) {
            val at = position + copied
            val start = at - at % block
            val bytes = block(start)
            val inBlock = (at - start).toInt()
            if (inBlock >= bytes.size) break
            val n = minOf(size - copied, bytes.size - inBlock)
            System.arraycopy(bytes, inBlock, buffer, offset + copied, n)
            copied += n
        }
        return if (copied == 0) -1 else copied
    }

    private fun block(start: Long): ByteArray = blocks[start] ?: run {
        val want = minOf(block, length - start).toInt()
        if (fetched + want > budget) throw IOException("thumbnail read budget exceeded")
        val bytes = runBlocking { provider.openRead(entry.location, start, entry.revision).use { it.stream.readAtMost(want) } }
        fetched += bytes.size
        blocks[start] = bytes
        if (blocks.size > kept) blocks.remove(blocks.keys.first())
        bytes
    }

    fun close() { blocks.clear() }
}
