package com.quickfiles.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickfiles.app.storage.FileEntry
import com.quickfiles.app.ui.icons.Symbols
import java.text.DateFormat
import java.util.Date

/** Files opened in Gotgan, and files that recently appeared on the phone (downloads, photos, recordings). */
@Composable
internal fun ColumnScope.RecentFilesPanel(model: ExplorerViewModel, close: () -> Unit) {
    LaunchedEffect(Unit) { model.loadRecents() }
    val recents by model.recents.collectAsStateWithLifecycle()
    val explorer by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        listOf("최근 연 파일", "새로 생긴 파일").forEachIndexed { index, label ->
            SegmentedButton(tab == index, { tab = index }, SegmentedButtonDefaults.itemShape(index, 2), Modifier.testTag("recent-tab-$index")) { Text(label) }
        }
    }
    val current = recents
    if (current == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    val items = if (tab == 0) current.first else current.second
    if (items.isEmpty()) {
        Text(if (tab == 0) "곳간에서 연 파일이 여기에 모여요." else if (!explorer.hasPermission) "‘모든 파일 접근’ 권한을 허용하면 휴대폰에 새로 생긴 파일을 보여 드려요." else "최근에 생긴 파일이 없습니다.",
            Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large).testTag("recent-list"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        items(items, key = { it.id }) { entry -> RecentRow(entry, model, { close(); model.openRecent(entry) }, { close(); entry.location.parent()?.let(model::navigate) }) }
    }
}

@Composable
private fun RecentRow(entry: FileEntry, model: ExplorerViewModel, open: () -> Unit, reveal: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = open).heightIn(min = 60.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            val place = "${model.sourceName(entry.location.sourceId)} / ${entry.location.parent()?.path.orEmpty()}"
            val detail = listOfNotNull(entry.size?.let(::formatBytes), entry.modified?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }).joinToString(" · ")
            Text(if (detail.isEmpty()) place else "$place · $detail", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ToolIcon(Symbols.FolderFilled, "‘${entry.name}’ 위치 열기", reveal)
    }
}
