package com.quickfiles.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent

/** Keep the browser and PiP tasks alive, but expose only the foreground destination. */
internal object RecentsCoordinator {
    fun foreground(activity: Activity) = expose(activity, activity.taskId)

    fun browser(activity: Activity, createIfMissing: Boolean = false) {
        val tasks = activity.getSystemService(ActivityManager::class.java).appTasks
        val browser = tasks.firstOrNull {
            runCatching { it.taskInfo.baseIntent.component?.className == MainActivity::class.java.name }.getOrDefault(false)
        }
        if (browser != null) expose(activity, browser.taskInfo.taskId)
        else if (createIfMissing) {
            activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private fun expose(activity: Activity, taskId: Int) {
        val tasks = activity.getSystemService(ActivityManager::class.java).appTasks
        // Hide companions first. Never finish a task here: that would lose browser state.
        tasks.forEach { task -> runCatching { if (task.taskInfo.taskId != taskId) task.setExcludeFromRecents(true) } }
        tasks.forEach { task -> runCatching { if (task.taskInfo.taskId == taskId) task.setExcludeFromRecents(false) } }
    }
}
