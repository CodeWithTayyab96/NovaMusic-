package com.novamusic.app.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the translation contract: response parsing, validation,
 * mapping and chunking. No network, no device.
 *
 * The contract exists because the previous implementation joined lines with a separator
 * and split the result apart again; when the model altered the separator the code fell
 * back to one request per line and tripped HTTP 429. A JSON array cannot fail that way.
 */
class LyricsTranslationContractTest {
    private fun kindOf(result: Result<List<String>>): TranslationError.Kind? =
        (result.exceptionOrNull() as? TranslationContractException)?.error?.kind

    // ─────────────────────────── JSON parsing ───────────────────────────

    @Test
    fun parsesAValidArray() {
        val result = LyricsTranslationContract.parseTranslationArray("""["one","two","three"]""", 3)
        assertEquals(listOf("one", "two", "three"), result.getOrNull())
    }

    @Test
    fun rejectsAnArrayOfTheWrongSize() {
        val result = LyricsTranslationContract.parseTranslationArray("""["one","two"]""", 3)
        assertEquals(TranslationError.Kind.InvalidTranslation, kindOf(result))
    }

    @Test
    fun rejectsNonStringElements() {
        val result = LyricsTranslationContract.parseTranslationArray("""["one",42,"three"]""", 3)
        assertEquals(TranslationError.Kind.InvalidTranslation, kindOf(result))
    }

    @Test
    fun rejectsNestedArrays() {
        val result = LyricsTranslationContract.parseTranslationArray("""["one",["two"],"three"]""", 3)
        assertEquals(TranslationError.Kind.InvalidTranslation, kindOf(result))
    }

    @Test
    fun rejectsMalformedJson() {
        val result = LyricsTranslationContract.parseTranslationArray("""["one", "two", ]""", 3)
        assertEquals(TranslationError.Kind.InvalidResponse, kindOf(result))
    }

    @Test
    fun parsesAnArrayWrappedInAMarkdownFence() {
        val raw = "Here you go:\n```json\n[\"a\",\"b\"]\n```\nHope that helps!"
        val result = LyricsTranslationContract.parseTranslationArray(raw, 2)
        assertEquals(listOf("a", "b"), result.getOrNull())
    }

    @Test
    fun parsesAnArraySurroundedByProse() {
        val result = LyricsTranslationContract.parseTranslationArray("Sure! [\"a\",\"b\"] done.", 2)
        assertEquals(listOf("a", "b"), result.getOrNull())
    }

    @Test
    fun rejectsNullAndBlankResponses() {
        assertEquals(
            TranslationError.Kind.InvalidResponse,
            kindOf(LyricsTranslationContract.parseTranslationArray(null, 1)),
        )
        assertEquals(
            TranslationError.Kind.InvalidResponse,
            kindOf(LyricsTranslationContract.parseTranslationArray("   ", 1)),
        )
    }

    @Test
    fun rejectsAnEmptyTranslation() {
        assertEquals(
            TranslationError.Kind.InvalidTranslation,
            kindOf(LyricsTranslationContract.parseTranslationArray("[]", 3)),
        )
    }

    @Test
    fun rejectsAnEmptyInputRequest() {
        assertEquals(
            TranslationError.Kind.InvalidTranslation,
            kindOf(LyricsTranslationContract.parseTranslationArray("""["a"]""", 0)),
        )
    }

    @Test
    fun preservesEscapedCharactersAndUnicode() {
        val raw = """["line \"quoted\"","उर्दू","emoji \uD83C\uDFB5"]"""
        val result = LyricsTranslationContract.parseTranslationArray(raw, 3)
        val lines = result.getOrNull()
        assertNotNull(lines)
        assertEquals("line \"quoted\"", lines!![0])
        assertEquals("उर्दू", lines[1])
    }

    // ─────────────────────────── mapping ───────────────────────────

    @Test
    fun mapsOneInputLineToExactlyOneOutput() {
        val result = LyricsTranslationContract.parseTranslationArray("""["translated"]""", 1)
        assertEquals(listOf("translated"), result.getOrNull())
    }

    @Test
    fun preservesOrderingAcrossManyLines() {
        val input = (1..25).map { "line $it" }
        val raw = input.joinToString(prefix = "[", postfix = "]") { "\"T:$it\"" }
        val result = LyricsTranslationContract.parseTranslationArray(raw, 25)
        assertEquals(input.map { "T:$it" }, result.getOrNull())
    }

    @Test
    fun preservesBlankLinesAsEmptyStrings() {
        // A blank input line must come back as an empty string so indices stay aligned and
        // each translation can be reattached to its original timestamp.
        val result = LyricsTranslationContract.parseTranslationArray("""["first","","third"]""", 3)
        assertEquals(listOf("first", "", "third"), result.getOrNull())
    }

    @Test
    fun indexAlignmentIsWhatKeepsTimestampsAttached() {
        // Timestamps live in the ORIGINAL lines and are never sent for translation. The
        // contract only guarantees index alignment, which is what lets the caller rebuild
        // "timestamp + translated line" without the model ever touching a timestamp.
        val originalWithTimestamps =
            listOf("[00:01.00] one", "[00:05.00] two", "[00:09.00] three")
        val translations = LyricsTranslationContract
            .parseTranslationArray("""["uno","dos","tres"]""", originalWithTimestamps.size)
            .getOrNull()
        assertNotNull(translations)

        val reattached = originalWithTimestamps.mapIndexed { index, original ->
            val stamp = original.substringBefore(']') + "]"
            "$stamp ${translations!![index]}"
        }
        assertEquals(
            listOf("[00:01.00] uno", "[00:05.00] dos", "[00:09.00] tres"),
            reattached,
        )
    }

    // ─────────────────────────── chunking ───────────────────────────

    @Test
    fun aNormalSongIsASingleChunk() {
        // The safety property: one ordinary song must produce ONE request, never one per line.
        val song = (1..40).map { "This is lyric line number $it" }
        val chunks = LyricsTranslationContract.chunkLines(song)
        assertEquals(1, chunks.size)
        assertEquals(40, chunks.first().size)
    }

    @Test
    fun longLyricsSplitDeterministically() {
        val song = (1..200).map { "A reasonably long lyric line, number $it, with padding text" }
        val first = LyricsTranslationContract.chunkLines(song, maxChars = 500)
        val second = LyricsTranslationContract.chunkLines(song, maxChars = 500)
        assertEquals(first, second) // deterministic
        assertTrue("expected more than one chunk", first.size > 1)
        assertEquals(song, first.flatten()) // global order preserved, nothing lost
    }

    @Test
    fun anOversizedSingleLineIsNotSplit() {
        val huge = "x".repeat(50_000)
        val chunks = LyricsTranslationContract.chunkLines(listOf(huge, "short"), maxChars = 1000)
        assertEquals(2, chunks.size)
        assertEquals(listOf(huge), chunks[0]) // kept intact: one string per line is the contract
    }

    @Test
    fun emptyInputProducesNoChunks() {
        assertTrue(LyricsTranslationContract.chunkLines(emptyList()).isEmpty())
    }

    // ─────────────────────────── prompt ───────────────────────────

    @Test
    fun thePromptStatesTheExactLineCountAndForbidsMerging() {
        val prompt = LyricsTranslationContract.buildSystemPrompt("Urdu", "Hindi")
        assertTrue(prompt.contains("Urdu"))
        assertTrue(prompt.contains("exactly one string per input line"))
        assertTrue(prompt.contains("Never merge, split, add, reorder or drop lines"))
        assertTrue(prompt.contains("JSON array"))

        val user = LyricsTranslationContract.buildUserPrompt(listOf("a", "b"))
        assertTrue(user.contains("exactly 2 strings"))
        assertTrue(user.contains("""["a","b"]"""))
    }

    @Test
    fun temperatureIsLowForDeterministicTranslation() {
        assertTrue(LyricsTranslationContract.TEMPERATURE <= 0.4)
    }

    // ─────────────────────────── errors ───────────────────────────

    @Test
    fun rateLimitErrorCarriesRetryAfter() {
        val error = TranslationError.RateLimited(retryAfterSeconds = 30)
        assertEquals(TranslationError.Kind.RateLimited, error.kind)
        assertEquals(30L, error.retryAfterSeconds)
    }

    @Test
    fun everyErrorExposesAStableKind() {
        val all =
            listOf(
                TranslationError.MissingApiKey,
                TranslationError.Unauthorized,
                TranslationError.Forbidden,
                TranslationError.RateLimited(),
                TranslationError.Timeout,
                TranslationError.NetworkUnavailable,
                TranslationError.ProviderUnavailable(503),
                TranslationError.InvalidResponse,
                TranslationError.InvalidTranslation("x"),
                TranslationError.UnsupportedLanguage("xx"),
                TranslationError.Unknown(),
            )
        val kinds = all.map { it.kind }
        assertEquals(kinds.size, kinds.toSet().size) // no accidental duplicate categories
    }
}
