package com.quickfiles.app.data

import com.quickfiles.app.storage.FileEntry
import com.quickfiles.app.storage.INBOX_SOURCE
import org.json.JSONObject

/** Files opened in Gotgan, newest first. Received (inbox) copies are temporary and never recorded. */
object RecentFiles {
    private const val PREFIX = "opened:"
    const val KEEP = 200

    fun encode(entry: FileEntry): String = JSONObject().put("location", JSONObject(entry.location.encode())).put("name", entry.name)
        .put("size", entry.size ?: JSONObject.NULL).put("modified", entry.modified ?: JSONObject.NULL).put("etag", entry.etag ?: JSONObject.NULL).toString()

    fun decode(value: String): FileEntry? = runCatching {
        val json = JSONObject(value)
        FileEntry(decodeLocation(json.getJSONObject("location").toString()), json.getString("name"), false,
            if (json.isNull("size")) null else json.getLong("size"), if (json.isNull("modified")) null else json.getLong("modified"),
            if (json.isNull("etag")) null else json.getString("etag"))
    }.getOrNull()

    suspend fun record(dao: ExplorerDao, entry: FileEntry, now: Long = System.currentTimeMillis()) {
        if (entry.isDirectory || entry.location.sourceId == INBOX_SOURCE) return
        dao.save(SavedValue(PREFIX + entry.id, encode(entry), now))
        dao.savedValues("$PREFIX%", KEEP + 50).drop(KEEP).forEach { dao.removeSaved(it.key) }
    }

    suspend fun list(dao: ExplorerDao, limit: Int = 100): List<FileEntry> = dao.savedValues("$PREFIX%", limit).mapNotNull { decode(it.value) }

    suspend fun forget(dao: ExplorerDao, entry: FileEntry) = dao.removeSaved(PREFIX + entry.id)
}
