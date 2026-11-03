package com.quickfiles.app.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/**
 * One schema step, written as plain SQL so the same statements run on the device (Room's SupportSQLite path)
 * and in the host JVM migration test (bundled SQLite driver). See docs/DATABASE.md before adding one.
 */
abstract class ExplorerMigration(from: Int, to: Int) : Migration(from, to) {
    /** Statements applied in order inside Room's migration transaction. */
    abstract val statements: List<String>

    final override fun migrate(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)
    final override fun migrate(connection: SQLiteConnection) = statements.forEach(connection::execSQL)
}

/**
 * Every migration of `explorer.db`, oldest first. Each step lives in its own `MIGRATION_x_y` value so parallel
 * changes only meet at one line of this list. `fallbackToDestructiveMigration` is never used: a missing step
 * must fail loudly (and ExplorerMigrationTest fails first) instead of silently erasing trash and task records.
 */
object ExplorerMigrations {
    val ALL: List<Migration> = listOf(
        // Add new steps here, e.g. MIGRATION_1_2,
    )
}
