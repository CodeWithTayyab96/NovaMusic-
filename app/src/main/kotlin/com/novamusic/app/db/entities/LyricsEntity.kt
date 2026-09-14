/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.novamusic.app.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val id: String,
    /**
     * The ORIGINAL, untranslated lyrics. Permanent: translation must never overwrite or
     * mutate this column, because it is the source for every future translation and the
     * content rendered in "original" mode.
     */
    val lyrics: String,
    /** Translated copy of [lyrics], or null when no translation has been stored. */
    val translatedLyrics: String? = null,
    /**
     * The language actually stored in [translatedLyrics]. Persisted so the UI never
     * labels a translation by whatever language happens to be selected.
     */
    val translationLanguage: String? = null,
) {
    /**
     * Translation text safe to render, or null when there is none worth showing.
     * Guards the persisted state: null, blank, or the LYRICS_NOT_FOUND sentinel all mean
     * "no translation", so translated mode can never render an empty or bogus value.
     */
    val usableTranslatedLyrics: String?
        get() = translatedLyrics?.takeIf { it.isNotBlank() && it != LYRICS_NOT_FOUND }

    /** Whether translated viewing mode is available for this song at all. */
    val hasUsableTranslation: Boolean
        get() = usableTranslatedLyrics != null && lyrics.isNotBlank() && lyrics != LYRICS_NOT_FOUND

    companion object {
        const val LYRICS_NOT_FOUND = "LYRICS_NOT_FOUND"
    }
}

/**
 * Chooses the text the lyrics renderer should display.
 *
 * Returns the translation only when the user has switched to translated mode AND a usable
 * translation actually exists. Otherwise null, which tells the renderer to fall back to the
 * entity's own original `lyrics` — so the original is shown whenever translated mode is
 * unavailable, and renderer behaviour is unchanged when nothing is overridden.
 *
 * Pure and side-effect free so the selection rules are unit testable without a device.
 */
internal fun effectiveLyricsOverride(
    entity: LyricsEntity?,
    showingTranslation: Boolean,
): String? = if (showingTranslation) entity?.usableTranslatedLyrics else null
