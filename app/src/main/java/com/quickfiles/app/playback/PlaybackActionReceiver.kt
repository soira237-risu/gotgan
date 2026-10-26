package com.quickfiles.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.quickfiles.app.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Receives explicit, app-private actions exposed by the PiP controls. */
class PlaybackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pendingResult = goAsync()
        val graph = AppGraph.get(context)
        graph.scope.launch {
            try {
                // A PendingIntent can outlive the player window. Never let an old
                // PiP button create or revive a playback session.
                val expectedSessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                val expectedActionId = intent.getStringExtra(EXTRA_ACTION_ID).orEmpty()
                withContext(Dispatchers.Main.immediate) {
                    val session = graph.playback.sessionState.value
                    if (
                        !graph.playback.state.value.visible ||
                        expectedSessionId.isEmpty() ||
                        session.sessionId != expectedSessionId ||
                        !PlaybackPipActionRuntime.isActive(expectedActionId)
                    ) return@withContext
                    when (action) {
                        ACTION_SEEK_BACKWARD -> graph.playback.seekBy(-SEEK_DELTA_MS)
                        ACTION_SEEK_FORWARD -> graph.playback.seekBy(SEEK_DELTA_MS)
                        ACTION_TOGGLE -> graph.playback.toggle()
                        ACTION_STOP -> graph.playback.stop(expectedSessionId)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "com.quickfiles.app.playback.extra.SESSION_ID"
        const val EXTRA_ACTION_ID = "com.quickfiles.app.playback.extra.ACTION_ID"
        const val ACTION_SEEK_BACKWARD = "com.quickfiles.app.playback.action.SEEK_BACKWARD"
        const val ACTION_TOGGLE = "com.quickfiles.app.playback.action.TOGGLE"
        const val ACTION_SEEK_FORWARD = "com.quickfiles.app.playback.action.SEEK_FORWARD"
        const val ACTION_STOP = "com.quickfiles.app.playback.action.STOP"

        const val REQUEST_SEEK_BACKWARD = 0x510
        const val REQUEST_TOGGLE = 0x511
        const val REQUEST_SEEK_FORWARD = 0x512
        const val REQUEST_STOP = 0x513

        private const val SEEK_DELTA_MS = 10_000L
    }
}
