package com.quickfiles.app.playback

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerOptionsDialog(connection: PlaybackConnection, title: String, navigate: (String) -> Unit, close: () -> Unit, onStop: () -> Unit) {
    val detail by connection.details.collectAsStateWithLifecycle()
    val session by connection.sessionState.collectAsStateWithLifecycle()
    val fill by connection.options.fill.collectAsStateWithLifecycle()
    val player = connection.controller
    val scope = rememberCoroutineScope()
    val contentScroll = rememberScrollState()
    var cp949 by remember(detail.mediaId) { mutableStateOf(connection.options.choices[detail.mediaId]?.cp949 ?: false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(title) { contentScroll.scrollTo(0) }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).weight(1f, fill = false)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = close) { Text("닫기") }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(contentScroll).padding(bottom = 16.dp)) {
            if (title == "재생 옵션") {
                Text("재생 속도")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    (2..8).forEach { step -> val speed = step / 4f
                        FilterChip(detail.speed == speed, { player?.setPlaybackSpeed(speed) }, label = { Text("${speed}×") }, modifier = Modifier.padding(end = 6.dp).testTag("speed-$speed"))
                    }
                }
                OptionChoice("화면 맞춤", !fill) { connection.options.fill.value = false }
                OptionChoice("화면 채움", fill) { connection.options.fill.value = true }
                listOf("반복 없음", "한 파일 반복", "전체 반복").forEachIndexed { index, label -> OptionChoice(label, detail.repeat == index) { player?.repeatMode = index } }
                TextButton(onClick = { navigate("자막") }) { Text("자막") }
                TextButton(onClick = { navigate("오디오") }) { Text("오디오") }
                TextButton(onClick = { navigate("취침 타이머") }, modifier = Modifier.testTag("player-sleep-timer-entry")) { Text("취침 타이머") }
                TextButton(onClick = { player?.volume = if (detail.muted) 1f else 0f }, modifier = Modifier.testTag("player-mute")) { Text(if (detail.muted) "음소거 해제" else "음소거") }
                TextButton(onClick = onStop, modifier = Modifier.testTag("player-stop")) { Text("재생 정지") }
                Text("화면 좌우 드래그로 시간 이동 · 드래그 중 아래로 내리면 정밀 탐색", style = MaterialTheme.typography.bodySmall)
            } else if (title == "취침 타이머") {
                SleepTimerMenu(connection, detail, session)
            } else {
                val type = if (title == "자막") C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
                val choice = connection.options.choices[detail.mediaId]
                OptionChoice(if (type == C.TRACK_TYPE_TEXT) "자막 끄기" else "자동 선택", if (type == C.TRACK_TYPE_TEXT) choice?.textDisabled == true else choice?.audio == null) { connection.selectTrack(type, null) }
                detail.tracks.groups.forEachIndexed { groupIndex, group -> if (group.type == type) {
                    (0 until group.length).forEach { trackIndex ->
                        val supported = group.isTrackSupported(trackIndex)
                        OptionChoice(trackLabel(group.getTrackFormat(trackIndex), trackIndex) + if (supported) "" else " · 지원하지 않음", group.isTrackSelected(trackIndex), supported) { connection.selectTrack(type, groupIndex, trackIndex) }
                    }
                } }
                if (type == C.TRACK_TYPE_TEXT) {
                    Text("같은 폴더의 외부 자막", Modifier.padding(top = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cp949, { cp949 = it }, enabled = !loading, modifier = Modifier.semantics { contentDescription = "CP949로 읽기" }); Text("CP949로 읽기") }
                    val entries = connection.options.subtitles[detail.mediaId].orEmpty()
                    if (entries.isEmpty()) Text("SRT·VTT 자막 파일이 없습니다.")
                    entries.forEach { entry -> TextButton(enabled = !loading, onClick = {
                        scope.launch { loading = true; error = null
                            try { connection.attachSubtitle(entry, cp949) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "자막을 읽지 못했습니다." }
                            finally { loading = false }
                        }
                    }) { Text(entry.name) } }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            }
        }
    }
}

@Composable
private fun SleepTimerMenu(connection: PlaybackConnection, detail: PlaybackDetails, session: PlaybackSessionState) {
    val scope = rememberCoroutineScope()
    var customMinutes by rememberSaveable(detail.mediaId) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val running = timerLabel(session)
    if (running.isNotEmpty()) {
        Text("실행 중: $running", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("sleep-timer-running"))
        TextButton(enabled = !loading, onClick = {
            scope.launch { loading = true; error = null; try { if (!connection.clearSleepTimer()) error = "타이머를 해제하지 못했습니다." } finally { loading = false } }
        }, modifier = Modifier.testTag("sleep-timer-clear")) { Text("해제") }
    } else {
        Text("재생 중인 파일을 기준으로 자동 종료합니다.", style = MaterialTheme.typography.bodyMedium)
    }
    Text("빠른 설정", Modifier.padding(top = 12.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        listOf(15, 30, 60).forEach { minutes ->
            OutlinedButton(enabled = !loading, onClick = {
                scope.launch { loading = true; error = null; try { if (!connection.setSleepTimerMinutes(minutes)) error = "타이머를 설정하지 못했습니다." } finally { loading = false } }
            }, modifier = Modifier.padding(end = 8.dp).testTag("sleep-timer-$minutes")) { Text("${minutes}분") }
        }
    }
    val endEnabled = detail.mediaId.isNotBlank() && detail.duration > 0
    OutlinedButton(enabled = endEnabled && !loading, onClick = {
        scope.launch { loading = true; error = null; try { if (!connection.setSleepTimerEndOfItem()) error = "현재 파일 끝 타이머를 설정하지 못했습니다." } finally { loading = false } }
    }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("sleep-timer-end")) { Text("현재 파일 끝") }
    if (!endEnabled) Text("재생 시간이 확인된 파일에서만 사용할 수 있습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(value = customMinutes, onValueChange = { value ->
        customMinutes = value.filter(Char::isDigit).take(3)
        error = null
    }, label = { Text("직접 설정 (1~720분)") }, singleLine = true, enabled = !loading,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("sleep-timer-custom-input"))
    TextButton(enabled = !loading, onClick = {
        val minutes = customMinutes.toIntOrNull()
        if (minutes == null || minutes !in 1..720) {
            error = "1~720분 사이의 숫자를 입력해 주세요."
        } else {
            scope.launch { loading = true; error = null; try { if (!connection.setSleepTimerMinutes(minutes)) error = "타이머를 설정하지 못했습니다." } finally { loading = false } }
        }
    }, modifier = Modifier.fillMaxWidth().testTag("sleep-timer-custom-submit")) { Text("설정") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sleep-timer-error")) }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
}

@Composable
private fun OptionChoice(label: String, selected: Boolean, enabled: Boolean = true, select: () -> Unit) {
    Surface(onClick = select, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { this.selected = selected }, color = Color.Transparent) {
        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, null, enabled = enabled); Text(label, Modifier.padding(start = 8.dp), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
