package com.quickfiles.app.data

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.quickfiles.app.operations.DiagnosticLog
import java.io.File

/**
 * Keeps a consistent copy of an older `explorer.db` before Room migrates it, so a faulty step cannot take the
 * trash journal or interrupted-task records with it. The copy is made with `VACUUM INTO`, which also folds in
 * any WAL frames that were never checkpointed.
 */
object MigrationBackup {
    private const val SUFFIX = "-backup"

    /** Snapshot file used for a database that is still at [version]. */
    fun snapshotFile(database: File, version: Int) = File(database.parentFile, "${database.name}.v$version$SUFFIX")

    /** Existing snapshots of [database], newest first. */
    fun snapshots(database: File): List<File> =
        database.parentFile?.listFiles { file -> file.name.startsWith("${database.name}.v") && file.name.endsWith(SUFFIX) }
            ?.sortedByDescending(File::lastModified).orEmpty()

    /**
     * Copies [database] when its on-disk version is older than [current], keeping the newest [keep] snapshots.
     * Returns the new snapshot, or null when there is nothing to migrate (no file, a fresh file, or up to date).
     */
    fun snapshotIfOutdated(driver: SQLiteDriver, database: File, current: Int, keep: Int = 2): File? {
        if (!database.isFile || database.length() == 0L) return null
        val connection = driver.open(database.path)
        val target = try {
            val onDisk = connection.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0).toInt() else 0 }
            if (onDisk <= 0 || onDisk >= current) return null
            snapshotFile(database, onDisk).also { target ->
                target.delete()
                connection.prepare("VACUUM INTO ?").use { it.bindText(1, target.path); it.step() }
            }
        } finally {
            connection.close()
        }
        target.setLastModified(System.currentTimeMillis())
        snapshots(database).drop(keep).forEach(File::delete)
        return target
    }
}

/**
 * Takes the [MigrationBackup] snapshot the first time Room asks for the database, which happens on Room's
 * background executor rather than on the thread that built the database.
 */
internal class BackupBeforeMigrationFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory,
    private val driver: () -> SQLiteDriver,
    private val version: Int,
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = delegate.create(configuration)
        val name = configuration.name ?: return helper // In-memory databases have nothing to keep.
        val file = configuration.context.getDatabasePath(name)
        return object : SupportSQLiteOpenHelper by helper {
            @Volatile private var checked = false

            private fun backup() {
                if (checked) return
                synchronized(this) {
                    if (checked) return
                    // A failed copy must not keep the app from opening; the migration itself is still transactional.
                    runCatching { MigrationBackup.snapshotIfOutdated(driver(), file, version) }
                        .onFailure { DiagnosticLog.record("database", "backup", it) }
                    checked = true
                }
            }

            override val writableDatabase get() = backup().let { helper.writableDatabase }
            override val readableDatabase get() = backup().let { helper.readableDatabase }
        }
    }
}
