package com.quickfiles.app.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Host-side migration safety net, run by CI on every push. For every exported schema older than
 * [ExplorerDatabase.VERSION] it builds that version from `app/schemas`, fills each table with a row, runs the
 * production [ExplorerMigrations] on real SQLite and checks that the result is exactly the schema Room expects
 * for a fresh install while every row (and every column that still exists) survives. The device-side
 * MigrationTestHelper check lives in androidTest/ExplorerMigrationDeviceTest.
 */
class ExplorerMigrationTest {
    @get:Rule val folder = TemporaryFolder()
    private val driver = BundledSQLiteDriver()

    /** Columns a migration intentionally rewrites, as "table.column"; everything else must keep its value. */
    private val transformedColumns = emptySet<String>()

    @Test fun everyVersionHasCommittedSchema() {
        for (version in 1..ExplorerDatabase.VERSION) {
            val schema = checkNotNull(schemaJson(version)) { "app/schemas is missing $version.json; build once and commit it" }
            assertEquals(version, schema.getJSONObject("database").getInt("version"))
        }
        assertNull("A schema newer than ExplorerDatabase.VERSION is committed", schemaJson(ExplorerDatabase.VERSION + 1))
    }

    @Test fun latestSchemaHoldsTheTablesTheAppUses() {
        val tables = schemaOf(create(ExplorerDatabase.VERSION, "fresh")).keys
        assertTrue(tables.toString(), tables.containsAll(listOf("table:tasks", "table:task_items", "table:trash", "table:saved_state")))
    }

    @Test fun everyOlderVersionHasAMigrationPathToTheLatest() {
        val steps = ExplorerMigrations.ALL
        assertEquals("Two migrations start at the same version", steps.size, steps.map { it.startVersion }.toSet().size)
        steps.forEach { assertTrue("Migration ${it.startVersion}->${it.endVersion} goes backwards", it.endVersion > it.startVersion) }
        for (version in 1 until ExplorerDatabase.VERSION) assertNotNull("No migration path from $version", path(version))
    }

    @Test fun migratingKeepsRecordsAndMatchesAFreshInstall() {
        val expected = schemaOf(create(ExplorerDatabase.VERSION, "fresh"))
        for (version in 1 until ExplorerDatabase.VERSION) {
            val file = create(version, "from-$version")
            val before = driver.open(file.path).use { connection -> fillEveryTable(connection, version); snapshotRows(connection) }
            driver.open(file.path).use { connection ->
                connection.execSQL("BEGIN")
                checkNotNull(path(version)) { "No migration path from $version" }.forEach { it.migrate(connection) }
                connection.execSQL("PRAGMA user_version = ${ExplorerDatabase.VERSION}")
                connection.execSQL("COMMIT")
                assertEquals("Schema after migrating from $version differs from a fresh install", expected, schemaOf(file))
                val after = snapshotRows(connection)
                for ((table, rows) in before) {
                    val migrated = after[table] ?: continue // A dropped table is visible in the schema comparison.
                    assertEquals("$table lost rows migrating from $version", rows.size, migrated.size)
                    rows.zip(migrated).forEach { (old, new) ->
                        old.filterKeys { it in new && "$table.$it" !in transformedColumns }
                            .forEach { (column, value) -> assertEquals("$table.$column changed migrating from $version", value, new[column]) }
                    }
                }
            }
        }
    }

    @Test fun backupCopiesAnOutdatedDatabaseIncludingUncheckpointedWrites() {
        val file = create(1, "explorer")
        driver.open(file.path).use { writer ->
            writer.execSQL("PRAGMA journal_mode = WAL")
            writer.execSQL("PRAGMA wal_autocheckpoint = 0")
            writer.execSQL("INSERT INTO trash (id, source, original, directory, payloadPath, name, revision, isDirectory, state, deleted) VALUES ('t1', 'local', '/a.jpg', '/', '/trash/t1', 'a.jpg', 'r1', 0, 'READY', 7)")
            // The row only lives in the WAL while this connection stays open.
            val snapshot = MigrationBackup.snapshotIfOutdated(driver, file, current = 2)
            assertEquals(MigrationBackup.snapshotFile(file, 1), snapshot)
            driver.open(snapshot!!.path).use { copy ->
                assertEquals(1L, copy.single("PRAGMA user_version"))
                assertEquals("/a.jpg", copy.prepare("SELECT original FROM trash WHERE id = 't1'").use { it.step(); it.getText(0) })
            }
        }
    }

    @Test fun backupSkipsMissingFreshAndCurrentDatabases() {
        val missing = File(folder.root, "missing.db")
        assertNull(MigrationBackup.snapshotIfOutdated(driver, missing, current = 2))
        assertTrue("Checking must not create the database", !missing.exists())
        val current = create(1, "current")
        assertNull(MigrationBackup.snapshotIfOutdated(driver, current, current = 1))
        val fresh = File(folder.root, "fresh.db").also { file -> driver.open(file.path).use { it.execSQL("CREATE TABLE t (x)") } }
        assertNull("user_version 0 is a database Room has not created yet", MigrationBackup.snapshotIfOutdated(driver, fresh, current = 2))
        assertTrue(MigrationBackup.snapshots(current).isEmpty() && MigrationBackup.snapshots(fresh).isEmpty())
    }

    @Test fun backupKeepsOnlyTheNewestSnapshots() {
        val file = create(1, "explorer")
        val old = MigrationBackup.snapshotFile(file, 7).apply { writeText("old"); setLastModified(1_000) }
        val older = MigrationBackup.snapshotFile(file, 6).apply { writeText("older"); setLastModified(500) }
        val unrelated = File(folder.root, "explorer.db-wal.keep").apply { writeText("x") }
        val snapshot = MigrationBackup.snapshotIfOutdated(driver, file, current = 3, keep = 2)!!
        assertEquals(listOf(snapshot, old), MigrationBackup.snapshots(file))
        assertTrue(!older.exists() && unrelated.exists())
    }

    // Builds the database exactly as Room would have created it at [version], from the committed schema.
    private fun create(version: Int, name: String): File {
        val database = checkNotNull(schemaJson(version)) { "No committed schema for version $version" }.getJSONObject("database")
        val file = File(folder.root, if (name == "explorer") "explorer.db" else "$name.db")
        driver.open(file.path).use { connection ->
            database.array("entities").forEach { entity ->
                val table = entity.getString("tableName")
                connection.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices ->
                    for (i in 0 until indices.length()) connection.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            database.array("views").forEach { view ->
                connection.execSQL(view.getString("createSql").replace("\${VIEW_NAME}", view.getString("viewName")))
            }
            val setup = database.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) connection.execSQL(setup.getString(i))
            connection.execSQL("PRAGMA user_version = $version")
        }
        return file
    }

    private fun fillEveryTable(connection: SQLiteConnection, version: Int) {
        schemaJson(version)!!.getJSONObject("database").array("entities").forEach { entity ->
            val table = entity.getString("tableName")
            val fields = entity.array("fields")
            val columns = fields.joinToString { "`${it.getString("columnName")}`" }
            connection.prepare("INSERT INTO `$table` ($columns) VALUES (${fields.joinToString { "?" }})").use { statement ->
                fields.forEachIndexed { index, field ->
                    when (field.getString("affinity")) {
                        "INTEGER" -> statement.bindLong(index + 1, 40L + index)
                        "REAL" -> statement.bindDouble(index + 1, 0.5 + index)
                        "BLOB" -> statement.bindBlob(index + 1, byteArrayOf(index.toByte()))
                        else -> statement.bindText(index + 1, "$table-${field.getString("columnName")}-가")
                    }
                }
                statement.step()
            }
        }
    }

    private fun snapshotRows(connection: SQLiteConnection): Map<String, List<Map<String, Any?>>> =
        userTables(connection).associateWith { table ->
            connection.prepare("SELECT * FROM `$table` ORDER BY rowid").use { statement ->
                buildList {
                    while (statement.step()) add((0 until statement.getColumnCount()).associate { statement.getColumnName(it) to statement.value(it) })
                }
            }
        }

    /** Everything Room's own validation looks at: columns, keys, indices, foreign keys and views. */
    private fun schemaOf(file: File): Map<String, Any> = driver.open(file.path).use { connection ->
        val result = sortedMapOf<String, Any>()
        for (table in userTables(connection)) {
            result["table:$table"] = listOf(
                connection.rows("PRAGMA table_info(`$table`)").map { listOf(it["name"], (it["type"] as String).uppercase(), it["notnull"], it["dflt_value"], it["pk"]) },
                connection.rows("PRAGMA index_list(`$table`)").map { index ->
                    listOf(index["name"], index["unique"], connection.rows("PRAGMA index_info(`${index["name"]}`)").map { it["name"] })
                }.sortedBy { it.toString() },
                connection.rows("PRAGMA foreign_key_list(`$table`)").map { listOf(it["table"], it["from"], it["to"], it["on_update"], it["on_delete"]) }.sortedBy { it.toString() },
            )
        }
        connection.rows("SELECT name, sql FROM sqlite_master WHERE type = 'view'").forEach { result["view:${it["name"]}"] = it["sql"].toString() }
        result
    }

    private fun userTables(connection: SQLiteConnection) =
        connection.rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name")
            .map { it["name"] as String }

    private fun path(from: Int): List<Migration>? {
        var version = from
        val steps = mutableListOf<Migration>()
        while (version < ExplorerDatabase.VERSION) {
            // Like Room, prefer the step that jumps furthest without passing the target.
            val step = ExplorerMigrations.ALL.filter { it.startVersion == version && it.endVersion <= ExplorerDatabase.VERSION }.maxByOrNull { it.endVersion } ?: return null
            steps += step
            version = step.endVersion
        }
        return steps
    }

    private fun schemaJson(version: Int): JSONObject? =
        javaClass.classLoader!!.getResource("${ExplorerDatabase::class.java.name}/$version.json")?.readText()?.let(::JSONObject)

    private fun JSONObject.array(name: String) = optJSONArray(name)?.let { array -> List(array.length()) { array.getJSONObject(it) } }.orEmpty()

    private fun SQLiteConnection.rows(sql: String): List<Map<String, Any?>> = prepare(sql).use { statement ->
        buildList { while (statement.step()) add((0 until statement.getColumnCount()).associate { statement.getColumnName(it) to statement.value(it) }) }
    }

    private fun SQLiteConnection.single(sql: String): Long = prepare(sql).use { it.step(); it.getLong(0) }

    private fun androidx.sqlite.SQLiteStatement.value(index: Int): Any? = when {
        isNull(index) -> null
        else -> when (getColumnType(index)) {
            androidx.sqlite.SQLITE_DATA_INTEGER -> getLong(index)
            androidx.sqlite.SQLITE_DATA_FLOAT -> getDouble(index)
            androidx.sqlite.SQLITE_DATA_BLOB -> getBlob(index).toList()
            else -> getText(index)
        }
    }
}
