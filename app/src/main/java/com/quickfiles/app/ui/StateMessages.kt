package com.quickfiles.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quickfiles.app.ui.icons.Symbols

/** Folder-area states. Each one names the cause and offers a way out. */
internal enum class StateKind(val icon: ImageVector, val title: String, val alarming: Boolean = false) {
    EMPTY(Symbols.FolderOpen, "이 폴더는 비어 있어요"),
    NO_MATCH(Symbols.Search, "검색 결과가 없어요"),
    PERMISSION(Symbols.LockOpen, "휴대폰 파일을 보려면 권한이 필요해요"),
    AUTH(Symbols.KeyOff, "저장소에 로그인하지 못했어요", alarming = true),
    CERTIFICATE(Symbols.GppBad, "안전한 연결을 확인할 수 없어요", alarming = true),
    NETWORK(Symbols.CloudOff, "저장소에 연결할 수 없어요"),
    FAILED(Symbols.Error, "폴더를 열지 못했어요", alarming = true),
}

/** Classifies a readable storage error. Messages come from the storage providers' fixed wording. */
internal fun errorKind(message: String): StateKind = when {
    "인증서" in message -> StateKind.CERTIFICATE
    "인증에 실패" in message || "비밀번호" in message -> StateKind.AUTH
    "권한" in message || "보호하는 폴더" in message -> StateKind.PERMISSION
    "연결하지 못" in message || "네트워크" in message || "HTTP" in message || "끝까지 읽지 못" in message -> StateKind.NETWORK
    else -> StateKind.FAILED
}

internal class StateAction(val label: String, val tag: String? = null, val onClick: () -> Unit)

@Composable
internal fun StateMessage(kind: StateKind, body: String, primary: StateAction? = null, secondary: StateAction? = null) {
    val colors = MaterialTheme.colorScheme
    val (circle, glyph) = when {
        kind.alarming -> colors.errorContainer to colors.onErrorContainer
        kind == StateKind.NETWORK -> colors.surfaceContainerHigh to colors.onSurfaceVariant
        else -> colors.primaryContainer to colors.onPrimaryContainer
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp).testTag("state-${kind.name.lowercase()}"),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).background(circle, CircleShape), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(kind.icon, null, Modifier.size(36.dp), tint = glyph)
        }
        Spacer(Modifier.height(16.dp))
        Text(kind.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
        if (primary != null || secondary != null) Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            primary?.let { Button(it.onClick, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp).then(it.tag?.let { tag -> Modifier.testTag(tag) } ?: Modifier)) { Text(it.label) } }
            secondary?.let { TextButton(it.onClick, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp).then(it.tag?.let { tag -> Modifier.testTag(tag) } ?: Modifier)) { Text(it.label) } }
        }
    }
}

/** Reading a folder: progress, what is happening, and cancel. */
@Composable
internal fun LoadingState(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("폴더를 읽고 있어요…", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onCancel, shape = CircleShape) { Text("취소") }
        }
        // Placeholder tiles keep the layout from jumping when the first items arrive.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1f, .7f, .45f).forEach { alpha ->
                Box(Modifier.weight(1f).aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha), MaterialTheme.shapes.medium))
            }
        }
    }
}
