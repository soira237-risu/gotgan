package com.quickfiles.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.quickfiles.app.ui.theme.GlassColors
import com.quickfiles.app.ui.theme.GlassLevel
import com.quickfiles.app.ui.theme.LocalGlass
import com.quickfiles.app.ui.theme.backdropBlur
import com.quickfiles.app.ui.theme.glass

/** A translucent container for floating controls; content keeps the caller's layout. */
@Composable
internal fun GlassSurface(modifier: Modifier = Modifier, shape: Shape = CircleShape, level: GlassLevel = GlassLevel.REGULAR,
    colors: GlassColors = LocalGlass.current, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.glass(colors, shape, level, behind = Modifier.backdropBlur()), content = content)
}

/** Circular glass button with a 48dp-or-larger touch target. */
@Composable
internal fun GlassIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp,
    iconSize: Dp = 22.dp, enabled: Boolean = true, colors: GlassColors = LocalGlass.current, tint: Color = LocalContentColor.current) {
    Box(modifier.size(size).glass(colors, behind = Modifier.backdropBlur()).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(iconSize), tint = if (enabled) tint else tint.copy(alpha = .38f))
    }
}

/** Icon-over-label entry inside a glass bar. Labels hide at large font scales. */
@Composable
internal fun BarItem(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false,
    enabled: Boolean = true, tint: Color = LocalContentColor.current, pill: Color = LocalGlass.current.pill, badge: @Composable (BoxScope.() -> Unit)? = null) {
    val showLabel = LocalDensity.current.fontScale < 1.5f
    val color = when { !enabled -> tint.copy(alpha = .38f); selected -> MaterialTheme.colorScheme.primary; else -> tint }
    Column(modifier.heightIn(min = 54.dp).widthIn(min = 54.dp).clip(CircleShape)
        .then(if (selected) Modifier.background(pill, CircleShape) else Modifier)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { this.selected = selected; if (!showLabel) contentDescription = label }
        .padding(horizontal = 6.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = color)
            badge?.invoke(this)
        }
        if (showLabel) Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Determinate ring around a bar icon; null progress draws only the track. */
@Composable
internal fun ProgressRing(progress: Float?, modifier: Modifier = Modifier, track: Color = MaterialTheme.colorScheme.outlineVariant,
    indicator: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.size(30.dp)) {
        val width = 2.5.dp.toPx()
        val inset = width / 2
        val arcSize = Size(size.width - width, size.height - width)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(width))
        if (progress != null) drawArc(indicator, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(width, cap = StrokeCap.Round))
    }
}
