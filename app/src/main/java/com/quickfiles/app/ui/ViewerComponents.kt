package com.quickfiles.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.stateDescription
import com.quickfiles.app.ui.theme.GlassLevel
import com.quickfiles.app.ui.theme.LocalGlass
import com.quickfiles.app.ui.theme.glass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.quickfiles.app.data.SavedValue
import com.quickfiles.app.data.TextPaper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import com.quickfiles.app.storage.*
import com.quickfiles.app.viewer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.quickfiles.app.ui.icons.Symbols

@Composable
internal fun TextDocumentView(preview: PreviewState, model: ExplorerViewModel) {
    val doc = preview.document ?: return
    val settings by model.settings.collectAsStateWithLifecycle()
    var search by rememberSaveable(preview.entry.id) { mutableStateOf("") }
    var editing by remember(preview.entry.id) { mutableStateOf(preview.draft != null) }
    var changed by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(preview.entry.name.substringBeforeLast('.') + "_edited.txt") }
    var discard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var text by remember(preview.entry.id, doc) { mutableStateOf(preview.draft ?: preview.decodedText) }
    LaunchedEffect(text, editing) { model.draft(if (editing) text else null) }
    val lines by produceState<List<String>>(emptyList(), preview.decodedText, search) {
        value = withContext(Dispatchers.Default) { preview.decodedText.lineSequence().flatMap { if (it.length > 4096) it.chunked(4096).asSequence() else sequenceOf(it) }.filter { search.isEmpty() || it.contains(search, true) }.toList() }
    }
    Column(Modifier.fillMaxSize()) {
        // Reading controls as one row of chips: encoding and wrap are choices, text size steps with A− / A＋.
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FilterChip(!preview.cp949, { model.encoding(false) }, label = { Text("자동 인코딩") }, shape = CircleShape)
            FilterChip(preview.cp949, { model.encoding(true) }, label = { Text("CP949") }, shape = CircleShape)
            FilterChip(settings.wrapText, { model.saveSettings(settings.copy(wrapText = !settings.wrapText)) }, label = { Text(if (settings.wrapText) "줄바꿈 켬" else "줄바꿈 끔") }, shape = CircleShape)
            AssistChip({ model.updateSettings { it.copy(textSize = (it.textSize - 1).coerceAtLeast(12)) } }, { Text("A−") }, Modifier.semantics { contentDescription = "글자 작게" }, shape = CircleShape)
            AssistChip({ model.updateSettings { it.copy(textSize = (it.textSize + 1).coerceAtMost(32)) } }, { Text("A＋") }, Modifier.semantics { contentDescription = "글자 크게" }, shape = CircleShape)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).semantics { contentDescription = "종이" }, horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("종이", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextPaper.entries.forEach { paper ->
                FilterChip(settings.textPaper == paper, { model.updateSettings { it.copy(textPaper = paper) } }, label = { Text(paper.label) },
                    shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.testTag("text-paper-${paper.name.lowercase()}"),
                    leadingIcon = if (paper == TextPaper.AUTO) null else ({ Box(Modifier.size(14.dp).background(paperColors(paper, Color.White, Color.Black).first, androidx.compose.foundation.shape.CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.CircleShape)) }))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (doc.start > 0) PillButton("이전 부분", { model.readMoreText(previous = true) })
            if (!doc.complete) PillButton("다음 1MiB 이어 읽기", { model.readMoreText() })
            if (preview.entry.isEditableText && (preview.entry.size ?: Long.MAX_VALUE) <= TextDocument.EDIT_LIMIT) {
                if (!doc.complete || doc.start > 0) PillButton("편집용 전체 읽기", { model.readMoreText(true) })
                else PillButton("편집", { text = preview.decodedText; editing = true }, primary = true)
            }
        }
        if (!doc.complete || doc.start > 0) Text("${formatBytes(doc.start)} ~ ${formatBytes(doc.start + doc.bytes.size)} 부분을 읽고 있습니다.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(search, { search = it }, placeholder = { Text("읽은 내용에서 검색") }, singleLine = true, shape = CircleShape, leadingIcon = { Icon(Symbols.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).semantics { contentDescription = "읽은 내용에서 검색" })
        if (search.isNotEmpty()) Text("일치하는 줄 ${lines.size}개", Modifier.padding(horizontal = 16.dp))
        val scroll = remember(preview.entry.id, preview.cp949, search, doc.start) { LazyListState() }
        val (paperColor, body) = paperColors(settings.textPaper, MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.onSurface)
        LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxWidth().background(paperColor).testTag("text-preview").then(if (settings.wrapText) Modifier else Modifier.horizontalScroll(rememberScrollState())), contentPadding = PaddingValues(16.dp)) {
            items(lines.size) { index -> Text(lines[index].ifEmpty { " " }, color = body, fontFamily = FontFamily.Monospace, fontSize = settings.textSize.sp, softWrap = settings.wrapText) }
        }
    }
    if (editing) Dialog(onDismissRequest = { if (text != preview.decodedText) discard = true else editing = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) { Column(Modifier.fillMaxSize().imePadding().padding(12.dp)) {
            Text("${preview.entry.name} · ${doc.charset.displayName()}")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { if (text != preview.decodedText) discard = true else editing = false }) { Text("취소") }
                TextButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { scope.launch { try { if (model.textUnchanged()) model.saveText(text) else changed = true } catch (e: Exception) { model.notice(readableError(e)) } } }) { Text("저장") }
                TextButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { scope.launch { try { if (model.textUnchanged()) model.saveText(text, true) else changed = true } catch (e: Exception) { model.notice(readableError(e)) } } }) { Text("UTF-8 저장") }
                TextButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { saveAs = true }) { Text("다른 이름") }
            }
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = "텍스트 편집 내용" }, textStyle = LocalTextStyle.current.copy(fontSize = settings.textSize.sp, fontFamily = FontFamily.Monospace))
        } }
        if (changed) AlertDialog(onDismissRequest = { changed = false }, title = { Text("외부에서 변경된 파일") }, text = { Text("원본을 덮어쓰지 않습니다. 다시 열거나 편집한 내용을 다른 이름으로 저장하세요.") }, confirmButton = { TextButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { changed = false; saveAs = true }) { Text("다른 이름 저장") } }, dismissButton = { TextButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { changed = false; editing = false; model.open(preview.entry) }) { Text("다시 열기") } })
        if (saveAs) AlertDialog(onDismissRequest = { saveAs = false }, title = { Text("다른 이름으로 저장") }, text = { OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("새 파일 이름") }) }, confirmButton = { TextButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { model.saveText(text, newName = newName); saveAs = false }) { Text("저장") } }, dismissButton = { TextButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { saveAs = false }) { Text("취소") } })
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("편집 내용 버리기") }, text = { Text("저장하지 않은 변경 내용을 버릴까요?") }, confirmButton = { TextButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { discard = false; editing = false }) { Text("버리기") } }, dismissButton = { TextButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { discard = false }) { Text("계속 편집") } })
    }
}

/**
 * Paper and ink for text files. Dark papers lower body contrast slightly (86%) to reduce halation,
 * staying well above 4.5:1.
 */
internal fun paperColors(paper: TextPaper, themeBackground: Color, themeInk: Color): Pair<Color, Color> = when (paper) {
    TextPaper.AUTO -> themeBackground to if (themeBackground.luminance() < .5f) themeInk.copy(alpha = .86f) else themeInk
    TextPaper.LIGHT -> Color(0xFFFAF9F5) to Color(0xFF30251F)
    TextPaper.DARK -> Color(0xFF1B1714) to Color(0xFFF3E9DE).copy(alpha = .86f)
    TextPaper.BLACK -> Color.Black to Color(0xFFF5F5F5).copy(alpha = .86f)
}

internal enum class PageTone(val label: String, val filter: ColorFilter?) {
    ORIGINAL("원본", null),
    DIM("밝기 낮춤", ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(.78f, .78f, .76f, 1f) })),
    INVERT("색 반전", ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(-.9f, 0f, 0f, 0f, 235f, 0f, -.9f, 0f, 0f, 235f, 0f, 0f, -.9f, 0f, 235f, 0f, 0f, 0f, 1f, 0f)))),
}

@Composable
internal fun PdfDocumentView(preview: PreviewState, model: ExplorerViewModel) {
    val pdf = preview.pdf ?: return
    val list = remember(preview.entry.id) { LazyListState() }
    val currentPage by remember { derivedStateOf { list.firstVisibleItemIndex + 1 } }
    var page by remember { mutableStateOf("1") }
    var scale by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    // White pages glare in dark mode: default to dimmed pages there, with original and inverted options.
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    var tone by rememberSaveable(dark) { mutableStateOf(if (dark) PageTone.DIM else PageTone.ORIGINAL) }
    LaunchedEffect(preview.entry.id) {
        val saved = model.graph.dao.saved("pdf:${preview.entry.id}")?.value?.toIntOrNull() ?: 0
        if (pdf.pages > 0) list.scrollToItem(saved.coerceIn(0, pdf.pages - 1))
        snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().collect { index -> model.graph.dao.save(SavedValue("pdf:${preview.entry.id}", index.toString())) }
    }
    // Toolbar: page jump, zoom and page tone as capsules, matching the other viewers.
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(page, { page = it.filter(Char::isDigit) }, label = { Text("${currentPage} / ${pdf.pages}") }, singleLine = true, shape = CircleShape,
            modifier = Modifier.width(112.dp).testTag("pdf-page-input").semantics { contentDescription = "이동할 PDF 페이지" })
        PillButton("이동", { scope.launch { list.animateScrollToItem(((page.toIntOrNull() ?: 1) - 1).coerceIn(0, (pdf.pages - 1).coerceAtLeast(0))) } })
        GlassIconButton(Symbols.ZoomOut, "PDF 축소", { scale = (scale - 0.25f).coerceAtLeast(1f) }, enabled = scale > 1f)
        GlassIconButton(Symbols.ZoomIn, "PDF 확대", { scale = (scale + 0.25f).coerceAtMost(3f) }, enabled = scale < 3f)
        PillButton(tone.label, { tone = PageTone.entries[(tone.ordinal + 1) % PageTone.entries.size] },
            Modifier.testTag("pdf-page-tone").semantics { contentDescription = "페이지 밝기: ${tone.label}" }, icon = Symbols.Contrast)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Pages fit the screen width at 1x and stay centred; zooming widens them and scrolls sideways.
    val pageWidth = (maxWidth - 24.dp) * scale
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("pdf-pages"), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)) {
        items(pdf.pages) { index ->
            var bitmap by remember(pdf, index) { mutableStateOf<android.graphics.Bitmap?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(pdf, index) { try { bitmap = pdf.render(index, 1440) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = "페이지를 읽지 못했습니다. 파일 또는 연결을 확인해 주세요." } }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
                bitmap?.let { image ->
                    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                        AsyncImage(image, "PDF ${index + 1}페이지", imageLoader = model.graph.imageLoader, modifier = Modifier.padding(horizontal = 12.dp).width(pageWidth).height(pageWidth * image.height / image.width)
                            .clip(MaterialTheme.shapes.extraSmall).border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall),
                            contentScale = ContentScale.FillBounds, colorFilter = tone.filter)
                    }
                } ?: Box(Modifier.fillMaxWidth().height(480.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) }
                Text("${index + 1} / ${pdf.pages}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    }
}

/**
 * Page slider for comic archives. The page bubble shows only while the thumb is dragged;
 * the page opens once on release so dragging never decodes every page in between.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageScrubber(page: Int, pages: Int, onPage: (Int) -> Unit) {
    var dragging by remember(page) { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: page.toFloat()).let { kotlin.math.round(it).toInt() }
    val glass = LocalGlass.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        // Slider track: row padding 16 + label 28 + gap 8 + thumb inset 10 on each side.
        val trackStart = 62.dp
        val track = maxWidth - trackStart * 2
        Column {
            Box(Modifier.height(44.dp).fillMaxWidth()) {
                if (dragging != null) {
                    val fraction = if (pages > 1) shown / (pages - 1f) else 0f
                    Box(Modifier.offset(x = trackStart + track * fraction - 32.dp).width(64.dp).height(40.dp)
                        .glass(glass, MaterialTheme.shapes.medium, GlassLevel.THICK).semantics { contentDescription = "${shown + 1}쪽" },
                        contentAlignment = Alignment.Center) {
                        Text("${shown + 1}쪽", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(52.dp).glass(glass).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${shown + 1}", Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium)
                Slider(dragging ?: page.toFloat(), { dragging = it }, Modifier.weight(1f).testTag("archive-page-slider")
                    .semantics { contentDescription = "쪽 이동"; stateDescription = "${shown + 1}쪽 / ${pages}쪽" },
                    valueRange = 0f..(pages - 1).toFloat().coerceAtLeast(1f),
                    onValueChangeFinished = { val target = shown; dragging = null; if (target != page) onPage(target) })
                Text("$pages", Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ArchiveDocumentView(preview: PreviewState, model: ExplorerViewModel) {
    val archive = preview.archive ?: return
    val selected = preview.archiveSelected
    val members = remember(archive) { archive.entries.associateBy { it.name } }
    val current = members[preview.archiveCurrent]
    var bytes by remember(current) { mutableStateOf<ByteArray?>(null) }
    var error by remember(current) { mutableStateOf<String?>(null) }
    var cp949 by remember { mutableStateOf(false) }
    var extract by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf(preview.entry.name.substringBeforeLast('.') + "_extracted") }
    val images = remember(archive) { archive.entries.filter { !it.isDirectory && FileFormats.isImage(it.name) } }
    BackHandler(current != null) { model.archivePage(null) }
    LaunchedEffect(current) {
        val item = current ?: return@LaunchedEffect
        try {
            if (!FileFormats.isImage(item.name) && !FileFormats.isText(item.name)) { error = "압축파일 안에서는 지원되는 이미지와 텍스트를 볼 수 있습니다."; return@LaunchedEffect }
            bytes = archive.preview(item, if (FileFormats.isText(item.name)) TextDocument.EDIT_LIMIT else IMAGE_LIMIT.toInt())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = readableError(e) }
    }
    Column(Modifier.fillMaxSize()) {
        if (current != null) {
            val item = current!!
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("내부 목록", { model.archivePage(null) }, icon = Symbols.ViewList)
                val index = images.indexOf(item)
                PillButton("이전", { model.archivePage(images.getOrNull(index - 1)?.name) }, enabled = index > 0, icon = Symbols.ChevronLeft)
                PillButton("다음", { model.archivePage(images.getOrNull(index + 1)?.name) }, enabled = index >= 0 && index + 1 < images.size, icon = Symbols.ChevronRight)
                if (FileFormats.isText(item.name)) PillButton(if (cp949) "CP949" else "자동 인코딩", { cp949 = !cp949 })
            }
            Text(item.name, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val pageIndex = images.indexOf(item)
            if (pageIndex >= 0 && images.size > 1) PageScrubber(pageIndex, images.size) { model.archivePage(images[it].name) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            bytes?.let { content ->
                if (FileFormats.isText(item.name)) {
                    val lines by produceState<List<String>>(emptyList(), content, cp949) {
                        value = withContext(Dispatchers.Default) { TextPreview(content, false).decode(cp949).lineSequence().flatMap { if (it.length > 4096) it.chunked(4096).asSequence() else sequenceOf(it) }.toList() }
                    }
                    LazyColumn { items(lines) { Text(it.ifEmpty { " " }, Modifier.padding(horizontal = 16.dp), fontFamily = FontFamily.Monospace) } }
                } else AnimatedAssetImage(content, model, Modifier.fillMaxWidth().weight(1f), sourceName = item.name)
            } ?: if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else Unit
        } else {
            // Members read like the trash list: name and size on the left, the selection circle on the right; tapping the name opens it.
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val all = selected.size == archive.entries.size && archive.entries.isNotEmpty()
                PillButton(if (all) "선택 해제" else "전체 선택", { model.archiveSelection(if (all) emptySet() else archive.entries.map { it.name }.toSet()) })
                PillButton(if (selected.isEmpty()) "압축 해제" else "${selected.size}개 압축 해제", { extract = true }, enabled = selected.isNotEmpty(), primary = true, icon = Symbols.FolderZip)
            }
            LazyColumn(Modifier.padding(horizontal = 12.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large),
                contentPadding = PaddingValues(vertical = 4.dp)) { items(archive.entries, key = { it.name }) { item ->
                val checked = item.name in selected
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable(enabled = !item.isDirectory) { model.archivePage(item.name) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(when { item.isDirectory -> Symbols.FolderFilled; FileFormats.isImage(item.name) -> Symbols.Image; FileFormats.isText(item.name) -> Symbols.Draft; else -> Symbols.Draft },
                            null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(item.name.trimEnd('/').substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val folder = item.name.trimEnd('/').substringBeforeLast('/', "")
                            Text(listOfNotNull(folder.ifEmpty { null }, if (item.isDirectory) "폴더" else item.size.takeIf { it >= 0 }?.let(::formatBytes)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(Modifier.size(56.dp).toggleable(checked, role = Role.Checkbox, onValueChange = { model.archiveSelection(if (it) selected + item.name else selected - item.name) })
                        .semantics { contentDescription = "${item.name} 선택" }, contentAlignment = Alignment.Center) {
                        if (checked) Box(Modifier.size(26.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Symbols.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        } else Box(Modifier.size(26.dp).border(1.8.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    }
                }
                if (item != archive.entries.last()) GroupDivider()
            } }
        }
    }
    if (extract) AlertDialog(onDismissRequest = { extract = false }, title = { Text("새 폴더에 압축 해제") }, text = { OutlinedTextField(folder, { folder = it }, label = { Text("만들 폴더 이름") }) }, confirmButton = { TextButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = {
        runCatching { requireFileName(folder); model.graph.workspace.prepare(PendingTransfer(if (model.tabId == "A") 0 else 1, listOf(preview.entry.location), false, preview.entry.location.parent(), selected.toList(), folder)) }.onFailure { model.notice(it.message) }
        extract = false
    }) { Text("목적지 확인") } }, dismissButton = { TextButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { extract = false }) { Text("취소") } })
}
