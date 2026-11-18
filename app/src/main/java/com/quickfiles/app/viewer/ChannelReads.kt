package com.quickfiles.app.viewer

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * Fills `data[0, size)` from [offset], stopping early only at the end of the channel. A remote cache channel
 * returns at most the rest of one 1 MiB block per read, and the PDF proxy file would take a short read as
 * the end of the file.
 */
internal fun readFully(channel: SeekableByteChannel, offset: Long, data: ByteArray, size: Int): Int {
    channel.position(offset)
    val buffer = ByteBuffer.wrap(data, 0, size)
    while (buffer.hasRemaining()) {
        val n = channel.read(buffer)
        if (n < 0) break
    }
    return size - buffer.remaining()
}
