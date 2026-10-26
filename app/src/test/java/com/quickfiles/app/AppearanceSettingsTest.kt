package com.quickfiles.app

import androidx.datastore.preferences.core.*
import com.quickfiles.app.data.*
import org.junit.Assert.*
import org.junit.Test

class AppearanceSettingsTest {
    @Test fun legacySettingsKeepExistingChoicesAndUseSafeAppearanceDefaults() {
        val legacy = preferencesOf(booleanPreferencesKey("grid") to false,
            stringPreferencesKey("theme_mode") to "DARK", stringPreferencesKey("grid_density") to "SMALL",
            booleanPreferencesKey("auto_pip") to true, intPreferencesKey("cache_mib") to 2048)
        val settings = SettingsCodec.decode(legacy)
        assertFalse(settings.grid)
        assertEquals(ThemeMode.DARK, settings.theme)
        assertEquals(GridDensity.SMALL, settings.density)
        assertTrue(settings.autoPip)
        assertEquals(2048, settings.cacheMiB)
        assertEquals(ColorTheme.TERRACOTTA, settings.colorTheme)
        assertEquals(ListDensity.COMPACT, settings.listDensity)
        assertFalse(settings.oledBlack)
    }

    @Test fun oledBlackRoundTripsWithoutChangingOtherAppearance() {
        val preferences = mutablePreferencesOf()
        for (oled in listOf(true, false)) {
            SettingsCodec.encode(preferences, ExplorerSettings(oledBlack = oled, theme = ThemeMode.DARK, colorTheme = ColorTheme.SAGE))
            val restored = SettingsCodec.decode(preferences)
            assertEquals(oled, restored.oledBlack)
            assertEquals(ThemeMode.DARK, restored.theme)
            assertEquals(ColorTheme.SAGE, restored.colorTheme)
        }
    }

    @Test fun textPaperRoundTripsAndUnknownValuesFollowTheTheme() {
        val preferences = mutablePreferencesOf()
        for (paper in TextPaper.entries) {
            SettingsCodec.encode(preferences, ExplorerSettings(textPaper = paper, textSize = 20))
            val restored = SettingsCodec.decode(preferences)
            assertEquals(paper, restored.textPaper)
            assertEquals(20, restored.textSize)
        }
        assertEquals(TextPaper.AUTO, SettingsCodec.decode(preferencesOf(stringPreferencesKey("text_paper") to "sepia")).textPaper)
    }

    @Test fun unknownAppearanceValuesFallBackWithoutLosingIndependentSettings() {
        val settings = SettingsCodec.decode(preferencesOf(stringPreferencesKey("color_theme") to "future-color",
            stringPreferencesKey("list_density") to "future-density", stringPreferencesKey("theme_mode") to "LIGHT"))
        assertEquals(ColorTheme.TERRACOTTA, settings.colorTheme)
        assertEquals(ListDensity.COMPACT, settings.listDensity)
        assertEquals(ThemeMode.LIGHT, settings.theme)
    }

    @Test fun savingAppearanceRetainsOtherPreferencesAndRoundTripsAllChoices() {
        val preferences = mutablePreferencesOf(stringPreferencesKey("unrelated") to "keep")
        for (color in ColorTheme.entries) for (density in ListDensity.entries) {
            SettingsCodec.encode(preferences, ExplorerSettings(colorTheme = color, listDensity = density,
                grid = false, theme = ThemeMode.DARK, showHidden = true, textSize = 22, autoPip = true))
            val restored = SettingsCodec.decode(preferences)
            assertEquals(color, restored.colorTheme)
            assertEquals(density, restored.listDensity)
            assertFalse(restored.grid)
            assertEquals(ThemeMode.DARK, restored.theme)
            assertTrue(restored.showHidden)
            assertTrue(restored.autoPip)
            assertEquals(22, restored.textSize)
            assertEquals("keep", preferences[stringPreferencesKey("unrelated")])
        }
    }
}
