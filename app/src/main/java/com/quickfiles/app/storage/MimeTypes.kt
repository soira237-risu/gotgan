package com.quickfiles.app.storage

import android.webkit.MimeTypeMap

/** MIME types for handing files to other apps. Unknown types use a wildcard so the system can still offer every viewer. */
object MimeTypes {
    const val ANY = "*/*"

    // Formats the platform table misses or reports inconsistently across Android versions.
    private val known = mapOf(
        "apk" to "application/vnd.android.package-archive",
        "epub" to "application/epub+zip",
        "cbz" to "application/vnd.comicbook+zip",
        "cbr" to "application/vnd.comicbook-rar",
        "md" to "text/markdown", "markdown" to "text/markdown",
        "log" to "text/plain", "conf" to "text/plain", "ini" to "text/plain", "properties" to "text/plain",
        "yaml" to "text/yaml", "yml" to "text/yaml", "tsv" to "text/tab-separated-values",
        "srt" to "application/x-subrip", "vtt" to "text/vtt",
        "mkv" to "video/x-matroska", "m4v" to "video/x-m4v", "ts" to "video/mp2t", "mts" to "video/mp2t", "m2ts" to "video/mp2t",
        "flv" to "video/x-flv", "webm" to "video/webm",
        "m4a" to "audio/mp4", "m4b" to "audio/mp4", "opus" to "audio/ogg", "oga" to "audio/ogg", "flac" to "audio/flac", "amr" to "audio/amr",
        "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif", "webp" to "image/webp",
        "7z" to "application/x-7z-compressed", "rar" to "application/vnd.rar", "tar" to "application/x-tar", "tgz" to "application/gzip",
        "gz" to "application/gzip", "xz" to "application/x-xz", "bz2" to "application/x-bzip2", "zst" to "application/zstd",
    )

    fun forName(name: String, platform: (String) -> String? = ::platformType): String {
        val extension = FileFormats.extension(name)
        if (extension.isEmpty()) return ANY
        return platform(extension)?.takeIf { it.isNotBlank() } ?: known[extension] ?: ANY
    }

    /** Extension for a received stream that came without a file name; null when the type says nothing useful. */
    fun extensionFor(mimeType: String?, platform: (String) -> String? = ::platformExtension): String? {
        val type = mimeType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { it.contains('/') && !it.contains('*') } ?: return null
        return platform(type)?.takeIf { it.isNotBlank() } ?: known.entries.firstOrNull { it.value == type }?.key
    }

    /** Whether [mimeType] satisfies any of [accepted] picker patterns, where `*` matches any type or subtype. An empty request accepts everything. */
    fun accepts(accepted: List<String>, mimeType: String): Boolean {
        if (accepted.isEmpty()) return true
        val (type, sub) = mimeType.lowercase(java.util.Locale.ROOT).split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return accepted.any { pattern ->
            val (wantType, wantSub) = pattern.lowercase(java.util.Locale.ROOT).trim().split('/', limit = 2).let { it[0] to it.getOrElse(1) { "*" } }
            (wantType == "*" || wantType == type) && (wantSub == "*" || wantSub == sub)
        }
    }

    private fun platformType(extension: String): String? = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    private fun platformExtension(mimeType: String): String? = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
}
