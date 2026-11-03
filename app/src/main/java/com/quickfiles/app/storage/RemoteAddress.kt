package com.quickfiles.app.storage

import java.net.URI

/** The kinds of server Gotgan can connect to, told apart by the address scheme. */
enum class Protocol(val label: String, val defaultPort: Int) {
    WEBDAV("WebDAV", 443), SMB("SMB", 445), SFTP("SFTP", 22);

    companion object {
        fun of(address: String): Protocol = when (address.trim().substringBefore("://").lowercase()) {
            "smb" -> SMB
            "sftp" -> SFTP
            else -> WEBDAV
        }
    }
}

/**
 * A parsed `smb://host[:port]/share/folder` or `sftp://host[:port]/folder` address. [segments] are decoded
 * path components; for SMB the first one is the share. Credentials, queries and fragments are refused so
 * they never sit in a saved address.
 */
data class RemoteAddress(val protocol: Protocol, val host: String, val port: Int, val segments: List<String>) {
    val hostKey: String get() = if (port == protocol.defaultPort) host else "[$host]:$port"
    override fun toString(): String = buildString {
        append(protocol.name.lowercase()).append("://")
        append(if (':' in host) "[$host]" else host)
        if (port != protocol.defaultPort) append(':').append(port)
        append('/')
        append(segments.joinToString("/") { URI(null, null, it, null).rawPath.replace("/", "%2F") })
        if (segments.isNotEmpty()) append('/')
    }

    companion object {
        fun parse(address: String): RemoteAddress {
            val protocol = Protocol.of(address)
            require(protocol != Protocol.WEBDAV) { "SMB 또는 SFTP 주소가 아닙니다." }
            val uri = try { URI(address.trim()) } catch (e: Exception) { throw IllegalArgumentException("주소 형식을 확인해 주세요.") }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("서버 주소를 입력해 주세요.")
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "주소에는 계정, 쿼리, #을 넣지 말고 별도 계정 입력란을 사용해 주세요."
            }
            val port = if (uri.port == -1) protocol.defaultPort else uri.port
            require(port in 1..65535) { "포트 번호를 확인해 주세요." }
            val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
            require(segments.none { it == "." || it == ".." || '/' in it || '\\' in it || '\u0000' in it }) { "유효하지 않은 서버 경로입니다." }
            if (protocol == Protocol.SMB) require(segments.isNotEmpty()) { "SMB 주소에는 공유 폴더 이름이 필요합니다. 예: smb://nas.local/공유" }
            return RemoteAddress(protocol, host, port, segments)
        }
    }
}

/** Accepts any supported address and returns its canonical form. */
fun validatedAddress(address: String): String = when (Protocol.of(address)) {
    Protocol.WEBDAV -> WebDavProvider.validatedUrl(address).toString()
    else -> RemoteAddress.parse(address).toString()
}
