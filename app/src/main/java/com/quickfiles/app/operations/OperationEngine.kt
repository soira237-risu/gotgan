package com.quickfiles.app.operations

import com.quickfiles.app.data.*
import com.quickfiles.app.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID
import java.io.File
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import com.quickfiles.app.viewer.ArchiveDocument
import com.quickfiles.app.viewer.ArchiveSafety
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import kotlin.coroutines.coroutineContext

enum class ConflictPolicy { SKIP, RENAME, REPLACE, MERGE }

/** All user mutations enter this durable queue. Providers never decide conflicts or retry writes. */
class OperationEngine(
    val dao: ExplorerDao,
    private val provider: suspend (String) -> StorageProvider,
    private val workRoot: File? = null,
    private val openChannel: (suspend (StorageProvider, FileEntry) -> SeekableByteChannel)? = null,
    private val recordError: (String, String, Throwable) -> Unit = DiagnosticLog::record,
) {
    val changed = MutableSharedFlow<StorageLocation>(extraBufferCapacity = 64)
    private val lock = Mutex()
    private var running: Job? = null
    private var runningId: String? = null

    suspend fun enqueue(kind: String, sources: List<StorageLocation> = emptyList(), destination: StorageLocation? = null,
                        policy: ConflictPolicy = ConflictPolicy.SKIP, extra: JSONObject = JSONObject()): String {
        val unique = sources.distinct()
        val keys = unique.toSet()
        val roots = unique.filter { location -> generateSequence(location.parent()) { it.parent() }.none { it in keys } }
        val payload = extra.put("sources", JSONArray(roots.map { it.encode() }))
            .put("destination", destination?.encode()).put("policy", policy.name)
        val id = UUID.randomUUID().toString()
        val encoded = payload.toString().toByteArray()
        if (encoded.size > 128 * 1024) {
            val root = requireNotNull(workRoot).also { it.mkdirs() }
            val file = File(root, "$id.json")
            val envelope = JSONObject().put("specFile", file.name).put("specHash", MessageDigest.getInstance("SHA-256").digest(encoded).toHex())
                .put("sourceIds", JSONArray((roots.map { it.sourceId } + listOfNotNull(destination?.sourceId)).distinct()))
            val task = FileTask(id, kind, envelope.toString(), state = "PREPARING")
            dao.task(task)
            withContext(Dispatchers.IO) { file.outputStream().use { it.write(encoded); it.fd.sync() } }
            dao.task(task.copy(state = "QUEUED"))
        } else dao.task(FileTask(id, kind, payload.toString()))
        return id
    }

    suspend fun enqueueText(bytes: ByteArray, destination: StorageLocation, revision: Revision, asNew: Boolean): String {
        require(bytes.size <= 4 * 1024 * 1024)
        val root = requireNotNull(workRoot).also { it.mkdirs() }
        val id = UUID.randomUUID().toString(); val file = File(root, "$id.edit")
        val payload = JSONObject().put("sources", JSONArray()).put("destination", destination.encode()).put("policy", "SKIP")
            .put("textFile", file.name).put("textSize", bytes.size).put("textHash", MessageDigest.getInstance("SHA-256").digest(bytes).toHex()).put("revision", revision.encode())
        val task = FileTask(id, if (asNew) "SAVE_AS" else "SAVE", payload.toString(), state = "PREPARING")
        dao.task(task)
        withContext(Dispatchers.IO) { file.outputStream().use { it.write(bytes); it.fd.sync() } }
        dao.task(task.copy(state = "QUEUED"))
        return id
    }
    private suspend fun payload(task: FileTask): JSONObject = withContext(Dispatchers.IO) {
        val envelope = JSONObject(task.payload)
        if (!envelope.has("specFile")) return@withContext envelope
        require(envelope.getString("specFile") == "${task.id}.json")
        val file = File(requireNotNull(workRoot), "${task.id}.json")
        require(file.length() <= 64 * 1024 * 1024)
        val bytes = file.readBytes()
        require(MessageDigest.getInstance("SHA-256").digest(bytes).toHex() == envelope.getString("specHash")) { "중단된 작업 명세가 불완전합니다. 새 작업으로 다시 선택해 주세요." }
        JSONObject(bytes.toString(Charsets.UTF_8))
    }
    private suspend fun textBytes(task: String, data: JSONObject): ByteArray = withContext(Dispatchers.IO) {
        require(data.getString("textFile") == "$task.edit")
        val file = File(requireNotNull(workRoot), "$task.edit")
        require(file.length() == data.getLong("textSize") && file.length() <= 4 * 1024 * 1024)
        file.readBytes().also { require(MessageDigest.getInstance("SHA-256").digest(it).toHex() == data.getString("textHash")) { "편집 임시 데이터가 불완전합니다. 원본을 보존했습니다." } }
    }
    suspend fun retryTextAs(id: String, destination: StorageLocation): String {
        val task = requireNotNull(dao.task(id)); require(task.kind in listOf("SAVE", "SAVE_AS"))
        val data = payload(task)
        return enqueueText(textBytes(id, data), destination, decodeRevision(data.getString("revision")), true)
    }

    suspend fun pause(id: String, cancel: Boolean = false) {
        val task = dao.task(id) ?: return
        if (task.state in setOf("COMPLETE", "CANCELLED")) return
        dao.task(task.copy(state = if (cancel) "CANCELLED" else "PAUSED", message = if (cancel) "취소됨 · 완료된 파일과 복구 가능한 임시 파일은 보존됩니다." else "일시정지됨"))
        if (runningId == id) {
            val active = running
            active?.cancel(); active?.join()
            dao.items(id).flatMap { listOf(it.source, it.destination) }.filter { it.isNotEmpty() }.mapNotNull { runCatching { decodeLocation(it).parent() }.getOrNull() }.distinct().forEach { changed.tryEmit(it) }
        }
    }
    suspend fun resume(id: String) {
        val task = dao.task(id) ?: return
        if (task.state in listOf("RUNNING", "COMPLETE", "CANCELLED")) return
        dao.task(task.copy(state = "QUEUED", failed = 0, message = "중단된 항목의 상태를 확인합니다."))
    }
    suspend fun needsNetwork(id: String): Boolean {
        val task = dao.task(id) ?: return false
        val data = payload(task)
        val sources = data.getJSONArray("sources")
        if ((0 until sources.length()).any { decodeLocation(sources.getString(it)).sourceId != LOCAL_SOURCE }) return true
        if (data.optString("destination").takeIf { it.isNotEmpty() && it != "null" }?.let { decodeLocation(it).sourceId != LOCAL_SOURCE } == true) return true
        val trashIds = data.optJSONArray("trashIds") ?: return false
        return (0 until trashIds.length()).any { dao.trash(trashIds.getString(it))?.source != LOCAL_SOURCE }
    }

    suspend fun drain(onProgress: (FileTask) -> Unit = {}) = lock.withLock {
        while (true) {
            coroutineContext.ensureActive()
            val task = dao.nextTask() ?: break
            coroutineScope {
                runningId = task.id
                running = launch { execute(task, onProgress) }
                running!!.join()
                running = null; runningId = null
            }
        }
    }

    private suspend fun execute(original: FileTask, onProgress: (FileTask) -> Unit) {
        var task = original.copy(state = "RUNNING", message = "준비 중", failed = 0, updated = System.currentTimeMillis())
        dao.task(task); onProgress(task)
        try {
            val data = payload(task)
            val sources = data.getJSONArray("sources").let { a -> (0 until a.length()).map { decodeLocation(a.getString(it)) } }
            val destination = data.optString("destination").takeIf { it.isNotEmpty() && it != "null" }?.let(::decodeLocation)
            val policy = ConflictPolicy.valueOf(data.getString("policy"))
            val targets = when (task.kind) {
                "RESTORE", "PURGE" -> data.getJSONArray("trashIds").let { a -> (0 until a.length()).map { a.getString(it) } }
                "ZIP", "EXTRACT" -> listOf("")
                else -> sources.map { it.encode() }.ifEmpty { listOf("") }
            }
            var completed = 0; var skipped = 0; var failed = 0
            for (target in targets) {
                coroutineContext.ensureActive()
                val resultId = stableId(task.id, "result:$target")
                val previousResult = dao.item(resultId)
                val resultSource = if (task.kind in listOf("RESTORE", "PURGE")) dao.trash(target)?.let { StorageLocation(it.source, it.original).encode() }.orEmpty() else target.ifEmpty { destination?.encode().orEmpty() }
                val result = previousResult ?: TaskItem(resultId, task.id, resultSource, "", "", "", "RESULT_PENDING")
                dao.item(result)
                try {
                    val didWork = if (previousResult?.phase == "RESULT_DONE") true else when (task.kind) {
                        "COPY", "MOVE" -> {
                            val source = decodeLocation(target)
                            requireDestination(source, requireNotNull(destination))
                            transfer(task.id, source, destination.child(source.path.substringAfterLast('/')), policy, task.kind == "MOVE", onProgress)
                        }
                        "RENAME" -> {
                            val source = decodeLocation(target)
                            requireFileName(data.getString("name"))
                            transfer(task.id, source, source.parent()!!.child(data.getString("name")), ConflictPolicy.SKIP, true, onProgress)
                        }
                        "TRASH" -> { trash(decodeLocation(target), stableId(task.id, target)); true }
                        "RESTORE" -> { restore(target); true }
                        "PURGE" -> { purge(target); true }
                        "MKDIR" -> { createFolder(task.id, requireNotNull(destination)); true }
                        "SAVE" -> { saveText(task.id, data, requireNotNull(destination)); true }
                        "SAVE_AS" -> {
                            val bytes = textBytes(task.id, data)
                            require(bytes.size <= 4 * 1024 * 1024)
                            generated(task.id, requireNotNull(destination), bytes.size.toLong(), { ByteArrayInputStream(bytes) }); true
                        }
                        "ZIP" -> { createZip(task.id, sources, requireNotNull(destination)); true }
                        "EXTRACT" -> { extractZip(task.id, sources.single(), requireNotNull(destination), data.getJSONArray("entries")); true }
                        else -> throw StorageException(StorageError.UNSUPPORTED, "지원하지 않는 작업입니다.")
                    }
                    dao.item(result.copy(phase = if (didWork) "RESULT_DONE" else "RESULT_SKIPPED", error = ""))
                    if (didWork) completed++ else skipped++
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    failed++
                    dao.item(result.copy(phase = "RESULT_FAILED", error = readableError(e)))
                    task = task.copy(message = readableError(e))
                    recordError(task.kind, "item", e)
                }
                task = (dao.task(task.id) ?: task).copy(completed = completed, skipped = skipped, failed = failed, message = task.message, updated = System.currentTimeMillis())
                dao.task(task); onProgress(task)
            }
            task = task.copy(state = when { failed > 0 && completed > 0 -> "PARTIAL"; failed > 0 -> "FAILED"; skipped > 0 -> "PARTIAL"; else -> "COMPLETE" },
                message = if (failed > 0) task.message else "완료 $completed · 건너뜀 $skipped", speed = 0)
            dao.task(task); onProgress(task)
            if (task.state == "COMPLETE" && task.kind in listOf("SAVE", "SAVE_AS")) withContext(Dispatchers.IO) { File(requireNotNull(workRoot), "${task.id}.edit").delete() }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val latest = dao.task(task.id) ?: task
                if (latest.state == "RUNNING") dao.task(latest.copy(state = "PAUSED", message = "중단됨 · 재개 시 파일 상태를 확인합니다.", speed = 0))
            }
            throw e
        } catch (e: Exception) {
            recordError(task.kind, "task", e)
            dao.task(task.copy(state = "FAILED", message = readableError(e), speed = 0))
        }
    }

    private suspend fun writer(source: String) = provider(source) as? WritableStorageProvider
        ?: throw StorageException(StorageError.UNSUPPORTED, "이 저장소는 쓰기를 지원하지 않습니다.")

    private suspend fun createFolder(task: String, destination: StorageLocation) {
        requireFileName(destination.path.substringAfterLast('/'))
        val id = stableId(task, destination.encode())
        val existing = dao.item(id)
        if (existing?.phase == "COMMITTED") return
        val w = writer(destination.sourceId)
        if (existing != null && w.find(destination) != null) throw StorageException(StorageError.CONFLICT, "폴더 생성 결과를 확인할 수 없습니다. 기존 폴더를 보존했습니다.")
        if (w.find(destination) != null) throw StorageException(StorageError.CONFLICT, "같은 이름의 항목이 있습니다.")
        val item = TaskItem(id, task, "", destination.encode(), "", "", "CREATING")
        dao.item(item)
        w.createDirectory(destination)
        dao.item(item.copy(phase = "COMMITTED")); changed.tryEmit(destination.parent()!!)
    }

    private suspend fun transfer(task: String, source: StorageLocation, requested: StorageLocation, policy: ConflictPolicy,
                                 moving: Boolean, onProgress: (FileTask) -> Unit): Boolean {
        if (source == requested) return false
        val src = provider(source.sourceId)
        val dest = writer(requested.sourceId)
        val id = stableId(task, source.encode() + requested.encode())
        var item = dao.item(id)
        if (item?.phase == "DONE") return true
        if (item?.phase == "STAGE_CONFLICT") throw StorageException(StorageError.CONFLICT, "임시 파일 이름이 충돌한 작업입니다. 기존 항목을 보존했습니다. 새 작업으로 다시 시도해 주세요.")
        val sourceEntry = src.find(source)
        // Native MOVE acknowledgement may be lost. Without a prior fingerprint we never infer success.
        if (sourceEntry == null) {
            if (item?.phase == "SOURCE_TRASHED" || item?.phase == "COMMITTED" && !moving) { dao.item(item.copy(phase = "DONE")); return true }
            throw StorageException(StorageError.CONFLICT, "원본이 없어 이전 작업 결과를 확정하지 못했습니다. 대상과 작업 기록을 확인해 주세요.")
        }
        var destination = item?.destination?.let(::decodeLocation) ?: requested
        if (item == null) {
            val conflict = dest.find(destination)
            if (conflict != null) {
                when (policy) {
                    ConflictPolicy.SKIP -> return false
                    ConflictPolicy.RENAME -> destination = uniqueName(dest, requested)
                    ConflictPolicy.REPLACE -> if (conflict.isDirectory || sourceEntry.isDirectory) throw StorageException(StorageError.CONFLICT, "폴더는 교체할 수 없습니다. 병합 또는 이름 바꾸기를 선택해 주세요.")
                    ConflictPolicy.MERGE -> if (!conflict.isDirectory || !sourceEntry.isDirectory) return false
                }
            }
            item = TaskItem(id, task, source.encode(), destination.encode(), destination.parent()!!.child(".fileexplorer-$id.part").encode(), sourceEntry.revision.encode(), targetRevision = dest.find(destination)?.revision?.encode().orEmpty())
            dao.item(item)
        }
        val revision = decodeRevision(item.revision)
        if (!revision.matches(sourceEntry.revision)) throw StorageException(StorageError.CONFLICT, "작업 시작 후 원본이 변경되었습니다.")
        if (sourceEntry.isDirectory && moving && journalledTrashInside(source)) throw StorageException(StorageError.BUSY, "폴더 안의 기존 휴지통을 복원하거나 비운 뒤 이동해 주세요.")
        if (moving && source.sourceId == destination.sourceId && item.phase == "PLANNED" && dest.find(destination) == null) {
            item = item.copy(phase = "NATIVE_MOVING")
            dao.item(item)
            dest.move(source, destination, revision)
            dao.item(item.copy(phase = "DONE"))
            changed.tryEmit(source.parent()!!); changed.tryEmit(destination.parent()!!)
            return true
        }
        if (item.phase == "NATIVE_MOVING") throw StorageException(StorageError.CONFLICT, "이전 이동 결과를 확인해 주세요. 작업을 반복하지 않았습니다.")
        if (sourceEntry.isDirectory) {
            if (dest.find(destination) == null) {
                if (item.phase == "CREATING") throw StorageException(StorageError.CONFLICT, "이전 폴더 생성 결과가 불확실합니다.")
                item = item.copy(phase = "CREATING"); dao.item(item)
                dest.createDirectory(destination)
                item = item.copy(phase = "DIRECTORY"); dao.item(item)
            } else if (item.phase == "PLANNED" && policy != ConflictPolicy.MERGE) {
                throw StorageException(StorageError.CONFLICT, "대상 폴더가 다른 작업에서 생성되었습니다.")
            }
            val children = src.list(source)
            if (children.any { it.accessError != null }) throw StorageException(StorageError.ACCESS, "읽지 못하는 하위 항목이 있습니다. 원본 폴더를 보존했습니다.")
            if (children.any { isManagedName(it.name) }) throw StorageException(StorageError.BUSY, "폴더 안의 휴지통 또는 임시 작업을 정리한 뒤 다시 시도해 주세요.")
            val snapshot = children.associate { it.name to it.revision }
            var all = true
            // Copy first, then trash the complete original tree only if every child was verified.
            for (child in children) if (!transfer(task, child.location, destination.child(child.name), policy, false, onProgress)) all = false
            val after = src.list(source)
            if (after.size != snapshot.size || after.any { snapshot[it.name]?.matches(it.revision) != true }) throw StorageException(StorageError.CONFLICT, "원본 폴더 내용이 변경되었습니다. 원본을 보존했습니다.")
            if (moving && all) {
                verifyCopiedTree(task, source, destination)
                trash(source, stableId(id, "source"), revision)
            }
            if (all) dao.item(item.copy(phase = "DONE"))
            changed.tryEmit(destination.parent()!!); return all
        }
        if (item.phase == "COMMITTED" || item.phase == "SOURCE_TRASHED") {
            if (item.hash.isEmpty() || digest(dest, destination, null) != item.hash) throw StorageException(StorageError.CONFLICT, "확정된 대상이 변경되었습니다. 원본을 보존했습니다.")
            if (moving && item.phase != "SOURCE_TRASHED") trash(source, stableId(id, "source"), revision, item.hash)
            dao.item(item.copy(phase = "DONE")); return true
        }
        val stage = decodeLocation(item.stage)
        if (item.phase == "COMMITTING") {
            val final = dest.find(destination)
            if (final != null && dest.find(stage) == null && item.hash.isNotEmpty() && digest(dest, destination, final.revision) == item.hash) {
                item = item.copy(phase = "COMMITTED"); dao.item(item)
            } else throw StorageException(StorageError.CONFLICT, "확정 결과가 불확실합니다. 원본과 임시 파일을 보존했습니다.")
        } else {
            if (item.phase != "VERIFIED") {
                val oldStage = dest.find(stage)
                var resumeOffset = 0L
                val hash = MessageDigest.getInstance("SHA-256")
                if (oldStage != null) {
                    if (item.phase != "STAGING") throw StorageException(StorageError.CONFLICT, "기존 임시 항목을 덮어쓸 수 없습니다.")
                    // This exact unique name was recorded before creating it. Never delete a directory here.
                    if (oldStage.isDirectory) throw StorageException(StorageError.CONFLICT, "임시 경로가 폴더로 변경되었습니다.")
                    if (dest is LocalStorageProvider && revision.strongEtag != null && item.bytes > 0 && item.hash.isNotEmpty() && (oldStage.size ?: 0) >= item.bytes) {
                        dest.openRead(stage, 0, oldStage.revision).use { handle ->
                            var left = item.bytes; val buffer = ByteArray(128 * 1024)
                            while (left > 0) { coroutineContext.ensureActive(); val n = handle.stream.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt()); if (n < 0) throw StorageException(StorageError.CONFLICT, "이어받기 임시 파일이 변경되었습니다."); hash.update(buffer, 0, n); left -= n }
                        }
                        if ((hash.clone() as MessageDigest).digest().toHex() != item.hash) throw StorageException(StorageError.CONFLICT, "이어받기 내용이 변경되었습니다. 원본을 보존했습니다.")
                        resumeOffset = item.bytes
                    } else dest.delete(stage, oldStage.revision)
                }
                item = item.copy(phase = "STAGING", bytes = resumeOffset); dao.item(item)
                var lastUpdate = System.currentTimeMillis(); val started = lastUpdate
                val sourceHandle = try { src.openRead(source, resumeOffset, revision) } catch (e: StorageException) {
                    if (resumeOffset <= 0 || e.kind != StorageError.UNSUPPORTED) throw e
                    dest.delete(stage, dest.stat(stage).revision); resumeOffset = 0; hash.reset()
                    src.openRead(source, 0, revision)
                }
                sourceHandle.use { handle ->
                    val stream = java.security.DigestInputStream(handle.stream, hash)
                    val progress: (Long) -> Unit = { bytes ->
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 500) {
                            lastUpdate = now
                            runBlocking {
                                if (dest is LocalStorageProvider && revision.strongEtag != null) {
                                    val checkpointHash = runCatching { (hash.clone() as MessageDigest).digest().toHex() }.getOrNull()
                                    if (checkpointHash != null) dao.item(requireNotNull(item).copy(bytes = bytes, hash = checkpointHash))
                                }
                                dao.task((dao.task(task) ?: return@runBlocking).copy(bytes = bytes, totalBytes = sourceEntry.size ?: -1, speed = (bytes - resumeOffset) * 1000 / maxOf(1, now - started), message = if (resumeOffset > 0) "이어받는 중" else "전송 중", updated = now)).also { dao.task(task)?.let(onProgress) }
                            }
                        }
                    }
                    if (resumeOffset > 0 && dest is LocalStorageProvider) dest.appendStage(stage, resumeOffset, oldStage!!.revision, sourceEntry.size?.minus(resumeOffset), { stream }, progress)
                    else try { dest.put(stage, sourceEntry.size, { stream }, progress = progress) }
                    catch (e: StorageException) {
                        if (e.kind == StorageError.CONFLICT) dao.item(requireNotNull(item).copy(phase = "STAGE_CONFLICT"))
                        throw e
                    }
                }
                val sourceHash = hash.digest().toHex()
                if (!revision.matches(src.stat(source).revision)) throw StorageException(StorageError.CONFLICT, "읽는 동안 원본이 변경되었습니다.")
                dao.task((dao.task(task) ?: error("작업 기록 없음")).copy(message = "SHA-256 내용 검증 중", speed = 0)).also { dao.task(task)?.let(onProgress) }
                if (digest(dest, stage, null) != sourceHash) throw StorageException(StorageError.CONFLICT, "내용 검증에 실패했습니다. 원본을 보존했습니다.")
                item = item.copy(phase = "VERIFIED", hash = sourceHash, bytes = sourceEntry.size ?: 0); dao.item(item)
            }
            if (item.hash != digest(dest, stage, null)) throw StorageException(StorageError.CONFLICT, "임시 파일이 변경되었습니다.")
            val conflict = dest.find(destination)
            if (conflict != null) {
                if (policy != ConflictPolicy.REPLACE || conflict.isDirectory) throw StorageException(StorageError.CONFLICT, "대상이 존재합니다. 덮어쓰지 않았습니다.")
                if (item.targetRevision.isEmpty() || !decodeRevision(item.targetRevision).matches(conflict.revision)) throw StorageException(StorageError.CONFLICT, "작업 시작 후 교체 대상이 변경되었습니다. 기존 파일을 보존했습니다.")
                val backup = stableId(id, "backup")
                item = item.copy(backupId = backup); dao.item(item)
                trash(destination, backup)
            }
            item = item.copy(phase = "COMMITTING"); dao.item(item)
            dest.move(stage, destination, dest.stat(stage).revision)
            item = item.copy(phase = "COMMITTED"); dao.item(item)
        }
        if (moving) {
            if (digest(src, source, revision) != item.hash) throw StorageException(StorageError.CONFLICT, "원본이 변경되었습니다. 원본을 보존했습니다.")
            trash(source, stableId(id, "source"), revision, item.hash)
            item = item.copy(phase = "SOURCE_TRASHED"); dao.item(item)
        }
        dao.item(item.copy(phase = "DONE")); changed.tryEmit(destination.parent()!!)
        return true
    }

    suspend fun trash(location: StorageLocation, id: String, expected: Revision? = null, expectedHash: String? = null): TrashItem {
        val w = writer(location.sourceId)
        var record = dao.trash(id)
        if (record?.state == "TRASHED") return record
        val original = w.find(location)
        if (original != null && expected != null && !expected.matches(original.revision)) throw StorageException(StorageError.CONFLICT, "원본이 변경되었습니다. 원본을 보존했습니다.")
        if (record == null) {
            val entry = original ?: throw StorageException(StorageError.NOT_FOUND, "원본을 찾을 수 없습니다.")
            if (entry.isDirectory && journalledTrashInside(location)) throw StorageException(StorageError.BUSY, "하위 휴지통 항목을 먼저 정리해 주세요.")
            require(location.parent() != null)
            val directory = location.parent()!!.child(".fileexplorer-trash-$id")
            record = TrashItem(id, location.sourceId, location.path, directory.path, directory.child("content").path, entry.name, entry.revision.encode(), entry.isDirectory)
            dao.trash(record)
        }
        val dir = StorageLocation(record.source, record.directory)
        val payload = StorageLocation(record.source, record.payloadPath)
        if (record.state == "PREPARING") {
            if (w.find(dir) != null) throw StorageException(StorageError.CONFLICT, "휴지통 생성 결과를 확정할 수 없습니다. 원본을 보존했습니다.")
            w.createDirectory(dir)
            val metadata = JSONObject().put("owner", "com.quickfiles.app.trash.v1").put("id", record.id).put("source", record.source)
                .put("original", record.original).put("deleted", record.deleted).put("directory", record.isDirectory).toString().toByteArray()
            w.put(dir.child("owner.json"), metadata.size.toLong(), { ByteArrayInputStream(metadata) })
            record = record.copy(state = "READY"); dao.trash(record)
        }
        verifyTrashOwner(w, record)
        if (original != null && w.find(payload) == null) {
            if (expectedHash != null && digest(w, location, expected) != expectedHash) throw StorageException(StorageError.CONFLICT, "휴지통 이동 전 원본 내용이 달라졌습니다. 원본을 보존했습니다.")
            w.move(location, payload, decodeRevision(record.revision))
        } else if (original != null || w.find(payload) == null) {
            throw StorageException(StorageError.CONFLICT, "휴지통 이동 결과가 불확실합니다. 영구 삭제하지 않았습니다.")
        }
        record = record.copy(state = "TRASHED"); dao.trash(record)
        changed.tryEmit(location.parent()!!)
        return record
    }

    private suspend fun journalledTrashInside(location: StorageLocation): Boolean = dao.trashItems().first().any {
        it.state != "RESTORED" && it.source == location.sourceId && it.directory.startsWith(location.path + "/")
    }

    private suspend fun verifyCopiedTree(task: String, source: StorageLocation, destination: StorageLocation) {
        val p = provider(source.sourceId)
        val children = p.list(source)
        val plannedChildren = dao.items(task).filter { it.stage.isNotEmpty() && decodeLocation(it.source).parent() == source }
        if (children.size != plannedChildren.size || children.any { it.accessError != null }) throw StorageException(StorageError.CONFLICT, "원본 폴더 구성이 변경되었습니다. 원본을 보존했습니다.")
        for (entry in children) {
            val planned = dao.item(stableId(task, entry.location.encode() + destination.child(entry.name).encode()))
                ?: throw StorageException(StorageError.CONFLICT, "원본 폴더에 새 항목이 있습니다. 원본을 보존했습니다.")
            if (planned.phase != "DONE" || !decodeRevision(planned.revision).matches(entry.revision)) throw StorageException(StorageError.CONFLICT, "하위 항목이 변경되었습니다. 원본 폴더를 보존했습니다.")
            val target = decodeLocation(planned.destination)
            if (entry.isDirectory) verifyCopiedTree(task, entry.location, target)
            else if (planned.phase != "DONE" || planned.hash.isEmpty() || !decodeRevision(planned.revision).matches(entry.revision) || digest(p, entry.location, entry.revision) != planned.hash || digest(provider(target.sourceId), target, null) != planned.hash) throw StorageException(StorageError.CONFLICT, "하위 파일 내용이 변경되었습니다. 원본 폴더를 보존했습니다.")
        }
        val after = p.list(source)
        val snapshot = children.associate { it.name to it.revision }
        if (after.size != snapshot.size || after.any { snapshot[it.name]?.matches(it.revision) != true }) throw StorageException(StorageError.CONFLICT, "검증 중 원본 폴더 구성이 변경되었습니다. 원본을 보존했습니다.")
    }

    private suspend fun verifyTrashOwner(w: StorageProvider, record: TrashItem) {
        val bytes = w.openRead(StorageLocation(record.source, record.directory).child("owner.json")).use { it.stream.readAtMost(16385) }
        if (bytes.size > 16384) throw StorageException(StorageError.CONFLICT, "휴지통 소유 정보를 확인할 수 없습니다.")
        val info = JSONObject(bytes.toString(Charsets.UTF_8))
        if (info.optString("owner") != "com.quickfiles.app.trash.v1" || info.optString("id") != record.id || info.optString("source") != record.source || info.optString("original") != record.original) throw StorageException(StorageError.CONFLICT, "앱 소유 휴지통이 아닙니다.")
    }
    private suspend fun restore(id: String) {
        var record = dao.trash(id) ?: return
        if (record.state == "RESTORED") return
        val w = writer(record.source)
        if (record.state == "RESTORED_CLEANING") { cleanRestoredContainer(w, record); return }
        verifyTrashOwner(w, record)
        val original = StorageLocation(record.source, record.original); val payload = StorageLocation(record.source, record.payloadPath)
        if (record.state == "RESTORING" && w.find(payload) == null && w.find(original) != null) throw StorageException(StorageError.CONFLICT, "복원 결과를 확인해 주세요. 기존 항목을 보존했습니다.")
        if (w.find(original) != null) throw StorageException(StorageError.CONFLICT, "원래 위치에 같은 이름이 있습니다. 먼저 이름을 변경해 주세요.")
        record = record.copy(state = "RESTORING"); dao.trash(record)
        w.move(payload, original, w.stat(payload).revision)
        record = record.copy(state = "RESTORED_CLEANING"); dao.trash(record)
        changed.tryEmit(original.parent()!!)
        cleanRestoredContainer(w, record)
    }
    private suspend fun cleanRestoredContainer(w: WritableStorageProvider, record: TrashItem) {
        val directory = StorageLocation(record.source, record.directory)
        if (w.find(directory) != null) {
            val children = w.list(directory)
            if (children.any { it.name != "owner.json" }) throw StorageException(StorageError.CONFLICT, "복원은 완료했지만 휴지통에 알 수 없는 항목이 있어 보존했습니다.")
            if (children.isNotEmpty()) {
                verifyTrashOwner(w, record)
                w.delete(children.single().location, children.single().revision)
            }
            // The journal recorded RESTORED_CLEANING before metadata deletion. Only an empty owned container remains.
            if (w.list(directory).isNotEmpty()) throw StorageException(StorageError.CONFLICT, "휴지통이 변경되어 빈 폴더 정리를 중단했습니다.")
            w.delete(directory, w.stat(directory).revision)
        }
        dao.trash(record.copy(state = "RESTORED"))
    }
    private suspend fun purge(id: String) {
        var record = dao.trash(id) ?: return
        val w = writer(record.source)
        if (record.state == "RESTORED_CLEANING") { cleanRestoredContainer(w, record); return }
        val recordedDirectory = StorageLocation(record.source, record.directory)
        if (record.state == "PURGING" && w.find(recordedDirectory) == null) { dao.forgetTrash(id); return }
        if (record.state == "PURGING" && w.find(recordedDirectory.child("owner.json")) == null && w.list(recordedDirectory).isEmpty()) {
            w.delete(recordedDirectory, w.stat(recordedDirectory).revision); dao.forgetTrash(id); return
        }
        verifyTrashOwner(w, record)
        record = record.copy(state = "PURGING"); dao.trash(record)
        val payload = StorageLocation(record.source, record.payloadPath)
        if (w.find(payload) != null) deleteTree(w, payload)
        val dir = StorageLocation(record.source, record.directory)
        val remaining = w.list(dir)
        if (remaining.any { it.name != "owner.json" }) throw StorageException(StorageError.CONFLICT, "휴지통에 알 수 없는 항목이 있어 보존했습니다.")
        for (entry in remaining) w.delete(entry.location, entry.revision)
        w.delete(dir, w.stat(dir).revision)
        dao.forgetTrash(id)
    }
    private suspend fun deleteTree(w: WritableStorageProvider, location: StorageLocation) {
        coroutineContext.ensureActive()
        val entry = w.stat(location)
        if (entry.isDirectory) for (child in w.list(location)) deleteTree(w, child.location)
        w.delete(location, w.stat(location).revision)
    }

    private suspend fun saveText(task: String, data: JSONObject, destination: StorageLocation) {
        val id = stableId(task, "text")
        val old = dao.item(id)
        if (old?.phase == "DONE") return
        val w = writer(destination.sourceId)
        val expected = decodeRevision(data.getString("revision"))
        if (!expected.matches(w.stat(destination).revision)) throw StorageException(StorageError.CONFLICT, "외부에서 수정되었습니다. 다시 열거나 다른 이름으로 저장해 주세요.")
        // The editor encodes the entire file before queueing; no preview prefix can enter this operation.
        val bytes = textBytes(task, data)
        require(bytes.size <= 4 * 1024 * 1024)
        if (old != null) throw StorageException(StorageError.CONFLICT, "저장 결과를 확인한 뒤 다시 열어 주세요. 원본 또는 휴지통을 보존했습니다.")
        val stage = destination.parent()!!.child(".fileexplorer-$id.part")
        val item = TaskItem(id, task, destination.encode(), destination.encode(), stage.encode(), expected.encode(), "STAGING")
        dao.item(item)
        w.put(stage, bytes.size.toLong(), { ByteArrayInputStream(bytes) })
        if (digest(w, stage, null) != MessageDigest.getInstance("SHA-256").digest(bytes).toHex()) throw StorageException(StorageError.CONFLICT, "저장 내용 검증에 실패했습니다.")
        if (!expected.matches(w.stat(destination).revision)) throw StorageException(StorageError.CONFLICT, "파일이 변경되어 저장을 중단했습니다.")
        trash(destination, stableId(id, "backup"))
        w.move(stage, destination, w.stat(stage).revision)
        dao.item(item.copy(phase = "DONE")); changed.tryEmit(destination.parent()!!)
    }

    private suspend fun generated(task: String, destination: StorageLocation, length: Long?, input: () -> InputStream) {
        val w = writer(destination.sourceId)
        val id = stableId(task, "generated:${destination.key}")
        var item = dao.item(id)
        if (item?.phase == "DONE") return
        if (item?.phase == "STAGE_CONFLICT") throw StorageException(StorageError.CONFLICT, "임시 파일 이름 충돌로 중단한 작업입니다. 기존 항목을 보존했습니다.")
        if (item == null) {
            if (w.find(destination) != null) throw StorageException(StorageError.CONFLICT, "대상 파일이 이미 있습니다. 덮어쓰지 않았습니다.")
            item = TaskItem(id, task, "", destination.encode(), destination.parent()!!.child(".fileexplorer-$id.part").encode(), "")
            dao.item(item)
        }
        val stage = decodeLocation(item.stage)
        if (item.phase == "COMMITTING") {
            if (w.find(stage) == null && w.find(destination) != null && item.hash.isNotEmpty() && digest(w, destination, null) == item.hash) { dao.item(item.copy(phase = "DONE")); return }
            throw StorageException(StorageError.CONFLICT, "이전 확정 결과가 불확실합니다. 임시 파일을 보존했습니다.")
        }
        if (item.phase != "VERIFIED") {
            val previous = w.find(stage)
            if (previous != null) {
                if (item.phase != "STAGING" || previous.isDirectory) throw StorageException(StorageError.CONFLICT, "임시 파일을 확인할 수 없습니다.")
                w.delete(stage, previous.revision)
            }
            item = item.copy(phase = "STAGING"); dao.item(item)
            val hash = MessageDigest.getInstance("SHA-256")
            try { w.put(stage, length, { java.security.DigestInputStream(input(), hash) }) }
            catch (e: StorageException) {
                if (e.kind == StorageError.CONFLICT) dao.item(item.copy(phase = "STAGE_CONFLICT"))
                throw e
            }
            val digest = hash.digest().toHex()
            if (digest(w, stage, null) != digest) throw StorageException(StorageError.CONFLICT, "압축 작업의 파일 검증에 실패했습니다.")
            item = item.copy(phase = "VERIFIED", hash = digest); dao.item(item)
        }
        item = item.copy(phase = "COMMITTING"); dao.item(item)
        w.move(stage, destination, w.stat(stage).revision)
        dao.item(item.copy(phase = "DONE")); changed.tryEmit(destination.parent()!!)
    }

    private suspend fun createZip(task: String, sources: List<StorageLocation>, destination: StorageLocation) = withContext(Dispatchers.IO) {
        require(sources.isNotEmpty())
        sources.forEach { requireDestination(it, destination) }
        val root = requireNotNull(workRoot).also { it.mkdirs() }
        val file = File(root, "$task.zip")
        // Journal the owned local staging artifact before opening it for any write.
        val stageId = stableId(task, "zip-local")
        val stageRecord = dao.item(stageId) ?: TaskItem(stageId, task, "", destination.encode(), file.name, "").also { dao.item(it) }
        try {
            if (stageRecord.phase != "VERIFIED" || !file.exists()) {
                dao.item(stageRecord.copy(phase = "STAGING"))
                var count = 0; var inputBytes = 0L
                val paths = HashSet<String>()
                ZipArchiveOutputStream(object : java.io.FilterOutputStream(file.outputStream()) {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (file.length() + len > ArchiveSafety.MAX_OUTPUT || root.usableSpace < len + 16L * 1024 * 1024) throw StorageException(StorageError.NO_SPACE, "ZIP 임시 저장 공간이 부족하거나 20GiB를 초과했습니다.")
                        out.write(b, off, len)
                    }
                    override fun write(b: Int) { out.write(b) }
                }).use { zip ->
                    suspend fun add(location: StorageLocation, name: String) {
                        coroutineContext.ensureActive()
                        require(++count <= ArchiveSafety.MAX_ENTRIES) { "ZIP 항목은 10만 개까지 지원합니다." }
                        require(paths.add(ArchiveSafety.path(name))) { "중복 파일 이름이 있습니다." }
                        val p = provider(location.sourceId); val entry = p.stat(location)
                        val record = ZipArchiveEntry(name + if (entry.isDirectory) "/" else "").apply { entry.modified?.let { time = it } }
                        zip.putArchiveEntry(record)
                        if (!entry.isDirectory) {
                            p.openRead(location, 0, entry.revision).use { handle ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    coroutineContext.ensureActive(); val n = handle.stream.read(buffer); if (n < 0) break
                                    inputBytes += n; require(inputBytes <= ArchiveSafety.MAX_OUTPUT) { "ZIP 작업 입력이 20GiB를 초과합니다." }; zip.write(buffer, 0, n)
                                }
                            }
                            if (!entry.revision.matches(p.stat(location).revision)) throw StorageException(StorageError.CONFLICT, "압축하는 동안 원본이 변경되었습니다.")
                        }
                        zip.closeArchiveEntry()
                        if (entry.isDirectory) for (child in p.list(location).filterNot { isManagedName(it.name) }) add(child.location, "$name/${child.name}")
                    }
                    for (source in sources) add(source, source.path.substringAfterLast('/'))
                    zip.finish()
                }
                dao.item(stageRecord.copy(phase = "VERIFIED"))
            }
            generated(task, destination, file.length(), file::inputStream)
            dao.item(stageRecord.copy(phase = "DONE"))
            file.delete()
        } catch (e: Exception) { throw e }
    }

    private suspend fun extractZip(task: String, source: StorageLocation, destination: StorageLocation, selected: JSONArray) = withContext(Dispatchers.IO) {
        requireDestination(source, destination)
        val p = provider(source.sourceId); val entry = p.stat(source)
        val snapshotId = stableId(task, "archive-version")
        val snapshot = dao.item(snapshotId)
        if (snapshot != null && !decodeRevision(snapshot.revision).matches(entry.revision)) throw StorageException(StorageError.CONFLICT, "압축파일이 변경되었습니다. 기존 출력은 보존했습니다.")
        if (snapshot == null) dao.item(TaskItem(snapshotId, task, source.encode(), destination.encode(), "", entry.revision.encode()))
        val names = (0 until selected.length()).map { ArchiveSafety.path(selected.getString(it)) }.toSet()
        ArchiveDocument.open(requireNotNull(openChannel).invoke(p, entry)).use { archive ->
            createFolder(task, destination)
            val folders = hashSetOf("")
            suspend fun parents(path: String) {
                var current = ""
                for (part in path.split('/').filter { it.isNotEmpty() }) {
                    current = if (current.isEmpty()) part else "$current/$part"
                    if (folders.add(current)) createFolder(task, StorageLocation(destination.sourceId, destination.path + "/" + current))
                }
            }
            var total = 0L
            for (item in archive.entries) {
                coroutineContext.ensureActive()
                val path = ArchiveSafety.path(item.name)
                if (names.none { path == it || path.startsWith("$it/") }) continue
                if (item.isDirectory) { parents(path); continue }
                parents(path.substringBeforeLast('/', ""))
                val target = StorageLocation(destination.sourceId, destination.path + "/" + path)
                generated(task, target, item.size.takeIf { it >= 0 }) {
                    object : java.io.FilterInputStream(archive.open(item)) {
                        val crc = java.util.zip.CRC32(); var actual = 0L
                        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val n = `in`.read(b, off, len)
                            if (n > 0) {
                                total += n; actual += n; crc.update(b, off, n)
                                if (total > ArchiveSafety.MAX_OUTPUT) throw StorageException(StorageError.TOO_LARGE, "실제 압축 해제량이 20GiB를 초과했습니다.")
                            } else if (n < 0 && (item.size >= 0 && actual != item.size || item.crc >= 0 && crc.value != item.crc)) throw StorageException(StorageError.INVALID_RESPONSE, "압축파일의 크기 또는 CRC 검증에 실패했습니다.")
                            return n
                        }
                    }
                }
            }
            if (!entry.revision.matches(p.stat(source).revision)) throw StorageException(StorageError.CONFLICT, "압축파일이 변경되었습니다. 출력과 원본을 보존했습니다.")
        }
    }

    companion object {
        private val managedName = Regex("\\.fileexplorer-(?:trash-)?[a-f0-9-]{36}(?:\\.part)?")
        fun isManagedName(name: String) = managedName.matches(name)
        fun requireDestination(source: StorageLocation, destination: StorageLocation) {
            require(source.path.isNotEmpty()) { "저장소 루트는 복사하거나 이동할 수 없습니다." }
            require(source.sourceId != destination.sourceId || !(destination.path == source.path || destination.path.startsWith(source.path + "/"))) { "원본 폴더 안으로 복사하거나 이동할 수 없습니다." }
        }
        suspend fun uniqueName(provider: StorageProvider, requested: StorageLocation): StorageLocation {
            val name = requested.path.substringAfterLast('/')
            val entry = provider.stat(requested)
            val stem = if (!entry.isDirectory && name.contains('.')) name.substringBeforeLast('.') else name
            val extension = if (!entry.isDirectory && name.contains('.')) "." + name.substringAfterLast('.') else ""
            for (i in 1..10000) {
                val location = requested.parent()!!.child("$stem ($i)$extension")
                if (provider.find(location) == null) return location
            }
            throw StorageException(StorageError.CONFLICT, "사용 가능한 이름을 찾지 못했습니다.")
        }
        suspend fun digest(provider: StorageProvider, location: StorageLocation, revision: Revision?): String {
            val digest = MessageDigest.getInstance("SHA-256")
            provider.openRead(location, 0, revision).use { handle ->
                val buffer = ByteArray(128 * 1024)
                while (true) { coroutineContext.ensureActive(); val read = handle.stream.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
            }
            return digest.digest().toHex()
        }
        private fun stableId(task: String, value: String) = UUID.nameUUIDFromBytes("$task:$value".toByteArray()).toString()
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
