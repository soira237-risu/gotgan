package com.quickfiles.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.quickfiles.app.ui.theme.appColors
import com.quickfiles.app.ui.theme.oledColors
import com.quickfiles.app.ui.theme.themeExtras
import com.quickfiles.app.ui.theme.keepsakeColors
import com.quickfiles.app.ui.paperColors
import com.quickfiles.app.data.TextPaper
import androidx.compose.ui.graphics.compositeOver
import com.quickfiles.app.data.ColorTheme
import org.junit.Assert.assertEquals
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
            val (background, ink) = paperColors(paper, scheme.background, scheme.onSurface, extras(dark, theme))
            val shown = ink.compositeOver(background)
            val a = shown.luminance(); val b = background.luminance()
            val ratio = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
            assertTrue("paper=$paper dark=$dark theme=$theme contrast=$ratio", ratio >= 7f)
        }
    }

    @Test fun lightAndDarkPapersFollowThePalette() {
        for (dark in listOf(false, true)) for (theme in ColorTheme.entries) {
            val scheme = appColors(dark, theme)
            val (light, lightInk) = paperColors(TextPaper.LIGHT, scheme.background, scheme.onSurface, extras(dark, theme))
            assertEquals("light paper theme=$theme", appColors(false, theme).background, light)
            assertEquals("light ink theme=$theme", appColors(false, theme).onSurface, lightInk)
            val (darkPaper, _) = paperColors(TextPaper.DARK, scheme.background, scheme.onSurface, extras(dark, theme))
            assertEquals("dark paper theme=$theme", appColors(true, theme).background, darkPaper)
        }
    }

    @Test fun successChipReadsOnEverySurface() {
        for (dark in listOf(false, true)) for (theme in ColorTheme.entries) for (oled in listOf(false, true)) {
            if (oled && !dark) continue
            val colors = appColors(dark, theme).let { if (oled) oledColors(it) else it }
            val extra = extras(dark, theme)
            val ratio = contrastOf(extra.onSuccessContainer, extra.successContainer)
            assertTrue("success chip dark=$dark theme=$theme oled=$oled contrast=$ratio", ratio >= 4.5f)
            for (surface in listOf(colors.surface, colors.surfaceContainerLow, colors.surfaceContainer, colors.surfaceContainerHigh)) {
                val mark = contrastOf(extra.success, surface)
                assertTrue("success mark dark=$dark theme=$theme oled=$oled contrast=$mark", mark >= 3f)
            }
        }
    }

    @Test fun wallpaperChoiceFallsBackToKeepsakeWithoutPlatformColours() {
        // ColorScheme has no value equality; compare the roles screens read.
        for (dark in listOf(false, true)) {
            val expected = keepsakeColors(dark); val actual = appColors(dark, ColorTheme.DYNAMIC)
            listOf(expected.primary to actual.primary, expected.background to actual.background, expected.onSurface to actual.onSurface,
                expected.surfaceContainerLow to actual.surfaceContainerLow, expected.primaryContainer to actual.primaryContainer)
                .forEach { (want, got) -> assertEquals("dark=$dark", want, got) }
        }
    }

    private fun extras(dark: Boolean, theme: ColorTheme) = themeExtras(dark, appColors(false, theme), appColors(true, theme))

    private fun contrastOf(foreground: Color, background: Color): Float {
        val a = foreground.compositeOver(background).luminance(); val b = background.luminance()
        return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
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
