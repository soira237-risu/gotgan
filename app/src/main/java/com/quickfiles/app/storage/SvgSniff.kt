package com.quickfiles.app.storage

/**
 * Cached previews are stored as `*.full` files, so decoders never see the `.svg` extension and must sniff the bytes.
 * Coil's own check wants `<` as the very first byte and `<svg` within 1KiB, which rejects files that start with a
 * UTF-8 BOM, a blank line, or a long editor comment/DOCTYPE preamble. This one skips those and looks further.
 */
object SvgSniff {
    const val LIMIT = 64 * 1024
    private val tag = "<svg".toByteArray()

    fun looksLikeSvg(head: ByteArray, length: Int = head.size): Boolean {
        val end = minOf(length, head.size, LIMIT)
        var start = 0
        if (end >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) start = 3
        while (start < end && head[start].toInt().toChar().isWhitespace()) start++
        if (start >= end || head[start] != '<'.code.toByte()) return false
        outer@ for (i in start..end - tag.size) {
            for (j in tag.indices) if (head[i + j] != tag[j]) continue@outer
            val next = i + tag.size
            // `<svg` must be the whole element name, not a prefix such as `<svgfoo`.
            if (next == end || head[next].toInt().toChar().let { it.isWhitespace() || it == '>' || it == '/' }) return true
        }
        return false
    }
}
