package com.quickfiles.app

import coil3.request.crossfade
import android.content.Context
import com.quickfiles.app.data.*
import com.quickfiles.app.operations.OperationEngine
import com.quickfiles.app.storage.StorageRepository
import com.quickfiles.app.storage.ContentCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import java.io.File

@androidx.annotation.Keep
interface AppGraphOwner { val appGraph: AppGraph }

class AppGraph(context: Context, val repository: StorageRepository = StorageRepository(context)) {
    val app = context.applicationContext as android.app.Application
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dao = ExplorerDatabase.get(app).dao()
    val settings = SettingsStore(app)
    val cache = ContentCache(File(app.cacheDir, "content-v1"), repository.offline::copyOf) { settings.settings.first().cacheMiB * 1024L * 1024 }
    val operations = OperationEngine(dao, repository::provider, File(app.filesDir, "transfer-staging"), cache::channel)
    val playback = com.quickfiles.app.playback.PlaybackConnection(app)
    val imageLoader = coil3.ImageLoader.Builder(app).components {
        add(com.quickfiles.app.viewer.SniffingSvgDecoderFactory()); add(coil3.gif.AnimatedImageDecoder.Factory())
    }.build()
    // Default bitmap decoder keeps grid GIFs still; animated decoding belongs to the viewer.
    // Thumbnails that arrive from disk or the network fade in over the type icon (memory-cache hits appear at once).
    val thumbnailLoader = coil3.ImageLoader.Builder(app).components { add(com.quickfiles.app.viewer.SniffingSvgDecoderFactory()) }
        .crossfade(com.quickfiles.app.ui.MaterialMotion.THUMBNAIL_FADE_MS).build()
    val thumbnailRequests = kotlinx.coroutines.sync.Semaphore(2)
    val mediaThumbnails = com.quickfiles.app.storage.MediaThumbnails(File(app.cacheDir, "media-thumbs"))
    /** Remote video frames read up to [com.quickfiles.app.storage.MediaThumbnails.REMOTE_BUDGET] each, so they wait for an unmetered network. */
    fun unmetered(): Boolean = app.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == false
    val ready = scope.async { dao.recoverTasks() }
    val workspace = com.quickfiles.app.ui.WorkspaceController(this)
    /** Cache copies handed to apps that asked Gotgan to pick a file; pinned so eviction cannot pull them away mid-read. */
    val pickedLeases = java.util.ArrayDeque<ContentCache.Lease>()

    private val startedAt = System.currentTimeMillis()

    init {
        scope.launch {
            ready.await()
            val state: suspend (String) -> String? = { dao.task(it)?.state }
            runCatching { repository.inbox.cleanup(state, processStartedAt = startedAt) }
            // Received files are removed once the task saving them completes.
            dao.tasks().map { tasks -> tasks.filter { it.state == "COMPLETE" || it.state == "CANCELLED" }.map { it.id }.toSet() }
                .distinctUntilChanged().drop(1).collect { runCatching { repository.inbox.cleanup(state) } }
        }
    }
    fun keepPicked(lease: ContentCache.Lease) = synchronized(pickedLeases) {
        pickedLeases.addLast(lease)
        while (pickedLeases.size > 64) pickedLeases.removeFirst().close()
    }
    companion object {
        fun get(context: Context): AppGraph = (context.applicationContext as AppGraphOwner).appGraph
    }
}
