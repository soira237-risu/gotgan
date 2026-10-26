package com.quickfiles.app

import android.graphics.ImageDecoder
import android.os.Environment
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.ExplorerViewModel
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class ExpandedFormatsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: ExplorerViewModel
    private lateinit var folder: StorageLocation
    private lateinit var directory: File
    @Before fun prepare() {
        val path = InstrumentationRegistry.getArguments().getString("playerMediaDir")
        Assume.assumeTrue("Generated player fixtures required", path != null)
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        directory = File(Environment.getExternalStorageDirectory(), path!!).canonicalFile
        require(directory.toPath().startsWith(root.toPath()) && directory.name.startsWith("validation-player-"))
        folder = StorageLocation(LOCAL_SOURCE, path)
        compose.runOnIdle { model = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java); model.home() }
        compose.waitUntil { model.state.value.profilesReady }
        compose.runOnIdle { model.navigate(folder) }
        compose.waitUntil(15000) { !model.state.value.loading && model.state.value.entries.isNotEmpty() }
    }
    @After fun finish() { if (::model.isInitialized) compose.runOnIdle { model.home() } }
    private fun open(name: String) {
        val entry = runBlocking { model.provider(LOCAL_SOURCE).stat(folder.child(name)) }
        compose.runOnIdle { model.open(entry) }
        compose.waitUntil(15000) { model.state.value.preview?.loading == false }
        assertNull(model.state.value.preview?.error)
    }
    @Test fun bitmapHeifAvifAndArchiveMembersDecode() {
        for (name in listOf("sample.bmp", "sample.heic", "sample.heif", "sample.avif")) {
            val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(directory, name)))
            assertEquals(480, image.width); assertEquals(640, image.height); image.recycle()
            open(name)
            compose.onNodeWithContentDescription("이미지 미리보기").assertIsDisplayed()
            compose.onNodeWithText("이미지를 표시하지 못했습니다.", substring = true).assertDoesNotExist()
            compose.runOnIdle { model.closePreview() }
        }
        open("formats.cbz")
        compose.runOnIdle { model.archivePage("notes.md") }
        compose.waitUntil(10000) { compose.onAllNodesWithText("한글 파일 미리보기").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("편집", substring = false).assertDoesNotExist()
        compose.runOnIdle { model.archivePage("sample.heic") }
        compose.onNodeWithContentDescription("이미지 미리보기").assertIsDisplayed()
    }
    @Test fun newTextTypesAreReadOnlyAndLargeTextIsWindowed() {
        for (name in listOf("notes.md", "notes.json", "notes.csv", "notes.yaml", "notes.properties", "captions.vtt")) {
            open(name)
            assertNotNull(model.state.value.preview?.document)
            compose.onNodeWithText("편집", substring = false).assertDoesNotExist()
            compose.runOnIdle { model.closePreview() }
        }
        open("large.log")
        assertFalse(model.state.value.preview!!.document!!.complete)
        assertTrue(model.state.value.preview!!.document!!.bytes.size <= 1024 * 1024)
        compose.onNodeWithText("다음 1MiB 이어 읽기").performClick()
        compose.waitUntil(15000) { model.state.value.preview?.loading == false }
        assertTrue(model.state.value.preview!!.document!!.start > 0)
    }
    @Test fun corruptedImageReportsFailure() {
        open("broken.avif")
        compose.waitUntil(15000) { compose.onAllNodesWithText("이미지를 표시하지 못했습니다.", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
