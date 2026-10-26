package com.quickfiles.app

import android.content.Context
import com.quickfiles.app.data.*
import com.quickfiles.app.operations.OperationEngine
import com.quickfiles.app.storage.StorageRepository
import com.quickfiles.app.storage.ContentCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

@androidx.annotation.Keep
interface AppGraphOwner { val appGraph: AppGraph }

class AppGraph(context: Context, val repository: StorageRepository = StorageRepository(context)) {
    val app = context.applicationContext as android.app.Application
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dao = ExplorerDatabase.get(app).dao()
    val settings = SettingsStore(app)
    val cache = ContentCache(File(app.cacheDir, "content-v1")) { settings.settings.first().cacheMiB * 1024L * 1024 }
    val operations = OperationEngine(dao, repository::provider, File(app.filesDir, "transfer-staging"), cache::channel)
    val playback = com.quickfiles.app.playback.PlaybackConnection(app)
    val imageLoader = coil3.ImageLoader.Builder(app).components { add(coil3.gif.AnimatedImageDecoder.Factory()) }.build()
    // Default bitmap decoder keeps grid GIFs still; animated decoding belongs to the viewer.
    val thumbnailLoader = coil3.ImageLoader.Builder(app).build()
    val thumbnailRequests = kotlinx.coroutines.sync.Semaphore(2)
    val ready = scope.async { dao.recoverTasks() }
    val workspace = com.quickfiles.app.ui.WorkspaceController(this)
    companion object {
        fun get(context: Context): AppGraph = (context.applicationContext as AppGraphOwner).appGraph
    }
}
