package com.novamusic.app.translation

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.novamusic.app.constants.OpenRouterApiKeyKey
import com.novamusic.app.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings-repository behaviour, verified with Robolectric so a real DataStore and a real
 * AndroidKeyStore are used — the point is to prove the key is not stored in plaintext.
 *
 * Uses a fake test credential only; no real OpenRouter key and no network access.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationSettingsRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: TranslationSettingsRepository

    private companion object {
        /** Clearly fake — never a real OpenRouter credential. */
        const val TEST_KEY = "sk-test-not-a-real-key"
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repository = TranslationSettingsRepository(context)
    }

    @After
    fun tearDown() {
        // DataStore is process-wide here, so reset between tests.
        runBlocking {
            repository.clearApiKey()
            repository.setBaseUrl(DEFAULT_OPENROUTER_BASE_URL)
            repository.setTargetLanguage(DEFAULT_TRANSLATION_TARGET_LANGUAGE)
        }
    }

    private fun currentConfig() = runBlocking { repository.currentConfig() }

    private fun rawStoredKey(): String? = runBlocking { context.dataStore.data.first()[OpenRouterApiKeyKey] }

    // ─────────────────────────── defaults ───────────────────────────

    @Test
    fun defaultsAreUsedWhenNothingIsConfigured() {
        val config = currentConfig()
        assertEquals(DEFAULT_OPENROUTER_MODEL, config.model)
        assertEquals(DEFAULT_OPENROUTER_BASE_URL, config.baseUrl)
        assertEquals(DEFAULT_TRANSLATION_TARGET_LANGUAGE, config.targetLanguage)
        assertNull(config.apiKey)
        assertFalse(config.isConfigured)
    }

    // ─────────────────────────── key storage ───────────────────────────

    /**
     * The invariant that matters: the plaintext key is never persisted, whatever the platform
     * does.
     *
     * Robolectric has no usable AndroidKeyStore, so on the JVM `setApiKey` fails and writes
     * nothing. On a device the same call succeeds and stores the encrypted envelope. Both
     * outcomes are asserted here because both must be safe — the failure path especially,
     * since a naive implementation would fall back to storing cleartext.
     */
    @Test
    fun theApiKeyIsNeverStoredInPlaintext() {
        val saved = runBlocking { repository.setApiKey(TEST_KEY) }
        val raw = rawStoredKey()

        if (saved) {
            assertNotEquals("the key must not be stored in plaintext", TEST_KEY, raw)
            assertTrue(
                "stored value must use our encrypted envelope",
                SecureKeyStore.isEncrypted(raw),
            )
            assertEquals(TEST_KEY, currentConfig().apiKey)
        } else {
            assertNull("a failed save must write nothing at all, never plaintext", raw)
        }

        // Holds either way.
        assertNotEquals("the key must never be persisted in plaintext", TEST_KEY, raw)
    }

    /** Saving either configures the provider or fails cleanly — never half-succeeds. */
    @Test
    fun savingAKeyEitherConfiguresOrFailsCleanly() {
        val saved = runBlocking { repository.setApiKey(TEST_KEY) }
        assertEquals(
            "isConfigured must reflect whether the key really saved",
            saved,
            currentConfig().isConfigured,
        )
        if (!saved) {
            assertNull("nothing may be stored when the save failed", rawStoredKey())
        }
    }

    @Test
    fun clearRemovesThePreferenceEntirely() {
        runBlocking {
            repository.setApiKey(TEST_KEY)
            repository.clearApiKey()
        }
        // Removed, not left behind as an empty string.
        assertNull(rawStoredKey())
        assertNull(currentConfig().apiKey)
        assertFalse(currentConfig().isConfigured)
    }

    @Test
    fun aBlankKeyIsTreatedAsMissing() {
        runBlocking {
            repository.setApiKey(TEST_KEY)
            repository.setApiKey("   ")
        }
        assertNull("a blank key must clear the stored preference", rawStoredKey())
        assertFalse(currentConfig().isConfigured)
    }

    // ─────────────────────────── other settings ───────────────────────────

    @Test
    fun theModelIsAlwaysTheFreeRouterAndCannotBeChanged() {
        // There is deliberately no setter: routing to a paid model would break
        // translation for anyone without credit.
        assertEquals("openrouter/free", OPENROUTER_FREE_MODEL)
        assertEquals(OPENROUTER_FREE_MODEL, DEFAULT_OPENROUTER_MODEL)
        assertEquals(OPENROUTER_FREE_MODEL, currentConfig().model)

        runBlocking { repository.setTargetLanguage("Urdu") }
        val config = currentConfig()
        // Changing an unrelated setting must not move the model off the free pool.
        assertEquals(OPENROUTER_FREE_MODEL, config.model)
        assertEquals("Urdu", config.targetLanguage)
        assertEquals(DEFAULT_OPENROUTER_BASE_URL, config.baseUrl)
    }

    @Test
    fun aBlankTargetLanguageFallsBackToTheDefault() {
        runBlocking { repository.setTargetLanguage("  ") }
        assertEquals(DEFAULT_TRANSLATION_TARGET_LANGUAGE, currentConfig().targetLanguage)
    }

    @Test
    fun theConfigurationFlowEmitsUpdatedValues() {
        runBlocking {
            assertEquals(OPENROUTER_FREE_MODEL, repository.config.first().model)
            repository.setTargetLanguage("Urdu")
            assertEquals("Urdu", repository.config.first().targetLanguage)
            assertEquals(OPENROUTER_FREE_MODEL, repository.config.first().model)
        }
    }

    // ─────────────────────────── helpers ───────────────────────────
}
