package com.quickfiles.app

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.quickfiles.app.playback.PlaybackConnection
import com.quickfiles.app.playback.PlayerActivity
import kotlinx.coroutines.runBlocking
import org.junit.runners.model.MultipleFailureException

/** Finish test-opened player windows before the next test creates its Activity. */
internal fun cleanupPlayerTest(playback: PlaybackConnection) {
    val failures = mutableListOf<Throwable>()
    try {
        finishPlayerActivities()
    } catch (failure: Throwable) {
        failures += failure
    }
    // A failed Activity cleanup must not leave the session playing for the next test.
    try {
        runBlocking { playback.stop() }
    } catch (failure: Throwable) {
        failures += failure
    }
    MultipleFailureException.assertEmpty(failures)
}

private fun finishPlayerActivities() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val monitor = ActivityLifecycleMonitorRegistry.getInstance()
    val observed = linkedSetOf<PlayerActivity>()
    val deadline = SystemClock.elapsedRealtime() + 15_000
    var remaining: List<String>
    do {
        remaining = emptyList()
        instrumentation.runOnMainSync {
            // PiP and configuration transitions can leave windows in PAUSED/CREATED.
            // Discover replacements on every pass, rather than retaining only the
            // Activity instance originally opened by the test.
            Stage.entries.filter { it != Stage.DESTROYED }.forEach { stage ->
                monitor.getActivitiesInStage(stage).filterIsInstance<PlayerActivity>().forEach { activity ->
                    observed += activity
                    if (!activity.isFinishing) activity.finish()
                }
            }
            remaining = observed.filter { monitor.getLifecycleStageOf(it) != Stage.DESTROYED }.map {
                "${it.javaClass.simpleName}@${System.identityHashCode(it)} stage=${monitor.getLifecycleStageOf(it)} finishing=${it.isFinishing}"
            }
        }
        if (remaining.isEmpty()) {
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("stream", "PLAYER_CLEANUP destroyed=${observed.size} remaining=0\n")
            })
            return
        }
        SystemClock.sleep(50)
    } while (SystemClock.elapsedRealtime() < deadline)
    throw AssertionError("PlayerActivity cleanup did not reach DESTROYED within 15000 ms: ${remaining.joinToString()}")
}
