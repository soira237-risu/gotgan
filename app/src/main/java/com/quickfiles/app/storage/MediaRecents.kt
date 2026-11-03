package com.quickfiles.app.storage

import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Files that recently appeared on phone storage (downloads, photos, recordings), from the system media index. */
object MediaRecents {
    /** Maps one MediaStore row to a phone-storage entry; rows the browser could not open (protected, hidden, odd paths) are dropped. */
    fun toEntry(relativePath: String?, name: String?, size: Long?, modifiedSeconds: Long?): FileEntry? {
        if (name.isNullOrBlank() || name.startsWith('.')) return null
        val folder = relativePath.orEmpty().trim('/')
        if (folder.split('/').any { it.startsWith('.') }) return null
        if (folder == "Android/data" || folder.startsWith("Android/data/") || folder == "Android/obb" || folder.startsWith("Android/obb/")) return null
        val location = runCatching { StorageLocation(LOCAL_SOURCE, if (folder.isEmpty()) name else "$folder/$name") }.getOrNull() ?: return null
        return FileEntry(location, name, false, size?.takeIf { it >= 0 }, modifiedSeconds?.takeIf { it > 0 }?.times(1000))
    }

    suspend fun recentlyAdded(context: Context, limit: Int = 100): List<FileEntry> = withContext(Dispatchers.IO) {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val columns = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        val args = Bundle().apply {
            // Folders have no MIME type in the index; pending and trashed rows are not real files yet / any more.
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL AND ${MediaStore.MediaColumns.SIZE} > 0")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit * 2)
        }
        val found = mutableListOf<FileEntry>()
        context.contentResolver.query(uri, columns, args, null)?.use { c ->
            val path = c.getColumnIndexOrThrow(columns[0]); val name = c.getColumnIndexOrThrow(columns[1])
            val size = c.getColumnIndexOrThrow(columns[2]); val modified = c.getColumnIndexOrThrow(columns[3])
            while (c.moveToNext() && found.size < limit) {
                toEntry(c.getString(path), c.getString(name), if (c.isNull(size)) null else c.getLong(size), if (c.isNull(modified)) null else c.getLong(modified))?.let(found::add)
            }
        }
        found
    }
}
