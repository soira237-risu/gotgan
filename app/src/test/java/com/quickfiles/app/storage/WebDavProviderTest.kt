package com.quickfiles.app.storage

import kotlinx.coroutines.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class WebDavProviderTest {
    @Test fun freshlyWrittenWeakEtagIsRereadRatherThanForged() = runBlocking {
        server.enqueue(response(entry("/dav/new.txt").replace("\"v1\"", "W/\"fresh\"")))
        server.enqueue(response(entry("/dav/new.txt").replace("\"v1\"", "\"fresh\"")))
        val result = provider().stat(StorageLocation("nas", "new.txt"))
        assertEquals("\"fresh\"", result.revision.strongEtag)
        assertEquals(2, server.requestCount)
    }
    @Test fun mutationsSendPreconditionsAndNeverFollowRedirects() = runBlocking {
        val p = provider(); val source = StorageLocation("nas", "한글 a.txt"); val target = StorageLocation("nas", "new.txt")
        server.enqueue(MockResponse().setResponseCode(201))
        p.put(target, 3, { "abc".byteInputStream() })
        server.takeRequest().let { assertEquals("PUT", it.method); assertEquals("*", it.getHeader("If-None-Match")); assertEquals("abc", it.body.readUtf8()) }
        server.enqueue(MockResponse().setResponseCode(201))
        p.createDirectory(StorageLocation("nas", "new folder"))
        server.takeRequest().let { assertEquals("MKCOL", it.method); assertEquals("*", it.getHeader("If-None-Match")) }
        for (method in listOf("MOVE", "COPY")) {
            server.enqueue(MockResponse().setResponseCode(201))
            if (method == "MOVE") p.move(source, target, Revision(etag = "\"version\"")) else p.copy(source, target, Revision(etag = "\"version\""))
            server.takeRequest().let { assertEquals(method, it.method); assertEquals("F", it.getHeader("Overwrite")); assertEquals("\"version\"", it.getHeader("If-Match")); assertTrue(it.getHeader("Destination")!!.endsWith("/dav/new.txt")) }
        }
        server.enqueue(MockResponse().setResponseCode(204))
        p.delete(source, Revision(etag = "\"version\""))
        assertEquals("\"version\"", server.takeRequest().getHeader("If-Match"))
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "https://other.example/leak"))
        try { p.put(target, 3, { "abc".byteInputStream() }); fail("Redirect must fail") } catch (e: StorageException) { assertEquals(StorageError.UNSUPPORTED, e.kind) }
        assertEquals(6, server.requestCount)
    }
    @Test fun conditionalMutationFailureDoesNotRetryOrUseWeakEtag() = runBlocking {
        val p = provider(); val file = StorageLocation("nas", "a.txt")
        for ((code, kind) in listOf(412 to StorageError.CONFLICT, 507 to StorageError.NO_SPACE, 503 to StorageError.NETWORK)) {
            server.enqueue(MockResponse().setResponseCode(code))
            try { p.put(file, 1, { byteArrayOf(1).inputStream() }); fail("Expected error") } catch (e: StorageException) { assertEquals(kind, e.kind) }
        }
        assertEquals(3, server.requestCount)
        try { p.delete(file, Revision(etag = "W/\"v1\"")); fail("Weak condition must be refused") } catch (e: StorageException) { assertEquals(StorageError.UNSUPPORTED, e.kind) }
        assertEquals(3, server.requestCount)
    }
    @Test fun rangeReadChecksOffsetsVersionsAndServerFallback() = runBlocking {
        val p = provider(); val file = StorageLocation("nas", "a.txt"); val rev = Revision(size = 6, etag = "\"v1\"")
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setBody("def"))
        p.openRead(file, 3, rev).use { assertEquals("def", it.stream.reader().readText()) }
        server.takeRequest().let { assertEquals("bytes=3-", it.getHeader("Range")); assertEquals("\"v1\"", it.getHeader("If-Match")); assertEquals("\"v1\"", it.getHeader("If-Range")); assertEquals("identity", it.getHeader("Accept-Encoding")) }
        server.enqueue(MockResponse().setBody("abcdef"))
        try { p.openRead(file, 3, rev); fail("Must detect no range") } catch (e: StorageException) { assertEquals(StorageError.UNSUPPORTED, e.kind) }
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-5/6").setBody("cdef"))
        try { p.openRead(file, 3, rev); fail("Must reject wrong range") } catch (e: StorageException) { assertEquals(StorageError.CONFLICT, e.kind) }
        server.enqueue(MockResponse().setResponseCode(412))
        try { p.openRead(file, 3, rev); fail("Must detect external change") } catch (e: StorageException) { assertEquals(StorageError.CONFLICT, e.kind) }
    }
    @Test fun transientReadsRetryTwiceButAuthenticationDoesNotRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(MockResponse().setResponseCode(502)); server.enqueue(response(entry("/dav/", true)))
        assertTrue(provider().list(StorageLocation("nas")).isEmpty())
        assertEquals(3, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(401))
        try { provider().list(StorageLocation("nas")); fail("Authentication should fail") } catch (e: StorageException) { assertEquals(StorageError.AUTH, e.kind) }
        assertEquals(4, server.requestCount)
    }
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var certificate: HeldCertificate

    @Before fun start() {
        certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
        client = OkHttpClient.Builder().sslSocketFactory(
            HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build().sslSocketFactory(),
            HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build().trustManager,
        ).readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    }
    @After fun stop() { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }

    private fun provider(client: OkHttpClient = this.client) = WebDavProvider("nas", server.url("/dav/").newBuilder().host("localhost").build().toString(), "reader", "fixture-password", client)
    private fun response(vararg entries: String) = MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:">${entries.joinToString("")}</d:multistatus>""")
    private fun entry(href: String, folder: Boolean = false, size: String = "12") = """<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>${if (folder) "<d:collection/>" else ""}</d:resourcetype><d:getcontentlength>$size</d:getcontentlength><d:getetag>"v1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat><d:propstat><d:prop><d:getcontentlength>999</d:getcontentlength></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>"""

    @Test fun httpsOnlyAndSeparateCredentials() {
        listOf("http://localhost/dav", "https://u:p@localhost/", "https://localhost/?key=value", "https://localhost/#secret", "https://localhost/a%2Fb/").forEach {
            assertThrows(IllegalArgumentException::class.java) { WebDavProvider.validatedUrl(it) }
        }
        assertEquals("https://example.com/share/", WebDavProvider.validatedUrl("https://example.com/share").toString())
    }

    @Test fun listsUnicodeExcludesSelfAndUnsafeHrefsHonorsPropstat() = runBlocking {
        server.enqueue(response(entry("/dav/", true), entry("/dav/%ED%95%9C%EA%B8%80%20%23%25.txt"),
            entry("https://other.example/dav/leak.txt"), entry("/private.txt"), entry("/dav/a%2Fb.txt"), entry("/dav/%2e%2e/private.txt"),
            entry("/dav/sub/", true), entry("/dav/sub/deeper.txt")))
        val result = provider().list(StorageLocation("nas"))
        assertEquals(listOf("한글 #%.txt", "sub"), result.map { it.name })
        assertEquals(12L, result[0].size)
        assertTrue(result[1].isDirectory)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.getHeader("Depth"))
        assertEquals(Credentials.basic("reader", "fixture-password", Charsets.UTF_8), request.getHeader("Authorization"))
    }

    @Test fun nestedFolderRelativeHrefAndEncodedGet() = runBlocking {
        server.enqueue(response(entry("/dav/sub/", true), entry("%ED%95%9C%EA%B8%80%20%23%25.txt")))
        val provider = provider()
        val file = provider.list(StorageLocation("nas", "sub")).single()
        assertEquals("sub/한글 #%.txt", file.location.path)
        assertEquals("/dav/sub/", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("한글 내용"))
        provider.openRead(file.location).use { assertEquals("한글 내용", it.stream.reader().readText()) }
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/dav/sub/%ED%95%9C%EA%B8%80%20%23%25.txt", request.path)
    }

    @Test fun statAndEmptyDirectory() = runBlocking {
        server.enqueue(response(entry("/dav/a.txt")))
        assertEquals("\"v1\"", provider().stat(StorageLocation("nas", "a.txt")).etag)
        assertEquals("0", server.takeRequest().getHeader("Depth"))
        server.enqueue(response(entry("/dav/", true)))
        assertTrue(provider().list(StorageLocation("nas")).isEmpty())
    }

    @Test fun rootDenialInsideMultistatusIsNotAnEmptyFolder() = runBlocking {
        server.enqueue(response("<d:response><d:href>/dav/</d:href><d:status>HTTP/1.1 403 Forbidden</d:status></d:response>"))
        try { provider().list(StorageLocation("nas")); fail("Must not treat denial as an empty folder") }
        catch (e: StorageException) { assertEquals(StorageError.ACCESS, e.kind) }
    }

    @Test fun disconnectedDownloadIsReportedAsNetworkFailure() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(128 * 1024)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try {
            PreviewCache.text(provider(), FileEntry(StorageLocation("nas", "broken.txt"), "broken.txt", false)) { _, _ -> }
            fail("A disconnected response must not be treated as a complete file")
        } catch (e: StorageException) { assertEquals(StorageError.NETWORK, e.kind) }
    }

    @Test fun discriminatesStatusErrorsAndDoesNotFollowRedirects() = runBlocking {
        listOf(401 to StorageError.AUTH, 403 to StorageError.ACCESS, 404 to StorageError.NOT_FOUND,
            405 to StorageError.UNSUPPORTED, 302 to StorageError.UNSUPPORTED, 500 to StorageError.READ).forEach { (status, kind) ->
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", "https://other.example/"))
            try { provider().list(StorageLocation("nas")); fail("Expected $status error") }
            catch (e: StorageException) { assertEquals(kind, e.kind) }
        }
        assertEquals(6, server.requestCount)
    }

    @Test fun rejectsDtdAndMalformedXml() = runBlocking {
        listOf("<!DOCTYPE x [<!ENTITY attack SYSTEM 'file:///etc/passwd'>]><d:multistatus xmlns:d='DAV:'>&attack;</d:multistatus>",
            "<html>not WebDAV</html>", "<d:multistatus xmlns:d='DAV:'><broken>").forEach { body ->
            server.enqueue(MockResponse().setResponseCode(207).setBody(body))
            try { provider().list(StorageLocation("nas")); fail("Expected parsing failure: $body") }
            catch (e: StorageException) { assertEquals(StorageError.INVALID_RESPONSE, e.kind) }
        }
    }

    @Test fun rejectsUntrustedCertificate() = runBlocking {
        try { provider(OkHttpClient()).list(StorageLocation("nas")); fail("Must reject untrusted certificate") }
        catch (e: StorageException) { assertEquals(StorageError.TLS, e.kind) }
        assertEquals(0, server.requestCount)
    }

    @Test fun rejectsWrongHostnameEvenWhenCaTrusted() = runBlocking {
        val wrong = HeldCertificate.Builder().commonName("synology").addSubjectAlternativeName("synology").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(wrong).build().sslSocketFactory(), false)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(wrong.certificate).build()
        val trusted = client.newBuilder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        try { provider(trusted).list(StorageLocation("nas")); fail("Must reject hostname mismatch") }
        catch (e: StorageException) { assertEquals(StorageError.TLS, e.kind) }
        assertEquals(0, server.requestCount)
    }

    @Test fun rejectsExpiredServerCertificate() = runBlocking {
        val ca = HeldCertificate.Builder().commonName("test CA").certificateAuthority(0).build()
        val expired = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost")
            .validityInterval(System.currentTimeMillis() - 172_800_000, System.currentTimeMillis() - 86_400_000).signedBy(ca).build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(expired, ca.certificate).build().sslSocketFactory(), false)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build()
        val trusted = client.newBuilder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        try { provider(trusted).list(StorageLocation("nas")); fail("Must reject expired certificate") }
        catch (e: StorageException) { assertEquals(StorageError.TLS, e.kind) }
    }

    @Test fun cancellationWhileWaitingForHeaders() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val operation = launch(Dispatchers.IO) { provider().list(StorageLocation("nas")) }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        withTimeout(2000) { operation.cancelAndJoin() }
        assertTrue(operation.isCancelled)
    }

    @Test fun cancellationClosesBlockedBodyRead() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(2048)).throttleBody(1, 1, TimeUnit.SECONDS))
        val operation = launch(Dispatchers.IO) {
            PreviewCache.text(provider(), FileEntry(StorageLocation("nas", "slow.txt"), "slow.txt", false)) { _, _ -> }
        }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        delay(100)
        withTimeout(2000) { operation.cancelAndJoin() }
        assertTrue(operation.isCancelled)
    }
}
