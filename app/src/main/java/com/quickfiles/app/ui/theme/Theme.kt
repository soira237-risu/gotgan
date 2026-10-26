package com.quickfiles.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickfiles.app.data.*

/** F / Keepsake: ivory surfaces, clay accents, and warm ink. */
internal fun keepsakeColors(dark: Boolean): ColorScheme {
    val bg = Color(if (dark) 0xFF1B1714 else 0xFFFAF9F5)
    val surface = Color(if (dark) 0xFF2B231E else 0xFFF0E9DF)
    val ink = Color(if (dark) 0xFFF3E9DE else 0xFF30251F)
    val muted = Color(if (dark) 0xFFCEBAAB else 0xFF68584D)
    val border = Color(if (dark) 0xFF514136 else 0xFFDBCFC3)
    // The icon's #D97757 is decorative. Text/buttons use deeper clay for contrast.
    val clay = Color(if (dark) 0xFFF0A889 else 0xFF93452E)
    val onClay = Color(if (dark) 0xFF3E2015 else 0xFFFFFFFF)
    val claySurface = Color(if (dark) 0xFF603622 else 0xFFF6DDCD)
    val onClaySurface = Color(if (dark) 0xFFFFDAC6 else 0xFF653B2B)
    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = clay, onPrimary = onClay, primaryContainer = claySurface, onPrimaryContainer = onClaySurface,
        secondary = clay, onSecondary = onClay, secondaryContainer = claySurface, onSecondaryContainer = onClaySurface,
        tertiary = muted, onTertiary = bg, tertiaryContainer = surface, onTertiaryContainer = ink,
        background = bg, onBackground = ink, surface = bg, onSurface = ink,
        surfaceVariant = surface, onSurfaceVariant = muted, surfaceTint = Color.Transparent,
        outline = Color(if (dark) 0xFFA38C7B else 0xFF8B776B), outlineVariant = border,
        inverseSurface = ink, inverseOnSurface = bg, inversePrimary = Color(if (dark) 0xFF93452E else 0xFFF0A889),
        surfaceBright = Color(if (dark) 0xFF413329 else 0xFFFFFDF9), surfaceDim = surface,
        surfaceContainerLowest = Color(if (dark) 0xFF15110F else 0xFFFFFDF9),
        surfaceContainerLow = Color(if (dark) 0xFF231D19 else 0xFFF4EFE8),
        surfaceContainer = surface, surfaceContainerHigh = Color(if (dark) 0xFF352A23 else 0xFFEBE1D6),
        surfaceContainerHighest = Color(if (dark) 0xFF413329 else 0xFFE4D6C8),
        error = Color(if (dark) 0xFFFFB4AB else 0xFFB3261E), onError = Color(if (dark) 0xFF601410 else 0xFFFFFFFF),
        errorContainer = Color(if (dark) 0xFF5C2723 else 0xFFFFE1DA),
        onErrorContainer = Color(if (dark) 0xFFFFDAD4 else 0xFF72221B),
    )
}
/** OLED black: dark surfaces become true black so browsing and media screens read as one canvas. */
internal fun oledColors(colors: ColorScheme): ColorScheme = colors.copy(
    background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF121212), surfaceContainer = Color(0xFF1C1C1E), surfaceDim = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E), surfaceContainerHighest = Color(0xFF3A3A3C), surfaceBright = Color(0xFF3A3A3C),
    surfaceVariant = Color(0xFF1C1C1E), tertiaryContainer = Color(0xFF1C1C1E),
)

private val AppTypography = Typography(
    // Large collapsing titles (iOS-style 34pt) on browse screens.
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    // Dock and glass toolbar labels.
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

/** Six radii: xs 8 badges, sm 12 thumbnails, md 16 tiles/rows, lg 24 cards/panels, xl 32 sheets; capsules use CircleShape. */
internal val AppShapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp))

@Composable
fun GotganTheme(darkOverride: Boolean? = null, manageSystemBars: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { SettingsStore(context) }
    val settings by store.settings.collectAsStateWithLifecycle(ExplorerSettings())
    val dark = darkOverride ?: when (settings.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val view = LocalView.current
    SideEffect {
        val activity = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        if (manageSystemBars) activity?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val oled = dark && settings.oledBlack
    val colors = remember(dark, oled, settings.colorTheme) { appColors(dark, settings.colorTheme).let { if (oled) oledColors(it) else it } }
    val glass = remember(colors, dark, oled) { if (!dark) lightGlass(colors.surface) else if (oled) MediaGlass.copy(regular = .76f, thick = .9f) else darkGlass(colors.surfaceContainer) }
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes,
        content = { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, LocalGlass provides glass) { content() } })
}
