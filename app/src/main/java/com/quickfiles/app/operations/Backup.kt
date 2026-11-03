package com.quickfiles.app.operations

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.*
import com.quickfiles.app.AppGraph
import com.quickfiles.app.data.ExplorerDao
import com.quickfiles.app.data.SavedValue
import com.quickfiles.app.data.decodeLocation
import com.quickfiles.app.data.encode
import com.quickfiles.app.storage.StorageLocation
import com.quickfiles.app.storage.isDeviceSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A phone folder mirrored into a server folder: new files are uploaded, files already there are left alone,
 * and nothing is ever deleted on either side. The copy lands in `<destination>/<folder name>`.
 */
data class BackupRule(
    val id: String = UUID.randomUUID().toString(),
    val source: StorageLocation, val destination: StorageLocation,
    val enabled: Boolean = true, val lastRun: Long = 0, val lastTask: String? = null,
) {
    init {
        require(isDeviceSource(source.sourceId) && source.path.isNotEmpty()) { "휴대폰이나 USB의 폴더만 자동 백업할 수 있습니다." }
        require(!isDeviceSource(destination.sourceId)) { "백업 위치는 서버 폴더여야 합니다." }
    }
    fun encode(): String = JSONObject().put("id", id).put("source", source.encode()).put("destination", destination.encode())
        .put("enabled", enabled).put("lastRun", lastRun).put("lastTask", lastTask ?: JSONObject.NULL).toString()
    companion object {
        const val PREFIX = "backup:"
        fun decode(value: String): BackupRule? = runCatching {
            val json = JSONObject(value)
            BackupRule(json.getString("id"), decodeLocation(json.getString("source")), decodeLocation(json.getString("destination")),
                json.optBoolean("enabled", true), json.optLong("lastRun"), json.optString("lastTask").takeIf { !json.isNull("lastTask") && it.isNotEmpty() })
        }.getOrNull()
    }
}

/** Saved backup rules, observable for the settings screen. */
class BackupRules(private val dao: ExplorerDao) {
    private val state = MutableStateFlow<List<BackupRule>>(emptyList())
    val rules: StateFlow<List<BackupRule>> = state.asStateFlow()

    suspend fun load(): List<BackupRule> = dao.savedValues("${BackupRule.PREFIX}%", 1000).mapNotNull { BackupRule.decode(it.value) }
        .sortedBy { it.source.key }.also { state.value = it }
    suspend fun save(rule: BackupRule) { dao.save(SavedValue(BackupRule.PREFIX + rule.id, rule.encode())); load() }
    suspend fun remove(id: String) { dao.removeSaved(BackupRule.PREFIX + id); load() }

    /**
     * Queues one backup pass for [rule] unless its previous pass is still waiting or running.
     * @return the queued task id, or null when nothing was queued.
     */
    suspend fun run(engine: OperationEngine, rule: BackupRule, now: Long = System.currentTimeMillis()): String? {
        val previous = rule.lastTask?.let { dao.task(it) }
        if (previous != null && previous.state in ACTIVE) return null
        val id = engine.enqueue("BACKUP", listOf(rule.source), rule.destination, ConflictPolicy.MERGE)
        save(rule.copy(lastRun = now, lastTask = id))
        return id
    }

    companion object { val ACTIVE = setOf("QUEUED", "PREPARING", "RUNNING", "PAUSING", "CANCELLING", "PAUSED") }
}

/** Runs enabled backups every few hours, only on Wi-Fi (unmetered) and when the battery is not low. */
object BackupScheduler {
    private const val WORK = "auto-backup"
    const val INTERVAL_HOURS = 6L
    fun sync(context: Context, rules: List<BackupRule>) {
        val work = WorkManager.getInstance(context)
        if (rules.none { it.enabled }) { work.cancelUniqueWork(WORK); return }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo() = ForegroundInfo(TransferScheduler.NOTIFICATION, TransferScheduler.notification(applicationContext), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext); graph.ready.await()
        val queued = graph.backups.load().filter { it.enabled }.mapNotNull { rule -> runCatching { graph.backups.run(graph.operations, rule) }.getOrNull() }
        if (queued.isEmpty()) return Result.success()
        setForeground(getForegroundInfo())
        graph.operations.drain { task -> applicationContext.getSystemService(NotificationManager::class.java).notify(TransferScheduler.NOTIFICATION, TransferScheduler.notification(applicationContext, task)) }
        return Result.success()
    }
}
