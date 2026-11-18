package com.quickfiles.app.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.CancellationSignal
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Video frames, embedded album art and photo tiles, cached as small JPEGs keyed by file version.
 * Local media use the platform thumbnailers. Remote media are read through ranged requests with a
 * per-thumbnail byte budget, so a file whose index sits at its end costs a bounded download, not the file.
 * Remote photos use the preview the camera embedded in the first few hundred KiB when it is sharp enough,
 * and download the original only once, when allowed, to make a thumbnail that is kept afterwards.
 */
class MediaThumbnails(private val root: File, private val maximum: Long = 64 * MIB) {
    /** Full-resolution photo decodes at once; each can briefly hold tens of MB. */
    private val decodes = Semaphore(3)
    /** Thrown when a policy, not the file, kept a thumbnail from being made; nothing is remembered as a miss. */
    private class Skipped : Exception()

    suspend fun thumbnail(provider: StorageProvider, entry: FileEntry, size: Int = 320): File? =
        if (!entry.isVideo && !entry.isAudio) null else make(entry, size) {
            if (provider is LocalStorageProvider) local(provider.fileForRead(entry.location), entry, size)
            else if (provider.capabilities.rangedRead) remote(provider, entry, size)
            else null
        }

    /**
     * A small JPEG for a photo tile. [original] fetches the whole file (through the content cache) and is called
     * only when the embedded preview is missing or too small, and only if [fullAllowed] accepts the file's size.
     */
    suspend fun image(provider: StorageProvider, entry: FileEntry, fullAllowed: (Long?) -> Boolean,
        original: suspend () -> ContentCache.Lease, size: Int = 320): File? =
        if (!entry.isImage || FileFormats.extension(entry.name) == "svg") null else make(entry, size) {
            if (provider is LocalStorageProvider) {
                val file = provider.fileForRead(entry.location)
                (if (FileFormats.extension(entry.name) in EXIF_PREVIEW) embedded({ ExifInterface(file) }, size) else null)
                    ?: decodes.withPermit { decodeFile(file, size) }
            } else {
                val length = entry.size
                val preview = if (provider.capabilities.rangedRead && length != null && FileFormats.extension(entry.name) in EXIF_PREVIEW)
                    RangedStream(RangedBlocks(provider, entry, length, budget = EXIF_BUDGET, block = 64 * 1024L, kept = 4), length).use { stream -> embedded({ ExifInterface(stream) }, size) }
                else null
                preview ?: run {
                    if (!fullAllowed(length)) throw Skipped()
                    original().use { lease -> decodes.withPermit { decodeFile(lease.file, size) } }
                }
            }
        }

    /** Returns the cached thumbnail, or makes one with [create]; a null bitmap from [create] is remembered as a miss. */
    private suspend fun make(entry: FileEntry, size: Int, create: suspend () -> Bitmap?): File? = withContext(Dispatchers.IO) {
        root.mkdirs()
        val key = hash("${entry.id}:${entry.size}:${entry.modified}:${entry.etag}:$size")
        val target = File(root, "$key.jpg"); val miss = File(root, "$key.none")
        if (target.isFile) { target.setLastModified(System.currentTimeMillis()); return@withContext target }
        if (miss.exists()) return@withContext null
        val bitmap = try { create() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // A skipped download or a network failure may succeed later; only an unreadable file is a miss.
            if (e is Skipped || e is StorageException) return@withContext null
            null
        }
        if (bitmap == null) { runCatching { miss.createNewFile() }; return@withContext null }
        // Two tiles of the same file can race; each writes its own part and the first rename wins.
        val partial = File(root, "$key.${java.util.UUID.randomUUID()}.part")
        try {
            partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (!partial.renameTo(target) && !target.isFile) return@withContext null
        } finally { bitmap.recycle(); partial.delete() }
        added(target.length())
        target
    }

    /** The camera's embedded preview, upright, if it is sharp enough for a tile and shows the whole photo. */
    private fun embedded(open: () -> ExifInterface, size: Int): Bitmap? {
        val exif = try { open() } catch (_: IOException) { return null }
        val bytes = exif.thumbnailBytes ?: return null
        val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { w -> w > 0 } ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
        val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { h -> h > 0 } ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!embeddedUsable(bounds.outWidth, bounds.outHeight, width, height, size)) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return upright(bitmap, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
    }

    /** Running total of the thumbnail folder, measured from disk only when it first matters or the folder is full. */
    private var total = -1L
    @Synchronized private fun added(bytes: Long) {
        if (total < 0) total = root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0 else total += bytes
        if (total > maximum) prune()
    }

    private fun prune() {
        val files = root.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) { if (total <= maximum) break; val n = file.length(); if (file.delete()) total -= n }
        this.total = total
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

    companion object {
        /** Remote bytes one thumbnail may read before giving up. */
        const val REMOTE_BUDGET = 24 * MIB
        /** Remote bytes read looking for a photo's embedded preview; it sits in the header, well inside this. */
        const val EXIF_BUDGET = 512 * 1024L
        /** On a metered network, originals up to this size may still be fetched for a thumbnail. */
        const val METERED_FULL_LIMIT = 2 * MIB
        /** Formats whose embedded preview is read from the start of the file. */
        val EXIF_PREVIEW = setOf("jpg", "jpeg", "jfif") + FileFormats.rawImages

        /** Whether a photo thumbnail may download the original: always on the phone or unmetered, small files otherwise. */
        fun fullDownloadAllowed(local: Boolean, unmetered: Boolean, size: Long?): Boolean =
            local || unmetered || (size != null && size <= METERED_FULL_LIMIT)

        /**
         * An embedded preview is used when its short side reaches most of the tile size and its shape matches the photo,
         * so tiles stay sharp and cameras that letterbox a 4:3 preview of a 16:9 photo fall back to the original.
         */
        fun embeddedUsable(previewWidth: Int, previewHeight: Int, imageWidth: Int, imageHeight: Int, size: Int): Boolean {
            if (previewWidth <= 0 || previewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return false
            if (minOf(previewWidth, previewHeight) * 4 < size * 3) return false
            val preview = previewWidth.toDouble() / previewHeight; val image = imageWidth.toDouble() / imageHeight
            return kotlin.math.abs(preview - image) / image <= 0.03
        }

        /** Decodes [file] subsampled for a tile; ImageDecoder applies the EXIF orientation itself. Null when the format is unreadable. */
        private fun decodeFile(file: File, size: Int): Bitmap? = try {
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, size))
            }
            // Power-of-two sampling can leave the short side near twice the tile; the kept JPEG needs no more than the tile.
            val short = minOf(decoded.width, decoded.height)
            if (short <= size * 5 / 4) decoded
            else Bitmap.createScaledBitmap(decoded, decoded.width * size / short, decoded.height * size / short, true).also { if (it !== decoded) decoded.recycle() }
        } catch (_: ImageDecoder.DecodeException) { null }

        private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.setRotate(180f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
                else -> return bitmap
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
        }
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

/** A forward stream over [RangedBlocks]; skipping moves the position without fetching the skipped bytes. */
internal class RangedStream(private val blocks: RangedBlocks, private val length: Long) : InputStream() {
    private var position = 0L
    private var mark = 0L
    override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = blocks.readAt(position, b, off, len)
        if (n > 0) position += n
        return n
    }
    override fun skip(n: Long): Long { val moved = n.coerceIn(0, length - position); position += moved; return moved }
    override fun available(): Int = (length - position).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    override fun markSupported() = true
    override fun mark(readlimit: Int) { mark = position }
    override fun reset() { position = mark }
    override fun close() = blocks.close()
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
