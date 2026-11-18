package com.quickfiles.app.viewer

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

class ChannelReadsTest {
    /** Like the remote cache channel: each read stops at the end of the current block. */
    private class BlockChannel(private val data: ByteArray, private val block: Int) : SeekableByteChannel {
        private var position = 0L
        override fun read(dst: ByteBuffer): Int {
            if (position >= data.size) return -1
            val blockEnd = (position / block + 1) * block
            val n = minOf(dst.remaining().toLong(), blockEnd - position, data.size - position).toInt()
            dst.put(data, position.toInt(), n); position += n; return n
        }
        override fun position() = position
        override fun position(newPosition: Long): SeekableByteChannel { position = newPosition; return this }
        override fun size() = data.size.toLong()
        override fun write(src: ByteBuffer) = throw UnsupportedOperationException()
        override fun truncate(size: Long) = throw UnsupportedOperationException()
        override fun isOpen() = true
        override fun close() {}
    }

    @Test fun readsAcrossBlockBoundariesUntilTheRequestIsFull() {
        val data = ByteArray(10_000) { (it % 253).toByte() }
        val channel = BlockChannel(data, 4096)
        val out = ByteArray(5000)
        assertEquals("a read crossing a block end is not cut short", 5000, readFully(channel, 3000, out, 5000))
        assertArrayEquals(data.copyOfRange(3000, 8000), out)
    }

    @Test fun stopsOnlyAtTheEnd() {
        val data = ByteArray(10_000) { it.toByte() }
        val out = ByteArray(4096)
        assertEquals(1000, readFully(BlockChannel(data, 4096), 9000, out, 4096))
        assertEquals(0, readFully(BlockChannel(data, 4096), 10_000, out, 4096))
    }
}
