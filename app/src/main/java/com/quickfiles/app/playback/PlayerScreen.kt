package com.quickfiles.app.playback

import com.quickfiles.app.ui.icons.Symbols
import android.app.Activity
import android.content.res.Configuration
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun PlayerScreen(connection: PlaybackConnection, pip: Boolean, activity: Activity, outputEnabled: Boolean, revealable: Boolean,
    onBack: () -> Unit, onDismiss: () -> Unit, onPip: () -> Unit, onRotate: () -> Unit, onStop: () -> Unit, onView: (PlayerView) -> Unit) {
    var player by remember { mutableStateOf(connection.controller) }
    LaunchedEffect(connection) { player = connection.connect() }
    val state by connection.state.collectAsStateWithLifecycle()
    val detail by connection.details.collectAsStateWithLifecycle()
    val session by connection.sessionState.collectAsStateWithLifecycle()
    val fill by connection.options.fill.collectAsStateWithLifecycle()
    var controls by rememberSaveable(detail.mediaId) { mutableStateOf(true) }
    var locked by rememberSaveable(detail.mediaId) { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf<String?>(null) }
    var touchTick by remember { mutableIntStateOf(0) }
    val identity = detail.mediaId to session.sessionId
    var seeking by remember(identity) { mutableStateOf(false) }
    var preview by remember(identity) { mutableStateOf<SeekPreview?>(null) }
    var seekHint by remember { mutableStateOf("") }
    var audioOnlyLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val accessibility = remember(activity) { activity.getSystemService(AccessibilityManager::class.java) }
    val density = LocalDensity.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val gestureInsets = WindowInsets.systemGestures
    val leftEdgePx = maxOf(gestureInsets.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr).toFloat(), with(density) { 24.dp.toPx() })
    val rightEdgePx = maxOf(gestureInsets.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr).toFloat(), with(density) { 24.dp.toPx() })
    var exploring by remember { mutableStateOf(accessibility.isTouchExplorationEnabled) }
    DisposableEffect(accessibility) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { exploring = it }
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility.removeTouchExplorationStateChangeListener(listener) }
    }
    LaunchedEffect(pip) { controls = true; menu = null; locked = false }
    LaunchedEffect(state.error) { if (state.error != null) { controls = true; locked = false; menu = null } }
    LaunchedEffect(pip, locked, state.error, outputEnabled, detail.seekable) {
        if (pip || locked || state.error != null || !outputEnabled || !detail.seekable) { preview = null; seeking = false }
    }
    LaunchedEffect(controls, state.playing, menu, exploring, locked, touchTick, seeking, preview) {
        if (controls && state.playing && menu == null && !exploring && !locked && !seeking && preview == null) { delay(3000); controls = false }
    }
    LaunchedEffect(seekHint, touchTick) { if (seekHint.isNotEmpty()) { delay(700); seekHint = "" } }
    DisposableEffect(activity, controls, pip, locked, state.video, landscape) {
        val bars = WindowCompat.getInsetsController(activity.window, view)
        bars.isAppearanceLightStatusBars = false; bars.isAppearanceLightNavigationBars = false
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if ((!controls || locked || (state.video && landscape)) && !pip) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
        onDispose { bars.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(state.playing, state.video, pip) {
        view.keepScreenOn = state.playing && state.video && !pip
        onDispose { view.keepScreenOn = false }
    }
    fun interact(action: () -> Unit) { touchTick++; action() }
    fun commitSeek(target: Long, mediaId: String = detail.mediaId, sessionId: String = session.sessionId) {
        val connected = player ?: return
        if (locked || pip || !outputEnabled || connection.state.value.error != null || !connection.details.value.seekable ||
            connected.currentMediaItem?.mediaId != mediaId || connection.sessionState.value.sessionId != sessionId) { preview = null; return }
        val clamped = target.coerceIn(0, connection.details.value.duration.coerceAtLeast(0))
        preview = null
        // seekTo leaves playWhenReady untouched: a paused drag must never start playback.
        connected.seekTo(clamped)
        controls = true; touchTick++
    }
    fun seek(delta: Long) {
        val connected = player ?: return
        if (!detail.seekable) { seekHint = "이 파일은 시간 이동을 지원하지 않습니다."; touchTick++; return }
        commitSeek(seekTarget(connected.currentPosition, delta, detail.duration))
        seekHint = if (delta < 0) "−10초" else "+10초"; touchTick++
    }
    // Music can be pulled down to reveal the folder underneath. Video keeps the opaque window
    // its orientation requests need, and a locked or busy screen must not move.
    PlayerDismissFrame(revealable && !state.video && !pip && !locked && menu == null, onDismiss) {
        AndroidView(factory = { PlayerView(it).apply {
            useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setBackgroundColor(android.graphics.Color.BLACK); setShutterBackgroundColor(android.graphics.Color.BLACK)
            onView(this)
        } }, update = { output ->
            val visiblePlayer = if (outputEnabled) player else null
            if (output.player !== visiblePlayer) output.player = visiblePlayer
            output.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
        if (!state.video && !pip) AudioStage(state.title, Modifier.matchParentSize())
        if (pip && detail.buffering) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = Color.White, strokeWidth = 3.dp)
        } else if (pip && state.error != null) {
            Text("재생 오류", Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .8f), RoundedCornerShape(8.dp)).padding(8.dp), color = Color.White)
        }
        if (!pip) {
            PlayerSeekSurface(Modifier.matchParentSize(), identity,
                enabled = detail.seekable && !locked && !exploring && menu == null && state.error == null && outputEnabled,
                tapsEnabled = !locked && state.error == null && menu == null,
                leftEdgePx = leftEdgePx, rightEdgePx = rightEdgePx,
                onStart = { width ->
                    player?.takeIf { it.currentMediaItem?.mediaId == detail.mediaId }?.let {
                        seekHint = ""; touchTick++
                        SeekDrag(detail.mediaId, session.sessionId, it.currentPosition, detail.duration, width)
                    }
                }, onPreview = { preview = it },
                onCommit = { commitSeek(it.target, it.mediaId, it.sessionId) },
                onCancel = { preview = null },
                onTap = { controls = !controls; touchTick++ }, onDoubleTap = { seek(if (it) 10_000 else -10_000) })
            if (state.error != null) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).background(Color.Black.copy(alpha = .86f), RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState()).padding(16.dp).testTag("player-error-panel"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(state.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    Text(state.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
                        PlayerButton("이전", "player-previous", enabled = detail.previous) { interact { player?.seekToPreviousMediaItem() } }
                        PlayerButton("다음", "player-next", enabled = detail.next) { interact { player?.seekToNextMediaItem() } }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
                        if (state.retryable) {
                            PlayerButton("다시 시도", "player-retry", enabled = !audioOnlyLoading) {
                                scope.launch { audioOnlyLoading = true; try { connection.retry() } finally { audioOnlyLoading = false } }
                            }
                        }
                        PlayerButton("목록으로", "player-error-back", action = onBack)
                        PlayerButton("정지", "player-stop", action = onStop)
                    }
                }
            }
            else if (detail.buffering) CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = Color.White)
            if (seekHint.isNotEmpty() && preview == null) Text(seekHint, Modifier.align(Alignment.Center).offset(y = (-80).dp)
                .background(PlayerPanel, RoundedCornerShape(24.dp)).padding(16.dp).testTag("player-seek-hint"), color = PlayerIvory)
            if (state.error == null && locked) {
                PlayerButton("잠금 해제", "player-unlock", Modifier.align(Alignment.TopEnd).safeDrawingPadding()) { locked = false; controls = true; touchTick++ }
            } else if (state.error == null && (controls || preview != null)) {
                // Playback and convenience controls share the bottom safe area.
                // The gradients only shade the edges, leaving the picture unobscured.
                if (preview == null) Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .42f), Color.Transparent)))
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                        .padding(horizontal = 8.dp).heightIn(min = 48.dp)
                        .testTag("player-header"), verticalAlignment = Alignment.CenterVertically) {
                        PlayerIconButton(Symbols.ArrowBackIosNew, "목록으로", "player-back", action = onBack)
                        // Music shows its title on the stage below; repeating it here is noise.
                        if (state.video) Text(state.title, Modifier.weight(1f).padding(horizontal = 4.dp), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = PlayerIvory, fontSize = if (landscape) 14.sp else 16.sp)
                    }
                    if (preview != null) Box(Modifier.align(Alignment.Center)) { PlayerSeekHint(preview!!) }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .48f))))
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        .padding(horizontal = 16.dp).testTag("player-footer")) {
                        key(identity) {
                            PlayerTimeline(player, detail, preview,
                                onStart = { touchTick++ }, onCommit = { commitSeek(it) }, onSeeking = { seeking = it })
                        }
                        if (!detail.seekable && detail.duration > 0) Text("이 파일은 시간 이동을 지원하지 않습니다.", color = PlayerIvory, style = MaterialTheme.typography.bodySmall)
                        val runningTimer = timerLabel(session)
                        if (preview == null && runningTimer.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            PlayerButton(runningTimer, "player-sleep-timer") { menu = "취침 타이머"; touchTick++ }
                        }
                        @Composable fun transport() {
                            PlayerIconButton(PlayerSymbols.previous, "이전 파일", "player-previous", enabled = detail.previous) { interact { player?.seekToPreviousMediaItem() } }
                            PlayerSeekButton(false, detail.seekable) { seek(-10_000) }
                            PlayerIconButton(if (state.playWhenReady) Symbols.PauseFilled else Symbols.PlayArrowFilled,
                                if (state.playWhenReady) "일시정지" else "재생", "player-toggle", enabled = state.visible, primary = true) { interact { connection.toggle() } }
                            PlayerSeekButton(true, detail.seekable) { seek(10_000) }
                            PlayerIconButton(PlayerSymbols.next, "다음 파일", "player-next", enabled = detail.next) { interact { player?.seekToNextMediaItem() } }
                        }
                        // Speed is always followed directly by PiP.
                        @Composable fun leadingTools() {
                            PlayerButton("${detail.speed}×", "player-speed", contentDescription = "재생 속도 ${detail.speed}배") { menu = "재생 옵션"; touchTick++ }
                            if (state.video) {
                                PlayerIconButton(PlayerSymbols.pip, "PiP 작은 창", "player-pip", action = onPip)
                                PlayerIconButton(if (fill) PlayerSymbols.fit else PlayerSymbols.fill, if (fill) "화면 맞춤" else "화면 채움", "player-fill") { interact { connection.options.fill.value = !fill } }
                                PlayerIconButton(PlayerSymbols.rotate, "화면 회전", "player-rotate") { interact(onRotate) }
                            }
                        }
                        @Composable fun trailingTools() {
                            if (state.video) PlayerIconButton(PlayerSymbols.audio, if (detail.audioAvailable) "소리만 듣기" else "오디오 트랙 없음", "player-audio-only",
                                enabled = detail.audioAvailable && !audioOnlyLoading) {
                                scope.launch {
                                    audioOnlyLoading = true
                                    try { if (connection.setAudioOnly(true)) onBack() }
                                    finally { audioOnlyLoading = false }
                                }
                            }
                            PlayerIconButton(PlayerSymbols.lock, "터치 잠금", "player-lock") { locked = true; menu = null }
                            PlayerIconButton(Symbols.Tune, "재생 옵션", "player-options") { menu = "재생 옵션"; touchTick++ }
                        }
                        if (preview == null && landscape && state.video) {
                            // Landscape: convenience tools flank the transport, keeping the picture clear above two short rows.
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { leadingTools() }
                                Row(Modifier.testTag("player-transport"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { transport() }
                                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) { trailingTools() }
                            }
                        } else if (preview == null) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Row(Modifier.horizontalScroll(rememberScrollState()).testTag("player-transport"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { transport() }
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { leadingTools(); trailingTools() }
                        } else Spacer(Modifier.height(48.dp))
                    }
            }
        }
    }
    if (menu != null && !pip) PlayerOptionsDialog(connection, menu!!, { menu = it }, { menu = null; touchTick++ }, onStop)
}

/**
 * Music "now playing" stage: warm colour fields softened behind a large artwork tile and the title.
 * Controls stay in the shared footer; the stage never covers them.
 */
@Composable
private fun AudioStage(title: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(Color(0xFF15110F)).clearAndSetSemantics { }) {
        val soft = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) Modifier.blur(72.dp) else Modifier
        val w = maxWidth; val h = maxHeight
        Box(Modifier.matchParentSize().then(soft).graphicsLayer { alpha = .72f }) {
            Box(Modifier.offset(x = w * .05f, y = h * .12f).size(w * .75f).background(Color(0xFFDB7B5B), CircleShape))
            Box(Modifier.offset(x = w * .4f, y = h * .3f).size(w * .7f).background(Color(0xFF5A4C7A), CircleShape))
            Box(Modifier.offset(x = w * .2f, y = h * .55f).size(w * .6f).background(Color(0xFFC9A46A), CircleShape))
        }
        Box(Modifier.matchParentSize().background(Color(0x7315110F)))
        val landscape = maxWidth > maxHeight
        val art = (if (landscape) maxHeight * .42f else maxWidth * .68f).coerceAtMost(320.dp)
        Column(Modifier.align(Alignment.TopCenter).padding(top = if (landscape) 56.dp else maxHeight * .14f).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(art).shadow(24.dp, RoundedCornerShape(32.dp)).background(
                Brush.linearGradient(listOf(Color(0xFFE9A27E), Color(0xFFB85F4E), Color(0xFF4A3A62))), RoundedCornerShape(32.dp)),
                contentAlignment = Alignment.Center) {
                Icon(Symbols.GraphicEq, null, Modifier.size(art * .4f), tint = Color(0xFFFFF3E6))
            }
            Spacer(Modifier.height(24.dp))
            Text(title, color = PlayerIvory, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun PlayerButton(label: String, tag: String, modifier: Modifier = Modifier, enabled: Boolean = true, contentDescription: String? = null, action: () -> Unit) {
    val accessibility = if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription }
    TextButton(onClick = action, enabled = enabled, modifier = modifier.then(accessibility).heightIn(min = 48.dp).testTag(tag), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = PlayerApricot, disabledContentColor = Color.Gray)) {
        Text(label, maxLines = 1)
    }
}
