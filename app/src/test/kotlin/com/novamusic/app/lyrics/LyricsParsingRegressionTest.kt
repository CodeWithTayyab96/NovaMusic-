/*
 * Regression tests for the lyrics parsing pipeline.
 *
 * Real-world LRC files (KuGou, lrclib, BetterLyrics) use 1-2 digit minutes and an
 * OPTIONAL fractional second ("[00:35]", "[1:05.50]"). The previous patterns required
 * two-digit minutes AND a fraction, so whole-second LRC parsed as EMPTY and the synced
 * renderer showed a blank screen for lyrics the fetcher had accepted as meaningful.
 */
package com.novamusic.app.lyrics

import com.novamusic.app.db.entities.LyricsEntity
import com.novamusic.app.db.entities.effectiveLyricsOverride
import com.novamusic.app.ui.menu.canReuseStoredTranslation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParsingRegressionTest {

    // ─────────────────── LRC parsing (the timestamp rigidity bug) ───────────────────

    @Test
    fun parsesWholeSecondLrcLines() {
        val entries = LyricsUtils.parseLyrics("[00:35]hello world")
        assertEquals(1, entries.size)
        assertEquals(35_000L, entries[0].time)
        assertEquals("hello world", entries[0].text)
    }

    @Test
    fun parsesSingleDigitMinuteLrcLines() {
        val entries = LyricsUtils.parseLyrics("[1:05]short minute")
        assertEquals(1, entries.size)
        assertEquals(65_000L, entries[0].time)
    }

    @Test
    fun keepsParsingClassicFractionalLrcLines() {
        val entries = LyricsUtils.parseLyrics("[01:02.50]fraction")
        assertEquals(1, entries.size)
        // ".50" (2 digits) is hundredths → 500 ms.
        assertEquals(62_500L, entries[0].time)
    }

    @Test
    fun handlesMultipleTimestampsOnOneLine() {
        val entries = LyricsUtils.parseLyrics("[00:10.25][00:12]word")
        assertEquals(2, entries.size)
        assertEquals(10_250L, entries[0].time)
        assertEquals(12_000L, entries[1].time)
        assertEquals("word", entries[0].text)
    }

    @Test
    fun unsyncedLinesAreIgnoredByTheSyncedParser() {
        val entries = LyricsUtils.parseLyrics("just some words\n[00:30]synced")
        assertEquals(1, entries.size)
        assertEquals("synced", entries[0].text)
    }

    // ─────────────────── meaningful-lyrics guard (sentinel-overwrite bug) ───────────────────

    @Test
    fun theNotFoundSentinelIsNotMeaningful() {
        assertFalse(LyricsHelper.isMeaningfulLyrics(LyricsEntity.LYRICS_NOT_FOUND))
    }

    @Test
    fun blankAndWhitespaceOnlyLyricsAreNotMeaningful() {
        assertFalse(LyricsHelper.isMeaningfulLyrics(""))
        assertFalse(LyricsHelper.isMeaningfulLyrics("   \n\t "))
        assertFalse(LyricsHelper.isMeaningfulLyrics("\u00A0\u00A0"))
    }

    @Test
    fun timestampOnlyContentIsNotMeaningful() {
        assertFalse(LyricsHelper.isMeaningfulLyrics("[00:01][00:02][00:03]"))
    }

    @Test
    fun realLyricsAreMeaningful() {
        assertTrue(LyricsHelper.isMeaningfulLyrics("[00:01]na"))
        assertTrue(LyricsHelper.isMeaningfulLyrics("plain unsynced line"))
        // BOM and invisible characters must not make junk look meaningful.
        assertFalse(LyricsHelper.isMeaningfulLyrics("\uFEFF\u200B\u00AD"))
    }

    // ─────────────────── override-selection contract (original preservation) ───────────────────

    @Test
    fun overrideIsNullUnlessTranslationModeIsOn() {
        val entity = LyricsEntity(
            id = "id",
            lyrics = "original",
            translatedLyrics = "traduit",
            translationLanguage = "French",
        )
        assertNull(effectiveLyricsOverride(entity, showingTranslation = false))
        assertEquals("traduit", effectiveLyricsOverride(entity, showingTranslation = true))
    }

    @Test
    fun overrideIsNullWhenNoUsableTranslationExists() {
        val sentinel = LyricsEntity(id = "id", lyrics = "original", translatedLyrics = LyricsEntity.LYRICS_NOT_FOUND)
        val blank = LyricsEntity(id = "id", lyrics = "original", translatedLyrics = "  ")
        assertNull(effectiveLyricsOverride(sentinel, showingTranslation = true))
        assertNull(effectiveLyricsOverride(blank, showingTranslation = true))
        assertNull(effectiveLyricsOverride(null, showingTranslation = true))
    }

    // ─────────────────── stored-translation reuse rule ───────────────────

    @Test
    fun storedTranslationIsReusedOnlyForTheSameLanguage() {
        assertTrue(canReuseStoredTranslation("traduit", "French", "French"))
        assertFalse(canReuseStoredTranslation("traduit", "French", "German"))
        assertFalse(canReuseStoredTranslation(null, "French", "French"))
        assertFalse(canReuseStoredTranslation("traduit", null, "French"))
        assertFalse(canReuseStoredTranslation("", "French", "French"))
    }
}
