package com.quickfiles.app

import android.app.Application
import android.os.Build
import androidx.work.Configuration
import com.quickfiles.app.operations.CrashLog
import java.io.File

open class GotganApplication : Application(), Configuration.Provider, AppGraphOwner {
    @get:androidx.annotation.Keep
    override val appGraph: AppGraph by lazy { AppGraph(this) }
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setJobSchedulerJobIdRange(2000, 3999).build()

    override fun onCreate() {
        super.onCreate()
        // Installed before anything else runs, so even a crash while building the graph is kept.
        crashLog(this).install()
    }

    companion object {
        /** Reports live in app-private storage and leave the device only when the user shares one. */
        fun crashLog(app: Application) = CrashLog(File(app.filesDir, "crash")) {
            val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
            listOf(
                "앱: ${info?.versionName ?: "?"} (${info?.longVersionCode ?: "?"})",
                "기기: ${Build.MANUFACTURER} ${Build.MODEL}",
                "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            )
        }
    }
}
