package com.novamusic.app.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Paths
import kotlin.collections.buildSet

/**
 * Real 28 -> 29 migration test, running against actual SQLite via Robolectric.
 *
 * This exists because a schema bump could previously wipe user data. The specific defect it
 * guards against: `UniversalMigration` fixed the PHYSICAL schema but never updated
 * `room_master_table.identity_hash`, so Room rejected the migrated database with "Room cannot
 * verify the data integrity" and the app fell into a repair path that could ultimately delete
 * the file.
 *
 * Step 5 below — reopening the migrated database through Room — is the assertion that catches
 * that bug. A SQLite-level check alone would pass while Room still refused the database.
 */
@RunWith(RobolectricTestRunner::class)
// Pinned to an SDK Robolectric definitely ships with; Room's behaviour here does not depend on
// the platform version, and the project targets 36 which may not have a Robolectric image yet.
@Config(sdk = [34])
class MusicDatabaseMigrationTest {
    private val dbName = "novamusic-migration-test.db"

    /**
     * Uses the assets-folder form of MigrationTestHelper so the schema JSONs resolve from the
     * FILESYSTEM rather than packaged assets, which do not reach a Robolectric unit test.
     *
     * Room 2.8 ships this constructor as
     * (Instrumentation, String assetsFolder, SupportSQLiteOpenHelper.Factory = default).
     * There is no overload that also accepts the database class, so only two arguments are
     * passed here. The Instrumentation is unavoidable — Room uses it for the Context — but
     * `assetsFolder` redirects the schema lookup away from assets.
     *
     * `schemasRoot` is the absolute path to app/schemas. Room resolves schema files relative to
     * it, so if this build reports "schema not found", the fix is to point assetsFolder at the
     * package-named subdirectory (app/schemas/com.novamusic.app.db.InternalDatabase) which holds
     * <version>.json directly. The defensive assert below prints the exact path in that case.
     */
    @get:Rule
    val helper: MigrationTestHelper = run {
        // A RELATIVE ASSET path, not a filesystem path: Room's Android helper resolves these
        // through assets.open("<assetsFolder>/<version>.json"). app/schemas is added as a test
        // assets srcDir in build.gradle.kts, so the package-named subdirectory is reachable here.
        val schemasRoot = "com.novamusic.app.db.InternalDatabase"
        // The assets-folder form is (Instrumentation, String, SupportSQLiteOpenHelper.Factory),
        // with the factory defaulted. There is no overload that also takes the database class,
        // so the third argument above was rejected by the compiler and is removed.
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            schemasRoot,
        )
    }

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun migrations() = InternalDatabase.universalMigrationsFor(context)

    // ─────────────────────────────────────────────────────────────────────────────
    // The migration itself
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun migrate28To29_preservesData_andRoomAcceptsTheResult() {
        // ── Step 1 + 2: a genuine v28 database populated with representative data ──
        helper.createDatabase(dbName, 28).use { db ->
            db.insert("song", mapOf("id" to "song-1", "title" to "Zaroorat"))
            db.insert("song", mapOf("id" to "song-2", "title" to "Baarish Ban Jaana"))
            db.insert("playlist", mapOf("id" to "pl-1", "name" to "Favourites"))
            db.insert("playlist", mapOf("id" to "pl-2", "name" to "Road Trip"))
            db.insert("searchHistory", mapOf("query" to "mustafa zahid"))
            db.insert(
                "lyrics",
                mapOf("id" to "song-1", "lyrics" to ORIGINAL_LYRICS),
            )
            db.insert(
                "lyrics",
                mapOf("id" to "song-2", "lyrics" to "[00:01.00] second song"),
            )
        }

        // ── Step 3 + 4: run the SAME migrations production registers, then close ──
        helper.runMigrationsAndValidate(dbName, InternalDatabase.DB_VERSION, true, *migrations())

        // ── Step 5: reopen through Room. THIS is what catches a stale identity hash. ──
        val reopened = openWithRoom()

        // ── Step 6: data survived ──
        assertEquals(2, reopened.query("SELECT COUNT(*) FROM song", emptyArray()).use { it.intAt(0) })
        assertEquals(2, reopened.query("SELECT COUNT(*) FROM playlist", emptyArray()).use { it.intAt(0) })
        assertEquals(1, reopened.query("SELECT COUNT(*) FROM searchHistory", emptyArray()).use { it.intAt(0) })

        val lyricsCount = reopened.query("SELECT COUNT(*) FROM lyrics", emptyArray()).use { it.intAt(0) }
        assertEquals(2, lyricsCount)

        // Original lyrics are byte-for-byte unchanged.
        val storedOriginal =
            reopened
                .query("SELECT lyrics FROM lyrics WHERE id = 'song-1'", emptyArray())
                .use { it.stringAt(0) }
        assertEquals(ORIGINAL_LYRICS, storedOriginal)

        // Titles and names unchanged.
        assertEquals(
            "Zaroorat",
            reopened.query("SELECT title FROM song WHERE id = 'song-1'", emptyArray()).use { it.stringAt(0) },
        )
        assertEquals(
            "Road Trip",
            reopened.query("SELECT name FROM playlist WHERE id = 'pl-2'", emptyArray()).use { it.stringAt(0) },
        )

        // ── Step 7: the new columns exist and are null for pre-existing rows ──
        val columns = columnNames(reopened, "lyrics")
        assertTrue("translatedLyrics column missing", columns.contains("translatedLyrics"))
        assertTrue("translationLanguage column missing", columns.contains("translationLanguage"))

        reopened
            .query("SELECT translatedLyrics FROM lyrics WHERE id = 'song-1'", emptyArray())
            .use { cursor ->
                cursor.moveToFirst()
                assertNull("pre-existing row must have no translation", cursor.stringOrNull(0))
            }
        reopened
            .query("SELECT translationLanguage FROM lyrics WHERE id = 'song-1'", emptyArray())
            .use { cursor ->
                cursor.moveToFirst()
                assertNull("pre-existing row must have no language", cursor.stringOrNull(0))
            }

        reopened.close()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Regression: opening an already-current database
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun openingAnAlreadyCurrentDatabaseSucceeds() {
        // Create at the current version, then open again with the same migrations registered.
        helper.createDatabase(dbName, InternalDatabase.DB_VERSION).use { db ->
            db.insert("lyrics", mapOf("id" to "song-1", "lyrics" to ORIGINAL_LYRICS))
        }

        val reopened = openWithRoom()
        assertEquals(1, reopened.query("SELECT COUNT(*) FROM lyrics", emptyArray()).use { it.intAt(0) })
        reopened.close()
    }

    @Test
    fun migratingTwiceIsIdempotent() {
        // A second open must not re-run migrations or disturb the data.
        helper.createDatabase(dbName, 28).use { db ->
            db.insert("lyrics", mapOf("id" to "song-1", "lyrics" to ORIGINAL_LYRICS))
        }
        helper.runMigrationsAndValidate(dbName, InternalDatabase.DB_VERSION, true, *migrations())

        openWithRoom().close()
        val second = openWithRoom()
        assertEquals(1, second.query("SELECT COUNT(*) FROM lyrics", emptyArray()).use { it.intAt(0) })
        second.close()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Safety: a failure must never delete the database
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun aMissingMigrationFailsWithoutDeletingTheDatabaseFile() {
        helper.createDatabase(dbName, 28).use { db ->
            db.insert("lyrics", mapOf("id" to "song-1", "lyrics" to ORIGINAL_LYRICS))
        }

        val before = dbFile()
        assertTrue("test database should exist", before.exists())
        val sizeBefore = before.length()

        // Open as the current version WITHOUT registering any migration. Room cannot find a
        // path, so it must fail rather than quietly recreate the database.
        //
        // Note the structure: the assertion is OUTSIDE the try/catch. An earlier version put
        // fail() inside the try, where its AssertionError would have been swallowed by
        // `catch (expected: Throwable)` — the test would have passed even if the open had
        // succeeded, which is the opposite of what it is meant to prove.
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

        val after = dbFile()
        assertTrue("the database file must still exist after a failed open", after.exists())
        assertEquals(
            "the database file must not be truncated or recreated",
            sizeBefore,
            after.length(),
        )

        // And the data is still readable once a valid path is supplied again.
        helper.runMigrationsAndValidate(dbName, InternalDatabase.DB_VERSION, true, *migrations())
        val reopened = openWithRoom()
        assertEquals(1, reopened.query("SELECT COUNT(*) FROM lyrics", emptyArray()).use { it.intAt(0) })
        assertEquals(
            ORIGINAL_LYRICS,
            reopened.query("SELECT lyrics FROM lyrics WHERE id = 'song-1'", emptyArray()).use { it.stringAt(0) },
        )
        reopened.close()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private fun dbFile(): File = context.getDatabasePath(dbName)

    /**
     * Opens the migrated database the way the app does — through Room, with the production
     * migration set. Room performs schema AND identity-hash verification here, so a stale
     * hash fails this call even though the SQLite schema looks correct.
     */
    private fun openWithRoom(): InternalDatabase =
        Room
            .databaseBuilder(context, InternalDatabase::class.java, dbName)
            .addMigrations(*migrations())
            .build()
            .also { it.openHelper.writableDatabase }

    private fun columnNames(
        db: InternalDatabase,
        table: String,
    ): Set<String> =
        db.query("PRAGMA table_info(`$table`)", emptyArray()).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    /**
     * Inserts [values] into [table], filling any other NOT NULL column that has no default with
     * a type-appropriate placeholder. Keeps the test focused on the rows it cares about without
     * hardcoding every column of every entity — which would silently rot as entities change.
     */
    private fun SupportSQLiteDatabase.insert(
        table: String,
        values: Map<String, Any?>,
    ) {
        val extras = mutableMapOf<String, Any?>()
        query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val type = cursor.getString(cursor.getColumnIndexOrThrow("type")).orEmpty()
                val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1
                val hasDefault = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")) != null
                val isPk = cursor.getInt(cursor.getColumnIndexOrThrow("pk")) == 1
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
