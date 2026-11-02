package com.quickfiles.app

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Cache copies are named `*.full`; the provider must still report the real file's type to receiving apps. */
class OpenWithDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun providerReportsTypeFromDisplayNameForCacheCopies() {
        val directory = File(context.cacheDir, "content-v1/open-with-test").also { it.mkdirs() }
        val file = File(directory, "0123abcd.full").apply { writeBytes("%PDF-1.4\n".toByteArray()) }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file, "견적서.pdf")
            assertEquals("application/pdf", context.contentResolver.getType(uri))
            val apk = FileProvider.getUriForFile(context, "${context.packageName}.files", file, "app.apk")
            assertEquals("application/vnd.android.package-archive", context.contentResolver.getType(apk))
            val unknown = FileProvider.getUriForFile(context, "${context.packageName}.files", file, "notes.unknownext")
            assertEquals("application/octet-stream", context.contentResolver.getType(unknown))
        } finally { directory.deleteRecursively() }
    }

}
