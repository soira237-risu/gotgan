package com.quickfiles.app.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.quickfiles.app.operations.CrashLog
import com.quickfiles.app.ui.icons.Symbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Settings section listing the crash reports kept on this device, with view, share and delete. */
@Composable
internal fun CrashReportsSection(log: CrashLog) {
    var version by remember { mutableIntStateOf(0) }
    var reports by remember { mutableStateOf<List<CrashLog.Report>?>(null) }
    var open by remember { mutableStateOf<CrashLog.Report?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(version) { reports = withContext(Dispatchers.IO) { runCatching { log.reports() }.getOrDefault(emptyList()) } }
    val list = reports.orEmpty()
    SectionLabel(if (list.isEmpty()) "오류 기록" else "오류 기록 ${list.size}건")
    GroupCard(Modifier.testTag("crash-reports")) {
        if (reports != null && list.isEmpty()) Text("기록된 오류가 없습니다. 곳간이 예기치 않게 종료되면 원인이 이 기기에만 저장되고, 직접 공유하기 전에는 밖으로 나가지 않습니다.",
            Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.forEachIndexed { index, report ->
            if (index > 0) GroupDivider()
            Row(Modifier.fillMaxWidth().clickable { open = report }.heightIn(min = 60.dp).padding(vertical = 10.dp).testTag("crash-report-$index"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Symbols.Error, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.error)
                Column(Modifier.weight(1f)) {
                    Text(DateFormat.getDateTimeInstance().format(Date(report.time)), style = MaterialTheme.typography.bodyLarge)
                    Text(report.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
        }
        if (list.isNotEmpty()) {
            GroupDivider()
            TextButton({ confirmClear = true }, Modifier.testTag("crash-clear"), shape = MaterialTheme.shapes.small) { Text("오류 기록 모두 삭제") }
        }
    }
    open?.let { report -> CrashReportDialog(log, report, onDismiss = { open = null }, onDelete = {
        scope.launch { withContext(Dispatchers.IO) { runCatching { log.delete(report.name) } }; open = null; version++ }
    }) }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("오류 기록 ${list.size}건 삭제") },
        text = { Text("이 기기에 저장된 오류 기록을 모두 지웁니다. 되돌릴 수 없습니다.") },
        confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = {
            confirmClear = false
            scope.launch { withContext(Dispatchers.IO) { runCatching { log.clear() } }; version++ }
        }) { Text("삭제") } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { confirmClear = false }) { Text("취소") } })
}

@Composable
private fun CrashReportDialog(log: CrashLog, report: CrashLog.Report, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var text by remember(report.name) { mutableStateOf<String?>(null) }
    LaunchedEffect(report.name) { text = withContext(Dispatchers.IO) { runCatching { log.read(report.name) }.getOrElse { "기록을 읽지 못했습니다." } } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("오류 기록") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            SelectionContainer {
                Text(text ?: "불러오는 중…", Modifier.horizontalScroll(rememberScrollState()).testTag("crash-report-text"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }, confirmButton = {
        TextButton(shape = MaterialTheme.shapes.small, enabled = text != null, onClick = {
            val body = text ?: return@TextButton
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "곳간 오류 기록 ${report.name}").putExtra(Intent.EXTRA_TEXT, body)
            context.startActivity(Intent.createChooser(send, "오류 기록 공유"))
        }) { Text("공유") }
    }, dismissButton = {
        Row {
            TextButton(shape = MaterialTheme.shapes.small, onClick = onDelete, modifier = Modifier.testTag("crash-delete")) { Text("삭제") }
            TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss) { Text("닫기") }
        }
    })
}
