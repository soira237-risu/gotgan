package com.quickfiles.app.playback

/**
 * BDAV MPEG-2 transport streams (.mts/.m2ts from AVCHD camcorders and Blu-ray) prefix every
 * 188-byte TS packet with a 4-byte TP_extra_header. Media3's TsExtractor only accepts 188-byte
 * packets, so playback presents these files as a plain TS stream with the headers removed.
 *
 * "Logical" positions address the stripped 188-byte stream; "physical" positions address the file.
 */
internal object M2tsLayout {
    const val PACKET = 192
    const val PAYLOAD = 188
    const val HEADER = PACKET - PAYLOAD
    private const val SYNC = 0x47.toByte()
    /** Three packets are enough to tell the two packet sizes apart without trusting a lucky sync byte. */
    const val PROBE_BYTES = PACKET * 3

    val extensions = setOf("mts", "m2ts", "m2t", "ts")

    enum class Kind { M2TS, PLAIN }

    /** Classifies the first bytes of a file; [length] may be shorter than [PROBE_BYTES] for tiny files. */
    fun detect(data: ByteArray, length: Int): Kind {
        val bdavPackets = (0 until 3).map { HEADER + it * PACKET }.filter { it < length }
        if (bdavPackets.isNotEmpty() && bdavPackets.all { data[it] == SYNC }) return Kind.M2TS
        return Kind.PLAIN
    }

    fun physical(logical: Long): Long {
        require(logical >= 0)
        return logical / PAYLOAD * PACKET + HEADER + logical % PAYLOAD
    }

    /** Number of payload bytes that lie before [physicalEnd]; also converts a file length. */
    fun logicalEnd(physicalEnd: Long): Long {
        require(physicalEnd >= 0)
        return physicalEnd / PACKET * PAYLOAD + (physicalEnd % PACKET - HEADER).coerceAtLeast(0)
    }

    /** Physical byte count needed from [physical] ([logical]) to cover [length] logical bytes. */
    fun physicalLength(logical: Long, length: Long): Long {
        require(length > 0)
        return physical(logical + length - 1) + 1 - physical(logical)
    }
}

/**
 * Strips the 4-byte headers from an upstream reader that was opened at [M2tsLayout.physical]
 * of [logicalStart]. [upstream] follows the DataSource contract: bytes read, or -1 at the end.
 */
internal class M2tsReader(logicalStart: Long, private var remaining: Long, private val upstream: (ByteArray, Int, Int) -> Int) {
    private var payloadLeft = M2tsLayout.PAYLOAD - (logicalStart % M2tsLayout.PAYLOAD).toInt()
    private val header = ByteArray(M2tsLayout.HEADER)

    /** [remaining] < 0 means the logical length is unknown. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return END
        if (payloadLeft == 0) {
            var skipped = 0
            while (skipped < M2tsLayout.HEADER) {
                val n = upstream(header, skipped, M2tsLayout.HEADER - skipped)
                if (n < 0) return END
                skipped += n
            }
            payloadLeft = M2tsLayout.PAYLOAD
        }
        var wanted = minOf(length, payloadLeft)
        if (remaining > 0) wanted = minOf(wanted.toLong(), remaining).toInt()
        val n = upstream(buffer, offset, wanted)
        if (n < 0) return END
        payloadLeft -= n
        if (remaining > 0) remaining -= n
        return n
    }

    companion object { const val END = -1 }
}
