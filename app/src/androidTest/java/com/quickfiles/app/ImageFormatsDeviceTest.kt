package com.quickfiles.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.quickfiles.app.storage.FileFormats
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DNG, SVG, ICO and JFIF must decode through the same Coil loaders the viewer and grid thumbnails use.
 * Every fixture is a red-left / blue-right image built here, so no external media is needed. Each one is also
 * decoded from a `.full` copy, because that is how cached previews reach the loaders (no image extension).
 */
class ImageFormatsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    @Before fun prepare() { directory = File(context.cacheDir, "image-formats-test").apply { deleteRecursively(); check(mkdirs()) } }
    @After fun cleanup() { directory.deleteRecursively() }

    private fun halves(width: Int, height: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) for (x in 0 until width) setPixel(x, y, if (x < width / 2) Color.RED else Color.BLUE)
    }
    private fun Bitmap.encode(format: Bitmap.CompressFormat) = ByteArrayOutputStream().also { compress(format, 100, it) }.toByteArray()

    private fun ico(png: ByteArray, width: Int, height: Int) = ByteBuffer.allocate(22 + png.size).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(0).putShort(1).putShort(1)
        .put(width.toByte()).put(height.toByte()).put(0).put(0).putShort(1).putShort(32).putInt(png.size).putInt(22)
        .put(png).array()

    // BOM plus a comment longer than Coil's own 1KiB sniff window, as editors such as Illustrator write.
    private val svg = "﻿<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- ${"generator metadata ".repeat(120)} -->\n" +
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"64\" height=\"48\" viewBox=\"0 0 64 48\">" +
        "<rect width=\"32\" height=\"48\" fill=\"#ff0000\"/><rect x=\"32\" width=\"32\" height=\"48\" fill=\"#0000ff\"/></svg>"

    private fun fixtures(): Map<String, ByteArray> {
        val bitmap = halves(64, 48)
        return mapOf(
            "camera.dng" to TinyDng.build(64, 48),
            "vector.svg" to svg.toByteArray(Charsets.UTF_8),
            "icon.ico" to ico(bitmap.encode(Bitmap.CompressFormat.PNG), 64, 48),
            "photo.jfif" to bitmap.encode(Bitmap.CompressFormat.JPEG),
        )
    }

    private fun decode(loader: ImageLoader, file: File): Bitmap = runBlocking {
        val request = ImageRequest.Builder(context).data(file).size(256)
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
        val result = loader.execute(request)
        assertTrue("${file.name}: ${result}", result is SuccessResult)
        val bitmap = (result as SuccessResult).image.toBitmap()
        // Loaders may hand out GPU-only bitmaps; reading pixels needs a software copy.
        if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    }

    private fun assertHalves(label: String, bitmap: Bitmap) {
        assertEquals("$label aspect", 64f / 48f, bitmap.width.toFloat() / bitmap.height, 0.05f)
        val left = bitmap.getPixel(bitmap.width / 4, bitmap.height / 2)
        val right = bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 2)
        assertTrue("$label left ${Integer.toHexString(left)}", Color.red(left) > 180 && Color.blue(left) < 90 && Color.green(left) < 90)
        assertTrue("$label right ${Integer.toHexString(right)}", Color.blue(right) > 180 && Color.red(right) < 90 && Color.green(right) < 90)
    }

    @Test fun newFormatsDecodeInViewerAndThumbnailLoaders() {
        val graph = AppGraph.get(context)
        for ((name, bytes) in fixtures()) {
            assertTrue(name, FileFormats.isImage(name))
            val named = File(directory, name).apply { writeBytes(bytes) }
            val cached = File(directory, "$name.full").apply { writeBytes(bytes) }
            for ((loaderName, loader) in listOf("viewer" to graph.imageLoader, "thumbnail" to graph.thumbnailLoader))
                for (file in listOf(named, cached)) assertHalves("$loaderName ${file.name}", decode(loader, file))
        }
    }

    @Test fun nonSvgMarkupIsNotRoutedToTheSvgDecoder() {
        val graph = AppGraph.get(context)
        val html = File(directory, "page.svg.full").apply { writeText("<html><body>not an image</body></html>") }
        val result = runBlocking { graph.imageLoader.execute(ImageRequest.Builder(context).data(html).size(64).build()) }
        assertFalse(result is SuccessResult)
    }
}
