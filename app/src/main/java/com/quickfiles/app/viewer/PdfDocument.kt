package com.quickfiles.app.viewer

import android.content.*
import android.graphics.Bitmap
import android.os.*
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.util.LruCache
import com.quickfiles.app.AppGraph
import com.quickfiles.app.storage.FileEntry
import kotlinx.coroutines.*
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

class PdfDocument private constructor(private val context: Context, private val connection: ServiceConnection,
    private val service: IPdfService, private val input: ParcelFileDescriptor, private val thread: HandlerThread,
    private val channel: SeekableByteChannel, val pages: Int) : Closeable {
    /** Recently rendered pages, so scrolling back to a page shows it again without rendering it again. */
    private val rendered = object : LruCache<Long, Bitmap>(RENDER_CACHE_BYTES) { override fun sizeOf(key: Long, value: Bitmap) = value.byteCount }

    suspend fun render(page: Int, width: Int): Bitmap = withContext(Dispatchers.IO) {
        val key = page.toLong() shl 32 or width.toLong()
        rendered.get(key)?.let { return@withContext it }
        val (read, write) = ParcelFileDescriptor.createPipe()
        val bitmap = coroutineScope {
            // The service writes into the pipe while this side reads, so neither waits on a full pipe buffer.
            val pixels = async { ParcelFileDescriptor.AutoCloseInputStream(read).use { readPage(java.io.DataInputStream(it.buffered(256 * 1024))) } }
            try { write.use { service.render(page, width, it) } } catch (e: Exception) { pixels.cancel(); throw e }
            pixels.await()
        }
        rendered.put(key, bitmap)
        bitmap
    }
    override fun close() {
        rendered.evictAll()
        runCatching { service.close() }; runCatching { context.unbindService(connection) }
        runCatching { input.close() }; runCatching { channel.close() }; thread.quitSafely()
    }
    companion object {
        private val RENDER_CACHE_BYTES = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(64L * 1024 * 1024).toInt()

        /** Reads one page the isolated service rendered. The service is untrusted, so the size is checked before allocating. */
        internal fun readPage(input: java.io.DataInputStream): Bitmap {
            val w = input.readInt(); val h = input.readInt()
            if (w !in 1..2048 || h !in 1..4096) throw java.io.IOException("PDF 페이지 크기가 올바르지 않습니다.")
            val bytes = ByteArray(w * h * 4); input.readFully(bytes)
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
        }

        suspend fun open(context: Context, entry: FileEntry): PdfDocument = withContext(Dispatchers.IO) {
            val graph = AppGraph.get(context)
            val channel = graph.cache.channel(graph.repository.provider(entry.location.sourceId), entry)
            val thread = HandlerThread("pdf-range").apply { start() }
            var input: ParcelFileDescriptor? = null
            var bound = false
            val ready = CompletableDeferred<IPdfService>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete(IPdfService.Stub.asInterface(binder)) }
                override fun onServiceDisconnected(name: ComponentName) { ready.completeExceptionally(IllegalStateException("PDF 처리 프로세스가 종료되었습니다.")) }
                override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(IllegalStateException("PDF 서비스를 시작하지 못했습니다.")) }
            }
            try {
                input = context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                    object : ProxyFileDescriptorCallback() {
                        override fun onGetSize() = channel.size()
                        override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                            synchronized(channel) { channel.position(offset); channel.read(ByteBuffer.wrap(data, 0, size)).coerceAtLeast(0) }
                        } catch (_: Exception) { throw ErrnoException("pdf_read", OsConstants.EIO) }
                        override fun onRelease() { channel.close() }
                    }, Handler(thread.looper))
                bound = context.bindService(Intent(context, PdfService::class.java), connection, Context.BIND_AUTO_CREATE)
                check(bound)
                val service = withTimeout(20000) { ready.await() }
                val count = service.open(input)
                PdfDocument(context, connection, service, input, thread, channel, count)
            } catch (e: Exception) {
                if (bound) context.unbindService(connection)
                input?.close(); channel.close(); thread.quitSafely(); throw e
            }
        }
    }
}
