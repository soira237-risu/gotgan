package com.quickfiles.app

import android.os.Environment
import android.os.SystemClock
import android.view.Choreographer
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.data.ExplorerDatabase
import com.quickfiles.app.operations.OperationEngine
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.ExplorerViewModel
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in measurements on the installed APK; these are never reported as host/JVM performance. */
class PerformanceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun tenThousandRowsThirtyReturnsAndOneGiBVerifiedCopy() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("performance") == "true")
        assertTrue(Environment.isExternalStorageManager())
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val run = File(root, "validation-${UUID.randomUUID()}").canonicalFile
        require(run.toPath().startsWith(root.toPath()) && run != root && run.mkdirs())
        val location = StorageLocation(LOCAL_SOURCE, run.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        val graph = AppGraph.get(compose.activity)
        val db = Room.inMemoryDatabaseBuilder(compose.activity, ExplorerDatabase::class.java).build()
        var model: ExplorerViewModel? = null
        try {
            val listing = File(run, "listing").also { check(it.mkdir()) }
            repeat(10000) { File(listing, "row-${it.toString().padStart(5, '0')}.txt").writeText("곳간 performance fixture $it") }
            compose.runOnIdle { model = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java); model!!.home() }
            compose.waitUntil(15_000) { model!!.state.value.profilesReady }
            val started = SystemClock.elapsedRealtimeNanos()
            compose.runOnIdle { model!!.navigate(location.child("listing")) }
            compose.waitUntil(60_000) { !model!!.state.value.loading && model!!.state.value.entries.size == 10000 }
            val listMs = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
            compose.onAllNodesWithTag("folder-list").onFirst().performScrollToIndex(299)
            compose.waitUntil { model!!.state.value.index == 299 }
            val entries = model!!.state.value.entries
            val row = entries[299]
            val times = mutableListOf<Double>()
            val queries = StorageReadMetrics.count(LOCAL_SOURCE)
            File(listing, "new-after-query.txt").writeText("A fresh query would include this file")
            repeat(30) {
                compose.runOnIdle { model!!.open(row) }
                compose.waitUntil(10_000) { model!!.state.value.preview?.document != null }
                val drawn = CountDownLatch(1); var start = 0L; var elapsed = 0L
                // Close action through two frame callbacks: layout/draw must get a frame before sampling.
                compose.runOnIdle {
                    start = SystemClock.elapsedRealtimeNanos(); model!!.closePreview()
                    Choreographer.getInstance().postFrameCallback { Choreographer.getInstance().postFrameCallback {
                        elapsed = SystemClock.elapsedRealtimeNanos() - start; drawn.countDown()
                    } }
                }
                assertTrue(drawn.await(5, TimeUnit.SECONDS)); compose.waitForIdle()
                assertSame(entries, model!!.state.value.entries)
                assertEquals(299, model!!.state.value.index)
                assertFalse(model!!.state.value.entries.any { it.name == "new-after-query.txt" })
                times += elapsed / 1e6
            }
            val p95 = times.sorted()[28]
            assertEquals("Closing viewers must not issue a directory query", queries, StorageReadMetrics.count(LOCAL_SOURCE))
            val large = File(run, "one-gib.bin")
            check(run.usableSpace > 3L * 1024 * 1024 * 1024)
            val block = ByteArray(1024 * 1024) { (it * 31 + 7).toByte() }
            val sha = MessageDigest.getInstance("SHA-256")
            large.outputStream().use { out -> repeat(1024) { out.write(block); sha.update(block) }; out.fd.sync() }
            check(File(run, "copied").mkdir())
            val engine = OperationEngine(db.dao(), graph.repository::provider)
            val copyStarted = SystemClock.elapsedRealtime()
            runBlocking(Dispatchers.IO) {
                val id = engine.enqueue("COPY", listOf(location.child(large.name)), location.child("copied"))
                engine.drain(); assertEquals("COMPLETE", db.dao().task(id)!!.state)
            }
            val copied = File(run, "copied/one-gib.bin")
            assertEquals(1024L * 1024 * 1024, copied.length())
            val targetHash = MessageDigest.getInstance("SHA-256")
            copied.inputStream().use { input -> while (true) { val n = input.read(block); if (n < 0) break; targetHash.update(block, 0, n) } }
            assertArrayEquals(sha.digest(), targetHash.digest())
            val result = JSONObject().put("localEntries", 10000).put("listLoadMs", listMs).put("returnSamplesMs", times)
                .put("returnP95Ms", p95).put("sameListingObject", true).put("returnListRequests", 0).put("localCopyBytes", copied.length())
                .put("copyAndIndependentHashMs", SystemClock.elapsedRealtime() - copyStarted)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "PERFORMANCE $result\n") })
            assertTrue("Return p95 was $p95 ms", p95 <= 250)
        } finally {
            model?.let { compose.runOnIdle { it.home() } }
            db.close()
            require(run.canonicalFile.toPath().startsWith(root.toPath()) && run != root)
            run.deleteRecursively()
        }
    }
}
