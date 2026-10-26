package com.quickfiles.app.storage

import java.util.Locale

/** One routing table for browser entries, playlists and archive previews. */
object FileFormats {
    val images = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
    val texts = setOf("txt", "md", "markdown", "log", "json", "csv", "tsv", "xml", "yaml", "yml", "ini", "conf", "properties", "srt", "vtt")
    val videos = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "ts", "3gpp", "mpg", "mpeg", "flv")
    val audio = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "m4b", "oga", "amr")
    fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun isImage(name: String) = extension(name) in images
    fun isText(name: String) = extension(name) in texts
    fun isEditableText(name: String) = extension(name) == "txt"
    fun isSubtitle(name: String) = extension(name) in setOf("srt", "vtt")
}
