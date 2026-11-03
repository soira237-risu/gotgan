package com.quickfiles.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.work.Configuration

open class GotganApplication : Application(), Configuration.Provider, AppGraphOwner {
    @get:androidx.annotation.Keep
    override val appGraph: AppGraph by lazy { AppGraph(this) }
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setJobSchedulerJobIdRange(2000, 3999).build()

    private val main = Handler(Looper.getMainLooper())
    private var started = 0
    /** Locks the vault once no Gotgan screen has been visible for [VAULT_GRACE_MS]. */
    private val lockVault = Runnable { if (started == 0) appGraph.repository.vault.lock() }

    override fun onCreate() {
        super.onCreate()
        // The grace period lets an app opened from the vault (or the screen-lock prompt) read the file it was handed.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++; main.removeCallbacks(lockVault) }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) { main.removeCallbacks(lockVault); main.postDelayed(lockVault, VAULT_GRACE_MS) }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object { const val VAULT_GRACE_MS = 30_000L }
}
