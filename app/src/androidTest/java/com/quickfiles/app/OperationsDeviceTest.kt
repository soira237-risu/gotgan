package com.quickfiles.app

import android.os.Environment
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.data.*
import com.quickfiles.app.operations.*
import com.quickfiles.app.storage.*
import com.quickfiles.app.viewer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OperationsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var fixture: File
    private lateinit var location: StorageLocation
    private lateinit var db: ExplorerDatabase
    @Before fun setup() {
        assertTrue(Environment.isExternalStorageManager())
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        fixture = File(root, "validation-${UUID.randomUUID()}").canonicalFile
        require(fixture.toPath().startsWith(root.toPath()) && fixture != root && fixture.mkdirs())
        location = StorageLocation(LOCAL_SOURCE, fixture.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        db = Room.inMemoryDatabaseBuilder(context, ExplorerDatabase::class.java).build()
    }
    @After fun cleanup() {
        if (::db.isInitialized) db.close()
        if (::fixture.isInitialized) {
            val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
            require(fixture.canonicalFile.toPath().startsWith(root.toPath()) && fixture != root && fixture.name.startsWith("validation-"))
            fixture.deleteRecursively()
        }
    }
    @Test fun roomJournalZipExtractTrashAndRestoreOnRealPhone() = runBlocking {
        val graph = AppGraph.get(context)
        val engine = OperationEngine(db.dao(), graph.repository::provider, File(context.filesDir, "transfer-staging"), graph.cache::channel)
        File(fixture, "source.txt").writeText("실기 ZIP 검증\r\n한글")
        val zip = engine.enqueue("ZIP", listOf(location.child("source.txt")), location.child("test.zip"))
        engine.drain(); assertEquals("COMPLETE", db.dao().task(zip)!!.state)
        val extract = engine.enqueue("EXTRACT", listOf(location.child("test.zip")), location.child("unpacked"), extra = JSONObject().put("entries", JSONArray(listOf("source.txt"))))
        engine.drain(); assertEquals("COMPLETE", db.dao().task(extract)!!.state)
        assertEquals(File(fixture, "source.txt").readText(), File(fixture, "unpacked/source.txt").readText())
        val trash = engine.enqueue("TRASH", listOf(location.child("source.txt")))
        engine.drain(); assertEquals("COMPLETE", db.dao().task(trash)!!.state)
        assertFalse(File(fixture, "source.txt").exists())
        val record = db.dao().trashItems().first().single()
        val restore = engine.enqueue("RESTORE", extra = JSONObject().put("trashIds", JSONArray(listOf(record.id))))
        engine.drain(); assertEquals("COMPLETE", db.dao().task(restore)!!.state)
        assertEquals("실기 ZIP 검증\r\n한글", File(fixture, "source.txt").readText())
    }
    @Test fun pdfRendersThroughIsolatedProcess() = runBlocking {
        val output = File(fixture, "test.pdf")
        val pdf = android.graphics.pdf.PdfDocument()
        try {
            val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 400, 1).create())
            page.canvas.drawColor(android.graphics.Color.WHITE)
            page.canvas.drawRect(25f, 25f, 275f, 375f, android.graphics.Paint().apply { color = android.graphics.Color.BLUE })
            pdf.finishPage(page); output.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        val source = AppGraph.get(context).repository.provider(LOCAL_SOURCE)
        val document = PdfDocument.open(context, source.stat(location.child("test.pdf")))
        try {
            assertEquals(1, document.pages)
            val image = document.render(0, 600)
            assertEquals(600, image.width); assertEquals(800, image.height)
            assertEquals(android.graphics.Color.BLUE, image.getPixel(300, 400))
            image.recycle()
        } finally { document.close() }
    }
    @Test fun configuredNasRoundTripUsesOnlyOwnedRunFolder() = runBlocking {
        Assume.assumeTrue("Explicit opt-in required for approved live NAS fixtures", InstrumentationRegistry.getArguments().getString("nasWrites") == "true")
        val graph = AppGraph.get(context)
        val profile = graph.repository.connections.profiles.first().single { it.name == "Synology NAS" }
        val nas = graph.repository.writer(profile.id)
        val parent = StorageLocation(profile.id, "home/FileExplorerTest")
        if (nas.find(parent) == null) nas.createDirectory(parent)
        val remote = parent.child(fixture.name)
        assertNull(nas.find(remote)); nas.createDirectory(remote)
        val marker = "곳간 ${fixture.name}".toByteArray()
        nas.put(remote.child("owner.txt"), marker.size.toLong(), { marker.inputStream() })
        val engine = OperationEngine(db.dao(), graph.repository::provider)
        File(fixture, "roundtrip.txt").writeText("한글 파일왕복 · " + "verified content ".repeat(10000))
        val source = location.child("roundtrip.txt")
        val upload = engine.enqueue("COPY", listOf(source), remote); engine.drain()
        assertEquals("COMPLETE", db.dao().task(upload)!!.state)
        File(fixture, "returned").mkdir()
        val download = engine.enqueue("MOVE", listOf(remote.child("roundtrip.txt")), location.child("returned")); engine.drain()
        assertEquals("COMPLETE", db.dao().task(download)!!.state)
        assertArrayEquals(File(fixture, "roundtrip.txt").readBytes(), File(fixture, "returned/roundtrip.txt").readBytes())
        val trash = db.dao().trashItems().first().single()
        val restore = engine.enqueue("RESTORE", extra = JSONObject().put("trashIds", JSONArray(listOf(trash.id)))); engine.drain()
        assertEquals("COMPLETE", db.dao().task(restore)!!.state)
        // Retain this run's NAS fixtures and marker for inspection; never recursively delete the NAS root.
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "NAS_ROUNDTRIP scope=/${remote.path} upload=passed verifiedMove=passed restore=passed\n") })
    }
}
