package com.quickfiles.app.storage

import java.util.Locale

/** How many selected items get which new name, and why any of them cannot be renamed. */
data class RenamePlan(val items: List<Item>) {
    data class Item(val entry: FileEntry, val name: String, val problem: String?) {
        val changes: Boolean get() = name != entry.name
    }
    val renames: List<Item> get() = items.filter { it.changes && it.problem == null }
    val valid: Boolean get() = items.none { it.problem != null } && renames.isNotEmpty()
}

/**
 * Batch renaming rules. A template rewrites each name's stem: `{name}` is the old stem and `{n}` a counter
 * padded to the width of the largest number; the extension of a file is kept. Find and replace then applies
 * to the whole resulting name. Names are compared case-insensitively because shared storage is.
 */
object BatchRename {
    fun plan(selected: List<FileEntry>, siblings: List<FileEntry>, template: String, find: String, replace: String, start: Int = 1): RenamePlan {
        val width = (start + selected.size - 1).coerceAtLeast(0).toString().length
        val proposed = selected.mapIndexed { index, entry ->
            val (stem, ext) = split(entry)
            val base = if (template.isEmpty()) entry.name else template
                .replace("{name}", stem).replace("{n}", (start + index).toString().padStart(width, '0')) + ext
            entry to if (find.isEmpty()) base else base.replace(find, replace)
        }
        val renamed = selected.map { it.id }.toSet()
        val untouched = siblings.filter { it.id !in renamed }.map { key(it, it.name) }.toSet()
        val currentOfSelected = selected.associate { key(it, it.name) to it.id }
        val counts = proposed.filter { (entry, name) -> name != entry.name }.groupingBy { (entry, name) -> key(entry, name) }.eachCount()
        return RenamePlan(proposed.map { (entry, name) ->
            val changes = name != entry.name
            val problem = when {
                !changes -> null
                !validName(name) -> "사용할 수 없는 이름"
                key(entry, name) == key(entry, entry.name) -> "대소문자만 바꿀 수 없음"
                counts.getValue(key(entry, name)) > 1 -> "새 이름이 겹침"
                key(entry, name) in untouched -> "같은 이름의 파일이 있음"
                currentOfSelected[key(entry, name)]?.let { it != entry.id } == true -> "다른 선택 항목의 현재 이름과 겹침"
                else -> null
            }
            RenamePlan.Item(entry, name, problem)
        })
    }

    private fun split(entry: FileEntry): Pair<String, String> {
        if (entry.isDirectory) return entry.name to ""
        val dot = entry.name.lastIndexOf('.')
        return if (dot <= 0) entry.name to "" else entry.name.substring(0, dot) to entry.name.substring(dot)
    }
    /** Names only collide within one folder; search results can span several. */
    private fun key(entry: FileEntry, name: String) = entry.location.sourceId + ":" + entry.location.parent()?.path.orEmpty() + "/" + name.lowercase(Locale.ROOT)
    private fun validName(name: String) = runCatching { requireFileName(name) }.isSuccess && name.trim() == name
}
