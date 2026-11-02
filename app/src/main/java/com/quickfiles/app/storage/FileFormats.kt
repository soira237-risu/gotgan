package com.quickfiles.app.storage

import java.util.Locale

/** One routing table for browser entries, playlists and archive previews. */
object FileFormats {
    /** Camera RAW files the platform decoder reads (DNG fully; the others through their embedded preview). */
    val rawImages = setOf("dng", "cr2", "nef", "nrw", "arw", "rw2", "orf", "raf", "pef", "srw")
    val images = setOf("jpg", "jpeg", "jfif", "png", "webp", "gif", "bmp", "ico", "heic", "heif", "avif", "svg") + rawImages
    val texts = setOf("txt", "md", "markdown", "log", "json", "csv", "tsv", "xml", "yaml", "yml", "ini", "conf", "properties", "srt", "vtt")
    val videos = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "ts", "3gpp", "mpg", "mpeg", "flv")
    val audio = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "m4b", "oga", "amr")
    fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun isImage(name: String) = extension(name) in images
    fun isRawImage(name: String) = extension(name) in rawImages
    fun isText(name: String) = extension(name) in texts
    fun isEditableText(name: String) = extension(name) == "txt"
    fun isSubtitle(name: String) = extension(name) in setOf("srt", "vtt")
}
