package com.novamusic.app.translation

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compliance tests for the constraints that are easy to violate by accident:
 *
 * - the model is the FREE router, and the paid router is never requested;
 * - the Authorization header never reaches a log sink;
 * - 402 has its own outcome and is not retried;
 * - transient failures are retried exactly once; 429 is never retried.
 *
 * Offline and deterministic: ktor-client-mock, one fake key held only in test memory.
 */
class OpenRouterFreeModelComplianceTest {
    private companion object {
        /** Clearly fake — never a real OpenRouter credential. */
        const val TEST_KEY = "sk-or-v1-compliance-test-only"
        const val ENDPOINT = "https://openrouter.test/api/v1/chat/completions"
        val LINES = listOf("first line", "second line", "third line", "fourth line")
        val TRANSLATED = LINES.map { "$it-trad" }
    }

    private fun quote(raw: String): String =
        "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /** An OpenAI-compatible envelope whose content is a JSON array of [elements]. */
    private fun envelope(
        elements: List<String>,
        model: String = "test-served/free",
    ): String {
        val array = elements.joinToString(",", prefix = "[", postfix = "]") { quote(it) }
        return """{"model":${quote(model)},"choices":[{"index":0,"message":{"role":"assistant","content":${quote(array)}}}]}"""
    }

    private fun requestBody(request: HttpRequestData): String =
        (request.body as? TextContent)?.text ?: request.body.toString()

    private fun providerWith(
        seen: MutableList<HttpRequestData>,
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = envelope(TRANSLATED),
        log: (String) -> Unit = {},
    ): OpenRouterTranslationProvider {
        val engine =
            MockEngine { request ->
                seen += request
                respond(
                    content = body,
                    status = status,
                    headers = headersOf("Content-Type" to listOf("application/json")),
                )
            }
        return OpenRouterTranslationProvider(
            configProvider = { TranslationConfig(apiKey = TEST_KEY, baseUrl = ENDPOINT) },
            engine = engine,
            log = log,
        )
    }

    // ─────────────────────────── the model ───────────────────────────

    @Test
    fun theModelConstantIsTheFreeRouter() {
        assertEquals("openrouter/free", OPENROUTER_FREE_MODEL)
    }

    @Test
    fun thePaidRouterIsNeverRequested() {
        val seen = mutableListOf<HttpRequestData>()
        val provider = providerWith(seen)

        runBlocking { provider.translate(TranslationRequest(LINES, "Spanish")) }

        val body = requestBody(seen.single())
        assertTrue("body must request the free router", body.contains("openrouter/free"))
        assertFalse(
            "openrouter/auto is a paid router and must never be requested",
            body.contains("openrouter/auto"),
        )
    }

    @Test
    fun aConfiguredModelCannotOverrideTheFreeRouter() {
        // Even a stale configuration naming another model must not change the request.
        val seen = mutableListOf<HttpRequestData>()
        val engine =
            MockEngine { request ->
                seen += request
                respond(
                    content = envelope(TRANSLATED),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type" to listOf("application/json")),
                )
            }
        val provider =
            OpenRouterTranslationProvider(
                configProvider = {
                    TranslationConfig(
                        apiKey = TEST_KEY,
                        model = "some-vendor/expensive-model",
                        baseUrl = ENDPOINT,
                    )
                },
                engine = engine,
            )

        runBlocking { provider.translate(TranslationRequest(LINES, "Spanish")) }

        val body = requestBody(seen.single())
        assertTrue(body.contains("openrouter/free"))
        assertFalse(body.contains("some-vendor/expensive-model"))
    }

    // ─────────────────────────── logging ───────────────────────────

    @Test
    fun theAuthorizationHeaderIsNeverLogged() {
        val seen = mutableListOf<HttpRequestData>()
        val logs = mutableListOf<String>()
        val provider =
            providerWith(
                seen = seen,
                body = envelope(TRANSLATED, model = "served-by/free"),
                log = { logs += it },
            )

        runBlocking { provider.translate(TranslationRequest(LINES, "Spanish")) }

        // The header really was sent — otherwise this would pass for the wrong reason.
        assertEquals("Bearer $TEST_KEY", seen.single().headers["Authorization"])

        assertTrue("expected diagnostics to be emitted", logs.isNotEmpty())
        logs.forEach { message ->
            assertFalse("a log line must never contain the key", message.contains(TEST_KEY))
            assertFalse("a log line must never contain the header", message.contains("Bearer"))
            assertFalse(
                "a log line must never contain the header name",
                message.contains("Authorization"),
            )
        }
    }

    @Test
    fun theServedModelIsLoggedForDebugging() {
        val logs = mutableListOf<String>()
        val provider =
            providerWith(
                seen = mutableListOf(),
                body = envelope(TRANSLATED, model = "served-by/free"),
                log = { logs += it },
            )

        runBlocking { provider.translate(TranslationRequest(LINES, "Spanish")) }

        assertTrue(
            "the model that actually served the request must be logged (logs=$logs)",
            logs.any { it.contains("served-by/free") },
        )
    }

    // ─────────────────────────── 402 ───────────────────────────

    @Test
    fun status402BecomesInsufficientBalanceAndIsNotRetried() {
        val seen = mutableListOf<HttpRequestData>()
        val provider =
            providerWith(
                seen = seen,
                status = HttpStatusCode.PaymentRequired,
                body = """{"error":{"message":"insufficient credits"}}""",
            )

        val result = runBlocking { provider.translate(TranslationRequest(LINES, "Spanish")) }

        val error = (result.exceptionOrNull() as? TranslationContractException)?.error
        assertEquals(TranslationError.Kind.InsufficientBalance, error?.kind)
        assertEquals("a 402 is deterministic — retrying cannot change the balance", 1, seen.size)
    }

    // ─────────────────────────── retry policy ───────────────────────────

    @Test
    fun aCountMismatchIsRetriedExactlyOnceThenSucceeds() {
        val seen = mutableListOf<HttpRequestData>()
        var attempt = 0
        val engine =
            MockEngine { request ->
                seen += request
                attempt += 1
                // First reply is one line short; the retry is correct.
                val body = if (attempt == 1) envelope(TRANSLATED.dropLast(1)) else envelope(TRANSLATED)
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type" to listOf("application/json")),
                )
            }
        val provider =
            OpenRouterTranslationProvider(
                configProvider = { TranslationConfig(apiKey = TEST_KEY, baseUrl = ENDPOINT) },
                engine = engine,
            )

        val result = runBlocking { provider.translate(TranslationRequest(LINES, "Spanish")) }

        assertTrue("result=$result", result.isSuccess)
        assertEquals(TRANSLATED, result.getOrThrow().lines)
        assertEquals(2, seen.size)
    }

    @Test
    fun aPersistentCountMismatchIsRetriedOnceAndThenFails() {
        val seen = mutableListOf<HttpRequestData>()
        val provider = providerWith(seen, body = envelope(TRANSLATED.dropLast(1)))

        val result = runBlocking { provider.translate(TranslationRequest(LINES, "Spanish")) }

        assertTrue(result.isFailure)
        assertEquals("one retry only — never a loop", 2, seen.size)
    }

    @Test
    fun aServerErrorIsRetriedOnceAndThenFails() {
        val seen = mutableListOf<HttpRequestData>()
        val provider =
            providerWith(
                seen = seen,
                status = HttpStatusCode.InternalServerError,
                body = "upstream failure",
            )

        val result = runBlocking { provider.translate(TranslationRequest(LINES, "Spanish")) }

        assertTrue(result.isFailure)
        assertEquals(2, seen.size)
    }

    @Test
    fun aRateLimitIsNeverRetried() {
        val seen = mutableListOf<HttpRequestData>()
        val engine =
            MockEngine { request ->
                seen += request
                respond(
                    content = """{"error":{"message":"rate limited"}}""",
                    status = HttpStatusCode.TooManyRequests,
                    headers =
                        headersOf(
                            "Content-Type" to listOf("application/json"),
                            "Retry-After" to listOf("120"),
                        ),
                )
            }
        val provider =
            OpenRouterTranslationProvider(
                configProvider = { TranslationConfig(apiKey = TEST_KEY, baseUrl = ENDPOINT) },
                engine = engine,
            )

        val result = runBlocking { provider.translate(TranslationRequest(LINES, "Spanish")) }

        val error = (result.exceptionOrNull() as? TranslationContractException)?.error
        assertEquals(TranslationError.Kind.RateLimited, error?.kind)
        assertEquals("a 429 means back off, not retry", 1, seen.size)
    }
}
