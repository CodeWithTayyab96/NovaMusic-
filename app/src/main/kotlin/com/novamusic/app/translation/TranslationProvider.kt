/*
 * NovaMusic — AI lyrics translation
 *
 * The provider abstraction and the OpenRouter implementation are adapted from
 * Echo Music (https://github.com/…), which is licensed GPL-3.0 — the same licence
 * as NovaMusic. Echo's design idea that we deliberately adopt: the model returns a
 * JSON array with exactly one string per input line, instead of joining lines with a
 * separator and splitting them apart again. Separator round-tripping was what caused
 * the old HTTP 429 request-amplification failure, so it is not used anywhere here.
 *
 * Copyright (C) Echo Music contributors — GPL-3.0
 * Copyright (C) NovaMusic contributors — GPL-3.0
 */
package com.novamusic.app.translation

/**
 * Structured translation failures.
 *
 * The UI receives one of these categories, never a raw HTTP status or exception dump —
 * an OpenRouter error body can echo request details, and raw text is not actionable for
 * a user.
 */
sealed interface TranslationError {
    /** Human-readable, already localised by the UI layer; kept out of logs when it may
     *  carry provider text. */
    val kind: Kind

    enum class Kind {
        MissingApiKey,
        Unauthorized,
        Forbidden,
        RateLimited,
        Timeout,
        NetworkUnavailable,
        ProviderUnavailable,
        InvalidResponse,
        InvalidTranslation,
        UnsupportedLanguage,
        Unknown,
    }

    data object MissingApiKey : TranslationError {
        override val kind = Kind.MissingApiKey
    }

    data object Unauthorized : TranslationError {
        override val kind = Kind.Unauthorized
    }

    data object Forbidden : TranslationError {
        override val kind = Kind.Forbidden
    }

    /** [retryAfterSeconds] comes from the provider's Retry-After header when present. */
    data class RateLimited(val retryAfterSeconds: Long? = null) : TranslationError {
        override val kind = Kind.RateLimited
    }

    data object Timeout : TranslationError {
        override val kind = Kind.Timeout
    }

    data object NetworkUnavailable : TranslationError {
        override val kind = Kind.NetworkUnavailable
    }

    data class ProviderUnavailable(val statusCode: Int? = null) : TranslationError {
        override val kind = Kind.ProviderUnavailable
    }

    /** The response was not the JSON array we require. */
    data object InvalidResponse : TranslationError {
        override val kind = Kind.InvalidResponse
    }

    /**
     * The response was well-formed JSON but unusable: wrong element count, non-string
     * elements, or empty entries where text was expected. Rejected rather than silently
     * producing corrupt lyrics.
     */
    data class InvalidTranslation(val detail: String) : TranslationError {
        override val kind = Kind.InvalidTranslation
    }

    data class UnsupportedLanguage(val language: String) : TranslationError {
        override val kind = Kind.UnsupportedLanguage
    }

    data class Unknown(val detail: String? = null) : TranslationError {
        override val kind = Kind.Unknown
    }
}

/** A translation request: an ordered list of source lines. Blank lines are preserved by
 *  the caller's structure, not sent for translation. */
data class TranslationRequest(
    val lines: List<String>,
    val targetLanguage: String,
    val sourceLanguage: String? = null,
)

/**
 * Result of translating a batch of lines.
 *
 * [lines] is guaranteed to have the same size and order as the requested input, so the
 * caller can reattach each entry to its original timestamp by index.
 */
data class TranslationResult(
    val lines: List<String>,
)

/**
 * A translation backend.
 *
 * Kept deliberately small so another provider (LibreTranslate, Azure, a local model) can
 * be added without touching the UI. Nothing above this interface knows about OpenRouter.
 */
interface TranslationProvider {
    /**
     * Translates [request] as a single logical operation.
     *
     * Implementations must never turn one line into one request: the contract is a bounded
     * number of calls for a whole song, with deterministic chunking if the text is too
     * large for one request.
     *
     * @return the translated lines in input order, or a [TranslationError].
     */
    suspend fun translate(request: TranslationRequest): Result<TranslationResult>

    /** Whether this provider is usable at all (e.g. an API key is configured). */
    fun isConfigured(): Boolean
}
