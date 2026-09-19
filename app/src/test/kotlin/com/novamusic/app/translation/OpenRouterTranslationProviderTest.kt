package com.novamusic.app.translation

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTTP-level tests for the REAL [OpenRouterTranslationProvider], driven by ktor-client-mock.
 * Offline, deterministic, pure JVM (no Robolectric needed).
 *
 * These exist because status-code handling was once silently broken: the response was inferred as
 * [Any] (the try/catch branches returned different types), so `response.status` never resolved and
 * the whole 401/403/429 mapping was dead code. Every HTTP test below therefore asserts the
 * resulting structured [TranslationError] category, never merely "something failed".
 *
 * Uses a clearly fake key held only in test memory.
 */
class OpenRouterTranslationProviderTest {
    private companion object {
        const val TEST_KEY = "test-openrouter-key-123"
        const val MODEL = "some-vendor/test-model"
        const val ENDPOINT = "https://openrouter.test/api/v1/chat/completions"

        /** Representative song length: large enough that a per-line fallback could not hide. */
        const val LINE_COUNT = 40
    }

    private fun inputLines(n: Int = LINE_COUNT): List<String> = (1..n).map { "Lyric line number $it" }

    private fun translatedLines(n: Int = LINE_COUNT): List<String> = (1..n).map { "translation $it" }

    /** A real provider whose engine is a MockEngine producing [status]/[body]. */
    private fun provider(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = envelope(translatedLines()),
        retryAfter: String? = null,
        throwable: Throwable? = null,
        apiKey: String? = TEST_KEY,
    ): Pair<OpenRouterTranslationProvider, MutableList<HttpRequestData>> {
        val seen = mutableListOf<HttpRequestData>()
        val engine =
            MockEngine { request ->
                seen += request
                throwable?.let { throw it }
                val headers =
                    if (retryAfter != null) {
                        headersOf(
                            "Content-Type" to listOf("application/json; charset=UTF-8"),
                            "Retry-After" to listOf(retryAfter),
                        )
                    } else {
                        headersOf("Content-Type" to listOf("application/json; charset=UTF-8"))
                    }
                respond(content = body, status = status, headers = headers)
            }

        val provider =
            OpenRouterTranslationProvider(
                configProvider = {
                    TranslationConfig(
                        apiKey = apiKey,
                        model = MODEL,
                        baseUrl = ENDPOINT,
                        targetLanguage = "English",
                    )
                },
                engine = engine,
            )
        return provider to seen
    }

    /** Wraps a raw JSON value inside an OpenAI-compatible chat-completions envelope. */
    private fun envelope(rawContentJson: String): String =
        """{"choices":[{"index":0,"message":{"role":"assistant","content":$rawContentJson}}]}"""

    /**
     * OpenRouter returns `message.content` as a JSON **string** whose value is the array text, so
     * the array is quoted into that string rather than embedded as a raw JSON value.
     */
    private fun envelope(elements: List<String>): String =
        envelope(quote(elements.joinToString(",", prefix = "[", postfix = "]") { quote(it) }))

    private fun quote(raw: String): String =
        "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun requestBody(request: HttpRequestData): String =
        (request.body as? TextContent)?.text ?: request.body.toString()

    // ────────────────────────────── success ──────────────────────────────

    @Test
    fun fortyLinesProduceExactlyOneRequest() {
        val (provider, seen) = provider()

        val result = runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }

        assertTrue("result=$result", result.isSuccess)
        assertEquals(translatedLines(), result.getOrThrow().lines)
        assertEquals(
            "40 lines must be ONE request — a per-line fallback must never hide here",
            1,
            seen.size,
        )
    }

    @Test
    fun theRequestCarriesModelEndpointAuthorizationAndContract() {
        val (provider, seen) = provider()
        runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }

        val request = seen.single()
        assertEquals(ENDPOINT, request.url.toString())
        assertEquals("Bearer $TEST_KEY", request.headers["Authorization"])

        val headerType = request.headers["Content-Type"].orEmpty()
        val bodyType = request.body.contentType?.toString().orEmpty()
        assertTrue(
            "request must be JSON (headers=$headerType, body=$bodyType)",
            headerType.contains("application/json") || bodyType.contains("application/json"),
        )

        val body = requestBody(request)
        assertTrue("body must carry the configured model", body.contains(MODEL))
        assertTrue("body must demand a JSON array", body.contains("JSON array"))
        assertTrue("body must contain the lyric lines", body.contains("Lyric line number 1"))
        assertFalse(
            "timestamps must never be handed to the model to reconstruct",
            body.contains("[00:"),
        )
    }

    @Test
    fun theKeyIsNotPlacedInTheUrlOrQueryParameters() {
        val (provider, seen) = provider()
        runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }

        val request = seen.single()
        assertFalse("key must not appear in the URL", request.url.toString().contains(TEST_KEY))
        assertFalse(
            "no key-like query parameter may exist",
            request.url.parameters.names().any { it.contains("key", ignoreCase = true) },
        )
    }

    // ────────────────────────── missing configuration ──────────────────────────

    @Test
    fun aMissingApiKeyFailsBeforeAnyRequest() {
        val (provider, seen) = provider(apiKey = null)
        val result = runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }
        assertEquals(TranslationError.Kind.MissingApiKey, kindOf(result))
        assertEquals(0, seen.size)
    }

    // ────────────────────────── HTTP status mapping ──────────────────────────

    @Test
    fun status401BecomesUnauthorized() {
        val (provider, seen) = provider(status = HttpStatusCode.Unauthorized, body = "")
        val result = runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }
        assertEquals(
            "401 must map to Unauthorized — this is the mapping the Any-inference bug killed",
            TranslationError.Kind.Unauthorized,
            kindOf(result),
        )
        assertEquals("a failure must not retry", 1, seen.size)
    }

    @Test
    fun status403BecomesForbidden() {
        val (provider, seen) = provider(status = HttpStatusCode.Forbidden, body = "")
        val result = runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }
        assertEquals(TranslationError.Kind.Forbidden, kindOf(result))
        assertEquals(1, seen.size)
    }

    @Test
    fun status429BecomesRateLimitedAndSurfacesRetryAfter() {
        val (provider, seen) =
            provider(status = HttpStatusCode.TooManyRequests, body = "", retryAfter = "45")
        val result = runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }

        assertEquals(TranslationError.Kind.RateLimited, kindOf(result))
        val error = errorOf(result) as? TranslationError.RateLimited
        assertEquals("Retry-After must reach the caller", 45L, error?.retryAfterSeconds)

        // Bounded: being rate limited must never trigger a request storm.
        assertEquals(1, seen.size)
    }

    @Test
    fun status500BecomesProviderUnavailable() {
        val (provider, seen) = provider(status = HttpStatusCode.InternalServerError, body = "")
        val result = runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }
        assertEquals(TranslationError.Kind.ProviderUnavailable, kindOf(result))
        assertEquals(1, seen.size)
    }

    @Test
    fun status503BecomesProviderUnavailable() {
        val (provider, seen) = provider(status = HttpStatusCode.ServiceUnavailable, body = "")
        val result = runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }
        assertEquals(TranslationError.Kind.ProviderUnavailable, kindOf(result))
        assertEquals(1, seen.size)
    }

    // ────────────────────────── network / timeout ──────────────────────────

    @Test
    fun aSocketTimeoutBecomesTimeout() {
        val (provider, _) = provider(throwable = SocketTimeoutException("read timed out"))
        val result = runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }
        assertEquals(TranslationError.Kind.Timeout, kindOf(result))
    }

    @Test
    fun anUnknownHostBecomesNetworkUnavailable() {
        val (provider, _) = provider(throwable = UnknownHostException("no dns"))
        val result = runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }
        assertEquals(TranslationError.Kind.NetworkUnavailable, kindOf(result))
    }

    // ────────────────────────── malformed responses ──────────────────────────

    @Test
    fun emptyChoicesIsRejected() {
        val (provider, _) = provider(body = """{"choices":[]}""")
        assertEquals(
            TranslationError.Kind.InvalidResponse,
            kindOf(runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }),
        )
    }

    @Test
    fun aChoiceWithoutAMessageIsRejected() {
        val (provider, _) = provider(body = """{"choices":[{"index":0}]}""")
        assertEquals(
            TranslationError.Kind.InvalidResponse,
            kindOf(runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }),
        )
    }

    @Test
    fun aMessageWithoutContentIsRejected() {
        val (provider, _) = provider(body = """{"choices":[{"message":{"role":"assistant"}}]}""")
        assertEquals(
            TranslationError.Kind.InvalidResponse,
            kindOf(runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }),
        )
    }

    @Test
    fun emptyContentIsRejected() {
        val (provider, _) = provider(body = envelope(quote("")))
        val result = runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }
        assertTrue("empty model output must never be accepted", result.isFailure)
    }

    @Test
    fun nonJsonContentIsRejected() {
        val (provider, _) = provider(body = envelope(quote("this is not JSON")))
        assertEquals(
            TranslationError.Kind.InvalidResponse,
            kindOf(runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }),
        )
    }

    @Test
    fun aJsonObjectInsteadOfAnArrayIsRejected() {
        val (provider, _) = provider(body = envelope(quote("""{"translation":"hello"}""")))
        assertEquals(
            TranslationError.Kind.InvalidResponse,
            kindOf(runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }),
        )
    }

    @Test
    fun aWrongLineCountIsRejectedAndNeverPersisted() {
        val (provider, _) = provider(body = envelope(listOf("only one")))
        val result =
            runBlocking { provider.translate(TranslationRequest(inputLines(LINE_COUNT), "English")) }
        assertEquals(
            "a short translation must be rejected, not truncated into place",
            TranslationError.Kind.InvalidTranslation,
            kindOf(result),
        )
    }

    @Test
    fun aNonStringElementIsRejected() {
        val (provider, _) = provider(body = envelope(quote("""["hello", 123, "world"]""")))
        val result = runBlocking { provider.translate(TranslationRequest(inputLines(3), "English")) }
        assertEquals(TranslationError.Kind.InvalidTranslation, kindOf(result))
    }

    @Test
    fun anEmptyArrayIsRejectedWhenLyricsWereSupplied() {
        val (provider, _) = provider(body = envelope(emptyList()))
        val result = runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }
        assertEquals(TranslationError.Kind.InvalidTranslation, kindOf(result))
    }

    // ────────────────────────── secret handling ──────────────────────────

    @Test
    fun failuresNeverExposeTheApiKey() {
        val scenarios =
            listOf(
                provider(status = HttpStatusCode.Unauthorized, body = ""),
                provider(status = HttpStatusCode.TooManyRequests, body = "", retryAfter = "5"),
                provider(status = HttpStatusCode.InternalServerError, body = ""),
                provider(throwable = SocketTimeoutException("boom")),
            )
        for ((provider, _) in scenarios) {
            val result = runBlocking { provider.translate(TranslationRequest(inputLines(), "English")) }
            val text = result.exceptionOrNull()?.toString().orEmpty()
            assertFalse("an error must never carry the API key: $text", text.contains(TEST_KEY))
        }
    }

    // ────────────────────────── helpers ──────────────────────────

    private fun errorOf(result: Result<*>): TranslationError? =
        (result.exceptionOrNull() as? TranslationContractException)?.error

    private fun kindOf(result: Result<*>): TranslationError.Kind? = errorOf(result)?.kind
}
