/*
 * NovaMusic — AI lyrics translation contract.
 *
 * The "JSON array of one string per line" contract is Echo Music's design (GPL-3.0, same
 * licence as NovaMusic) and is the reason it does not suffer the request-amplification
 * failures NovaMusic had: there is no separator to survive a round-trip through the model,
 * so a mis-split can never degrade into one request per line.
 *
 * Copyright (C) Echo Music contributors — GPL-3.0
 * Copyright (C) NovaMusic contributors — GPL-3.0
 */
package com.novamusic.app.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure helpers for the translation contract: prompt construction, response parsing,
 * validation and deterministic chunking.
 *
 * Everything here is side-effect free so the whole contract is unit testable on the JVM —
 * no network, no device, no Android framework.
 */
object LyricsTranslationContract {
    /** Low temperature: translation should be as deterministic as the model allows. */
    const val TEMPERATURE: Double = 0.3

    /**
     * Upper bound on characters sent in one request. Deliberately conservative: a whole
     * song normally fits, so the common case is ONE request.
     */
    const val MAX_CHARS_PER_REQUEST: Int = 6000

    private val json = Json { isLenient = false; ignoreUnknownKeys = true }

    /**
     * System prompt, adapted from Echo's rather than copied verbatim: the rules below are
     * tightened for NovaMusic's timestamped lyrics, where the line count must match exactly
     * because each translation is reattached to an original timestamp by index.
     */
    fun buildSystemPrompt(
        targetLanguage: String,
        sourceLanguage: String?,
    ): String =
        buildString {
            append("You are a precise lyrics translation engine.\n")
            append("Translate the user's lyrics into $targetLanguage.\n")
            if (!sourceLanguage.isNullOrBlank()) {
                append("The source language is $sourceLanguage.\n")
            }
            append("\n")
            append("Rules:\n")
            append("1. Output ONLY a valid JSON array of strings. No prose, no markdown, no code fences.\n")
            append("2. The array MUST contain exactly one string per input line, in the same order.\n")
            append("3. Never merge, split, add, reorder or drop lines.\n")
            append("4. If an input line is empty or has no translatable text, repeat it unchanged as an empty string.\n")
            append("5. Translate the lyrics only. Never explain, summarise, comment or answer.\n")
            append("6. Never invent lyrics that are not present in the input.\n")
            append("7. Do not include timestamps in the output; translate the text only.\n")
            append("8. Do not translate proper nouns, artist names or sound-effect syllables unless they have a common form in $targetLanguage.\n")
        }

    /**
     * User prompt: the lines as a JSON array, so the model sees the exact shape it must
     * return and the count is unambiguous.
     */
    fun buildUserPrompt(lines: List<String>): String =
        buildString {
            append("Translate each of the ")
            append(lines.size)
            append(" lines below. Return exactly ")
            append(lines.size)
            append(" strings as a JSON array.\n\n")
            append(Json.encodeToString(JsonArray.serializer(), JsonArray(lines.map { JsonPrimitive(it) })))
        }

    /**
     * Extracts the JSON array from a model response that may be wrapped in prose or a
     * markdown code fence.
     *
     * Returns null when no plausible array is present.
     */
    fun extractJsonArrayText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        // Strip a fenced block if present, preferring ```json ... ```.
        val fenced =
            Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
        val candidate = if (!fenced.isNullOrBlank()) fenced else raw.trim()

        // Otherwise take the outermost [ ... ] span.
        val start = candidate.indexOf('[')
        val end = candidate.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return candidate.substring(start, end + 1).trim()
    }

    /**
     * Parses and validates a model response against [expectedCount].
     *
     * Rejects, rather than repairing: wrong element count, non-string elements, malformed
     * JSON. A partially or incorrectly translated result must never be stored as if it were
     * complete, because each line is reattached to an original timestamp by index.
     */
    fun parseTranslationArray(
        raw: String?,
        expectedCount: Int,
    ): Result<List<String>> {
        if (expectedCount <= 0) {
            return Result.failure(TranslationContractException(TranslationError.InvalidTranslation("no input lines")))
        }
        val jsonText =
            extractJsonArrayText(raw)
                ?: return Result.failure(
                    TranslationContractException(TranslationError.InvalidResponse),
                )

        val element =
            runCatching { json.parseToJsonElement(jsonText) }.getOrNull()
                ?: return Result.failure(
                    TranslationContractException(TranslationError.InvalidResponse),
                )

        val array =
            element as? JsonArray
                ?: return Result.failure(
                    TranslationContractException(TranslationError.InvalidResponse),
                )

        if (array.size != expectedCount) {
            return Result.failure(
                TranslationContractException(
                    TranslationError.InvalidTranslation(
                        "expected $expectedCount lines, received ${array.size}",
                    ),
                ),
            )
        }

        val out = ArrayList<String>(array.size)
        for (item in array) {
            val primitive = item as? JsonPrimitive
            if (primitive == null || !primitive.isString) {
                return Result.failure(
                    TranslationContractException(
                        TranslationError.InvalidTranslation("element is not a JSON string"),
                    ),
                )
            }
            out.add(primitive.content)
        }
        return Result.success(out)
    }

    /**
     * Splits [lines] into deterministic chunks so a long song stays within
     * [MAX_CHARS_PER_REQUEST].
     *
     * Chunking is deterministic (no randomness, no timing) and preserves global ordering, so
     * the caller can concatenate results and verify the final count. It is the ONLY
     * subdivision allowed — never per-line requests.
     */
    fun chunkLines(
        lines: List<String>,
        maxChars: Int = MAX_CHARS_PER_REQUEST,
    ): List<List<String>> {
        require(maxChars > 0) { "maxChars must be positive" }
        if (lines.isEmpty()) return emptyList()

        val chunks = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var currentChars = 0

        for (line in lines) {
            val cost = line.length + 1
            // A single line larger than the budget still gets its own chunk rather than
            // being split, so the one-string-per-line contract is never broken.
            if (current.isNotEmpty() && currentChars + cost > maxChars) {
                chunks.add(current)
                current = ArrayList()
                currentChars = 0
            }
            current.add(line)
            currentChars += cost
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }
}

/** Carries a [TranslationError] through [Result]. */
class TranslationContractException(
    val error: TranslationError,
) : Exception("Translation contract failure: ${error.kind}")
