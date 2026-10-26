package com.quickfiles.app.storage

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/** Permission routing only. File reads and writes belong in the next development phase. */
object StorageAccess {
    fun openSettings(activity: Activity): Boolean {
        val intents = listOf(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                "package:${activity.packageName}".toUri(),
            ),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // Some device settings apps expose only the general permission list.
            } catch (_: SecurityException) {
                // Try the public settings list if the app-specific route is restricted.
            }
        }
        return false
    }
}
