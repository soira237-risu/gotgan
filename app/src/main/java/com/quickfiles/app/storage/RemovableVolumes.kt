package com.quickfiles.app.storage

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

/** Source ids of removable volumes (USB OTG drives, SD cards): `usb-<volume uuid>`. */
const val VOLUME_PREFIX = "usb-"

/** Phone storage and removable volumes are both plain file systems behind [LocalStorageProvider]. */
fun isLocalSource(sourceId: String) = sourceId == LOCAL_SOURCE || sourceId.startsWith(VOLUME_PREFIX)
fun isVolumeSource(sourceId: String) = sourceId.startsWith(VOLUME_PREFIX)

data class RemovableVolume(
    val sourceId: String, val name: String, val directory: File, val readOnly: Boolean,
    val usb: Boolean, val totalBytes: Long, val freeBytes: Long,
) {
    val kindLabel get() = if (usb) "USB" else "SD 카드"
}

/** Stable source id for a volume; the uuid survives re-plugging, the directory name is the fallback. */
fun volumeSourceId(uuid: String?, directory: File): String {
    val raw = uuid?.takeIf { it.isNotBlank() } ?: directory.name
    val cleaned = raw.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '-' }
    return VOLUME_PREFIX + cleaned.ifEmpty { "VOLUME" }
}

/**
 * Tracks mounted removable volumes. With all-files access an app may read and write a public
 * volume's directory (`/storage/XXXX-XXXX`) directly, so each one becomes a [LocalStorageProvider].
 */
class RemovableVolumes(context: Context) {
    private val app = context.applicationContext
    private val manager: StorageManager? = app.getSystemService(StorageManager::class.java)
    private val mutable = MutableStateFlow<List<RemovableVolume>>(emptyList())
    val volumes: StateFlow<List<RemovableVolume>> = mutable.asStateFlow()
    private val providers = HashMap<String, Pair<File, LocalStorageProvider>>()

    init {
        val manager = manager
        if (manager != null) {
            refresh()
            manager.registerStorageVolumeCallback(app.mainExecutor, object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) = refresh()
            })
            // Some OEM builds skip the callback for USB OTG; the media broadcasts cover them too.
            val filter = IntentFilter().apply {
                listOf(Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL).forEach(::addAction)
                addDataScheme("file")
            }
            androidx.core.content.ContextCompat.registerReceiver(app, object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) = refresh() }, filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    /** Re-reads mounted volumes; free space is refreshed too, so call it after transfers or on resume. */
    fun refresh() {
        val manager = manager ?: return
        mutable.value = manager.storageVolumes.mapNotNull { volume ->
            val state = volume.state
            if (volume.isPrimary || !volume.isRemovable) return@mapNotNull null
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
            val directory = volume.directory ?: return@mapNotNull null
            val description = volume.getDescription(app).orEmpty()
            // StorageVolume has no public bus type; the system labels USB drives with "USB".
            val usb = description.contains("USB", ignoreCase = true) || !description.contains("SD", ignoreCase = true)
            RemovableVolume(volumeSourceId(volume.uuid, directory), description.ifBlank { if (usb) "USB 저장소" else "SD 카드" }, directory,
                state == Environment.MEDIA_MOUNTED_READ_ONLY, usb, directory.totalSpace, directory.usableSpace)
        }.distinctBy { it.sourceId }
    }

    fun volume(sourceId: String) = volumes.value.firstOrNull { it.sourceId == sourceId }

    /** Null once the volume is unplugged, so stale tabs and queued tasks fail with a clear message. */
    @Synchronized fun provider(sourceId: String): LocalStorageProvider? {
        val volume = volume(sourceId) ?: return null.also { providers.remove(sourceId) }
        providers[sourceId]?.takeIf { it.first == volume.directory }?.let { return it.second }
        val provider = LocalStorageProvider(volume.directory, sourceId = sourceId, rootName = volume.name, writable = !volume.readOnly,
            canRead = { Environment.isExternalStorageManager() && volume(sourceId)?.directory == volume.directory })
        providers[sourceId] = volume.directory to provider
        return provider
    }

    companion object {
        /**
         * Android lets only the system format or eject a volume, so these open the system storage screen
         * where the user picks the drive and chooses 포맷 or 꺼내기.
         */
        fun openSystemStorageSettings(context: Context): Boolean {
            for (action in listOf(Settings.ACTION_MEMORY_CARD_SETTINGS, Settings.ACTION_INTERNAL_STORAGE_SETTINGS, Settings.ACTION_SETTINGS)) {
                try { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true }
                catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
            }
            return false
        }
    }
}
