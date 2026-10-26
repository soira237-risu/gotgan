package com.quickfiles.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.*
import com.quickfiles.app.AppGraph
import com.quickfiles.app.data.*
import com.quickfiles.app.storage.*
import kotlinx.coroutines.*
import java.io.IOException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StorageDataSource(private val graph: AppGraph) : BaseDataSource(true) {
    private val retryBudget = ReadRetryBudget()
    private var handle: ReadHandle? = null
    private var reader: Job? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        if (dataSpec.uri.scheme == "fileexplorer" && dataSpec.uri.authority == "subtitle") {
            require(dataSpec.uri.pathSegments.size == 1 && dataSpec.position >= 0)
            val file = SubtitleFiles.file(graph.app, dataSpec.uri.lastPathSegment!!)
            val stream = java.io.FileInputStream(file)
            try {
                stream.channel.position(dataSpec.position)
                handle = ReadHandle(stream, (file.length() - dataSpec.position).coerceAtLeast(0))
                uri = dataSpec.uri
                remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, handle!!.length!!) else handle!!.length!!
                opened = true; transferStarted(dataSpec); return remaining
            } catch (e: Exception) { stream.close(); throw e }
        }
        val location = decodeMediaUri(dataSpec.uri)
        val ready = CompletableDeferred<ReadHandle>()
        reader = graph.scope.launch(retryBudget) {
            var stream: ReadHandle? = null
            try {
                val provider = graph.repository.provider(location.sourceId)
                val entry = provider.stat(location)
                stream = try { provider.openRead(location, dataSpec.position, entry.revision) }
                catch (e: StorageException) {
                    if (e.kind != StorageError.UNSUPPORTED) throw e
                    val full = provider.openRead(location, 0, entry.revision)
                    try {
                        var left = dataSpec.position; val buffer = ByteArray(128 * 1024)
                        while (left > 0) { ensureActive(); val n = full.stream.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt()); if (n < 0) throw IOException("Media offset unavailable"); left -= n }
                        ReadHandle(full.stream, entry.size?.minus(dataSpec.position)) { full.close() }
                    } catch (failure: Exception) { full.close(); throw failure }
                }
                ready.complete(stream)
                awaitCancellation()
            } catch (e: Exception) { ready.completeExceptionally(e) }
            finally { stream?.close() }
        }
        try {
            handle = runBlocking { ready.await() }
            uri = dataSpec.uri
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else handle!!.length ?: C.LENGTH_UNSET.toLong()
            opened = true; transferStarted(dataSpec); return remaining
        } catch (e: Exception) { close(); throw MediaReadError(e, retryBudget) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = try { handle?.stream?.read(buffer, offset, if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()) ?: return C.RESULT_END_OF_INPUT }
        catch (e: IOException) { throw MediaReadError(e, retryBudget) }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (remaining >= 0) remaining -= n
        bytesTransferred(n); return n
    }
    override fun getUri() = uri
    override fun close() {
        reader?.cancel(); reader = null
        runCatching { handle?.close() }; handle = null; uri = null
        if (opened) { opened = false; transferEnded() }
    }
    companion object {
        fun subtitleUri(id: String): Uri = Uri.Builder().scheme("fileexplorer").authority("subtitle").appendPath(id).build()
        fun mediaUri(location: StorageLocation): Uri = Uri.Builder().scheme("fileexplorer").authority("media").appendPath(android.util.Base64.encodeToString(location.encode().toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)).build()
        fun decodeMediaUri(uri: Uri): StorageLocation {
            require(uri.scheme == "fileexplorer" && uri.authority == "media" && uri.pathSegments.size == 1)
            return decodeLocation(String(android.util.Base64.decode(uri.lastPathSegment, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)))
        }
    }
}

internal class MediaReadError(cause: Throwable, val budget: ReadRetryBudget) : IOException("Media read failed", cause)
