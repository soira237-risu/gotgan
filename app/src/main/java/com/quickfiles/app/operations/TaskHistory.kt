package com.quickfiles.app.operations

import com.quickfiles.app.data.ExplorerDao
import com.quickfiles.app.data.FinishedTask

/**
 * Keeps the task journal from growing forever. Only finished tasks are forgotten: failed, partial and paused
 * ones stay for retry, and a cancelled save keeps its edit. Every task list query and every progress tick
 * reads the whole table, so its size is the cost of each.
 */
object TaskHistory {
    const val KEEP = 100
    const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    /** Ids to forget: anything past the newest [keep] finished tasks, and anything finished longer than [maxAge] ago. */
    fun prunable(finished: List<FinishedTask>, now: Long, keep: Int = KEEP, maxAge: Long = MAX_AGE_MS): List<String> =
        finished.sortedByDescending { it.updated }.filterIndexed { index, task -> index >= keep || now - task.updated > maxAge }.map { it.id }

    suspend fun prune(dao: ExplorerDao, now: Long = System.currentTimeMillis()): Int {
        val ids = prunable(dao.finishedTasks(), now)
        // Items first: a task row is never left pointing at items that are gone, only the other way round.
        for (chunk in ids.chunked(500)) { dao.forgetTaskItems(chunk); dao.forgetTasks(chunk) }
        return ids.size
    }
}
