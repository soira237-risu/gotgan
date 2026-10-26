package com.quickfiles.app.playback

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.quickfiles.app.AppGraph
import com.quickfiles.app.MainActivity
import com.quickfiles.app.R
import com.quickfiles.app.RecentsCoordinator
import com.quickfiles.app.ui.theme.GotganTheme
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private companion object { var outputOwner: java.lang.ref.WeakReference<PlayerActivity>? = null }
    private var pip by mutableStateOf(false)
    private var multiWindow by mutableStateOf(false)
    private var topResumed by mutableStateOf(false)
    private var outputVisible by mutableStateOf(true)
    private var autoPip = false
    private var returningToBrowser = false
    private var pipActionId = ""
    private var playerView: PlayerView? = null
    private val graph get() = AppGraph.get(this)
    private fun videoOutput(enabled: Boolean) {
        outputVisible = enabled
        if (enabled) outputOwner = java.lang.ref.WeakReference(this)
        fun videoTrack(visible: Boolean) {
            if (!visible && outputOwner?.get() !== this) return
            graph.playback.controller?.let { player ->
                val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                if (disabled == visible) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, !visible).build()
            }
        }
        // Hidden video does not need a decoder or an offscreen output queue. Keep the
        // audio renderer and session running, and restore video at the current position.
        if (!enabled) videoTrack(false)
        playerView?.player = if (enabled) graph.playback.controller else null
        if (enabled) videoTrack(true)
    }
    private fun willAutoPip() = !returningToBrowser && autoPip && !graph.playback.sessionState.value.audioOnly && graph.playback.state.value.video && graph.playback.state.value.playing
    private fun returnToBrowser() {
        if (returningToBrowser) return
        returningToBrowser = true
        RecentsCoordinator.browser(this)
        setPictureInPictureParams(buildPipParams())
        // The player owns a separate task for PiP. Reuse the existing browser task
        // and Activity so Back preserves both panes, selection and scroll position.
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
    private fun pipAction(action: String, requestCode: Int, icon: Int, title: String, description: String): RemoteAction {
        val sessionId = graph.playback.sessionState.value.sessionId
        val intent = Intent(this, PlaybackActionReceiver::class.java)
            .setAction(action)
            .setData(
                android.net.Uri.Builder()
                    .scheme("quickfiles")
                    .authority("pip-action")
                    .appendPath(sessionId)
                    .appendPath(pipActionId)
                    .appendPath(requestCode.toString())
                    .build(),
            )
            .putExtra(PlaybackActionReceiver.EXTRA_SESSION_ID, sessionId)
            .putExtra(PlaybackActionReceiver.EXTRA_ACTION_ID, pipActionId)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(Icon.createWithResource(this, icon), title, description, pendingIntent)
    }
    private fun pipActions(): List<RemoteAction> {
        val state = graph.playback.state.value
        val detail = graph.playback.details.value
        val session = graph.playback.sessionState.value
        if (!state.visible || session.sessionId.isEmpty() || !PlaybackPipActionRuntime.isActive(pipActionId)) return emptyList()
        val toggle = pipAction(
            PlaybackActionReceiver.ACTION_TOGGLE,
            PlaybackActionReceiver.REQUEST_TOGGLE,
            if (state.playWhenReady) R.drawable.ic_pip_pause else R.drawable.ic_pip_play,
            if (state.playWhenReady) "일시정지" else "재생",
            if (state.playWhenReady) "재생 일시정지" else "재생 시작",
        )
        if (!detail.seekable) return listOf(toggle)
        val backward = pipAction(
            PlaybackActionReceiver.ACTION_SEEK_BACKWARD,
            PlaybackActionReceiver.REQUEST_SEEK_BACKWARD,
            R.drawable.ic_pip_seek_back,
            "−10초",
            "10초 뒤로 이동",
        )
        val forward = pipAction(
            PlaybackActionReceiver.ACTION_SEEK_FORWARD,
            PlaybackActionReceiver.REQUEST_SEEK_FORWARD,
            R.drawable.ic_pip_seek_forward,
            "+10초",
            "10초 앞으로 이동",
        )
        val actions = listOf(backward, toggle, forward)
        val maxActions = maxNumPictureInPictureActions.coerceAtLeast(0)
        if (maxActions >= actions.size) return actions
        // Some devices expose fewer PiP slots. Keep playback control first, then
        // fill remaining slots with the available seek actions.
        return buildList {
            if (maxActions > 0) add(toggle)
            if (maxActions > size) add(backward)
            if (maxActions > size) add(forward)
        }
    }
    internal fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        if (android.os.Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(willAutoPip())
        playerView?.videoSurfaceView?.let { view -> val rect = android.graphics.Rect(); if (view.getGlobalVisibleRect(rect) && !rect.isEmpty) builder.setSourceRectHint(rect) }
        val ratio = (graph.playback.details.value.aspect ?: (16f / 9f)).coerceIn(0.42f, 2.38f)
        builder.setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        builder.setActions(pipActions())
        if (android.os.Build.VERSION.SDK_INT >= 33 && graph.playback.state.value.visible && graph.playback.sessionState.value.sessionId.isNotEmpty()) {
            builder.setCloseAction(
                pipAction(
                    PlaybackActionReceiver.ACTION_STOP,
                    PlaybackActionReceiver.REQUEST_STOP,
                    R.drawable.ic_pip_stop,
                    "닫기",
                    "재생을 멈추고 닫기",
                ),
            )
        }
        return builder.build()
    }
    private fun enterPip() { if (graph.playback.state.value.video && !graph.playback.sessionState.value.audioOnly) enterPictureInPictureMode(buildPipParams()) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { returnToBrowser() }
        pipActionId = PlaybackPipActionRuntime.activate()
        enableEdgeToEdge()
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        pip = isInPictureInPictureMode; multiWindow = isInMultiWindowMode
        lifecycleScope.launch { graph.playback.setAudioOnly(false) }
        lifecycleScope.launch { graph.settings.settings.collect { autoPip = it.autoPip; setPictureInPictureParams(buildPipParams()) } }
        lifecycleScope.launch {
            graph.playback.sessionState.collect { session ->
                if (session.closePlayerSessionId != null && session.closePlayerSessionId == session.sessionId) {
                    RecentsCoordinator.browser(this@PlayerActivity)
                    finish()
                }
            }
        }
        setContent {
            val state by graph.playback.state.collectAsStateWithLifecycle()
            val detail by graph.playback.details.collectAsStateWithLifecycle()
            val sessionState by graph.playback.sessionState.collectAsStateWithLifecycle()
            var manualMediaId by rememberSaveable { mutableStateOf("") }
            var manualPortrait by rememberSaveable { mutableStateOf<Boolean?>(null) }
            val orientationOverride = manualPortrait.takeIf { manualMediaId == detail.mediaId }
            LaunchedEffect(detail.mediaId) {
                if (manualMediaId != detail.mediaId) { manualPortrait = null; manualMediaId = detail.mediaId }
            }
            LaunchedEffect(detail.mediaId, detail.aspect, detail.seekable, orientationOverride, pip, multiWindow, topResumed, state.video, state.playing, state.playWhenReady, state.visible, sessionState.audioOnly, sessionState.sessionId) {
                // Android sizes PiP and split windows itself. Changing the orientation request
                // during a PiP transition starts a competing display rotation.
                if (topResumed && !pip && !multiWindow) {
                    val target = if (!state.video) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    else (orientationOverride ?: detail.aspect?.let { it < 1f })?.let {
                        if (it) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                    if (target != null && requestedOrientation != target) requestedOrientation = target
                }
                setPictureInPictureParams(buildPipParams())
            }
            GotganTheme(darkOverride = true, manageSystemBars = false) {
                PlayerScreen(graph.playback, pip, this, outputVisible, onBack = ::returnToBrowser, onPip = ::enterPip,
                    onRotate = { manualPortrait = resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT },
                    onStop = { lifecycleScope.launch { graph.playback.stop(); returnToBrowser() } },
                    onView = { output ->
                        playerView = output
                        output.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> setPictureInPictureParams(buildPipParams()) }
                        setPictureInPictureParams(buildPipParams())
                    })
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); pip = isInPictureInPictureMode }
    override fun onResume() { super.onResume(); pip = isInPictureInPictureMode; multiWindow = isInMultiWindowMode; videoOutput(true) }
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity); topResumed = isTopResumedActivity
        if (isTopResumedActivity && !isInPictureInPictureMode && !returningToBrowser) RecentsCoordinator.foreground(this)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) videoOutput(true)
    }
    override fun onPause() {
        if (!isInPictureInPictureMode && !willAutoPip()) videoOutput(false)
        super.onPause()
    }
    override fun onStop() {
        // A hidden window must not hold the video output queue while audio continues.
        if (!isInPictureInPictureMode) videoOutput(false)
        super.onStop()
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Release the output before the Home animation can stall its buffer queue.
        // The service and audio clock continue, and recomposition must not reconnect it.
        if (!isInPictureInPictureMode && !willAutoPip()) videoOutput(false)
        if (android.os.Build.VERSION.SDK_INT < 31 && willAutoPip()) enterPip()
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig); pip = isInPictureInPictureMode
        if (isInPictureInPictureMode) RecentsCoordinator.browser(this, createIfMissing = true)
        else if (topResumed && !returningToBrowser) RecentsCoordinator.foreground(this)
        if (isInPictureInPictureMode) videoOutput(true)
    }
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig); multiWindow = isInMultiWindowMode
    }
    override fun onDestroy() {
        val closingSessionId = graph.playback.sessionState.value.sessionId
        val stopClosingPip = pip && isFinishing && closingSessionId.isNotEmpty() &&
            graph.playback.state.value.visible && PlaybackPipActionRuntime.isActive(pipActionId)
        PlaybackPipActionRuntime.deactivate(pipActionId)
        if (stopClosingPip) {
            graph.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                if (
                    graph.playback.sessionState.value.sessionId == closingSessionId &&
                    graph.playback.state.value.visible
                ) graph.playback.stop(closingSessionId)
            }
        }
        if (outputOwner?.get() === this) { videoOutput(false); outputOwner = null }
        playerView?.player = null; playerView = null; super.onDestroy()
    }
}
