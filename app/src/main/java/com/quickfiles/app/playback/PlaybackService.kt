package com.quickfiles.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.quickfiles.app.AppGraph
import com.quickfiles.app.data.SavedValue
import com.quickfiles.app.storage.StorageError
import com.quickfiles.app.storage.StorageException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var graph: AppGraph
    private lateinit var player: ExoPlayer
    private lateinit var timer: SleepTimerController
    private var timerJob: Job? = null
    private var audioOnly = false
    private var stopping = false
    private var activeSessionId = ""
    private var closePlayerSessionId: String? = null
    private var observedMediaId = ""
    private var failedSessionId: String? = null

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.get(this)
        timer = SleepTimerController(SystemClock::elapsedRealtime)
        val sources = DefaultMediaSourceFactory { StorageDataSource(graph) }.setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(2) {
            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val causes = generateSequence(info.exception as Throwable) { it.cause }.take(12).toList()
                if (causes.filterIsInstance<StorageException>().none { it.kind == StorageError.NETWORK } || info.errorCount > 2) return C.TIME_UNSET
                return causes.filterIsInstance<MediaReadError>().firstOrNull()?.budget?.nextDelay() ?: C.TIME_UNSET
            }
        })
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sources)
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
                addListener(timerListener)
            }
        PlaybackDiagnostics.attach(player)

        val openPlayer = PendingIntent.getActivity(this, 10, Intent(this, PlayerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openPlayer)
            .setMediaButtonPreferences(mediaButtons())
            .setCallback(sessionCallback)
            .build()
        publishSessionState()
        scope.launch {
            while (isActive) {
                delay(5_000)
                val item = player.currentMediaItem ?: continue
                graph.dao.save(SavedValue("playback:${item.mediaId}", player.currentPosition.toString()))
            }
        }
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val base = super.onConnect(session, controller)
            if (!base.isAccepted || controller.packageName != packageName) return base
            val commands = base.availableSessionCommands.buildUpon().apply { PlaybackCommands.all.forEach(::add) }.build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(base.availablePlayerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) return result(SessionResult.RESULT_ERROR_PERMISSION_DENIED)
            val code = when (customCommand.customAction) {
                PlaybackCommands.beginSession.customAction -> beginPlaybackSession(
                    args.getString(PlaybackCommands.ARG_SESSION_ID).orEmpty(),
                )
                PlaybackCommands.stop.customAction -> {
                    val expectedId = args.getString(PlaybackCommands.ARG_SESSION_ID)
                    if (expectedId != null && expectedId != activeSessionId) SessionResult.RESULT_ERROR_INVALID_STATE
                    else { stopPlayback(); SessionResult.RESULT_SUCCESS }
                }
                PlaybackCommands.audioOnly.customAction -> if (setAudioOnly(true)) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_INVALID_STATE
                PlaybackCommands.showVideo.customAction -> { setAudioOnly(false); SessionResult.RESULT_SUCCESS }
                PlaybackCommands.timerMinutes.customAction -> startDurationTimer(args.getInt(PlaybackCommands.ARG_MINUTES, 0))
                PlaybackCommands.timerEndOfItem.customAction -> startEndOfItemTimer()
                PlaybackCommands.timerCancel.customAction -> { cancelTimer(); SessionResult.RESULT_SUCCESS }
                PlaybackCommands.retry.customAction -> retryPlayback()
                else -> SessionResult.RESULT_ERROR_NOT_SUPPORTED
            }
            return result(code)
        }
    }

    private val timerListener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if (!stopping && activeSessionId.isNotEmpty() && closePlayerSessionId != activeSessionId) {
                failedSessionId = activeSessionId
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previousId = observedMediaId
            val selectedId = mediaItem?.mediaId.orEmpty()
            observedMediaId = selectedId
            if (timer.onCurrentItemChanged(selectedId)) {
                player.setPauseAtEndOfMediaItems(false)
                publishSessionState()
            }
            // Seeking to a different item updates the queue even after a fatal error,
            // but does not move ExoPlayer out of IDLE. Recover only that explicit
            // selection; same-item seeks and automatic transitions never retry.
            if (
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
                previousId.isNotEmpty() && selectedId.isNotEmpty() && previousId != selectedId &&
                player.currentMediaItem?.mediaId == selectedId && player.mediaItemCount > 0 &&
                !stopping && activeSessionId.isNotEmpty() && closePlayerSessionId != activeSessionId &&
                (player.playerError != null ||
                    (failedSessionId == activeSessionId && player.playbackState == Player.STATE_IDLE))
            ) {
                // Consume an already-due duration timer before acquiring a decoder.
                if (timer.poll()) stopPlayback()
                else {
                    failedSessionId = null
                    player.prepare() // Keep playWhenReady, options and the remaining timer.
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) consumeEndOfItem()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_IDLE) failedSessionId = null
            if (playbackState == Player.STATE_ENDED) consumeEndOfItem()
        }
    }

    private fun mediaButtons(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName("10초 뒤로")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
            .setDisplayName("10초 앞으로")
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName("이전 파일")
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .setSlots(CommandButton.SLOT_BACK_SECONDARY)
            .build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName("다음 파일")
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY)
            .build(),
    )

    private fun beginPlaybackSession(id: String): Int {
        if (id.length !in 1..80) return SessionResult.RESULT_ERROR_BAD_VALUE
        // A new explicit file selection invalidates old PiP tokens while retaining
        // the user's current playback settings and duration timer.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .build()
        audioOnly = false
        activeSessionId = id
        closePlayerSessionId = null
        failedSessionId = null
        publishSessionState()
        return SessionResult.RESULT_SUCCESS
    }

    private fun setAudioOnly(enabled: Boolean): Boolean {
        if (enabled && (player.currentMediaItem == null || player.currentTracks.groups.none { group ->
                group.type == C.TRACK_TYPE_AUDIO && (0 until group.length).any(group::isTrackSupported)
            })) return false
        val parameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, enabled).build()
        if (parameters != player.trackSelectionParameters) player.trackSelectionParameters = parameters
        audioOnly = enabled
        publishSessionState()
        return true
    }

    private fun startDurationTimer(minutes: Int): Int {
        if (player.currentMediaItem == null || minutes !in SleepTimerController.MINUTES_MIN..SleepTimerController.MINUTES_MAX) {
            return SessionResult.RESULT_ERROR_BAD_VALUE
        }
        timer.startDuration(minutes)
        player.setPauseAtEndOfMediaItems(false)
        scheduleDurationTimer()
        publishSessionState()
        return SessionResult.RESULT_SUCCESS
    }

    private fun startEndOfItemTimer(): Int {
        val item = player.currentMediaItem ?: return SessionResult.RESULT_ERROR_INVALID_STATE
        if (player.duration == C.TIME_UNSET || player.duration <= 0 || player.isCurrentMediaItemLive) return SessionResult.RESULT_ERROR_NOT_SUPPORTED
        timer.startEndOfItem(item.mediaId)
        timerJob?.cancel(); timerJob = null
        player.setPauseAtEndOfMediaItems(true)
        publishSessionState()
        return SessionResult.RESULT_SUCCESS
    }

    private fun scheduleDurationTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive && timer.snapshot.kind == SleepTimerKind.DURATION) {
                val remaining = timer.remaining() ?: break
                publishSessionState()
                if (timer.poll()) {
                    publishSessionState()
                    stopPlayback()
                    break
                }
                delay(remaining.coerceIn(1L, 1_000L))
            }
        }
    }

    private fun cancelTimer() {
        timerJob?.cancel(); timerJob = null
        timer.cancel()
        player.setPauseAtEndOfMediaItems(false)
        publishSessionState()
    }

    private fun consumeEndOfItem() {
        val id = player.currentMediaItem?.mediaId ?: return
        if (timer.onItemEnded(id)) stopPlayback()
    }

    private fun retryPlayback(): Int {
        if (player.currentMediaItem == null || player.playerError == null) return SessionResult.RESULT_ERROR_INVALID_STATE
        player.prepare()
        return SessionResult.RESULT_SUCCESS
    }

    private fun stopPlayback() {
        if (stopping) return
        stopping = true
        failedSessionId = null
        val stoppedSessionId = activeSessionId
        val itemId = player.currentMediaItem?.mediaId
        val position = player.currentPosition.coerceAtLeast(0)
        val subtitleIds = ((0 until player.mediaItemCount)
            .flatMap { index -> player.getMediaItemAt(index).localConfiguration?.subtitleConfigurations.orEmpty() }
            .mapNotNull { it.uri.lastPathSegment } +
            graph.playback.options.choices.values.mapNotNull { it.external?.uri?.lastPathSegment })
            .toSet()
        player.pause()
        timerJob?.cancel(); timerJob = null
        timer.cancel()
        player.setPauseAtEndOfMediaItems(false)
        player.stop()
        player.clearMediaItems()
        player.setPlaybackSpeed(1f)
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.volume = 1f
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
        audioOnly = false
        closePlayerSessionId = stoppedSessionId.ifEmpty { null }
        publishSessionState()
        graph.playback.onServiceStopped()
        scope.launch(Dispatchers.IO) {
            if (!itemId.isNullOrEmpty()) graph.dao.save(SavedValue("playback:$itemId", position.toString()))
            subtitleIds.forEach { id -> runCatching { SubtitleFiles.file(this@PlaybackService, id).delete() } }
        }
        stopping = false
    }

    private fun publishSessionState() {
        val snapshot = timer.snapshot
        PlaybackSessionRuntime.publish(PlaybackSessionState(
            sessionId = activeSessionId,
            closePlayerSessionId = closePlayerSessionId,
            timerKind = snapshot.kind,
            timerRemainingMs = timer.remaining() ?: 0,
            timerTargetMediaId = snapshot.targetMediaId,
            audioOnly = audioOnly,
        ))
    }

    private fun result(code: Int): ListenableFuture<SessionResult> = Futures.immediateFuture(SessionResult(code))

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onTaskRemoved(rootIntent: Intent?) { if (!player.playWhenReady) stopSelf() }

    override fun onDestroy() {
        timerJob?.cancel()
        scope.cancel()
        if (::player.isInitialized) {
            player.removeListener(timerListener)
            PlaybackDiagnostics.detach(player)
            player.release()
        }
        session?.release()
        session = null
        PlaybackSessionRuntime.clear()
        super.onDestroy()
    }

    private companion object { const val SEEK_MS = 10_000L }
}
