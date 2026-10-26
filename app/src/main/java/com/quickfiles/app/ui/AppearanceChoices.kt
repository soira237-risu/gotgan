package com.quickfiles.app.ui

import com.quickfiles.app.ui.icons.Symbols
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.quickfiles.app.data.ColorTheme
import com.quickfiles.app.data.ThemeMode
import com.quickfiles.app.ui.theme.appColors

@Composable
internal fun ColorThemeChoices(selected: ColorTheme, mode: ThemeMode, choose: (ColorTheme) -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val columns = if (maxWidth >= 320.dp && LocalDensity.current.fontScale < 1.5f) 2 else 1
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorTheme.entries.chunked(columns).forEach { themes ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    themes.forEach { theme ->
                        val preview = appColors(dark, theme)
                        val active = selected == theme
                        Surface(shape = MaterialTheme.shapes.medium, color = preview.surfaceContainerLow,
                            border = BorderStroke(if (active) 2.dp else 1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth().testTag("color-theme-${theme.name}")
                                .selectable(active, role = Role.RadioButton, onClick = { choose(theme) })
                                .heightIn(min = 56.dp).padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(24.dp).background(preview.primary, CircleShape), contentAlignment = Alignment.Center) {
                                    if (active) Icon(Symbols.Check, null, Modifier.size(18.dp), tint = preview.onPrimary)
                                }
                                Text(theme.label, Modifier.weight(1f), color = preview.onSurface, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (themes.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
