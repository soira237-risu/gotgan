package com.quickfiles.app.ui

import com.quickfiles.app.ui.icons.Symbols
import androidx.compose.animation.*

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickfiles.app.data.*
import com.quickfiles.app.operations.ConflictPolicy
import com.quickfiles.app.operations.OperationEngine
import com.quickfiles.app.playback.PlayerActivity
import com.quickfiles.app.storage.*
import com.quickfiles.app.ui.theme.LocalBackdrop
import com.quickfiles.app.ui.theme.LocalGlass
import com.quickfiles.app.ui.theme.backdropBlur
import com.quickfiles.app.ui.theme.backdropSource
import com.quickfiles.app.ui.theme.glass
import com.quickfiles.app.ui.theme.rememberBackdrop
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkspaceScreen(first: ExplorerViewModel, second: ExplorerViewModel, hasPermission: Boolean, settingsUnavailable: Boolean, onSettings: () -> Unit) {
    val controller = first.graph.workspace
    val workspace by controller.state.collectAsStateWithLifecycle()
    val firstState by first.state.collectAsStateWithLifecycle(); val secondState by second.state.collectAsStateWithLifecycle()
    val models = listOf(first, second); val states = listOf(firstState, secondState)
    val active = models[workspace.active]; val state = states[workspace.active]
    val player by first.graph.playback.state.collectAsStateWithLifecycle()
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var command by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionMenu by remember { mutableStateOf(false) }
    val openPanel: (String) -> Unit = { if (it == "재생 중") context.startActivity(Intent(context, PlayerActivity::class.java)) else controller.panel(it) }
    val previewOwner = workspace.viewerOwner?.takeIf { states[it].preview != null } ?: states.indexOfFirst { it.preview != null }.takeIf { it >= 0 }
    LaunchedEffect(firstState.preview?.entry?.id, secondState.preview?.entry?.id) { controller.viewer(previewOwner) }
    val panelDepth = remember { mutableFloatStateOf(0f) }
    val towardStart = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    // While a full panel comes in, this screen steps back a quarter of its width and dims slightly.
    BoxWithConstraints(Modifier.fillMaxSize()
        .graphicsLayer { translationX = .25f * size.width * panelDepth.floatValue * towardStart }
        .drawWithContent { drawContent(); if (panelDepth.floatValue > 0f) drawRect(Color.Black, alpha = .12f * panelDepth.floatValue) }
        .background(MaterialTheme.colorScheme.background)
        .then(if (previewOwner == null) Modifier.safeDrawingPadding() else Modifier).imePadding()) {
        val canSplit = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE && maxWidth >= 720.dp
        val split = canSplit && workspace.split
        if (workspace.ready) {
            val headerStates = listOf(rememberTopAppBarState(), rememberTopAppBarState())
            val selecting = state.selected.isNotEmpty()
            val showPlayer = player.visible && !selecting && workspace.pending == null
            // Lists scroll under the floating dock; keep their last row reachable above it.
            val bottomInset = (16 + 64 + 24 + (if (showPlayer) 64 else 0) + (if (workspace.pending != null) 56 else 0)).dp
            // Two thresholds, so a header resting near half-collapsed cannot make the dock flicker between shapes.
            var collapsed by remember { mutableStateOf(false) }
            LaunchedEffect(workspace.active) {
                snapshotFlow { headerStates[workspace.active].collapsedFraction }.collect { fraction ->
                    if (fraction > .6f) collapsed = true else if (fraction < .4f) collapsed = false
                }
            }
            // The preparation pill keeps its text while it shrinks away.
            var lastPending by remember { mutableStateOf(workspace.pending) }
            SideEffect { if (workspace.pending != null) lastPending = workspace.pending }
            // The panes are recorded once per frame so the floating dock can blur what scrolls beneath it.
            val backdrop = rememberBackdrop(MaterialTheme.colorScheme.background)
            Box(Modifier.fillMaxSize().then(if (previewOwner != null) Modifier.safeDrawingPadding().clearAndSetSemantics {} else Modifier)) {
                Row(Modifier.fillMaxSize().backdropSource(backdrop)) {
                    models.forEachIndexed { index, model ->
                        if (split || index == workspace.active) Box(Modifier.weight(1f).fillMaxHeight()
                            .then(if (split) Modifier.border(1.dp, if (index == workspace.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant) else Modifier)
                            .pointerInput(index) { awaitPointerEventScope { while (true) { if (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed } && controller.state.value.active != index) controller.activate(index) } } }) {
                            ExplorerScreen(model, hasPermission, settingsUnavailable, onSettings, active = workspace.active == index && workspace.panel == null && previewOwner == null,
                                renderPreview = false, onSwitch = { controller.panel("폴더 전환") }, onPanel = openPanel, headerState = headerStates[index], bottomInset = bottomInset)
                        }
                    }
                }
                CompositionLocalProvider(LocalBackdrop provides backdrop) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedVisibility(workspace.pending != null && workspace.panel == null, Modifier.align(Alignment.CenterHorizontally),
                        enter = fadeIn(MaterialMotion.effects()) + expandVertically(MaterialMotion.spatial(), expandFrom = Alignment.Bottom),
                        exit = fadeOut(MaterialMotion.screenExit(100)) + shrinkVertically(MaterialMotion.spatial(), shrinkTowards = Alignment.Bottom)) {
                        val pending = workspace.pending ?: lastPending ?: return@AnimatedVisibility
                        Row(Modifier.height(48.dp).glass(LocalGlass.current, behind = Modifier.backdropBlur())
                            .clickable { controller.panel(if (pending.destination == null) "목적지 선택" else "목적지") }.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${pending.count}개 ${pending.label} 준비 · 목적지 확인", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    val names = states.mapIndexed { index, folder -> folder.location?.let { it.path.substringAfterLast('/').ifEmpty { models[index].sourceName(it.sourceId) } } }
                    val dock = DockModel(names[workspace.active] ?: "저장소", names[1 - workspace.active] ?: "저장소 선택", state.location == null,
                        canSearch = state.location != null && !state.searchOpen)
                    val actions = DockActions(home = { active.home() }, switchPanel = { controller.panel("폴더 전환") }, swap = { controller.activate(1 - workspace.active) },
                        tasks = { controller.panel("작업 요약") }, search = { active.showSearch(true) }, player = { openPanel("재생 중") },
                        toggle = { first.graph.playback.toggle() }, stop = { scope.launch { first.graph.playback.stop() } })
                    val tasks by first.graph.dao.tasks().collectAsStateWithLifecycle(emptyList())
                    val running = tasks.filter { it.state in setOf("RUNNING", "QUEUED", "PREPARING", "PAUSING", "CANCELLING") }
                    val total = running.sumOf { it.totalBytes.coerceAtLeast(0) }
                    val progress = if (running.isEmpty()) null else if (total > 0) running.sumOf { it.bytes }.toFloat() / total else 0f
                    // The dock keeps its place and changes shape: the area eases to the new size while the controls cross-fade.
                    val mode = when {
                        selecting -> DockMode.SELECTING
                        // The dock steps aside while the keyboard is up so search results keep the space.
                        WindowInsets.isImeVisible -> DockMode.HIDDEN
                        collapsed && showPlayer -> DockMode.COMPACT
                        else -> DockMode.EXPANDED
                    }
                    AnimatedContent(mode, Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter, label = "dock", transitionSpec = {
                        (fadeIn(MaterialMotion.effects()) + scaleIn(MaterialMotion.spatial(), initialScale = .92f, transformOrigin = TransformOrigin(.5f, 1f))) togetherWith
                            (fadeOut(MaterialMotion.screenExit(100)) + scaleOut(MaterialMotion.spatial(), targetScale = .92f, transformOrigin = TransformOrigin(.5f, 1f))) using
                            SizeTransform(clip = false) { _, _ -> MaterialMotion.spatial() }
                    }) { shown ->
                        when (shown) {
                            DockMode.SELECTING -> {
                                val selection = active.selection()
                                val canModify = state.writable && selection.all { it.accessError == null && (it.location.sourceId == LOCAL_SOURCE || it.revision.strongEtag != null) }
                                GlassSurface(Modifier.fillMaxWidth().testTag("selection-bar")) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        BarItem(Symbols.ContentCopy, "복사", { controller.prepare(PendingTransfer(workspace.active, selection.map { it.location }, false, states[1 - workspace.active].location)) }, Modifier.weight(1f))
                                        BarItem(Symbols.DriveFileMove, "이동", { controller.prepare(PendingTransfer(workspace.active, selection.map { it.location }, true, states[1 - workspace.active].location)) }, Modifier.weight(1f), enabled = canModify)
                                        BarItem(Symbols.Delete, "휴지통", { command = "휴지통으로 이동" }, Modifier.weight(1f), enabled = canModify)
                                        Box(Modifier.weight(1f)) {
                                            BarItem(Symbols.MoreHoriz, "더보기", { selectionMenu = true }, Modifier.fillMaxWidth())
                                            DropdownMenu(selectionMenu, { selectionMenu = false }) {
                                                DropdownMenuItem({ Text("전체 선택") }, { active.selectAll(); selectionMenu = false })
                                                DropdownMenuItem({ Text("선택 해제") }, { active.clearSelection(); selectionMenu = false })
                                                DropdownMenuItem({ Text("이름 변경") }, { command = "이름 변경"; selectionMenu = false }, enabled = canModify && selection.size == 1)
                                                DropdownMenuItem({ Text("공유") }, { active.shareSelected(); selectionMenu = false }, enabled = selection.none { it.isDirectory })
                                                DropdownMenuItem({ Text("파일 정보") }, { command = "파일 정보"; selectionMenu = false }, enabled = selection.size == 1)
                                                DropdownMenuItem({ Text("ZIP 만들기") }, { command = "ZIP 만들기"; selectionMenu = false }, enabled = state.writable)
                                                if (!canModify) Text("권한 또는 서버 변경 확인 기능이 필요합니다", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                            DockMode.HIDDEN -> Spacer(Modifier.fillMaxWidth())
                            DockMode.COMPACT -> CompactDock(dock, actions, player)
                            DockMode.EXPANDED -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AnimatedVisibility(showPlayer, enter = fadeIn(MaterialMotion.effects()) + expandVertically(MaterialMotion.spatial(), expandFrom = Alignment.Bottom),
                                    exit = fadeOut(MaterialMotion.screenExit(100)) + shrinkVertically(MaterialMotion.spatial(), shrinkTowards = Alignment.Bottom)) {
                                    MiniPlayer(player, actions)
                                }
                                ExpandedDock(dock, actions, running.size, progress, compact = collapsed) { dismiss ->
                                    ExplorerMenuItems(state, active, openPanel, { name -> command = name }) { action -> dismiss(); action() }
                                }
                            }
                        }
                    }
                }
                }
            }
            previewOwner?.let { owner -> states[owner].preview?.let { preview -> BackHandler(workspace.panel == null && !preview.entry.isImage) { models[owner].closePreview() }; PreviewScreen(preview, models[owner], backEnabled = workspace.panel == null) } }
        }
        FileActions(state, active, command, { command = null })
        CompositionLocalProvider(LocalPanelDepth provides panelDepth) {
        when (workspace.panel) {
            "폴더 전환" -> WorkspaceSheet("폴더 전환", onDismissRequest = { controller.panel(null) }) {
                Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState())) {
                    states.forEachIndexed { index, folder ->
                        val location = folder.location
                        val dismissSheet = LocalSheetDismiss.current
                        Surface(onClick = { controller.activate(index); dismissSheet { controller.panel(null) } }, shape = RoundedCornerShape(8.dp), color = if (workspace.active == index) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (workspace.active == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("workspace-location-$index")) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(location?.path?.substringAfterLast('/')?.ifEmpty { models[index].sourceName(location.sourceId) } ?: "저장소 선택", style = MaterialTheme.typography.titleMedium); location?.let { Text("${models[index].sourceName(it.sourceId)} / ${it.path}", style = MaterialTheme.typography.bodySmall) } }
                                if (index == workspace.active) Icon(Symbols.Check, "현재 폴더")
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("나란히 보기"); Text(if (canSplit) "두 폴더를 같은 크기로 표시합니다" else "가로 화면 · 720dp 이상에서 사용", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Switch(workspace.split, { controller.split(it) }, enabled = canSplit, modifier = Modifier.semantics { contentDescription = "나란히 보기" })
                    }
                }
            }
            "목적지" -> workspace.pending?.let { pending -> DestinationPanel(pending, models[pending.owner], { controller.panel(null) }, { controller.panel("목적지 선택") }, controller::cancelPreparation, controller::policy) { policy ->
                if (controller.state.value.pending?.submitting == false) {
                    controller.submitting(true)
                    if (pending.archiveEntries != null) models[pending.owner].enqueue("EXTRACT", pending.sources, pending.destination!!.child(pending.outputFolder), extra = org.json.JSONObject().put("entries", org.json.JSONArray(pending.archiveEntries)), onQueued = { controller.cancelPreparation() }, onFinished = { controller.submitting(false) })
                    else models[pending.owner].enqueue(if (pending.move) "MOVE" else "COPY", pending.sources, pending.destination, policy, onQueued = { controller.cancelPreparation() }, onFinished = { controller.submitting(false) })
                }
            } }
            "목적지 선택" -> workspace.pending?.let { pending -> DestinationPicker(models[pending.owner], pending.destination, { controller.panel("목적지") }, controller::destination) }
            "작업 요약" -> WorkspaceSheet("작업", onDismissRequest = { controller.panel(null) }) { TaskSummary(active) { controller.panel("작업") } }
            "작업", "휴지통", "설정" -> FullPanel(workspace.panel!!, { controller.panel(null) }) { when (workspace.panel) { "작업" -> TasksPanel(active); "휴지통" -> TrashPanel(active); else -> SettingsPanel(active) } }
        }
        }
    }
}

/** How far a full panel has come in, 0..1, so the screen beneath can step back while it does. */
internal val LocalPanelDepth = staticCompositionLocalOf<MutableFloatState?> { null }

/** One level deeper in the app: the panel slides in from the end edge and the screen beneath steps back.
 * A predictive back swipe shrinks and rounds the panel before it leaves the way it came. */
@Composable
internal fun FullPanel(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val enter = remember { Animatable(0f) }
    var back by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val closeRequest by rememberUpdatedState(close)
    val depth = LocalPanelDepth.current
    fun dismiss() {
        if (closing) return
        closing = true
        scope.launch { enter.animateTo(0f, MaterialMotion.screenExit(250)); closeRequest() }
    }
    LaunchedEffect(Unit) { enter.animateTo(1f, MaterialMotion.screenEnter()) }
    LaunchedEffect(depth) { snapshotFlow { enter.value * (1f - .3f * back) }.collect { depth?.floatValue = it } }
    DisposableEffect(depth) { onDispose { depth?.floatValue = 0f } }
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Edge to edge without the dialog dim, so the status bar shows the panel's own background.
        val view = androidx.compose.ui.platform.LocalView.current
        val light = MaterialTheme.colorScheme.background.luminance() > .5f
        SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                window.setWindowAnimations(0)
                window.setDimAmount(0f)
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
            }
        }
        // Keep this callback enabled through its committed animation, as the viewer's does.
        PredictiveBackHandler { events ->
            try {
                events.collect { back = it.progress.coerceIn(0f, 1f) }
                dismiss()
            } catch (cancelled: CancellationException) {
                scope.launch { animate(back, 0f, animationSpec = MaterialMotion.spatial()) { value, _ -> back = value } }
                throw cancelled
            }
        }
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val corner = with(LocalDensity.current) { 28.dp.toPx() }
        Surface(Modifier.fillMaxSize().graphicsLayer {
            translationX = size.width * (1f - enter.value) * (if (rtl) -1f else 1f)
            scaleX = 1f - .1f * back; scaleY = scaleX
            transformOrigin = TransformOrigin(if (rtl) 0f else 1f, .5f)
            shape = RoundedCornerShape(corner * back); clip = back > 0f
            shadowElevation = if (enter.value < 1f || back > 0f) 12f else 0f
        }, color = MaterialTheme.colorScheme.background) { Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.headlineLarge)
                GlassIconButton(Symbols.Close, "닫기", ::dismiss, size = 44.dp, iconSize = 20.dp)
            }
            content()
        } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationPanel(pending: PendingTransfer, model: ExplorerViewModel, collapse: () -> Unit, choose: () -> Unit, cancel: () -> Unit, setPolicy: (ConflictPolicy) -> Unit, execute: (ConflictPolicy) -> Unit) {
    val policy = pending.policy
    var writable by remember(pending.destination) { mutableStateOf(false) }
    var error by remember(pending.destination) { mutableStateOf<String?>(null) }
    LaunchedEffect(pending.destination) { try { writable = pending.destination?.let { model.provider(it.sourceId).capabilities.writable } ?: false } catch (e: CancellationException) { throw e } catch (e: Exception) { error = readableError(e) } }
    WorkspaceSheet("${pending.count}개 ${pending.label}", onDismissRequest = collapse) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(pending.destination?.let { "${model.sourceName(it.sourceId)} / ${it.path}" } ?: "저장소를 선택해 주세요")
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = choose, enabled = !pending.submitting) { Text("목적지 변경") }
            if (pending.archiveEntries != null) Text("새 폴더: ${pending.outputFolder}\n기존 파일은 덮어쓰지 않습니다.")
            else Text("같은 이름이 있을 때", style = MaterialTheme.typography.titleMedium)
            if (pending.archiveEntries == null) listOf(ConflictPolicy.SKIP to "건너뛰기 (기본)", ConflictPolicy.RENAME to "새 이름으로 보관", ConflictPolicy.REPLACE to "파일 교체 · 기존 파일은 휴지통 보관", ConflictPolicy.MERGE to "폴더 병합 · 같은 파일은 건너뛰기").forEach { (value, label) ->
                ChoiceRow(label, policy == value, { setPolicy(value) }, enabled = !pending.submitting)
            }
            error?.let { ErrorPanel(it) }
            if (!writable && pending.destination != null) Text("이 위치에는 쓸 수 없습니다. 다른 목적지를 선택해 주세요.", style = MaterialTheme.typography.bodySmall)
            Button(shape = MaterialTheme.shapes.small, onClick = { execute(policy) }, enabled = writable && !pending.submitting, modifier = Modifier.fillMaxWidth()) { Text(if (pending.submitting) "작업 기록 중…" else if (pending.archiveEntries != null) "여기에 압축 해제" else if (pending.move) "여기로 이동" else "여기에 복사") }
            val dismissSheet = LocalSheetDismiss.current
            Row { TextButton(shape = MaterialTheme.shapes.small, onClick = { dismissSheet(collapse) }) { Text("접기") }; TextButton(shape = MaterialTheme.shapes.small, onClick = { dismissSheet(cancel) }, enabled = !pending.submitting) { Text("준비 취소") } }
        }
    }
}

@Composable
private fun DestinationPicker(model: ExplorerViewModel, initial: StorageLocation?, close: () -> Unit, choose: (StorageLocation) -> Unit) {
    var encoded by rememberSaveable { mutableStateOf(initial?.encode()) }
    val location = remember(encoded) { encoded?.let(::decodeLocation) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var writable by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val state by model.state.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    LaunchedEffect(location, refresh) { withContext(Dispatchers.Main.immediate) {
        entries = emptyList(); error = null; writable = false
        if (location != null) {
            loading = true
            try { val provider = model.provider(location.sourceId); val listing = provider.list(location); entries = withContext(Dispatchers.Default) { sortedEntries(listing.filter { it.isDirectory && !OperationEngine.isManagedName(it.name) }, SortOrder.NAME) }; writable = provider.capabilities.writable }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = readableError(e) } finally { loading = false }
        }
    } }
    FullPanel("목적지 선택", close) {
        BackHandler(location != null) { encoded = location?.parent()?.encode() }
        Row(verticalAlignment = Alignment.CenterVertically) { if (location != null) ToolIcon(Symbols.ArrowBackIosNew, "상위 폴더", { encoded = location.parent()?.encode() }); Text(location?.let { "${model.sourceName(it.sourceId)} / ${it.path}" } ?: "저장소", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorPanel(it); TextButton(shape = MaterialTheme.shapes.small, onClick = { refresh++ }) { Text("다시 시도") } }
        BoxWithConstraints(Modifier.weight(1f)) {
            val width = maxWidth.value
            val columns = if (settings.grid) gridColumns(width, LocalDensity.current.fontScale, settings.density) else 1
            val places = remember(location, entries, state.profiles, settings.showHidden) {
                if (location == null) listOf(PlaceItem(LOCAL_SOURCE, "휴대폰 저장소", "", StorageLocation(LOCAL_SOURCE))) + state.profiles.map { PlaceItem(it.id, it.name, "WebDAV", StorageLocation(it.id)) }
                else entries.filter { settings.showHidden || !it.name.startsWith('.') }.map { PlaceItem(it.id, it.name, "", it.location) }
            }
            Column {
                ViewControls(model, width)
                LazyVerticalGrid(GridCells.Fixed(columns), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp), verticalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp)) {
                    items(places, key = { it.id }) { place -> PlaceCell(place, settings.grid, { encoded = place.location!!.encode() }, {}, listDensity = settings.listDensity) }
                }
            }
        }
        Button(shape = MaterialTheme.shapes.small, onClick = { location?.let(choose) }, enabled = location != null && writable && !loading && error == null, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("이 폴더 선택") }
    }
}

private enum class DockMode { EXPANDED, COMPACT, SELECTING, HIDDEN }
