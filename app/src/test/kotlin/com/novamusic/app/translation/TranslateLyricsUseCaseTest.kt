package com.novamusic.app.translation

import com.novamusic.app.db.entities.LyricsEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the translation orchestration.
 *
 * Uses fakes for the provider, the store and the config source, so there are no network calls,
 * no Android framework and no Room. The provider fake also counts requests, which is how the
 * "no per-line fallback" and caching guarantees are enforced rather than assumed.
 */
class TranslateLyricsUseCaseTest {
    private val original = listOf("Hello", "How are you", "Goodbye")

    private fun useCase(
        entity: LyricsEntity?,
        apiKey: String? = "test-key-not-real",
        providerResult: Result<TranslationResult>? = null,
    ): Triple<TranslateLyricsUseCase, FakeProvider, FakeStore> {
        val provider = FakeProvider(providerResult)
        val store = FakeStore(entity)
        val config =
            TranslationConfig(
                apiKey = apiKey,
                model = "test/model",
                baseUrl = "https://example.test/chat",
                targetLanguage = "English",
            )
        return Triple(
            TranslateLyricsUseCase(FakeConfig(config), provider, store),
            provider,
            store,
        )
    }

    private fun errorOf(result: Result<*>): TranslationError.Kind? =
        (result.exceptionOrNull() as? TranslationContractException)?.error?.kind

    // ─────────────────────────── caching ───────────────────────────

    @Test
    fun matchingLanguageReusesTheStoredTranslationWithZeroRequests() {
        val entity =
            LyricsEntity(
                id = "s1",
                lyrics = original.joinToString("\n"),
                translatedLyrics = "Hola\n¿Cómo estás?\nAdiós",
                translationLanguage = "Spanish",
            )
        val (useCase, provider, store) = useCase(entity)

        val result = run(useCase, target = "Spanish")

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("Hola", "¿Cómo estás?", "Adiós"),
            (result.getOrNull() as TranslationOutcome.Cached).lines,
        )
        assertEquals("cached translation must not hit the provider", 0, provider.calls)
        assertEquals("cached translation must not rewrite storage", 0, store.saves)
    }

    @Test
    fun differentLanguageCallsTheProviderExactlyOnce() {
        val entity =
            LyricsEntity(
                id = "s1",
                lyrics = original.joinToString("\n"),
                translatedLyrics = "Hola\n¿Cómo estás?\nAdiós",
                translationLanguage = "Spanish",
            )
        val (useCase, provider, store) =
            useCase(entity, providerResult = Result.success(TranslationResult(listOf("Bonjour", "Comment ça va", "Au revoir"))))

        val result = run(useCase, target = "French")

        assertTrue(result.isSuccess)
        assertEquals(1, provider.calls)
        assertEquals(1, store.saves)
        assertEquals("French", store.savedLanguage)
        assertTrue(result.getOrNull() is TranslationOutcome.Translated)
    }

    @Test
    fun noStoredTranslationCallsTheProviderExactlyOnce() {
        val (useCase, provider) =
            useCase(
                LyricsEntity(id = "s1", lyrics = original.joinToString("\n")),
                providerResult = Result.success(TranslationResult(listOf("a", "b", "c"))),
            )

        assertTrue(run(useCase).isSuccess)
        assertEquals(1, provider.calls)
    }

    @Test
    fun aCachedTranslationWithTheWrongLineCountIsNotReused() {
        // The original was edited after the translation was stored, so the cached value is stale.
        val entity =
            LyricsEntity(
                id = "s1",
                lyrics = "one\ntwo\nthree\nfour",
                translatedLyrics = "uno\ndos\ntres",
                translationLanguage = "Spanish",
            )
        val (useCase, provider) =
            useCase(
                entity,
                providerResult = Result.success(TranslationResult(listOf("w", "x", "y", "z"))),
            )

        val result = run(useCase, lines = listOf("one", "two", "three", "four"), target = "Spanish")

        assertTrue(result.getOrNull() is TranslationOutcome.Translated)
        assertEquals("stale cache must be re-translated, not served", 1, provider.calls)
    }

    // ─────────────────────────── persistence ───────────────────────────

    @Test
    fun aSuccessfulTranslationIsPersistedAndTheOriginalIsUntouched() {
        val entity = LyricsEntity(id = "s1", lyrics = original.joinToString("\n"))
        val (useCase, _, store) =
            useCase(
                entity,
                providerResult = Result.success(TranslationResult(listOf("Hola", "¿Cómo estás?", "Adiós"))),
            )

        run(useCase, target = "Spanish")

        assertEquals("Hola\n¿Cómo estás?\nAdiós", store.savedText)
        assertEquals("Spanish", store.savedLanguage)
        // The real assertion: after persisting, the original lyrics are byte-for-byte intact.
        assertEquals(
            "the original lyrics must never be overwritten",
            original.joinToString("\n"),
            store.persisted()?.lyrics,
        )
    }

    @Test
    fun aFailedTranslationLeavesBothOriginalAndStoredTranslationUntouched() {
        val entity =
            LyricsEntity(
                id = "s1",
                lyrics = original.joinToString("\n"),
                translatedLyrics = "Hola\n¿Cómo estás?\nAdiós",
                translationLanguage = "Spanish",
            )
        val (useCase, _, store) =
            useCase(
                entity,
                providerResult = Result.failure(TranslationContractException(TranslationError.RateLimited(30))),
            )

        val result = run(useCase, target = "French")

        assertEquals(0, store.saves)
        assertEquals(TranslationError.Kind.RateLimited, errorOf(result))
        assertEquals(
            "a failed translation must leave the stored translation untouched",
            "Hola\n¿Cómo estás?\nAdiós",
            store.persisted()?.translatedLyrics,
        )
        assertEquals("Spanish", store.persisted()?.translationLanguage)
    }

    // ─────────────────────────── configuration ───────────────────────────

    @Test
    fun aMissingApiKeyFailsBeforeAnyProviderRequest() {
        val (useCase, provider) =
            useCase(
                LyricsEntity(id = "s1", lyrics = original.joinToString("\n")),
                apiKey = null,
                providerResult = Result.success(TranslationResult(listOf("a", "b", "c"))),
            )

        val result = run(useCase)

        assertEquals(TranslationError.Kind.MissingApiKey, errorOf(result))
        assertEquals("no key must mean no request", 0, provider.calls)
    }

    @Test
    fun aBlankApiKeyCountsAsMissing() {
        val (useCase, provider) =
            useCase(
                LyricsEntity(id = "s1", lyrics = original.joinToString("\n")),
                apiKey = "   ",
                providerResult = Result.success(TranslationResult(listOf("a", "b", "c"))),
            )

        assertEquals(TranslationError.Kind.MissingApiKey, errorOf(run(useCase)))
        assertEquals(0, provider.calls)
    }

    @Test
    fun theTargetLanguageIsPassedToTheProvider() {
        val (useCase, provider) =
            useCase(
                LyricsEntity(id = "s1", lyrics = original.joinToString("\n")),
                providerResult = Result.success(TranslationResult(listOf("a", "b", "c"))),
            )

        run(useCase, target = "Urdu")

        assertEquals("Urdu", provider.lastRequest?.targetLanguage)
        assertEquals(original, provider.lastRequest?.lines)
    }

    // ─────────────────────────── validation ───────────────────────────

    @Test
    fun aWrongLineCountIsRejectedAndNotPersisted() {
        val (useCase, _, store) =
            useCase(
                LyricsEntity(id = "s1", lyrics = original.joinToString("\n")),
                providerResult = Result.success(TranslationResult(listOf("only", "two"))),
            )

        val result = run(useCase)

        assertEquals(TranslationError.Kind.InvalidTranslation, errorOf(result))
        assertEquals("a malformed result must never be persisted", 0, store.saves)
    }

    @Test
    fun anUnexpectedBlankLineIsRejected() {
        val (useCase, _, store) =
            useCase(
                LyricsEntity(id = "s1", lyrics = "one\ntwo\nthree"),
                providerResult = Result.success(TranslationResult(listOf("uno", "", "tres"))),
            )

        val result = run(useCase, lines = listOf("one", "two", "three"))

        assertEquals(TranslationError.Kind.InvalidTranslation, errorOf(result))
        assertEquals(0, store.saves)
    }

    @Test
    fun aBlankLineInTheOriginalMayStayBlank() {
        // Blank input lines are legitimate and must not be treated as dropped content.
        val (useCase, _, store) =
            useCase(
                LyricsEntity(id = "s1", lyrics = "one\n\nthree"),
                providerResult = Result.success(TranslationResult(listOf("uno", "", "tres"))),
            )

        assertTrue(run(useCase, lines = listOf("one", "", "three")).isSuccess)
        assertEquals(1, store.saves)
    }

    // ─────────────────────────── error propagation ───────────────────────────

    @Test
    fun structuredErrorsPropagateUnchanged() {
        val cases =
            listOf(
                TranslationError.Unauthorized to TranslationError.Kind.Unauthorized,
                TranslationError.Forbidden to TranslationError.Kind.Forbidden,
                TranslationError.RateLimited(45) to TranslationError.Kind.RateLimited,
                TranslationError.Timeout to TranslationError.Kind.Timeout,
                TranslationError.NetworkUnavailable to TranslationError.Kind.NetworkUnavailable,
                TranslationError.ProviderUnavailable(503) to TranslationError.Kind.ProviderUnavailable,
                TranslationError.InvalidResponse to TranslationError.Kind.InvalidResponse,
                TranslationError.Unknown() to TranslationError.Kind.Unknown,
            )
        for ((error, expected) in cases) {
            val (useCase, provider) =
                useCase(
                    LyricsEntity(id = "s1", lyrics = original.joinToString("\n")),
                    providerResult = Result.failure(TranslationContractException(error)),
                )
            val result = run(useCase)
            assertEquals("for $error", expected, errorOf(result))
            assertEquals("for $error", 1, provider.calls)
        }
    }

    // ─────────────────────────── request-count safety ───────────────────────────

    @Test
    fun fortyLinesProduceExactlyOneRequestNotForty() {
        val song = (1..40).map { "This is lyric line number $it" }
        val entity = LyricsEntity(id = "s1", lyrics = song.joinToString("\n"))
        val (useCase, provider) =
            useCase(
                entity,
                providerResult = Result.success(TranslationResult(song.map { "T$it" })),
            )

        assertTrue(run(useCase, lines = song).isSuccess)
        assertEquals("40 lines must be ONE request, never one per line", 1, provider.calls)
    }

    @Test
    fun aBlankTargetLanguageIsRejectedWithoutARequest() {
        val (useCase, provider) =
            useCase(
                LyricsEntity(id = "s1", lyrics = original.joinToString("\n")),
                providerResult = Result.success(TranslationResult(listOf("a", "b", "c"))),
            )

        assertEquals(TranslationError.Kind.UnsupportedLanguage, errorOf(run(useCase, target = " ")))
        assertEquals(0, provider.calls)
    }

    // ─────────────────────────── helpers ───────────────────────────

    private fun run(
        useCase: TranslateLyricsUseCase,
        lines: List<String> = original,
        target: String = "English",
    ): Result<TranslationOutcome> =
        kotlinx.coroutines.runBlocking { useCase("s1", lines, target) }

    private class FakeProvider(
        private val result: Result<TranslationResult>?,
    ) : TranslationProvider {
        var calls = 0
        var lastRequest: TranslationRequest? = null

        override suspend fun translate(request: TranslationRequest): Result<TranslationResult> {
            calls++
            lastRequest = request
            return result ?: Result.success(TranslationResult(request.lines))
        }

        override fun isConfigured(): Boolean = true
    }

    /** Mutable so tests can inspect the row AFTER a save and prove the original survived. */
    private class FakeStore(
        entity: LyricsEntity?,
    ) : LyricsTranslationStore {
        var saves = 0
        var savedText: String? = null
        var savedLanguage: String? = null
        private var current: LyricsEntity? = entity

        /** The row as it would now be persisted. */
        fun persisted(): LyricsEntity? = current

        override suspend fun get(songId: String, language: String): LyricsEntity? = current

        override suspend fun saveTranslation(
            songId: String,
            translatedLyrics: String,
            language: String,
        ) {
            saves++
            savedText = translatedLyrics
            savedLanguage = language
            // Mirrors the real store: ONLY the translation columns change.
            current = current?.copy(translatedLyrics = translatedLyrics, translationLanguage = language)
        }

        override suspend fun clearTranslation(songId: String) {
            saves++
            savedText = null
            savedLanguage = null
            current = current?.copy(translatedLyrics = null, translationLanguage = null)
        }
    }

    private class FakeConfig(
        private val config: TranslationConfig,
    ) : TranslationConfigSource {
        override suspend fun current(): TranslationConfig = config
    }
}
