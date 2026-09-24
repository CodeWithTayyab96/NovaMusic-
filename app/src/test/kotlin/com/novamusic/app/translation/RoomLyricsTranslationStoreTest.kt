package com.novamusic.app.translation

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.novamusic.app.db.InternalDatabase
import com.novamusic.app.db.MusicDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room-backed translation persistence.
 *
 * The point of these tests is that a translation is stored through a targeted UPDATE, so the
 * original lyrics and every unrelated column are untouched — not merely "usually" untouched,
 * but structurally unable to change, because the statement cannot name a column it does not set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomLyricsTranslationStoreTest {
    private lateinit var context: Context
    private lateinit var delegate: InternalDatabase
    private lateinit var database: MusicDatabase
    private lateinit var store: RoomLyricsTranslationStore

    private companion object {
        const val SONG_ID = "song-1"
        const val ORIGINAL = "[00:12.34] तो फिर आओ\n[00:25.01] मुझको सताओ"
        const val TRANSLATED = "[00:12.34] then come again\n[00:25.01] torment me"
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        delegate =
            Room
                // In-memory on purpose: a file-backed database made Robolectric's per-test temp
                // directory teardown fail (NoSuchFileException, and a crashed test worker on the
                // x86_64 unit-test variant). Nothing here needs a file — the assertions are about
                // UPDATE semantics, not persistence to disk.
                .inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
                // Test only — Robolectric runs on the main thread.
                .allowMainThreadQueries()
                .build()
        delegate.openHelper.writableDatabase
        database = MusicDatabase(delegate)
        store = RoomLyricsTranslationStore(database)
    }

    @After
    fun tearDown() {
        runCatching { delegate.openHelper.close() }
    }

    // ─────────────────────────── helpers ───────────────────────────

    private fun exec(sql: String) {
        delegate.openHelper.writableDatabase.execSQL(sql)
    }

    private fun string(sql: String): String? {
        delegate.openHelper.readableDatabase.query(sql).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }

    private fun count(sql: String): Int {
        delegate.openHelper.readableDatabase.query(sql).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun insertLyrics(
        id: String,
        lyrics: String,
    ) {
        exec(
            "INSERT INTO lyrics (`id`, `lyrics`) VALUES ('$id', '$lyrics')",
        )
    }

    private fun columnOf(
        id: String,
        column: String,
    ): String? = string("SELECT `$column` FROM lyrics WHERE id = '$id'")

    /**
     * Inserts a `song` row, filling every NOT NULL column that has no default by reading
     * PRAGMA table_info — so the test does not hardcode the schema or break when a column is
     * added. (Chasing NOT NULL columns one at a time cost three test cycles.)
     */
    private fun insertSong(
        id: String,
        title: String,
    ) {
        val db = delegate.openHelper.writableDatabase
        val extras = mutableMapOf<String, Any?>()
        db.query("PRAGMA table_info(`song`)").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                val type = c.getString(c.getColumnIndexOrThrow("type")).orEmpty()
                val notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) == 1
                val hasDefault = c.getString(c.getColumnIndexOrThrow("dflt_value")) != null
                val isPk = c.getInt(c.getColumnIndexOrThrow("pk")) == 1
                if (name == "id" || name == "title" || isPk || !notNull || hasDefault) continue
                extras[name] =
                    when {
                        type.contains("INT", true) -> 0
                        type.contains("REAL", true) || type.contains("FLOA", true) -> 0.0
                        type.contains("BLOB", true) -> ByteArray(0)
                        else -> ""
                    }
            }
        }
        val named = mapOf("id" to id, "title" to title) + extras
        val cols = named.keys.joinToString(",") { "`$it`" }
        val placeholders = named.keys.joinToString(",") { "?" }
        db.execSQL("INSERT INTO `song` ($cols) VALUES ($placeholders)", named.values.toTypedArray())
    }

    // ─────────────────────────── tests ───────────────────────────

    @Test
    fun savingStoresTheTranslationAndPreservesTheOriginal() {
        insertLyrics(SONG_ID, ORIGINAL)

        runBlocking { store.saveTranslation(SONG_ID, TRANSLATED, "English") }

        assertEquals(TRANSLATED, columnOf(SONG_ID, "translatedLyrics"))
        assertEquals("English", columnOf(SONG_ID, "translationLanguage"))
        assertEquals(
            "the original lyrics must be byte-for-byte unchanged",
            ORIGINAL,
            columnOf(SONG_ID, "lyrics"),
        )
    }

    @Test
    fun aStoredTranslationIsReadableThroughGet() {
        insertLyrics(SONG_ID, ORIGINAL)
        runBlocking { store.saveTranslation(SONG_ID, TRANSLATED, "English") }

        val entity = runBlocking { store.get(SONG_ID, "English") }
        assertEquals(ORIGINAL, entity?.lyrics)
        assertEquals(TRANSLATED, entity?.translatedLyrics)
        assertEquals("English", entity?.translationLanguage)
    }

    @Test
    fun unrelatedRowsAndColumnsAreUntouched() {
        insertLyrics(SONG_ID, ORIGINAL)
        // A second, unrelated lyrics row and a song row, both of which must not change.
        insertLyrics("song-2", "unrelated original")
        insertSong("song-1", "Zaroorat")

        runBlocking { store.saveTranslation(SONG_ID, TRANSLATED, "English") }

        assertEquals("unrelated original", columnOf("song-2", "lyrics"))
        assertNull("an unrelated row must not gain a translation", columnOf("song-2", "translatedLyrics"))
        assertEquals(
            "song metadata must not be touched",
            "Zaroorat",
            string("SELECT title FROM song WHERE id = 'song-1'"),
        )
    }

    @Test
    fun clearingNullsTheTranslationButKeepsTheRow() {
        insertLyrics(SONG_ID, ORIGINAL)
        runBlocking {
            store.saveTranslation(SONG_ID, TRANSLATED, "English")
            store.clearTranslation(SONG_ID)
        }

        assertEquals(1, count("SELECT COUNT(*) FROM lyrics"))
        assertEquals(
            "the original lyrics must survive clearing",
            ORIGINAL,
            columnOf(SONG_ID, "lyrics"),
        )
        assertNull(columnOf(SONG_ID, "translatedLyrics"))
        assertNull(columnOf(SONG_ID, "translationLanguage"))
    }

    @Test
    fun aMissingIdDoesNotCreateARow() {
        // A targeted UPDATE matches nothing; it must not silently insert.
        runBlocking { store.saveTranslation("no-such-song", TRANSLATED, "English") }

        assertEquals(
            "an UPDATE must never create a row",
            0,
            count("SELECT COUNT(*) FROM lyrics"),
        )
        assertNull(runBlocking { store.get("no-such-song", "English") })
    }
}
