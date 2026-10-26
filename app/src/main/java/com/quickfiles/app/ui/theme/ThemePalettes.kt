package com.quickfiles.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.quickfiles.app.data.ColorTheme

private data class Palette(
    val background: Long, val low: Long, val surface: Long, val high: Long, val highest: Long,
    val ink: Long, val muted: Long, val outline: Long, val border: Long,
    val accent: Long, val onAccent: Long, val selection: Long, val onSelection: Long,
)

private fun palette(theme: ColorTheme, dark: Boolean): Palette = when (theme) {
    ColorTheme.BLUE -> if (dark) Palette(
        background = 0xFF151A22, low = 0xFF1C2430, surface = 0xFF242F3E,
        high = 0xFF2B3748, highest = 0xFF303D50, ink = 0xFFF0F4FA,
        muted = 0xFFC1CCDC, outline = 0xFF93A3BA, border = 0xFF47566B,
        accent = 0xFFADC9F5, onAccent = 0xFF132E50, selection = 0xFF28405F,
        onSelection = 0xFFD8E7FF,
    ) else Palette(
        background = 0xFFF8FAFD, low = 0xFFF0F5FA, surface = 0xFFEAF1F8,
        high = 0xFFE5EDF5, highest = 0xFFDFE9F3, ink = 0xFF202D3C,
        muted = 0xFF536274, outline = 0xFF738397, border = 0xFFCBD6E2,
        accent = 0xFF285B91, onAccent = 0xFFFFFFFF, selection = 0xFFDEEBFA,
        onSelection = 0xFF203E65,
    )
    ColorTheme.SAGE -> if (dark) Palette(
        background = 0xFF161B17, low = 0xFF1D261E, surface = 0xFF253027,
        high = 0xFF2C382E, highest = 0xFF344236, ink = 0xFFEFF5ED,
        muted = 0xFFC3CFC1, outline = 0xFF93A38E, border = 0xFF4C5C49,
        accent = 0xFFB1D7AC, onAccent = 0xFF1D3A20, selection = 0xFF304B32,
        onSelection = 0xFFDDF1D6,
    ) else Palette(
        background = 0xFFF8FAF7, low = 0xFFF0F5EF, surface = 0xFFEAF1E8,
        high = 0xFFE5EDE3, highest = 0xFFDFE9DC, ink = 0xFF263229,
        muted = 0xFF566653, outline = 0xFF778773, border = 0xFFCBD8C7,
        accent = 0xFF356342, onAccent = 0xFFFFFFFF, selection = 0xFFE0EEDC,
        onSelection = 0xFF2D4F32,
    )
    ColorTheme.LAVENDER -> if (dark) Palette(
        background = 0xFF1B1721, low = 0xFF251E2D, surface = 0xFF302738,
        high = 0xFF393044, highest = 0xFF43394F, ink = 0xFFF6EFFA,
        muted = 0xFFD0C2DC, outline = 0xFFA596B3, border = 0xFF5C4E69,
        accent = 0xFFD3BAF2, onAccent = 0xFF36204D, selection = 0xFF4B355F,
        onSelection = 0xFFEFDFFF,
    ) else Palette(
        background = 0xFFFBF9FD, low = 0xFFF5F0F9, surface = 0xFFF0EAF6,
        high = 0xFFEBE4F2, highest = 0xFFE6DEEE, ink = 0xFF302839,
        muted = 0xFF665A72, outline = 0xFF887991, border = 0xFFD8CDDD,
        accent = 0xFF66468C, onAccent = 0xFFFFFFFF, selection = 0xFFEBDFF9,
        onSelection = 0xFF483061,
    )
    ColorTheme.ROSE -> if (dark) Palette(
        background = 0xFF21171B, low = 0xFF2D1D24, surface = 0xFF38262F,
        high = 0xFF43303A, highest = 0xFF4D3943, ink = 0xFFFBEFF3,
        muted = 0xFFDEC3CF, outline = 0xFFB595A5, border = 0xFF6A4C5B,
        accent = 0xFFF1B6CE, onAccent = 0xFF4C2033, selection = 0xFF603346,
        onSelection = 0xFFFFDCE9,
    ) else Palette(
        background = 0xFFFDF9FA, low = 0xFFF9F0F3, surface = 0xFFF5E9EE,
        high = 0xFFF0E3E9, highest = 0xFFECDDE4, ink = 0xFF3B2830,
        muted = 0xFF745B66, outline = 0xFF947B87, border = 0xFFDFCCD5,
        accent = 0xFF8B3F5D, onAccent = 0xFFFFFFFF, selection = 0xFFF6DEEA,
        onSelection = 0xFF642A43,
    )
    ColorTheme.MONOCHROME -> if (dark) Palette(
        background = 0xFF181818, low = 0xFF222222, surface = 0xFF2C2C2C,
        high = 0xFF353535, highest = 0xFF404040, ink = 0xFFF5F5F5,
        muted = 0xFFCDCDCD, outline = 0xFF9C9C9C, border = 0xFF545454,
        accent = 0xFFE0E0E0, onAccent = 0xFF252525, selection = 0xFF424242,
        onSelection = 0xFFF5F5F5,
    ) else Palette(
        background = 0xFFFAFAFA, low = 0xFFF3F3F3, surface = 0xFFEDEDED,
        high = 0xFFE8E8E8, highest = 0xFFE2E2E2, ink = 0xFF252525,
        muted = 0xFF606060, outline = 0xFF7C7C7C, border = 0xFFD1D1D1,
        accent = 0xFF343434, onAccent = 0xFFFFFFFF, selection = 0xFFE4E4E4,
        onSelection = 0xFF303030,
    )
    ColorTheme.TERRACOTTA -> error("Terracotta uses the original Keepsake palette")
}

/** App surfaces and controls follow the user's palette; error roles retain their meaning. */
internal fun appColors(dark: Boolean, theme: ColorTheme): ColorScheme {
    val original = keepsakeColors(dark)
    if (theme == ColorTheme.TERRACOTTA) return original
    val p = palette(theme, dark)
    return original.copy(
        primary = Color(p.accent), onPrimary = Color(p.onAccent),
        primaryContainer = Color(p.selection), onPrimaryContainer = Color(p.onSelection),
        secondary = Color(p.accent), onSecondary = Color(p.onAccent),
        secondaryContainer = Color(p.selection), onSecondaryContainer = Color(p.onSelection),
        tertiary = Color(p.muted), onTertiary = Color(p.background),
        tertiaryContainer = Color(p.surface), onTertiaryContainer = Color(p.ink),
        background = Color(p.background), onBackground = Color(p.ink),
        surface = Color(p.background), onSurface = Color(p.ink),
        surfaceVariant = Color(p.surface), onSurfaceVariant = Color(p.muted),
        outline = Color(p.outline), outlineVariant = Color(p.border),
        inverseSurface = Color(p.ink), inverseOnSurface = Color(p.background),
        inversePrimary = Color(palette(theme, !dark).accent),
        surfaceBright = Color(if (dark) p.highest else p.background), surfaceDim = Color(p.surface),
        surfaceContainerLowest = Color(p.background), surfaceContainerLow = Color(p.low),
        surfaceContainer = Color(p.surface), surfaceContainerHigh = Color(p.high),
        surfaceContainerHighest = Color(p.highest),
    )
}

