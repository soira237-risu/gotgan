package com.quickfiles.app

import com.quickfiles.app.data.*
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.*
import org.junit.Assert.*
import org.junit.Test

class UiStateTest {
    private fun entry(name: String, folder: Boolean = false, size: Long = 0) = FileEntry(StorageLocation(LOCAL_SOURCE, name), name, folder, size, size)
    @Test fun everySortDirectionKeepsFoldersFirst() {
        val files = listOf(entry("z", true, 99), entry("b", false, 20), entry("a", false, 10), entry("c", true, 1))
        for (order in SortOrder.entries) for (direction in SortDirection.entries) {
            val sorted = sortedEntries(files, SortSpec(order, direction))
            assertTrue(sorted.take(2).all { it.isDirectory })
            assertTrue(sorted.drop(2).none { it.isDirectory })
            assertEquals(if (direction == SortDirection.ASCENDING) "a" else "b", sorted[2].name)
        }
        assertEquals(SortDirection.DESCENDING, SortSpec(SortOrder.SIZE).direction)
        assertEquals(SortDirection.DESCENDING, SortSpec(SortOrder.DATE).direction)
        assertEquals(SortDirection.ASCENDING, SortSpec(SortOrder.NAME).direction)
    }
    @Test fun adaptiveColumnsRespectFontScaleAndSmallSplitWidths() {
        assertEquals(2, gridColumns(380f, 1f, GridDensity.LARGE))
        assertEquals(3, gridColumns(380f, 1f, GridDensity.NORMAL))
        assertEquals(4, gridColumns(380f, 1f, GridDensity.SMALL))
        assertEquals(1, gridColumns(300f, 2f, GridDensity.NORMAL))
        assertEquals(1, gridColumns(40f, 2f, GridDensity.LARGE))
        assertEquals(GridDensity.SMALL, GridDensity.SMALL.step(true))
        assertEquals(GridDensity.LARGE, GridDensity.LARGE.step(false))
    }
    @Test fun compactGridUsesFourDpGapsWithoutOverflowAtTheColumnBoundary() {
        // Three 104dp cells and two 4dp gaps fit exactly in 320dp.
        assertEquals(3, gridColumns(320f, 1f, GridDensity.NORMAL))
        assertEquals(2, gridColumns(319f, 1f, GridDensity.NORMAL))
        assertEquals(2, gridColumns(316f, 1.5f, GridDensity.NORMAL))
    }
    @Test fun anchorSurvivesReorderingAndConservativelyFallsBackAfterDeletion() {
        val files = (0..400).map { entry("file-$it") }
        val anchor = ViewportAnchor(files[300].id, 300, 17, "list")
        assertEquals(100, anchor.resolve(files.reversed()))
        assertEquals(17, anchor.offsetFor("list"))
        assertEquals(0, anchor.offsetFor("grid:3:NORMAL"))
        assertEquals(19, anchor.copy(fileId = "gone").resolve(files.take(20)))
        assertEquals(0, anchor.resolve(emptyList()))
    }
    @Test fun freshPreferencesUseGridAndLegacyBooleanCallersRemainCompatible() {
        assertEquals(ViewMode.GRID, ExplorerSettings().viewMode)
        assertEquals(ViewMode.LIST, ExplorerSettings().copy(grid = false).viewMode)
        assertEquals(ThemeMode.SYSTEM, ExplorerSettings().theme)
    }
    @Test fun folderOnScreenCanBecomeTheDestinationOnlyWhenItMakesSense() {
        val photos = StorageLocation(LOCAL_SOURCE, "DCIM/photos")
        val pending = PendingTransfer(0, listOf(photos.child("a.jpg"), photos.child("trip")), move = true, destination = StorageLocation(LOCAL_SOURCE, "Download"))
        assertTrue(pending.canTarget(StorageLocation(LOCAL_SOURCE, "Pictures")))
        assertTrue(pending.canTarget(StorageLocation("usb-1", "DCIM/photos/trip")))
        assertFalse(pending.canTarget(StorageLocation(LOCAL_SOURCE, "Download")))
        // Where everything already is, a selected folder itself, or anything inside it.
        assertFalse(pending.canTarget(photos))
        assertFalse(pending.canTarget(photos.child("trip")))
        assertFalse(pending.canTarget(photos.child("trip").child("day 1")))
        // A sibling whose name merely starts the same way is fine.
        assertTrue(pending.canTarget(photos.child("trip-old")))
        assertFalse(pending.copy(sources = emptyList()).canTarget(StorageLocation(LOCAL_SOURCE, "Pictures")))
    }
}
