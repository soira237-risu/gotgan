package com.quickfiles.app.ui

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import com.quickfiles.app.ui.icons.Symbols
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickfiles.app.data.*
import com.quickfiles.app.operations.*
import com.quickfiles.app.playback.PlayerActivity
import com.quickfiles.app.storage.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

internal fun taskKindLabel(kind: String) = when (kind) { "COPY" -> "복사"; "MOVE" -> "이동"; "RENAME" -> "이름 변경"; "TRASH" -> "휴지통 이동"; "RESTORE" -> "복원"; "PURGE" -> "영구 삭제"; "MKDIR" -> "폴더 생성"; "SAVE", "SAVE_AS" -> "텍스트 저장"; "ZIP" -> "ZIP 만들기"; "EXTRACT" -> "압축 해제"; else -> kind }

internal fun taskStateLabel(state: String) = when (state) { "QUEUED" -> "대기"; "RUNNING" -> "진행 중"; "PAUSING" -> "일시정지 중"; "CANCELLING" -> "취소 중"; "PAUSED" -> "일시정지"; "COMPLETE" -> "완료"; "PARTIAL" -> "부분 완료"; "FAILED" -> "실패"; "CANCELLED" -> "취소"; "PREPARING" -> "준비 중"; else -> state }

internal fun taskTone(state: String) = when (state) {
    "RUNNING", "QUEUED", "PREPARING", "PAUSING", "CANCELLING" -> StatusTone.ACTIVE
    "COMPLETE" -> StatusTone.DONE
    "FAILED", "PARTIAL" -> StatusTone.PROBLEM
    else -> StatusTone.IDLE
}

internal fun taskKindIcon(kind: String) = when (kind) {
    "COPY" -> Symbols.ContentCopy; "MOVE" -> Symbols.DriveFileMove; "RENAME", "SAVE", "SAVE_AS" -> Symbols.Edit
    "TRASH", "PURGE" -> Symbols.Delete; "RESTORE" -> Symbols.Refresh; "MKDIR" -> Symbols.CreateNewFolder
    "ZIP" -> Symbols.FolderZip; "EXTRACT" -> Symbols.Unarchive; else -> Symbols.SwapVert
}

/** Compact sheet from the dock's progress ring: active work first, then the latest finished items. */
@Composable
internal fun TaskSummary(model: ExplorerViewModel, openAll: () -> Unit) {
    val tasks by model.graph.dao.tasks().collectAsStateWithLifecycle(emptyList())
    val active = tasks.filter { taskTone(it.state) == StatusTone.ACTIVE }
    val recent = tasks.filter { taskTone(it.state) != StatusTone.ACTIVE }.take(3)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (tasks.isEmpty()) Text("진행 중인 작업이 없어요.", Modifier.padding(4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        (active + recent).take(5).forEach { task ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(taskKindIcon(task.kind), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(taskKindLabel(task.kind), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (task.state == "RUNNING" && task.totalBytes > 0) {
                        LinearProgressIndicator(progress = { (task.bytes.toFloat() / task.totalBytes).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                        Text("${formatBytes(task.bytes)} / ${formatBytes(task.totalBytes)} · ${formatBytes(task.speed)}/s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Text(task.message.ifBlank { "완료 ${task.completed} · 실패 ${task.failed}" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                StatusChip(taskStateLabel(task.state), taskTone(task.state))
            }
        }
        PillButton("전체 작업 보기", openAll, Modifier.fillMaxWidth().testTag("task-summary-all"), primary = true)
    }
}

@Composable
internal fun TasksPanel(model: ExplorerViewModel) {
    val tasks by model.graph.dao.tasks().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    var savingAs by remember { mutableStateOf<FileTask?>(null) }
    var saveName by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }
    if (tasks.isEmpty()) Text("아직 작업이 없습니다. 복사·이동·휴지통 이동 같은 작업이 여기에 기록돼요.", Modifier.padding(start = 4.dp, top = 16.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        items(tasks, key = { it.id }) { task ->
            var details by remember(task.id) { mutableStateOf<List<TaskItem>?>(null) }
            var detailLimit by remember(task.id) { mutableIntStateOf(50) }
            GroupCard { Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val kind = taskKindLabel(task.kind)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                        Icon(taskKindIcon(task.kind), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(kind, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    StatusChip(taskStateLabel(task.state), taskTone(task.state))
                }
                // A finished task's message is the same counts summary, so it is shown only when it says something more.
                val counts = "완료 ${task.completed} · 건너뜀 ${task.skipped} · 실패 ${task.failed}"
                if (task.message.isNotBlank() && !counts.startsWith(task.message)) Text(task.message, style = MaterialTheme.typography.bodyMedium)
                Text(counts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (task.state == "RUNNING") {
                    if (task.totalBytes > 0) LinearProgressIndicator(progress = { (task.bytes.toFloat() / task.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                    Text("${formatBytes(task.bytes)} / ${if (task.totalBytes >= 0) formatBytes(task.totalBytes) else "계산 중"} · ${formatBytes(task.speed)}/s", style = MaterialTheme.typography.bodySmall)
                    if (task.totalBytes > 0) Text("남은 전송량 ${formatBytes((task.totalBytes - task.bytes).coerceAtLeast(0))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.state in listOf("RUNNING", "QUEUED")) PillButton("일시정지", { scope.launch { model.graph.operations.pause(task.id) } })
                    if (task.state in listOf("PAUSED", "FAILED", "PARTIAL")) PillButton(if (task.state == "PAUSED") "재개" else "실패 항목 재시도", { scope.launch { try { val network = model.graph.operations.needsNetwork(task.id); model.graph.operations.resume(task.id); TransferScheduler.schedule(context, network) } catch (e: Exception) { model.notice(readableError(e)) } } }, primary = true)
                    if (task.state in listOf("RUNNING", "QUEUED", "PAUSED")) PillButton("취소", { scope.launch { model.graph.operations.pause(task.id, true) } })
                    if (task.failed > 0) PillButton(if (details == null) "실패 항목 보기" else "접기", { scope.launch { details = if (details == null) model.graph.dao.items(task.id).filter { it.phase == "RESULT_FAILED" } else null } })
                    if (task.kind in listOf("SAVE", "SAVE_AS") && task.state in listOf("FAILED", "PARTIAL", "PAUSED", "CANCELLED")) PillButton("편집본 다른 이름 저장", {
                        savingAs = task; saveError = null
                        saveName = decodeLocation(JSONObject(task.payload).getString("destination")).path.substringAfterLast('/').substringBeforeLast('.') + "_edited.txt"
                    })
                }
                details?.let { rows ->
                    rows.take(detailLimit).forEach { result ->
                        Text(runCatching { decodeLocation(result.source).path.substringAfterLast('/') }.getOrDefault(kind), style = MaterialTheme.typography.labelLarge)
                        Text(result.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (rows.size > detailLimit) TextButton(shape = MaterialTheme.shapes.small, onClick = { detailLimit += 50 }) { Text("다음 50개") }
                }
            } }
        }
    }
    savingAs?.let { task -> AlertDialog(onDismissRequest = { savingAs = null }, title = { Text("보존한 편집 내용 저장") }, text = { Column { OutlinedTextField(saveName, { saveName = it }, singleLine = true, label = { Text("새 파일 이름") }); saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = {
        scope.launch {
            try {
                requireFileName(saveName)
                val destination = decodeLocation(JSONObject(task.payload).getString("destination")).parent()!!.child(saveName)
                model.graph.operations.retryTextAs(task.id, destination)
                TransferScheduler.schedule(context, destination.sourceId != LOCAL_SOURCE); savingAs = null
            } catch (e: Exception) { saveError = readableError(e) }
        }
    }) { Text("저장") } }, dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { savingAs = null }) { Text("취소") } }) }
}

@Composable
internal fun TrashPanel(model: ExplorerViewModel) {
    val records by model.graph.dao.trashItems().collectAsStateWithLifecycle(emptyList())
    val items = records.filter { it.state != "RESTORED" }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var purge by remember { mutableStateOf<List<String>?>(null) }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(if (selected.size == items.size && items.isNotEmpty()) "선택 해제" else "전체 선택", { selected = if (selected.size == items.size) emptySet() else items.map { it.id }.toSet() }, enabled = items.isNotEmpty())
        PillButton(if (selected.isEmpty()) "복원" else "${selected.size}개 복원", { model.enqueue("RESTORE", emptyList(), null, extra = JSONObject().put("trashIds", JSONArray(selected.toList()))) }, enabled = selected.isNotEmpty(), primary = true, icon = Symbols.Refresh)
        PillButton("영구 삭제", { purge = selected.toList() }, enabled = selected.isNotEmpty())
        PillButton("비우기", { purge = items.map { it.id } }, enabled = items.isNotEmpty())
    }
    if (items.isEmpty()) Text("휴지통이 비어 있습니다. 삭제한 파일은 여기에 보관되고 직접 비우기 전까지 남아 있어요.", Modifier.padding(start = 4.dp, top = 8.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else LazyColumn(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) { items(items, key = { it.id }) { item ->
        val checked = item.id in selected
        Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = { selected = if (it) selected + item.id else selected - item.id }).heightIn(min = 60.dp).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge)
                Text("${model.sourceName(item.source)} / ${item.original}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val status = when (item.state) { "TRASHED" -> "보관 중"; "PREPARING" -> "보관 확인 중"; "RESTORING" -> "복원 확인 중"; "PURGING" -> "삭제 확인 중"; else -> "상태 확인 필요" }
                Text("${DateFormat.getDateTimeInstance().format(Date(item.deleted))} · $status", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (checked) Box(Modifier.size(26.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Symbols.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
            } else Box(Modifier.size(26.dp).border(1.8.dp, MaterialTheme.colorScheme.outline, CircleShape))
        }
        if (item != items.last()) GroupDivider()
    } }
    purge?.let { ids -> AlertDialog(onDismissRequest = { purge = null }, title = { Text("${ids.size}개 영구 삭제") }, text = { Text("선택한 휴지통 항목을 영구 삭제합니다. 이 작업은 되돌릴 수 없습니다.") }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { model.enqueue("PURGE", emptyList(), null, extra = JSONObject().put("trashIds", JSONArray(ids))); purge = null; selected = emptySet() }) { Text("영구 삭제") } }, dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { purge = null }) { Text("취소") } }) }
}

@Composable
internal fun SettingsPanel(model: ExplorerViewModel) {
    val settings by model.settings.collectAsStateWithLifecycle()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SectionLabel("화면")
        GroupCard {
            ThemeMode.entries.forEachIndexed { index, mode -> if (index > 0) GroupDivider(); ChoiceRow(mode.label, settings.theme == mode, { model.updateSettings { it.copy(theme = mode) } }) }
            GroupDivider()
            ToggleRow("OLED 검정 · 어둡게일 때 완전한 검정 배경", settings.oledBlack) { value -> model.updateSettings { it.copy(oledBlack = value) } }
        }
        SectionLabel("색상 테마")
        ColorThemeChoices(settings.colorTheme, settings.theme) { color -> model.updateSettings { it.copy(colorTheme = color) } }
        SectionLabel("목록 간격")
        GroupCard {
            ListDensity.entries.forEachIndexed { index, value -> if (index > 0) GroupDivider(); ChoiceRow(value.label, settings.listDensity == value,
                { model.updateSettings { it.copy(listDensity = value) } }, modifier = Modifier.testTag("settings-list-${value.name}")) }
        }
        SectionLabel("기본 그리드 밀도")
        GroupCard {
            GridDensity.entries.forEachIndexed { index, value -> if (index > 0) GroupDivider(); ChoiceRow(value.label, settings.density == value, { model.updateSettings { it.copy(density = value) } }) }
        }
        SectionLabel("탐색")
        GroupCard {
            ToggleRow("숨김 파일 표시", settings.showHidden) { value -> model.updateSettings { it.copy(showHidden = value) } }
            GroupDivider()
            ToggleRow("격자로 보기", settings.grid) { value -> model.updateSettings { it.copy(grid = value) } }
            GroupDivider()
            Text("미리보기 캐시 ${settings.cacheMiB}MiB", Modifier.padding(top = 12.dp))
            Slider(settings.cacheMiB.toFloat(), { value -> model.updateSettings { it.copy(cacheMiB = (value / 256).toInt() * 256) } }, valueRange = 256f..4096f, steps = 14,
                modifier = Modifier.semantics { contentDescription = "미리보기 캐시 한도"; stateDescription = "${settings.cacheMiB}MiB" })
        }
        SectionLabel("재생")
        GroupCard {
            ToggleRow("영상에서 홈으로 나갈 때 자동 PiP", settings.autoPip) { value -> model.updateSettings { it.copy(autoPip = value) } }
        }
        SectionLabel("텍스트")
        GroupCard {
            ToggleRow("텍스트 줄바꿈", settings.wrapText) { value -> model.updateSettings { it.copy(wrapText = value) } }
            GroupDivider()
            Text("글자 크기 ${settings.textSize}", Modifier.padding(top = 12.dp))
            Slider(settings.textSize.toFloat(), { value -> model.updateSettings { it.copy(textSize = value.toInt()) } }, valueRange = 12f..32f, steps = 19,
                modifier = Modifier.semantics { contentDescription = "텍스트 글자 크기"; stateDescription = "${settings.textSize}sp" })
        }
        Text("곳간 1.0.0 · 개인 설치용\n휴지통은 자동으로 비우지 않습니다.", Modifier.padding(start = 4.dp, top = 24.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun FileActions(state: ExplorerState, model: ExplorerViewModel, command: String? = null, onConsumed: () -> Unit = {}) {
    var action by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(command) { if (command != null) { action = command; name = if (command == "이름 변경") model.selection().singleOrNull()?.name.orEmpty() else if (command == "ZIP 만들기") "Archive.zip" else ""; onConsumed() } }
    val selection = model.selection()
    action?.let { title -> AlertDialog(onDismissRequest = { action = null }, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (title) {
                "휴지통으로 이동" -> Text("${selection.size}개 항목을 휴지통에 보관합니다. 복원할 수 있으며, 보관할 수 없으면 삭제하지 않습니다.")
                "파일 정보" -> selection.singleOrNull()?.let { Text("${it.name}\n${model.sourceName(it.location.sourceId)} / ${it.location.path}\n${it.size?.let(::formatBytes) ?: "폴더"}\n${it.modified?.let { time -> DateFormat.getDateTimeInstance().format(Date(time)) }.orEmpty()}") }
                else -> OutlinedTextField(name, { name = it }, label = { Text("이름") }, singleLine = true)
            }
        }
    }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = {
        when (title) {
            "휴지통으로 이동" -> model.enqueue("TRASH")
            "새 폴더" -> runCatching { requireFileName(name); model.enqueue("MKDIR", emptyList(), state.location!!.child(name)) }.onFailure { model.notice(it.message) }
            "이름 변경" -> runCatching { requireFileName(name); model.enqueue("RENAME", extra = JSONObject().put("name", name)) }.onFailure { model.notice(it.message) }
            "ZIP 만들기" -> runCatching { requireFileName(name); model.enqueue("ZIP", destination = state.location!!.child(if (name.endsWith(".zip", true)) name else "$name.zip")) }.onFailure { model.notice(it.message) }
        }
        action = null
    }) { Text(if (title == "파일 정보") "닫기" else "확인") } }, dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { action = null }) { Text("취소") } }) }
}
