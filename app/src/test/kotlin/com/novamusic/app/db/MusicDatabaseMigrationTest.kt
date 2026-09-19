/*
 * NovaMusic — 28 -> 29 migration test.
 *
 * Deliberately does NOT use MigrationTestHelper. Under Robolectric, Room 2.8's
 * SupportSQLiteDriver rejects the file path that MigrationTestHelper.createDatabase resolves
 * ("configured to open a database named X but <path> was requested"), so the helper cannot
 * create a database here at all. The exported schema is still the source of truth — it is read
 * straight from the JSON — and the REAL migrations are run, so the guarantees are unchanged:
 *
 *   real v28 database -> real 28 -> 29 migration -> reopened through Room, which performs the
 *   identity-hash verification -> data preserved.
 *
 * Copyright (C) NovaMusic contributors — GPL-3.0
 */
package com.novamusic.app.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicDatabaseMigrationTest {
    private val dbName = "novamusic-migration-test.db"
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun migrations() = InternalDatabase.universalMigrationsFor(context)

    // ─────────────────────────────────────────────────────────────────────────────
    // The migration itself
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun migrate28To29_preservesData_andRoomAcceptsTheResult() {
        createDatabaseAtVersion(28) { db ->
            db.insert("song", mapOf("id" to "song-1", "title" to "Zaroorat"))
            db.insert("song", mapOf("id" to "song-2", "title" to "Baarish Ban Jaana"))
            db.insert("playlist", mapOf("id" to "pl-1", "name" to "Favourites"))
            db.insert("playlist", mapOf("id" to "pl-2", "name" to "Road Trip"))
            db.insert("search_history", mapOf("query" to "mustafa zahid"))
            db.insert("lyrics", mapOf("id" to "song-1", "lyrics" to ORIGINAL_LYRICS))
            db.insert("lyrics", mapOf("id" to "song-2", "lyrics" to "[00:01.00] second song"))
        }

        // Reopen through Room. Room runs the registered 28 -> 29 migration and then performs
        // its own identity-hash verification — this is the assertion that catches a stale
        // room_master_table.identity_hash, which is what could previously wipe user data.
        val reopened = openWithRoom()

        assertEquals(2, reopened.query("SELECT COUNT(*) FROM song", emptyArray()).use { it.intAt(0) })
        assertEquals(2, reopened.query("SELECT COUNT(*) FROM playlist", emptyArray()).use { it.intAt(0) })
        assertEquals(1, reopened.query("SELECT COUNT(*) FROM search_history", emptyArray()).use { it.intAt(0) })
        assertEquals(2, reopened.query("SELECT COUNT(*) FROM lyrics", emptyArray()).use { it.intAt(0) })

        assertEquals(
            ORIGINAL_LYRICS,
            reopened.query("SELECT lyrics FROM lyrics WHERE id = 'song-1'", emptyArray())
                .use { it.stringAt(0) },
        )
        assertEquals(
            "Zaroorat",
            reopened.query("SELECT title FROM song WHERE id = 'song-1'", emptyArray())
                .use { it.stringAt(0) },
        )
        assertEquals(
            "Road Trip",
            reopened.query("SELECT name FROM playlist WHERE id = 'pl-2'", emptyArray())
                .use { it.stringAt(0) },
        )

        val columns = columnNames(reopened, "lyrics")
        assertTrue("translatedLyrics column missing", "translatedLyrics" in columns)
        assertTrue("translationLanguage column missing", "translationLanguage" in columns)

        reopened.query("SELECT translatedLyrics FROM lyrics WHERE id = 'song-1'", emptyArray())
            .use { c ->
                c.moveToFirst()
                assertNull("pre-existing row must have no translation", c.stringOrNull(0))
            }
        reopened.query("SELECT translationLanguage FROM lyrics WHERE id = 'song-1'", emptyArray())
            .use { c ->
                c.moveToFirst()
                assertNull("pre-existing row must have no language", c.stringOrNull(0))
            }

        reopened.close()
    }

    @Test
    fun openingAnAlreadyCurrentDatabaseSucceeds() {
        createDatabaseAtVersion(InternalDatabase.DB_VERSION) { db ->
            db.insert("lyrics", mapOf("id" to "song-1", "lyrics" to ORIGINAL_LYRICS))
        }

        val reopened = openWithRoom()
        assertEquals(1, reopened.query("SELECT COUNT(*) FROM lyrics", emptyArray()).use { it.intAt(0) })
        reopened.close()
    }

    @Test
    fun reopeningTwiceIsIdempotent() {
        createDatabaseAtVersion(28) { db ->
            db.insert("lyrics", mapOf("id" to "song-1", "lyrics" to ORIGINAL_LYRICS))
        }

        openWithRoom().close()
        val second = openWithRoom()
        assertEquals(1, second.query("SELECT COUNT(*) FROM lyrics", emptyArray()).use { it.intAt(0) })
        assertEquals(
            ORIGINAL_LYRICS,
            second.query("SELECT lyrics FROM lyrics WHERE id = 'song-1'", emptyArray())
                .use { it.stringAt(0) },
        )
        second.close()
    }

    @Test
    fun aFailedOpenLeavesTheDatabaseFileIntact() {
        createDatabaseAtVersion(28) { db ->
            db.insert("lyrics", mapOf("id" to "song-1", "lyrics" to ORIGINAL_LYRICS))
        }

        val before = context.getDatabasePath(dbName)
        assertTrue("test database should exist", before.exists())
        val sizeBefore = before.length()

        var openFailed = false
        try {
            val unopened =
                Room.databaseBuilder(context, InternalDatabase::class.java, dbName).build()
            try {
                unopened.openHelper.writableDatabase
            } finally {
                unopened.close()
            }
        } catch (expected: Throwable) {
            openFailed = true
        }
        assertTrue("opening without a migration path must fail", openFailed)

        val after = context.getDatabasePath(dbName)
        assertTrue("the database file must still exist after a failed open", after.exists())
        assertEquals(
            "the database file must not be truncated or recreated",
            sizeBefore,
            after.length(),
        )

        val reopened = openWithRoom()
        assertEquals(1, reopened.query("SELECT COUNT(*) FROM lyrics", emptyArray()).use { it.intAt(0) })
        reopened.close()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Building a database at a given Room version, from the exported schema
    // ─────────────────────────────────────────────────────────────────────────────

    /** Creates a real SQLite database at Room schema [version] using the exported CREATE SQL. */
    private fun createDatabaseAtVersion(
        version: Int,
        populate: (android.database.sqlite.SQLiteDatabase) -> Unit,
    ) {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        File(file.path + "-wal").takeIf { it.exists() }?.delete()
        File(file.path + "-shm").takeIf { it.exists() }?.delete()

        // Raw SQLite rather than FrameworkSQLiteOpenHelper: the helper's onCreate lifecycle
        // proved unreliable here, and we need exact control over the schema and user_version.
        val raw =
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            schemaCreateSql(version).forEach { raw.execSQL(it) }
            // Every real Room database carries room_master_table with the identity hash for
            // its schema version. Without it the fixture is not a faithful Room database and
            // Room rejects it as "invalid schema".
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table " +
                    "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
            )
            raw.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf<Any?>(schemaIdentityHash(version)),
            )
            raw.execSQL("PRAGMA user_version = $version")
            assertEquals(
                "the fixture database must really be at version $version",
                version,
                raw.rawQuery("PRAGMA user_version", null).use { it.intAt(0) },
            )
            populate(raw)
        } finally {
            raw.close()
        }
    }

    /** CREATE statements for [version], read from the exported schema JSON on disk. */
    private fun schemaCreateSql(version: Int): List<String> {
        val file = schemaFile(version)
        assertTrue("exported schema must exist: ${file.absolutePath}", file.isFile)
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val database = root.getValue("database").jsonObject
        val out = mutableListOf<String>()
        database["entities"]?.jsonArray?.forEach { entity ->
            val obj = entity.jsonObject
            val sql = obj.getValue("createSql").jsonPrimitive.content
            // Room exports createSql with a literal ${TABLE_NAME} placeholder; it must be
            // replaced with the real table name or SQLite creates a table literally called
            // "${TABLE_NAME}".
            out += sql.replace("\${TABLE_NAME}", obj.getValue("tableName").jsonPrimitive.content)
        }
        // Views are intentionally NOT created: Room's exported schema keeps a literal
        // ${VIEW_NAME} placeholder in view createSql, which is not executable. They are not
        // needed for these assertions, and the real migration reconciles database objects
        // anyway (SchemaTools.reconcileDatabase recreates views from the expected schema).
        assertTrue("schema $version must define at least one table", out.isNotEmpty())
        return out
    }

    /** The identity hash Room exported for [version]. */
    private fun schemaIdentityHash(version: Int): String {
        val root = Json.parseToJsonElement(schemaFile(version).readText()).jsonObject
        return root.getValue("database").jsonObject.getValue("identityHash").jsonPrimitive.content
    }

    /** Resolves the exported schema file. Gradle unit tests run with the module dir as cwd. */
    private fun schemaFile(version: Int): File {
        val relative = "schemas/com.novamusic.app.db.InternalDatabase/$version.json"
        val direct = File(relative)
        if (direct.isFile) return direct
        val fromProjectRoot = File("app/$relative")
        if (fromProjectRoot.isFile) return fromProjectRoot
        return direct
    }

    private fun openWithRoom(): InternalDatabase =
        Room
            .databaseBuilder(context, InternalDatabase::class.java, dbName)
            .addMigrations(*migrations())
            // Test only: Robolectric runs these on the main thread and Room refuses queries
            // there by default. The production app never enables this.
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }

    private fun columnNames(
        db: InternalDatabase,
        table: String,
    ): Set<String> =
        db.query("PRAGMA table_info(`$table`)", emptyArray()).use { c ->
            val index = c.getColumnIndexOrThrow("name")
            buildSet {
                while (c.moveToNext()) add(c.getString(index))
            }
        }

    /**
     * Inserts [values], filling any other NOT NULL column without a default via PRAGMA
     * table_info, so the test does not hardcode every entity column.
     */
    private fun android.database.sqlite.SQLiteDatabase.insert(
        table: String,
        values: Map<String, Any?>,
    ) {
        val extras = mutableMapOf<String, Any?>()
        rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                val type = c.getString(c.getColumnIndexOrThrow("type")).orEmpty()
                val notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) == 1
                val hasDefault = c.getString(c.getColumnIndexOrThrow("dflt_value")) != null
                val isPk = c.getInt(c.getColumnIndexOrThrow("pk")) == 1
                if (name in values || isPk || !notNull || hasDefault) continue
                extras[name] =
                    when {
                        type.contains("INT", true) -> 0
                        type.contains("REAL", true) || type.contains("FLOA", true) -> 0.0
                        type.contains("BLOB", true) -> ByteArray(0)
                        else -> ""
                    }
            }
        }

        val all = values + extras
        val columns = all.keys.joinToString(",") { "`$it`" }
        val placeholders = all.keys.joinToString(",") { "?" }
        execSQL("INSERT INTO `$table` ($columns) VALUES ($placeholders)", all.values.toTypedArray())
    }

    private fun android.database.Cursor.intAt(index: Int): Int {
        moveToFirst()
        return getInt(index)
    }

    private fun android.database.Cursor.stringAt(index: Int): String {
        moveToFirst()
        return getString(index)
    }

    private fun android.database.Cursor.stringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private companion object {
        const val ORIGINAL_LYRICS = "[00:12.34] तो फिर आओ\n[00:25.01] मुझको सताओ"
    }
}
