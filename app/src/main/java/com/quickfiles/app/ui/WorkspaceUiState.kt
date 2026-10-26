package com.quickfiles.app.ui

import com.quickfiles.app.AppGraph
import com.quickfiles.app.data.SavedValue
import com.quickfiles.app.storage.StorageLocation
import com.quickfiles.app.operations.ConflictPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class PendingTransfer(val owner: Int, val sources: List<StorageLocation>, val move: Boolean, val destination: StorageLocation? = null,
    val archiveEntries: List<String>? = null, val outputFolder: String = "", val policy: ConflictPolicy = ConflictPolicy.SKIP, val submitting: Boolean = false) {
    val label get() = if (archiveEntries != null) "압축 해제" else if (move) "이동" else "복사"
    val count get() = archiveEntries?.size ?: sources.size
}
data class WorkspaceUiState(val active: Int = 0, val split: Boolean = false, val panel: String? = null, val viewerOwner: Int? = null, val pending: PendingTransfer? = null, val ready: Boolean = false)

/** Application lifetime retains transient selection preparation through Activity recreation only. */
class WorkspaceController(private val graph: AppGraph) {
    private val mutable = MutableStateFlow(WorkspaceUiState())
    val state = mutable.asStateFlow()
    init { graph.scope.launch {
        val saved = graph.dao.saved("workspace:ui")?.value?.let { runCatching { JSONObject(it) }.getOrNull() }
        mutable.update { it.copy(active = saved?.optInt("active", 0)?.coerceIn(0, 1) ?: 0, split = saved?.optBoolean("split", false) ?: false, ready = true) }
    } }
    fun activate(index: Int) { mutable.update { it.copy(active = index.coerceIn(0, 1)) }; persist() }
    fun split(enabled: Boolean) { mutable.update { it.copy(split = enabled) }; persist() }
    fun panel(value: String?) { mutable.update { it.copy(panel = value) } }
    fun viewer(owner: Int?) { mutable.update { it.copy(viewerOwner = owner) } }
    fun prepare(value: PendingTransfer) { mutable.update { it.copy(pending = value, panel = if (value.destination == null) "목적지 선택" else "목적지") } }
    fun destination(value: StorageLocation) { mutable.update { it.copy(pending = it.pending?.copy(destination = value), panel = "목적지") } }
    fun policy(value: ConflictPolicy) { mutable.update { it.copy(pending = it.pending?.copy(policy = value)) } }
    fun submitting(value: Boolean) { mutable.update { it.copy(pending = it.pending?.copy(submitting = value)) } }
    fun cancelPreparation() { mutable.update { it.copy(pending = null, panel = null) } }
    private fun persist() {
        val current = state.value
        if (current.ready) graph.scope.launch { graph.dao.save(SavedValue("workspace:ui", JSONObject().put("active", current.active).put("split", current.split).toString())) }
    }
}
