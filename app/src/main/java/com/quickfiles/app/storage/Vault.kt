package com.quickfiles.app.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/** Files moved into the locked vault: encrypted at rest in app-private storage, readable only after the user unlocks. */
const val VAULT_SOURCE = "vault"

/**
 * Streaming file encryption: AES-256-GCM over fixed-size chunks so a large video never has to sit in memory.
 * Each chunk has its own nonce (random file prefix + counter) and authenticates its index and whether it is the last,
 * so chunks cannot be reordered, dropped or truncated without the read failing.
 */
object VaultCipher {
    private const val MAGIC = 0x47564C31 // "GVL1"
    const val CHUNK = 64 * 1024
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun newKey(): SecretKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "AES")

    fun encrypt(key: SecretKey, input: InputStream, output: OutputStream, onChunk: (Long) -> Unit = {}) {
        val prefix = ByteArray(8).also(random::nextBytes)
        val out = DataOutputStream(output)
        out.writeInt(MAGIC); out.write(prefix); out.writeInt(CHUNK)
        val buffer = ByteArray(CHUNK); var next = ByteArray(CHUNK)
        var length = input.readFully(buffer)
        var index = 0L; var total = 0L
        while (true) {
            // Read ahead one chunk to know whether this one is the last.
            val nextLength = if (length == CHUNK) input.readFully(next) else 0
            val last = nextLength <= 0
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, prefix, index, last).doFinal(buffer, 0, length.coerceAtLeast(0))
            out.writeInt(sealed.size); out.write(sealed)
            total += length.coerceAtLeast(0); onChunk(total)
            if (last) break
            System.arraycopy(next, 0, buffer, 0, nextLength); length = nextLength; index++
        }
        out.flush()
    }

    /** Decrypts a whole vault file; any damage, truncation or wrong key ends in [StorageError.INVALID_RESPONSE], never in partial success. */
    fun decrypt(key: SecretKey, input: InputStream, output: OutputStream): Long = try { decryptChunks(key, input, output) }
        catch (_: java.io.EOFException) { throw StorageException(StorageError.INVALID_RESPONSE, "보관함 파일이 잘렸습니다.") }

    private fun decryptChunks(key: SecretKey, input: InputStream, output: OutputStream): Long {
        val data = DataInputStream(input)
        if (data.readInt() != MAGIC) throw StorageException(StorageError.INVALID_RESPONSE, "보관함 파일 형식이 올바르지 않습니다.")
        val prefix = ByteArray(8).also(data::readFully)
        if (data.readInt() != CHUNK) throw StorageException(StorageError.INVALID_RESPONSE, "보관함 파일 형식이 올바르지 않습니다.")
        var index = 0L; var total = 0L
        while (true) {
            val size = data.readInt()
            if (size < 16 || size > CHUNK + 16) throw StorageException(StorageError.INVALID_RESPONSE, "보관함 파일이 손상되었습니다.")
            val sealed = ByteArray(size).also(data::readFully)
            // A chunk sealed as the last one opens only as the last one; try "more follows" first, as most chunks are.
            val plain = runCatching { cipher(Cipher.DECRYPT_MODE, key, prefix, index, false).doFinal(sealed) }.getOrNull()
            if (plain != null) { output.write(plain); total += plain.size; index++; continue }
            val final = try { cipher(Cipher.DECRYPT_MODE, key, prefix, index, true).doFinal(sealed) }
                catch (e: javax.crypto.AEADBadTagException) { throw StorageException(StorageError.INVALID_RESPONSE, "보관함 파일이 손상되었거나 다른 키로 잠겨 있습니다.", e) }
            output.write(final); total += final.size
            if (data.read() != -1) throw StorageException(StorageError.INVALID_RESPONSE, "보관함 파일 끝에 알 수 없는 데이터가 있습니다.")
            return total
        }
    }

    fun seal(key: SecretKey, plain: ByteArray): ByteArray = java.io.ByteArrayOutputStream().also { encrypt(key, plain.inputStream(), it) }.toByteArray()
    fun open(key: SecretKey, sealed: ByteArray): ByteArray = java.io.ByteArrayOutputStream().also { decrypt(key, sealed.inputStream(), it) }.toByteArray()

    private fun cipher(mode: Int, key: SecretKey, prefix: ByteArray, index: Long, last: Boolean): Cipher {
        val nonce = prefix.copyOf(12)
        for (i in 0 until 4) nonce[8 + i] = (index ushr (8 * (3 - i))).toByte()
        require(index < 0xFFFFFFFFL) { "파일이 너무 큽니다." }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(java.nio.ByteBuffer.allocate(9).putLong(index).put(if (last) 1 else 0).array())
        }
    }
    private fun InputStream.readFully(buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) { val n = read(buffer, read, buffer.size - read); if (n < 0) break; read += n }
        return read
    }
}

/** What the vault knows about one file; kept encrypted next to it. */
data class VaultItem(val id: String, val name: String, val size: Long, val modified: Long?, val origin: StorageLocation?, val added: Long) {
    val location: StorageLocation get() = StorageLocation(VAULT_SOURCE, "$id/$name")
}

/**
 * Encrypted files live in `<root>/<id>.bin` with their name and origin in `<root>/<id>.meta`, both sealed with the
 * data key. The data key exists in memory only while unlocked; [KeyGuard] wraps it at rest (Android Keystore in the app).
 * Opened files are decrypted into [openRoot], which is wiped whenever the vault locks.
 */
class Vault(root: File, openRoot: File, private val guard: KeyGuard) {
    /** Wraps and unwraps the data key; the app's implementation requires the user's fingerprint or screen lock. */
    interface KeyGuard {
        fun wrap(key: ByteArray): ByteArray
        fun unwrap(wrapped: ByteArray): ByteArray
    }

    val root: File = root.canonicalFile
    private val openRoot: File = openRoot.canonicalFile
    private val keyFile get() = File(root, "key.bin")
    @Volatile private var key: SecretKey? = null
    private val state = MutableStateFlow<List<VaultItem>?>(null)
    /** The vault's files while unlocked; null while locked. */
    val items: StateFlow<List<VaultItem>?> = state.asStateFlow()
    val provider = VaultProvider(this)

    val isSetUp: Boolean get() = keyFile.isFile
    val isUnlocked: Boolean get() = key != null

    init { openRoot.deleteRecursively() }

    /** Creates the data key on first use, or unwraps the existing one; [guard] decides whether the user may. */
    suspend fun unlock() = withContext(Dispatchers.IO) {
        root.mkdirs()
        val raw = if (keyFile.isFile) guard.unwrap(keyFile.readBytes())
            else ByteArray(32).also(SecureRandom()::nextBytes).also { keyFile.writeBytes(guard.wrap(it)) }
        val unlocked = SecretKeySpec(raw, "AES")
        raw.fill(0)
        key = unlocked
        state.value = readAll(unlocked)
    }

    /** Forgets the key and wipes every decrypted copy. */
    fun lock() {
        key = null
        state.value = null
        openRoot.deleteRecursively()
    }

    private fun requireKey(): SecretKey = key ?: throw StorageException(StorageError.ACCESS, "보관함이 잠겨 있습니다. 다시 열어 주세요.")

    /** Encrypts [entry] into the vault and returns it; the original is left for the caller to remove once this returns. */
    suspend fun add(provider: StorageProvider, entry: FileEntry, progress: (Long) -> Unit = {}): VaultItem = withContext(Dispatchers.IO) {
        require(!entry.isDirectory)
        require(entry.location.sourceId != VAULT_SOURCE)
        val key = requireKey()
        val id = UUID.randomUUID().toString()
        val data = File(root, "$id.bin"); val meta = File(root, "$id.meta")
        try {
            provider.openRead(entry.location, 0, entry.revision).use { handle ->
                data.outputStream().buffered().use { output -> VaultCipher.encrypt(key, handle.stream, output) { coroutineContext.ensureActive(); progress(it) } }
            }
            // Read it back before anything removes the original: the tag of every chunk must verify and the size must match.
            val restored = FileInputStream(data).buffered().use { VaultCipher.decrypt(key, it, NullOutput) }
            if (entry.size != null && restored != entry.size) throw StorageException(StorageError.READ, "보관함에 넣은 파일을 확인하지 못했습니다. 원본을 그대로 두었습니다.")
            val item = VaultItem(id, Inbox.sanitizeName(entry.name, "파일"), restored, entry.modified, entry.location, System.currentTimeMillis())
            meta.writeBytes(VaultCipher.seal(key, encodeMeta(item).toByteArray()))
            state.value = (state.value.orEmpty() + item)
            item
        } catch (e: Throwable) { data.delete(); meta.delete(); throw e }
    }

    /** The decrypted copy of [item] for reading in place; it lasts until the vault locks. */
    suspend fun open(item: VaultItem): File = withContext(Dispatchers.IO) {
        val key = requireKey()
        val folder = File(openRoot, item.id).apply { mkdirs() }
        val target = File(folder, item.name)
        if (target.isFile && target.length() == item.size) return@withContext target
        val part = File(folder, ".part")
        FileInputStream(File(root, "${item.id}.bin")).buffered().use { input -> part.outputStream().buffered().use { VaultCipher.decrypt(key, input, it) } }
        check(part.renameTo(target)) { "보관함 파일을 열지 못했습니다." }
        target
    }

    /** Deletes items for good: the encrypted file, its record, and any decrypted copy. */
    suspend fun delete(items: Collection<VaultItem>) = withContext(Dispatchers.IO) {
        requireKey()
        items.forEach { File(root, "${it.id}.bin").delete(); File(root, "${it.id}.meta").delete(); File(openRoot, it.id).deleteRecursively() }
        val gone = items.map { it.id }.toSet()
        state.value = state.value?.filterNot { it.id in gone }
    }

    internal fun item(location: StorageLocation): VaultItem? {
        require(location.sourceId == VAULT_SOURCE)
        val id = location.path.substringBefore('/')
        return state.value?.firstOrNull { it.id == id && it.location == location }
    }

    private fun readAll(key: SecretKey): List<VaultItem> = root.listFiles().orEmpty().filter { it.name.endsWith(".meta") }.mapNotNull { meta ->
        runCatching { decodeMeta(String(VaultCipher.open(key, meta.readBytes()))) }.getOrNull()?.takeIf { File(root, "${it.id}.bin").isFile }
    }.sortedByDescending { it.added }

    private fun encodeMeta(item: VaultItem) = JSONObject().put("id", item.id).put("name", item.name).put("size", item.size)
        .put("modified", item.modified ?: JSONObject.NULL).put("source", item.origin?.sourceId ?: JSONObject.NULL).put("path", item.origin?.path ?: JSONObject.NULL)
        .put("added", item.added).toString()
    private fun decodeMeta(text: String): VaultItem = JSONObject(text).let { json ->
        VaultItem(json.getString("id"), json.getString("name"), json.getLong("size"), json.optLong("modified").takeIf { !json.isNull("modified") },
            if (json.isNull("source")) null else StorageLocation(json.getString("source"), json.getString("path")), json.getLong("added"))
    }

    private object NullOutput : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}

/** Read-only, flat view of the unlocked vault. Files are decrypted on first read and wiped on lock. */
class VaultProvider(private val vault: Vault) : FileBackedProvider {
    override val capabilities = StorageCapabilities(writable = false, rangedRead = true)
    private fun item(location: StorageLocation): VaultItem {
        if (!vault.isUnlocked) throw StorageException(StorageError.ACCESS, "보관함이 잠겨 있습니다. 다시 열어 주세요.")
        return vault.item(location) ?: throw StorageException(StorageError.NOT_FOUND, "보관함에서 파일을 찾을 수 없습니다.")
    }
    private fun entry(item: VaultItem) = FileEntry(item.location, item.name, false, item.size, item.modified ?: item.added)

    override suspend fun list(location: StorageLocation): List<FileEntry> {
        if (location.path.isNotEmpty()) throw StorageException(StorageError.NOT_FOUND, "보관함에서 폴더를 찾을 수 없습니다.")
        val items = vault.items.value ?: throw StorageException(StorageError.ACCESS, "보관함이 잠겨 있습니다. 다시 열어 주세요.")
        return items.map(::entry)
    }
    override suspend fun stat(location: StorageLocation): FileEntry = if (location.path.isEmpty()) FileEntry(location, "잠금 보관함", true) else entry(item(location))
    override suspend fun fileForRead(location: StorageLocation): File = vault.open(item(location))
    override suspend fun openRead(location: StorageLocation): ReadHandle = openRead(location, 0, null)
    override suspend fun openRead(location: StorageLocation, offset: Long, expected: Revision?): ReadHandle = withContext(Dispatchers.IO) {
        require(offset >= 0)
        val file = fileForRead(location)
        val stream: InputStream = FileInputStream(file).also { it.channel.position(offset) }
        ReadHandle(stream, (file.length() - offset).coerceAtLeast(0))
    }
}
