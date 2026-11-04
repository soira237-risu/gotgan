package com.quickfiles.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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

/**
 * Roles Material's [ColorScheme] has no slot for. Read them here instead of guessing from a colour's brightness.
 * [lightPaper] and [darkPaper] are the active palette's own light and dark backgrounds with their ink, for text papers.
 */
@Immutable
data class ThemeExtras(
    val dark: Boolean,
    val success: Color, val successContainer: Color, val onSuccessContainer: Color,
    val lightPaper: Pair<Color, Color>, val darkPaper: Pair<Color, Color>,
)

internal fun themeExtras(dark: Boolean, light: ColorScheme, darkScheme: ColorScheme) = ThemeExtras(
    dark = dark,
    success = Color(if (dark) 0xFF8ECB9C else 0xFF2E6B40),
    successContainer = Color(if (dark) 0xFF1F3A27 else 0xFFDCEEDF),
    onSuccessContainer = Color(if (dark) 0xFFA8DAB5 else 0xFF1E5A30),
    lightPaper = light.background to light.onSurface,
    darkPaper = darkScheme.background to darkScheme.onSurface,
)

val LocalThemeExtras = staticCompositionLocalOf { themeExtras(false, keepsakeColors(false), keepsakeColors(true)) }

/** Wallpaper colours on Android 12+, adjusted like the fixed palettes; null where the platform has none. */
internal fun dynamicColors(context: android.content.Context, dark: Boolean): ColorScheme? =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) null
    else (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).copy(surfaceTint = Color.Transparent)

internal fun dynamicColorAvailable() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** The scheme for a palette choice; [ColorTheme.DYNAMIC] falls back to Keepsake where wallpaper colours are unavailable. */
internal fun themeColors(context: android.content.Context, dark: Boolean, theme: ColorTheme): ColorScheme =
    (if (theme == ColorTheme.DYNAMIC) dynamicColors(context, dark) else null) ?: appColors(dark, theme)

/**
 * Holds this window's frames until [ready], for at most [limitMillis]. The first frame then already uses the saved palette
 * instead of the defaults shown while settings load. Returning false from a pre-draw listener skips that frame only.
 */
@Composable
private fun HoldFramesUntil(ready: Boolean, limitMillis: Long = 600) {
    val view = LocalView.current
    val current by rememberUpdatedState(ready)
    DisposableEffect(view) {
        val deadline = android.os.SystemClock.uptimeMillis() + limitMillis
        val observer = view.viewTreeObserver
        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!current && android.os.SystemClock.uptimeMillis() < deadline) return false
                view.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                return true
            }
        }
        observer.addOnPreDrawListener(listener)
        onDispose { view.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener) }
    }
}

@Composable
fun GotganTheme(darkOverride: Boolean? = null, manageSystemBars: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { SettingsStore(context) }
    val loaded by store.settings.collectAsStateWithLifecycle<ExplorerSettings?>(null)
    HoldFramesUntil(loaded != null)
    val settings = loaded ?: ExplorerSettings()
    val systemDark = isSystemInDarkTheme()
    val dark = darkOverride ?: when (settings.theme) { ThemeMode.SYSTEM -> systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val oled = dark && settings.oledBlack
    // Wallpaper colours change with the configuration, which also recomposes this.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val colors = remember(dark, oled, settings.colorTheme, configuration) { themeColors(context, dark, settings.colorTheme).let { if (oled) oledColors(it) else it } }
    val extras = remember(dark, settings.colorTheme, configuration) {
        themeExtras(dark, themeColors(context, false, settings.colorTheme), themeColors(context, true, settings.colorTheme))
    }
    val view = LocalView.current
    SideEffect {
        val activity = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        if (manageSystemBars) activity?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
            // The window shows through during resizes and the recents snapshot; match the palette, not the launch theme.
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(colors.background.toArgb()))
        }
    }
    val glass = remember(colors, dark, oled) { if (!dark) lightGlass(colors.surface) else if (oled) MediaGlass.copy(regular = .76f, thick = .9f) else darkGlass(colors.surfaceContainer) }
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes,
        content = { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, LocalGlass provides glass, LocalThemeExtras provides extras) { content() } })
}
