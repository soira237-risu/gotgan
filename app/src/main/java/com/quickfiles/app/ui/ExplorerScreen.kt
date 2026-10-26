package com.quickfiles.app.ui

import com.quickfiles.app.ui.icons.Symbols
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickfiles.app.storage.*
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import coil3.compose.AsyncImage
import com.quickfiles.app.data.decodeLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ErrorPanel(message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Symbols.Error, "오류", tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
internal fun ConnectionDialog(edit: ConnectionEditor, model: ExplorerViewModel) {
    Dialog(onDismissRequest = { model.closeEditor() }) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp)) {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (edit.existing == null) "WebDAV 저장소 추가" else "WebDAV 연결 수정", style = MaterialTheme.typography.titleLarge)
                Text("표준 HTTPS · 사용자 계정 인증", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(edit.name, { model.updateEditor(edit.copy(name = it)) }, label = { Text("연결 이름") }, singleLine = true, enabled = !edit.busy, modifier = Modifier.fillMaxWidth().testTag("connection-name"))
                OutlinedTextField(edit.url, { model.updateEditor(edit.copy(url = it)) }, label = { Text("HTTPS 주소") }, placeholder = { Text("https://nas.example.com:5006/") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !edit.busy, modifier = Modifier.fillMaxWidth().testTag("connection-url"))
                OutlinedTextField(edit.username, { model.updateEditor(edit.copy(username = it)) }, label = { Text("사용자 이름") }, singleLine = true, enabled = !edit.busy, modifier = Modifier.fillMaxWidth().testTag("connection-user"))
                OutlinedTextField(edit.password, { model.updateEditor(edit.copy(password = it)) }, label = { Text("비밀번호") }, supportingText = { if (edit.existing != null) Text("비워두면 기존 비밀번호를 유지합니다.") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !edit.busy, modifier = Modifier.fillMaxWidth().testTag("connection-password"))
                if (edit.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                edit.result?.let { Text(it, modifier = Modifier.testTag("connection-result"), style = MaterialTheme.typography.bodyMedium) }
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { model.checkOrSaveConnection(false) }, enabled = !edit.busy, modifier = Modifier.fillMaxWidth().testTag("connection-test")) { Text("연결 확인") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(shape = MaterialTheme.shapes.small, onClick = { model.closeEditor() }) { Text(if (edit.busy) "취소" else "닫기") }
                    Button(shape = MaterialTheme.shapes.small, onClick = { model.checkOrSaveConnection(true) }, enabled = !edit.busy, modifier = Modifier.testTag("connection-save")) { Text("저장") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreviewScreen(preview: PreviewState, model: ExplorerViewModel, backEnabled: Boolean = true) {
    if (preview.entry.isImage) {
        ImagePreviewTransition(preview, model, backEnabled)
        return
    }
    Scaffold(modifier = Modifier.fillMaxSize().testTag("preview"), topBar = {
        TopAppBar(title = { Text(preview.entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) }, navigationIcon = {
            GlassIconButton(Symbols.ArrowBackIosNew, "닫기", { model.closePreview() }, Modifier.padding(start = 8.dp).testTag("close-preview"), iconSize = 18.dp)
        }, actions = { ViewerTrashAction(preview, model) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                preview.loading -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (preview.read == 0L) "파일을 읽고 있어요…" else "${formatBytes(preview.read)}${preview.total?.let { " / ${formatBytes(it)}" }.orEmpty()}")
                    TextButton(shape = MaterialTheme.shapes.small, onClick = { model.closePreview() }) { Text("취소") }
                }
                preview.error != null -> Column(Modifier.padding(20.dp)) {
                    ErrorPanel(preview.error)
                    if (preview.entry.isImage || preview.entry.isText) Button(shape = MaterialTheme.shapes.small, onClick = { model.open(preview.entry) }) { Text("다시 시도") }
                }
                preview.document != null -> TextDocumentView(preview, model)
                preview.pdf != null -> PdfDocumentView(preview, model)
                preview.archive != null -> ArchiveDocumentView(preview, model)
            }
        }
    }
}

internal fun formatBytes(value: Long): String = when {
    value >= MIB -> String.format(Locale.getDefault(), "%.1f MiB", value.toDouble() / MIB)
    value >= 1024 -> String.format(Locale.getDefault(), "%.1f KiB", value / 1024.0)
    else -> "$value B"
}
