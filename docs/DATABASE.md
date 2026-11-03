# explorer.db schema changes

`ExplorerDatabase` (Room) keeps the trash journal, file-operation tasks and saved UI state. Users keep this
file across updates, so every schema change ships with a migration. `fallbackToDestructiveMigration` is not used
and must not be added: it would silently empty the trash journal.

## Changing the schema

1. Change or add the `@Entity` classes (new tables can live in their own file; list them in `@Database(entities = ...)`).
2. Bump `ExplorerDatabase.VERSION` by one.
3. Add a step in `ExplorerMigrations.kt`, one value per step, and append it to `ExplorerMigrations.ALL`:

   ```kotlin
   val MIGRATION_1_2 = object : ExplorerMigration(1, 2) {
       override val statements = listOf(
           "CREATE TABLE IF NOT EXISTS `offline_copies` (...)", // copy createSql from app/schemas/.../2.json
           "ALTER TABLE trash ADD COLUMN note TEXT NOT NULL DEFAULT ''",
       )
   }
   ```

   Copy `CREATE TABLE`/`CREATE INDEX` text from the newly generated schema JSON so the result matches a fresh
   install exactly. New non-null columns need `@ColumnInfo(defaultValue = ...)` and the same `DEFAULT` in SQL.
4. Build once (`./gradlew :app:compileDebugKotlin`); Room writes `app/schemas/com.quickfiles.app.data.ExplorerDatabase/<VERSION>.json`.
   Commit it. Never edit or delete an already released schema file.
5. Run `./gradlew :app:testDebugUnitTest`.

Two parallel branches that both bump the version will conflict on `VERSION`, `ALL` and the schema file;
the second one to merge renumbers its step (and regenerates its JSON) on top of the first.

## Safety net

- `ExplorerMigrationTest` (host JVM, runs in CI): every version has a committed schema, every older version has a
  migration path, and migrating a database built from each old schema with one row per table yields exactly the
  latest schema while keeping every row and every surviving column value. It runs the production migrations on
  real SQLite through the bundled `androidx.sqlite` driver.
- `ExplorerMigrationDeviceTest` (androidTest): Room's `MigrationTestHelper` validation on the device's SQLite,
  opening an old database through the real builder path, and the backup on the device driver.
- `MigrationBackup`: before Room upgrades an existing older database, the app writes
  `databases/explorer.db.v<old>-backup` with `VACUUM INTO` (WAL included) and keeps the two newest copies.
  A failed backup is logged and does not block startup. To recover by hand on a debug build:
  `adb shell run-as com.quickfiles.app cp databases/explorer.db.v1-backup databases/explorer.db` with the app stopped
  (also remove `explorer.db-wal` and `explorer.db-shm`), then install a build whose migration is fixed.
