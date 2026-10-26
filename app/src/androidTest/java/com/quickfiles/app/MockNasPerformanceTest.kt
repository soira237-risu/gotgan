package com.quickfiles.app

import android.os.Environment
import android.os.SystemClock
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import com.quickfiles.app.operations.TransferScheduler
import kotlinx.coroutines.flow.first
import com.quickfiles.app.data.ExplorerDatabase
import com.quickfiles.app.operations.OperationEngine
import com.quickfiles.app.storage.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MockNasPerformanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun httpsTenThousandEntriesAndStreamingOneGiBDownload() = runBlocking(Dispatchers.IO) {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("performance") == "true")
        assertTrue(Environment.isExternalStorageManager())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val background = InstrumentationRegistry.getArguments().getString("backgroundTransfer") == "true"
        val graph = AppGraph.get(context)
        val application = context.applicationContext as FixtureApplication
        var taskId: String? = null
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val run = File(root, "validation-${UUID.randomUUID()}").canonicalFile
        require(run.toPath().startsWith(root.toPath()) && run != root && run.mkdirs())
        val destination = StorageLocation(LOCAL_SOURCE, run.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        val db = Room.inMemoryDatabaseBuilder(context, ExplorerDatabase::class.java).build()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val serverContext = SSLContext.getInstance("TLS").apply { init(arrayOf(certificates.keyManager), null, java.security.SecureRandom()) }
        val listener = serverContext.serverSocketFactory.createServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager).build()
        val executor = Executors.newCachedThreadPool()
        val downloads = AtomicInteger()
        val total = 1024L * 1024 * 1024
        fun resource(path: String, directory: Boolean, size: Long = 0): String = "<d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype>${if (directory) "<d:collection/>" else ""}</d:resourcetype><d:getcontentlength>$size</d:getcontentlength><d:getetag>&quot;fixture-v1&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        fun serve(socket: Socket) { socket.use {
            val reader = it.getInputStream().bufferedReader(Charsets.UTF_8)
            val request = reader.readLine()?.split(' ') ?: return
            val headers = linkedMapOf<String, String>()
            while (true) { val line = reader.readLine() ?: return; if (line.isEmpty()) break; headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
            var remaining = headers["content-length"]?.toIntOrNull() ?: 0
            while (remaining > 0) { val buffer = CharArray(minOf(remaining, 4096)); val n = reader.read(buffer); if (n < 0) return; remaining -= n }
            check(headers["authorization"] == okhttp3.Credentials.basic("fixture", "fixture-password", Charsets.UTF_8))
            val out = it.getOutputStream()
            if (request[0] == "PROPFIND") {
                val path = request[1]
                val content = if (path.startsWith("/listing")) resource("/listing/", true) + (0 until 10000).joinToString("") { n -> resource("/listing/row-${n.toString().padStart(5, '0')}.txt", false, 64) } else resource("/large.bin", false, total)
                val bytes = ("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">$content</d:multistatus>").toByteArray()
                out.write("HTTP/1.1 207 Multi-Status\r\nContent-Length: ${bytes.size}\r\nContent-Type: application/xml\r\nConnection: close\r\n\r\n".toByteArray()); out.write(bytes)
            } else {
                check(request[0] == "GET" && request[1] == "/large.bin")
                check(headers["if-match"] == "\"fixture-v1\"")
                downloads.incrementAndGet()
                val offset = headers["range"]?.substringAfter("bytes=")?.substringBefore('-')?.toLong() ?: 0
                val range = if (offset > 0) "Content-Range: bytes $offset-${total - 1}/$total\r\n" else ""
                out.write("HTTP/1.1 ${if (offset > 0) "206 Partial Content" else "200 OK"}\r\nContent-Length: ${total - offset}\r\nETag: \"fixture-v1\"\r\n${range}Connection: close\r\n\r\n".toByteArray())
                val buffer = ByteArray(128 * 1024) { n -> ((n + offset) * 31 + 7).toByte() }
                var sent = offset
                while (sent < total) { val n = minOf(buffer.size.toLong(), total - sent).toInt(); out.write(buffer, 0, n); sent += n; if (background) Thread.sleep(80) }
            }
            out.flush()
        } }
        executor.execute { while (!listener.isClosed) { try { val socket = listener.accept(); executor.execute { runCatching { serve(socket) } } } catch (_: java.io.IOException) { break } } }
        try {
            check(run.usableSpace > total + 128 * 1024 * 1024)
            val nas = WebDavProvider("mock-nas", "https://127.0.0.1:${listener.localPort}/", "fixture", "fixture-password", client)
            val start = SystemClock.elapsedRealtime()
            val entries = sortedEntries(nas.list(StorageLocation("mock-nas", "listing")), SortOrder.NAME)
            assertEquals(10000, entries.size)
            val listingMs = SystemClock.elapsedRealtime() - start
            val local = AppGraph.get(context).repository.local
            val engine = if (background) graph.operations else OperationEngine(db.dao(), { if (it == LOCAL_SOURCE) local else nas })
            val copyStart = SystemClock.elapsedRealtime()
            if (background) {
                graph.ready.await()
                assertTrue("Do not execute other queued user work in a fixture test", graph.dao.tasks().first().none { it.state in listOf("QUEUED", "RUNNING") })
                application.providers["mock-nas"] = nas
                val mediaPath = requireNotNull(InstrumentationRegistry.getArguments().getString("mediaPath"))
                val mediaFile = File(Environment.getExternalStorageDirectory(), mediaPath).canonicalFile
                require(mediaFile.toPath().startsWith(root.toPath()) && mediaFile.parentFile!!.name.startsWith("validation-") && mediaFile.isFile)
                val media = local.stat(StorageLocation(LOCAL_SOURCE, mediaPath))
                graph.playback.play(media, listOf(media))
                withContext(Dispatchers.Main) { graph.playback.controller!!.seekTo(0); graph.playback.controller!!.repeatMode = Player.REPEAT_MODE_ONE }
            }
            val id = engine.enqueue("COPY", listOf(StorageLocation("mock-nas", "large.bin")), destination)
            taskId = id
            if (background) {
                withContext(Dispatchers.Main) { TransferScheduler.schedule(compose.activity, true) }
                delay(3000)
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
                var previousPosition = 0L
                var cumulativePosition = 0L
                withTimeout(20 * 60_000) {
                    while (true) {
                        delay(30_000)
                        val task = graph.dao.task(id)!!
                        val playing = withContext(Dispatchers.Main) { graph.playback.controller!!.isPlaying }
                        assertTrue("Media must keep playing while the transfer runs", playing)
                        val (position, duration) = withContext(Dispatchers.Main) { graph.playback.controller!!.let { it.currentPosition to it.duration } }
                        cumulativePosition += if (position >= previousPosition) position - previousPosition else duration - previousPosition + position
                        previousPosition = position
                        assertTrue("Playback clock must advance in background", cumulativePosition >= (SystemClock.elapsedRealtime() - copyStart - 5000) * 9 / 10)
                        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "ENDURANCE elapsedMs=${SystemClock.elapsedRealtime() - copyStart} state=${task.state} bytes=${task.bytes} mediaPlaying=$playing playedMs=$cumulativePosition\n") })
                        if (task.state == "COMPLETE") break
                        assertTrue("Background task failed: ${task.message}", task.state in listOf("QUEUED", "RUNNING"))
                    }
                }
                assertTrue("The throttled transfer must remain alive for ten minutes", SystemClock.elapsedRealtime() - copyStart >= 600_000)
            } else engine.drain()
            assertEquals("COMPLETE", engine.dao.task(id)!!.state)
            assertEquals(total, File(run, "large.bin").length()); assertEquals(1, downloads.get())
            val result = JSONObject().put("mockNasEntries", entries.size).put("listAndSortMs", listingMs).put("downloadBytes", total)
                .put("downloadAndSha256VerificationMs", SystemClock.elapsedRealtime() - copyStart).put("getRequests", downloads.get()).put("standardTls", true).put("backgroundTransferAndMedia", background)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "MOCK_NAS_PERFORMANCE $result\n") })
        } finally {
            if (background) { taskId?.let { graph.operations.pause(it, true) }; graph.playback.stop(); application.providers.remove("mock-nas") }
            listener.close(); executor.shutdownNow(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow(); db.close()
            require(run.canonicalFile.toPath().startsWith(root.toPath()) && run != root); run.deleteRecursively()
        }
    }
}
