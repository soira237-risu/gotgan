package com.quickfiles.app.ui

import com.quickfiles.app.storage.FileEntry
import com.quickfiles.app.storage.MIB

/** Modified within a recent window. */
enum class DateFilter(val label: String, val days: Int) {
    DAY("오늘", 1), WEEK("7일", 7), MONTH("30일", 30), YEAR("1년", 365);
    fun matches(entry: FileEntry, now: Long): Boolean = entry.modified?.let { now - it <= days * DAY_MS } == true
    private companion object { const val DAY_MS = 24L * 60 * 60 * 1000 }
}

/** File size bands; folders carry no size and never match. */
enum class SizeFilter(val label: String, val min: Long, val max: Long) {
    SMALL("1MB 미만", 0, MIB), MEDIUM("1MB 이상", MIB, Long.MAX_VALUE), LARGE("100MB 이상", 100 * MIB, Long.MAX_VALUE), HUGE("1GB 이상", 1024 * MIB, Long.MAX_VALUE);
    fun matches(entry: FileEntry): Boolean = !entry.isDirectory && entry.size?.let { it in min until max } == true
}

/** Search conditions besides the name: kind, recent changes and size. Any active one hides folders. */
data class SearchFilters(val kind: FileKind? = null, val date: DateFilter? = null, val size: SizeFilter? = null) {
    val active: Boolean get() = kind != null || date != null || size != null
    fun matches(entry: FileEntry, now: Long = System.currentTimeMillis()): Boolean =
        (kind == null || kind.matches(entry)) && (date == null || (!entry.isDirectory && date.matches(entry, now))) && (size == null || size.matches(entry))
}
