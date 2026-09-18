/*
 * NovaMusic — lyrics translation orchestration.
 *
 * Sits between the UI and the provider. Nothing here knows about OpenRouter, HTTP or JSON:
 * it only knows about TranslationProvider, the persisted entity and the line contract. That
 * is what makes it unit testable with fakes and what lets another provider be added later.
 *
 * Copyright (C) NovaMusic contributors — GPL-3.0
 */
package com.novamusic.app.translation

import com.novamusic.app.db.entities.LyricsEntity

/** Reads the stored lyrics row for a song. */
interface LyricsTranslationStore {
    suspend fun get(songId: String): LyricsEntity?

    /**
     * Persists a completed translation.
     *
     * Implementations must update ONLY translatedLyrics and translationLanguage — the original
     * `lyrics` column is never touched.
     */
    suspend fun saveTranslation(
        songId: String,
        translatedLyrics: String,
        language: String,
    )

    /**
     * Removes the stored translation by setting the translation columns to NULL.
     * The original `lyrics` must remain untouched.
     */
    suspend fun clearTranslation(songId: String)
}

/** Supplies the provider configuration. Implemented by TranslationSettingsRepository. */
interface TranslationConfigSource {
    suspend fun current(): TranslationConfig
}

/** What a translation request produced. */
sealed interface TranslationOutcome {
    val lines: List<String>

    /** Served from the stored translation — no provider request was made. */
    data class Cached(override val lines: List<String>) : TranslationOutcome

    /** Freshly translated by the provider and already persisted. */
    data class Translated(override val lines: List<String>) : TranslationOutcome
}

/**
 * Translates lyrics, honouring the cache and persisting only complete results.
 *
 * Contract:
 * - a stored translation in the requested language is reused with ZERO provider requests;
 * - the original `lyrics` are never written;
 * - a translation is persisted only after its line count matches the input exactly;
 * - failures propagate the structured [TranslationError] unchanged, never a raw exception
 *   message, and leave both the original lyrics and any existing translation untouched.
 *
 * [lines] are plain lyric text with timestamps already stripped by the caller; the returned
 * list has the same length and order, so the caller reattaches each entry to its original
 * timestamp by index. The model is never asked to produce timestamps.
 */
class TranslateLyricsUseCase(
    private val configSource: TranslationConfigSource,
    private val provider: TranslationProvider,
    private val store: LyricsTranslationStore,
) {
    suspend operator fun invoke(
        songId: String,
        lines: List<String>,
        targetLanguage: String,
    ): Result<TranslationOutcome> {
        if (targetLanguage.isBlank()) {
            return translationFailure(TranslationError.UnsupportedLanguage(""))
        }

        // 1. Reuse a stored translation for the SAME language. No network call.
        val entity = store.get(songId)
        val stored = entity?.usableTranslatedLyrics
        if (stored != null && entity?.translationLanguage == targetLanguage) {
            val cached = stored.lines()
            // A cached value whose line count no longer matches the current lyrics is stale
            // (the original was edited or refetched), so it is not reused.
            if (cached.size == lines.size) {
                return Result.success(TranslationOutcome.Cached(cached))
            }
        }

        // 2. Configuration must exist before any request.
        val config = configSource.current()
        if (!config.isConfigured) {
            return translationFailure(TranslationError.MissingApiKey)
        }

        // 3. Translate. One logical request for the whole song; chunking, if any, is the
        //    provider's concern and is deterministic — never one request per line.
        val result =
            provider.translate(
                TranslationRequest(lines = lines, targetLanguage = targetLanguage),
            )
        val translated =
            result.getOrElse { throwable ->
                return Result.failure(throwable)
            }

        // 4. Treat provider output as untrusted: the line count must match exactly.
        if (translated.lines.size != lines.size) {
            return translationFailure(
                TranslationError.InvalidTranslation(
                    "expected ${lines.size} lines, received ${translated.lines.size}",
                ),
            )
        }
        // Defensive: a blank where real text was expected means the model dropped content.
        // Compared by INDEX — indexOf() would match the first duplicate and give wrong results.
        val droppedContent =
            translated.lines.indices.any { index ->
                translated.lines[index].isBlank() && lines[index].isNotBlank()
            }
        if (droppedContent) {
            return translationFailure(
                TranslationError.InvalidTranslation("translation contained an unexpected blank line"),
            )
        }

        // 5. Persist only a complete, validated result.
        val joined = translated.lines.joinToString("\n")
        store.saveTranslation(
            songId = songId,
            translatedLyrics = joined,
            language = targetLanguage,
        )

        return Result.success(TranslationOutcome.Translated(translated.lines))
    }

    private fun <T> translationFailure(error: TranslationError): Result<T> =
        Result.failure(TranslationContractException(error))
}
