package com.quickfiles.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quickfiles.app.playback.PlaybackState
import com.quickfiles.app.ui.icons.Symbols
import com.quickfiles.app.ui.theme.LocalGlass
import com.quickfiles.app.ui.theme.backdropBlur
import com.quickfiles.app.ui.theme.glass

/** Names for the two remembered locations; A/B labels are never shown. */
internal data class DockModel(val current: String, val other: String, val atHome: Boolean, val canSearch: Boolean)

internal class DockActions(val home: () -> Unit, val switchPanel: () -> Unit, val swap: () -> Unit, val tasks: () -> Unit,
    val search: () -> Unit, val player: () -> Unit, val toggle: () -> Unit, val stop: () -> Unit)

/** Floating glass dock with a separate circular search button. [compact] hides labels while content scrolls. */
@Composable
internal fun ExpandedDock(dock: DockModel, actions: DockActions, running: Int, progress: Float?, compact: Boolean,
    menu: @Composable (dismiss: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val glass = LocalGlass.current
    // Scrolling eases the dock between its full and slim heights instead of stepping.
    val height by animateDpAsState(if (compact) 54.dp else 64.dp, MaterialMotion.spatial(), label = "dock-height")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    // On a phone-width dock the labels would leave the location names a few letters, so the buttons go icon-only there.
    val iconOnly = compact || maxWidth - (if (dock.canSearch) height + 10.dp else 0.dp) < 340.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        Row(Modifier.weight(1f).height(height).glass(glass, behind = Modifier.backdropBlur()).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            DockButton(if (dock.atHome) Symbols.HardDriveFilled else Symbols.HardDrive, "저장소", actions.home, iconOnly, dock.atHome, Modifier.testTag("storage-home-button"))
            LocationSwitch(dock, actions, compact, Modifier.weight(1f))
            DockButton(Symbols.SwapVert, "작업", actions.tasks, iconOnly, false,
                Modifier.semantics { if (running > 0) contentDescription = "작업 진행 중 ${running}개" }, ring = if (running > 0) progress ?: 0f else null)
            Box {
                DockButton(Symbols.MoreHoriz, "더보기", { open = true }, iconOnly, false, Modifier.testTag("explorer-menu"))
                DropdownMenu(open, { open = false }) { menu { open = false } }
            }
        }
        if (dock.canSearch) GlassIconButton(Symbols.Search, "검색", actions.search, size = height, iconSize = 26.dp)
    }
    }
}

@Composable
private fun DockButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, iconOnly: Boolean, selected: Boolean,
    modifier: Modifier = Modifier, ring: Float? = null) {
    if (iconOnly) {
        Box(modifier.size(48.dp).clip(CircleShape).then(if (selected) Modifier.background(LocalGlass.current.pill, CircleShape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            if (ring != null) ProgressRing(ring)
            Icon(icon, null, Modifier.size(if (ring != null) 18.dp else 22.dp), tint = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current)
        }
    } else BarItem(icon, label, onClick, modifier, selected = selected, badge = if (ring != null) {{ ProgressRing(ring, Modifier.align(Alignment.Center)) }} else null)
}

/** Current (●) and other (○) location names; the body opens the switcher, the ⇄ button swaps directly. */
@Composable
private fun LocationSwitch(dock: DockModel, actions: DockActions, compact: Boolean, modifier: Modifier) {
    Row(modifier.height(if (compact) 46.dp else 52.dp).clip(CircleShape).background(LocalGlass.current.pill, CircleShape)
        .clickable(role = Role.Button, onClickLabel = "폴더 전환 열기", onClick = actions.switchPanel).testTag("folder-switch")
        .padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape)); Spacer(Modifier.width(5.dp))
                Text(dock.current, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // At large font sizes the second name no longer fits the capsule, so only the current one stays.
            if (!compact && LocalDensity.current.fontScale < 1.5f) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.outline, CircleShape).padding(1.dp).background(LocalGlass.current.pill, CircleShape)); Spacer(Modifier.width(5.dp))
                Text(dock.other, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.size(if (compact) 38.dp else 40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
            .clickable(role = Role.Button, onClickLabel = "다른 위치로 전환", onClick = actions.swap)
            .semantics { contentDescription = "다른 위치로 전환: ${dock.other}" }, contentAlignment = Alignment.Center) {
            Icon(Symbols.SwapHoriz, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** Playback capsule shown above the expanded dock. */
@Composable
internal fun MiniPlayer(player: PlaybackState, actions: DockActions, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(56.dp).glass(LocalGlass.current, behind = Modifier.backdropBlur()).padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayerBody(player, actions, Modifier.weight(1f))
        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary, CircleShape)
            .clickable(role = Role.Button, onClick = actions.toggle).semantics { contentDescription = if (player.playing) "일시정지" else "재생" }, contentAlignment = Alignment.Center) {
            Icon(if (player.playing) Symbols.PauseFilled else Symbols.PlayArrowFilled, null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        ToolIcon(Symbols.Close, "재생 정지", actions.stop)
    }
}

@Composable
private fun PlayerBody(player: PlaybackState, actions: DockActions, modifier: Modifier) {
    Row(modifier.fillMaxHeight().clip(CircleShape).clickable(onClickLabel = "재생 화면 열기", onClick = actions.player).testTag("mini-player-open"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Symbols.MusicNote, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(player.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(player.error ?: if (player.playing) "재생 중" else "일시정지", style = MaterialTheme.typography.labelSmall,
                color = if (player.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Collapsed while scrolling: swap button, the playing item, and search on one 54dp line. */
@Composable
internal fun CompactDock(dock: DockModel, actions: DockActions, player: PlaybackState) {
    val glass = LocalGlass.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(Symbols.SwapHoriz, "다른 위치로 전환: ${dock.other}", actions.swap, size = 54.dp, iconSize = 24.dp)
        Row(Modifier.weight(1f).height(54.dp).glass(glass, behind = Modifier.backdropBlur()).padding(start = 7.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerBody(player, actions, Modifier.weight(1f))
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary, CircleShape)
                .clickable(role = Role.Button, onClick = actions.toggle).semantics { contentDescription = if (player.playing) "일시정지" else "재생" }, contentAlignment = Alignment.Center) {
                Icon(if (player.playing) Symbols.PauseFilled else Symbols.PlayArrowFilled, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        if (dock.canSearch) GlassIconButton(Symbols.Search, "검색", actions.search, size = 54.dp, iconSize = 24.dp)
    }
}
