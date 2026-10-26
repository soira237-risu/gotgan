package com.quickfiles.app

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.ExplorerViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import com.quickfiles.app.data.ExplorerSettings
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExplorerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: ExplorerViewModel
    private lateinit var fixture: File
    private lateinit var location: StorageLocation
    private lateinit var originalSettings: ExplorerSettings

    @Before fun prepare() {
        assertTrue("Grant all-files permission before device tests", Environment.isExternalStorageManager())
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        fixture = File(root, "validation-${UUID.randomUUID()}").canonicalFile
        check(fixture.toPath().startsWith(root.toPath()) && fixture != root)
        check(fixture.mkdirs())
        repeat(320) { index -> File(fixture, "file_${index.toString().padStart(3, '0')}.txt").writeText("곳간 기기 검증 $index\n한글 텍스트 미리보기\n원본은 테스트 전용 파일입니다.") }
        File(fixture, "utf16.txt").writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "UTF16 한글 확인".toByteArray(Charsets.UTF_16LE))
        File(fixture, "cp949.txt").writeBytes("CP949 한글 확인".toByteArray(charset("MS949")))
        val bitmap = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(25, 55, 110))
            drawCircle(1200f, 800f, 500f, Paint().apply { color = Color.rgb(115, 190, 235) })
            drawText("곳간", 380f, 860f, Paint().apply { color = Color.WHITE; textSize = 190f; isAntiAlias = true })
        }
        File(fixture, "image.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        originalSettings = runBlocking { AppGraph.get(compose.activity).settings.settings.first() }
        runBlocking { AppGraph.get(compose.activity).settings.save(originalSettings.copy(grid = false)) }
        AppGraph.get(compose.activity).workspace.activate(0); AppGraph.get(compose.activity).workspace.split(false)
        location = StorageLocation(LOCAL_SOURCE, fixture.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        compose.runOnIdle { model = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java); model.home() }
        compose.waitUntil(10_000) { model.state.value.profilesReady }
    }

    @After fun cleanup() {
        if (::model.isInitialized) compose.runOnIdle { model.home() }
        if (::originalSettings.isInitialized) runBlocking { AppGraph.get(compose.activity).settings.save(originalSettings) }
        if (::fixture.isInitialized) {
            val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
            check(fixture.canonicalFile.toPath().startsWith(root.toPath()) && fixture.canonicalFile != root && fixture.name.startsWith("validation-"))
            fixture.deleteRecursively()
        }
    }

    private fun browseFixture() {
        compose.runOnIdle { model.navigate(location) }
        compose.waitUntil(10_000) { !model.state.value.loading && model.state.value.entries.size == 323 }
    }

    @Test fun realFilesPreviewRestores300thRowWithoutReloadAndRefreshFindsNewFile() {
        browseFixture()
        compose.onNodeWithTag("folder-list").performScrollToIndex(300)
        compose.waitUntil { model.state.value.index == 300 }
        val index = model.state.value.index
        val offset = model.state.value.offset
        val selected = model.state.value.entries[300]
        compose.onNodeWithTag("entry-${selected.location.path}").performClick()
        compose.waitUntil(10_000) { model.state.value.preview?.text != null }
        compose.onNodeWithText("한글 텍스트 미리보기").assertIsDisplayed()
        File(fixture, "added-after-list.txt").writeText("Only explicit refresh should see this.")
        compose.onNodeWithTag("close-preview").performClick()
        compose.waitUntil { model.state.value.preview == null }
        compose.runOnIdle {
            assertEquals(index, model.state.value.index)
            assertEquals(offset, model.state.value.offset)
            assertFalse(model.state.value.entries.any { it.name == "added-after-list.txt" })
        }
        compose.onNodeWithTag("explorer-menu").performClick(); compose.onNodeWithTag("refresh-folder").performClick()
        compose.waitUntil(10_000) { !model.state.value.loading && model.state.value.entries.any { it.name == "added-after-list.txt" } }
    }

    @Test fun imageZoomRotationAndActivityRecreation() {
        browseFixture()
        val image = model.state.value.entries.first { it.isImage }
        compose.onNodeWithTag("folder-list").performScrollToNode(hasTestTag("entry-${image.location.path}"))
        compose.onNodeWithTag("entry-${image.location.path}").performClick()
        compose.waitUntil(10_000) { model.state.value.preview?.asset?.isFile == true }
        compose.onNodeWithContentDescription("이미지 미리보기").performTouchInput { doubleClick() }
        val original = compose.activity.requestedOrientation
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("이미지 미리보기").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("이미지 미리보기").assertIsDisplayed()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = original }
        }
        compose.onNodeWithTag("close-preview").performClick()
        compose.onNodeWithTag("folder-list").assertExists()
    }

    @Test fun localPermissionDoesNotBlockConnectionEditor() {
        compose.runOnIdle { model.permissionChanged(false) }
        compose.onNodeWithTag("storage-add").performClick()
        compose.onNodeWithTag("connection-url").assertExists()
        compose.onNodeWithTag("connection-name").performTextInput("HTTPS 검사")
        compose.onNodeWithTag("connection-url").performTextInput("http://example.com/")
        compose.onNodeWithTag("connection-user").performTextInput("test")
        compose.onNodeWithTag("connection-test").performScrollTo().performClick()
        compose.waitUntil(5_000) { model.state.value.editor?.result != null }
        compose.onNodeWithText("HTTPS WebDAV 주소만 지원합니다.").assertExists()
        compose.runOnIdle { model.closeEditor(); model.permissionChanged(Environment.isExternalStorageManager()) }
    }

    @Test fun keystoreRoundtripAndRemoval() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ConnectionStore(context)
        val profile = store.save(null, "Device test", "https://example.com/dav/", "fixture-user", "ONLY-FIXTURE-SECRET-42")
        try {
            assertEquals("ONLY-FIXTURE-SECRET-42", store.password(profile))
            val bytes = File(context.filesDir, "datastore/connections.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(bytes.contains("ONLY-FIXTURE-SECRET-42"))
        } finally { store.remove(profile.id) }
    }

    @Test fun configuredNasDisplaysCertificateError() {
        val address = InstrumentationRegistry.getArguments().getString("nasUrl")
        Assume.assumeTrue("Pass nasUrl to explicitly opt in to a live certificate check", address != null)
        compose.onNodeWithTag("storage-add").performClick()
        compose.onNodeWithTag("connection-name").performTextInput("NAS 인증서 확인")
        compose.onNodeWithTag("connection-url").performTextInput(address!!)
        compose.onNodeWithTag("connection-user").performTextInput("certificate-diagnostic")
        compose.onNodeWithTag("connection-test").performScrollTo().performClick()
        compose.waitUntil(30_000) { model.state.value.editor?.result != null }
        assertTrue(model.state.value.editor!!.result!!.contains("인증서"))
        compose.onNodeWithTag("connection-result").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { model.closeEditor() }
    }
}
