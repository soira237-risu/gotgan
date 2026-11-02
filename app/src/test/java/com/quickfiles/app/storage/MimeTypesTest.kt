package com.quickfiles.app.storage

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Open with" must hand other apps a concrete type whenever the extension is known, and a wildcard otherwise. */
class MimeTypesTest {
    private val platform = mapOf("pdf" to "application/pdf", "jpg" to "image/jpeg", "mp4" to "video/mp4", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    private fun type(name: String) = MimeTypes.forName(name) { platform[it] }

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
}
