package com.quickfiles.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settingsData by preferencesDataStore("settings")
enum class ViewMode { LIST, GRID }
enum class GridDensity(val label: String, val minimumWidth: Int) {
    LARGE("크게", 144), NORMAL("보통", 104), SMALL("작게", 80);
    fun step(denser: Boolean) = entries[(ordinal + if (denser) 1 else -1).coerceIn(0, entries.lastIndex)]
}
enum class ThemeMode(val label: String) { SYSTEM("시스템 따르기"), LIGHT("밝게"), DARK("어둡게") }
enum class ColorTheme(val label: String) {
    TERRACOTTA("테라코타"), BLUE("블루"), SAGE("세이지 그린"), LAVENDER("라벤더"), ROSE("로즈"), MONOCHROME("흑백")
}
/** Reading paper for text files; AUTO follows the app's light/dark choice. */
enum class TextPaper(val label: String) { AUTO("테마 따라"), LIGHT("밝게"), DARK("어둡게"), BLACK("검정") }
enum class ListDensity(val label: String, val minimumHeight: Int, val verticalPadding: Int, val thumbnailSize: Int, val itemSpacing: Int) {
    COMPACT("촘촘하게", 56, 4, 32, 8), NORMAL("보통", 64, 8, 40, 12)
}
data class ExplorerSettings(
    val cacheMiB: Int = 1024, val showHidden: Boolean = false, val grid: Boolean = true,
    val textSize: Int = 16, val wrapText: Boolean = true, val autoPip: Boolean = false,
    val density: GridDensity = GridDensity.NORMAL, val theme: ThemeMode = ThemeMode.SYSTEM,
    val colorTheme: ColorTheme = ColorTheme.TERRACOTTA, val listDensity: ListDensity = ListDensity.COMPACT,
    val oledBlack: Boolean = false, val textPaper: TextPaper = TextPaper.AUTO,
) { val viewMode: ViewMode get() = if (grid) ViewMode.GRID else ViewMode.LIST }
internal object SettingsCodec {
    private val cache = intPreferencesKey("cache_mib")
    private val hidden = booleanPreferencesKey("hidden")
    private val grid = booleanPreferencesKey("grid")
    private val textSize = intPreferencesKey("text_size")
    private val wrap = booleanPreferencesKey("wrap")
    private val pip = booleanPreferencesKey("auto_pip")
    private val density = stringPreferencesKey("grid_density")
    private val theme = stringPreferencesKey("theme_mode")
    private val color = stringPreferencesKey("color_theme")
    private val listDensity = stringPreferencesKey("list_density")
    private val oled = booleanPreferencesKey("oled_black")
    private val paper = stringPreferencesKey("text_paper")
    fun decode(it: Preferences) = ExplorerSettings(
        (it[cache] ?: 1024).coerceIn(256, 4096), it[hidden] ?: false, it[grid] ?: true,
        (it[textSize] ?: 16).coerceIn(12, 32), it[wrap] ?: true, it[pip] ?: false,
        GridDensity.entries.firstOrNull { value -> value.name == it[density] } ?: GridDensity.NORMAL,
        ThemeMode.entries.firstOrNull { value -> value.name == it[theme] } ?: ThemeMode.SYSTEM,
        ColorTheme.entries.firstOrNull { value -> value.name == it[color] } ?: ColorTheme.TERRACOTTA,
        ListDensity.entries.firstOrNull { value -> value.name == it[listDensity] } ?: ListDensity.COMPACT,
        it[oled] ?: false,
        TextPaper.entries.firstOrNull { value -> value.name == it[paper] } ?: TextPaper.AUTO,
    )
    fun encode(it: MutablePreferences, value: ExplorerSettings) {
        it[cache] = value.cacheMiB.coerceIn(256, 4096); it[hidden] = value.showHidden; it[grid] = value.grid
        it[textSize] = value.textSize.coerceIn(12, 32); it[wrap] = value.wrapText; it[pip] = value.autoPip
        it[density] = value.density.name; it[theme] = value.theme.name
        it[color] = value.colorTheme.name; it[listDensity] = value.listDensity.name
        it[oled] = value.oledBlack; it[paper] = value.textPaper.name
    }
}
class SettingsStore(private val context: Context) {
    val settings = context.settingsData.data.map(SettingsCodec::decode)
    suspend fun update(transform: (ExplorerSettings) -> ExplorerSettings) {
        context.settingsData.edit {
            SettingsCodec.encode(it, transform(SettingsCodec.decode(it)))
        }
    }
    suspend fun save(value: ExplorerSettings) = update { value }
}
