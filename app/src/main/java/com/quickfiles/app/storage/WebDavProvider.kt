package com.quickfiles.app.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.RequestBody
import okio.BufferedSink
import kotlinx.coroutines.delay
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resumeWithException

class WebDavProvider(
    private val sourceId: String,
    address: String,
    username: String,
    password: String,
    client: OkHttpClient = defaultClient,
) : WritableStorageProvider {
    override val capabilities = StorageCapabilities(writable = true, serverCopy = true, rangedRead = true)
    private val base = validatedUrl(address)
    private val rootSegments = base.pathSegments.dropLastWhile { it.isEmpty() }
    private val authorization = Credentials.basic(username, password, Charsets.UTF_8)
    // Never let redirects silently change method, authority, or credential destination.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    private fun url(location: StorageLocation): HttpUrl {
        require(location.sourceId == sourceId)
        return base.newBuilder().apply {
            if (location.path.isNotEmpty()) location.path.split('/').forEach { addPathSegment(it) }
        }.build()
    }

    override suspend fun list(location: StorageLocation): List<FileEntry> {
        StorageReadMetrics.listed(location.sourceId)
        return properties(location, "1").filter { it.location != location && it.location.parent() == location }
    }

    override suspend fun stat(location: StorageLocation): FileEntry {
        repeat(3) { attempt ->
            val entry = properties(location, "0").firstOrNull { it.location == location }
                ?: throw StorageException(StorageError.NOT_FOUND, "파일을 찾을 수 없습니다.")
            // Synology/Apache can expose a weak tag during the second in which a file was written.
            // Do not convert weak tags to strong ones; re-read metadata briefly, then retain the limitation.
            if (entry.etag?.startsWith("W/") != true || attempt == 2) return entry
            delay(1100)
        }
        error("unreachable")
    }

    private suspend fun properties(location: StorageLocation, depth: String): List<FileEntry> = retryRead { withContext(Dispatchers.IO) {
        val requestUrl = url(location).let { if (depth == "1" && !it.encodedPath.endsWith('/')) it.newBuilder().addPathSegment("").build() else it }
        val request = Request.Builder().url(requestUrl).header("Authorization", authorization)
            .header("Depth", depth).method("PROPFIND", propfind.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
        val (response, cancellation) = execute(request)
        try {
            response.use {
                checkStatus(it, 207)
                val body = it.body ?: throw StorageException(StorageError.INVALID_RESPONSE, "WebDAV 응답에 내용이 없습니다.")
                try {
                    parse(BoundedInputStream(body.byteStream(), 16L * 1024 * 1024), requestUrl, location)
                } catch (e: StorageException) { throw e }
                catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw StorageException(StorageError.INVALID_RESPONSE, "WebDAV 폴더 응답을 해석하지 못했습니다.", e)
                }
            }
        } finally { cancellation.dispose() }
    } }

    override suspend fun openRead(location: StorageLocation): ReadHandle = read(location, 0, null)
    override suspend fun openRead(location: StorageLocation, offset: Long, expected: Revision?): ReadHandle = read(location, offset, expected)
    private suspend fun read(location: StorageLocation, offset: Long, expected: Revision?): ReadHandle {
        require(offset >= 0)
        if (offset > 0 && expected?.strongEtag == null) throw StorageException(StorageError.UNSUPPORTED, "이 서버의 파일은 검증된 이어 읽기를 지원하지 않습니다.")
        val context = currentCoroutineContext()
        val request = Request.Builder().url(url(location)).header("Authorization", authorization).header("Accept-Encoding", "identity")
        expected?.strongEtag?.let { request.header("If-Match", it) }
        if (offset > 0) request.header("Range", "bytes=$offset-").header("If-Range", expected!!.strongEtag!!)
        val (response, cancellation) = execute(request.get().build())
        try {
            if (offset > 0) {
                if (response.code == 200) throw StorageException(StorageError.UNSUPPORTED, "서버가 범위 읽기를 지원하지 않습니다. 다운로드 후 열어 주세요.")
                checkStatus(response, 206)
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                    ?: throw StorageException(StorageError.INVALID_RESPONSE, "잘못된 범위 응답입니다.")
                val first = range.groupValues[1].toLongOrNull()
                val last = range.groupValues[2].toLongOrNull()
                val total = range.groupValues[3].toLongOrNull()
                if (first != offset || last == null || total == null || last < offset || last != total - 1 || (expected?.size != null && total != expected.size))
                    throw StorageException(StorageError.CONFLICT, "원격 파일 크기 또는 범위가 변경되었습니다.")
            } else checkStatus(response, 200)
            if (expected?.strongEtag != null && response.header("ETag")?.let { it != expected.strongEtag } == true) throw StorageException(StorageError.CONFLICT, "서버가 다른 파일 버전을 반환했습니다.")
            val body = response.body ?: throw StorageException(StorageError.READ, "파일 내용이 없습니다.")
            val stream = object : FilterInputStream(body.byteStream()) {
                private inline fun reading(block: () -> Int): Int {
                    context.ensureActive()
                    try { return block() } catch (e: IOException) {
                        context.ensureActive()
                        throw mapNetworkError(e)
                    }
                }
                override fun read(): Int = reading { `in`.read() }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = reading { `in`.read(buffer, offset, length) }
            }
            return ReadHandle(stream, body.contentLength().takeIf { it >= 0 }) {
                response.close()
                cancellation.dispose()
            }
        } catch (e: Exception) {
            response.close()
            cancellation.dispose()
            throw e
        }
    }

    private fun writeRequest(location: StorageLocation) = Request.Builder().url(url(location)).header("Authorization", authorization)
    private fun condition(request: Request.Builder, revision: Revision?) {
        if (revision != null) {
            val tag = revision.strongEtag ?: throw StorageException(StorageError.UNSUPPORTED, "서버가 파일 변경 검증을 제공하지 않아 원본을 보존했습니다.")
            request.header("If-Match", tag)
        }
    }
    private suspend fun mutate(request: Request) {
        val (response, cancellation) = execute(request)
        try { response.use { if (it.code !in listOf(200, 201, 204)) checkStatus(it, 201) } } finally { cancellation.dispose() }
    }
    override suspend fun createDirectory(location: StorageLocation) {
        require(location.path.isNotEmpty())
        mutate(writeRequest(location).header("If-None-Match", "*").method("MKCOL", null).build())
    }
    override suspend fun put(location: StorageLocation, length: Long?, input: () -> InputStream, expected: Revision?, createOnly: Boolean, progress: (Long) -> Unit) {
        require(location.path.isNotEmpty())
        val context = currentCoroutineContext()
        val request = writeRequest(location)
        if (createOnly) request.header("If-None-Match", "*") else {
            if (expected == null) throw StorageException(StorageError.CONFLICT, "덮어쓰기에는 기존 파일 버전이 필요합니다.")
            condition(request, expected)
        }
        mutate(request.put(object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = length ?: -1L
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                input().use { source ->
                    val buffer = ByteArray(128 * 1024)
                    var count = 0L
                    while (true) {
                        context.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read); count += read; progress(count)
                    }
                    if (length != null && count != length) throw IOException("Incomplete upload input")
                }
            }
        }).build())
    }
    override suspend fun move(source: StorageLocation, destination: StorageLocation, expected: Revision?) {
        require(source.path.isNotEmpty() && destination.path.isNotEmpty())
        val request = writeRequest(source).header("Destination", url(destination).toString()).header("Overwrite", "F")
        condition(request, expected)
        mutate(request.method("MOVE", null).build())
    }
    override suspend fun copy(source: StorageLocation, destination: StorageLocation, expected: Revision?) {
        val request = writeRequest(source).header("Destination", url(destination).toString()).header("Overwrite", "F").header("Depth", "infinity")
        condition(request, expected)
        mutate(request.method("COPY", null).build())
    }
    override suspend fun delete(location: StorageLocation, expected: Revision?) {
        require(location.path.isNotEmpty())
        val request = writeRequest(location)
        condition(request, expected)
        mutate(request.delete().build())
    }

    private suspend fun execute(request: Request): Pair<Response, DisposableHandle> {
        val reads = request.method in listOf("GET", "PROPFIND")
        val budget = currentCoroutineContext()[ReadRetryBudget] ?: ReadRetryBudget()
        while (true) {
            try {
                val result = executeOnce(request)
                val pause = if (reads && result.first.code in listOf(408, 502, 503, 504)) budget.nextDelay() else null
                if (pause != null) {
                    result.first.close(); result.second.dispose()
                    delay(pause)
                } else return result
            }
            catch (e: StorageException) {
                currentCoroutineContext().ensureActive()
                if (!reads || e.kind != StorageError.NETWORK) throw e
                val pause = budget.nextDelay() ?: throw e
                com.quickfiles.app.operations.DiagnosticLog.record(request.method, "headers-retry", e)
                delay(pause)
            }
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun executeOnce(request: Request): Pair<Response, DisposableHandle> {
        val call = client.newCall(request)
        if (request.method in listOf("GET", "PUT")) call.timeout().clearTimeout()
        val callerContext = currentCoroutineContext()
        // Covers both awaiting headers and blocking stream reads; disposed on every close/failure.
        val cancellation = callerContext.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (it != null) call.cancel()
        }
        try {
            val response = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!callerContext.isActive) continuation.cancel()
                        else if (continuation.isActive) continuation.resumeWithException(mapNetworkError(e))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                })
            }
            return response to cancellation
        } catch (e: Exception) { cancellation.dispose(); throw e }
    }

    private fun checkStatus(response: Response, expected: Int) {
        if (response.code == expected) return
        throw when (response.code) {
            401 -> StorageException(StorageError.AUTH, "인증에 실패했습니다. 사용자 이름과 비밀번호를 확인해 주세요.")
            403 -> StorageException(StorageError.ACCESS, "이 계정에 폴더 또는 파일 접근 권한이 없습니다.")
            404 -> StorageException(StorageError.NOT_FOUND, "폴더 또는 파일을 찾을 수 없습니다.")
            409, 412 -> StorageException(StorageError.CONFLICT, "같은 이름의 항목이 있거나 파일이 변경되었습니다. 원본을 보존했습니다.")
            416 -> StorageException(StorageError.CONFLICT, "파일 크기가 변경되어 이어받기를 중단했습니다.")
            423 -> StorageException(StorageError.BUSY, "서버에서 파일을 사용 중입니다. 잠시 후 다시 시도해 주세요.")
            507 -> StorageException(StorageError.NO_SPACE, "NAS 저장 공간이 부족합니다.")
            in 300..399 -> StorageException(StorageError.UNSUPPORTED, "서버가 다른 주소로 이동을 요청했습니다. 연결 설정에 최종 HTTPS 주소를 입력해 주세요.")
            405, 501 -> StorageException(StorageError.UNSUPPORTED, "이 주소는 필요한 WebDAV 기능을 지원하지 않습니다.")
            408, 502, 503, 504 -> StorageException(StorageError.NETWORK, "서버가 일시적으로 요청을 처리하지 못했습니다. (HTTP ${response.code})")
            else -> StorageException(StorageError.READ, "서버가 요청을 처리하지 못했습니다. (HTTP ${response.code})")
        }
    }

    private fun parse(input: InputStream, requestUrl: HttpUrl, requested: StorageLocation): List<FileEntry> {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(input, null)
        val entries = linkedMapOf<String, FileEntry>()
        val text = StringBuilder()
        var href: String? = null
        var directStatus: String? = null
        var propStatus = ""
        var inPropstat = false
        var properties = mutableMapOf<String, String>()
        val successful = mutableMapOf<String, String>()
        var rootSeen = false
        var rootClosed = false
        var responseDepth = -1
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.DOCDECL -> throw StorageException(StorageError.INVALID_RESPONSE, "DTD가 포함된 WebDAV 응답은 허용하지 않습니다.")
                XmlPullParser.START_TAG -> {
                    text.setLength(0)
                    if (parser.depth == 1) {
                        rootSeen = parser.name == "multistatus" && parser.namespace == "DAV:"
                        if (!rootSeen) throw StorageException(StorageError.INVALID_RESPONSE, "WebDAV 폴더 응답이 아닙니다.")
                    }
                    if (parser.namespace == "DAV:") when (parser.name) {
                        "response" -> {
                            responseDepth = parser.depth
                            href = null; directStatus = null; successful.clear(); inPropstat = false
                        }
                        "propstat" -> { inPropstat = true; propStatus = ""; properties = mutableMapOf() }
                        "collection" -> if (inPropstat) properties["collection"] = "true"
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    if (text.length > 64 * 1024) throw StorageException(StorageError.INVALID_RESPONSE, "WebDAV 속성이 너무 큽니다.")
                    text.append(parser.text.orEmpty())
                }
                XmlPullParser.END_TAG -> if (parser.namespace == "DAV:") {
                    when (parser.name) {
                        "multistatus" -> if (parser.depth == 1) rootClosed = true
                        "href" -> if (parser.depth == responseDepth + 1) href = text.toString()
                        "status" -> if (inPropstat) propStatus = text.toString() else directStatus = text.toString()
                        "getcontentlength", "getlastmodified", "getetag" -> if (inPropstat) properties[parser.name] = text.toString()
                        "resourcetype" -> if (inPropstat) properties.putIfAbsent("collection", "false")
                        "propstat" -> {
                            if (statusCode(propStatus) in 200..299) successful.putAll(properties)
                            inPropstat = false
                        }
                        "response" -> {
                            val responseLocation = href?.let { locationFromHref(it, requestUrl) }
                            if (responseLocation == requested) when (statusCode(directStatus.orEmpty())) {
                                401 -> throw StorageException(StorageError.AUTH, "인증에 실패했습니다. 계정을 확인해 주세요.")
                                403 -> throw StorageException(StorageError.ACCESS, "이 폴더를 읽을 권한이 없습니다.")
                                404 -> throw StorageException(StorageError.NOT_FOUND, "폴더 또는 파일을 찾을 수 없습니다.")
                            }
                            if ((directStatus == null || statusCode(directStatus.orEmpty()) in 200..299) && successful.isNotEmpty()) {
                                val location = responseLocation
                                if (location != null) {
                                    entries[location.key] = FileEntry(
                                        location, location.path.substringAfterLast('/').ifEmpty { "WebDAV" },
                                        successful["collection"] == "true",
                                        successful["getcontentlength"]?.toLongOrNull()?.takeIf { it >= 0 },
                                        successful["getlastmodified"]?.let { runCatching {
                                            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(it)?.time
                                        }.getOrNull() }, successful["getetag"],
                                    )
                                }
                            } else if (responseLocation != null && responseLocation != requested && responseLocation.parent() == requested) {
                                entries[responseLocation.key] = FileEntry(responseLocation, responseLocation.path.substringAfterLast('/'), false, accessError = "이 항목의 정보를 읽을 수 없습니다. 계정 권한과 서버 상태를 확인해 주세요.")
                            }
                            responseDepth = -1
                        }
                    }
                    text.setLength(0)
                }
            }
            event = parser.nextToken()
        }
        if (!rootSeen || !rootClosed || responseDepth != -1) throw StorageException(StorageError.INVALID_RESPONSE, "WebDAV 폴더 응답이 비어 있거나 잘렸습니다.")
        return entries.values.toList()
    }

    private fun locationFromHref(href: String, requestUrl: HttpUrl): StorageLocation? {
        val resolved = requestUrl.resolve(href) ?: return null
        if (resolved.scheme != base.scheme || resolved.host != base.host || resolved.port != base.port ||
            resolved.username.isNotEmpty() || resolved.password.isNotEmpty() || resolved.query != null || resolved.fragment != null) return null
        val segments = resolved.pathSegments.dropLastWhile { it.isEmpty() }
        if (segments.take(rootSegments.size) != rootSegments) return null
        val relative = segments.drop(rootSegments.size)
        if (relative.any { it.isEmpty() || it == "." || it == ".." || '/' in it || '\\' in it || '\u0000' in it }) return null
        return StorageLocation(sourceId, relative.joinToString("/"))
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
        fun validatedUrl(address: String): HttpUrl {
            val parsed = address.trim().toHttpUrlOrNull()
                ?: throw IllegalArgumentException("올바른 HTTPS 주소를 입력해 주세요.")
            require(parsed.isHttps) { "HTTPS WebDAV 주소만 지원합니다." }
            require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null) {
                "주소에는 계정, 쿼리, #을 넣지 말고 별도 계정 입력란을 사용해 주세요."
            }
            require(parsed.pathSegments.none { '/' in it || '\\' in it || '\u0000' in it || it == "." || it == ".." }) {
                "유효하지 않은 서버 경로입니다."
            }
            return if (parsed.encodedPath.endsWith('/')) parsed else parsed.newBuilder().addPathSegment("").build()
        }
        private fun statusCode(value: String) = value.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: 0
        private fun mapNetworkError(e: IOException): StorageException = if (e is SSLException) {
            StorageException(StorageError.TLS, "인증서를 확인할 수 없습니다. 서버의 인증서 신뢰, 접속 주소 일치 여부와 유효기간을 확인해 주세요. 보안 검사를 건너뛰지 않습니다.", e)
        } else StorageException(StorageError.NETWORK, "서버에 연결하지 못했습니다. 주소와 네트워크를 확인한 뒤 다시 시도해 주세요.", e)
        private const val propfind = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/></d:prop></d:propfind>"""
    }
}

internal class BoundedInputStream(input: InputStream, private val maximum: Long) : FilterInputStream(input) {
    private var count = 0L
    override fun read(): Int {
        val value = super.read()
        if (value >= 0) record(1)
        return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val value = `in`.read(buffer, offset, minOf(length.toLong(), maximum - count + 1).toInt())
        if (value > 0) record(value)
        return value
    }
    private fun record(amount: Int) {
        count += amount
        if (count > maximum) throw StorageException(StorageError.TOO_LARGE, "응답이 미리보기 용량 제한을 초과했습니다.")
    }
}
