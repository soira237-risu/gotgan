package com.quickfiles.app

import android.os.Environment
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.ExplorerViewModel
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.io.File

class ExpandedWebDavDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun newImagesTextArchiveAndSubtitlesReadThroughVerifiedHttpsRanges() {
        val path = InstrumentationRegistry.getArguments().getString("playerMediaDir")
        Assume.assumeTrue(path != null)
        val directory = File(Environment.getExternalStorageDirectory(), path!!).canonicalFile
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        require(directory.toPath().startsWith(root.toPath()) && directory.name.startsWith("validation-player-"))
        val allowed = setOf("sample.heic", "sample.avif", "notes.json", "formats.cbz", "video.3gpp", "captions.srt")
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false) }
        val reads = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val name = request.requestUrl!!.pathSegments.last()
                if (name !in allowed) return MockResponse().setResponseCode(404)
                val file = File(directory, name)
                if (request.method == "PROPFIND") return MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/$name</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>${file.length()}</d:getcontentlength><d:getetag>&quot;test-v1&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>")
                if (request.method != "GET") return MockResponse().setResponseCode(405)
                reads.incrementAndGet()
                val range = request.getHeader("Range")
                val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toLong() ?: 0L
                val end = range?.substringAfter('-')?.toLongOrNull()?.coerceAtMost(file.length() - 1) ?: file.length() - 1
                val data = ByteArray((end - start + 1).toInt())
                java.io.RandomAccessFile(file, "r").use { it.seek(start); it.readFully(data) }
                return MockResponse().setResponseCode(if (range == null) 200 else 206).setHeader("ETag", "\"test-v1\"").apply {
                    if (range != null) setHeader("Content-Range", "bytes $start-$end/${file.length()}")
                }.setBody(Buffer().write(data))
            }
        }
        server.start()
        val application = compose.activity.application as FixtureApplication
        val graph = application.appGraph
        val id = "expanded-mock"
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        val provider = WebDavProvider(id, server.url("/").toString(), "fixture", "fixture", client)
        application.providers[id] = provider
        var model: ExplorerViewModel? = null
        try {
            compose.runOnIdle { model = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java); model!!.home() }
            for (name in listOf("sample.heic", "sample.avif", "notes.json", "formats.cbz")) {
                val entry = runBlocking { provider.stat(StorageLocation(id, name)) }
                compose.runOnIdle { model!!.open(entry) }
                compose.waitUntil(20000) { model!!.state.value.preview?.loading == false }
                assertNull("$name", model!!.state.value.preview?.error)
                compose.runOnIdle { model!!.closePreview() }
            }
            val media = runBlocking { provider.stat(StorageLocation(id, "video.3gpp")) }
            val subtitle = runBlocking { provider.stat(StorageLocation(id, "captions.srt")) }
            runBlocking { graph.playback.play(media, listOf(media, subtitle)) }
            compose.waitUntil(20000) { graph.playback.state.value.playing || graph.playback.state.value.error != null }
            assertNull(graph.playback.state.value.error)
            runBlocking(Dispatchers.Main) { graph.playback.controller!!.pause(); graph.playback.attachSubtitle(subtitle, false) }
            compose.waitUntil(20000) { graph.playback.details.value.tracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT && it.isSelected } }
            assertTrue(reads.get() >= 6)
        } finally {
            compose.runOnIdle { model?.closePreview() }
            runBlocking { graph.playback.stop() }
            application.providers.remove(id); server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
