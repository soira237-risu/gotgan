package com.quickfiles.app

import com.quickfiles.app.playback.*
import com.quickfiles.app.storage.*
import org.junit.Assert.*
import org.junit.Test

class PlayerOptionsTest {
    @Test fun seekStaysInCurrentItemAndSupportsShortVideos() {
        assertEquals(0, seekTarget(3000, -10000, 30000))
        assertEquals(30000, seekTarget(26000, 10000, 30000))
        assertEquals(12000, seekTarget(2000, 10000, 30000))
        assertEquals(4000, seekTarget(0, 10000, 4000))
        assertEquals(0, seekTarget(0, 10000, -1))
    }
    @Test fun orientationUsesDisplayedPixelsAndOnlyUnappliedRotation() {
        assertNull(displayAspect(0, 1080))
        assertEquals(16f / 9, displayAspect(1920, 1080)!!, .0001f)
        assertEquals(9f / 16, displayAspect(1920, 1080, 1f, 90)!!, .0001f)
        assertEquals(1f, displayAspect(1080, 1080)!!, .0001f)
        assertEquals(16f / 9, displayAspect(720, 576, 64f / 45)!!, .0001f)
    }
    @Test fun formatRoutesAreConsistentAndDoNotEnableNewEditing() {
        fun file(name: String, dir: Boolean = false) = FileEntry(StorageLocation(LOCAL_SOURCE, name), name, dir)
        listOf("BMP", "heic", "heif", "avif").forEach { assertTrue(file("sample.$it").isImage); assertFalse(file("folder.$it", true).isImage) }
        listOf("md", "json", "csv", "vtt", "properties").forEach { assertTrue(file("sample.$it").isText); assertFalse(file("sample.$it").isEditableText) }
        assertTrue(file("sample.TXT").isEditableText)
        assertTrue(file("transport.ts").isVideo); assertFalse(file("transport.ts").isText)
        listOf("3gpp", "mpg", "mpeg", "flv").forEach { assertTrue(file("video.$it").isVideo) }
        listOf("m4b", "oga", "amr").forEach { assertTrue(file("audio.$it").isAudio) }
        assertFalse(file("book.epub").isText); assertFalse(file("bad.avif.exe").isImage)
    }
    @Test fun subtitleEncodingIsStrictAndKeepsKorean() {
        val sample = "1\n00:00:01,000 --> 00:00:04,000\n한글 자막\n"
        assertEquals(sample, subtitleText(sample.toByteArray(), false))
        assertEquals(sample, subtitleText(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + sample.toByteArray(Charsets.UTF_16LE), false))
        assertEquals(sample, subtitleText(sample.toByteArray(charset("MS949")), true))
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { subtitleText(sample.toByteArray(charset("MS949")), false) }
    }
    @Test fun timestampsRemainUsefulForLongVideo() {
        assertEquals("0:00", clockText(-1)); assertEquals("1:05", clockText(65000)); assertEquals("2:01:09", clockText(7269000))
    }
}
