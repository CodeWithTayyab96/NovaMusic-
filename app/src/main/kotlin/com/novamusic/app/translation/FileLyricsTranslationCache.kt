/*
 * NovaMusic — file-backed lyrics translation cache.
 *
 * One JSON file per (song id, target language) inside the app's private files directory.
 * No Room table, no schema change: the cache is deliberately kept out of the database so
 * adding or clearing a translation can never touch the lyrics table or its migrations.
 *
 * The cache is checked BEFORE any provider request, which is what makes "translate the same
 * song again" cost zero requests.
 *
 * Copyright (C) NovaMusic contributors — GPL-3.0
 */
package com.novamusic.app.translation

import android.content.Context
import com.novamusic.app.db.entities.LyricsEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class CachedTranslation(
    val songId: String,
    val language: String,
    val lines: List<String>,
)

/**
 * Stores translations as JSON files under `filesDir/translations/`.
 *
 * The file name is a hash of song id + language, so a song id containing path characters
 * cannot escape the directory.
 */
@Singleton
class FileLyricsTranslationCache
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : LyricsTranslationStore {
    private val json = Json { ignoreUnknownKeys = true }

    private fun directory(): File = File(context.filesDir, CACHE_DIR).apply { mkdirs() }

    private fun fileFor(
        songId: String,
        language: String,
    ): File = File(directory(), "${hash(songId, language)}$FILE_EXTENSION")

    /**
     * Hash of the (song, language) pair. Not security-sensitive — it only has to be stable
     * and filesystem-safe.
     */
    private fun hash(
        songId: String,
        language: String,
    ): String {
        var result = HASH_SEED
        for (element in songId + '\u0000' + language) {
            result = (result * HASH_PRIME + element.code) and 0x7FFFFFFF
        }
        return result.toString(36)
    }

    /**
     * Returns a [LyricsEntity] carrying ONLY the cached translation for [language], or null
     * when nothing is cached for that pair.
     *
     * `lyrics` is intentionally empty: this store does not hold the original, and nothing in
     * the translation path reads it.
     */
    override suspend fun get(
        songId: String,
        language: String,
    ): LyricsEntity? =
        withContext(Dispatchers.IO) {
            val file = fileFor(songId, language)
            if (!file.exists()) return@withContext null
            val entry = readEntry(file) ?: return@withContext null
            if (entry.language != language) return@withContext null
            if (entry.lines.isEmpty()) return@withContext null
            LyricsEntity(
                id = entry.songId,
                lyrics = "",
                translatedLyrics = entry.lines.joinToString("\n"),
                translationLanguage = entry.language,
            )
        }

    override suspend fun saveTranslation(
        songId: String,
        translatedLyrics: String,
        language: String,
    ) {
        withContext(Dispatchers.IO) {
            if (translatedLyrics.isBlank()) return@withContext
            val lines = translatedLyrics.split("\n")
            val entry = CachedTranslation(songId = songId, language = language, lines = lines)
            runCatching {
                fileFor(songId, language).writeText(
                    json.encodeToString(CachedTranslation.serializer(), entry),
                )
            }
        }
    }

    override suspend fun clearTranslation(songId: String) {
        withContext(Dispatchers.IO) {
            // Clears every language for this song.
            directory()
                .listFiles { file -> file.name.endsWith(FILE_EXTENSION) }
                ?.forEach { file ->
                    val entry = readEntry(file)
                    if (entry?.songId == songId) {
                        runCatching { file.delete() }
                    }
                }
        }
    }

    private fun readEntry(file: File): CachedTranslation? =
        runCatching {
            json.decodeFromString(CachedTranslation.serializer(), file.readText())
        }.getOrNull()

    companion object {
        private const val CACHE_DIR = "translations"
        private const val FILE_EXTENSION = ".json"
        private const val HASH_SEED = 7
        private const val HASH_PRIME = 31
    }
}
