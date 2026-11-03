package com.quickfiles.app

import com.quickfiles.app.data.*
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.*
import org.junit.Assert.*
import org.junit.Test

class UiStateTest {
    private fun entry(name: String, folder: Boolean = false, size: Long = 0) = FileEntry(StorageLocation(LOCAL_SOURCE, name), name, folder, size, size)
    @Test fun namesSortByNumberValue() {
        val names = listOf("IMG_10.jpg", "img_2.jpg", "IMG_1.jpg", "IMG_002.jpg", "IMG_02.jpg", "IMG_100.jpg", "IMG_9a.jpg", "IMG_9.jpg", "img", "IMG", "3화", "10화", "가나", "가")
        val sorted = sortedEntries(names.map { entry(it) }, SortOrder.NAME).map { it.name }
        assertEquals(listOf("3화", "10화", "IMG", "img", "IMG_1.jpg", "img_2.jpg", "IMG_02.jpg", "IMG_002.jpg", "IMG_9.jpg", "IMG_9a.jpg", "IMG_10.jpg", "IMG_100.jpg", "가", "가나"), sorted)
        val descending = sortedEntries(names.map { entry(it) }, SortSpec(SortOrder.NAME, SortDirection.DESCENDING)).map { it.name }
        assertEquals(sorted.reversed(), descending)
        // Consistent with equality: every pair orders the same both ways.
        for (a in names) for (b in names) assertEquals(Integer.signum(NaturalNameOrder.compare(a, b)), -Integer.signum(NaturalNameOrder.compare(b, a)))
        // Very long digit runs must not overflow.
        assertTrue(NaturalNameOrder.compare("x99999999999999999999999", "x100000000000000000000000") < 0)
    }

    @Test fun nameDialogExplainsProblemsAndSelectsTheBaseName() {
        val taken = listOf("Report.pdf", "photos")
        assertEquals("이름을 입력해 주세요.", fileNameProblem("  ", taken))
        assertNotNull(fileNameProblem("a/b", taken)); assertNotNull(fileNameProblem("..", taken)); assertNotNull(fileNameProblem("a\u0001", taken))
        assertEquals("같은 이름의 항목이 이미 있습니다.", fileNameProblem("report.PDF", taken))
        assertNull("Renaming may change only the case of its own name", fileNameProblem("report.pdf", taken, current = "Report.pdf"))
        assertNotNull("but not take a sibling's name", fileNameProblem("Photos", taken, current = "Report.pdf"))
        assertNull(fileNameProblem("새 폴더", taken))
        assertEquals(androidx.compose.ui.text.TextRange(0, 6), nameSelection("Report.pdf", folder = false))
        assertEquals(androidx.compose.ui.text.TextRange(0, 11), nameSelection("archive.tar.gz", folder = false))
        assertEquals(androidx.compose.ui.text.TextRange(0, 8), nameSelection(".gitkeep", folder = false))
        assertEquals(androidx.compose.ui.text.TextRange(0, 7), nameSelection("v1.0.zz", folder = true))
    }

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
}
