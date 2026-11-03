package com.quickfiles.app.storage

import org.junit.Assert.*
import org.junit.Test

class BatchRenameTest {
    private fun file(name: String, folder: String = "DCIM") = FileEntry(StorageLocation(LOCAL_SOURCE, "$folder/$name"), name, false)

    @Test fun numberedTemplateKeepsExtensionsAndPads() {
        val selected = (1..10).map { file("IMG_$it.JPG") }
        val plan = BatchRename.plan(selected, selected, "Trip {n}", "", "")
        assertEquals("Trip 01.JPG", plan.items.first().name)
        assertEquals("Trip 10.JPG", plan.items.last().name)
        assertTrue(plan.valid); assertEquals(10, plan.renames.size)
    }

    @Test fun findReplaceAndOldNameTemplate() {
        val selected = listOf(file("draft-report.txt"), file("draft notes"), FileEntry(StorageLocation(LOCAL_SOURCE, "DCIM/draft.d"), "draft.d", true))
        val plan = BatchRename.plan(selected, selected, "{name}", "draft", "final")
        assertEquals(listOf("final-report.txt", "final notes", "final.d"), plan.items.map { it.name })
        assertTrue(plan.valid)
    }

    @Test fun reportsCollisionsInsteadOfRunningThem() {
        val a = file("a.txt"); val b = file("b.txt"); val other = file("x 1.txt")
        // Two selected files would both become "same.txt".
        assertEquals(listOf("새 이름이 겹침", "새 이름이 겹침"), BatchRename.plan(listOf(a, b), listOf(a, b), "same", "", "").items.map { it.problem })
        // An unselected sibling already holds the name; comparison ignores case.
        val clash = BatchRename.plan(listOf(a, b), listOf(a, b, other), "X {n}", "", "")
        assertEquals("같은 이름의 파일이 있음", clash.items[0].problem); assertNull(clash.items[1].problem); assertFalse(clash.valid)
        // Renaming onto another selected item's current name would depend on order.
        assertEquals("다른 선택 항목의 현재 이름과 겹침", BatchRename.plan(listOf(a, b), listOf(a, b), "", "a", "b").items[0].problem)
        assertEquals("사용할 수 없는 이름", BatchRename.plan(listOf(a), listOf(a), "x/y", "", "").items.single().problem)
        assertEquals("대소문자만 바꿀 수 없음", BatchRename.plan(listOf(a), listOf(a), "A", "", "").items.single().problem)
        // The same new name in different folders (search results) is fine.
        assertTrue(BatchRename.plan(listOf(file("a.txt", "one"), file("a.txt", "two")), emptyList(), "b", "", "").valid)
    }

    @Test fun unchangedNamesAreNotRenames() {
        val a = file("a.txt")
        val plan = BatchRename.plan(listOf(a), listOf(a), "{name}", "", "")
        assertFalse(plan.items.single().changes); assertFalse(plan.valid)
    }
}
