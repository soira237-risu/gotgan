package com.quickfiles.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.data.BackupBeforeMigrationFactory
import com.quickfiles.app.data.ExplorerDatabase
import com.quickfiles.app.data.ExplorerMigrations
import com.quickfiles.app.data.MigrationBackup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Room's own validation of every migration, on the device's SQLite, plus the pre-migration backup. */
@RunWith(AndroidJUnit4::class)
class ExplorerMigrationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @get:Rule val helper = MigrationTestHelper(instrumentation, ExplorerDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun everyOlderVersionMigratesAndKeepsTrash() {
        for (version in 1 until ExplorerDatabase.VERSION) {
            val name = "migration-$version.db"
            helper.createDatabase(name, version).use { insertTrash(it::execSQL) }
            helper.runMigrationsAndValidate(name, ExplorerDatabase.VERSION, true, *ExplorerMigrations.ALL.toTypedArray()).use { db ->
                db.query("SELECT original FROM trash WHERE id = 't1'").use { assertTrue(it.moveToFirst()); assertEquals("/사진/a.jpg", it.getString(0)) }
            }
        }
    }

    @Test fun roomOpensAnOlderDatabaseThroughTheBackupFactory() = runBlocking {
        for (version in 1 until ExplorerDatabase.VERSION) {
            val name = "upgrade-$version.db"
            helper.createDatabase(name, version).use { insertTrash(it::execSQL) }
            val db = Room.databaseBuilder(context, ExplorerDatabase::class.java, name)
                .addMigrations(*ExplorerMigrations.ALL.toTypedArray())
                .openHelperFactory(BackupBeforeMigrationFactory(FrameworkSQLiteOpenHelperFactory(), ::AndroidSQLiteDriver, ExplorerDatabase.VERSION))
                .build()
            try {
                assertEquals("/사진/a.jpg", db.dao().trash("t1")?.original)
            } finally {
                db.close()
            }
            val file = context.getDatabasePath(name)
            assertEquals(MigrationBackup.snapshotFile(file, version), MigrationBackup.snapshots(file).first())
        }
    }

    @Test fun backupUsesTheDeviceDriver() {
        val name = "backup-check.db"
        helper.createDatabase(name, ExplorerDatabase.VERSION).use { insertTrash(it::execSQL) }
        val file = context.getDatabasePath(name)
        MigrationBackup.snapshots(file).forEach { it.delete() }
        val snapshot = MigrationBackup.snapshotIfOutdated(AndroidSQLiteDriver(), file, ExplorerDatabase.VERSION + 1)!!
        AndroidSQLiteDriver().open(snapshot.path).use { copy ->
            copy.prepare("SELECT original FROM trash WHERE id = 't1'").use { assertTrue(it.step()); assertEquals("/사진/a.jpg", it.getText(0)) }
            copy.prepare("PRAGMA user_version").use { it.step(); assertEquals(ExplorerDatabase.VERSION.toLong(), it.getLong(0)) }
        }
        snapshot.delete()
    }

    // Columns of the version 1 trash table; later versions must keep accepting this insert or update the test.
    private fun insertTrash(exec: (String) -> Unit) = exec(
        "INSERT INTO trash (id, source, original, directory, payloadPath, name, revision, isDirectory, state, deleted) " +
            "VALUES ('t1', 'local', '/사진/a.jpg', '/사진', '/trash/t1', 'a.jpg', 'r1', 0, 'READY', 1700000000000)",
    )
}
