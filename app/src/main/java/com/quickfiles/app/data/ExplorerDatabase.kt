package com.quickfiles.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tasks")
data class FileTask(
    @PrimaryKey val id: String,
    val kind: String, val payload: String,
    val state: String = "QUEUED", val message: String = "",
    val completed: Int = 0, val skipped: Int = 0, val failed: Int = 0,
    val bytes: Long = 0, val totalBytes: Long = -1, val speed: Long = 0,
    val created: Long = System.currentTimeMillis(), val updated: Long = System.currentTimeMillis(),
)
@Entity(tableName = "task_items", indices = [Index("taskId")])
data class TaskItem(
    @PrimaryKey val id: String, val taskId: String,
    val source: String, val destination: String,
    val stage: String, val revision: String,
    val phase: String = "PLANNED", val hash: String = "", val bytes: Long = 0,
    val backupId: String = "", val error: String = "", val targetRevision: String = "",
)
@Entity(tableName = "trash")
data class TrashItem(
    @PrimaryKey val id: String, val source: String, val original: String,
    val directory: String, val payloadPath: String, val name: String,
    val revision: String, val isDirectory: Boolean,
    val state: String = "PREPARING", val deleted: Long = System.currentTimeMillis(),
)
/** Just what the dock's progress ring needs, without the task payload. */
data class TaskProgress(val state: String, val bytes: Long, val totalBytes: Long)
/** A finished task that history pruning may forget. */
data class FinishedTask(val id: String, val updated: Long)

@Entity(tableName = "saved_state")
data class SavedValue(@PrimaryKey val key: String, val value: String, val updated: Long = System.currentTimeMillis())

@Dao
interface ExplorerDao {
    @Upsert suspend fun task(task: FileTask)
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun task(id: String): FileTask?
    @Query("SELECT * FROM tasks ORDER BY created DESC") fun tasks(): Flow<List<FileTask>>
    @Query("SELECT state, bytes, totalBytes FROM tasks WHERE state IN ('RUNNING','QUEUED','PREPARING','PAUSING','CANCELLING')") fun activeProgress(): Flow<List<TaskProgress>>
    @Query("SELECT id FROM tasks WHERE state IN ('COMPLETE','CANCELLED')") fun finishedTaskIds(): Flow<List<String>>
    /** Finished tasks whose record nothing needs any more; a cancelled save keeps its edit for "save as" and is never listed. */
    @Query("SELECT id, updated FROM tasks WHERE state = 'COMPLETE' OR (state = 'CANCELLED' AND kind NOT IN ('SAVE','SAVE_AS')) ORDER BY updated DESC") suspend fun finishedTasks(): List<FinishedTask>
    @Query("DELETE FROM task_items WHERE taskId IN (:ids)") suspend fun forgetTaskItems(ids: List<String>)
    @Query("DELETE FROM tasks WHERE id IN (:ids)") suspend fun forgetTasks(ids: List<String>)
    @Query("SELECT * FROM tasks WHERE state = 'QUEUED' ORDER BY created LIMIT 1") suspend fun nextTask(): FileTask?
    @Query("UPDATE tasks SET state = 'PAUSED', message = '작업 상태를 확인한 뒤 재개하세요.' WHERE state IN ('RUNNING','PAUSING','CANCELLING','PREPARING')") suspend fun recoverTasks()
    @Upsert suspend fun item(item: TaskItem)
    @Query("SELECT * FROM task_items WHERE id = :id") suspend fun item(id: String): TaskItem?
    @Query("SELECT * FROM task_items WHERE taskId = :task") suspend fun items(task: String): List<TaskItem>
    @Upsert suspend fun trash(item: TrashItem)
    @Query("SELECT * FROM trash WHERE id = :id") suspend fun trash(id: String): TrashItem?
    @Query("SELECT * FROM trash ORDER BY deleted DESC") fun trashItems(): Flow<List<TrashItem>>
    @Query("DELETE FROM trash WHERE id = :id") suspend fun forgetTrash(id: String)
    @Upsert suspend fun save(value: SavedValue)
    @Query("SELECT * FROM saved_state WHERE `key` = :key") suspend fun saved(key: String): SavedValue?
    @Query("SELECT * FROM saved_state WHERE `key` LIKE :prefix ORDER BY updated DESC LIMIT :limit") suspend fun savedValues(prefix: String, limit: Int = 100): List<SavedValue>
    @Query("DELETE FROM saved_state WHERE `key` = :key") suspend fun removeSaved(key: String)
}

@Database(entities = [FileTask::class, TaskItem::class, TrashItem::class, SavedValue::class], version = 1, exportSchema = false)
abstract class ExplorerDatabase : RoomDatabase() {
    abstract fun dao(): ExplorerDao
    companion object {
        @Volatile private var instance: ExplorerDatabase? = null
        fun get(context: Context): ExplorerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ExplorerDatabase::class.java, "explorer.db").build().also { instance = it }
        }
    }
}
