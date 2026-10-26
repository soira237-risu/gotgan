package com.quickfiles.app.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import com.quickfiles.app.ui.icons.Symbols
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.delay

internal val PlayerIvory = Color(0xFFFAF9F5)
internal val PlayerApricot = Color(0xFFF0A889)
/** Neutral media glass: palette tints never shift the picture's colors. */
internal val PlayerPanel = Color(0xB81C1C1E)

internal object PlayerSymbols {
    val previous get() = Symbols.SkipPreviousFilled
    val next get() = Symbols.SkipNextFilled
    val rotate get() = Symbols.ScreenRotation
    val lock get() = Symbols.Lock
    val pip get() = Symbols.PictureInPictureAlt
    val fill get() = Symbols.ZoomIn
    val fit get() = Symbols.FitScreen
    val audio get() = Symbols.Headphones
    val seekBack get() = Symbols.Replay10
    val seekForward get() = Symbols.Forward10
}

@Composable
internal fun PlayerIconButton(icon: ImageVector, label: String, tag: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, primary: Boolean = false, action: () -> Unit) {
    // The primary play button is a translucent white glass disc so it never outshines the picture.
    FilledIconButton(onClick = action, enabled = enabled,
        modifier = modifier.size(if (primary) 64.dp else 48.dp).testTag(tag).semantics { contentDescription = label }
            .then(if (primary) Modifier.border(1.dp, Color.White.copy(alpha = .28f), CircleShape) else Modifier),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (primary) Color.White.copy(alpha = .18f) else Color.Transparent,
            contentColor = PlayerIvory,
            disabledContainerColor = Color.Transparent, disabledContentColor = Color(0xFF8B8077))) {
        Icon(icon, null, Modifier.size(if (primary) 34.dp else 24.dp))
    }
}

@Composable
internal fun PlayerSeekButton(forward: Boolean, enabled: Boolean, action: () -> Unit) {
    FilledIconButton(onClick = action, enabled = enabled, modifier = Modifier.size(48.dp)
        .testTag(if (forward) "player-seek-forward" else "player-seek-back")
        .semantics { contentDescription = if (forward) "10초 앞으로" else "10초 뒤로" },
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Transparent, contentColor = PlayerIvory,
            disabledContainerColor = Color.Transparent, disabledContentColor = Color(0xFF8B8077))) {
        Icon(if (forward) PlayerSymbols.seekForward else PlayerSymbols.seekBack, null, Modifier.size(30.dp))
    }
}

@Composable
internal fun PlayerSeekHint(preview: SeekPreview, modifier: Modifier = Modifier) {
    val delta = (preview.target - preview.origin) / 1000
    Column(modifier.background(PlayerPanel, RoundedCornerShape(24.dp)).border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(24.dp)).padding(horizontal = 24.dp, vertical = 12.dp)
        .testTag("player-seek-preview"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${if (delta < 0) "−" else "+"}${kotlin.math.abs(delta)}초", color = PlayerApricot, fontSize = 28.sp)
        Text(clockText(preview.target), color = PlayerIvory, fontSize = 16.sp, modifier = Modifier.testTag("player-seek-target"))
        Text(if (preview.fine) "정밀 탐색 · 1/5" else "아래로 내리면 정밀 탐색", color = PlayerIvory,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("player-seek-mode"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerTimeline(player: Player?, detail: PlaybackDetails, preview: SeekPreview?,
    onStart: () -> Unit, onCommit: (Long) -> Unit, onSeeking: (Boolean) -> Unit) {
    var position by remember(detail.mediaId) { mutableLongStateOf(0) }
    var dragging by remember(detail.mediaId) { mutableStateOf<Float?>(null) }
    val endSeeking by rememberUpdatedState(onSeeking)
    DisposableEffect(detail.mediaId) { onDispose { endSeeking(false) } }
    LaunchedEffect(player, detail.mediaId) { while (true) { position = player?.currentPosition?.coerceAtLeast(0) ?: 0; delay(250) } }
    val shown = preview?.target ?: dragging?.toLong() ?: position
    val timeline: @Composable (Modifier) -> Unit = { modifier ->
        Slider(value = shown.toFloat().coerceIn(0f, detail.duration.coerceAtLeast(1).toFloat()),
            valueRange = 0f..detail.duration.coerceAtLeast(1).toFloat(), enabled = detail.seekable && preview == null,
            onValueChange = {
                if (dragging == null) onStart()
                dragging = it; onSeeking(true)
            },
            onValueChangeFinished = {
                dragging?.let { onCommit(it.toLong()); position = it.toLong() }
                dragging = null; onSeeking(false)
            },
            thumb = { Box(Modifier.size(if (dragging != null) 16.dp else 14.dp).background(if (detail.seekable) Color.White else Color.Gray, CircleShape)) },
            track = { slider ->
                Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .22f), CircleShape)) {
                    Box(Modifier.fillMaxWidth((slider.value / detail.duration.coerceAtLeast(1)).coerceIn(0f, 1f))
                        .fillMaxHeight().background(if (detail.seekable) PlayerApricot else Color.Gray, CircleShape))
                }
            },
            modifier = modifier.heightIn(min = 48.dp).testTag("player-timeline").semantics {
                contentDescription = "재생 위치"; stateDescription = "${clockText(shown)} / ${clockText(detail.duration)}"
            })
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Long times and large system fonts get a separate row instead of crushing the slider.
        val stacked = maxWidth < 400.dp && (LocalDensity.current.fontScale > 1.3f || detail.duration >= 3_600_000)
        if (stacked) Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clockText(shown), Modifier.testTag("player-position"), color = PlayerIvory, style = MaterialTheme.typography.bodySmall)
                Text(clockText(detail.duration), color = PlayerIvory, style = MaterialTheme.typography.bodySmall)
            }
            timeline(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(clockText(shown), Modifier.testTag("player-position"), color = PlayerIvory, style = MaterialTheme.typography.bodySmall)
            timeline(Modifier.weight(1f))
            Text(clockText(detail.duration), color = PlayerIvory, style = MaterialTheme.typography.bodySmall)
        }
    }
}
