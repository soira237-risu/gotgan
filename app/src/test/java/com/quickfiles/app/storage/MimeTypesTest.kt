package com.quickfiles.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Open with" must hand other apps a concrete type whenever the extension is known, and a wildcard otherwise. */
class MimeTypesTest {
    private val platform = mapOf("pdf" to "application/pdf", "jpg" to "image/jpeg", "mp4" to "video/mp4", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    private fun type(name: String) = MimeTypes.forName(name) { platform[it] }

    @Test fun sharedFilesGetTheNarrowestCommonType() {
        assertEquals("image/jpeg", MimeTypes.common(listOf("image/jpeg", "IMAGE/JPEG")))
        assertEquals("image/*", MimeTypes.common(listOf("image/jpeg", "image/png")))
        assertEquals("*/*", MimeTypes.common(listOf("image/jpeg", "video/mp4")))
        assertEquals("*/*", MimeTypes.common(emptyList()))
        assertEquals("*/*", MimeTypes.common(listOf("*/*", "image/png")))
    }
    @Test fun platformTypesWinAndExtensionsIgnoreCase() {
        assertEquals("application/pdf", type("견적서.PDF"))
        assertEquals("image/jpeg", type("IMG_0001.Jpg"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", type("보고서.docx"))
    }

    @Test fun builtInTableFillsPlatformGaps() {
        assertEquals("application/vnd.android.package-archive", type("app-release.apk"))
        assertEquals("application/epub+zip", type("소설.epub"))
        assertEquals("video/x-matroska", type("영상.mkv"))
        assertEquals("video/mp2t", type("00001.MTS"))
        assertEquals("text/markdown", type("README.md"))
    }

    @Test fun platformOverridesTableWhenItKnowsTheType() {
        assertEquals("application/x-test", MimeTypes.forName("a.apk") { if (it == "apk") "application/x-test" else null })
        assertEquals("application/vnd.android.package-archive", MimeTypes.forName("a.apk") { " " })
    }

    @Test fun unknownOrMissingExtensionsUseWildcard() {
        assertEquals(MimeTypes.ANY, type("Makefile"))
        assertEquals(MimeTypes.ANY, type("archive.unknownext"))
        assertEquals(MimeTypes.ANY, type(".hidden"))
        assertEquals(MimeTypes.ANY, type("끝점."))
    }

    @Test fun pickerPatternsMatchTypeAndSubtype() {
        assertTrue(MimeTypes.accepts(emptyList(), "application/pdf"))
        assertTrue(MimeTypes.accepts(listOf("*/*"), "video/mp4"))
        assertTrue(MimeTypes.accepts(listOf("image/*"), "image/jpeg"))
        assertTrue(MimeTypes.accepts(listOf("IMAGE/*"), "image/png"))
        assertFalse(MimeTypes.accepts(listOf("image/*"), "video/mp4"))
        assertTrue(MimeTypes.accepts(listOf("image/*", "application/pdf"), "application/pdf"))
        assertFalse(MimeTypes.accepts(listOf("application/pdf"), "application/zip"))
        assertTrue(MimeTypes.accepts(listOf("image"), "image/gif"))
    }

    @Test fun extensionsForUnnamedStreams() {
        val platform = mapOf("image/jpeg" to "jpg")
        assertEquals("jpg", MimeTypes.extensionFor("image/jpeg", platform::get))
        assertEquals("jpg", MimeTypes.extensionFor("Image/JPEG; charset=binary", platform::get))
        assertEquals("epub", MimeTypes.extensionFor("application/epub+zip", platform::get))
        assertNull(MimeTypes.extensionFor("image/*", platform::get))
        assertNull(MimeTypes.extensionFor(null, platform::get))
        assertNull(MimeTypes.extensionFor("application/x-unknown", platform::get))
    }
}
