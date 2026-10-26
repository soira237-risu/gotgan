package com.quickfiles.app.ui

import com.quickfiles.app.storage.FileEntry

/** Search type filter. Folders never match, so filtered results list only files of that kind. */
enum class FileKind(val label: String) {
    IMAGE("사진"), VIDEO("영상"), AUDIO("음악"), DOCUMENT("문서"), ARCHIVE("압축");

    fun matches(entry: FileEntry): Boolean = when (this) {
        IMAGE -> entry.isImage
        VIDEO -> entry.isVideo
        AUDIO -> entry.isAudio
        DOCUMENT -> entry.isPdf || entry.isText
        ARCHIVE -> entry.isArchive
    }
}
