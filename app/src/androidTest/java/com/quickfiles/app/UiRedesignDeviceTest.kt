package com.quickfiles.app

import android.content.pm.ActivityInfo
import android.os.Environment
import android.os.SystemClock
import android.view.Choreographer
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.data.*
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

class UiRedesignDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun identityAndSelectionSurviveViewDensitySwitchViewerAndRotation() {
        val graph = AppGraph.get(compose.activity)
        val original = runBlocking { graph.settings.settings.first() }
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        val run = File(root, "validation-${UUID.randomUUID()}").canonicalFile
        require(run.toPath().startsWith(root.toPath()) && run != root && run.mkdirs())
        val performance = InstrumentationRegistry.getArguments().getString("uiPerformance") == "true"
        val count = if (performance) 10000 else 400
        repeat(count) { File(run, "row-${it.toString().padStart(5, '0')}.txt").writeText("곳간 UI fixture $it") }
        val remote = InstrumentationRegistry.getArguments().getString("uiMockNas") == "true"
        val server = if (remote) MockWebServer() else null
        var client: OkHttpClient? = null
        val fixtureApp = compose.activity.application as FixtureApplication
        val source = if (remote) "ui-mock" else LOCAL_SOURCE
        if (server != null) {
            val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            server.useHttps(certificates.sslSocketFactory(), false)
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            val body = "곳간 UI HTTPS fixture"
            fun resource(path: String, directory: Boolean) = "<d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype>${if (directory) "<d:collection/>" else ""}</d:resourcetype><d:getcontentlength>${body.length}</d:getcontentlength><d:getetag>&quot;fixture&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != okhttp3.Credentials.basic("fixture", "fixture-password", Charsets.UTF_8)) return MockResponse().setResponseCode(401)
                    return if (request.method == "PROPFIND") {
                        val path = request.path!!
                        val content = if (path.endsWith('/')) resource(path, true) + (0 until count).joinToString("") { resource("/listing/row-${it.toString().padStart(5, '0')}.txt", false) } else resource(path, false)
                        MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody("<d:multistatus xmlns:d=\"DAV:\">$content</d:multistatus>")
                    } else if (request.method == "GET") MockResponse().setHeader("ETag", "\"fixture\"").setBody(body) else MockResponse().setResponseCode(405)
                }
            }
            server.start()
            fixtureApp.providers[source] = WebDavProvider(source, server.url("/").toString(), "fixture", "fixture-password", client)
        }
        val location = if (remote) StorageLocation(source, "listing") else StorageLocation(source, run.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath)
        lateinit var model: ExplorerViewModel; lateinit var other: ExplorerViewModel
        var initialized = false
        val orientation = compose.activity.requestedOrientation
        try {
            runBlocking { graph.settings.save(original.copy(grid = false)) }
            compose.waitUntil(15_000) { graph.workspace.state.value.ready }
            compose.runOnIdle { graph.workspace.activate(0); graph.workspace.split(false); model = ViewModelProvider(compose.activity).get("A", ExplorerViewModel::class.java); other = ViewModelProvider(compose.activity).get("B", ExplorerViewModel::class.java); initialized = true; model.home(); other.home() }
            compose.waitUntil(15_000) { model.state.value.profilesReady }
            compose.runOnIdle { model.navigate(location) }
            compose.waitUntil(60_000) { !model.state.value.loading && model.state.value.entries.size == count }
            compose.onNodeWithTag("folder-list").performScrollToIndex(299)
            compose.waitUntil { model.state.value.index == 299 }
            val entries = model.state.value.entries; val row = entries[299]
            val queries = StorageReadMetrics.count(source)
            compose.onNodeWithTag("entry-${row.location.path}").performTouchInput { longClick() }
            compose.onNodeWithTag("view-grid").performClick()
            compose.onNodeWithTag("entry-${row.location.path}").assertIsDisplayed()
            compose.onNodeWithTag("grid-density").performClick()
            compose.onNodeWithText("작게", substring = true).performClick()
            compose.onNodeWithTag("entry-${row.location.path}").assertIsDisplayed()
            assertTrue(row.id in model.state.value.selected)
            val gridBounds = compose.onNodeWithTag("folder-grid").fetchSemanticsNode().boundsInRoot
            val point = compose.onNodeWithTag("entry-${row.location.path}").fetchSemanticsNode().boundsInRoot.center - gridBounds.topLeft
            compose.onNodeWithTag("folder-grid").performTouchInput {
                down(0, point + androidx.compose.ui.geometry.Offset(-24f, 0f)); down(1, point + androidx.compose.ui.geometry.Offset(24f, 0f))
                moveTo(0, point + androidx.compose.ui.geometry.Offset(-72f, 0f), 120); moveTo(1, point + androidx.compose.ui.geometry.Offset(72f, 0f), 120)
                up(0); up(1)
            }
            compose.waitUntil { model.settings.value.density == GridDensity.NORMAL }
            compose.onNodeWithTag("entry-${row.location.path}").assertIsDisplayed()
            compose.onNodeWithTag("folder-switch").performClick(); compose.onNodeWithTag("workspace-location-1").performClick()
            compose.onNodeWithTag("storage-home").assertExists()
            compose.onNodeWithTag("folder-switch").performClick(); compose.onNodeWithTag("workspace-location-0").performClick()
            compose.onNodeWithTag("entry-${row.location.path}").assertIsDisplayed()
            assertTrue(row.id in model.state.value.selected)
            fun rotationDiagnostic(stage: String) {
                if (InstrumentationRegistry.getArguments().getString("rotationDiagnostics") != "true") return
                val anchor = compose.runOnUiThread { model.state.value.anchor }
                val config = compose.activity.resources.configuration
                val tree = compose.onRoot(useUnmergedTree = true).printToString()
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "ROTATION_DIAGNOSTIC stage=$stage orientation=${config.orientation} widthDp=${config.screenWidthDp} fontScale=${config.fontScale} anchor=$anchor\n$tree\n")
                })
            }
            rotationDiagnostic("before-landscape")
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitForIdle(); compose.onNodeWithTag("folder-grid").assertExists()
            rotationDiagnostic("after-landscape")
            compose.activityRule.scenario.recreate(); compose.waitForIdle()
            rotationDiagnostic("after-recreate")
            compose.onNodeWithTag("entry-${row.location.path}").assertIsDisplayed()
            assertTrue(row.id in model.state.value.selected)
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            compose.waitForIdle()
            compose.runOnIdle { model.clearSelection(); model.open(row) }
            compose.waitUntil(15_000) { model.state.value.preview?.document != null }
            compose.onNodeWithTag("close-preview").performClick()
            compose.onNodeWithTag("entry-${row.location.path}").assertIsDisplayed()
            assertSame(entries, model.state.value.entries)
            assertEquals(queries, StorageReadMetrics.count(source))
            val toggleTimes = mutableListOf<Double>(); val returnTimes = mutableListOf<Double>()
            if (performance) {
                val frameTimes = java.util.Collections.synchronizedList(mutableListOf<Double>())
                val frames = android.os.HandlerThread("ui-frame-metrics").apply { start() }
                val frameListener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ -> frameTimes.add(metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1e6) }
                var window: android.view.Window? = null
                compose.activityRule.scenario.onActivity { window = it.window; it.window.addOnFrameMetricsAvailableListener(frameListener, android.os.Handler(frames.looper)) }
                try {
                fun measured(ready: () -> Boolean = { true }, action: () -> Unit): Double {
                    val latch = CountDownLatch(1); var elapsed = 0L
                    var start = 0L
                    compose.runOnIdle { start = SystemClock.elapsedRealtimeNanos(); action() }
                    compose.waitUntil(10_000, ready); compose.waitForIdle()
                    compose.runOnIdle { Choreographer.getInstance().postFrameCallback { Choreographer.getInstance().postFrameCallback { elapsed = SystemClock.elapsedRealtimeNanos() - start; latch.countDown() } } }
                    assertTrue(latch.await(10, TimeUnit.SECONDS)); compose.waitForIdle()
                    return elapsed / 1e6
                }
                repeat(30) { index ->
                    val targetGrid = !model.settings.value.grid
                    toggleTimes += measured(ready = { model.settings.value.grid == targetGrid }) { model.updateSettings { it.copy(grid = targetGrid) } }
                    compose.onNodeWithTag("entry-${row.location.path}").assertIsDisplayed()
                    compose.runOnIdle { model.open(row) }
                    compose.waitUntil(10_000) { model.state.value.preview?.document != null }
                    returnTimes += measured { model.closePreview() }
                    assertSame(entries, model.state.value.entries)
                    assertEquals(queries, StorageReadMetrics.count(source))
                }
                val result = JSONObject().put("source", source).put("entries", count).put("viewSamplesMs", org.json.JSONArray(toggleTimes)).put("returnSamplesMs", org.json.JSONArray(returnTimes))
                    .put("viewP95Ms", toggleTimes.sorted()[28]).put("returnP95Ms", returnTimes.sorted()[28]).put("directoryQueries", 0)
                    .put("javaUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()).put("nativeHeapBytes", android.os.Debug.getNativeHeapAllocatedSize())
                val recorded = synchronized(frameTimes) { frameTimes.toList().sorted() }
                assertTrue("Window frame timings must cover at least the 60 measured transitions; captured ${recorded.size}", recorded.size >= 60)
                val memory = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
                result.put("frames", recorded.size).put("framesOver16_67Ms", recorded.count { it > 16.67 }).put("frameP95Ms", recorded.getOrNull((recorded.size * .95).toInt().coerceAtMost(recorded.lastIndex))).put("pssKiB", memory.totalPss)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "UI_PERFORMANCE $result\n") })
                assertTrue(toggleTimes.sorted()[28] <= 250); assertTrue(returnTimes.sorted()[28] <= 250)
                } finally { compose.runOnIdle { window?.removeOnFrameMetricsAvailableListener(frameListener) }; frames.quitSafely() }
            }
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
            if (initialized) compose.runOnIdle { model.home(); other.home() }
            runBlocking { graph.settings.save(original); graph.dao.removeSaved("recent:${location.key}") }
            fixtureApp.providers.remove("ui-mock"); server?.shutdown(); client?.connectionPool?.evictAll(); client?.dispatcher?.executorService?.shutdown()
            require(run.canonicalFile.toPath().startsWith(root.toPath()) && run != root); run.deleteRecursively()
        }
    }
}
