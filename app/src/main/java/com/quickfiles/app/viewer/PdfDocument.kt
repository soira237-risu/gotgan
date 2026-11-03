package com.quickfiles.app.viewer

import android.content.*
import android.graphics.Bitmap
import android.os.*
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import com.quickfiles.app.AppGraph
import com.quickfiles.app.storage.FileEntry
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.UUID

class PdfDocument private constructor(private val context: Context, private val connection: ServiceConnection,
    private val service: IPdfService, private val input: ParcelFileDescriptor, private val thread: HandlerThread,
    private val channel: SeekableByteChannel, val pages: Int) : Closeable {
    suspend fun render(page: Int, width: Int): Bitmap = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "pdf-render-${UUID.randomUUID()}.raw")
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { output -> service.render(page, width, output) }
            decodeRawPage(file)
        } finally { file.delete() }
    }
    /** Reads the service's width/height header and ARGB pixels; the sandboxed process is not trusted to size them sensibly. */
    private fun decodeRawPage(file: File): Bitmap = java.io.FileInputStream(file).channel.use { channel ->
        val header = ByteBuffer.allocate(8)
        while (header.hasRemaining()) if (channel.read(header) < 0) error("PDF 페이지를 표시하지 못했습니다.")
        header.flip()
        val w = header.int; val h = header.int
        check(w in 1..PDF_MAX_WIDTH && h in 1..PDF_MAX_HEIGHT && channel.size() == 8L + w.toLong() * h * 4) { "PDF 페이지를 표시하지 못했습니다." }
        val pixels = ByteBuffer.allocate(w * h * 4)
        while (pixels.hasRemaining()) if (channel.read(pixels) < 0) error("PDF 페이지를 표시하지 못했습니다.")
        pixels.flip()
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(pixels) }
    }
    override fun close() {
        runCatching { service.close() }; runCatching { context.unbindService(connection) }
        runCatching { input.close() }; runCatching { channel.close() }; thread.quitSafely()
    }
    companion object {
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
