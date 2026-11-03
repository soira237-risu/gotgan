package com.quickfiles.app

import com.quickfiles.app.playback.M2tsLayout
import com.quickfiles.app.playback.M2tsReader
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Byte-level checks; [M2tsPlaybackDeviceTest] plays the same file through ExoPlayer. */
class M2tsStreamTest {
    /** 1 s H.264 + AC-3 written by `ffmpeg -f mpegts -mpegts_m2ts_mode 1`, as AVCHD camcorders do. */
    private val camcorder: ByteArray = javaClass.classLoader!!.getResourceAsStream("media/avchd.mts")!!.use { it.readBytes() }
    private val stripped: ByteArray = (camcorder.indices step M2tsLayout.PACKET)
        .flatMap { camcorder.copyOfRange(it + M2tsLayout.HEADER, it + M2tsLayout.PACKET).toList() }.toByteArray()

    @Test fun fixtureIsBdavAndStrippingYieldsPlainTransportStream() {
        assertEquals(0, camcorder.size % M2tsLayout.PACKET)
        assertEquals(M2tsLayout.Kind.M2TS, M2tsLayout.detect(camcorder, camcorder.size))
        assertEquals(M2tsLayout.Kind.PLAIN, M2tsLayout.detect(stripped, stripped.size))
        assertTrue((stripped.indices step 188).all { stripped[it] == 0x47.toByte() })
        assertEquals(M2tsLayout.Kind.PLAIN, M2tsLayout.detect(Random(7).nextBytes(M2tsLayout.PROBE_BYTES).also { it[4] = 0x11 }, M2tsLayout.PROBE_BYTES))
        assertEquals(M2tsLayout.Kind.M2TS, M2tsLayout.detect(camcorder, 100))
        assertEquals(M2tsLayout.Kind.PLAIN, M2tsLayout.detect(camcorder, 3))
    }

    @Test fun positionsMapBetweenFileAndPayloadStream() {
        assertEquals(4L, M2tsLayout.physical(0)); assertEquals(191L, M2tsLayout.physical(187)); assertEquals(196L, M2tsLayout.physical(188))
        assertEquals(stripped.size.toLong(), M2tsLayout.logicalEnd(camcorder.size.toLong()))
        assertEquals(0L, M2tsLayout.logicalEnd(4)); assertEquals(1L, M2tsLayout.logicalEnd(5)); assertEquals(188L, M2tsLayout.logicalEnd(196))
        for (logical in 0L until 2000L) {
            val physical = M2tsLayout.physical(logical)
            assertEquals(stripped[logical.toInt()], camcorder[physical.toInt()])
            assertEquals(logical + 1, M2tsLayout.logicalEnd(physical + 1))
            assertEquals(logical, M2tsLayout.logicalEnd(physical))
        }
        assertEquals(188L, M2tsLayout.physicalLength(0, 188)); assertEquals(7L, M2tsLayout.physicalLength(186, 3)); assertEquals(1L, M2tsLayout.physicalLength(187, 1))
    }

    @Test fun readerRemovesHeadersFromAnyStartWithShortUpstreamReads() {
        listOf(0L, 1L, 187L, 188L, 1000L, 188L * 50 + 17, stripped.size - 1L).forEach { start ->
            listOf(-1L, 1L, 300L).forEach { length ->
                val expectedEnd = if (length < 0) stripped.size else minOf(stripped.size.toLong(), start + length).toInt()
                val physicalStart = M2tsLayout.physical(start).toInt()
                var cursor = physicalStart
                val chunk = Random(start).nextInt(1, 7)
                val reader = M2tsReader(start, length) { buffer, offset, wanted ->
                    if (cursor >= camcorder.size) -1 else minOf(wanted, chunk, camcorder.size - cursor).also {
                        camcorder.copyInto(buffer, offset, cursor, cursor + it); cursor += it
                    }
                }
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(97)
                while (true) { val n = reader.read(buffer, 0, buffer.size); if (n < 0) break; assertTrue(n > 0); out.write(buffer, 0, n) }
                assertArrayEquals("start=$start length=$length", stripped.copyOfRange(start.toInt(), expectedEnd), out.toByteArray())
            }
        }
    }
}
