package com.quickfiles.app.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class PreviewLoader(private val cache: PreviewCache) {
    suspend fun image(provider: StorageProvider, entry: FileEntry, progress: (Long, Long?) -> Unit): Bitmap = withContext(Dispatchers.IO) {
        val cached: File? = if (isLocalSource(entry.location.sourceId)) null else cache.image(provider, entry, progress)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (cached != null) BitmapFactory.decodeFile(cached.path, bounds)
        else provider.openRead(entry.location).use { BitmapFactory.decodeStream(it.stream, null, bounds) }
        coroutineContext.ensureActive()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw StorageException(StorageError.READ, "이미지가 손상되었거나 JPG 형식이 아닙니다.")
        var sample = 1
        while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = if (cached != null) BitmapFactory.decodeFile(cached.path, options)
        else provider.openRead(entry.location).use { BitmapFactory.decodeStream(it.stream, null, options) }
        coroutineContext.ensureActive()
        bitmap ?: throw StorageException(StorageError.READ, "이미지를 표시할 수 없습니다.")
    }
}
