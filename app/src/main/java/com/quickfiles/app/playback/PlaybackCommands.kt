package com.quickfiles.app.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object PlaybackCommands {
    const val ARG_MINUTES = "minutes"
    const val ARG_SESSION_ID = "sessionId"
    val beginSession = SessionCommand("com.quickfiles.app.playback.BEGIN_SESSION", Bundle.EMPTY)
    val stop = SessionCommand("com.quickfiles.app.playback.STOP", Bundle.EMPTY)
    val audioOnly = SessionCommand("com.quickfiles.app.playback.AUDIO_ONLY", Bundle.EMPTY)
    val showVideo = SessionCommand("com.quickfiles.app.playback.SHOW_VIDEO", Bundle.EMPTY)
    val timerMinutes = SessionCommand("com.quickfiles.app.playback.TIMER_MINUTES", Bundle.EMPTY)
    val timerEndOfItem = SessionCommand("com.quickfiles.app.playback.TIMER_END_OF_ITEM", Bundle.EMPTY)
    val timerCancel = SessionCommand("com.quickfiles.app.playback.TIMER_CANCEL", Bundle.EMPTY)
    val retry = SessionCommand("com.quickfiles.app.playback.RETRY", Bundle.EMPTY)
    val all = listOf(beginSession, stop, audioOnly, showVideo, timerMinutes, timerEndOfItem, timerCancel, retry)
}

internal data class PlaybackSessionState(
    val sessionId: String = "",
    val closePlayerSessionId: String? = null,
    val timerKind: SleepTimerKind = SleepTimerKind.OFF,
    val timerRemainingMs: Long = 0,
    val timerTargetMediaId: String? = null,
    val audioOnly: Boolean = false,
)

/** Identifies the currently rendered PlayerActivity so delayed PiP actions can be rejected. */
internal object PlaybackPipActionRuntime {
    @Volatile private var activeId: String = ""

    fun activate(): String = java.util.UUID.randomUUID().toString().also { activeId = it }
    fun isActive(id: String): Boolean = id.isNotEmpty() && activeId == id
    fun deactivate(id: String) { if (activeId == id) activeId = "" }
}

/** Process-local projection of state that is owned and mutated by PlaybackService. */
internal object PlaybackSessionRuntime {
    private val mutable = MutableStateFlow(PlaybackSessionState())
    val state = mutable.asStateFlow()

    fun publish(value: PlaybackSessionState) { mutable.value = value }
    fun clear() { mutable.value = PlaybackSessionState() }
}

internal fun timerLabel(state: PlaybackSessionState): String = when (state.timerKind) {
    SleepTimerKind.OFF -> ""
    SleepTimerKind.END_OF_ITEM -> "현재 파일 끝에서 종료"
    SleepTimerKind.DURATION -> "타이머 ${clockText(if (state.timerRemainingMs <= 0) 0 else ((state.timerRemainingMs + 999) / 1000) * 1000)}"
}
