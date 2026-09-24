/*
 * NovaMusic — user-facing messages for translation failures.
 *
 * Every [TranslationError] category gets its own message. The UI shows one of these instead
 * of a raw HTTP status or an exception dump: an OpenRouter error body can echo request
 * details and is not actionable for a listener.
 *
 * Copyright (C) NovaMusic contributors — GPL-3.0
 */
package com.novamusic.app.translation

import com.novamusic.app.R

/** The string shown for this failure. */
fun TranslationError.messageRes(): Int =
    when (kind) {
        TranslationError.Kind.MissingApiKey -> R.string.translation_error_missing_key
        TranslationError.Kind.Unauthorized -> R.string.translation_error_unauthorized
        TranslationError.Kind.Forbidden -> R.string.translation_error_forbidden
        TranslationError.Kind.InsufficientBalance -> R.string.translation_error_balance
        TranslationError.Kind.RateLimited -> R.string.translation_error_rate_limited
        TranslationError.Kind.Timeout -> R.string.translation_error_timeout
        TranslationError.Kind.NetworkUnavailable -> R.string.translation_error_network
        TranslationError.Kind.ProviderUnavailable -> R.string.translation_error_provider
        TranslationError.Kind.InvalidResponse -> R.string.translation_error_invalid
        TranslationError.Kind.InvalidTranslation -> R.string.translation_error_invalid
        TranslationError.Kind.UnsupportedLanguage -> R.string.translation_error_language
        TranslationError.Kind.Unknown -> R.string.translation_failed
    }
