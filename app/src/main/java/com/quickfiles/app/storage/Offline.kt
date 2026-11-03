package com.quickfiles.app.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Server files kept on the phone so they open without a connection. */
const val OFFLINE_SOURCE = "offline"

/** Sources whose files are already on this phone: no network, no cache copy, no metered-data concerns. */
fun isDeviceSource(sourceId: String) = isLocalSource(sourceId) || sourceId == INBOX_SOURCE || sourceId == OFFLINE_SOURCE

/** A provider whose files are plain files on this phone, so readers can open them in place. */
interface FileBackedProvider : StorageProvider {
    suspend fun fileForRead(location: StorageLocation): File
}

/** One kept file: where it came from, which version was kept, and the copy itself. */
data class KeptFile(val origin: StorageLocation, val revision: Revision, val file: File, val saved: Long) {
    val location: StorageLocation get() = StorageLocation(OFFLINE_SOURCE, "${file.parentFile!!.name}/${file.name}")
}

/**
 * Kept copies live in `<root>/<id>/<name>` with `<root>/<id>/.origin` describing the server file, where `<id>`
 * is a hash of the server location. They are never evicted; the user removes them.
 */
class OfflineStore(root: File) {
    val root: File = root.canonicalFile
    val provider = OfflineProvider(this)
    private val state = MutableStateFlow<Map<String, KeptFile>>(emptyMap())
    /** Kept files by their server location key. */
    val kept: StateFlow<Map<String, KeptFile>> = state.asStateFlow()

    init { reload() }

    fun reload() {
        state.value = root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(ID) }.mapNotNull(::read).associateBy { it.origin.key }
    }

    /** The kept copy of a server file, if there is one. */
    fun copyOf(origin: StorageLocation): KeptFile? = state.value[origin.key]

    /**
     * Downloads [entry] from [provider] and keeps it, replacing an older kept version only once the new one is complete.
     * The server version is checked again at the end so a file changed mid-download is not kept half old, half new.
     */
    suspend fun keep(provider: StorageProvider, entry: FileEntry, progress: (Long, Long?) -> Unit = { _, _ -> }): KeptFile = withContext(Dispatchers.IO) {
        require(!entry.isDirectory)
        require(!isDeviceSource(entry.location.sourceId)) { "이미 휴대폰에 있는 파일입니다." }
        val current = provider.stat(entry.location)
        val folder = File(root, id(entry.location))
        val staging = File(root, ".${folder.name}.part").apply { deleteRecursively(); mkdirs() }
        try {
            val name = Inbox.sanitizeName(current.name, "파일")
            val target = File(staging, name)
            provider.openRead(current.location, 0, current.revision).use { handle ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024); var count = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = handle.stream.read(buffer); if (n < 0) break
                        if (staging.usableSpace < n) throw StorageException(StorageError.NO_SPACE, "기기에 보관할 저장 공간이 부족합니다.")
                        output.write(buffer, 0, n); count += n; progress(count, current.size)
                    }
                    if (current.size != null && count != current.size) throw StorageException(StorageError.NETWORK, "파일을 끝까지 받지 못했습니다. 다시 시도해 주세요.")
                }
            }
            if (!current.revision.matches(provider.stat(current.location).revision)) throw StorageException(StorageError.CONFLICT, "받는 동안 파일이 변경되었습니다. 다시 시도해 주세요.")
            val saved = System.currentTimeMillis()
            File(staging, ORIGIN).writeText(JSONObject().put("source", current.location.sourceId).put("path", current.location.path)
                .put("size", current.size ?: -1).put("modified", current.modified ?: -1).put("etag", current.etag ?: JSONObject.NULL).put("saved", saved).toString())
            folder.deleteRecursively()
            check(staging.renameTo(folder)) { "보관한 파일을 확정하지 못했습니다." }
            val kept = read(folder) ?: error("보관한 파일을 확정하지 못했습니다.")
            state.value = state.value + (kept.origin.key to kept)
            kept
        } finally { staging.deleteRecursively() }
    }

    /** Forgets kept copies by server location; the server files are untouched. */
    suspend fun remove(origins: Collection<StorageLocation>) = withContext(Dispatchers.IO) {
        origins.forEach { File(root, id(it)).deleteRecursively() }
        state.value = state.value - origins.map { it.key }.toSet()
    }

    /** Total bytes the kept copies take. */
    fun usedBytes(): Long = state.value.values.sumOf { it.file.length() }

    internal fun byLocation(location: StorageLocation): KeptFile? {
        require(location.sourceId == OFFLINE_SOURCE)
        val folder = location.path.substringBefore('/')
        return state.value.values.firstOrNull { it.file.parentFile!!.name == folder }
    }

    private fun read(folder: File): KeptFile? = runCatching {
        val origin = JSONObject(File(folder, ORIGIN).readText())
        val file = folder.listFiles().orEmpty().single { it.isFile && it.name != ORIGIN }
        KeptFile(StorageLocation(origin.getString("source"), origin.getString("path")),
            Revision(origin.optLong("size", -1).takeIf { it >= 0 }, origin.optLong("modified", -1).takeIf { it >= 0 }, origin.optString("etag").takeIf { !origin.isNull("etag") && it.isNotEmpty() }),
            file, origin.optLong("saved"))
    }.getOrNull()

    companion object {
        private const val ORIGIN = ".origin"
        private val ID = Regex("[0-9a-f]{32}")
        fun id(origin: StorageLocation): String = MessageDigest.getInstance("SHA-256").digest(origin.key.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }
}

/** Read-only, flat view of kept files: the root lists every kept file, newest first. */
class OfflineProvider(private val store: OfflineStore) : FileBackedProvider {
    override val capabilities = StorageCapabilities(writable = false, rangedRead = true)

    private fun kept(location: StorageLocation): KeptFile {
        require(location.sourceId == OFFLINE_SOURCE)
        val kept = store.byLocation(location)
        if (kept == null || kept.location != location || !kept.file.isFile) throw StorageException(StorageError.NOT_FOUND, "기기에 보관한 파일을 찾을 수 없습니다.")
        return kept
    }
    private fun entry(kept: KeptFile) = FileEntry(kept.location, kept.file.name, false, kept.file.length(), kept.file.lastModified())

    override suspend fun list(location: StorageLocation): List<FileEntry> = withContext(Dispatchers.IO) {
        if (location.path.isNotEmpty()) throw StorageException(StorageError.NOT_FOUND, "기기에 보관한 파일을 찾을 수 없습니다.")
        store.kept.value.values.filter { it.file.isFile }.sortedByDescending { it.saved }.map(::entry)
    }
    override suspend fun stat(location: StorageLocation): FileEntry = withContext(Dispatchers.IO) {
        if (location.path.isEmpty()) FileEntry(location, "기기에 보관한 파일", true) else entry(kept(location))
    }
    override suspend fun fileForRead(location: StorageLocation): File = withContext(Dispatchers.IO) { kept(location).file }
    override suspend fun openRead(location: StorageLocation): ReadHandle = openRead(location, 0, null)
    override suspend fun openRead(location: StorageLocation, offset: Long, expected: Revision?): ReadHandle = withContext(Dispatchers.IO) {
        require(offset >= 0)
        val kept = kept(location)
        if (expected != null && !expected.matches(entry(kept).revision)) throw StorageException(StorageError.CONFLICT, "파일이 변경되었습니다. 다시 열어 주세요.")
        val stream: InputStream = FileInputStream(kept.file).also { it.channel.position(offset) }
        ReadHandle(stream, (kept.file.length() - offset).coerceAtLeast(0))
    }
}
