package com.novamusic.app.translation

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The translation cache is JSON files under the app's private storage — not a Room table.
 *
 * The behaviour that matters most is the last test: translating the same song to the same
 * language twice must cost exactly ONE provider request.
 *
 * Robolectric supplies a real Context so a real filesDir is used. No network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileLyricsTranslationCacheTest {
    private lateinit var context: Context
    private lateinit var cache: FileLyricsTranslationCache

    private companion object {
        const val SONG = "song-abc"
        val LINES = listOf("one", "two", "three")
        val JOINED = LINES.joinToString("\n")
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        cache = FileLyricsTranslationCache(context)
        runBlocking { cache.clearTranslation(SONG) }
    }

    @Test
    fun aSavedTranslationIsReturnedForTheSameSongAndLanguage() {
        runBlocking { cache.saveTranslation(SONG, JOINED, "Spanish") }

        val entity = runBlocking { cache.get(SONG, "Spanish") }
        assertNotNull(entity)
        assertEquals(JOINED, entity!!.usableTranslatedLyrics)
        assertEquals("Spanish", entity.translationLanguage)
    }

    @Test
    fun aTranslationInAnotherLanguageIsNotReused() {
        runBlocking { cache.saveTranslation(SONG, JOINED, "Spanish") }

        assertNull(runBlocking { cache.get(SONG, "French") })
    }

    @Test
    fun clearingRemovesEveryLanguageForThatSong() {
        runBlocking {
            cache.saveTranslation(SONG, JOINED, "Spanish")
            cache.saveTranslation(SONG, JOINED, "French")
            cache.clearTranslation(SONG)
        }

        assertNull(runBlocking { cache.get(SONG, "Spanish") })
        assertNull(runBlocking { cache.get(SONG, "French") })
    }

    @Test
    fun anUncachedSongHasNoEntry() {
        assertNull(runBlocking { cache.get("no-such-song", "Spanish") })
    }

    @Test
    fun retranslatingBypassesTheCacheAndOverwritesIt() {
        var served = LINES.map { "$it-first" }
        var calls = 0
        val provider =
            object : TranslationProvider {
                override suspend fun translate(request: TranslationRequest): Result<TranslationResult> {
                    calls += 1
                    return Result.success(TranslationResult(served))
                }

                override fun isConfigured(): Boolean = true
            }
        val configSource =
            object : TranslationConfigSource {
                override suspend fun current(): TranslationConfig =
                    TranslationConfig(apiKey = "sk-test-not-real", targetLanguage = "Spanish")
            }
        val useCase = TranslateLyricsUseCase(configSource, provider, cache)

        runBlocking {
            assertNotNull(useCase(SONG, LINES, "Spanish").getOrNull())
            served = LINES.map { "$it-second" }
            val forced = useCase(SONG, LINES, "Spanish", force = true)
            assertEquals(LINES.map { "$it-second" }, forced.getOrNull()!!.lines)
        }

        // A forced run must spend the request rather than reusing what it replaces.
        assertEquals(2, calls)
        assertEquals(
            "the cached value must be replaced, not left stale",
            LINES.map { "$it-second" }.joinToString("\n"),
            runBlocking { cache.get(SONG, "Spanish") }!!.usableTranslatedLyrics,
        )
    }

    @Test
    fun translatingTheSameSongTwiceCostsExactlyOneProviderCall() {
        var calls = 0
        val provider =
            object : TranslationProvider {
                override suspend fun translate(request: TranslationRequest): Result<TranslationResult> {
                    calls += 1
                    return Result.success(TranslationResult(request.lines.map { "$it-trad" }))
                }

                override fun isConfigured(): Boolean = true
            }
        val configSource =
            object : TranslationConfigSource {
                override suspend fun current(): TranslationConfig =
                    TranslationConfig(apiKey = "sk-test-not-real", targetLanguage = "Spanish")
            }
        val useCase = TranslateLyricsUseCase(configSource, provider, cache)

        runBlocking {
            assertNotNull(useCase(SONG, LINES, "Spanish").getOrNull())
            val second = useCase(SONG, LINES, "Spanish")
            assertNotNull(second.getOrNull())
        }

        assertEquals("the second translation must be served from the file cache", 1, calls)
    }
}
