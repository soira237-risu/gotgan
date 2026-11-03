package com.quickfiles.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a minimal uncompressed 8-bit LinearRaw DNG: left half red, right half blue.
 * Real camera files are too large to commit, and this keeps the RAW decode test self-contained.
 */
internal object TinyDng {
    private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)
    private const val BYTE = 1; private const val ASCII = 2; private const val SHORT = 3; private const val LONG = 4
    private const val RATIONAL = 5; private const val SRATIONAL = 10

    private fun le(size: Int, fill: ByteBuffer.() -> Unit) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()
    private fun shorts(vararg v: Int) = Entry(0, SHORT, v.size, le(v.size * 2) { v.forEach { putShort(it.toShort()) } })
    private fun longs(vararg v: Int) = Entry(0, LONG, v.size, le(v.size * 4) { v.forEach { putInt(it) } })
    private fun ascii(text: String) = (text.toByteArray(Charsets.US_ASCII) + 0).let { Entry(0, ASCII, it.size, it) }
    private fun rationals(type: Int, vararg v: Int) = Entry(0, type, v.size, le(v.size * 8) { v.forEach { putInt(it); putInt(1) } })
    private fun Entry.tag(tag: Int) = Entry(tag, type, count, data)

    fun build(width: Int = 64, height: Int = 48): ByteArray {
        val pixels = ByteArray(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) {
            val i = (y * width + x) * 3
            if (x < width / 2) pixels[i] = 0xFF.toByte() else pixels[i + 2] = 0xFF.toByte()
        }
        val entries = listOf(
            longs(0).tag(254), longs(width).tag(256), longs(height).tag(257), shorts(8, 8, 8).tag(258),
            shorts(1).tag(259), shorts(34892).tag(262), ascii("Gotgan").tag(271), ascii("Test").tag(272),
            longs(0).tag(273), shorts(1).tag(274), shorts(3).tag(277), longs(height).tag(278),
            longs(pixels.size).tag(279), shorts(1).tag(284),
            Entry(50706, BYTE, 4, byteArrayOf(1, 4, 0, 0)), Entry(50707, BYTE, 4, byteArrayOf(1, 1, 0, 0)),
            ascii("Gotgan Test").tag(50708), rationals(SRATIONAL, 1, 0, 0, 0, 1, 0, 0, 0, 1).tag(50721),
            rationals(RATIONAL, 1, 1, 1).tag(50728), shorts(21).tag(50778),
        ).sortedBy { it.tag }
        val ifdSize = 2 + entries.size * 12 + 4
        var extra = 8 + ifdSize
        val extraOffsets = entries.map { e -> if (e.data.size > 4) extra.also { extra += (e.data.size + 1) and 1.inv() } else -1 }
        val pixelOffset = extra
        val out = ByteBuffer.allocate(pixelOffset + pixels.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        out.putShort(entries.size.toShort())
        entries.forEachIndexed { index, e ->
            out.putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.count)
            val data = if (e.tag == 273) le(4) { putInt(pixelOffset) } else e.data
            if (extraOffsets[index] >= 0) out.putInt(extraOffsets[index]) else out.put(data.copyOf(4))
        }
        out.putInt(0)
        entries.forEachIndexed { index, e -> if (extraOffsets[index] >= 0) { out.position(extraOffsets[index]); out.put(e.data) } }
        out.position(pixelOffset); out.put(pixels)
        return out.array()
    }
}
