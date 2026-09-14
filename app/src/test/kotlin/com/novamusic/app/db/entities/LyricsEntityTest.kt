package com.novamusic.app.db.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the lyrics original/translation split.
 *
 * The bug being replaced: the translator wrote its output straight into
 * [LyricsEntity.lyrics], destroying the original. Language switching was therefore
 * impossible — there was nothing left to switch back to, and a second translation
 * chained off the first one.
 *
 * Scenarios 5 (Room migration 28->29) and 7 (reactive flow reaching the UI) need a real
 * Room/SQLite environment, so they are covered by the CI build and the schema tooling
 * rather than here.
 */
class LyricsEntityTest {
    private val original = "[00:01.00] Hello\n[00:05.00] World"

    // ─── 1, 2, 3: translation is stored separately and never mutates the original ───

    @Test
    fun writingATranslationLeavesTheOriginalUntouched() {
        val entity = LyricsEntity(id = "song", lyrics = original)
        val translated = entity.copy(translatedLyrics = "Bonjour\nMonde", translationLanguage = "French")

        assertEquals(original, translated.lyrics)
        assertEquals("Bonjour\nMonde", translated.translatedLyrics)
        assertEquals("French", translated.translationLanguage)
    }

    @Test
    fun aSecondTranslationStillLeavesTheOriginalUntouched() {
        // Translating to another language must replace the translation, never the original.
        val first = LyricsEntity("song", original, "Bonjour", "French")
        val second = first.copy(translatedLyrics = "Hola", translationLanguage = "Spanish")

        assertEquals(original, second.lyrics)
        assertEquals("Hola", second.translatedLyrics)
        assertEquals("Spanish", second.translationLanguage)
    }

    @Test
    fun translationLanguageDescribesTheStoredTextNotTheSelection() {
        // A persisted translation in French must keep reporting French even if the UI is
        // currently set to Spanish.
        val entity = LyricsEntity("song", original, "Bonjour", "French")
        assertEquals("French", entity.translationLanguage)
    }

    // ─── 6: rows with no translation default to null ───

    @Test
    fun aSongWithNoTranslationHasNullTranslationFields() {
        val entity = LyricsEntity(id = "song", lyrics = original)
        assertNull(entity.translatedLyrics)
        assertNull(entity.translationLanguage)
        assertFalse(entity.hasUsableTranslation)
    }

    // ─── 8: mode selection ───

    @Test
    fun originalModeNeverReturnsATranslation() {
        val entity = LyricsEntity("song", original, "Bonjour", "French")
        assertNull(effectiveLyricsOverride(entity, showingTranslation = false))
    }

    @Test
    fun translatedModeReturnsTheTranslationWhenOneExists() {
        val entity = LyricsEntity("song", original, "Bonjour", "French")
        assertEquals("Bonjour", effectiveLyricsOverride(entity, showingTranslation = true))
    }

    @Test
    fun translatedModeFallsBackToTheOriginalWhenNoTranslationExists() {
        val entity = LyricsEntity("song", original)
        assertNull(effectiveLyricsOverride(entity, showingTranslation = true))
    }

    @Test
    fun translatedModeIsSafeWithNoEntityAtAll() {
        assertNull(effectiveLyricsOverride(null, showingTranslation = true))
    }

    // ─── 9: blank / missing translations are unavailable, not rendered ───

    @Test
    fun blankTranslationIsTreatedAsUnavailable() {
        val blank = LyricsEntity("song", original, "   ", "French")
        assertNull(blank.usableTranslatedLyrics)
        assertFalse(blank.hasUsableTranslation)
        assertNull(effectiveLyricsOverride(blank, showingTranslation = true))
    }

    @Test
    fun emptyTranslationIsTreatedAsUnavailable() {
        val empty = LyricsEntity("song", original, "", "French")
        assertNull(empty.usableTranslatedLyrics)
        assertFalse(empty.hasUsableTranslation)
    }

    // ─── 10: the sentinel is never translatable content ───

    @Test
    fun lyricsNotFoundSentinelCannotBeTranslated() {
        val sentinel = LyricsEntity("song", LyricsEntity.LYRICS_NOT_FOUND)
        assertFalse(sentinel.hasUsableTranslation)
        assertNull(effectiveLyricsOverride(sentinel, showingTranslation = true))
    }

    @Test
    fun sentinelStoredAsATranslationIsAlsoRejected() {
        // Defensive: a bogus persisted translation must not be rendered as if real.
        val entity =
            LyricsEntity(
                id = "song",
                lyrics = original,
                translatedLyrics = LyricsEntity.LYRICS_NOT_FOUND,
                translationLanguage = "French",
            )
        assertNull(entity.usableTranslatedLyrics)
        assertFalse(entity.hasUsableTranslation)
        assertNull(effectiveLyricsOverride(entity, showingTranslation = true))
    }

    // ─── 4: the translation source is always the original ───

    @Test
    fun replacingTheOriginalClearsTheStaleTranslation() {
        // updateOriginalLyrics() upserts a fresh entity; the previous translation was
        // derived from the old original and must not survive as if still valid.
        val translated = LyricsEntity("song", original, "Bonjour", "French")
        val replaced = translated.copy(lyrics = "[00:01.00] Different", translatedLyrics = null, translationLanguage = null)

        assertEquals("[00:01.00] Different", replaced.lyrics)
        assertFalse(replaced.hasUsableTranslation)
    }

    @Test
    fun aValidTranslationIsReportedAsUsable() {
        val entity = LyricsEntity("song", original, "Bonjour\nMonde", "French")
        assertTrue(entity.hasUsableTranslation)
        assertEquals("Bonjour\nMonde", entity.usableTranslatedLyrics)
    }
}
