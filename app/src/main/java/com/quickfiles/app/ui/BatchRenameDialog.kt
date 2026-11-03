package com.quickfiles.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Renames the selection with a numbered template and find/replace, showing every resulting name before anything runs. */
@Composable
internal fun BatchRenameDialog(model: ExplorerViewModel, dismiss: () -> Unit) {
    var template by rememberSaveable { mutableStateOf("{name}") }
    var find by rememberSaveable { mutableStateOf("") }
    var replace by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf("1") }
    val plan = remember(template, find, replace, start, model.state.value.selected) {
        model.renamePlan(template, find, replace, start.toIntOrNull() ?: 1)
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("${plan.items.size}개 이름 변경") }, modifier = Modifier.testTag("batch-rename"), text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(template, { template = it }, Modifier.fillMaxWidth().testTag("batch-template"), singleLine = true,
                label = { Text("이름 형식") }, supportingText = { Text("{name}은 원래 이름, {n}은 번호예요. 확장자는 그대로 둡니다.") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(find, { find = it }, Modifier.weight(1f).testTag("batch-find"), singleLine = true, label = { Text("찾기") })
                OutlinedTextField(replace, { replace = it }, Modifier.weight(1f).testTag("batch-replace"), singleLine = true, label = { Text("바꾸기") })
            }
            OutlinedTextField(start, { value -> start = value.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("시작 번호") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            HorizontalDivider()
            LazyColumn(Modifier.heightIn(max = 240.dp).testTag("batch-preview")) {
                items(plan.items, key = { it.entry.id }) { item ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(item.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (item.changes) "→ ${item.name}" else "→ 그대로", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        item.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }, confirmButton = {
        TextButton(shape = MaterialTheme.shapes.small, enabled = plan.valid, modifier = Modifier.testTag("batch-confirm"),
            onClick = { model.batchRename(plan); dismiss() }) { Text("${plan.renames.size}개 바꾸기") }
    }, dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = dismiss) { Text("취소") } })
}
