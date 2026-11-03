package com.quickfiles.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.quickfiles.app.MainActivity
import com.quickfiles.app.R
import com.quickfiles.app.data.decodeLocation
import com.quickfiles.app.data.encode
import com.quickfiles.app.storage.StorageLocation
import java.security.MessageDigest

/** Launcher shortcuts that open a folder straight in Gotgan. */
object FolderShortcuts {
    const val ACTION = "com.quickfiles.app.OPEN_FOLDER"
    private const val EXTRA_LOCATION = "location"

    fun id(location: StorageLocation) = "folder-" + MessageDigest.getInstance("SHA-256").digest(location.key.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    fun intent(context: Context, location: StorageLocation): Intent = Intent(ACTION).setClass(context, MainActivity::class.java)
        .putExtra(EXTRA_LOCATION, location.encode()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** The folder a shortcut intent points at, or null for any other intent or a damaged extra. */
    fun location(intent: Intent?): StorageLocation? = intent?.takeIf { it.action == ACTION }?.getStringExtra(EXTRA_LOCATION)
        ?.let { runCatching { decodeLocation(it) }.getOrNull() }

    /** Asks the launcher to pin [location]; false when the launcher cannot pin shortcuts. */
    fun pin(context: Context, location: StorageLocation, label: String): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val shortcut = ShortcutInfoCompat.Builder(context, id(location))
            .setShortLabel(label.take(24).ifBlank { "곳간 폴더" }).setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_folder))
            .setIntent(intent(context, location)).build()
        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }
}
