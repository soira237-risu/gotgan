package com.quickfiles.app.ui

import com.quickfiles.app.ui.icons.Symbols
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.BackEventCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animate
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import com.quickfiles.app.ui.theme.LocalGlass
import com.quickfiles.app.ui.theme.glass
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.quickfiles.app.R
import com.quickfiles.app.data.*
import com.quickfiles.app.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withPermit
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ToolIcon(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    IconButton(onClick, modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), enabled = enabled) { Icon(icon, label, Modifier.size(24.dp)) }
}

/** Distance a folder slides in the shared-axis change, and how far a back swipe lets it peek. */
private const val FOLDER_SHIFT_DP = 28
private const val BACK_PEEK_DP = 16f

/** The last state of the folder on screen, kept drawing while it steps aside for the next one. */
private class FolderFrame {
    var last: ExplorerState? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(model: ExplorerViewModel, hasPermission: Boolean, settingsUnavailable: Boolean, onSettings: () -> Unit,
    active: Boolean = true, renderPreview: Boolean = true, onSwitch: () -> Unit = {}, onPanel: (String) -> Unit = {},
    headerState: TopAppBarState = rememberTopAppBarState(), bottomInset: Dp = 24.dp) {
    val live by model.state.collectAsStateWithLifecycle()
    // When the folder changes, the old one keeps drawing from its last state until it has stepped aside.
    val folderFrame = remember { FolderFrame() }
    var shownLocation by remember { mutableStateOf(live.location) }
    if (live.location == shownLocation) folderFrame.last = live
    val state = if (live.location == shownLocation) live else folderFrame.last ?: live
    // Large title collapses while content scrolls down and expands again at the top.
    val headerBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(headerState)
    LaunchedEffect(state.location) { headerState.heightOffset = 0f; headerState.contentOffset = 0f }
    var command by rememberSaveable { mutableStateOf<String?>(null) }
    val backProgress = remember { Animatable(0f) }
    var backDirection by remember { mutableFloatStateOf(1f) }
    var transition by remember { mutableStateOf(FolderMotion.NONE) }
    var predictiveBack by remember { mutableStateOf(false) }
    // Shared-axis folder change: the old folder (still drawn from its last state) steps aside, then the new one comes in.
    var enter by remember { mutableFloatStateOf(1f) }
    var leave by remember { mutableFloatStateOf(0f) }
    var dim by remember { mutableFloatStateOf(1f) }
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    var backJob by remember { mutableStateOf<Job?>(null) }
    val movingFolder = state.preview == null && state.editor == null && state.selected.isEmpty() && !state.searchOpen
    fun goBack() {
        backJob?.cancel()
        model.back()
        backJob = scope.launch { backProgress.snapTo(0f) }
    }
    // A new input restarts this, carrying on from wherever the previous change had got to.
    LaunchedEffect(live.location) {
        if (live.location == shownLocation) {
            // Back to this folder before it had finished leaving: it returns to place.
            if (leave > 0f || dim < 1f) {
                launch { animate(dim, 1f, animationSpec = MaterialMotion.state()) { value, _ -> dim = value } }
                animate(leave, 0f, animationSpec = MaterialMotion.spatial()) { value, _ -> leave = value }
            }
            return@LaunchedEffect
        }
        transition = folderMotion(shownLocation, live.location)
        if (transition != FolderMotion.NONE) {
            // A quick folder flips at once; a slow one dims the old list, then moves on to the loading state.
            if (model.state.value.loading) {
                animate(dim, .55f, animationSpec = MaterialMotion.state()) { value, _ -> dim = value }
                withTimeoutOrNull(250) { snapshotFlow { model.state.value.loading }.first { !it } }
            }
            animate(leave, 1f, animationSpec = MaterialMotion.screenExit(100)) { value, _ -> leave = value }
        }
        enter = 0f; leave = 0f; dim = 1f
        backProgress.snapTo(0f)
        shownLocation = live.location
        animate(0f, 1f, animationSpec = MaterialMotion.screenEnter(350)) { value, _ -> enter = value }
    }
    LaunchedEffect(hasPermission) { model.permissionChanged(hasPermission) }
    PredictiveBackHandler(active && (predictiveBack || state.location != null || state.preview != null || state.editor != null || state.searchOpen)) { events ->
        backJob?.cancel()
        predictiveBack = true
        try {
            events.collect { if (movingFolder) {
                backDirection = if (it.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                backProgress.snapTo(it.progress.coerceIn(0f, 1f))
            } }
            // A folder change leaves from where the swipe left it and resets the swipe itself.
            val before = model.state.value.location
            model.back()
            if (model.state.value.location == before) backProgress.animateTo(0f, MaterialMotion.spatial())
        } catch (cancelled: CancellationException) {
            backJob = scope.launch { backProgress.animateTo(0f, MaterialMotion.spatial()) }
            throw cancelled
        } finally {
            predictiveBack = false
        }
    }
    Box(Modifier.fillMaxSize()) {
        val logicalDirection = if (layoutDirection == LayoutDirection.Rtl) -1 else 1
        Column(Modifier.fillMaxSize().graphicsLayer {
            val shift = if (transition == FolderMotion.REPLACE) 0f else transition.direction * logicalDirection * FOLDER_SHIFT_DP.dp.toPx()
            translationX = shift * (1f - enter) - shift * leave + backDirection * backProgress.value * BACK_PEEK_DP.dp.toPx()
            alpha = enter * (1f - leave) * dim * (1f - backProgress.value * .15f)
            scaleX = (if (transition == FolderMotion.REPLACE) .96f + .04f * enter else 1f) * (1f - .03f * backProgress.value)
            scaleY = scaleX
        }
            // While searching the header stays expanded so the field and type chips remain in reach.
            .then(if (state.searchOpen) Modifier else Modifier.nestedScroll(headerBehavior.nestedScrollConnection))
            .then(if (state.preview != null) Modifier.clearAndSetSemantics {} else Modifier)) {
            ExplorerHeader(state, model, headerState, onPanel, ::goBack)
            if (state.location == null) StorageHome(state, model, settingsUnavailable, onSettings, bottomInset)
            else FolderContent(state, model, onSettings, bottomInset) { command = it }
        }
        if (renderPreview) state.preview?.let { PreviewScreen(it, model) }
    }
    FileActions(state, model, command, { command = null })
    state.editor?.let { ConnectionDialog(it, model) }
    state.notice?.let { message -> AlertDialog(onDismissRequest = { model.notice(null) }, text = { Text(message) }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { model.notice(null) }) { Text("확인") } }) }
}

/** Folder and app actions shown from the dock's More button. */
@Composable
internal fun ExplorerMenuItems(state: ExplorerState, model: ExplorerViewModel, onPanel: (String) -> Unit, onCommand: (String) -> Unit, close: (() -> Unit) -> Unit) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val player by model.graph.playback.state.collectAsStateWithLifecycle()
    if (state.location != null) {
        DropdownMenuItem({ Text("새 폴더") }, { close { onCommand("새 폴더") } }, enabled = state.writable, leadingIcon = { Icon(Symbols.CreateNewFolder, null) })
        if (!state.writable) Text("이 위치는 변경할 수 없습니다", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        DropdownMenuItem({ Text("즐겨찾기 추가/해제") }, { close { model.favorite() } }, leadingIcon = { Icon(Symbols.Star, null) })
        DropdownMenuItem({ Text(if (settings.showHidden) "숨김 파일 감추기" else "숨김 파일 표시") }, { close { model.updateSettings { it.copy(showHidden = !it.showHidden) } } }, leadingIcon = { Icon(Symbols.Visibility, null) })
        DropdownMenuItem({ Text("새로고침") }, { close { model.navigate(state.location, refresh = true) } }, modifier = Modifier.testTag("refresh-folder"), leadingIcon = { Icon(Symbols.Refresh, null) })
        DropdownMenuItem({ Text("전체 선택") }, { close { model.selectAll() } }, enabled = state.entries.isNotEmpty(), leadingIcon = { Icon(Symbols.CheckCircle, null) })
        HorizontalDivider()
    }
    DropdownMenuItem({ Text("휴지통") }, { close { onPanel("휴지통") } }, leadingIcon = { Icon(Symbols.Delete, null) })
    DropdownMenuItem({ Text("설정") }, { close { onPanel("설정") } }, leadingIcon = { Icon(Symbols.Settings, null) })
    if (player.visible) DropdownMenuItem({ Text("재생 중") }, { close { onPanel("재생 중") } }, leadingIcon = { Icon(Symbols.MusicNote, null) })
}

/**
 * Large title header: a 56dp row of glass buttons stays, the title block below slides away as content scrolls,
 * and the row turns into one glass capsule with a compact title once collapsed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExplorerHeader(state: ExplorerState, model: ExplorerViewModel, headerState: TopAppBarState, onPanel: (String) -> Unit, onBack: () -> Unit) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val glass = LocalGlass.current
    // Opening search from a scrolled list expands the header, since the field lives in the collapsible block.
    LaunchedEffect(state.searchOpen) { if (state.searchOpen) { headerState.heightOffset = 0f; searchFocus.requestFocus(); keyboard?.show() } }
    val selecting = state.selected.isNotEmpty()
    val location = state.location
    val count = remember(state.entries, state.query, state.searchResults, state.kind, settings.showHidden) { if (location == null) 0 else model.visibleEntries().size }
    val title = when { selecting -> "${state.selected.size}개 선택"; location == null -> "곳간"; else -> location.path.substringAfterLast('/').ifEmpty { model.sourceName(location.sourceId) } }
    val meta = when { location == null -> "내 파일을 차곡차곡"; selecting -> "${model.sourceName(location.sourceId)} · ${count}개 중"; location.path.isEmpty() -> "항목 ${count}개"; else -> "${model.sourceName(location.sourceId)} · 항목 ${count}개" }
    val collapsed = headerState.collapsedFraction
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp, vertical = 6.dp)) {
            if (collapsed > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = collapsed }.glass(glass))
            Row(Modifier.fillMaxSize().padding(horizontal = if (collapsed > 0f) 2.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                if (location != null || state.searchOpen) GlassIconButton(if (selecting || state.searchOpen) Symbols.Close else Symbols.ArrowBackIosNew,
                    if (selecting) "선택 해제" else "뒤로", { focus.clearFocus(); onBack() }, Modifier.testTag("navigate-up"), iconSize = if (selecting || state.searchOpen) 22.dp else 18.dp)
                else Image(painterResource(R.drawable.ic_brand_keepsake), null, Modifier.size(40.dp).clip(MaterialTheme.shapes.small))
                Column(Modifier.weight(1f).padding(horizontal = 8.dp).graphicsLayer { alpha = collapsed }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when {
                    selecting -> Box(Modifier.height(48.dp).glass(glass).clickable(role = androidx.compose.ui.semantics.Role.Button) { model.selectAll() }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text("전체 선택", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    location == null -> Row(Modifier.glass(glass)) {
                        ToolIcon(Symbols.Add, "저장소 추가", { model.editConnection() }, enabled = state.profilesReady)
                        ToolIcon(Symbols.Settings, "설정", { onPanel("설정") })
                    }
                    else -> Spacer(Modifier.width(48.dp))
                }
            }
        }
        // Report the title block's height as the collapse distance, then lay it out shortened by the scroll offset.
        Layout(content = {
            Column(Modifier.fillMaxWidth().onSizeChanged { if (headerState.heightOffsetLimit != -it.height.toFloat()) headerState.heightOffsetLimit = -it.height.toFloat() }
                .graphicsLayer { alpha = 1f - collapsed }.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.searchOpen) OutlinedTextField(state.query, model::query, singleLine = true, placeholder = { Text("현재 폴더 검색") }, shape = CircleShape,
                    leadingIcon = { Icon(Symbols.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(searchFocus).testTag("folder-search").semantics { contentDescription = "현재 폴더 검색" })
                if (state.searchOpen) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FileKind.entries.forEach { kind ->
                        val on = state.kind == kind
                        FilterChip(on, { model.kind(if (on) null else kind) }, label = { Text(kind.label) }, shape = CircleShape,
                            leadingIcon = if (on) ({ Icon(Symbols.Check, null, Modifier.size(18.dp)) }) else null,
                            modifier = Modifier.testTag("search-kind-${kind.name.lowercase()}"))
                    }
                }
                if (!state.searchOpen) Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (location != null && !selecting) PathChips(location, model)
                if (!selecting) BoxWithConstraints(Modifier.fillMaxWidth()) {
                    ViewControls(model, maxWidth.value - if (state.gridOverride ?: settings.grid) 16 else 32, state.takeIf { location != null })
                }
            }
        }, modifier = Modifier.fillMaxWidth().clipToBounds()) { measurables, constraints ->
            val placeable = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val height = (placeable.height + headerState.heightOffset).roundToInt().coerceIn(0, placeable.height)
            layout(constraints.maxWidth, height) { placeable.place(0, height - placeable.height) }
        }
    }
}

@Composable
private fun PathChips(location: StorageLocation, model: ExplorerViewModel) {
    val pieces = location.path.split('/').filter { it.isNotEmpty() }
    val scroll = rememberScrollState()
    LaunchedEffect(location) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // A long folder name is capped and ellipsized so one chip never outgrows the screen; the header shows the full name.
        @Composable fun chip(label: String, current: Boolean, onClick: () -> Unit) {
            Box(Modifier.heightIn(min = 36.dp).widthIn(max = 220.dp).clip(CircleShape)
                .background(if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        chip(model.sourceName(location.sourceId), pieces.isEmpty()) { model.navigate(StorageLocation(location.sourceId)) }
        pieces.forEachIndexed { index, name ->
            Icon(Symbols.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
            chip(name, index == pieces.lastIndex) { model.navigate(StorageLocation(location.sourceId, pieces.take(index + 1).joinToString("/"))) }
        }
    }
}

@Composable
internal fun ViewControls(model: ExplorerViewModel, availableWidth: Float, state: ExplorerState? = null) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val scale = LocalDensity.current.fontScale
    var densityMenu by remember { mutableStateOf(false) }; var sortMenu by remember { mutableStateOf(false) }
    val glass = LocalGlass.current
    // In a folder (state given) the choice is remembered per folder; elsewhere it is the global default.
    val gridOn = state?.gridOverride ?: settings.grid
    fun chooseGrid(grid: Boolean) { if (state != null) model.setGrid(grid) else model.updateSettings { it.copy(grid = grid) } }
    @Composable fun capsule(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
        Row(modifier.height(44.dp).glass(glass).clickable(onClick = onClick).padding(start = if (icon != null) 10.dp else 14.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            icon?.let { Icon(it, null, Modifier.size(20.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state != null) Box {
            capsule(state.sort.label, { sortMenu = true }, icon = Symbols.Sort)
            DropdownMenu(sortMenu, { sortMenu = false }) {
                SortOrder.entries.forEach { order -> DropdownMenuItem({ Text(order.label) }, { model.sort(SortSpec(order, state.direction)); sortMenu = false }, modifier = Modifier.semantics { selected = state.sort == order }, leadingIcon = { if (state.sort == order) Icon(Symbols.Check, null) }) }
                HorizontalDivider()
                SortDirection.entries.forEach { direction -> DropdownMenuItem({ Text(direction.label) }, { model.sort(SortSpec(state.sort, direction)); sortMenu = false }, modifier = Modifier.semantics { selected = state.direction == direction }, leadingIcon = { if (state.direction == direction) Icon(Symbols.Check, null) }) }
            }
        }
        // List/grid segmented control: one glass capsule, the current mode on a raised pill.
        Row(Modifier.height(44.dp).glass(glass).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(false to (Symbols.ViewList to "목록"), true to (Symbols.GridViewFilled to "그리드")).forEach { (grid, info) ->
                val on = gridOn == grid
                Box(Modifier.fillMaxHeight().width(48.dp).clip(CircleShape).then(if (on) Modifier.background(glass.pill, CircleShape) else Modifier)
                    .selectable(on, role = androidx.compose.ui.semantics.Role.RadioButton) { chooseGrid(grid) }
                    .semantics { contentDescription = info.second }.testTag(if (grid) "view-grid" else "view-list"), contentAlignment = Alignment.Center) {
                    Icon(if (grid && !on) Symbols.GridView else info.first, null, Modifier.size(20.dp),
                        tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (gridOn) Box {
            capsule("${settings.density.label} · ${gridColumns(availableWidth, scale, settings.density)}열", { densityMenu = true }, Modifier.testTag("grid-density"))
            DropdownMenu(densityMenu, { densityMenu = false }) {
                GridDensity.entries.forEach { value -> DropdownMenuItem({ Text("${value.label} · ${gridColumns(availableWidth, scale, value)}열") }, { model.updateSettings { it.copy(density = value) }; densityMenu = false }, modifier = Modifier.semantics { selected = settings.density == value }, leadingIcon = { if (settings.density == value) Icon(Symbols.Check, null) }) }
            }
        } else Box {
            capsule(settings.listDensity.label, { densityMenu = true }, Modifier.testTag("list-density"))
            DropdownMenu(densityMenu, { densityMenu = false }) {
                ListDensity.entries.forEach { value -> DropdownMenuItem({ Text(value.label) },
                    { model.updateSettings { it.copy(listDensity = value) }; densityMenu = false },
                    modifier = Modifier.testTag("list-density-${value.name}").semantics { selected = settings.listDensity == value },
                    leadingIcon = { if (settings.listDensity == value) Icon(Symbols.Check, null) }) }
            }
        }
    }
}

internal data class PlaceItem(val id: String, val title: String, val detail: String, val location: StorageLocation?, val profile: ConnectionProfile? = null)

@Composable
private fun StorageHome(state: ExplorerState, model: ExplorerViewModel, settingsUnavailable: Boolean, onSettings: () -> Unit, bottomInset: Dp) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val places by model.places.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf<ConnectionProfile?>(null) }
    var deleting by remember { mutableStateOf<ConnectionProfile?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentWidth = maxWidth.value - if (settings.grid) 16 else 32
        val columns = if (settings.grid) gridColumns(contentWidth, LocalDensity.current.fontScale, settings.density) else 1
        val edge = if (settings.grid) 8.dp else 16.dp
        @Composable fun sectionTitle(text: String) = Text(text, Modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().testTag("storage-home"), contentPadding = PaddingValues(start = edge, end = edge, top = 4.dp, bottom = bottomInset), horizontalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp), verticalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) { sectionTitle("저장소") }
                val sources = listOf(PlaceItem("phone", "휴대폰 저장소", "다운로드 · 사진 · 문서", StorageLocation(LOCAL_SOURCE))) + state.profiles.map { PlaceItem(it.id, it.name, "WebDAV", StorageLocation(it.id), it) }
                items(sources, key = { it.id }) { place -> PlaceCell(place, settings.grid, { model.navigate(place.location!!) }, { place.profile?.let { details = it } }, listDensity = settings.listDensity) }
                item { PlaceCell(PlaceItem("add", "저장소 추가", "WebDAV 연결", null), settings.grid,
                    { model.editConnection() }, {}, enabled = state.profilesReady, listDensity = settings.listDensity) }
                if (!state.hasPermission || settingsUnavailable) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column { Text("휴대폰 파일을 보려면 접근 권한을 허용해 주세요. NAS는 바로 사용할 수 있습니다.", style = MaterialTheme.typography.bodyMedium); TextButton(shape = MaterialTheme.shapes.small, onClick = onSettings, modifier = Modifier.testTag("storage-permission")) { Text("파일 접근 허용") } }
                }
                if (!state.profilesReady) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) { ErrorPanel(message) } }
                listOf("favorite:" to "즐겨찾기", "recent:" to "최근 위치").forEach { (prefix, title) ->
                    item(span = { GridItemSpan(maxLineSpan) }) { sectionTitle(title) }
                    val section = places.filter { it.key.startsWith(prefix) }.mapNotNull { place -> runCatching { decodeLocation(place.value) }.getOrNull()?.let { PlaceItem(place.key, it.path.substringAfterLast('/').ifEmpty { model.sourceName(it.sourceId) }, "${model.sourceName(it.sourceId)} / ${it.path}", it) } }
                    if (section.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text(if (prefix == "favorite:") "폴더 메뉴에서 즐겨찾기를 추가하세요." else "방문한 폴더가 여기에 표시됩니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    items(section, key = { it.id }) { place -> PlaceCell(place, settings.grid, { model.navigate(place.location!!) }, {}, listDensity = settings.listDensity) }
                }
            }
        }
    }
    details?.let { profile -> AlertDialog(onDismissRequest = { details = null }, title = { Text(profile.name) }, text = { Column { Text(profile.url); Text(profile.username); TextButton(shape = MaterialTheme.shapes.small, onClick = { deleting = profile; details = null }) { Text("연결 삭제") } } }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { details = null; model.editConnection(profile) }) { Text("연결 수정") } }, dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { details = null }) { Text("닫기") } }) }
    deleting?.let { profile -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("저장된 연결 삭제") }, text = { Text("‘${profile.name}’의 연결 정보와 캐시를 삭제합니다. NAS 파일은 유지됩니다.") }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { model.removeConnection(profile); deleting = null }) { Text("연결 삭제") } }, dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { deleting = null }) { Text("취소") } }) }
}

@Composable
internal fun PlaceCell(place: PlaceItem, grid: Boolean, open: () -> Unit, detail: () -> Unit, enabled: Boolean = true, listDensity: ListDensity = ListDensity.COMPACT) {
    val titleStyle = if (grid) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    // minLines alone still varies with Korean/Latin fallback font metrics.
    val titleHeight = with(LocalDensity.current) { titleStyle.lineHeight.toDp() * if (grid) 1 else 2 }
    val detailHeight = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() }
    Surface(onClick = open, enabled = enabled, shape = MaterialTheme.shapes.large,
        color = if (place.id == "add") MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag(when (place.id) { "phone" -> "storage-phone"; "add" -> "storage-add"; else -> "place-${place.id}" })) {
        if (grid) Column(Modifier.padding(GRID_SPACING_DP.dp)) {
            // Reserve the same header and text slots for every card, including Add.
            BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                if (place.profile != null && maxWidth < 88.dp) {
                    IconButton(onClick = detail, modifier = Modifier.size(48.dp).semantics { contentDescription = "${place.title} 연결 상세" }) { PlaceSymbol(place) }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    PlaceSymbol(place); Spacer(Modifier.weight(1f))
                    if (place.profile != null) ToolIcon(Symbols.MoreVert, "${place.title} 연결 상세", detail)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(place.title, Modifier.height(titleHeight), minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis, style = titleStyle)
            Text(place.detail, Modifier.height(detailHeight), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else Row(Modifier.heightIn(min = listDensity.minimumHeight.dp).padding(horizontal = 8.dp, vertical = if (listDensity == ListDensity.COMPACT) 4.dp else 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(listDensity.itemSpacing.dp)) {
            PlaceSymbol(place)
            Column(Modifier.weight(1f)) {
                Text(place.title, if (listDensity == ListDensity.COMPACT) Modifier else Modifier.height(titleHeight), style = titleStyle, minLines = if (listDensity == ListDensity.COMPACT) 1 else 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(place.detail, Modifier.height(detailHeight), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (place.profile != null) ToolIcon(Symbols.MoreVert, "${place.title} 연결 상세", detail)
        }
    }
}

@Composable
private fun PlaceSymbol(place: PlaceItem) {
    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Icon(when { place.id == "add" -> Symbols.Add; place.profile != null -> Symbols.Dns; else -> Symbols.FolderFilled }, null,
            Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun FolderStateView(state: ExplorerState, model: ExplorerViewModel, location: StorageLocation, onSettings: () -> Unit, onCommand: (String) -> Unit) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val error = state.error
    val retry = StateAction("다시 시도", "state-retry") { model.navigate(location, refresh = true) }
    val home = StateAction("저장소 선택", "state-home") { model.home() }
    val profile = state.profiles.firstOrNull { it.id == location.sourceId }
    when {
        error != null -> when (val kind = errorKind(error)) {
            StateKind.PERMISSION -> StateMessage(kind, error, if (location.sourceId == LOCAL_SOURCE) StateAction("권한 설정 열기", "state-permission", onSettings) else retry, home)
            StateKind.AUTH -> StateMessage(kind, error, profile?.let { StateAction("다시 로그인", "state-login") { model.editConnection(it) } } ?: retry, home)
            StateKind.CERTIFICATE -> StateMessage(kind, "$error 곳간은 인증서 예외를 허용하지 않아요.",
                profile?.let { StateAction("연결 정보 보기", "state-connection") { model.editConnection(it) } } ?: home, retry)
            else -> StateMessage(kind, error, retry, home)
        }
        state.query.isNotBlank() || state.kind != null -> StateMessage(StateKind.NO_MATCH, "다른 이름으로 다시 찾아보세요. 하위 폴더까지 찾으려면 ‘하위 폴더 포함 검색’을 누르세요.")
        else -> StateMessage(StateKind.EMPTY, if (state.writable) "다른 위치에서 파일을 복사해 오거나 새 폴더를 만들 수 있어요." else "이 위치에 표시할 파일이 없어요.",
            if (state.writable) StateAction("새 폴더", "state-new-folder") { onCommand("새 폴더") } else null,
            if (!settings.showHidden) StateAction("숨긴 파일 보기") { model.updateSettings { it.copy(showHidden = true) } } else null)
    }
}

@Composable
private fun FolderContent(state: ExplorerState, model: ExplorerViewModel, onSettings: () -> Unit, bottomInset: Dp, onCommand: (String) -> Unit) {
    val location = state.location ?: return
    val settings by model.settings.collectAsStateWithLifecycle()
    val entries = remember(state.entries, state.query, state.searchResults, state.kind, settings.showHidden) { model.visibleEntries() }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f)) {
            val contentWidth = maxWidth.value - if (state.gridOverride ?: settings.grid) 16 else 32
            Column {
                if (state.searchOpen) TextButton(shape = MaterialTheme.shapes.small, onClick = { model.recursiveSearch(state.searching) }) { Text(if (state.searching) "하위 검색 취소" else "하위 폴더 포함 검색") }
                if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    state.loading -> LoadingState({ model.home() })
                    state.error != null || entries.isEmpty() -> FolderStateView(state, model, location, onSettings, onCommand)
                    else -> {
                        if (state.selected.isNotEmpty()) Text("드래그로 선택 · 양쪽 여백으로 스크롤", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        EntryCollection(entries, state, model, contentWidth, bottomInset)
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCollection(entries: List<FileEntry>, state: ExplorerState, model: ExplorerViewModel, width: Float, bottomInset: Dp) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val columns = gridColumns(width, LocalDensity.current.fontScale, settings.density)
    val grid = state.gridOverride ?: settings.grid
    val layout = if (grid) "grid:$columns:${settings.density}" else "list:${settings.listDensity}"
    // Recreate only the layout state, keeping the provider's entry list and selection untouched.
    key(state.location?.key, state.viewportEpoch, layout, state.query, state.kind, state.searchResults != null) {
        val anchor = remember { model.currentViewportAnchor() }
        val position = remember { anchor.resolve(entries) }
        val currentEntries by rememberUpdatedState(entries)
        DisposableEffect(model) { onDispose { model.flushViewport() } }
        val click: (FileEntry) -> Unit = { if (model.state.value.selected.isNotEmpty()) model.select(it) else model.open(it) }
        if (grid) {
            val grid = remember { LazyGridState(position, anchor.offsetFor(layout)) }
            LaunchedEffect(grid) {
                var previousIndex = grid.firstVisibleItemIndex
                var previousOffset = grid.firstVisibleItemScrollOffset
                var restoringAnchor = !grid.isScrollInProgress
                snapshotFlow { Triple(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, grid.isScrollInProgress) }
                    .distinctUntilChanged().drop(1).collect { (index, offset, scrolling) ->
                        if (scrolling) restoringAnchor = false
                        if (index != previousIndex || offset != previousOffset) {
                            previousIndex = index
                            previousOffset = offset
                            // Insets can change the column count while a recreated
                            // window settles. Keep the requested file identity when
                            // the initial layout only rounds it to its row's start.
                            val initialRowNormalization = restoringAnchor &&
                                index == position - position % columns && offset == anchor.offsetFor(layout)
                            if (!initialRowNormalization) {
                                restoringAnchor = false
                                model.trackViewport(ViewportAnchor(currentEntries.getOrNull(index)?.id, index, offset, layout), settled = !scrolling, origin = state)
                            }
                        } else if (!scrolling) {
                            // A stop signal must not replace an explicit pinch anchor
                            // with the old grid's unchanged first visible item.
                            model.flushViewport()
                        }
                    }
            }
            val scope = rememberCoroutineScope()
            val currentDensity by rememberUpdatedState(settings.density)
            val gesture = Modifier.selectionGestures(entries to layout,
                hits = { grid.layoutInfo.visibleItemsInfo.mapNotNull { item -> currentEntries.getOrNull(item.index)?.let {
                    SelectionHit(it.id, Rect(item.offset.x.toFloat(), item.offset.y.toFloat(), (item.offset.x + item.size.width).toFloat(), (item.offset.y + item.size.height).toFloat()))
                } } },
                selected = { model.state.value.selected },
                apply = { ids, selecting -> model.paintSelection(ids, selecting, state) },
                scroll = { grid.scrollBy(it) },
                pinch = { scale, centroid ->
                    val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { centroid.x >= it.offset.x && centroid.x < it.offset.x + it.size.width && centroid.y >= it.offset.y && centroid.y < it.offset.y + it.size.height }
                    if (item != null) model.viewport(ViewportAnchor(currentEntries.getOrNull(item.index)?.id, item.index, 0, "pinch"))
                    scope.launch { model.graph.settings.update { it.copy(density = currentDensity.step(scale < 1f)) } }
                })
            LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().then(gesture).testTag("folder-grid"), state = grid, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = bottomInset), horizontalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp), verticalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp)) {
                items(entries, key = { it.id }, contentType = { if (it.isDirectory) "folder" else "file" }) { FileCell(it, it.id in state.selected, model, true, state.location?.path?.takeIf { _ -> state.searchResults != null }, square = columns > 1) { click(it) } }
            }
        } else {
            val list = remember { LazyListState(position, anchor.offsetFor(layout)) }
            LaunchedEffect(list) {
                var previousIndex = list.firstVisibleItemIndex
                var previousOffset = list.firstVisibleItemScrollOffset
                snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.isScrollInProgress) }
                    .distinctUntilChanged().drop(1).collect { (index, offset, scrolling) ->
                        if (index != previousIndex || offset != previousOffset) {
                            previousIndex = index
                            previousOffset = offset
                            model.trackViewport(ViewportAnchor(currentEntries.getOrNull(index)?.id, index, offset, layout), settled = !scrolling, origin = state)
                        } else if (!scrolling) {
                            model.flushViewport()
                        }
                    }
            }
            val horizontalPadding = with(LocalDensity.current) { 16.dp.toPx() }
            val gesture = Modifier.selectionGestures(entries to layout,
                hits = { list.layoutInfo.visibleItemsInfo.mapNotNull { item -> currentEntries.getOrNull(item.index)?.let {
                    SelectionHit(it.id, Rect(horizontalPadding, item.offset.toFloat(), list.layoutInfo.viewportSize.width - horizontalPadding, (item.offset + item.size).toFloat()))
                } } },
                selected = { model.state.value.selected },
                apply = { ids, selecting -> model.paintSelection(ids, selecting, state) },
                scroll = { list.scrollBy(it) })
            LazyColumn(Modifier.fillMaxSize().then(gesture).testTag("folder-list"), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomInset)) {
                items(entries, key = { it.id }, contentType = { if (it.isDirectory) "folder" else "file" }) { FileCell(it, it.id in state.selected, model, false, state.location?.path?.takeIf { _ -> state.searchResults != null }, listDensity = settings.listDensity, selecting = state.selected.isNotEmpty()) { click(it) } }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCell(entry: FileEntry, selected: Boolean, model: ExplorerViewModel, grid: Boolean, relativeRoot: String?, listDensity: ListDensity = ListDensity.COMPACT, selecting: Boolean = false, square: Boolean = true, open: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    val cardShape = MaterialTheme.shapes.medium
    val background = key(MaterialTheme.colorScheme) {
        animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            MaterialMotion.state(), label = "file-selection-background")
    }
    val border = key(MaterialTheme.colorScheme) {
        animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            MaterialMotion.state(), label = "file-selection-border")
    }
    // An image tile lends the viewer its place and decoded thumbnail, so opening can grow out of it and closing land back in it.
    val tile = remember(entry.id) { object { var coordinates: LayoutCoordinates? = null; var image: coil3.Image? = null } }
    if (entry.isImage) DisposableEffect(entry.id) {
        val ref = TileRef({ tile.coordinates }, { tile.image })
        model.tiles[entry.id] = ref
        onDispose { if (model.tiles[entry.id] === ref) model.tiles.remove(entry.id) }
    }
    val modifier = Modifier.fillMaxWidth().testTag("entry-${entry.location.path}").clip(cardShape)
        .background(background.value)
        .border(1.dp, border.value, cardShape)
        .clickable(onClick = open)
        .semantics { this.selected = selected; stateDescription = if (selected) "선택됨" else "선택 안 됨"; onLongClick("선택") { model.select(entry); true } }
    val icon = when { entry.isDirectory -> Symbols.FolderFilled; entry.isImage -> Symbols.Image; entry.isVideo -> Symbols.Movie; entry.isAudio -> Symbols.MusicNote; entry.isPdf -> Symbols.PictureAsPdf; entry.isArchive -> Symbols.FolderZip; else -> Symbols.Draft }
    @Composable fun thumbnail(size: Modifier) {
        Box(size.then(if (entry.isImage) Modifier.onPlaced { tile.coordinates = it }.graphicsLayer { alpha = if (model.flightEntryId == entry.id) 0f else 1f } else Modifier)
            .clip(shape).background(if (entry.isDirectory) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(if (grid) 44.dp else 28.dp),
                tint = if (entry.isDirectory) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.isImage) Thumbnail(entry, model, Modifier.fillMaxSize()) { tile.image = it }
            // Grid tiles mark selection on the thumbnail; list rows use the trailing check instead.
            AnimatedVisibility(selected && grid, modifier = Modifier.align(Alignment.TopEnd),
                enter = fadeIn(MaterialMotion.settle()) + scaleIn(MaterialMotion.settle(), initialScale = .8f), exit = ExitTransition.None) {
                Icon(Symbols.CheckCircleFilled, "선택됨", Modifier.background(MaterialTheme.colorScheme.surface, shape).size(24.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
    @Composable fun label() {
        val titleStyle = if (grid) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
        val titleSlot = if (grid) Modifier.height(with(LocalDensity.current) { titleStyle.lineHeight.toDp() }) else Modifier
        val detailSlot = if (grid) Modifier.height(with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() }) else Modifier
        Text(entry.name, modifier = titleSlot, style = titleStyle, minLines = 1, maxLines = if (grid) 1 else 2, overflow = TextOverflow.Ellipsis)
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val detail = remember(entry, relativeRoot, configuration) {
            if (relativeRoot != null) entry.location.path.removePrefix(relativeRoot).trimStart('/')
            else listOfNotNull(if (entry.isDirectory) "폴더" else entry.size?.let(::formatBytes), entry.modified?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)) }).joinToString(" · ")
        }
        Text(if (grid) entry.accessError ?: detail else detail, modifier = detailSlot, style = MaterialTheme.typography.bodySmall, color = if (grid && entry.accessError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!grid) entry.accessError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
    }
    // Grid tiles are square at every density; a single column (very large text) keeps a fixed height so one tile never fills the screen.
    if (grid) Column(modifier.padding(GRID_SPACING_DP.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { thumbnail(Modifier.fillMaxWidth().then(if (square) Modifier.aspectRatio(1f) else Modifier.height(120.dp))); label() }
    else Row(modifier.heightIn(min = listDensity.minimumHeight.dp).padding(vertical = listDensity.verticalPadding.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(listDensity.itemSpacing.dp)) { thumbnail(Modifier.size(listDensity.thumbnailSize.dp)); Column(Modifier.weight(1f)) { label() }
        when {
            selecting && selected -> Box(Modifier.size(26.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Symbols.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            selecting -> Box(Modifier.size(26.dp).border(1.8.dp, MaterialTheme.colorScheme.outline, CircleShape))
            entry.isDirectory -> Icon(Symbols.ChevronRight, null, Modifier.size(20.dp))
        } }
}

@Composable
internal fun Thumbnail(entry: FileEntry, model: ExplorerViewModel, modifier: Modifier, onImage: ((coil3.Image) -> Unit)? = null) {
    var asset by remember(entry.id, entry.etag, entry.modified) { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(entry.id, entry.etag, entry.modified) {
        var lease: ContentCache.Lease? = null
        try {
            if (entry.location.sourceId == LOCAL_SOURCE) lease = model.graph.cache.full(model.provider(entry.location.sourceId), entry, IMAGE_LIMIT)
            else model.graph.thumbnailRequests.withPermit { lease = model.graph.cache.full(model.provider(entry.location.sourceId), entry, IMAGE_LIMIT) }
            asset = lease?.file
            awaitCancellation()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { asset = null } finally { lease?.close() }
    }
    asset?.let { AsyncImage(it, null, imageLoader = model.graph.thumbnailLoader, modifier = modifier, contentScale = ContentScale.Crop,
        onSuccess = onImage?.let { report -> { state -> report(state.result.image) } }) }
}
