package com.quickfiles.app.storage

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.first

class StorageRepository(context: Context, private val additionalProvider: (suspend (String) -> StorageProvider?)? = null) {
    val connections = ConnectionStore(context.applicationContext)
    val local = LocalStorageProvider(Environment.getExternalStorageDirectory()) { Environment.isExternalStorageManager() }
    val inbox = Inbox(java.io.File(context.applicationContext.filesDir, "inbox"))
    suspend fun provider(id: String): StorageProvider {
        additionalProvider?.invoke(id)?.let { return it }
        if (id == LOCAL_SOURCE) return local
        if (id == INBOX_SOURCE) return inbox.provider
        val profile = connections.profiles.first().firstOrNull { it.id == id }
            ?: throw StorageException(StorageError.NOT_FOUND, "저장소 연결을 찾을 수 없습니다.")
        if (profile.protocol == Protocol.WEBDAV) return WebDavProvider(id, profile.url, profile.username, connections.password(profile))
        // SMB and SFTP keep a logged-in session, so one provider per saved profile version is reused.
        synchronized(sessions) { sessions[id]?.takeIf { it.first == profile }?.let { return it.second } }
        val created = connect(id, profile.url, profile.username, connections.password(profile))
        val replaced = synchronized(sessions) {
            sessions[id]?.takeIf { it.first == profile }?.let { (created as? java.io.Closeable)?.close(); return it.second }
            sessions.put(id, profile to created)
        }
        (replaced?.second as? java.io.Closeable)?.let { runCatching { it.close() } }
        return created
    }
    private val sessions = HashMap<String, Pair<ConnectionProfile, StorageProvider>>()

    /** A provider for an address and account, without saving anything; used by the connection check too. */
    fun connect(id: String, url: String, username: String, password: String): StorageProvider = when (Protocol.of(url)) {
        Protocol.WEBDAV -> WebDavProvider(id, url, username, password)
        Protocol.SMB -> SmbProvider(id, url, username, password)
        Protocol.SFTP -> SftpProvider(id, url, username, password, connections)
    }
    suspend fun writer(id: String): WritableStorageProvider = provider(id) as? WritableStorageProvider
        ?: throw StorageException(StorageError.UNSUPPORTED, "이 저장소는 읽기 전용입니다.")
}
