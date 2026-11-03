package com.quickfiles.app.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Files other apps hand to Gotgan (share, "open with") are copied here before they are saved or viewed. */
const val INBOX_SOURCE = "inbox"

/** One file another app offers: a content stream, or plain shared text that becomes a .txt file. */
sealed interface Incoming {
    data class Content(val uri: Uri) : Incoming
    data class Text(val text: String, val subject: String? = null) : Incoming
}

/**
 * Received files live in `<root>/<batch>/<name>`. A batch is kept while a save task may still read it:
 * [attach] records the task, and [cleanup] removes batches whose task completed, batches nobody claimed
 * for a day, and unclaimed batches left over from before the current process started.
 */
class Inbox(root: File) {
    val root: File = root.canonicalFile
    val provider = InboxProvider(this.root)

    /** Copies [items] into a new batch and returns their locations, in order. Partial batches are removed on failure. */
    suspend fun receive(resolver: ContentResolver, items: List<Incoming>, progress: (Long, Long?) -> Unit = { _, _ -> }): List<StorageLocation> = withContext(Dispatchers.IO) {
        require(items.isNotEmpty())
        val batch = UUID.randomUUID().toString()
        val folder = File(root, batch)
        if (!folder.mkdirs()) throw StorageException(StorageError.READ, "받은 파일을 보관할 공간을 만들지 못했습니다.")
        try {
            val described = items.mapIndexed { index, item -> item to describe(resolver, item, index) }
            val total = described.map { it.second.second }.takeIf { sizes -> sizes.all { it != null } }?.sumOf { it!! }
            var copied = 0L
            val taken = mutableSetOf<String>()
            described.map { (item, info) ->
                val name = uniqueName(info.first, taken)
                val target = File(folder, name)
                when (item) {
                    is Incoming.Text -> { target.writeText(item.text); copied += target.length(); progress(copied, total) }
                    is Incoming.Content -> {
                        val input = resolver.openInputStream(item.uri) ?: throw StorageException(StorageError.READ, "보낸 앱에서 파일을 읽을 수 없습니다.")
                        input.use { source ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val n = source.read(buffer)
                                    if (n < 0) break
                                    if (folder.usableSpace < n) throw StorageException(StorageError.NO_SPACE, "받은 파일을 보관할 저장 공간이 부족합니다.")
                                    output.write(buffer, 0, n); copied += n; progress(copied, total)
                                }
                            }
                        }
                    }
                }
                StorageLocation(INBOX_SOURCE, "$batch/$name")
            }
        } catch (e: Throwable) {
            folder.deleteRecursively()
            if (e is SecurityException) throw StorageException(StorageError.ACCESS, "보낸 앱이 파일 읽기를 허용하지 않았습니다.", e)
            throw e
        }
    }

    /** Remembers which task copies a batch, so the batch is kept until that task completes. */
    fun attach(sources: List<StorageLocation>, taskId: String) {
        batches(sources).forEach { File(root, it).takeIf(File::isDirectory)?.let { folder -> File(folder, TASK_MARK).writeText(taskId) } }
    }

    /** Drops batches the user decided not to save. */
    fun discard(sources: List<StorageLocation>) = batches(sources).forEach { File(root, it).deleteRecursively() }

    /**
     * @param taskState current state of a task id, or null when the task no longer exists.
     * @param processStartedAt launch time; unclaimed batches from before it are orphans, since a pending
     *   destination choice never survives a restart. Batches received after launch are left alone.
     */
    suspend fun cleanup(taskState: suspend (String) -> String?, processStartedAt: Long? = null, now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        root.listFiles()?.filter { it.isDirectory }?.forEach { folder ->
            val task = File(folder, TASK_MARK).takeIf { it.isFile }?.readText()?.trim()
            val age = now - folder.lastModified()
            val remove = if (task != null) {
                val state = taskState(task)
                state == null || state == "COMPLETE" || state == "CANCELLED" || age > KEEP_FAILED_MS
            } else (processStartedAt != null && folder.lastModified() < processStartedAt) || age > UNCLAIMED_MS
            if (remove) folder.deleteRecursively()
        }
    }

    private fun batches(sources: List<StorageLocation>) = sources.filter { it.sourceId == INBOX_SOURCE }
        .map { it.path.substringBefore('/') }.filter { it.matches(BATCH) }.toSet()

    private fun describe(resolver: ContentResolver, item: Incoming, index: Int): Pair<String, Long?> = when (item) {
        is Incoming.Text -> sanitizeName(item.subject?.let { "$it.txt" }, "공유된 텍스트.txt") to item.text.toByteArray().size.toLong()
        is Incoming.Content -> {
            var name: String? = null; var size: Long? = null
            runCatching {
                resolver.query(item.uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }?.let { name = cursor.getString(it) }
                        cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it).takeIf { s -> s >= 0 } }
                    }
                }
            }
            if (name == null && item.uri.scheme == ContentResolver.SCHEME_FILE) name = item.uri.lastPathSegment
            val extension = MimeTypes.extensionFor(runCatching { resolver.getType(item.uri) }.getOrNull())
            withExtension(sanitizeName(name, "받은 파일 ${index + 1}"), extension) to size
        }
    }

    companion object {
        private const val TASK_MARK = ".task"
        private const val UNCLAIMED_MS = 24L * 60 * 60 * 1000
        private const val KEEP_FAILED_MS = 7L * 24 * 60 * 60 * 1000
        private val BATCH = Regex("[0-9a-f-]{36}")

        /** A name other apps chose is untrusted: strip path parts and control characters, keep it a valid file name. */
        fun sanitizeName(name: String?, fallback: String): String {
            val cleaned = name.orEmpty().substringAfterLast('/').substringAfterLast('\\')
                .filter { it.code >= 32 && it != '\u0000' && it != '\u007f' }.trim()
                .trimStart('.').take(200)
            return cleaned.takeIf { it.isNotBlank() && it != TASK_MARK } ?: fallback
        }

        /** Viewers pick a format by extension, so a name that lacks one borrows it from the sender's MIME type. */
        fun withExtension(name: String, extension: String?): String =
            if (extension == null || FileFormats.extension(name).isNotEmpty()) name else "$name.$extension"

        fun uniqueName(name: String, taken: MutableSet<String>): String {
            var candidate = name
            var n = 2
            val stem = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
            while (!taken.add(candidate.lowercase())) candidate = "$stem ($n)$ext".also { n++ }
            return candidate
        }
    }
}

/** Read-only view of received files, so the transfer engine and viewers treat them like any other source. */
class InboxProvider(private val root: File) : FileBackedProvider {
    override val capabilities = StorageCapabilities(writable = false, rangedRead = true)

    private fun resolve(location: StorageLocation): File {
        require(location.sourceId == INBOX_SOURCE)
        val file = File(root, location.path).canonicalFile
        if (file != root && !file.toPath().startsWith(root.toPath())) throw StorageException(StorageError.ACCESS, "받은 파일 보관함 밖의 경로입니다.")
        return file
    }
    private fun entry(file: File, location: StorageLocation) = FileEntry(location, if (location.path.isEmpty()) "받은 파일" else file.name, file.isDirectory,
        if (file.isDirectory) null else file.length(), file.lastModified())

    override suspend fun list(location: StorageLocation): List<FileEntry> = withContext(Dispatchers.IO) {
        val folder = resolve(location)
        if (!folder.isDirectory) throw StorageException(StorageError.NOT_FOUND, "받은 파일을 찾을 수 없습니다. 다시 공유해 주세요.")
        folder.listFiles().orEmpty().filter { !it.name.startsWith('.') }.map { entry(it, location.child(it.name)) }
    }
    override suspend fun stat(location: StorageLocation): FileEntry = withContext(Dispatchers.IO) {
        val file = resolve(location)
        if (!file.exists()) throw StorageException(StorageError.NOT_FOUND, "받은 파일을 찾을 수 없습니다. 다시 공유해 주세요.")
        entry(file, location)
    }
    override suspend fun fileForRead(location: StorageLocation): File = withContext(Dispatchers.IO) {
        resolve(location).takeIf { it.isFile } ?: throw StorageException(StorageError.NOT_FOUND, "받은 파일을 찾을 수 없습니다. 다시 공유해 주세요.")
    }
    override suspend fun openRead(location: StorageLocation): ReadHandle = openRead(location, 0, null)
    override suspend fun openRead(location: StorageLocation, offset: Long, expected: Revision?): ReadHandle = withContext(Dispatchers.IO) {
        require(offset >= 0)
        val file = resolve(location)
        if (!file.isFile) throw StorageException(StorageError.NOT_FOUND, "받은 파일을 찾을 수 없습니다. 다시 공유해 주세요.")
        if (expected != null && !expected.matches(entry(file, location).revision)) throw StorageException(StorageError.CONFLICT, "파일이 변경되었습니다. 다시 열어 주세요.")
        val stream: InputStream = FileInputStream(file).also { it.channel.position(offset) }
        ReadHandle(stream, (file.length() - offset).coerceAtLeast(0))
    }
}
