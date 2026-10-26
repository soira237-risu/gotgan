package com.quickfiles.app

import android.app.Application
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.test.runner.AndroidJUnitRunner
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.quickfiles.app.storage.StorageProvider
import com.quickfiles.app.storage.StorageRepository
import com.quickfiles.app.data.SavedValue
import com.quickfiles.app.data.ExplorerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.ConcurrentHashMap

/** Only this test APK supplies fixture providers. Production has no fixture server or trust exceptions. */
class GotganTestRunner : AndroidJUnitRunner() {
    private var originalUi: Map<String, SavedValue?>? = null
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, FixtureApplication::class.java.name, context)

    override fun onStart() {
        if (protectUserState()) {
            lateinit var graph: AppGraph
            // Waiting for the main binding message also waits for Application.onCreate.
            runOnMainSync { graph = AppGraph.get(targetContext) }
            originalUi = runBlocking(Dispatchers.IO) {
                graph.ready.await()
                listOf("tab:A", "tab:B", "workspace:ui").associateWith { graph.dao.saved(it) }
            }
        }
        super.onStart()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        var code = resultCode
        val output = results ?: Bundle()
        try {
            originalUi?.let { before ->
                val deadline = SystemClock.uptimeMillis() + 5_000
                var remaining: List<Activity>
                do {
                    remaining = emptyList()
                    runOnMainSync {
                        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                        remaining = Stage.values().filter { it != Stage.DESTROYED }
                            .flatMap { monitor.getActivitiesInStage(it) }.distinct()
                        remaining.filterNot { it.isFinishing }.forEach { it.finish() }
                    }
                    if (remaining.isNotEmpty()) Thread.sleep(20)
                } while (remaining.isNotEmpty() && SystemClock.uptimeMillis() < deadline)
                check(remaining.isEmpty()) { "Test activities were not destroyed before state restore" }
                val graph = AppGraph.get(targetContext)
                runBlocking(Dispatchers.IO) {
                    graph.scope.coroutineContext[Job]?.cancelAndJoin()
                    ExplorerDatabase.get(targetContext).withTransaction {
                        for ((key, saved) in before) {
                            if (saved == null) graph.dao.removeSaved(key) else graph.dao.save(saved)
                        }
                        check(before.all { (key, saved) -> graph.dao.saved(key) == saved }) {
                            "Original browser state was not restored after instrumentation"
                        }
                    }
                }
                sendStatus(0, Bundle().apply { putString("stream", "ORIGINAL_UI_STATE_RESTORED keys=${before.size}\n") })
            }
        } catch (failure: Exception) {
            code = Activity.RESULT_CANCELED
            output.putString("shortMsg", "UI_STATE_RESTORE_FAILED: ${failure.javaClass.simpleName}")
            sendStatus(-1, Bundle().apply { putString("stream", "UI_STATE_RESTORE_FAILED: ${failure.javaClass.simpleName}\n") })
        } finally {
            super.finish(code, output)
        }
    }
}
private fun protectUserState() = InstrumentationRegistry.getArguments().getString("protectUserState") == "true"

class FixtureApplication : GotganApplication() {
    val providers = ConcurrentHashMap<String, StorageProvider>()
    override val appGraph by lazy {
        if (protectUserState()) {
            // Gate the first graph access before recovery or workers can consume existing jobs.
            runBlocking(Dispatchers.IO) {
                val active = setOf("QUEUED", "RUNNING", "PREPARING", "PAUSING", "CANCELLING")
                check(ExplorerDatabase.get(this@FixtureApplication).dao().tasks().first().none { it.state in active }) {
                    "Finish or pause existing file operations before device tests"
                }
            }
        }
        AppGraph(this, StorageRepository(this) { providers[it] })
    }
}
