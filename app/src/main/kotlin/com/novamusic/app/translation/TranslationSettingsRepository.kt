/*
 * NovaMusic — AI translation settings.
 *
 * Holds the OpenRouter configuration and is the ONLY place that knows about the encrypted
 * credential. Everything above this layer sees a plain String or null and never touches
 * KeyStore, encryption or preference keys.
 *
 * Copyright (C) NovaMusic contributors — GPL-3.0
 */
package com.novamusic.app.translation

import android.content.Context
import com.novamusic.app.constants.OpenRouterApiKeyKey
import com.novamusic.app.constants.OpenRouterBaseUrlKey
import com.novamusic.app.constants.OpenRouterModelKey
import com.novamusic.app.constants.TranslationTargetLanguageKey
import com.novamusic.app.utils.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Echo's current default. Used as a STARTING default only — the model is user-configurable. */
const val DEFAULT_OPENROUTER_MODEL = "google/gemini-2.5-flash-lite"

const val DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1/chat/completions"

const val DEFAULT_TRANSLATION_TARGET_LANGUAGE = "English"

/** Provider configuration as seen by the rest of the app. */
data class TranslationConfig(
    /** Decrypted API key, or null when the user has not configured one. */
    val apiKey: String? = null,
    val model: String = DEFAULT_OPENROUTER_MODEL,
    val baseUrl: String = DEFAULT_OPENROUTER_BASE_URL,
    val targetLanguage: String = DEFAULT_TRANSLATION_TARGET_LANGUAGE,
) {
    /** Whether a provider call can be attempted at all. */
    val isConfigured: Boolean get() = !apiKey.isNullOrBlank()
}

/**
 * Reads and writes AI translation settings.
 *
 * The API key is encrypted with [SecureKeyStore] before it reaches DataStore and decrypted on
 * the way out, so the plaintext is never persisted. Clearing removes the preference outright
 * rather than storing an empty string.
 *
 * Nothing here logs the key, and the key never appears in an exception message.
 */
class TranslationSettingsRepository(
    private val context: Context,
) {
    /** Live configuration; decrypts the key lazily on each emission. */
    val config: Flow<TranslationConfig> =
        context.dataStore.data.map { prefs ->
            val stored = prefs[OpenRouterApiKeyKey]
            TranslationConfig(
                apiKey = SecureKeyStore.decrypt(stored),
                model = prefs[OpenRouterModelKey]?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_OPENROUTER_MODEL,
                baseUrl = prefs[OpenRouterBaseUrlKey]?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_OPENROUTER_BASE_URL,
                targetLanguage = prefs[TranslationTargetLanguageKey]?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_TRANSLATION_TARGET_LANGUAGE,
            )
        }

    suspend fun currentConfig(): TranslationConfig = config.first()

    /**
     * Encrypts and stores [rawKey]; a blank value clears the stored key.
     *
     * Returns false when the key could not be encrypted. Nothing is written in that case, so a
     * broken keystore can never result in the key being persisted in the clear.
     */
    suspend fun setApiKey(rawKey: String): Boolean {
        if (rawKey.isBlank()) {
            clearApiKey()
            return true
        }
        val encrypted = SecureKeyStore.encrypt(rawKey) ?: return false
        context.dataStore.edit { prefs -> prefs[OpenRouterApiKeyKey] = encrypted }
        return true
    }

    suspend fun clearApiKey() {
        context.dataStore.edit { prefs -> prefs.remove(OpenRouterApiKeyKey) }
    }

    suspend fun setModel(model: String) {
        context.dataStore.edit { prefs -> prefs[OpenRouterModelKey] = model.trim() }
    }

    suspend fun setBaseUrl(baseUrl: String) {
        context.dataStore.edit { prefs -> prefs[OpenRouterBaseUrlKey] = baseUrl.trim() }
    }

    suspend fun setTargetLanguage(language: String) {
        context.dataStore.edit { prefs -> prefs[TranslationTargetLanguageKey] = language.trim() }
    }
}
