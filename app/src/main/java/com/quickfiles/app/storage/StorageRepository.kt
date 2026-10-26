package com.quickfiles.app.storage

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.first

class StorageRepository(context: Context, private val additionalProvider: (suspend (String) -> StorageProvider?)? = null) {
    val connections = ConnectionStore(context.applicationContext)
    val local = LocalStorageProvider(Environment.getExternalStorageDirectory()) { Environment.isExternalStorageManager() }
    suspend fun provider(id: String): StorageProvider {
        additionalProvider?.invoke(id)?.let { return it }
        if (id == LOCAL_SOURCE) return local
        val profile = connections.profiles.first().firstOrNull { it.id == id }
            ?: throw StorageException(StorageError.NOT_FOUND, "저장소 연결을 찾을 수 없습니다.")
        return WebDavProvider(id, profile.url, profile.username, connections.password(profile))
    }
    suspend fun writer(id: String): WritableStorageProvider = provider(id) as? WritableStorageProvider
        ?: throw StorageException(StorageError.UNSUPPORTED, "이 저장소는 읽기 전용입니다.")
}
