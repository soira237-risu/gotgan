package com.quickfiles.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickfiles.app.playback.PlaybackConnection
import com.quickfiles.app.playback.clockText
import com.quickfiles.app.ui.icons.Symbols
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Quick controls that rise from the mini player: position, transport and stop without leaving the folder.
 * The full player stays one tap away for options, tracks and the sleep timer. [onStop] runs in the caller's scope,
 * because this sheet's own scope ends with it.
 */
@Composable
internal fun QuickPlayerSheet(playback: PlaybackConnection, onDismiss: () -> Unit, onOpenPlayer: () -> Unit, onStop: () -> Unit) {
    WorkspaceSheet("재생 중", onDismissRequest = onDismiss) {
        val state by playback.state.collectAsStateWithLifecycle()
        val detail by playback.details.collectAsStateWithLifecycle()
        val dismissSheet = LocalSheetDismiss.current
        val scope = rememberCoroutineScope()
        // Playback ending elsewhere (notification, timer, end of list) leaves nothing to control, so the sheet leaves too.
        LaunchedEffect(state.visible) { if (!state.visible) dismissSheet(onDismiss) }
        var position by remember(detail.mediaId) { mutableLongStateOf(playback.position()) }
        var dragging by remember(detail.mediaId) { mutableStateOf<Float?>(null) }
        LaunchedEffect(detail.mediaId) { while (true) { position = playback.position(); delay(250) } }
        val duration = detail.duration.coerceAtLeast(0)
        val shown = dragging?.toLong() ?: if (duration > 0) position.coerceAtMost(duration) else position

        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).testTag("quick-player")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Symbols.MusicNote, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f)) {
                    Text(state.title, Modifier.testTag("quick-player-title"), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(state.error ?: when { detail.buffering -> "불러오는 중"; state.playing -> "재생 중"; else -> "일시정지" },
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.error != null && state.retryable) TextButton({ scope.launch { playback.retry() } }) { Text("다시 시도") }
            }

            Spacer(Modifier.height(12.dp))
            Slider(value = shown.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = detail.seekable,
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { playback.seekTo(it.toLong()); position = it.toLong() }; dragging = null },
                modifier = Modifier.fillMaxWidth().testTag("quick-player-timeline").semantics {
                    contentDescription = "재생 위치"; stateDescription = "${clockText(shown)} / ${clockText(duration)}"
                })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clockText(shown), Modifier.testTag("quick-player-position"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (duration > 0) clockText(duration) else "--:--", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                QuickButton(Symbols.SkipPreviousFilled, "이전 파일", "quick-player-previous", detail.previous) { playback.previous() }
                QuickButton(Symbols.Replay10, "10초 뒤로", "quick-player-seek-back", detail.seekable) { playback.seekBy(-10_000); position = playback.position() }
                FilledIconButton({ playback.toggle() }, Modifier.size(64.dp).testTag("quick-player-toggle")
                    .semantics { contentDescription = if (state.playWhenReady) "일시정지" else "재생" }, shape = CircleShape) {
                    Icon(if (state.playWhenReady) Symbols.PauseFilled else Symbols.PlayArrowFilled, null, Modifier.size(32.dp))
                }
                QuickButton(Symbols.Forward10, "10초 앞으로", "quick-player-seek-forward", detail.seekable) { playback.seekBy(10_000); position = playback.position() }
                QuickButton(Symbols.SkipNextFilled, "다음 파일", "quick-player-next", detail.next) { playback.next() }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ dismissSheet { onDismiss(); onStop() } }, Modifier.weight(1f).testTag("quick-player-stop")) {
                    Icon(Symbols.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("재생 정지")
                }
                Button({ dismissSheet { onDismiss(); onOpenPlayer() } }, Modifier.weight(1f).testTag("quick-player-open-full")) {
                    Icon(Symbols.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("전체 화면")
                }
            }
        }
    }
}

@Composable
private fun QuickButton(icon: ImageVector, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(48.dp).testTag(tag).semantics { contentDescription = label }, enabled = enabled) {
        Icon(icon, null, Modifier.size(28.dp))
    }
}
