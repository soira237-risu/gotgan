package com.quickfiles.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Section caption above a grouped card, in the grouped-list style used by settings and panels. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Opaque grouped card (lg 24). Rows inside are separated by [GroupDivider]. */
@Composable
internal fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large)
        .padding(horizontal = 16.dp, vertical = 4.dp), content = content)
}

@Composable
internal fun GroupDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))

/** Capsule action; [primary] uses the accent fill, otherwise a tonal fill. */
@Composable
internal fun PillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false, icon: ImageVector? = null) {
    val content: @Composable RowScope.() -> Unit = {
        icon?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(label, maxLines = 1)
    }
    if (primary) Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = CircleShape, content = content)
    else FilledTonalButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = CircleShape, content = content)
}

/** Small status capsule; [tone] picks the meaning colour. */
@Composable
internal fun StatusChip(label: String, tone: StatusTone) {
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when (tone) {
        StatusTone.ACTIVE -> colors.primaryContainer to colors.onPrimaryContainer
        StatusTone.DONE -> Color(0xFF2E6B40).copy(alpha = .16f) to if (colors.background.red < .5f) Color(0xFFA8DAB5) else Color(0xFF1E5A30)
        StatusTone.PROBLEM -> colors.errorContainer to colors.onErrorContainer
        StatusTone.IDLE -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    Box(Modifier.background(background, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = foreground)
    }
}

internal enum class StatusTone { ACTIVE, DONE, PROBLEM, IDLE }
