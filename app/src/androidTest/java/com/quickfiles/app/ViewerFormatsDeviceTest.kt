package com.quickfiles.app

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Environment
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.ExplorerViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class ViewerFormatsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: ExplorerViewModel
    private lateinit var location: StorageLocation
    private lateinit var directory: File
    @Before fun prepare() {
        val media = InstrumentationRegistry.getArguments().getString("mediaPath")
        Assume.assumeTrue(media != null)
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        directory = File(Environment.getExternalStorageDirectory(), media!!).canonicalFile.parentFile!!
        require(directory.toPath().startsWith(root.toPath()) && directory != root && directory.name.startsWith("validation-"))
        location = StorageLocation(LOCAL_SOURCE, directory.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        compose.runOnIdle { model = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java); model.home() }
        compose.waitUntil { model.state.value.profilesReady }
        compose.runOnIdle { model.navigate(location) }
        compose.waitUntil(15_000) { !model.state.value.loading && model.state.value.entries.isNotEmpty() }
    }
    @After fun finish() { if (::model.isInitialized) compose.runOnIdle { model.home() } }
    private fun assertAnimatedGifFrames(label: String) {
        val image = compose.onNodeWithContentDescription("이미지 미리보기")
        val expected = setOf(android.graphics.Color.RED, android.graphics.Color.BLUE)
        val seen = mutableSetOf<Int>()
        val evidence = File(directory, "gif-$label-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val start = android.os.SystemClock.uptimeMillis()
        while (seen != expected && android.os.SystemClock.uptimeMillis() - start < 3_000) {
            val frame = image.captureToImage()
            val pixels = frame.toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2].toArgb()
            if (center in expected && seen.add(center)) {
                android.util.Log.i("GifFrameEvidence", "$label ${android.os.SystemClock.uptimeMillis() - start}ms ${Integer.toHexString(center)}")
                File(evidence, "${Integer.toHexString(center)}.png").outputStream().use { frame.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            if (seen != expected) Thread.sleep(100)
        }
        assertEquals("Both literal fixture frames must render in $label", expected, seen)
    }
    @Test fun pngWebpAndAnimatedGifRenderInCommonViewer() {
        for (name in listOf("sample.png", "sample.webp", "animated.gif")) {
            val file = File(directory, name); assertTrue(file.isFile)
            val entry = model.state.value.entries.single { it.name == name }
            compose.runOnIdle { model.open(entry) }
            compose.waitUntil(10_000) { model.state.value.preview?.asset != null }
            compose.onNodeWithContentDescription("이미지 미리보기").assertIsDisplayed()
            if (name.endsWith("gif")) {
                assertTrue(ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) is AnimatedImageDrawable)
                assertAnimatedGifFrames("file")
            }
            compose.onNodeWithText("이미지를 표시하지 못했습니다.", substring = true).assertDoesNotExist()
            compose.onNodeWithTag("close-preview").performClick()
        }
    }
    @Test fun utf16EditorSavesWholeFileWithBomAndCrLfThroughPersistentWorker() {
        val name = "edit-${UUID.randomUUID()}.txt"
        val file = File(directory, name)
        val original = "첫 줄\r\n둘째 줄"
        file.writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + original.toByteArray(Charsets.UTF_16LE))
        val graph = model.graph
        try {
            val entry = runBlocking { model.provider(LOCAL_SOURCE).stat(location.child(name)) }
            compose.runOnIdle { model.open(entry) }
            compose.waitUntil(10_000) { model.state.value.preview?.document != null }
            compose.onNodeWithText("편집", substring = false).performClick()
            compose.onNode(hasSetTextAction() and hasText(original)).performTextReplacement("첫 줄\n둘째 줄\n추가한 줄")
            compose.onNodeWithText("저장", substring = false).performClick()
            val expected = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "첫 줄\r\n둘째 줄\r\n추가한 줄".toByteArray(Charsets.UTF_16LE)
            compose.waitUntil(30_000) { file.exists() && file.readBytes().contentEquals(expected) }
            assertArrayEquals(expected, file.readBytes())
            runBlocking {
                val backup = graph.dao.trashItems().first().single { it.original == location.child(name).path }
                val provider = graph.repository.local
                assertArrayEquals(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + original.toByteArray(Charsets.UTF_16LE), provider.openRead(StorageLocation(LOCAL_SOURCE, backup.payloadPath)).use { it.stream.readBytes() })
            }
        } finally {
            compose.runOnIdle { model.closePreview() }
            runBlocking(Dispatchers.IO) {
                val owned = graph.dao.trashItems().first().filter { it.source == LOCAL_SOURCE && it.original == location.child(name).path }
                if (owned.isNotEmpty()) { graph.operations.enqueue("PURGE", extra = JSONObject().put("trashIds", JSONArray(owned.map { it.id }))); graph.operations.drain() }
            }
            if (file.exists()) file.delete()
        }
    }
    @Test fun pdfPageRestoreAndCbzImageTextNavigation() {
        val token = UUID.randomUUID().toString()
        val pdfFile = File(directory, "$token.pdf")
        val zipFile = File(directory, "$token.cbz")
        val pdf = android.graphics.pdf.PdfDocument()
        try {
            repeat(3) { index ->
                val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 400, index + 1).create())
                page.canvas.drawColor(listOf(android.graphics.Color.BLUE, android.graphics.Color.GREEN, android.graphics.Color.RED)[index])
                pdf.finishPage(page)
            }
            pdfFile.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { out ->
            for (name in listOf("sample.png", "sample.webp", "animated.gif")) {
                out.putNextEntry(java.util.zip.ZipEntry(name)); File(directory, name).inputStream().use { it.copyTo(out) }; out.closeEntry()
            }
            out.putNextEntry(java.util.zip.ZipEntry("notes.txt")); out.write("압축 안의 텍스트".toByteArray()); out.closeEntry()
        }
        fun open(file: File) {
            val entry = runBlocking { model.provider(LOCAL_SOURCE).stat(location.child(file.name)) }
            compose.runOnIdle { model.open(entry) }
            compose.waitUntil(15_000) { model.state.value.preview?.loading == false }
            assertNull(model.state.value.preview?.error)
        }
        try {
            open(pdfFile)
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("PDF 1페이지").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pdf-page-input").performTextReplacement("2")
            compose.onNodeWithText("이동", substring = false).performClick()
            val id = model.state.value.preview!!.entry.id
            compose.waitUntil(15_000) { runBlocking { model.graph.dao.saved("pdf:$id")?.value == "1" } }
            compose.onNodeWithContentDescription("PDF 확대").performClick()
            compose.onNodeWithTag("close-preview").performClick()
            open(pdfFile)
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("PDF 2페이지").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("PDF 2페이지").assertIsDisplayed()
            compose.onNodeWithTag("close-preview").performClick()
            open(zipFile)
            compose.onNodeWithText("sample.png", substring = false).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("이미지 미리보기").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("다음", substring = false).performClick()
            compose.onNodeWithText("sample.webp", substring = false).assertIsDisplayed()
            compose.onNodeWithText("내부 목록").performClick()
            compose.onNodeWithText("animated.gif", substring = false).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("이미지 미리보기").fetchSemanticsNodes().isNotEmpty() }
            val gif = compose.onNodeWithContentDescription("이미지 미리보기")
            gif.assertIsDisplayed()
            assertAnimatedGifFrames("cbz")
            compose.onNodeWithText("이미지를 표시하지 못했습니다.", substring = true).assertDoesNotExist()
            compose.onNodeWithText("내부 목록").performClick()
            compose.onNodeWithText("notes.txt", substring = false).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("압축 안의 텍스트").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("압축 안의 텍스트").assertIsDisplayed()
        } finally {
            compose.runOnIdle { model.closePreview() }
            pdfFile.delete(); zipFile.delete()
        }
    }
    @Test fun utf16SurrogatesAndManualCp949KeepWindowBoundaries() {
        val file = File(directory, "boundary-${UUID.randomUUID()}.txt")
        try {
            for (encoding in listOf("UTF-16LE", "UTF-16BE", "MS949")) {
                val legacy = encoding == "MS949"
                val prefix = "a".repeat(if (legacy) com.quickfiles.app.viewer.TextDocument.CHUNK - 1 else com.quickfiles.app.viewer.TextDocument.CHUNK / 2 - 2)
                val suffix = if (legacy) "한글\n끝" else "😀한글\n끝"
                val bom = when (encoding) { "UTF-16LE" -> byteArrayOf(0xff.toByte(), 0xfe.toByte()); "UTF-16BE" -> byteArrayOf(0xfe.toByte(), 0xff.toByte()); else -> byteArrayOf() }
                file.writeBytes(bom + (prefix + suffix).toByteArray(charset(encoding)))
                val entry = runBlocking { model.provider(LOCAL_SOURCE).stat(location.child(file.name)) }
                compose.runOnIdle { model.open(entry) }
                compose.waitUntil(15_000) { model.state.value.preview?.loading == false }
                if (legacy) {
                    compose.onNodeWithText("CP949", substring = false).performClick()
                    compose.waitUntil(15_000) { model.state.value.preview?.let { it.cp949 && !it.loading } == true }
                }
                assertNull(model.state.value.preview?.error)
                assertEquals(prefix, model.state.value.preview!!.decodedText)
                compose.onNodeWithText("다음 1MiB 이어 읽기").performClick()
                compose.waitUntil(15_000) { model.state.value.preview?.document?.start?.let { it > 0 } == true }
                assertNull(model.state.value.preview?.error)
                assertEquals(suffix, model.state.value.preview!!.decodedText)
                val firstLine = suffix.substringBefore('\n')
                compose.waitUntil(10_000) { compose.onAllNodesWithText(firstLine, substring = false).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(firstLine, substring = false).assertIsDisplayed()
                compose.onNodeWithText("끝", substring = false).assertIsDisplayed()
                compose.onNodeWithTag("close-preview").performClick()
            }
        } finally { compose.runOnIdle { model.closePreview() }; file.delete() }
    }
    @Test fun exifOrientationAndLongTextContinuation() {
        val token = UUID.randomUUID().toString()
        val jpeg = File(directory, "$token.jpg"); val text = File(directory, "$token.txt")
        val bitmap = android.graphics.Bitmap.createBitmap(240, 120, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply {
            drawColor(android.graphics.Color.RED)
            drawRect(120f, 0f, 240f, 120f, android.graphics.Paint().apply { color = android.graphics.Color.GREEN })
        }
        jpeg.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, it) }; bitmap.recycle()
        android.media.ExifInterface(jpeg.path).apply { setAttribute(android.media.ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
        text.writeText("a".repeat(com.quickfiles.app.viewer.TextDocument.CHUNK - 1) + "한글\n끝")
        fun open(file: File) {
            val entry = runBlocking { model.provider(LOCAL_SOURCE).stat(location.child(file.name)) }
            compose.runOnIdle { model.open(entry) }
            compose.waitUntil(15_000) { model.state.value.preview?.loading == false }
            assertNull(model.state.value.preview?.error)
        }
        try {
            open(jpeg)
            // Decode is asynchronous; compare rendered pixels only once Coil has supplied the image.
            compose.waitUntil(10_000) {
                val pixels = compose.onNodeWithContentDescription("이미지 미리보기").captureToImage().toPixelMap()
                val top = pixels[pixels.width / 2, pixels.height * 3 / 10]
                val bottom = pixels[pixels.width / 2, pixels.height * 7 / 10]
                top.red > .8f && top.green < .2f && bottom.green > .8f && bottom.red < .2f
            }
            compose.onNodeWithTag("close-preview").performClick()
            open(text)
            assertFalse(model.state.value.preview!!.document!!.complete)
            compose.onNodeWithText("다음 1MiB 이어 읽기").performClick()
            compose.waitUntil(10_000) { model.state.value.preview?.document?.start?.let { it > 0 } == true }
            assertEquals("한글\n끝", model.state.value.preview!!.decodedText)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("한글", substring = false).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("한글", substring = false).assertIsDisplayed()
            compose.onNodeWithText("끝", substring = false).assertIsDisplayed()
            compose.onNodeWithText("이전 부분").performClick()
            compose.waitUntil(10_000) { model.state.value.preview?.document?.start == 0L }
            compose.onNodeWithText("편집용 전체 읽기").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.state.value.preview?.document?.complete == true }
            assertEquals(text.readText(), model.state.value.preview!!.decodedText)
        } catch (failure: Throwable) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { screen ->
                File(directory, "text-failure-$token.png").outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; screen.recycle()
            }
            throw failure
        } finally { compose.runOnIdle { model.closePreview() }; jpeg.delete(); text.delete() }
    }
}
