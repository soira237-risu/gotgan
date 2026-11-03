package com.quickfiles.app

import com.quickfiles.app.storage.FileEntry
import com.quickfiles.app.storage.MIB
import com.quickfiles.app.storage.StorageLocation
import com.quickfiles.app.ui.DateFilter
import com.quickfiles.app.ui.FileKind
import com.quickfiles.app.ui.SearchFilters
import com.quickfiles.app.ui.SizeFilter
import org.junit.Assert.*
import org.junit.Test

class SearchFiltersTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private fun file(name: String, size: Long?, ageDays: Double?, folder: Boolean = false) =
        FileEntry(StorageLocation("phone", "x/$name"), name, folder, size, ageDays?.let { now - (it * day).toLong() })

    @Test fun dateWindowsAreInclusiveAndNeedATimestamp() {
        assertTrue(DateFilter.DAY.matches(file("a.jpg", 1, 0.5), now))
        assertFalse(DateFilter.DAY.matches(file("a.jpg", 1, 1.5), now))
        assertTrue(DateFilter.WEEK.matches(file("a.jpg", 1, 7.0), now))
        assertFalse(DateFilter.MONTH.matches(file("a.jpg", 1, 31.0), now))
        assertTrue(DateFilter.YEAR.matches(file("a.jpg", 1, 200.0), now))
        assertFalse(DateFilter.YEAR.matches(file("a.jpg", 1, null), now))
    }

    @Test fun sizeBandsHaveNoGapsOrOverlapAtOneMegabyte() {
        assertTrue(SizeFilter.SMALL.matches(file("a", MIB - 1, 0.0)))
        assertFalse(SizeFilter.SMALL.matches(file("a", MIB, 0.0)))
        assertTrue(SizeFilter.MEDIUM.matches(file("a", MIB, 0.0)))
        assertTrue(SizeFilter.LARGE.matches(file("a", 100 * MIB, 0.0)))
        assertFalse(SizeFilter.HUGE.matches(file("a", 1023 * MIB, 0.0)))
        assertTrue(SizeFilter.HUGE.matches(file("a", 5000 * MIB, 0.0)))
        assertFalse(SizeFilter.SMALL.matches(file("dir", null, 0.0, folder = true)))
        assertFalse(SizeFilter.SMALL.matches(file("unknown", null, 0.0)))
    }

    @Test fun conditionsCombineAndHideFolders() {
        val filters = SearchFilters(FileKind.VIDEO, DateFilter.MONTH, SizeFilter.LARGE)
        assertTrue(filters.active)
        assertTrue(filters.matches(file("trip.mp4", 500 * MIB, 3.0), now))
        assertFalse(filters.matches(file("trip.mp4", 5 * MIB, 3.0), now))
        assertFalse(filters.matches(file("old.mp4", 500 * MIB, 90.0), now))
        assertFalse(filters.matches(file("big.zip", 500 * MIB, 3.0), now))
        assertFalse(SearchFilters(date = DateFilter.WEEK).matches(file("New folder", null, 1.0, folder = true), now))
        assertFalse(SearchFilters().active)
        assertTrue(SearchFilters().matches(file("any folder", null, null, folder = true), now))
    }
}
