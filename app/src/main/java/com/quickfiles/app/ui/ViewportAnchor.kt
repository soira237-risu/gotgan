package com.quickfiles.app.ui

import com.quickfiles.app.data.GridDensity
import com.quickfiles.app.storage.FileEntry
import kotlin.math.floor

/** Index is an item index, never a row number. Identity survives column count changes. */
data class ViewportAnchor(val fileId: String? = null, val index: Int = 0, val offset: Int = 0, val layout: String = "list") {
    fun resolve(entries: List<FileEntry>): Int = entries.indexOfFirst { it.id == fileId }.takeIf { it >= 0 }
        ?: index.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
    fun offsetFor(layout: String) = if (this.layout == layout) offset.coerceAtLeast(0) else 0
}

const val GRID_SPACING_DP = 4
fun gridColumns(availableWidthDp: Float, fontScale: Float, density: GridDensity): Int =
    floor((availableWidthDp + GRID_SPACING_DP) / (density.minimumWidth * fontScale.coerceAtLeast(1f) + GRID_SPACING_DP)).toInt().coerceAtLeast(1)
