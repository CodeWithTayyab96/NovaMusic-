package com.novamusic.app

import com.novamusic.app.playback.isDownloadComplete
import com.novamusic.app.ui.menu.canReuseStoredTranslation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the two rules extracted from the translation and download fixes.
 *
 * Both rules are pure, so they are testable without a network, a device or Room.
 */
class DownloadAndTranslationRuleTest {
    // ─────────────────────────────────────────────────────────────────────────────
    // FIX #2 — download completion
    //
    // The bug: both integrity checks were gated on `contentLength > 0`. When the server
    // sent neither Content-Range nor Content-Length, contentLength was -1, the fetch loop
    // never ran, only the probe chunk was written — and the download was still reported
    // COMPLETED. The file played from the start and stopped partway.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun unknownContentLengthIsNeverComplete_evenWithBytesWritten() {
        // The exact regression: bytes arrived, so the old `bytesDownloaded > 0` check passed.
        assertFalse(isDownloadComplete(bytesWritten = 65_536, contentLength = -1))
        assertFalse(isDownloadComplete(bytesWritten = 65_536, contentLength = 0))
    }

    @Test
    fun aLargeByteCountDoesNotProveCompletion() {
        // 5 MB written, but the total was never established.
        assertFalse(isDownloadComplete(bytesWritten = 5_000_000, contentLength = -1))
    }

    @Test
    fun fullDownloadIsComplete() {
        assertTrue(isDownloadComplete(bytesWritten = 4_000_000, contentLength = 4_000_000))
    }

    @Test
    fun overrunIsTreatedAsCompleteNotTruncated() {
        // Defensive: never reject a file that received at least the expected bytes.
        assertTrue(isDownloadComplete(bytesWritten = 4_000_001, contentLength = 4_000_000))
    }

    @Test
    fun partialDownloadIsNotComplete() {
        assertFalse(isDownloadComplete(bytesWritten = 1_000_000, contentLength = 4_000_000))
    }

    @Test
    fun zeroBytesIsNotComplete() {
        assertFalse(isDownloadComplete(bytesWritten = 0, contentLength = 0))
        assertFalse(isDownloadComplete(bytesWritten = 0, contentLength = 4_000_000))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // FIX #1 — translation reuse
    //
    // Re-translating into the language that is already stored spent another request
    // against a rate-limited endpoint for no benefit.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun aStoredTranslationInTheRequestedLanguageIsReused() {
        assertTrue(
            canReuseStoredTranslation(
                storedTranslation = "Hello",
                storedLanguage = "English",
                requestedLanguage = "English",
            ),
        )
    }

    @Test
    fun aTranslationInADifferentLanguageIsNotReused() {
        // Must translate again — from the ORIGINAL, never from the French text.
        assertFalse(
            canReuseStoredTranslation(
                storedTranslation = "Bonjour",
                storedLanguage = "French",
                requestedLanguage = "English",
            ),
        )
    }

    @Test
    fun aStaleLanguageSelectionCannotReuseTheWrongText() {
        // The persisted language is what the text IS. Requesting Spanish must not hand back
        // the stored French translation just because Spanish is selected in the UI.
        assertFalse(
            canReuseStoredTranslation(
                storedTranslation = "Bonjour",
                storedLanguage = "French",
                requestedLanguage = "Spanish",
            ),
        )
    }

    @Test
    fun noStoredTranslationIsNotReusable() {
        assertFalse(
            canReuseStoredTranslation(
                storedTranslation = null,
                storedLanguage = null,
                requestedLanguage = "English",
            ),
        )
    }

    @Test
    fun blankStoredTranslationIsNotReusable() {
        assertFalse(
            canReuseStoredTranslation(
                storedTranslation = "   ",
                storedLanguage = "English",
                requestedLanguage = "English",
            ),
        )
    }

    @Test
    fun missingStoredLanguageIsNotReusable() {
        // Defensive: a translation with no recorded language cannot be trusted to match.
        assertFalse(
            canReuseStoredTranslation(
                storedTranslation = "Hello",
                storedLanguage = null,
                requestedLanguage = "English",
            ),
        )
        assertFalse(
            canReuseStoredTranslation(
                storedTranslation = "Hello",
                storedLanguage = "  ",
                requestedLanguage = "English",
            ),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Note: the per-line amplification fallback (one HTTP request per lyric line) was
    // removed and now throws instead. That path lives inside a Compose onClick and cannot
    // be unit tested without UI-test infrastructure; it is covered by inspection plus the
    // fact that the only remaining translateBlocking call sites are the batch loop.
    // ─────────────────────────────────────────────────────────────────────────────
}
