package com.quickfiles.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.quickfiles.app.storage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.quickfiles.app.AppGraph
import com.quickfiles.app.data.*
import com.quickfiles.app.operations.*
import com.quickfiles.app.viewer.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.json.JSONArray

data class PreviewState(
    val entry: FileEntry, val loading: Boolean = true, val read: Long = 0, val total: Long? = null,
    val bitmap: Bitmap? = null, val text: TextPreview? = null, val error: String? = null,
    val cp949: Boolean = false, val decodedText: String = "", val previous: FileEntry? = null, val next: FileEntry? = null,
    val asset: File? = null, val document: TextDocument? = null, val pdf: PdfDocument? = null, val archive: ArchiveDocument? = null,
    val draft: String? = null,
    val archiveSelected: Set<String> = emptySet(), val archiveCurrent: String? = null,
    val token: String = UUID.randomUUID().toString(),
)

data class ConnectionEditor(
    val token: String = UUID.randomUUID().toString(), val existing: ConnectionProfile? = null,
    val name: String = "", val url: String = "", val username: String = "", val password: String = "",
    val busy: Boolean = false, val result: String? = null,
)

data class ExplorerState(
    val profiles: List<ConnectionProfile> = emptyList(), val profilesReady: Boolean = false,
    val location: StorageLocation? = null, val entries: List<FileEntry> = emptyList(),
    val sort: SortOrder = SortOrder.NAME, val index: Int = 0, val offset: Int = 0,
    val loading: Boolean = false, val error: String? = null,
    val hasPermission: Boolean = Environment.isExternalStorageManager(),
    val preview: PreviewState? = null, val editor: ConnectionEditor? = null,
    val selected: Set<String> = emptySet(), val query: String = "", val searching: Boolean = false,
    val searchOpen: Boolean = false, val anchor: ViewportAnchor = ViewportAnchor(),
    val direction: SortDirection = SortDirection.ASCENDING, val viewportEpoch: Int = 0,
    val searchResults: List<FileEntry>? = null, val writable: Boolean = false, val notice: String? = null,
    /** Search type filter; null shows every kind. */
    val kind: FileKind? = null,
    /** This folder's remembered list/grid choice; null follows the global default. */
    val gridOverride: Boolean? = null,
)

private data class FolderSnapshot(val entries: List<FileEntry>, val sort: SortOrder, val index: Int, val offset: Int, val direction: SortDirection, val anchor: ViewportAnchor)

class ExplorerViewModel(application: Application, private val saved: SavedStateHandle, val tabId: String = "A") : AndroidViewModel(application) {
    /** Image tiles now on screen, so the viewer can grow from the tapped one and fly back into the current one. */
    internal val tiles = mutableMapOf<String, TileRef>()
    /** The image whose tile is in flight; that tile stays empty until the flight lands. */
    internal var flightEntryId by mutableStateOf<String?>(null)
    /** Last image shown in this viewer session; the viewer is rebuilt per image, so it cannot remember this itself. */
    internal var lastViewedImageId: String? = null
    val graph = AppGraph.get(application)
    val settings = graph.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ExplorerSettings())
    val places = MutableStateFlow<List<SavedValue>>(emptyList())
    private val store = ConnectionStore(application)
    private val cache = PreviewCache(File(application.cacheDir, "previews"))
    private val mutableState = MutableStateFlow(ExplorerState())
    val state = mutableState.asStateFlow()
    private val folders = LinkedHashMap<String, FolderSnapshot>()
    private var browsing: Job? = null
    private var viewing: Job? = null
    private var editing: Job? = null
    private var browseGeneration = 0
    private var viewGeneration = 0
    private var restored = false
    private var searching: Job? = null
    private var searchGeneration = 0
    private var persisting: Job? = null
    // Pixel offsets are restoration data, not screen content. Keep them out of the
    // observed ExplorerState until scrolling settles or the browser changes context.
    private var pendingViewport: ViewportAnchor? = null
    private var imageLease: ContentCache.Lease? = null
    private val sharing = mutableListOf<ContentCache.Lease>()
    private val textHistory = mutableListOf<Long>()

    init {
        viewModelScope.launch {
            graph.operations.changed.collect { location ->
                folders.remove(location.key)
                if (state.value.location == location) {
                    val generation = browseGeneration
                    try {
                        val entries = provider(location.sourceId).list(location).filterNot { OperationEngine.isManagedName(it.name) }
                        val order = SortSpec(state.value.sort, state.value.direction)
                        val sorted = withContext(Dispatchers.Default) { sortedEntries(entries, order) }
                        val presentIds = sorted.mapTo(HashSet()) { it.id }
                        if (generation == browseGeneration) {
                            mutableState.update { current ->
                                val removedIds = current.entries.filterNot { it.id in presentIds }.mapTo(HashSet()) { it.id }
                                current.copy(entries = sorted, selected = current.selected - removedIds)
                            }
                            rememberFolder()
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { notice(readableError(e)) }
                }
            }
        }
        refreshPlaces()
        viewModelScope.launch {
            val persisted = graph.dao.saved("tab:$tabId")?.value?.let { runCatching { JSONObject(it) }.getOrNull() }
            store.profiles.catch { mutableState.update { it.copy(profilesReady = true, error = "저장소 설정을 읽지 못했습니다. 앱을 다시 실행해 주세요.") } }
                .collect { profiles ->
                    mutableState.update { it.copy(profiles = profiles, profilesReady = true) }
                    if (!restored) {
                        restored = true
                        val source = saved.get<String>("source") ?: persisted?.optString("source")
                        if (!source.isNullOrBlank()) {
                            val location = runCatching { StorageLocation(source!!, saved["path"] ?: persisted?.optString("path").orEmpty()) }.getOrNull()
                            if (location != null) {
                                val sort = runCatching { SortOrder.valueOf(saved["sort"] ?: persisted?.optString("sort", "NAME") ?: "NAME") }.getOrDefault(SortOrder.NAME)
                                val index = saved.get<Int>("index") ?: persisted?.optInt("index") ?: 0
                                val offset = saved.get<Int>("offset") ?: persisted?.optInt("offset") ?: 0
                                val direction = runCatching { SortDirection.valueOf(saved["direction"] ?: persisted?.optString("direction").orEmpty()) }.getOrDefault(legacyDirection(sort))
                                val anchor = ViewportAnchor(saved["anchorId"] ?: persisted?.optString("anchorId")?.takeIf { it.isNotEmpty() }, index, offset, saved["layout"] ?: persisted?.optString("layout", "list") ?: "list")
                                navigate(location, restoredSort = sort, restoredIndex = index, restoredOffset = offset, restoredDirection = direction, restoredAnchor = anchor)
                            }
                        }
                    }
                }
        }
    }

    suspend fun provider(sourceId: String): StorageProvider = graph.repository.provider(sourceId)

    fun sourceName(sourceId: String) = if (sourceId == LOCAL_SOURCE) "휴대폰 저장소" else state.value.profiles.firstOrNull { it.id == sourceId }?.name ?: "WebDAV"

    fun navigate(location: StorageLocation, refresh: Boolean = false, restoredSort: SortOrder? = null, restoredIndex: Int = 0, restoredOffset: Int = 0, restoredDirection: SortDirection? = null, restoredAnchor: ViewportAnchor? = null) {
        rememberFolder()
        searching?.cancel(); searchGeneration++; searchReturn = null
        closePreview()
        textHistory.clear()
        browsing?.cancel()
        val generation = ++browseGeneration
        val old = folders[location.key]
        val order = restoredSort ?: old?.sort ?: SortOrder.NAME
        val direction = restoredDirection ?: old?.direction ?: legacyDirection(order)
        val anchor = old?.anchor ?: restoredAnchor ?: ViewportAnchor(index = restoredIndex, offset = restoredOffset)
        val index = old?.index ?: restoredIndex
        val offset = old?.offset ?: restoredOffset
        val canUseCache = old != null && !refresh && (location.sourceId != LOCAL_SOURCE || state.value.hasPermission)
        mutableState.update { it.copy(location = location, entries = if (canUseCache) old!!.entries else emptyList(), sort = order,
            index = index, offset = offset, direction = direction, anchor = anchor, searchOpen = false, viewportEpoch = it.viewportEpoch + 1, loading = !canUseCache, error = null, selected = emptySet(), query = "", searchResults = null, searching = false, kind = null,
            gridOverride = if (it.location == location) it.gridOverride else null, writable = location.sourceId != LOCAL_SOURCE || it.hasPermission) }
        savePosition()
        viewModelScope.launch { graph.dao.save(SavedValue("recent:${location.key}", location.encode())); refreshPlaces() }
        viewModelScope.launch {
            val remembered = graph.dao.saved(viewKey(location))?.value?.let { it == "grid" }
            if (state.value.location == location) mutableState.update { it.copy(gridOverride = remembered) }
        }
        if (canUseCache) return
        browsing = viewModelScope.launch {
            try {
                val source = provider(location.sourceId)
                val entries = source.list(location).filterNot { OperationEngine.isManagedName(it.name) }
                val sorted = withContext(Dispatchers.Default) { sortedEntries(entries, SortSpec(state.value.sort, state.value.direction)) }
                if (generation == browseGeneration) {
                    mutableState.update { it.copy(entries = sorted, loading = false, error = null, writable = source.capabilities.writable) }
                    rememberFolder()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == browseGeneration) mutableState.update { it.copy(entries = emptyList(), loading = false, error = readableError(e)) } }
        }
    }

    fun home() {
        rememberFolder()
        searching?.cancel(); searchGeneration++; persisting?.cancel(); searchReturn = null
        browsing?.cancel(); browseGeneration++
        closePreview()
        mutableState.update { it.copy(location = null, entries = emptyList(), loading = false, error = null, selected = emptySet(), query = "", searchOpen = false, searchResults = null, kind = null, gridOverride = null) }
        saved["source"] = null
        viewModelScope.launch { graph.dao.removeSaved("tab:$tabId") }
    }

    fun back() {
        if (state.value.editor != null) { closeEditor(); return }
        if (state.value.preview != null) { closePreview(); return }
        if (state.value.selected.isNotEmpty()) { clearSelection(); return }
        if (state.value.searchOpen) { showSearch(false); return }
        val parent = state.value.location?.parent()
        if (parent == null) home() else navigate(parent)
    }

    fun scroll(index: Int, offset: Int) = viewport(ViewportAnchor(visibleEntries().getOrNull(index)?.id, index, offset))

    fun currentViewportAnchor(): ViewportAnchor = pendingViewport ?: state.value.anchor

    fun trackViewport(anchor: ViewportAnchor, settled: Boolean, origin: ExplorerState) {
        val current = state.value
        // The old Lazy layout may finish a callback while a new folder, sort or
        // search is being composed. It must not overwrite that context's anchor.
        if (current.loading || current.location == null || current.location != origin.location ||
            current.viewportEpoch != origin.viewportEpoch || current.query != origin.query ||
            (current.searchResults != null) != (origin.searchResults != null)) return
        if (currentViewportAnchor() != anchor) {
            if (pendingViewport == null) { persisting?.cancel(); persisting = null }
            pendingViewport = anchor
            // SavedStateHandle stays current even if the Activity stops mid-fling.
            saveViewport(anchor)
        }
        if (settled) flushViewport()
    }

    fun flushViewport() {
        val anchor = pendingViewport ?: return
        viewport(anchor)
    }

    fun viewport(anchor: ViewportAnchor) {
        val hadPendingViewport = pendingViewport != null
        pendingViewport = null
        if (state.value.loading) return
        if (state.value.anchor == anchor) {
            // A drag can return to its starting position after cancelling that
            // position's queued Room write. Restore the write without a UI emission.
            if (hadPendingViewport) savePosition()
            return
        }
        mutableState.update { it.copy(index = anchor.index, offset = anchor.offset, anchor = anchor) }
        rememberFolder(); savePosition()
    }

    private var sorting: Job? = null
    fun sort(order: SortOrder) = sort(SortSpec(order))
    fun sort(spec: SortSpec) {
        if (state.value.loading) return
        flushViewport()
        sorting?.cancel()
        val generation = browseGeneration
        val entries = state.value.entries
        val results = state.value.searchResults
        sorting = viewModelScope.launch {
            val sorted = withContext(Dispatchers.Default) { sortedEntries(entries, spec) }
            val searched = withContext(Dispatchers.Default) { results?.let { sortedEntries(it, spec) } }
            if (generation != browseGeneration) return@launch
            pendingViewport = null
            mutableState.update { it.copy(sort = spec.order, direction = spec.direction, entries = sorted, searchResults = searched,
                index = 0, offset = 0, anchor = ViewportAnchor(), viewportEpoch = it.viewportEpoch + 1) }
            rememberFolder(); savePosition()
        }
    }

    fun showSearch(open: Boolean) {
        if (!open) { mutableState.update { it.copy(kind = null) }; query("") }
        mutableState.update { it.copy(searchOpen = open, kind = if (open) it.kind else null) }
    }

    fun kind(value: FileKind?) {
        flushViewport()
        val current = state.value
        if (value == current.kind) return
        val anchor = if (value == null && current.query.isEmpty()) searchReturn.also { searchReturn = null } ?: current.anchor
            else { if (searchReturn == null) searchReturn = current.anchor; ViewportAnchor(layout = current.anchor.layout) }
        mutableState.update { it.copy(kind = value, selected = emptySet(), index = anchor.index, offset = anchor.offset, anchor = anchor) }
    }

    /** Effective list/grid for the current location. */
    fun grid(settings: ExplorerSettings = this.settings.value) = state.value.gridOverride ?: settings.grid

    /** In a folder the choice is remembered for that folder only; at the storage home it changes the default. */
    fun setGrid(grid: Boolean) {
        val location = state.value.location
        if (location == null) { updateSettings { it.copy(grid = grid) }; return }
        mutableState.update { it.copy(gridOverride = grid) }
        viewModelScope.launch { graph.dao.save(SavedValue(viewKey(location), if (grid) "grid" else "list")) }
    }

    private fun viewKey(location: StorageLocation) = "view:${location.key}"

    private fun rememberFolder() {
        flushViewport()
        val current = state.value
        if (current.location != null && !current.loading && current.error == null) {
            folders[current.location.key] = FolderSnapshot(current.entries, current.sort, current.index, current.offset, current.direction, current.anchor)
            // Bound in-memory listings; process restoration stores only a tiny navigation record.
            while (folders.size > 12) folders.remove(folders.keys.first())
        }
    }

    private fun savePosition() {
        val current = state.value
        saved["source"] = current.location?.sourceId
        saved["path"] = current.location?.path
        saved["sort"] = current.sort.name
        saved["direction"] = current.direction.name
        saveViewport(current.anchor)
        persisting?.cancel()
        persisting = viewModelScope.launch {
            delay(200)
            if (current.location != null) graph.dao.save(SavedValue("tab:$tabId", JSONObject().put("source", current.location.sourceId).put("path", current.location.path).put("sort", current.sort.name).put("index", current.index).put("offset", current.offset).put("direction", current.direction.name).put("anchorId", current.anchor.fileId.orEmpty()).put("layout", current.anchor.layout).toString()))
        }
    }

    private fun saveViewport(anchor: ViewportAnchor) {
        saved["index"] = anchor.index
        saved["offset"] = anchor.offset
        saved["anchorId"] = anchor.fileId
        saved["layout"] = anchor.layout
    }

    fun permissionChanged(granted: Boolean) {
        val changed = granted != state.value.hasPermission
        mutableState.update { it.copy(hasPermission = granted) }
        if (changed) {
            folders.keys.filter { it.startsWith("$LOCAL_SOURCE:") }.forEach { folders.remove(it) }
            val location = state.value.location
            if (location?.sourceId == LOCAL_SOURCE) navigate(location, refresh = true)
        }
    }

    fun open(entry: FileEntry) {
        flushViewport()
        if (entry.accessError != null) { notice(entry.accessError); return }
        if (entry.isDirectory) { navigate(entry.location); return }
        if (entry.isVideo || entry.isAudio) {
            viewModelScope.launch {
                try {
                    graph.playback.play(entry, state.value.entries)
                    getApplication<Application>().startActivity(android.content.Intent(getApplication(), com.quickfiles.app.playback.PlayerActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { if (e is CancellationException) throw e; notice(readableError(e)) }
            }
            return
        }
        // Moving between images keeps the viewer session; a fresh open starts a new one.
        val viewedImage = if (state.value.preview?.entry?.isImage == true) lastViewedImageId else null
        closePreview()
        lastViewedImageId = viewedImage
        textHistory.clear()
        viewing?.cancel()
        val generation = ++viewGeneration
        val images = state.value.entries.filter { it.isImage }
        val position = images.indexOfFirst { it.id == entry.id }
        mutableState.update { it.copy(preview = PreviewState(entry, previous = images.getOrNull(position - 1), next = if (position >= 0) images.getOrNull(position + 1) else null)) }
        if (!entry.isImage && !entry.isText && !entry.isPdf && !entry.isArchive) {
            mutableState.update { it.copy(preview = it.preview?.copy(loading = false, error = "이 파일 형식은 내장 뷰어에서 지원하지 않습니다. 파일 메뉴에서 공유로 열어 주세요.")) }
            return
        }
        viewing = viewModelScope.launch {
            try {
                val source = provider(entry.location.sourceId)
                var lastProgress = 0L
                val progress: (Long, Long?) -> Unit = { bytes, total ->
                    val now = System.nanoTime()
                    if (now - lastProgress >= 100_000_000 || bytes == total) {
                        lastProgress = now
                        if (generation == viewGeneration) mutableState.update { it.copy(preview = it.preview?.copy(read = bytes, total = total)) }
                    }
                }
                val lease = if (entry.isImage) graph.cache.full(source, entry, if (entry.location.sourceId == LOCAL_SOURCE) Long.MAX_VALUE else IMAGE_LIMIT, progress) else null
                if (generation != viewGeneration) { lease?.close(); return@launch }
                imageLease = lease
                val document = if (entry.isText) TextDocument.read(source, entry) else null
                val text = document?.let { TextPreview(it.bytes, !it.complete) }
                val pdf = if (entry.isPdf) PdfDocument.open(getApplication(), entry) else null
                val archive = if (entry.isArchive) ArchiveDocument.open(graph.cache.channel(source, entry)) else null
                val decoded = withContext(Dispatchers.Default) { document?.decode().orEmpty() }
                if (generation == viewGeneration) mutableState.update { it.copy(preview = it.preview?.copy(loading = false, asset = lease?.file, text = text, document = document, decodedText = decoded, pdf = pdf, archive = archive)) }
                else { pdf?.close(); archive?.close() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { DiagnosticLog.record("PREVIEW", "read", e); if (generation == viewGeneration) mutableState.update { it.copy(preview = it.preview?.copy(loading = false, error = readableError(e))) } }
        }
    }

    fun closePreview() {
        lastViewedImageId = null
        viewing?.cancel(); viewGeneration++
        imageLease?.close(); imageLease = null
        val old = state.value.preview
        if (old?.pdf != null || old?.archive != null) graph.scope.launch { old.pdf?.close(); old.archive?.close() }
        mutableState.update { it.copy(preview = null) }
    }

    fun encoding(cp949: Boolean) {
        val preview = state.value.preview ?: return
        val document = preview.document ?: return
        viewing?.cancel()
        val generation = ++viewGeneration
        // A previous encoding's byte boundary is not necessarily valid for this encoding.
        textHistory.clear()
        mutableState.update { it.copy(preview = preview.copy(loading = true)) }
        viewing = viewModelScope.launch {
            try {
                val maximum = if (document.complete && document.start == 0L) TextDocument.EDIT_LIMIT else TextDocument.CHUNK
                val doc = TextDocument.read(provider(preview.entry.location.sourceId), preview.entry, maximum, cp949, expected = document.revision)
                val decoded = withContext(Dispatchers.Default) { doc.decode() }
                if (generation == viewGeneration) mutableState.update { it.copy(preview = preview.copy(loading = false, cp949 = cp949, decodedText = decoded, document = doc, text = TextPreview(doc.bytes, !doc.complete))) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == viewGeneration) mutableState.update { it.copy(preview = preview.copy(loading = false, error = readableError(e))) } }
        }
    }

    fun editConnection(profile: ConnectionProfile? = null) {
        editing?.cancel()
        mutableState.update { it.copy(editor = ConnectionEditor(existing = profile, name = profile?.name.orEmpty(), url = profile?.url.orEmpty(), username = profile?.username.orEmpty())) }
    }
    fun updateEditor(edit: ConnectionEditor) { mutableState.update { it.copy(editor = edit.copy(result = null)) } }
    fun closeEditor() { editing?.cancel(); mutableState.update { it.copy(editor = null) } }

    fun checkOrSaveConnection(save: Boolean) {
        val edit = state.value.editor ?: return
        if (edit.busy) return
        mutableState.update { it.copy(editor = edit.copy(busy = true, result = null)) }
        editing = viewModelScope.launch {
            try {
                require(edit.name.isNotBlank()) { "연결 이름을 입력해 주세요." }
                require(edit.username.isNotBlank() && ':' !in edit.username && '\r' !in edit.username && '\n' !in edit.username) { "올바른 사용자 이름을 입력해 주세요." }
                WebDavProvider.validatedUrl(edit.url)
                val password = if (edit.existing != null && edit.password.isEmpty()) store.password(edit.existing) else edit.password
                if (save) {
                    edit.existing?.let { previous ->
                        val pending = graph.dao.tasks().first().any { it.state !in listOf("COMPLETE", "CANCELLED") && it.payload.contains(previous.id) }
                        if (pending) throw StorageException(StorageError.BUSY, "이 연결의 파일 작업을 완료하거나 취소한 뒤 수정해 주세요.")
                        if ((previous.url != edit.url || previous.username != edit.username) && graph.dao.trashItems().first().any { it.source == previous.id && it.state != "RESTORED" }) throw StorageException(StorageError.BUSY, "이 연결의 휴지통 항목을 복원하거나 비운 뒤 주소·계정을 변경해 주세요. 새 연결을 추가할 수도 있습니다.")
                    }
                    // Purge before committing credentials, even if the saving job is cancelled later.
                    edit.existing?.id?.let { sourceId ->
                        cache.clearSource(sourceId)
                        graph.cache.clearSource(sourceId)
                        folders.keys.filter { it.startsWith("$sourceId:") }.forEach { folders.remove(it) }
                    }
                    store.save(edit.existing?.id, edit.name, edit.url, edit.username, password)
                    if (state.value.editor?.token == edit.token) closeEditor()
                } else {
                    val sourceId = edit.existing?.id ?: "connection-test"
                    val entries = WebDavProvider(sourceId, edit.url, edit.username, password).list(StorageLocation(sourceId))
                    if (state.value.editor?.token == edit.token) mutableState.update { it.copy(editor = it.editor?.copy(busy = false, result = "연결 성공 · 항목 ${entries.size}개")) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = if (e is IllegalArgumentException) e.message else readableError(e)
                if (state.value.editor?.token == edit.token) mutableState.update { it.copy(editor = it.editor?.copy(busy = false, result = message)) }
            }
        }
    }

    fun removeConnection(profile: ConnectionProfile) {
        viewModelScope.launch {
            try {
                if (graph.dao.tasks().first().any { it.state !in listOf("COMPLETE", "CANCELLED") && it.payload.contains(profile.id) } || graph.dao.trashItems().first().any { it.source == profile.id && it.state != "RESTORED" }) throw StorageException(StorageError.BUSY, "진행 중 작업과 휴지통을 먼저 정리해 주세요.")
                cache.clearSource(profile.id)
                graph.cache.clearSource(profile.id)
                store.remove(profile.id)
                folders.keys.filter { it.startsWith("${profile.id}:") }.forEach { folders.remove(it) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notice(readableError(e))
            }
        }
    }

    fun notice(message: String?) { mutableState.update { it.copy(notice = message) } }
    fun visibleEntries(): List<FileEntry> = state.value.kind.let { kind ->
        (state.value.searchResults ?: state.value.entries).filter { (settings.value.showHidden || !it.name.startsWith('.')) && it.name.contains(state.value.query, ignoreCase = true) && (kind == null || kind.matches(it)) }
    }
    fun select(entry: FileEntry) { mutableState.update { it.copy(selected = if (entry.id in it.selected) it.selected - entry.id else it.selected + entry.id) } }
    internal fun paintSelection(ids: Set<String>, selecting: Boolean, origin: ExplorerState) {
        if (ids.isEmpty()) return
        mutableState.update { current ->
            if (current.location != origin.location || current.viewportEpoch != origin.viewportEpoch ||
                current.query != origin.query || current.kind != origin.kind || current.preview != null) current
            else current.copy(selected = if (selecting) current.selected + ids else current.selected - ids)
        }
    }
    fun selectAll() { mutableState.update { it.copy(selected = visibleEntries().map { e -> e.id }.toSet()) } }
    fun clearSelection() { mutableState.update { it.copy(selected = emptySet()) } }
    fun selection() = (state.value.searchResults ?: state.value.entries).filter { it.id in state.value.selected }
    /** Folder position from before the search; a changed query or type starts results at the top, clearing the query returns here. */
    private var searchReturn: ViewportAnchor? = null
    fun query(value: String) {
        flushViewport()
        searching?.cancel(); searchGeneration++
        val current = state.value
        val anchor = if (value.isEmpty() && current.kind == null) searchReturn.also { searchReturn = null } ?: current.anchor
            else { if (searchReturn == null) searchReturn = current.anchor; if (value == current.query) current.anchor else ViewportAnchor(layout = current.anchor.layout) }
        mutableState.update { it.copy(query = value, searchResults = null, searching = false, index = anchor.index, offset = anchor.offset, anchor = anchor) }
    }
    fun recursiveSearch(cancel: Boolean = false) {
        flushViewport()
        searching?.cancel()
        val generation = ++searchGeneration
        if (cancel) { mutableState.update { it.copy(searching = false) }; return }
        val root = state.value.location ?: return
        val term = state.value.query
        if (term.isBlank()) { notice("검색할 이름을 먼저 입력해 주세요."); return }
        mutableState.update { it.copy(searching = true, searchResults = emptyList()) }
        searching = viewModelScope.launch(Dispatchers.IO) {
            try {
                val source = provider(root.sourceId); val queue = ArrayDeque<StorageLocation>(); queue.add(root)
                val found = mutableListOf<FileEntry>()
                while (queue.isNotEmpty()) {
                    ensureActive()
                    val entries = source.list(queue.removeFirst()).filterNot { OperationEngine.isManagedName(it.name) }
                    for (entry in entries) {
                        if (entry.name.contains(term, true)) found += entry
                        if (entry.isDirectory && (settings.value.showHidden || !entry.name.startsWith('.'))) queue.add(entry.location)
                    }
                    ensureActive()
                    if (generation == searchGeneration) mutableState.update { it.copy(searchResults = sortedEntries(found.toList(), SortSpec(it.sort, it.direction))) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == searchGeneration) notice("일부 폴더를 검색하지 못했습니다. ${readableError(e)}") }
            finally { if (generation == searchGeneration) mutableState.update { it.copy(searching = false) } }
        }
    }
    fun updateSettings(transform: (ExplorerSettings) -> ExplorerSettings) {
        flushViewport()
        viewModelScope.launch { graph.settings.update(transform) }
    }
    fun saveSettings(value: ExplorerSettings) { viewModelScope.launch { graph.settings.save(value) } }
    fun refreshPlaces() { viewModelScope.launch { places.value = graph.dao.savedValues("favorite:%") + graph.dao.savedValues("recent:%", 12) } }
    fun favorite() {
        val location = state.value.location ?: return
        viewModelScope.launch {
            val key = "favorite:${location.key}"
            if (graph.dao.saved(key) != null) graph.dao.removeSaved(key) else graph.dao.save(SavedValue(key, location.encode()))
            refreshPlaces()
        }
    }
    fun enqueue(kind: String, sources: List<StorageLocation> = selection().map { it.location }, destination: StorageLocation? = state.value.location, policy: ConflictPolicy = ConflictPolicy.SKIP, extra: JSONObject = JSONObject(), onQueued: () -> Unit = {}, onFinished: () -> Unit = {}, preserveSelection: Boolean = false) {
        viewModelScope.launch {
            try {
                graph.ready.await()
                val taskId = graph.operations.enqueue(kind, sources, destination, policy, extra)
                onQueued()
                if (!preserveSelection) clearSelection()
                TransferScheduler.schedule(getApplication(), graph.operations.needsNetwork(taskId))
                // The header and task journal already expose progress. Do not interrupt
                // each file operation with an acknowledgement dialog.
            } catch (e: Exception) { if (e is CancellationException) throw e; notice(readableError(e)) }
            finally { onFinished() }
        }
    }
    fun readMoreText(forEdit: Boolean = false, previous: Boolean = false) {
        val current = state.value.preview ?: return
        if (forEdit && !current.entry.isEditableText) { notice("이 형식은 읽기 전용입니다."); return }
        viewing?.cancel()
        val generation = ++viewGeneration
        mutableState.update { it.copy(preview = current.copy(loading = true)) }
        viewing = viewModelScope.launch {
            try {
                val old = current.document ?: return@launch
                val offset = if (forEdit) 0 else if (previous) textHistory.removeLastOrNull() ?: 0 else (old.start + old.bytes.size).also { textHistory.add(old.start) }
                val maximum = if (forEdit) TextDocument.EDIT_LIMIT else TextDocument.CHUNK
                val doc = TextDocument.read(provider(current.entry.location.sourceId), current.entry, maximum, current.cp949, offset, old.revision, old.charset.name())
                val decoded = withContext(Dispatchers.Default) { doc.decode() }
                if (generation == viewGeneration) mutableState.update { it.copy(preview = current.copy(loading = false, document = doc, text = TextPreview(doc.bytes, !doc.complete), decodedText = decoded)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == viewGeneration) mutableState.update { it.copy(preview = current.copy(loading = false, error = readableError(e))) } }
        }
    }
    fun saveText(text: String, utf8: Boolean = false, newName: String? = null) {
        val current = state.value.preview ?: return
        if (!current.entry.isEditableText) { notice("이 형식은 읽기 전용입니다."); return }
        val doc = current.document ?: return
        viewModelScope.launch { try {
            val bytes = withContext(Dispatchers.Default) { doc.encode(text, utf8) }
            newName?.let(::requireFileName)
            val destination = newName?.let { current.entry.location.parent()!!.child(it) } ?: current.entry.location
            graph.operations.enqueueText(bytes, destination, doc.revision, newName != null)
            TransferScheduler.schedule(getApplication(), destination.sourceId != LOCAL_SOURCE)
            notice("저장 작업을 추가했습니다. ‘작업’에서 결과를 확인하세요.")
            closePreview()
        } catch (e: CancellationException) { throw e }
        catch (e: java.nio.charset.CharacterCodingException) { notice("현재 인코딩으로 저장할 수 없는 문자가 있습니다. UTF-8 저장을 선택해 주세요.") }
        catch (e: Exception) { notice(e.message ?: "저장할 수 없습니다.") } }
    }
    fun draft(value: String?) { mutableState.update { it.copy(preview = it.preview?.copy(draft = value)) } }
    fun archiveSelection(value: Set<String>) { mutableState.update { it.copy(preview = it.preview?.copy(archiveSelected = value)) } }
    fun archivePage(value: String?) { mutableState.update { it.copy(preview = it.preview?.copy(archiveCurrent = value)) } }
    suspend fun textUnchanged(): Boolean {
        val preview = state.value.preview ?: return false
        return preview.document?.revision?.matches(provider(preview.entry.location.sourceId).stat(preview.entry.location).revision) == true
    }
    fun shareSelected() {
        val selected = selection().filterNot { it.isDirectory }
        viewModelScope.launch {
            try {
                sharing.forEach { it.close() }; sharing.clear()
                val context = getApplication<Application>()
                val uris = ArrayList<android.net.Uri>()
                for (entry in selected) {
                    val lease = graph.cache.full(provider(entry.location.sourceId), entry)
                    sharing += lease
                    uris += androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", lease.file, entry.name)
                }
                if (uris.isEmpty()) return@launch
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = android.content.ClipData.newUri(context.contentResolver, "선택한 파일", uris.first()).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
                context.startActivity(android.content.Intent.createChooser(intent, "파일 공유").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice(readableError(e)) }
        }
    }
    override fun onCleared() { closePreview(); sharing.forEach { it.close() }; super.onCleared() }
}
