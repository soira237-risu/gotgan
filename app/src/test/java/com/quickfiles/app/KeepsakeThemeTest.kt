package com.quickfiles.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.quickfiles.app.ui.theme.appColors
import com.quickfiles.app.ui.theme.oledColors
import com.quickfiles.app.ui.paperColors
import com.quickfiles.app.data.TextPaper
import androidx.compose.ui.graphics.compositeOver
import com.quickfiles.app.data.ColorTheme
import org.junit.Assert.assertTrue
import org.junit.Test

/** Color contrast is a usability constraint, independent of the chosen palette. */
class KeepsakeThemeTest {
    @Test fun lightThemeKeepsTextAndSelectionReadable() = verify(dark = false)
    @Test fun darkThemeKeepsTextAndSelectionReadable() = verify(dark = true)
    @Test fun everyColorChoiceKeepsLightTextAndSelectionReadable() {
        ColorTheme.entries.forEach { verify(dark = false, theme = it) }
    }
    @Test fun everyColorChoiceKeepsDarkTextAndSelectionReadable() {
        ColorTheme.entries.forEach { verify(dark = true, theme = it) }
    }

    @Test fun everyColorChoiceKeepsOledBlackReadable() {
        ColorTheme.entries.forEach { verify(dark = true, theme = it, oled = true) }
    }

    @Test fun everyTextPaperKeepsBodyReadable() {
        for (dark in listOf(false, true)) for (theme in ColorTheme.entries) for (paper in TextPaper.entries) {
            val scheme = appColors(dark, theme)
            val (background, ink) = paperColors(paper, scheme.background, scheme.onSurface)
            val shown = ink.compositeOver(background)
            val a = shown.luminance(); val b = background.luminance()
            val ratio = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
            assertTrue("paper=$paper dark=$dark theme=$theme contrast=$ratio", ratio >= 7f)
        }
    }

    private fun verify(dark: Boolean, theme: ColorTheme = ColorTheme.TERRACOTTA, oled: Boolean = false) {
        val colors = appColors(dark, theme).let { if (oled) oledColors(it) else it }
        fun contrast(label: String, foreground: Color, background: Color, minimum: Float = 4.5f) {
            val first = foreground.luminance()
            val second = background.luminance()
            val ratio = (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
            assertTrue("$label dark=$dark contrast=$ratio required=$minimum", ratio >= minimum)
        }
        listOf(colors.surface, colors.surfaceContainerLow, colors.surfaceContainer,
            colors.surfaceContainerHigh, colors.surfaceContainerHighest).forEachIndexed { index, surface ->
            contrast("Body on surface $index", colors.onSurface, surface)
            contrast("Metadata on surface $index", colors.onSurfaceVariant, surface)
            contrast("Action on surface $index", colors.primary, surface)
        }
        contrast("Filled button", colors.onPrimary, colors.primary)
        contrast("Selected chip", colors.onSecondaryContainer, colors.secondaryContainer)
        contrast("Selected file name", colors.onSurface, colors.primaryContainer)
        contrast("Selected metadata", colors.onSurfaceVariant, colors.primaryContainer)
        contrast("Selection boundary/checkmark", colors.primary, colors.primaryContainer, 3f)
        contrast("Input outline", colors.outline, colors.surface, 3f)
        contrast("Error", colors.error, colors.surface)
        contrast("Error panel", colors.onErrorContainer, colors.errorContainer)
        contrast("Snackbar", colors.inverseOnSurface, colors.inverseSurface)
    }
}
