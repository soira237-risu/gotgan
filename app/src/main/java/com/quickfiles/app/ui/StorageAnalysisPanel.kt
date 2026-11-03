package com.quickfiles.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickfiles.app.storage.*

/** Where a folder's space goes: by subfolder, by kind, the largest files, and duplicate copies that can be reclaimed. */
@Composable
internal fun ColumnScope.StorageAnalysisPanel(model: ExplorerViewModel, close: () -> Unit) {
    val analysis by model.analysis.collectAsStateWithLifecycle()
    val current = analysis
    var confirm by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    if (current == null) {
        Text("폴더를 연 뒤 더보기 메뉴에서 ‘저장공간 분석’을 눌러 주세요.", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text("${model.sourceName(current.root.sourceId)} / ${current.root.path}", maxLines = 2, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (current.running) PillButton("중지", { model.cancelAnalysis() }, Modifier.testTag("analysis-cancel"))
        else PillButton("다시 분석", { model.analyze(current.root) }, Modifier.testTag("analysis-rescan"))
        current.report?.duplicates?.takeIf { it.isNotEmpty() }?.let { groups ->
            PillButton("중복 ${groups.sumOf { it.files.size - 1 }}개 정리", { confirm = groups }, primary = true, modifier = Modifier.testTag("analysis-clean-duplicates"))
        }
    }
    if (current.running) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(if (current.progress.hashing) "같은 크기의 파일 ${current.progress.files}개 내용을 비교하는 중 · ${formatBytes(current.progress.bytes)}"
            else "파일 ${current.progress.files}개 · ${formatBytes(current.progress.bytes)} 확인 중", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
    current.error?.let { ErrorPanel(it) }
    val report = current.report ?: return
    LazyColumn(Modifier.weight(1f).testTag("analysis-report"), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatBytes(report.totalBytes), style = MaterialTheme.typography.headlineSmall)
                Text("파일 ${report.files}개 · 폴더 ${report.folders}개" + if (report.unreadableFolders > 0) " · 읽지 못한 폴더 ${report.unreadableFolders}개" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                report.kinds.filterValues { it > 0 }.forEach { (kind, bytes) -> UsageBar(kind.label, bytes, report.totalBytes) }
                if (report.duplicates.isNotEmpty()) Text("중복 파일을 정리하면 ${formatBytes(report.reclaimable)}를 확보할 수 있습니다.", color = MaterialTheme.colorScheme.primary)
                else if (report.duplicatesSkipped) Text("원격 저장소는 모든 파일을 내려받아야 해서 중복 확인을 하지 않았습니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
        section("폴더별 용량", report.children.isEmpty()) {
            items(report.children, key = { "child:" + it.entry.id }) { child ->
                ReportRow(child.entry.name, if (child.entry.isDirectory) "파일 ${child.files}개" else "파일", child.bytes, report.totalBytes) {
                    close(); if (child.entry.isDirectory) model.navigate(child.entry.location) else model.open(child.entry)
                }
            }
        }
        section("큰 파일", report.largest.isEmpty()) {
            items(report.largest, key = { "large:" + it.id }) { file ->
                ReportRow(file.name, file.location.parent()?.path?.ifEmpty { model.sourceName(file.location.sourceId) }.orEmpty(), file.size ?: 0, report.totalBytes) { close(); model.open(file) }
            }
        }
        if (report.duplicates.isNotEmpty()) section("중복 파일", false) {
            items(report.duplicates, key = { "dup:" + it.digest }) { group ->
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${group.files.size}개 · 각 ${formatBytes(group.size)}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(shape = MaterialTheme.shapes.small, onClick = { confirm = listOf(group) }) { Text("첫 파일만 남기기") }
                    }
                    group.files.forEachIndexed { index, file ->
                        Text((if (index == 0) "남김 · " else "") + "${model.sourceName(file.location.sourceId)} / ${file.location.path}", maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable { close(); model.open(file) }.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
    confirm?.let { groups ->
        val count = groups.sumOf { it.files.size - 1 }
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("중복 ${count}개 정리") },
            text = { Text("각 묶음에서 경로 순으로 첫 파일만 남기고 나머지 ${count}개(${formatBytes(groups.sumOf { it.reclaimable })})를 휴지통으로 옮깁니다. 휴지통에서 복원할 수 있습니다.") },
            confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { model.trashDuplicates(groups); confirm = null }, modifier = Modifier.testTag("confirm-clean-duplicates")) { Text("휴지통으로 이동") } },
            dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { confirm = null }) { Text("취소") } })
    }
}

private fun LazyListScope.section(title: String, empty: Boolean, content: LazyListScope.() -> Unit) {
    item(key = "title:$title") { Text(title, Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (empty) item(key = "empty:$title") { Text("항목이 없습니다.", Modifier.padding(4.dp), style = MaterialTheme.typography.bodySmall) } else content()
}

@Composable
private fun UsageBar(label: String, bytes: Long, total: Long) {
    Column {
        Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(formatBytes(bytes), style = MaterialTheme.typography.bodyMedium) }
        LinearProgressIndicator({ if (total > 0) bytes.toFloat() / total else 0f }, Modifier.fillMaxWidth().padding(top = 2.dp))
    }
}

@Composable
private fun ReportRow(title: String, detail: String, bytes: Long, total: Long, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                if (detail.isNotEmpty()) Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(formatBytes(bytes), style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator({ if (total > 0) bytes.toFloat() / total else 0f }, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}
