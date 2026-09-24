/*
 * NovaMusic — Room-backed lyrics translation persistence.
 *
 * Copyright (C) NovaMusic contributors — GPL-3.0
 */
package com.novamusic.app.translation

import com.novamusic.app.db.MusicDatabase
import com.novamusic.app.db.entities.LyricsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores translations through the existing DAO.
 *
 * Deliberately uses the targeted `UPDATE lyrics SET translatedLyrics = …, translationLanguage = …`
 * DAO call rather than upserting a whole LyricsEntity. Upserting a fresh entity would write into
 * every column, including the original `lyrics`, and would silently discard anything not present
 * on the object it was built from. A targeted UPDATE cannot touch a column it does not name.
 *
 * Clearing sets the translation columns to NULL and leaves the original row intact.
 */
class RoomLyricsTranslationStore(
    private val database: MusicDatabase,
) : LyricsTranslationStore {
    override suspend fun get(songId: String, language: String): LyricsEntity? =
        withContext(Dispatchers.IO) {
            database.getLyricsById(songId)?.takeIf { it.translationLanguage == language }
        }

    override suspend fun saveTranslation(
        songId: String,
        translatedLyrics: String,
        language: String,
    ) {
        withContext(Dispatchers.IO) {
            database.updateLyricsTranslation(
                id = songId,
                translatedLyrics = translatedLyrics,
                translationLanguage = language,
            )
        }
    }

    override suspend fun clearTranslation(songId: String) {
        withContext(Dispatchers.IO) {
            database.updateLyricsTranslation(
                id = songId,
                translatedLyrics = null,
                translationLanguage = null,
            )
        }
    }
}
