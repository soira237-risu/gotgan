package com.quickfiles.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.quickfiles.app.storage.FileFormats

/**
 * Presents BDAV (.mts/.m2ts) files to the extractors as ordinary 188-byte transport streams.
 * Other media and plain .ts files pass straight through to [upstream].
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class M2tsDataSource(
    private val upstream: DataSource,
    private val candidate: (Uri) -> Boolean = ::isTransportStreamName,
) : DataSource {
    private var reader: M2tsReader? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (!candidate(dataSpec.uri) || kind(dataSpec) != M2tsLayout.Kind.M2TS) return upstream.open(dataSpec)
        val start = dataSpec.position
        val physicalStart = M2tsLayout.physical(start)
        val physicalLength = if (dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
            else M2tsLayout.physicalLength(start, dataSpec.length)
        val opened = upstream.open(dataSpec.buildUpon().setPosition(physicalStart).setLength(physicalLength).build())
        val remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            opened == C.LENGTH_UNSET.toLong() -> C.LENGTH_UNSET.toLong()
            else -> (M2tsLayout.logicalEnd(physicalStart + opened) - start).coerceAtLeast(0)
        }
        reader = M2tsReader(start, remaining) { buffer, offset, length -> upstream.read(buffer, offset, length) }
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val active = reader ?: return upstream.read(buffer, offset, length)
        val n = active.read(buffer, offset, length)
        return if (n == M2tsReader.END) C.RESULT_END_OF_INPUT else n
    }

    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        reader = null
        upstream.close()
    }

    /** Reads the first packets once per file; extractors reopen the same URI for every load and seek. */
    private fun kind(dataSpec: DataSpec): M2tsLayout.Kind {
        val key = dataSpec.uri.toString()
        synchronized(kinds) { kinds[key] }?.let { return it }
        val probe = ByteArray(M2tsLayout.PROBE_BYTES)
        var filled = 0
        try {
            upstream.open(dataSpec.buildUpon().setPosition(0).setLength(M2tsLayout.PROBE_BYTES.toLong()).build())
            while (filled < probe.size) {
                val n = upstream.read(probe, filled, probe.size - filled)
                if (n == C.RESULT_END_OF_INPUT) break
                filled += n
            }
        } finally { upstream.close() }
        val kind = M2tsLayout.detect(probe, filled)
        synchronized(kinds) { kinds[key] = kind }
        return kind
    }

    companion object {
        private val kinds = object : LinkedHashMap<String, M2tsLayout.Kind>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, M2tsLayout.Kind>) = size > 64
        }

        fun isTransportStreamName(uri: Uri): Boolean {
            if (uri.scheme != "fileexplorer" || uri.authority != "media") return false
            val name = runCatching { StorageDataSource.decodeMediaUri(uri).path.substringAfterLast('/') }.getOrNull() ?: return false
            return FileFormats.extension(name) in M2tsLayout.extensions
        }
    }
}
