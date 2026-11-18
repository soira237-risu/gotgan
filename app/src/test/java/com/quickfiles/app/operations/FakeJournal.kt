package com.quickfiles.app.operations

import com.quickfiles.app.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeJournal : ExplorerDao {
    val taskRows = linkedMapOf<String, FileTask>()
    val itemRows = linkedMapOf<String, TaskItem>()
    val trashRows = linkedMapOf<String, TrashItem>()
    val savedRows = linkedMapOf<String, SavedValue>()
    private val taskFlow = MutableStateFlow<List<FileTask>>(emptyList())
    private val trashFlow = MutableStateFlow<List<TrashItem>>(emptyList())
    override suspend fun task(task: FileTask) { taskRows[task.id] = task; taskFlow.value = taskRows.values.toList() }
    override suspend fun task(id: String) = taskRows[id]
    override fun tasks() = taskFlow
    override fun activeProgress() = taskFlow.map { tasks -> tasks.filter { it.state in setOf("RUNNING", "QUEUED", "PREPARING", "PAUSING", "CANCELLING") }.map { TaskProgress(it.state, it.bytes, it.totalBytes) } }
    override fun finishedTaskIds() = taskFlow.map { tasks -> tasks.filter { it.state == "COMPLETE" || it.state == "CANCELLED" }.map { it.id } }
    override suspend fun finishedTasks() = taskRows.values.filter { it.state == "COMPLETE" || (it.state == "CANCELLED" && it.kind !in setOf("SAVE", "SAVE_AS")) }
        .sortedByDescending { it.updated }.map { FinishedTask(it.id, it.updated) }
    override suspend fun forgetTaskItems(ids: List<String>) { itemRows.values.removeAll { it.taskId in ids } }
    override suspend fun forgetTasks(ids: List<String>) { ids.forEach { taskRows.remove(it) }; taskFlow.value = taskRows.values.toList() }
    override suspend fun nextTask() = taskRows.values.firstOrNull { it.state == "QUEUED" }
    override suspend fun recoverTasks() { taskRows.values.filter { it.state == "RUNNING" }.toList().forEach { task(it.copy(state = "PAUSED")) } }
    override suspend fun item(item: TaskItem) { itemRows[item.id] = item }
    override suspend fun item(id: String) = itemRows[id]
    override suspend fun items(task: String) = itemRows.values.filter { it.taskId == task }
    override suspend fun trash(item: TrashItem) { trashRows[item.id] = item; trashFlow.value = trashRows.values.toList() }
    override suspend fun trash(id: String) = trashRows[id]
    override fun trashItems() = trashFlow
    override suspend fun forgetTrash(id: String) { trashRows.remove(id); trashFlow.value = trashRows.values.toList() }
    override suspend fun save(value: SavedValue) { savedRows[value.key] = value }
    override suspend fun saved(key: String) = savedRows[key]
    override suspend fun savedValues(prefix: String, limit: Int) = savedRows.values.filter { it.key.startsWith(prefix.trimEnd('%')) }.take(limit)
    override suspend fun removeSaved(key: String) { savedRows.remove(key) }
}
