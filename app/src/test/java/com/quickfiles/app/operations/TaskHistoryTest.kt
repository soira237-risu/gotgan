package com.quickfiles.app.operations

import com.quickfiles.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskHistoryTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    @Test fun forgetsOnlyOldOrSurplusFinishedTasksWithTheirItems() = runBlocking {
        val journal = FakeJournal()
        suspend fun task(id: String, state: String, age: Long, kind: String = "COPY") {
            journal.task(FileTask(id, kind, "{}", state = state, updated = now - age))
            journal.item(TaskItem("$id-item", id, "s", "d", "st", "r"))
        }
        task("recent", "COMPLETE", day)
        task("old", "COMPLETE", 20 * day)
        task("oldCancelled", "CANCELLED", 20 * day)
        task("oldFailed", "FAILED", 20 * day)
        task("oldPartial", "PARTIAL", 20 * day)
        task("oldPaused", "PAUSED", 20 * day)
        task("oldCancelledSave", "CANCELLED", 20 * day, kind = "SAVE")
        assertEquals(2, TaskHistory.prune(journal, now))
        assertEquals(setOf("recent", "oldFailed", "oldPartial", "oldPaused", "oldCancelledSave"), journal.taskRows.keys)
        assertEquals(journal.taskRows.keys.map { "$it-item" }.toSet(), journal.itemRows.keys)
    }

    @Test fun keepsOnlyTheNewestFinishedTasksPastTheLimit() {
        val finished = (0 until 130).map { FinishedTask("t$it", now - it * 1000L) }
        val pruned = TaskHistory.prunable(finished.shuffled(), now)
        assertEquals((100 until 130).map { "t$it" }.toSet(), pruned.toSet())
        assertTrue(TaskHistory.prunable(finished.take(5), now).isEmpty())
    }
}
