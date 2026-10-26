package com.quickfiles.app

import android.app.Application
import androidx.work.Configuration

open class GotganApplication : Application(), Configuration.Provider, AppGraphOwner {
    @get:androidx.annotation.Keep
    override val appGraph: AppGraph by lazy { AppGraph(this) }
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setJobSchedulerJobIdRange(2000, 3999).build()
}
