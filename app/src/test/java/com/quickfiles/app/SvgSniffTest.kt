package com.quickfiles.app

import com.quickfiles.app.storage.SvgSniff
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cached previews lose their `.svg` extension, so SVG routing depends entirely on this byte check. */
class SvgSniffTest {
    private fun sniff(text: String) = SvgSniff.looksLikeSvg(text.toByteArray())

    @Test fun plainAndPreambledSvgAreRecognised() {
        assertTrue(sniff("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"/>"""))
        assertTrue(sniff("<?xml version=\"1.0\"?>\n<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"x\">\n<svg>"))
        assertTrue(sniff("\n  \t<svg>"))
        assertTrue(SvgSniff.looksLikeSvg(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<svg/>".toByteArray()))
        // Editors such as Illustrator write long comment/metadata preambles; Coil's own 1KiB window misses these.
        assertTrue(sniff("<!-- " + "x".repeat(8 * 1024) + " -->\n<svg width=\"4\" height=\"4\">"))
    }

    @Test fun otherContentIsRejected() {
        assertFalse(sniff(""))
        assertFalse(sniff("<html><body>no vector here</body></html>"))
        assertFalse(sniff("<svgfoo/>"))
        assertFalse(sniff("text before <svg>"))
        assertFalse(SvgSniff.looksLikeSvg(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), '<'.code.toByte()) + "<svg>".toByteArray()))
        assertFalse(SvgSniff.looksLikeSvg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        // Only the first LIMIT bytes are examined.
        assertFalse(sniff("<!-- " + "x".repeat(SvgSniff.LIMIT) + " --><svg>"))
        assertFalse(SvgSniff.looksLikeSvg("<svg>".toByteArray(), length = 3))
    }
}
