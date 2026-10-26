package com.quickfiles.app.operations

import android.Manifest
import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.quickfiles.app.AppGraph
import com.quickfiles.app.MainActivity
import com.quickfiles.app.data.FileTask
import kotlinx.coroutines.*

object TransferScheduler {
    const val NOTIFICATION = 1200
    private const val JOB = 1200
    private const val CHANNEL = "file-transfers"
    fun schedule(context: Context, network: Boolean) {
        if (Build.VERSION.SDK_INT >= 34 && network) {
            val job = JobInfo.Builder(JOB, ComponentName(context, TransferJobService::class.java))
                .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build()
            check(context.getSystemService(JobScheduler::class.java).schedule(job) == JobScheduler.RESULT_SUCCESS) { "전송 작업을 시작하지 못했습니다. 앱에서 재개해 주세요." }
        } else {
            WorkManager.getInstance(context).enqueueUniqueWork("file-operations", ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<TransferWorker>().build())
        }
    }
    fun notification(context: Context, task: FileTask? = null): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "파일 전송", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("곳간 · ${task?.message ?: "파일 작업 준비"}")
            .setContentText(task?.let { "완료 ${it.completed} · 실패 ${it.failed}" } ?: "작업 상태를 확인합니다.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setSilent(true).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setProgress(1000, if (task != null && task.totalBytes > 0) (task.bytes * 1000 / task.totalBytes).toInt().coerceIn(0, 1000) else 0, task == null || task.totalBytes <= 0)
        if (task != null) {
            for ((action, label) in listOf("PAUSE" to "일시정지", "CANCEL" to "취소")) {
                val intent = Intent(context, TransferActionReceiver::class.java).setAction(action).putExtra("id", task.id)
                val pending = PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addAction(0, label, pending)
            }
        }
        return builder.build()
    }
}

class TransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT >= 34) setNotification(params, TransferScheduler.NOTIFICATION, TransferScheduler.notification(this), JOB_END_NOTIFICATION_POLICY_REMOVE)
        job = scope.launch {
            try {
                val graph = AppGraph.get(this@TransferJobService); graph.ready.await()
                graph.operations.drain { task ->
                    if (Build.VERSION.SDK_INT >= 34) setNotification(params, TransferScheduler.NOTIFICATION, TransferScheduler.notification(this@TransferJobService, task), JOB_END_NOTIFICATION_POLICY_REMOVE)
                }
            } finally { jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo() = ForegroundInfo(TransferScheduler.NOTIFICATION, TransferScheduler.notification(applicationContext), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())
        val graph = AppGraph.get(applicationContext); graph.ready.await()
        graph.operations.drain { task -> applicationContext.getSystemService(NotificationManager::class.java).notify(TransferScheduler.NOTIFICATION, TransferScheduler.notification(applicationContext, task)) }
        return Result.success()
    }
}

class TransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val result = goAsync()
        AppGraph.get(context).scope.launch {
            try { AppGraph.get(context).operations.pause(id, intent.action == "CANCEL") }
            finally { result.finish() }
        }
    }
}
